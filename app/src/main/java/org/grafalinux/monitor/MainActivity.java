package org.grafalinux.monitor;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;

public final class MainActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int DRAWER_DP = 270;
    private static final int UNDO_LIMIT = 30;

    Store store;
    Model.Dashboard dash;
    Sensors.Table latest;
    DashboardView dv;
    final History hist = new History();

    private final Poller poller = new Poller();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private FrameLayout screens;
    private final ArrayList<Screen> stack = new ArrayList<Screen>();
    private Screen home;
    private TextView status;
    private BackgroundView bgView;
    private Bitmap bgBmp;
    private TextView menuBtn;
    private TextView btnUndo;
    private TextView btnRedo;
    private LinearLayout editBar;
    private View drawer;
    private View scrim;
    private boolean drawerOpen = false;
    private String pollKey = "";
    private String pollServerId = "";
    private final HashSet<String> wanted = new HashSet<String>();
    private float downX, downY;
    private boolean edgeTrack = false;
    private boolean online = false;        // data is arriving from the server
    private long lastOk = 0;

    // undo / redo: snapshots of the dashboard layout as JSON text
    private final ArrayList<String> undoStack = new ArrayList<String>();
    private final ArrayList<String> redoStack = new ArrayList<String>();
    private String lastSaved;

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        dash = store.defaultDashboard();
        lastSaved = snapshot();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        screens = new FrameLayout(this);
        root.addView(screens, new FrameLayout.LayoutParams(MATCH, MATCH));

        home = buildHome();
        stack.add(home);
        screens.addView(home.root, new FrameLayout.LayoutParams(MATCH, MATCH));

        buildDrawer();
        setContentView(root);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(vis -> {
            if (store.settings.fullscreen && (vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                handler.removeCallbacks(refullscreen);
                handler.postDelayed(refullscreen, 1500);
            }
        });

        rebuildWanted();
        updateHint();
        updateEditBar();
        applySettings();
        handleWake(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleWake(intent);
    }

    /** Started by the experimental wake feature: light the screen even if the phone was locked. */
    @SuppressWarnings("deprecation")
    private void handleWake(Intent i) {
        if (i != null && i.getBooleanExtra("wake", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            i.removeExtra("wake");
        }
    }

    /** Hides the system bars again after a keyboard, dialog or other screen brought them back. */
    private final Runnable refullscreen = new Runnable() {
        @Override
        public void run() {
            applyFullscreen();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        applyFullscreen();
        updatePolling();
        showMenuButton();
        scheduleClock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        poller.stop();
        pollKey = "";
        handler.removeCallbacks(hideMenu);
        handler.removeCallbacks(clockTick);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    int dp(float v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------------
    // home screen = the dashboard
    // ------------------------------------------------------------------
    private Screen buildHome() {
        FrameLayout f = new FrameLayout(this);

        bgView = new BackgroundView(this);
        f.addView(bgView, new FrameLayout.LayoutParams(MATCH, MATCH));

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

            @Override
            public void onChanged() {
                onDashChanged();
            }
        };

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(dv, new ViewGroup.LayoutParams(MATCH, WRAP));
        f.addView(sv, new FrameLayout.LayoutParams(MATCH, MATCH));

        menuBtn = Ui.text(this, "\u2261", 22, Color.WHITE);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setBackground(Ui.round(this, 0x66000000, 18));
        menuBtn.setOnClickListener(v -> openDrawer());
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.START);
        mp.setMargins(dp(4), dp(4), 0, 0);
        f.addView(menuBtn, mp);

        status = Ui.text(this, "", 12, 0xFFFFB74D);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        sp.setMargins(dp(46), dp(14), dp(8), 0);
        f.addView(status, sp);

        editBar = Ui.hbox(this);
        editBar.setBackgroundColor(0xF01C1C1E);
        editBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        btnUndo = editButton("Undo", v -> undo());
        btnRedo = editButton("Redo", v -> redo());
        editBar.addView(btnUndo);
        editBar.addView(btnRedo);
        editBar.addView(editButton("+ Add", v -> addWidget()));
        editBar.addView(editButton("Grid", v -> push(Screens.dashboardSettings(this))));
        editBar.addView(editButton("Done", v -> setEditing(false)));
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
        t.setTextSize(13);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(p);
        return t;
    }

    private void updateEditBar() {
        btnUndo.setAlpha(undoStack.isEmpty() ? 0.35f : 1f);
        btnRedo.setAlpha(redoStack.isEmpty() ? 0.35f : 1f);
    }

    private void showDashMenu() {
        final ArrayList<String> items = new ArrayList<String>();
        final ArrayList<Integer> codes = new ArrayList<Integer>();
        if (dash.locked) {
            items.add("Unlock dashboard");
            codes.add(1);
        } else {
            items.add(dv.editing ? "Done editing" : "Edit dashboard");
            codes.add(0);
            items.add("Add widget");
            codes.add(2);
            items.add("Dashboard settings");
            codes.add(3);
            items.add("Lock dashboard");
            codes.add(1);
        }
        items.add("Open menu");
        codes.add(4);
        Ui.dialog(this).setItems(items.toArray(new String[0]), (dlg, which) -> {
            switch (codes.get(which)) {
                case 0:
                    setEditing(!dv.editing);
                    break;
                case 1:
                    toggleLock();
                    break;
                case 2:
                    addWidget();
                    break;
                case 3:
                    push(Screens.dashboardSettings(this));
                    break;
                default:
                    openDrawer();
                    break;
            }
        }).show();
    }

    private void toggleLock() {
        dash.locked = !dash.locked;
        if (dash.locked) setEditing(false);
        store.saveDashboard(dash);
        dv.relayout();
        Ui.toast(this, dash.locked ? "Dashboard locked" : "Dashboard unlocked");
    }

    private void setEditing(boolean e) {
        if (e && dash.locked) return;
        dv.editing = e;
        editBar.setVisibility(e ? View.VISIBLE : View.GONE);
        updateEditBar();
        dv.relayout();
        showMenuButton();
    }

    private void addWidget() {
        if (dash.locked) return;
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
    // menu button: auto-hides so it does not cover your graphs
    // ------------------------------------------------------------------
    private final Runnable hideMenu = new Runnable() {
        @Override
        public void run() {
            menuBtn.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() {
                @Override
                public void run() {
                    menuBtn.setVisibility(View.INVISIBLE);
                }
            }).start();
        }
    };

    void showMenuButton() {
        handler.removeCallbacks(hideMenu);
        menuBtn.animate().cancel();
        int mode = store.settings.menuMode;
        if (dv.editing || mode == 2) {
            menuBtn.setVisibility(View.GONE);
            return;
        }
        menuBtn.setAlpha(0.7f);
        menuBtn.setVisibility(View.VISIBLE);
        if (mode == 1) handler.postDelayed(hideMenu, 3500);
    }

    // ------------------------------------------------------------------
    // dashboard bookkeeping and undo / redo
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

    /** Layout and widgets as text (the data source and lock state are deliberately left out). */
    private String snapshot() {
        try {
            JSONObject o = dash.toJson();
            o.remove("serverId");
            o.remove("locked");
            return o.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    private void trackUndo() {
        String now = snapshot();
        if (now == null || now.equals(lastSaved)) return;
        if (lastSaved != null) {
            undoStack.add(lastSaved);
            if (undoStack.size() > UNDO_LIMIT) undoStack.remove(0);
        }
        redoStack.clear();
        lastSaved = now;
        updateEditBar();
    }

    private void restore(String json) {
        try {
            dash.copyFrom(Model.Dashboard.fromJson(new JSONObject(json)));
        } catch (JSONException e) {
            return;
        }
        dash.fit();
        rebuildWanted();
        updateHint();
        store.saveDashboard(dash);
        dv.relayout();
        updatePolling();
        lastSaved = snapshot();
        updateEditBar();
        scheduleClock();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        String cur = snapshot();
        String prev = undoStack.remove(undoStack.size() - 1);
        if (cur != null) redoStack.add(cur);
        restore(prev);
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        String cur = snapshot();
        String next = redoStack.remove(redoStack.size() - 1);
        if (cur != null) undoStack.add(cur);
        restore(next);
    }

    /** Call after anything about the dashboard changed. */
    void onDashChanged() {
        dash.fit();
        rebuildWanted();
        updateHint();
        store.saveDashboard(dash);
        dv.relayout();
        updatePolling();
        trackUndo();
        scheduleClock();
    }

    void applySettings() {
        Model.Settings s = store.settings;
        updateKeepOn();
        Wake.apply(this, s);
        int o = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (s.orientation == 1) o = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        else if (s.orientation == 2) o = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        setRequestedOrientation(o);
        applyFullscreen();
        showMenuButton();
        Sensors.rateBits = s.rateBits;
        if (s.bgMode == 2) {
            if (bgBmp == null) loadBgBitmap();
        } else {
            bgBmp = null;
        }
        applyBackground();
        dv.invalidate();
        updatePolling();
    }

    /** Keep the screen on: always (setting), or only while the PC answers when a wake mode is chosen. */
    private void updateKeepOn() {
        Model.Settings s = store.settings;
        boolean keep = s.wakeMode != 0 ? online : s.keepOn;
        if (keep) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void applyFullscreen() {
        View decor = getWindow().getDecorView();
        if (store.settings.fullscreen) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // ------------------------------------------------------------------
    // clock widgets: redraw once a minute (or each second when a clock shows seconds), only while visible
    // ------------------------------------------------------------------
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            dv.invalidate();
            scheduleClock();
        }
    };

    private void scheduleClock() {
        handler.removeCallbacks(clockTick);
        if (stack.size() != 1) return;
        boolean any = false;
        boolean secs = false;
        for (Model.Widget w : dash.widgets) {
            if (w.type == Model.CLOCK) {
                any = true;
                if (w.clockSeconds) secs = true;
            }
        }
        if (!any) return;
        long now = System.currentTimeMillis();
        long delay = secs ? 1000 - now % 1000 : 60000 - now % 60000;
        handler.postDelayed(clockTick, delay + 20);
    }

    // ------------------------------------------------------------------
    // several dashboards
    // ------------------------------------------------------------------
    void switchDashboard(Model.Dashboard d) {
        if (d == null) return;
        setEditing(false);
        dash = d;
        dv.dash = d;
        undoStack.clear();
        redoStack.clear();
        lastSaved = snapshot();
        updateEditBar();
        rebuildWanted();
        updateHint();
        dv.relayout();
        updatePolling();
        scheduleClock();
    }

    void openHome() {
        closeDrawer();
        while (stack.size() > 1) pop();
    }

    // ------------------------------------------------------------------
    // background image (scaled to the screen size when imported, to save memory)
    // ------------------------------------------------------------------
    private static final int REQ_BG = 42;

    void applyBackground() {
        bgView.apply(store.settings, bgBmp);
    }

    private void loadBgBitmap() {
        bgBmp = null;
        File f = new File(getFilesDir(), "bg.jpg");
        if (!f.exists()) return;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            bgBmp = BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable t) {
            bgBmp = null;
        }
    }

    void pickBackgroundImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Choose a background image"), REQ_BG);
    }

    void removeBackgroundImage() {
        new File(getFilesDir(), "bg.jpg").delete();
        bgBmp = null;
        if (store.settings.bgMode == 2) store.settings.bgMode = 0;
        store.saveSettings();
        applyBackground();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_BG && res == RESULT_OK && data != null && data.getData() != null) {
            importBackground(data.getData());
        }
    }

    private void importBackground(final Uri uri) {
        Ui.toast(this, "Loading image…");
        new Thread(() -> {
            boolean ok = false;
            try {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                int target = Math.max(dm.widthPixels, dm.heightPixels);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                InputStream in = getContentResolver().openInputStream(uri);
                BitmapFactory.decodeStream(in, null, o);
                in.close();
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                in = getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
                in.close();
                if (bmp != null) {
                    int mx = Math.max(bmp.getWidth(), bmp.getHeight());
                    if (mx > target) {
                        float k = (float) target / mx;
                        Bitmap sc = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * k),
                                Math.round(bmp.getHeight() * k), true);
                        if (sc != bmp) bmp.recycle();
                        bmp = sc;
                    }
                    FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "bg.jpg"));
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
                    out.close();
                    bmp.recycle();
                    ok = true;
                }
            } catch (Throwable t) {
                ok = false;
            }
            final boolean fok = ok;
            runOnUiThread(() -> {
                if (fok) {
                    store.settings.bgMode = 2;
                    store.saveSettings();
                    loadBgBitmap();
                    applyBackground();
                    Ui.toast(this, "Background image set");
                } else {
                    Ui.toast(this, "Could not load that image");
                }
            });
        }).start();
    }

    // ------------------------------------------------------------------
    // polling: only while a screen that needs data is on top
    // ------------------------------------------------------------------
    private final Poller.Listener listener = new Poller.Listener() {
        @Override
        public void onData(Sensors.Table t) {
            Sensors.addPhone(MainActivity.this, t);
            lastOk = SystemClock.elapsedRealtime();
            if (!online) {
                online = true;
                updateKeepOn();
            }
            latest = t;
            hist.record(t, wanted);
            dv.setTable(t);
            status.setVisibility(View.GONE);
            stack.get(stack.size() - 1).onData(t);
        }

        @Override
        public void onError(String msg) {
            if (online && SystemClock.elapsedRealtime() - lastOk > 8000) {
                online = false;
                updateKeepOn();
            }
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
        scheduleClock();
    }

    void pop() {
        if (stack.size() <= 1) return;
        Screen s = stack.remove(stack.size() - 1);
        s.onClose();
        screens.removeView(s.root);
        stack.get(stack.size() - 1).root.setVisibility(View.VISIBLE);
        updateHint();
        updatePolling();
        if (stack.size() == 1) showMenuButton();
        scheduleClock();
        if (store.settings.fullscreen) handler.postDelayed(refullscreen, 300);
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
        d.setPadding(0, dp(28), 0, dp(16));
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
        addMenuItem(d, "Dashboards", () -> navigate(Screens.dashboards(this)));
        addMenuItem(d, "Settings", () -> navigate(Screens.settings(this)));
        addMenuItem(d, "Credits", () -> navigate(Screens.credits(this)));

        // scrollable, so every entry stays reachable in landscape
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF111113);
        sv.setClickable(true);
        sv.addView(d, new ViewGroup.LayoutParams(MATCH, WRAP));
        drawer = sv;
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
                if (store.settings.menuMode == 1 && stack.size() == 1) showMenuButton();
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
