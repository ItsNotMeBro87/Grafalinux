package org.grafalinux.monitor;

import android.view.View;

/** One full-screen page managed by MainActivity's back stack. */
class Screen {
    View root;

    /** Called on the UI thread with every new snapshot. */
    void onData(Sensors.Table t) {
    }

    void onError(String msg) {
    }

    /** Called just before the screen is removed from the stack. */
    void onClose() {
    }

    /** A server this screen wants to inspect instead of the dashboard's server. */
    Model.Server pollServer() {
        return null;
    }

    /** Whether the app should poll the server while this screen is on top. */
    boolean needsData() {
        return false;
    }
}
