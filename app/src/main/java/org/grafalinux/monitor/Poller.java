package org.grafalinux.monitor;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Polls one linmon-agent on a background thread; results are delivered on the UI thread. */
final class Poller {
    interface Listener {
        void onData(Sensors.Table t);
        void onError(String msg);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int gen = 0;

    synchronized void start(final Model.Server s, final int intervalMs, final Listener l) {
        final int my = ++gen;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(s, intervalMs, l, my);
            }
        }, "grafalinux-poller");
        th.setDaemon(true);
        th.start();
    }

    synchronized void stop() {
        gen++;
    }

    private void loop(Model.Server s, int intervalMs, final Listener l, final int my) {
        while (gen == my) {
            long t0 = SystemClock.elapsedRealtime();
            Sensors.Table table = null;
            String err = null;
            try {
                table = Sensors.parse(getJson(s.url("/api/v1/stats"), s.token));
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            if (gen != my) return;
            final Sensors.Table ft = table;
            final String fe = err;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (gen != my) return;
                    if (ft != null) l.onData(ft);
                    else l.onError(fe);
                }
            });
            long sleep = intervalMs - (SystemClock.elapsedRealtime() - t0);
            if (err != null) sleep = Math.max(sleep, 2000);
            try {
                Thread.sleep(Math.max(100, sleep));
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    static JSONObject getJson(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            if (token != null && token.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Unauthorized - check the token");
            if (code != 200) throw new IOException("HTTP " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }
}
