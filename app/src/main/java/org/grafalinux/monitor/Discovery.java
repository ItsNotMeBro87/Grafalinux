package org.grafalinux.monitor;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds Grafalinux servers on the local network: one small UDP broadcast, every server answers with its
 * name and port. The token is never sent over discovery; the user still has to type it.
 */
final class Discovery {
    static final int PORT = 8788;
    private static final String REQUEST = "GRAFALINUX?1";

    static final class Found {
        String name = "";
        String host = "";
        int port = 8787;
    }

    interface Callback {
        void onDone(List<Found> found);
    }

    private Discovery() {
    }

    /** Scans for about 2.5 seconds on a background thread; the callback runs on the UI thread. */
    static void scan(final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Found> out = new ArrayList<>();
                DatagramSocket sock = null;
                try {
                    sock = new DatagramSocket();
                    sock.setBroadcast(true);
                    sock.setSoTimeout(300);
                    byte[] req = REQUEST.getBytes("UTF-8");
                    Set<String> targets = new LinkedHashSet<>();
                    targets.add("255.255.255.255");
                    try {
                        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                        while (en != null && en.hasMoreElements()) {
                            NetworkInterface ni = en.nextElement();
                            if (!ni.isUp() || ni.isLoopback()) continue;
                            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                                InetAddress bc = ia.getBroadcast();
                                if (bc != null) targets.add(bc.getHostAddress());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    long end = SystemClock.elapsedRealtime() + 2500;
                    long nextSend = 0;
                    int sent = 0;
                    byte[] buf = new byte[1024];
                    while (SystemClock.elapsedRealtime() < end) {
                        long now = SystemClock.elapsedRealtime();
                        if (sent < 3 && now >= nextSend) {
                            for (String t : targets) {
                                try {
                                    sock.send(new DatagramPacket(req, req.length, InetAddress.getByName(t), PORT));
                                } catch (Exception ignored) {
                                }
                            }
                            sent++;
                            nextSend = now + 700;
                        }
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            sock.receive(p);
                            JSONObject j = new JSONObject(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                            if (!"grafalinux".equals(j.optString("app"))) continue;
                            Found f = new Found();
                            f.host = p.getAddress().getHostAddress();
                            f.port = j.optInt("port", 8787);
                            f.name = j.optString("name", f.host);
                            boolean dup = false;
                            for (Found o : out) {
                                if (o.host.equals(f.host) && o.port == f.port) dup = true;
                            }
                            if (!dup) out.add(f);
                        } catch (SocketTimeoutException ignored) {
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (sock != null) sock.close();
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(out);
                    }
                });
            }
        }, "grafalinux-discovery");
        th.setDaemon(true);
        th.start();
    }
}
