package org.grafalinux.monitor;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;

public final class MainActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int DRAWER_DP = 270;

    Store store;
    Model.Dashboard dash;
    Sensors.Table latest;
    DashboardView dv;
    final History hist = new History();

    private final Poller poller = new Poller();
    private FrameLayout root;
    private FrameLayout screens;
    private final ArrayList<Screen> stack = new ArrayList<Screen>();
    private Screen home;
    private TextView status;
    private TextView menuBtn;
    private LinearLayout editBar;
    private View drawer;
    private View scrim;
    private boolean drawerOpen = false;
    private String pollKey = "";
    private String pollServerId = "";
    private final HashSet<String> wanted = new HashSet<String>();
    private float downX, downY;
    private boolean edgeTrack = false;

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        dash = store.dashboard();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        screens = new FrameLayout(this);
        root.addView(screens, new FrameLayout.LayoutParams(MATCH, MATCH));

        home = buildHome();
        stack.add(home);
        screens.addView(home.root, new FrameLayout.LayoutParams(MATCH, MATCH));

        buildDrawer();
        setContentView(root);

        rebuildWanted();
        updateHint();
        applySettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        poller.stop();
        pollKey = "";
    }

    int dp(float v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------------
    // home screen = the dashboard
    // ------------------------------------------------------------------
    private Screen buildHome() {
        FrameLayout f = new FrameLayout(this);

        dv = new DashboardView(this);
        dv.dash = dash;
        dv.st = store.settings;
        dv.hist = hist;
        dv.host = new DashboardView.Host() {
            @Override
            public void onWidgetTap(Model.Widget w) {
                push(Screens.widgetEditor(MainActivity.this, w, false));
            }

            @Override
            public void onLongPress() {
                showDashMenu();
            }
        };

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(dv, new ViewGroup.LayoutParams(MATCH, WRAP));
        f.addView(sv, new FrameLayout.LayoutParams(MATCH, MATCH));

        menuBtn = Ui.text(this, "\u2261", 24, Color.WHITE);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setBackground(Ui.round(this, 0x66000000, 22));
        menuBtn.setOnClickListener(v -> openDrawer());
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.START);
        mp.setMargins(dp(6), dp(6), 0, 0);
        f.addView(menuBtn, mp);

        status = Ui.text(this, "", 12, 0xFFFFB74D);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        sp.setMargins(dp(56), dp(18), dp(8), 0);
        f.addView(status, sp);

        editBar = Ui.hbox(this);
        editBar.setBackgroundColor(0xF01C1C1E);
        editBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        editBar.addView(editButton("Done", v -> setEditing(false)));
        editBar.addView(editButton("+ Widget", v -> addWidget()));
        editBar.addView(editButton("Grid", v -> push(Screens.dashboardSettings(this))));
        editBar.setVisibility(View.GONE);
        f.addView(editBar, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));

        Screen s = new Screen() {
            @Override
            boolean needsData() {
                return true;
            }
        };
        s.root = f;
        return s;
    }

    private TextView editButton(String label, View.OnClickListener l) {
        TextView t = Ui.button(this, label, l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1);
        p.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(p);
        return t;
    }

    private void showDashMenu() {
        final boolean ed = dv.editing;
        String[] items = {ed ? "Done editing" : "Edit dashboard", "Add widget", "Dashboard settings"};
        Ui.dialog(this).setItems(items, (dlg, which) -> {
            if (which == 0) setEditing(!ed);
            else if (which == 1) addWidget();
            else push(Screens.dashboardSettings(this));
        }).show();
    }

    private void setEditing(boolean e) {
        dv.editing = e;
        editBar.setVisibility(e ? View.VISIBLE : View.GONE);
        menuBtn.setVisibility(e ? View.GONE : View.VISIBLE);
        dv.invalidate();
    }

    private void addWidget() {
        Model.Widget w = new Model.Widget();
        w.type = Model.GAUGE;
        w.w = Math.min(2, dash.cols);
        w.h = 2;
        place(w);
        dash.widgets.add(w);
        dash.fit();
        dv.relayout();
        push(Screens.widgetEditor(this, w, true));
    }

    private boolean isFree(int x, int y, int ww, int hh) {
        for (Model.Widget o : dash.widgets) {
            if (x < o.x + o.w && x + ww > o.x && y < o.y + o.h && y + hh > o.y) return false;
        }
        return true;
    }

    /** Puts a new widget in the first free spot of the grid. */
    private void place(Model.Widget w) {
        for (int y = 0; y < Model.MAX_ROWS - w.h; y++) {
            for (int x = 0; x + w.w <= dash.cols; x++) {
                if (isFree(x, y, w.w, w.h)) {
                    w.x = x;
                    w.y = y;
                    return;
                }
            }
        }
        w.x = 0;
        w.y = 0;
    }

    // ------------------------------------------------------------------
    // dashboard bookkeeping
    // ------------------------------------------------------------------
    Model.Server serverById(String id) {
        for (Model.Server s : store.servers()) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    private void rebuildWanted() {
        wanted.clear();
        for (Model.Widget w : dash.widgets) {
            for (Model.Series s : w.series) wanted.add(s.sensorId);
        }
    }

    private void updateHint() {
        if (store.servers().isEmpty()) {
            dv.hint = "No server yet.\nOpen the menu (swipe from the left edge)\n\u2192 Servers to add your PC.";
        } else if (dash.widgets.isEmpty()) {
            dv.hint = "Empty dashboard.\nLong-press anywhere to edit it.";
        } else {
            dv.hint = "";
        }
        dv.invalidate();
    }

    /** Call after anything about the dashboard changed. */
    void onDashChanged() {
        dash.fit();
        rebuildWanted();
        updateHint();
        store.saveDashboard(dash);
        dv.relayout();
        updatePolling();
    }

    void applySettings() {
        Model.Settings s = store.settings;
        if (s.keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        int o = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (s.orientation == 1) o = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        else if (s.orientation == 2) o = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        setRequestedOrientation(o);
        dv.invalidate();
        updatePolling();
    }

    // ------------------------------------------------------------------
    // polling: only while a screen that needs data is on top
    // ------------------------------------------------------------------
    private final Poller.Listener listener = new Poller.Listener() {
        @Override
        public void onData(Sensors.Table t) {
            latest = t;
            hist.record(t, wanted);
            dv.setTable(t);
            status.setVisibility(View.GONE);
            stack.get(stack.size() - 1).onData(t);
        }

        @Override
        public void onError(String msg) {
            status.setText("Offline: " + msg);
            status.setVisibility(View.VISIBLE);
            stack.get(stack.size() - 1).onError(msg);
        }
    };

    void updatePolling() {
        Screen top = stack.get(stack.size() - 1);
        Model.Server target = null;
        if (top.needsData()) {
            target = top.pollServer();
            if (target == null) target = serverById(dash.serverId);
        }
        String key = target == null ? ""
                : target.id + "|" + target.host + "|" + target.port + "|" + target.token + "|"
                + store.settings.intervalMs;
        if (key.equals(pollKey)) return;
        pollKey = key;
        poller.stop();
        if (target == null) return;
        if (!target.id.equals(pollServerId)) {
            pollServerId = target.id;
            latest = null;
            hist.clear();
            dv.setTable(null);
        }
        status.setVisibility(View.GONE);
        poller.start(target, store.settings.intervalMs, listener);
    }

    // ------------------------------------------------------------------
    // screen stack
    // ------------------------------------------------------------------
    void push(Screen s) {
        stack.get(stack.size() - 1).root.setVisibility(View.GONE);
        stack.add(s);
        screens.addView(s.root, new FrameLayout.LayoutParams(MATCH, MATCH));
        updatePolling();
    }

    void pop() {
        if (stack.size() <= 1) return;
        Screen s = stack.remove(stack.size() - 1);
        s.onClose();
        screens.removeView(s.root);
        stack.get(stack.size() - 1).root.setVisibility(View.VISIBLE);
        updateHint();
        updatePolling();
    }

    private void navigate(Screen s) {
        closeDrawer();
        while (stack.size() > 1) pop();
        push(s);
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
        } else if (stack.size() > 1) {
            pop();
        } else if (dv.editing) {
            setEditing(false);
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------
    // slide-out menu (swipe from the left edge, or tap the menu button)
    // ------------------------------------------------------------------
    private void buildDrawer() {
        scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout d = Ui.vbox(this);
        d.setBackgroundColor(0xFF111113);
        d.setPadding(0, dp(28), 0, 0);
        d.setClickable(true);
        TextView t = Ui.text(this, "Grafalinux", 22, Color.WHITE);
        t.setPadding(dp(20), dp(8), dp(20), dp(2));
        d.addView(t);
        TextView sub = Ui.text(this, "System Monitor", 13, Ui.SUB);
        sub.setPadding(dp(20), 0, dp(20), dp(16));
        d.addView(sub);
        addMenuItem(d, "Dashboard", () -> {
            closeDrawer();
            while (stack.size() > 1) pop();
        });
        addMenuItem(d, "Servers", () -> navigate(Screens.servers(this)));
        addMenuItem(d, "Dashboards", () -> navigate(Screens.dashboardSettings(this)));
        addMenuItem(d, "Settings", () -> navigate(Screens.settings(this)));
        addMenuItem(d, "Credits", () -> navigate(Screens.credits(this)));

        drawer = d;
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(dp(DRAWER_DP), MATCH));
    }

    private void addMenuItem(LinearLayout parent, String label, final Runnable action) {
        TextView t = Ui.text(this, label, 16, Color.WHITE);
        t.setPadding(dp(20), dp(14), dp(20), dp(14));
        t.setOnClickListener(v -> action.run());
        parent.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        drawer.setTranslationX(-dp(DRAWER_DP));
        drawer.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0).setDuration(150).start();
        scrim.animate().alpha(1f).setDuration(150).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawer.animate().translationX(-dp(DRAWER_DP)).setDuration(150)
                .withEndAction(() -> {
                    if (!drawerOpen) drawer.setVisibility(View.GONE);
                }).start();
        scrim.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> {
                    if (!drawerOpen) scrim.setVisibility(View.GONE);
                }).start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edgeTrack = !drawerOpen && e.getX() < dp(24);
                downX = e.getX();
                downY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (edgeTrack) {
                    float dx = e.getX() - downX;
                    if (dx > dp(48) && dx > Math.abs(e.getY() - downY)) {
                        edgeTrack = false;
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        openDrawer();
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                edgeTrack = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(e);
    }
}
