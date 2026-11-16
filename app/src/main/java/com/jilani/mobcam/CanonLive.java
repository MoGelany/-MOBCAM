package com.jilani.mobcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Live view + remote control of a Canon EOS camera over PTP (USB-C or Wi-Fi), using the
 * same commands EOS Utility uses. Each live-view frame arrives as a JPEG and is drawn into
 * the monitor's SurfaceTexture. Record / focus commands are queued and run between frames.
 */
public class CanonLive {

    public interface Listener {
        void onLiveStarted(String how);
        void onLiveFrameSize(int w, int h);
        void onLiveError(String msg);
        void onLiveStopped();
        void onRecordState(boolean recording, boolean ok);
    }

    public interface Opener {
        PtpTransport open() throws Exception;
    }

    private static final String TAG = "MOBCAM-PTP";

    private static final int OP_OPEN_SESSION = 0x1002;
    private static final int OP_CLOSE_SESSION = 0x1003;
    private static final int OP_SET_PROP = 0x9110;
    private static final int OP_SET_REMOTE = 0x9114;
    private static final int OP_SET_EVENT = 0x9115;
    private static final int OP_GET_EVENT = 0x9116;
    private static final int OP_OLC_INFO = 0x913D;
    private static final int OP_VIEWFINDER = 0x9153;
    private static final int OP_DO_AF = 0x9154;
    private static final int OP_DRIVE_LENS = 0x9155;
    private static final int OP_AF_CANCEL = 0x9160;

    private static final int PROP_EVF_OUTPUT = 0xD1B0;
    private static final int PROP_EVF_MODE = 0xD1B1;
    private static final int PROP_RECORD = 0xD1B8;

    private static final int RC_OK = 0x2001;
    private static final int RC_SESSION_OPEN = 0x201E;
    private static final int RC_BUSY = 0x2019;
    private static final int RC_NOT_READY = 0xA102;

    private final Listener listener;
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private PtpTransport t;
    private volatile boolean running;
    private volatile boolean recording;
    private Thread thread;
    private String how = "";
    private Object source;

    private volatile SurfaceTexture target;
    private Surface surface;
    private int bufW, bufH;

    public CanonLive(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() { return running; }
    public boolean isRecording() { return recording; }
    public String how() { return how; }
    /** Whatever identifies the connection (the UsbDevice, or the IP string). */
    public Object source() { return source; }

    public synchronized void setTarget(SurfaceTexture st) {
        if (st != target) releaseSurface();
        target = st;
    }

    public void start(String how, Object source, Opener opener) {
        if (running) return;
        this.how = how;
        this.source = source;
        recording = false;
        queue.clear();
        running = true;
        thread = new Thread(() -> loop(opener), "canon-live");
        thread.start();
    }

    public void stop() {
        running = false;
        Thread th = thread;
        if (th != null && th != Thread.currentThread()) {
            try { th.join(2000); } catch (InterruptedException ignored) { }
        }
        thread = null;
    }

    // ------------------------------------------------------------------ remote control

    /** Starts / stops movie recording to the camera's card (camera must be in movie mode). */
    public void toggleRecord() {
        final boolean start = !recording;
        queue.add(() -> {
            boolean ok = setProp(PROP_RECORD, start ? 4 : 0);
            if (ok) recording = start;
            listener.onRecordState(recording, ok);
        });
    }

    /** Autofocus at the camera's current AF point (like a half-press). */
    public void autofocus() {
        queue.add(() -> {
            tr(OP_DO_AF, null, 0);
            sleepQuiet(700);
            tr(OP_AF_CANCEL, null);
        });
    }

    /** Nudges focus: step 1..3 (small..large), near = towards the camera. Lens must be on AF. */
    public void driveFocus(boolean near, int step) {
        queue.add(() -> tr(OP_DRIVE_LENS, null, (near ? 0 : 0x8000) | step));
    }

    // ------------------------------------------------------------------ worker

    private void loop(Opener opener) {
        String err = null;
        try {
            t = opener.open();
            init();
            listener.onLiveStarted(how);
            int frames = 0, misses = 0;
            while (running) {
                Runnable job;
                while ((job = queue.poll()) != null) job.run();
                PtpTransport.Resp r = t.transact(OP_VIEWFINDER, null, 0x00200000, 0, 0);
                if (r.code == RC_OK && r.data != null) {
                    misses = 0;
                    drawJpeg(r.data);
                } else if (r.code == RC_NOT_READY || r.code == RC_BUSY) {
                    if (++misses % 60 == 0) enableEvf(); // live view dropped (menu opened, etc.) – re-arm it
                    Thread.sleep(25);
                } else {
                    Log.w(TAG, "viewfinder rc=0x" + Integer.toHexString(r.code));
                    Thread.sleep(50);
                }
                if (++frames % 15 == 0) t.transact(OP_GET_EVENT, null); // keeps EOS bodies happy
            }
        } catch (Exception e) {
            Log.e(TAG, "live", e);
            if (running) err = e.getMessage();
        }
        if (t != null) {
            try {
                if (recording) setProp(PROP_RECORD, 0);
                setProp(PROP_EVF_OUTPUT, 1); // give live view back to the camera's own screen
                t.transact(OP_SET_REMOTE, null, 0);
                t.transact(OP_CLOSE_SESSION, null);
            } catch (Exception ignored) { }
            t.close();
            t = null;
        }
        releaseSurface();
        recording = false;
        running = false;
        if (err != null) listener.onLiveError(err);
        listener.onLiveStopped();
    }

    private void init() throws Exception {
        PtpTransport.Resp r = t.transact(OP_OPEN_SESSION, null, 1);
        if (r.code == RC_SESSION_OPEN) { // left open by a previous run
            t.transact(OP_CLOSE_SESSION, null);
            r = t.transact(OP_OPEN_SESSION, null, 1);
        }
        if (r.code != RC_OK) throw new Exception("OpenSession 0x" + Integer.toHexString(r.code));
        t.transact(OP_SET_REMOTE, null, 1);
        t.transact(OP_SET_EVENT, null, 1);
        t.transact(OP_OLC_INFO, null, 0x1fff);
        for (int i = 0; i < 3; i++) t.transact(OP_GET_EVENT, null);
        enableEvf();
        Thread.sleep(300);
    }

    private void enableEvf() throws Exception {
        setPropRaw(PROP_EVF_MODE, 1);
        setPropRaw(PROP_EVF_OUTPUT, 2); // 2 = PC (host) output
        t.transact(OP_GET_EVENT, null);
    }

    private boolean setPropRaw(int prop, int value) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(12).putInt(prop).putInt(value);
        return t.transact(OP_SET_PROP, b.array()).code == RC_OK;
    }

    private boolean setProp(int prop, int value) {
        try { return setPropRaw(prop, value); } catch (Exception e) { return false; }
    }

    private void tr(int op, byte[] d, int... p) {
        try { t.transact(op, d, p); } catch (Exception e) { Log.w(TAG, "op " + Integer.toHexString(op), e); }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
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
