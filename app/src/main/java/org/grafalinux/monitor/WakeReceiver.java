package org.grafalinux.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;

/** Handles "USB power connected" and the periodic check for the experimental wake feature. */
public final class WakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final SharedPreferences sp = ctx.getSharedPreferences("grafalinux", Context.MODE_PRIVATE);
        int mode = sp.getInt("wakeMode", 0);
        String action = intent.getAction();
        if (action == null || mode == 0) return;

        if (Intent.ACTION_POWER_CONNECTED.equals(action) && mode == 1) {
            Wake.wakeScreen(ctx);
        } else if (Wake.ACTION_TICK.equals(action) && mode == 2) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        check(ctx, sp);
                    } finally {
                        pr.finish();
                    }
                }
            }, "grafalinux-wake").start();
        }
    }

    /** Asks the default dashboard's server if it is up; wakes the screen when it just came online. */
    private static void check(Context ctx, SharedPreferences sp) {
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grafalinux:check");
            wl.acquire(15000);
        } catch (Throwable ignored) {
            wl = null;
        }
        boolean online = false;
        try {
            Store store = new Store(ctx);
            Model.Dashboard d = store.defaultDashboard();
            for (Model.Server s : store.servers()) {
                if (s.id.equals(d.serverId)) {
                    Poller.getJson(s.url("/api/v1/info"), s.token);
                    online = true;
                }
            }
        } catch (Exception e) {
            online = false;
        }
        boolean was = sp.getBoolean("wakeOnline", false);
        sp.edit().putBoolean("wakeOnline", online).apply();
        if (online && !was) Wake.wakeScreen(ctx);
        if (wl != null && wl.isHeld()) wl.release();
    }
}
