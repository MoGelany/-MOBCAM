package com.jilani.mobcam;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Vendor-specific half of a PTP camera session (live view frames + remote buttons). */
public abstract class CameraDriver {

    static final String TAG = "MOBCAM-PTP";

    public static final int RC_OK = 0x2001;
    static final int RC_SESSION_OPEN = 0x201E;

    static final int OP_GET_DEVICE_INFO = 0x1001;
    static final int OP_OPEN_SESSION = 0x1002;
    static final int OP_CLOSE_SESSION = 0x1003;

    protected final PtpTransport t;

    protected CameraDriver(PtpTransport t) {
        this.t = t;
    }

    public abstract String brand();
    public abstract void init() throws Exception;
    /** Next live-view JPEG (may contain a header before the JPEG), or null if not ready yet. */
    public abstract byte[] frame() throws Exception;
    public void tick(int frameNo) throws Exception { }
    public abstract void close();

    public abstract boolean record(boolean start);
    public abstract boolean photo();
    public abstract void autofocus();
    public abstract void driveFocus(boolean near, int step);
    /** Switch the camera between stills and movie. Returns false if this camera can't do it remotely. */
    public boolean setMovieMode(boolean movie) { return false; }

    // ------------------------------------------------------------------ helpers

    protected void openSession() throws Exception {
        PtpTransport.Resp r = t.transact(OP_OPEN_SESSION, null, 1);
        if (r.code == RC_SESSION_OPEN) { // left open by a previous run
            t.transact(OP_CLOSE_SESSION, null);
            r = t.transact(OP_OPEN_SESSION, null, 1);
        }
        if (r.code != RC_OK) throw new Exception("OpenSession 0x" + Integer.toHexString(r.code));
    }

    protected int op(int code, int... params) {
        try {
            return t.transact(code, null, params).code;
        } catch (Exception e) {
            Log.w(TAG, "op " + Integer.toHexString(code), e);
            return -1;
        }
    }

    protected int opData(int code, byte[] data, int... params) {
        try {
            return t.transact(code, data, params).code;
        } catch (Exception e) {
            Log.w(TAG, "op " + Integer.toHexString(code), e);
            return -1;
        }
    }

    static byte[] u16(int v) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) v).array();
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** Picks the right driver from the camera's PTP DeviceInfo (works the same over USB and Wi-Fi). */
    public static CameraDriver detect(PtpTransport t, int usbVendor) throws Exception {
        boolean sony = usbVendor == 0x054C, canon = usbVendor == 0x04A9;
        if (!sony && !canon) {
            PtpTransport.Resp r = t.transact(OP_GET_DEVICE_INFO, null);
            if (r.data != null && r.data.length > 6) {
                int ext = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN).getInt(2);
                String txt = new String(r.data, "UTF-16LE") + new String(r.data, 1, r.data.length - 1, "UTF-16LE");
                sony = ext == 0x11 || txt.contains("Sony") || txt.contains("SONY");
                canon = ext == 0x0B || txt.contains("Canon");
            }
        }
        if (sony) return new SonyDriver(t);
        return new CanonDriver(t);
    }
}
