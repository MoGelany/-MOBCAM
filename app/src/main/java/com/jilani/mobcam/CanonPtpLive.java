package com.jilani.mobcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Live view straight from a Canon EOS camera over its USB-C port (no HDMI / capture card),
 * using Canon's PTP live-view commands (the same ones EOS Utility uses). Each live-view
 * frame arrives as a JPEG, which is drawn into the monitor's SurfaceTexture.
 */
public class CanonPtpLive {

    public interface Listener {
        void onPtpStarted(String name);
        void onPtpFrameSize(int w, int h);
        void onPtpError(String msg);
        void onPtpStopped();
    }

    public static final int CANON_VID = 0x04A9;
    private static final String TAG = "MOBCAM-PTP";

    private static final int OP_OPEN_SESSION = 0x1002;
    private static final int OP_CLOSE_SESSION = 0x1003;
    private static final int OP_EOS_SET_PROP = 0x9110;
    private static final int OP_EOS_SET_REMOTE = 0x9114;
    private static final int OP_EOS_SET_EVENT = 0x9115;
    private static final int OP_EOS_GET_EVENT = 0x9116;
    private static final int OP_EOS_GET_VIEWFINDER = 0x9153;

    private static final int PROP_EVF_OUTPUT = 0xD1B0;
    private static final int PROP_EVF_MODE = 0xD1B3;

    private static final int RC_OK = 0x2001;
    private static final int RC_SESSION_OPEN = 0x201E;
    private static final int RC_BUSY = 0x2019;
    private static final int RC_NOT_READY = 0xA102;

    private final UsbManager usb;
    private final Listener listener;
    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint in, out;
    private volatile boolean running;
    private Thread thread;
    private int tid = 1;
    private volatile SurfaceTexture target;
    private Surface surface;
    private int bufW, bufH;
    private final byte[] rx = new byte[Build.VERSION.SDK_INT >= 28 ? 512 * 1024 : 16384];

    public CanonPtpLive(UsbManager usb, Listener listener) {
        this.usb = usb;
        this.listener = listener;
    }

    /** A Canon camera exposing a still-image (PTP) interface and no UVC video interface. */
    public static boolean isCanonPtp(UsbDevice d) {
        if (d == null || d.getVendorId() != CANON_VID) return false;
        boolean ptp = false;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            int c = d.getInterface(i).getInterfaceClass();
            if (c == UsbConstants.USB_CLASS_VIDEO) return false;
            if (c == UsbConstants.USB_CLASS_STILL_IMAGE) ptp = true;
        }
        return ptp;
    }

    public boolean isRunning() { return running; }
    public UsbDevice getDevice() { return device; }

    public synchronized void setTarget(SurfaceTexture st) {
        if (st != target) releaseSurface();
        target = st;
    }

    public void start(UsbDevice d) {
        if (running) return;
        device = d;
        running = true;
        thread = new Thread(this::loop, "canon-ptp");
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(1500); } catch (InterruptedException ignored) { }
        }
        thread = null;
    }

    // ------------------------------------------------------------------ worker

    private void loop() {
        String err = null;
        try {
            open();
            listener.onPtpStarted(device.getProductName() != null ? device.getProductName() : "Canon");
            int frames = 0, misses = 0;
            while (running) {
                Resp r = transact(OP_EOS_GET_VIEWFINDER, null, 0x00200000, 0, 0);
                if (r.code == RC_OK && r.data != null) {
                    misses = 0;
                    drawJpeg(r.data);
                } else if (r.code == RC_NOT_READY || r.code == RC_BUSY) {
                    if (++misses == 60) enableEvf(); // live view dropped (e.g. menu opened) – re-arm it
                    Thread.sleep(25);
                } else {
                    Log.w(TAG, "viewfinder rc=0x" + Integer.toHexString(r.code));
                    Thread.sleep(50);
                }
                if (++frames % 15 == 0) transact(OP_EOS_GET_EVENT, null); // keeps EOS bodies happy
            }
        } catch (Exception e) {
            Log.e(TAG, "ptp", e);
            if (running) err = e.getMessage();
        }
        try { shutdown(); } catch (Exception ignored) { }
        releaseSurface();
        running = false;
        if (err != null) listener.onPtpError(err);
        listener.onPtpStopped();
    }

    private void open() throws Exception {
        intf = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface it = device.getInterface(i);
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) { intf = it; break; }
        }
        if (intf == null) throw new Exception("الكاميرا مش ظاهرة كـ PTP");
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep; else out = ep;
        }
        conn = usb.openDevice(device);
        if (conn == null) throw new Exception("مقدرتش أفتح الكاميرا (صلاحية USB؟)");
        if (!conn.claimInterface(intf, true)) throw new Exception("الكاميرا مشغولة بتطبيق تاني");

        tid = 1;
        Resp r = transact(OP_OPEN_SESSION, null, 1);
        if (r.code == RC_SESSION_OPEN) { // left open by a previous run
            transact(OP_CLOSE_SESSION, null);
            r = transact(OP_OPEN_SESSION, null, 1);
        }
        if (r.code != RC_OK) throw new Exception("OpenSession 0x" + Integer.toHexString(r.code));
        transact(OP_EOS_SET_REMOTE, null, 1);
        transact(OP_EOS_SET_EVENT, null, 1);
        for (int i = 0; i < 3; i++) transact(OP_EOS_GET_EVENT, null);
        enableEvf();
        Thread.sleep(300);
    }

    private void enableEvf() throws Exception {
        setProp(PROP_EVF_MODE, 1);
        setProp(PROP_EVF_OUTPUT, 2); // 2 = PC (host) output
        transact(OP_EOS_GET_EVENT, null);
    }

    private void shutdown() throws Exception {
        if (conn == null) return;
        try {
            setProp(PROP_EVF_OUTPUT, 1); // hand live view back to the camera's own screen
            transact(OP_EOS_SET_REMOTE, null, 0);
            transact(OP_CLOSE_SESSION, null);
        } finally {
            try { conn.releaseInterface(intf); } catch (Exception ignored) { }
            conn.close();
            conn = null;
        }
    }

    private void setProp(int prop, int value) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(12).putInt(prop).putInt(value);
        transact(OP_EOS_SET_PROP, b.array());
    }

    // ------------------------------------------------------------------ PTP transport

    private static class Resp { int code; byte[] data; }

    private Resp transact(int op, byte[] dataOut, int... params) throws Exception {
        int id = tid++;
        ByteBuffer c = ByteBuffer.allocate(12 + 4 * params.length).order(ByteOrder.LITTLE_ENDIAN);
        c.putInt(12 + 4 * params.length).putShort((short) 1).putShort((short) op).putInt(id);
        for (int p : params) c.putInt(p);
        send(c.array());
        if (dataOut != null) {
            ByteBuffer d = ByteBuffer.allocate(12 + dataOut.length).order(ByteOrder.LITTLE_ENDIAN);
            d.putInt(12 + dataOut.length).putShort((short) 2).putShort((short) op).putInt(id).put(dataOut);
            send(d.array());
        }
        Resp r = new Resp();
        while (true) {
            byte[] pkt = readContainer();
            ByteBuffer h = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
            int type = h.getShort(4) & 0xffff;
            int code = h.getShort(6) & 0xffff;
            if (type == 2) {
                byte[] payload = new byte[pkt.length - 12];
                System.arraycopy(pkt, 12, payload, 0, payload.length);
                r.data = payload;
            } else if (type == 3) {
                r.code = code;
                return r;
            }
        }
    }

    private void send(byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int chunk = Math.min(b.length - off, 16384);
            byte[] part = b;
            if (off != 0 || chunk != b.length) {
                part = new byte[chunk];
                System.arraycopy(b, off, part, 0, chunk);
            }
            int n = conn.bulkTransfer(out, part, chunk, 2000);
            if (n < 0) throw new Exception("انقطع الاتصال بالكاميرا");
            off += n;
        }
    }

    /** Reads one PTP container (header + payload), however many USB transfers it spans. */
    private byte[] readContainer() throws Exception {
        int n = conn.bulkTransfer(in, rx, rx.length, 3000);
        if (n < 0) throw new Exception("انقطع الاتصال بالكاميرا");
        if (n < 12) {
            n = conn.bulkTransfer(in, rx, rx.length, 3000); // skip a stray zero-length packet
            if (n < 12) throw new Exception("رد غريب من الكاميرا");
        }
        int len = ByteBuffer.wrap(rx).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        if (len <= n || len < 12 || len > 64 * 1024 * 1024) {
            byte[] r = new byte[n];
            System.arraycopy(rx, 0, r, 0, n);
            return r;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream(len);
        bo.write(rx, 0, n);
        while (bo.size() < len) {
            int m = conn.bulkTransfer(in, rx, rx.length, 3000);
            if (m < 0) throw new Exception("انقطع الاتصال بالكاميرا");
            bo.write(rx, 0, m);
        }
        return bo.toByteArray();
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
                listener.onPtpFrameSize(w, h);
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
