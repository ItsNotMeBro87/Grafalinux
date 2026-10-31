package org.grafalinux.monitor;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

/**
 * Experimental: wake the phone's screen when the PC comes on.
 * Mode 1 reacts to USB power being connected (no polling). Mode 2 checks the PC every N seconds with an
 * alarm, which costs battery. The receiver is only enabled while one of the modes is selected.
 */
final class Wake {
    private Wake() {}

    static final String ACTION_TICK = "org.grafalinux.monitor.TICK";

    static void apply(Context c, Model.Settings s) {
        boolean on = s.wakeMode != 0;
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, WakeReceiver.class),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = tickIntent(c);
        am.cancel(pi);
        if (s.wakeMode == 2) {
            long iv = Math.max(15, s.wakeInterval) * 1000L;
            am.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + iv, iv, pi);
        }
    }

    private static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, WakeReceiver.class);
        i.setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, 1, i, flags);
    }

    /** Turns the screen on and brings the dashboard to the front. */
    @SuppressWarnings("deprecation")
    static void wakeScreen(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "grafalinux:wake");
            wl.acquire(10000);
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("wake", true);
            c.startActivity(i);
        } catch (Throwable ignored) {
            // some phones or Android versions do not allow this
        }
    }
}
