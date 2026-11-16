package com.jilani.mobcam;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Sony Alpha / ZV / FX in "PC Remote" mode (USB or Wi-Fi). Live view is read from the
 * special object 0xFFFFC002; buttons are pressed with SDIO ControlDevice.
 * (Newer Sonys set to "USB Streaming" show up as a normal UVC webcam instead — that works too.)
 */
public class SonyDriver extends CameraDriver {

    private static final int OP_GET_OBJECT_INFO = 0x1008;
    private static final int OP_GET_OBJECT = 0x1009;
    private static final int OP_SDIO_CONNECT = 0x9201;
    private static final int OP_SDIO_EXT_INFO = 0x9202;
    private static final int OP_SDIO_SET_EXT_PROP = 0x9205;
    private static final int OP_SDIO_CONTROL = 0x9207;

    private static final int LIVE_OBJECT = 0xFFFFC002;

    private static final int PROP_PRIORITY = 0xD25A;
    private static final int BTN_HALF = 0xD2C1;
    private static final int BTN_FULL = 0xD2C2;
    private static final int BTN_MOVIE = 0xD2C8;
    private static final int MF_ADJUST = 0xD2D1;

    public SonyDriver(PtpTransport t) {
        super(t);
    }

    @Override public String brand() { return "Sony"; }

    @Override
    public void init() throws Exception {
        openSession();
        t.transact(OP_SDIO_CONNECT, null, 1, 0, 0);
        t.transact(OP_SDIO_CONNECT, null, 2, 0, 0);
        for (int i = 0; i < 20; i++) {
            PtpTransport.Resp r = t.transact(OP_SDIO_EXT_INFO, null, 0x12C, 1);
            if (r.data != null && r.data.length > 0) break;
            Thread.sleep(50);
        }
        t.transact(OP_SDIO_CONNECT, null, 3, 0, 0);
        t.transact(OP_SDIO_SET_EXT_PROP, new byte[]{1}, PROP_PRIORITY); // control from the app
        Thread.sleep(300);
    }

    @Override
    public byte[] frame() throws Exception {
        t.transact(OP_GET_OBJECT_INFO, null, LIVE_OBJECT);
        PtpTransport.Resp r = t.transact(OP_GET_OBJECT, null, LIVE_OBJECT);
        if (r.code == RC_OK && r.data != null && r.data.length > 8) {
            byte[] d = r.data;
            int off = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            if (off > 0 && off + 1 < d.length && (d[off] & 0xff) == 0xFF && (d[off + 1] & 0xff) == 0xD8) {
                byte[] j = new byte[d.length - off];
                System.arraycopy(d, off, j, 0, j.length);
                return j;
            }
            return d;
        }
        Thread.sleep(30); // 0x200F / 0x2009 = not ready yet
        return null;
    }

    @Override
    public void close() {
        op(OP_CLOSE_SESSION);
    }

    private int press(int button, int value) {
        return opData(OP_SDIO_CONTROL, u16(value), button);
    }

    @Override
    public boolean record(boolean start) {
        // the movie button toggles recording: press + release
        int a = press(BTN_MOVIE, 2);
        sleep(150);
        press(BTN_MOVIE, 1);
        return a == RC_OK;
    }

    @Override
    public boolean photo() {
        int a = press(BTN_HALF, 2);
        int b = press(BTN_FULL, 2);
        sleep(600);
        press(BTN_FULL, 1);
        press(BTN_HALF, 1);
        return a == RC_OK && b == RC_OK;
    }

    @Override
    public void autofocus() {
        press(BTN_HALF, 2);
        sleep(800);
        press(BTN_HALF, 1);
    }

    @Override
    public void driveFocus(boolean near, int step) {
        int v = Math.min(7, step * 2 - 1); // 1, 3, 5
        opData(OP_SDIO_CONTROL, u16(near ? -v : v), MF_ADJUST);
    }
}
