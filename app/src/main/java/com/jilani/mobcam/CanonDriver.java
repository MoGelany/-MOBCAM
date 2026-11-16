package com.jilani.mobcam;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Canon EOS (R5 etc.) — the commands EOS Utility uses. */
public class CanonDriver extends CameraDriver {

    private static final int OP_SET_PROP = 0x9110;
    private static final int OP_SET_REMOTE = 0x9114;
    private static final int OP_SET_EVENT = 0x9115;
    private static final int OP_GET_EVENT = 0x9116;
    private static final int OP_RELEASE_ON = 0x9128;
    private static final int OP_RELEASE_OFF = 0x9129;
    private static final int OP_MOVIE_SW_ON = 0x9133;
    private static final int OP_MOVIE_SW_OFF = 0x9134;
    private static final int OP_OLC_INFO = 0x913D;
    private static final int OP_VIEWFINDER = 0x9153;
    private static final int OP_DO_AF = 0x9154;
    private static final int OP_DRIVE_LENS = 0x9155;
    private static final int OP_AF_CANCEL = 0x9160;

    private static final int PROP_EVF_OUTPUT = 0xD1B0;
    private static final int PROP_EVF_MODE = 0xD1B1;
    private static final int PROP_RECORD = 0xD1B8;

    private static final int RC_BUSY = 0x2019;
    private static final int RC_NOT_READY = 0xA102;

    private int misses = 0;
    private boolean recording = false;

    public CanonDriver(PtpTransport t) {
        super(t);
    }

    @Override public String brand() { return "Canon"; }

    @Override
    public void init() throws Exception {
        openSession();
        t.transact(OP_SET_REMOTE, null, 1);
        t.transact(OP_SET_EVENT, null, 1);
        t.transact(OP_OLC_INFO, null, 0x1fff);
        for (int i = 0; i < 3; i++) t.transact(OP_GET_EVENT, null);
        enableEvf();
        Thread.sleep(300);
    }

    private void enableEvf() throws Exception {
        setProp(PROP_EVF_MODE, 1);
        setProp(PROP_EVF_OUTPUT, 2); // 2 = PC (host) output
        t.transact(OP_GET_EVENT, null);
    }

    private boolean setProp(int prop, int value) {
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(12).putInt(prop).putInt(value);
        return opData(OP_SET_PROP, b.array()) == RC_OK;
    }

    @Override
    public byte[] frame() throws Exception {
        PtpTransport.Resp r = t.transact(OP_VIEWFINDER, null, 0x00200000, 0, 0);
        if (r.code == RC_OK && r.data != null) {
            misses = 0;
            return r.data;
        }
        if (r.code == RC_NOT_READY || r.code == RC_BUSY) {
            if (++misses % 60 == 0) enableEvf(); // live view dropped (menu, playback, mode change) – re-arm it
            Thread.sleep(25);
        } else {
            Log.w(TAG, "viewfinder rc=0x" + Integer.toHexString(r.code));
            Thread.sleep(50);
        }
        return null;
    }

    @Override
    public void tick(int frameNo) throws Exception {
        if (frameNo % 15 == 0) t.transact(OP_GET_EVENT, null); // keeps EOS bodies happy
    }

    @Override
    public void close() {
        if (recording) setProp(PROP_RECORD, 0);
        setProp(PROP_EVF_OUTPUT, 1); // give live view back to the camera's own screen
        op(OP_SET_REMOTE, 0);
        op(OP_CLOSE_SESSION);
    }

    @Override
    public boolean record(boolean start) {
        boolean ok = setProp(PROP_RECORD, start ? 4 : 0);
        if (ok) recording = start;
        return ok;
    }

    @Override
    public boolean photo() {
        int a = op(OP_RELEASE_ON, 1, 0); // half-press: focus
        sleep(600);
        int b = op(OP_RELEASE_ON, 2, 0); // full press
        op(OP_RELEASE_OFF, 2);
        op(OP_RELEASE_OFF, 1);
        try { t.transact(OP_GET_EVENT, null); } catch (Exception ignored) { }
        return a == RC_OK && b == RC_OK;
    }

    @Override
    public void autofocus() {
        op(OP_DO_AF, 0);
        sleep(700);
        op(OP_AF_CANCEL);
    }

    @Override
    public void driveFocus(boolean near, int step) {
        op(OP_DRIVE_LENS, (near ? 0 : 0x8000) | step);
    }

    @Override
    public boolean setMovieMode(boolean movie) {
        int rc = op(movie ? OP_MOVIE_SW_ON : OP_MOVIE_SW_OFF);
        sleep(400);
        try { enableEvf(); } catch (Exception ignored) { }
        return rc == RC_OK;
    }
}
