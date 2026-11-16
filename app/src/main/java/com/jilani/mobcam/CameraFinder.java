package com.jilani.mobcam;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Looks for a Canon camera on the current Wi-Fi (UPnP/SSDP announcements), plus the Wi-Fi gateway as a fallback. */
public class CameraFinder {

    /** Blocking — call off the UI thread. Returns candidate IPs, Canon-announced ones first. */
    public static Set<String> find(Context ctx, int timeoutMs) {
        Set<String> found = new LinkedHashSet<>();
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        Network net = IpPtpTransport.wifiNetwork(ctx);
        try {
            if (wm != null) {
                lock = wm.createMulticastLock("mobcam");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            InetAddress group = InetAddress.getByName("239.255.255.250");
            DatagramSocket search = new DatagramSocket();
            if (net != null) net.bindSocket(search);
            MulticastSocket listen = null;
            try {
                listen = new MulticastSocket(1900);
                listen.joinGroup(group);
                listen.setSoTimeout(150);
            } catch (Exception ignored) { listen = null; }

            String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
            byte[] m = msg.getBytes("US-ASCII");
            search.setSoTimeout(150);
            long end = System.currentTimeMillis() + timeoutMs;
            int sent = 0;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                if (sent < 3) { search.send(new DatagramPacket(m, m.length, new InetSocketAddress(group, 1900))); sent++; }
                for (DatagramSocket s : new DatagramSocket[]{search, listen}) {
                    if (s == null) continue;
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        String txt = new String(p.getData(), 0, p.getLength(), "US-ASCII").toLowerCase(Locale.US);
                        if (txt.contains("canon")) found.add(p.getAddress().getHostAddress());
                    } catch (SocketTimeoutException ignored) { }
                }
            }
            search.close();
            if (listen != null) listen.close();
        } catch (Exception ignored) {
        } finally {
            if (lock != null) lock.release();
        }
        String gw = gateway(ctx, net);
        if (gw != null) found.add(gw);
        return found;
    }

    /** When the phone joins the camera's own Wi-Fi, the camera is usually the gateway. */
    private static String gateway(Context ctx, Network net) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || net == null) return null;
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null) return null;
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() != null && !r.getGateway().isAnyLocalAddress()) {
                    return r.getGateway().getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
