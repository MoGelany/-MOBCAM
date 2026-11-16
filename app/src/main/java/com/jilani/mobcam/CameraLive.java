package com.jilani.mobcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A live PTP session with a Canon or Sony camera (USB-C or Wi-Fi): pulls live-view JPEGs and
 * draws them into the monitor's SurfaceTexture, and runs queued jobs (record, photo, focus,
 * card browsing) on the same thread between frames.
 */
public class CameraLive {

    public interface Listener {
        void onLiveStarted(String how, String brand);
        void onLiveFrameSize(int w, int h);
        void onLiveError(String msg);
        void onLiveStopped();
        void onRecordState(boolean recording, boolean ok);
        void onModeChanged(boolean movie, boolean ok);
        void onPhotoTaken(boolean ok);
        void onPhotoReady(PtpMedia.Item it, File file, Bitmap bmp);
    }

    public interface Opener {
        PtpTransport open() throws Exception;
    }

    /** Work that needs the camera connection; runs on the session thread (live view pauses meanwhile). */
    public interface Job {
        void run(PtpTransport t) throws Exception;
    }

    private static final String TAG = "MOBCAM-PTP";

    private final Listener listener;
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private PtpTransport t;
    private CameraDriver driver;
    private volatile boolean running, recording, movieMode;
    private Thread thread;
    private String how = "", brand = "";
    private Object source;
    private File cacheDir;

    private volatile SurfaceTexture target;
    private Surface surface;
    private int bufW, bufH;

    public CameraLive(Listener listener, File cacheDir) {
        this.listener = listener;
        this.cacheDir = cacheDir;
    }

    public boolean isRunning() { return running; }
    public boolean isRecording() { return recording; }
    public boolean isMovieMode() { return movieMode; }
    public String how() { return how; }
    public String brand() { return brand; }
    public Object source() { return source; }

    public synchronized void setTarget(SurfaceTexture st) {
        if (st != target) releaseSurface();
        target = st;
    }

    public void start(String how, Object source, int usbVendor, Opener opener) {
        if (running) return;
        this.how = how;
        this.source = source;
        brand = "";
        recording = false;
        queue.clear();
        running = true;
        thread = new Thread(() -> loop(opener, usbVendor), "camera-live");
        thread.start();
    }

    public void stop() {
        running = false;
        Thread th = thread;
        if (th != null && th != Thread.currentThread()) {
            try { th.join(2500); } catch (InterruptedException ignored) { }
        }
        thread = null;
    }

    /** Runs a job on the session thread; errors are logged, not thrown. */
    public void submit(Job j) {
        queue.add(() -> {
            try { j.run(t); } catch (Exception e) { Log.w(TAG, "job", e); }
        });
    }

    // ------------------------------------------------------------------ buttons

    public void toggleRecord() {
        final boolean start = !recording;
        queue.add(() -> {
            boolean ok = driver.record(start);
            if (ok) recording = start;
            listener.onRecordState(recording, ok);
        });
    }

    public void setMovieMode(boolean movie) {
        queue.add(() -> {
            boolean ok = driver.setMovieMode(movie);
            if (ok) movieMode = movie;
            listener.onModeChanged(movieMode, ok);
        });
    }

    /** Takes a photo; when {@code showAfter}, finds the new file on the card and hands it to onPhotoReady. */
    public void takePhoto(boolean showAfter) {
        queue.add(() -> {
            int before = Integer.MIN_VALUE;
            if (showAfter) {
                try {
                    int[] h = PtpMedia.allHandles(t);
                    before = h.length > 0 ? h[0] : 0;
                } catch (Exception ignored) { }
            }
            boolean ok = driver.photo();
            listener.onPhotoTaken(ok);
            if (!ok || !showAfter || before == Integer.MIN_VALUE) return;
            fetchNewPhoto(before);
        });
    }

    private void fetchNewPhoto(int before) {
        try {
            PtpMedia.Item pick = null;
            for (int tries = 0; tries < 16 && pick == null; tries++) {
                CameraDriver.sleep(400);
                for (int h : PtpMedia.allHandles(t)) {
                    if (Integer.compareUnsigned(h, before) <= 0) break;
                    PtpMedia.Item it = PtpMedia.info(t, h);
                    if (it == null || it.video) continue;
                    // prefer the JPEG/HEIF of a RAW+JPEG pair
                    if (pick == null || !isRaw(it.name)) pick = it;
                }
            }
            if (pick == null) return;
            File f = new File(cacheDir, "last_" + pick.name);
            PtpMedia.download(t, pick, f, null);
            Bitmap b = PtpMedia.decodeForScreen(f, 2400);
            listener.onPhotoReady(pick, f, b);
        } catch (Exception e) {
            Log.w(TAG, "show photo", e);
        }
    }

    private static boolean isRaw(String n) {
        String e = PtpMedia.ext(n);
        return e.equals("CR3") || e.equals("CR2") || e.equals("ARW");
    }

    public void autofocus() { queue.add(() -> driver.autofocus()); }

    public void driveFocus(boolean near, int step) { queue.add(() -> driver.driveFocus(near, step)); }

    // ------------------------------------------------------------------ worker

    private void loop(Opener opener, int usbVendor) {
        String err = null;
        try {
            t = opener.open();
            driver = CameraDriver.detect(t, usbVendor);
            brand = driver.brand();
            driver.init();
            listener.onLiveStarted(how, brand);
            int frames = 0;
            while (running) {
                Runnable job;
                while (running && (job = queue.poll()) != null) job.run();
                byte[] jpg = driver.frame();
                if (jpg != null) drawJpeg(jpg);
                driver.tick(++frames);
            }
        } catch (Exception e) {
            Log.e(TAG, "live", e);
            if (running) err = e.getMessage();
        }
        if (t != null) {
            if (driver != null) {
                try { driver.close(); } catch (Exception ignored) { }
            }
            t.close();
            t = null;
        }
        driver = null;
        releaseSurface();
        recording = false;
        running = false;
        if (err != null) listener.onLiveError(err);
        listener.onLiveStopped();
    }

    // ------------------------------------------------------------------ drawing

    private void drawJpeg(byte[] d) {
        int s = -1, e = -1;
        for (int i = 0; i < d.length - 1; i++) {
            if ((d[i] & 0xff) == 0xFF && (d[i + 1] & 0xff) == 0xD8) { s = i; break; }
        }
        if (s < 0) return;
        for (int i = d.length - 2; i > s; i--) {
            if ((d[i] & 0xff) == 0xFF && (d[i + 1] & 0xff) == 0xD9) { e = i + 2; break; }
        }
        if (e < 0) e = d.length;
        Bitmap bmp = BitmapFactory.decodeByteArray(d, s, e - s);
        if (bmp == null) return;
        synchronized (this) {
            SurfaceTexture st = target;
            if (st == null) { bmp.recycle(); return; }
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (surface == null || w != bufW || h != bufH) {
                releaseSurface();
                st.setDefaultBufferSize(w, h);
                surface = new Surface(st);
                bufW = w;
                bufH = h;
                listener.onLiveFrameSize(w, h);
            }
            try {
                Canvas cv = Build.VERSION.SDK_INT >= 23 ? surface.lockHardwareCanvas() : surface.lockCanvas(null);
                cv.drawBitmap(bmp, null, new Rect(0, 0, w, h), null);
                surface.unlockCanvasAndPost(cv);
            } catch (Exception ex) {
                Log.w(TAG, "draw", ex);
                releaseSurface();
            }
        }
        bmp.recycle();
    }

    private synchronized void releaseSurface() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        bufW = bufH = 0;
    }
}
