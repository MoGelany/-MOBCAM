package com.jilani.mobcam;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PTP over the camera's USB-C port (USB still-image class, bulk endpoints). */
public class UsbPtpTransport implements PtpTransport {

    public static final int CANON_VID = 0x04A9;

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private UsbEndpoint in, out;
    private int tid = 1;
    private final byte[] rx = new byte[Build.VERSION.SDK_INT >= 28 ? 512 * 1024 : 16384];

    public static final int SONY_VID = 0x054C;

    /** A Canon / Sony camera exposing a still-image (PTP) interface and no UVC video interface. */
    public static boolean isPtpCamera(UsbDevice d) {
        if (d == null || (d.getVendorId() != CANON_VID && d.getVendorId() != SONY_VID)) return false;
        boolean ptp = false;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            int c = d.getInterface(i).getInterfaceClass();
            if (c == UsbConstants.USB_CLASS_VIDEO) return false;
            if (c == UsbConstants.USB_CLASS_STILL_IMAGE) ptp = true;
        }
        return ptp;
    }

    public UsbPtpTransport(UsbManager usb, UsbDevice device) throws Exception {
        UsbInterface found = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface it = device.getInterface(i);
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) { found = it; break; }
        }
        if (found == null) throw new Exception("الكاميرا مش ظاهرة كـ PTP");
        intf = found;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep; else out = ep;
        }
        conn = usb.openDevice(device);
        if (conn == null) throw new Exception("مقدرتش أفتح الكاميرا (صلاحية USB؟)");
        if (!conn.claimInterface(intf, true)) {
            conn.close();
            throw new Exception("الكاميرا مشغولة بتطبيق تاني");
        }
    }

    @Override
    public Resp transact(int op, byte[] dataOut, int... params) throws Exception {
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

    @Override
    public void close() {
        try { conn.releaseInterface(intf); } catch (Exception ignored) { }
        conn.close();
    }
}
