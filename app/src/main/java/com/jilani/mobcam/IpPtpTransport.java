package com.jilani.mobcam;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * PTP over Wi-Fi (PTP/IP, TCP port 15740) — what the camera's
 * "Remote control (EOS Utility)" Wi-Fi mode speaks.
 */
public class IpPtpTransport implements PtpTransport {

    public static final int PORT = 15740;

    private static final int INIT_CMD_REQ = 1, INIT_CMD_ACK = 2, INIT_EVT_REQ = 3, INIT_EVT_ACK = 4, INIT_FAIL = 5;
    private static final int CMD_REQ = 6, CMD_RESP = 7, START_DATA = 9, DATA = 10, END_DATA = 12, PING = 13, PONG = 14;

    private final Socket cmd, evt;
    private final DataInputStream cin;
    private final OutputStream cout;
    private int tid = 1;
    private volatile boolean closed = false;

    /** The phone's Wi-Fi network, so sockets don't go out over mobile data when the camera's Wi-Fi has no internet. */
    public static Network wifiNetwork(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    private static Socket socket(Network net) throws IOException {
        Socket s = net != null ? net.getSocketFactory().createSocket() : new Socket();
        s.setTcpNoDelay(true);
        return s;
    }

    public IpPtpTransport(Context ctx, String host, byte[] guid, String name) throws Exception {
        Network net = wifiNetwork(ctx);
        cmd = socket(net);
        try {
            cmd.connect(new InetSocketAddress(host, PORT), 5000);
        } catch (IOException e) {
            throw new Exception("مش لاقي الكاميرا على " + host + " — اتأكد إن الموبايل والكاميرا على نفس الواي فاي");
        }
        // pairing may wait for you to press OK on the camera
        cmd.setSoTimeout(60000);
        cin = new DataInputStream(cmd.getInputStream());
        cout = cmd.getOutputStream();

        // Init Command Request: GUID + UTF-16 name + protocol version 1.0
        byte[] nm = (name + "\0").getBytes("UTF-16LE");
        ByteBuffer b = ByteBuffer.allocate(8 + 16 + nm.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(b.capacity()).putInt(INIT_CMD_REQ).put(guid).put(nm).putShort((short) 0).putShort((short) 1);
        cout.write(b.array());
        cout.flush();
        byte[] ack = readPacket(cin, cout);
        int type = le(ack).getInt(4);
        if (type == INIT_FAIL) throw new Exception("الكاميرا رفضت الاتصال — دوس OK على شاشة الكاميرا لما تسأل عن الربط");
        if (type != INIT_CMD_ACK) throw new Exception("رد غريب من الكاميرا (" + type + ")");
        int connId = le(ack).getInt(8);
        cmd.setSoTimeout(8000);

        evt = socket(net);
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try { evt.connect(new InetSocketAddress(host, PORT), 5000); last = null; break; }
            catch (Exception e) { last = e; Thread.sleep(150); }
        }
        if (last != null) throw last;
        evt.setSoTimeout(8000);
        ByteBuffer e = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        e.putInt(12).putInt(INIT_EVT_REQ).putInt(connId);
        OutputStream eout = evt.getOutputStream();
        eout.write(e.array());
        eout.flush();
        DataInputStream ein = new DataInputStream(evt.getInputStream());
        byte[] eack = readPacket(ein, eout);
        if (le(eack).getInt(4) != INIT_EVT_ACK) throw new Exception("مشكلة في قناة الأحداث");
        evt.setSoTimeout(0);

        // drain the event channel (Canon sends events by GetEvent anyway) and answer pings
        Thread t = new Thread(() -> {
            try { while (!closed) readPacket(ein, eout); } catch (Exception ignored) { }
        }, "ptpip-evt");
        t.setDaemon(true);
        t.start();
    }

    private static ByteBuffer le(byte[] a) {
        return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Reads one PTP/IP packet (length-prefixed); answers PING with PONG transparently. */
    private static byte[] readPacket(DataInputStream in, OutputStream out) throws IOException {
        while (true) {
            byte[] h = new byte[8];
            in.readFully(h);
            int len = le(h).getInt(0), type = le(h).getInt(4);
            if (len < 8 || len > 64 * 1024 * 1024) throw new IOException("bad packet length " + len);
            byte[] p = new byte[len];
            System.arraycopy(h, 0, p, 0, 8);
            in.readFully(p, 8, len - 8);
            if (type == PING) {
                synchronized (out) {
                    out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(8).putInt(PONG).array());
                    out.flush();
                }
                continue;
            }
            return p;
        }
    }

    @Override
    public Resp transact(int op, byte[] dataOut, int... params) throws Exception {
        int id = tid++;
        ByteBuffer c = ByteBuffer.allocate(18 + 4 * params.length).order(ByteOrder.LITTLE_ENDIAN);
        c.putInt(c.capacity()).putInt(CMD_REQ).putInt(dataOut != null ? 2 : 1).putShort((short) op).putInt(id);
        for (int p : params) c.putInt(p);
        synchronized (cout) {
            cout.write(c.array());
            if (dataOut != null) {
                ByteBuffer s = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
                s.putInt(20).putInt(START_DATA).putInt(id).putLong(dataOut.length);
                cout.write(s.array());
                ByteBuffer d = ByteBuffer.allocate(12 + dataOut.length).order(ByteOrder.LITTLE_ENDIAN);
                d.putInt(d.capacity()).putInt(END_DATA).putInt(id).put(dataOut);
                cout.write(d.array());
            }
            cout.flush();
        }

        Resp r = new Resp();
        java.io.ByteArrayOutputStream data = null;
        try {
            while (true) {
                byte[] p = readPacket(cin, cout);
                int type = le(p).getInt(4);
                if (type == START_DATA) {
                    long total = le(p).getLong(12);
                    data = new java.io.ByteArrayOutputStream((int) Math.min(total, 8 * 1024 * 1024));
                } else if (type == DATA || type == END_DATA) {
                    if (data == null) data = new java.io.ByteArrayOutputStream();
                    data.write(p, 12, p.length - 12);
                } else if (type == CMD_RESP) {
                    r.code = le(p).getShort(8) & 0xffff;
                    if (data != null) r.data = data.toByteArray();
                    return r;
                }
            }
        } catch (IOException e) {
            throw new Exception("انقطع الواي فاي مع الكاميرا");
        }
    }

    @Override
    public void close() {
        closed = true;
        try { cmd.close(); } catch (Exception ignored) { }
        try { if (evt != null) evt.close(); } catch (Exception ignored) { }
    }
}
