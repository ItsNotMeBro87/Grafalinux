package org.grafalinux.monitor;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builders for every screen except the dashboard itself. */
final class Screens {
    private Screens() {}

    private interface IntCb {
        void on(int v);
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------
    private static void changed(MainActivity a) {
        a.store.saveSettings();
        a.dv.invalidate();
    }

    private static CheckBox check(MainActivity a, String label, boolean value,
                                  CompoundButton.OnCheckedChangeListener l) {
        CheckBox cb = new CheckBox(a);
        cb.setText(label);
        cb.setTextColor(Ui.TEXT);
        cb.setChecked(value);
        cb.setOnCheckedChangeListener(l);
        return cb;
    }

    private static View labeled(MainActivity a, String label, View right) {
        LinearLayout row = Ui.hbox(a);
        row.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        row.addView(Ui.text(a, label, 15, Ui.TEXT), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        row.addView(right);
        return row;
    }

    private static TextView choice(final MainActivity a, final String title, final String[] items,
                                   int current, final IntCb cb) {
        final TextView b = Ui.button(a, items[Math.max(0, Math.min(items.length - 1, current))], null);
        b.setOnClickListener(v -> Ui.dialog(a).setTitle(title).setItems(items, (dlg, which) -> {
            b.setText(items[which]);
            cb.on(which);
        }).show());
        return b;
    }

    private static String serverName(MainActivity a) {
        Model.Server s = a.serverById(a.dash.serverId);
        return s == null ? "(none)" : s.name;
    }

    // ------------------------------------------------------------------
    // Servers
    // ------------------------------------------------------------------
    static Screen servers(final MainActivity a) {
        final Screen sc = new Screen();
        final LinearLayout body = Ui.vbox(a);
        sc.root = Ui.scaffold(a, "Servers", v -> a.pop(), body);
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            body.removeAllViews();
            List<Model.Server> list = a.store.servers();
            if (list.isEmpty()) {
                body.addView(Ui.text(a, "No servers yet. Tap \"Add server\" and enter the IP address "
                        + "of the PC that runs linmon-agent.", 14, Ui.SUB));
            }
            for (final Model.Server s : list) {
                LinearLayout card = Ui.card(a);
                card.addView(Ui.text(a, s.name, 16, Ui.TEXT));
                String sub = s.host + ":" + s.port
                        + (s.id.equals(a.dash.serverId) ? "   \u2022 used by the dashboard" : "");
                card.addView(Ui.text(a, sub, 12, Ui.SUB));
                card.setOnClickListener(v -> a.push(browse(a, s)));
                card.setOnLongClickListener(v -> {
                    serverMenu(a, s, refresh[0]);
                    return true;
                });
                Ui.add(body, card, 8);
            }
            Ui.add(body, Ui.button(a, "+ Add server", v -> serverDialog(a, null, refresh[0])), 16);
            body.addView(Ui.text(a, "Tap a server to see its sensors. Long-press to remove it "
                    + "or use it for the dashboard.", 12, Ui.SUB));
        };
        refresh[0].run();
        return sc;
    }

    private static void serverMenu(final MainActivity a, final Model.Server s, final Runnable done) {
        Ui.dialog(a).setTitle(s.name)
                .setItems(new CharSequence[]{"Edit", "Use for the dashboard", "Remove"}, (dlg, which) -> {
                    if (which == 0) {
                        serverDialog(a, s, done);
                    } else if (which == 1) {
                        a.dash.serverId = s.id;
                        a.onDashChanged();
                        done.run();
                    } else {
                        Ui.dialog(a).setTitle("Remove server?")
                                .setMessage("You can add it again later.")
                                .setPositiveButton("Remove", (d2, w2) -> {
                                    List<Model.Server> l = a.store.servers();
                                    for (int i = l.size() - 1; i >= 0; i--) {
                                        if (l.get(i).id.equals(s.id)) l.remove(i);
                                    }
                                    a.store.saveServers(l);
                                    if (s.id.equals(a.dash.serverId)) {
                                        a.dash.serverId = l.isEmpty() ? "" : l.get(0).id;
                                        a.onDashChanged();
                                    }
                                    done.run();
                                })
                                .setNegativeButton("Cancel", null).show();
                    }
                }).show();
    }

    private static void saveServer(MainActivity a, Model.Server s) {
        List<Model.Server> list = a.store.servers();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(s.id)) {
                list.set(i, s);
                found = true;
            }
        }
        if (!found) list.add(s);
        a.store.saveServers(list);
        if (a.dash.serverId.length() == 0) {
            a.dash.serverId = s.id;
            a.onDashChanged();
        }
        a.updatePolling();
    }

    private static void serverDialog(final MainActivity a, final Model.Server existing, final Runnable done) {
        LinearLayout box = Ui.vbox(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        final EditText name = Ui.edit(a, "Name (e.g. my PC)", existing == null ? "" : existing.name,
                InputType.TYPE_CLASS_TEXT);
        final EditText host = Ui.edit(a, "IP address or hostname", existing == null ? "" : existing.host,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText port = Ui.edit(a, "Port", existing == null ? "8787" : String.valueOf(existing.port),
                InputType.TYPE_CLASS_NUMBER);
        final EditText token = Ui.edit(a, "Token (if the agent uses one)", existing == null ? "" : existing.token,
                InputType.TYPE_CLASS_TEXT);
        box.addView(name);
        box.addView(host);
        box.addView(port);
        box.addView(token);

        final AlertDialog dlg = Ui.dialog(a).setTitle(existing == null ? "Add server" : "Edit server").setView(box)
                .setPositiveButton("Test & save", null)
                .setNegativeButton("Cancel", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final Model.Server s = new Model.Server();
            if (existing != null) s.id = existing.id;
            s.host = host.getText().toString().trim();
            s.name = name.getText().toString().trim();
            s.token = token.getText().toString().trim();
            try {
                s.port = Integer.parseInt(port.getText().toString().trim());
            } catch (NumberFormatException e) {
                s.port = 8787;
            }
            if (s.host.length() == 0) {
                Ui.toast(a, "Enter the IP address of the PC");
                return;
            }
            Ui.toast(a, "Connecting\u2026");
            new Thread(() -> {
                String err = null;
                String hn = "";
                try {
                    JSONObject j = Poller.getJson(s.url("/api/v1/info"), s.token);
                    hn = j.optString("hostname", "");
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                final String fe = err;
                final String fh = hn;
                a.runOnUiThread(() -> {
                    if (fe == null) {
                        if (s.name.length() == 0) s.name = fh.length() > 0 ? fh : s.host;
                        saveServer(a, s);
                        dlg.dismiss();
                        done.run();
                        Ui.toast(a, "Connected to " + s.name);
                    } else {
                        Ui.dialog(a).setTitle("Could not connect")
                                .setMessage(fe + "\n\nSave this server anyway?")
                                .setPositiveButton("Save anyway", (d2, w2) -> {
                                    if (s.name.length() == 0) s.name = s.host;
                                    saveServer(a, s);
                                    dlg.dismiss();
                                    done.run();
                                })
                                .setNegativeButton("Back", null).show();
                    }
                });
            }).start();
        });
    }

    // ------------------------------------------------------------------
    // Sensor browser for one server
    // ------------------------------------------------------------------
    static Screen browse(final MainActivity a, final Model.Server srv) {
        final SensorList list = new SensorList(a, null);
        final Screen sc = new Screen() {
            @Override
            void onData(Sensors.Table t) {
                list.update(t, a.store.settings.tempF);
            }

            @Override
            void onError(String m) {
                if (a.latest == null) list.showMessage("Cannot reach " + srv.name + ": " + m);
            }

            @Override
            Model.Server pollServer() {
                return srv;
            }

            @Override
            boolean needsData() {
                return true;
            }
        };
        sc.root = Ui.scaffoldView(a, srv.name + " \u2013 sensors", v -> a.pop(), list);
        return sc;
    }

    // ------------------------------------------------------------------
    // Sensor picker (used by the widget editor)
    // ------------------------------------------------------------------
    static Screen picker(final MainActivity a, final SensorList.OnPick cb) {
        final SensorList list = new SensorList(a, s -> {
            cb.pick(s);
            a.pop();
        });
        final Screen sc = new Screen() {
            @Override
            void onData(Sensors.Table t) {
                list.update(t, a.store.settings.tempF);
            }

            @Override
            void onError(String m) {
                if (a.latest == null) list.showMessage("Cannot reach the server: " + m);
            }

            @Override
            boolean needsData() {
                return true;
            }
        };
        sc.root = Ui.scaffoldView(a, "Choose a sensor", v -> a.pop(), list);
        if (a.latest != null) list.update(a.latest, a.store.settings.tempF);
        else if (a.serverById(a.dash.serverId) == null) {
            list.showMessage("No server selected. Add one under Servers first.");
        }
        return sc;
    }

    // ------------------------------------------------------------------
    // Dashboard settings (grid, padding, data source)
    // ------------------------------------------------------------------
    static Screen dashboardSettings(final MainActivity a) {
        final Model.Dashboard dd = a.dash;
        final LinearLayout body = Ui.vbox(a);
        final EditText name = Ui.edit(a, "Dashboard name", dd.name, InputType.TYPE_CLASS_TEXT);
        final Screen sc = new Screen() {
            @Override
            void onClose() {
                dd.name = name.getText().toString().trim();
                if (dd.name.length() == 0) dd.name = "Main";
                dd.fit();
                a.onDashChanged();
            }
        };
        sc.root = Ui.scaffold(a, "Dashboard", v -> a.pop(), body);

        body.addView(Ui.header(a, "Name"));
        body.addView(name);

        body.addView(Ui.header(a, "Data source"));
        final TextView srvBtn = Ui.button(a, serverName(a), null);
        srvBtn.setOnClickListener(v -> {
            final List<Model.Server> list = a.store.servers();
            if (list.isEmpty()) {
                Ui.toast(a, "Add a server first (menu \u2192 Servers)");
                return;
            }
            String[] names = new String[list.size()];
            for (int i = 0; i < names.length; i++) names[i] = list.get(i).name;
            Ui.dialog(a).setTitle("Data source").setItems(names, (dlg, which) -> {
                dd.serverId = list.get(which).id;
                srvBtn.setText(list.get(which).name);
            }).show();
        });
        Ui.add(body, srvBtn, 4);

        body.addView(Ui.header(a, "Grid"));
        body.addView(Ui.stepper(a, "Columns", new Ui.Range() {
            public int min() { return 1; }
            public int max() { return Model.MAX_COLS; }
            public int get() { return dd.cols; }
            public void set(int v) { dd.cols = v; dd.fit(); }
        }));
        body.addView(Ui.stepper(a, "Rows", new Ui.Range() {
            public int min() { return dd.minRows(); }
            public int max() { return Model.MAX_ROWS; }
            public int get() { return dd.rows; }
            public void set(int v) { dd.rows = v; }
        }));
        body.addView(Ui.stepper(a, "Padding between cells (dp)", new Ui.Range() {
            public int min() { return 0; }
            public int max() { return Model.MAX_GAP_DP; }
            public int get() { return dd.gap; }
            public void set(int v) { dd.gap = v; }
        }));
        body.addView(check(a, "Remove padding at the outer edge (use the full screen)", !dd.outerPadding,
                (btn, on) -> dd.outerPadding = !on));
        body.addView(Ui.text(a, "Long-press the dashboard to edit it. Cells are square; "
                + "the dashboard scrolls down when it has more rows than fit on the screen.", 12, Ui.SUB));
        return sc;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------
    static Screen settings(final MainActivity a) {
        final Model.Settings st = a.store.settings;
        final LinearLayout body = Ui.vbox(a);
        final Screen sc = new Screen() {
            @Override
            void onClose() {
                a.store.saveSettings();
                a.applySettings();
            }
        };
        sc.root = Ui.scaffold(a, "Settings", v -> a.pop(), body);

        final Runnable[] build = new Runnable[1];
        build[0] = () -> {
            body.removeAllViews();

            body.addView(Ui.header(a, "Colours (dashboard)"));
            body.addView(Ui.colorRow(a, "Font colour", st.fontColor, Model.WHITE,
                    col -> { st.fontColor = col; changed(a); }));
            body.addView(Ui.colorRow(a, "Label colour", st.labelColor, Model.WHITE,
                    col -> { st.labelColor = col; changed(a); }));

            body.addView(Ui.header(a, "Background"));
            body.addView(labeled(a, "Type", choice(a, "Background type",
                    new String[]{"Solid colour", "Gradient", "Image from this phone"}, st.bgMode,
                    which -> {
                        st.bgMode = which;
                        a.store.saveSettings();
                        a.applySettings();
                        build[0].run();
                    })));
            if (st.bgMode == 0) {
                body.addView(Ui.colorRow(a, "Background colour", st.bgColor, Model.BLACK,
                        col -> { st.bgColor = col; bgChanged(a); }));
            } else if (st.bgMode == 1) {
                body.addView(Ui.colorRow(a, "First colour", st.bgGrad1, 0xFF0F2027,
                        col -> { st.bgGrad1 = col; bgChanged(a); }));
                body.addView(Ui.colorRow(a, "Second colour", st.bgGrad2, 0xFF2C5364,
                        col -> { st.bgGrad2 = col; bgChanged(a); }));
                body.addView(check(a, "Use a third colour", st.bgGrad3On, (btn, on) -> {
                    st.bgGrad3On = on;
                    bgChanged(a);
                    build[0].run();
                }));
                if (st.bgGrad3On) {
                    body.addView(Ui.colorRow(a, "Third colour", st.bgGrad3, 0xFF203A43,
                            col -> { st.bgGrad3 = col; bgChanged(a); }));
                }
                body.addView(labeled(a, "Direction", choice(a, "Gradient direction",
                        new String[]{"Top to bottom", "Left to right", "Diagonal"}, st.bgGradDir,
                        which -> { st.bgGradDir = which; bgChanged(a); })));
            } else {
                Ui.add(body, Ui.button(a, "Choose image\u2026", v -> a.pickBackgroundImage()), 6);
                Ui.add(body, Ui.button(a, "Remove image", v -> {
                    a.removeBackgroundImage();
                    build[0].run();
                }), 6);
                body.addView(Ui.text(a, "Darken the image (keeps widgets readable)", 15, Ui.TEXT));
                final TextView dsv = Ui.text(a, st.bgDim + "%", 14, Ui.SUB);
                SeekBar dsb = new SeekBar(a);
                dsb.setMax(8);
                dsb.setProgress(Math.max(0, Math.min(8, st.bgDim / 10)));
                dsb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                        st.bgDim = p * 10;
                        dsv.setText(st.bgDim + "%");
                        a.applyBackground();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar s) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar s) {
                        a.store.saveSettings();
                    }
                });
                LinearLayout dr = Ui.hbox(a);
                dr.addView(dsb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                dr.addView(dsv, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.WRAP));
                body.addView(dr);
                body.addView(Ui.text(a, "The image is scaled to your screen size when you pick it, "
                        + "to keep memory use low.", 12, Ui.SUB));
            }

            body.addView(Ui.header(a, "Threshold colours"));
            body.addView(check(a, "Colour values when they pass a threshold", st.thEnabled,
                    (btn, on) -> { st.thEnabled = on; changed(a); }));
            body.addView(Ui.colorRow(a, "Warning colour", st.warnColor, 0xFFE5A50A,
                    col -> { st.warnColor = col; changed(a); }));
            body.addView(Ui.colorRow(a, "Critical colour", st.critColor, 0xFFDA4453,
                    col -> { st.critColor = col; changed(a); }));
            for (int i = 0; i < Model.TH_KEYS.length; i++) {
                LinearLayout card = Ui.card(a);
                card.addView(Ui.text(a, Model.TH_NAMES[i], 14, Ui.TEXT));
                card.addView(thresholdRow(a, st, i, false));
                card.addView(thresholdRow(a, st, i, true));
                Ui.add(body, card, 8);
            }
            Ui.add(body, Ui.button(a, "Reset thresholds to defaults", v -> {
                st.resetThresholds();
                changed(a);
                build[0].run();
            }), 10);
            body.addView(Ui.text(a, "Thresholds only change the colour of values; they are guides "
                    + "and can be turned off above.", 12, Ui.SUB));

            body.addView(Ui.header(a, "Text"));
            body.addView(Ui.text(a, "Label and value text size", 15, Ui.TEXT));
            final TextView tsv = Ui.text(a, st.textScale + "%", 14, Ui.SUB);
            SeekBar tsb = new SeekBar(a);
            tsb.setMax(10);
            tsb.setProgress(Math.max(0, Math.min(10, (st.textScale - 60) / 10)));
            tsb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    st.textScale = 60 + p * 10;
                    tsv.setText(st.textScale + "%");
                    a.dv.invalidate();
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    a.store.saveSettings();
                }
            });
            LinearLayout tr = Ui.hbox(a);
            tr.addView(tsb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            tr.addView(tsv, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.WRAP));
            body.addView(tr);
            body.addView(check(a, "Show sensor names on widgets (labels you type always show)", st.showNames,
                    (btn, on) -> { st.showNames = on; changed(a); }));
            body.addView(Ui.text(a, "Each widget can override both settings.", 12, Ui.SUB));

            body.addView(Ui.header(a, "Units"));
            body.addView(labeled(a, "Temperature", choice(a, "Temperature unit",
                    new String[]{"Celsius (\u00B0C)", "Fahrenheit (\u00B0F)"}, st.tempF ? 1 : 0,
                    which -> { st.tempF = which == 1; changed(a); build[0].run(); })));
            body.addView(labeled(a, "Network & disk speed", choice(a, "Network and disk speed",
                    new String[]{"Bytes (KB/s, MB/s)", "Bits (Kb/s, Mb/s)"}, st.rateBits ? 1 : 0,
                    which -> { st.rateBits = which == 1; Sensors.rateBits = st.rateBits; changed(a); })));
            body.addView(labeled(a, "Fan speed", choice(a, "Fan speed",
                    new String[]{"RPM", "Percentage"}, st.fanPercent ? 1 : 0,
                    which -> { st.fanPercent = which == 1; changed(a); })));
            body.addView(Ui.text(a, "Fan percentage = RPM divided by the sensor's maximum "
                    + "(default 3000). Set the real maximum per sensor in the widget editor.", 12, Ui.SUB));

            body.addView(Ui.header(a, "Update interval"));
            final TextView iv = Ui.text(a, "", 14, Ui.SUB);
            iv.setText(String.format(Locale.US, "%.1f s", st.intervalMs / 1000.0));
            SeekBar sb = new SeekBar(a);
            sb.setMax(19);
            sb.setProgress(Math.max(0, Math.min(19, st.intervalMs / 500 - 1)));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    st.intervalMs = (p + 1) * 500;
                    iv.setText(String.format(Locale.US, "%.1f s", st.intervalMs / 1000.0));
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    a.store.saveSettings();
                    a.applySettings();
                }
            });
            LinearLayout ir = Ui.hbox(a);
            ir.addView(sb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            ir.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.WRAP));
            body.addView(ir);
            body.addView(Ui.text(a, "Longer intervals use less battery and CPU on the phone.", 12, Ui.SUB));

            body.addView(Ui.header(a, "Screen"));
            body.addView(check(a, "Fullscreen (hide the status and navigation bars)", st.fullscreen, (btn, on) -> {
                st.fullscreen = on;
                a.store.saveSettings();
                a.applySettings();
            }));
            body.addView(labeled(a, "Menu button", choice(a, "Menu button",
                    new String[]{"Always visible", "Auto-hide", "Hidden"}, st.menuMode,
                    which -> {
                        st.menuMode = which;
                        a.store.saveSettings();
                        a.applySettings();
                    })));
            body.addView(Ui.text(a, "The menu always opens with a swipe from the left edge, "
                    + "or from the long-press menu.", 12, Ui.SUB));
            body.addView(check(a, "Keep the screen on", st.keepOn, (btn, on) -> {
                st.keepOn = on;
                a.store.saveSettings();
                a.applySettings();
            }));
            body.addView(labeled(a, "Orientation", choice(a, "Orientation",
                    new String[]{"Automatic", "Portrait", "Landscape"}, st.orientation,
                    which -> {
                        st.orientation = which;
                        a.store.saveSettings();
                        a.applySettings();
                    })));
        };
        build[0].run();
        return sc;
    }

    private static View thresholdRow(final MainActivity a, final Model.Settings st, final int idx,
                                     final boolean crit) {
        LinearLayout row = Ui.hbox(a);
        row.addView(Ui.text(a, crit ? "Critical" : "Warning", 13, Ui.SUB),
                new LinearLayout.LayoutParams(Ui.dp(a, 62), Ui.WRAP));
        final boolean pct = Model.TH_PERCENT[idx];
        final int base = pct ? 0 : 20;                 // percent: 0-100, temperature: 20-120 \u00B0C
        final TextView val = Ui.text(a, "", 13, Ui.SUB);
        val.setGravity(android.view.Gravity.END);
        final Runnable paint = () -> {
            float v = crit ? st.thCrit[idx] : st.thWarn[idx];
            val.setText(pct ? String.format(Locale.US, "%.0f%%", v)
                    : Sensors.format(Sensors.Unit.CELSIUS, v, st.tempF));
            val.setTextColor(st.thWarn[idx] >= st.thCrit[idx] ? Ui.DANGER : Ui.SUB);
        };
        paint.run();
        SeekBar sb = new SeekBar(a);
        sb.setMax(100);
        sb.setProgress(Math.max(0, Math.min(100, Math.round(crit ? st.thCrit[idx] : st.thWarn[idx]) - base)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (crit) st.thCrit[idx] = p + base;
                else st.thWarn[idx] = p + base;
                paint.run();
                a.dv.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                a.store.saveSettings();
            }
        });
        row.addView(sb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        row.addView(val, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.WRAP));
        return row;
    }

    // ------------------------------------------------------------------
    // Credits
    // ------------------------------------------------------------------
    private static void bgChanged(MainActivity a) {
        a.store.saveSettings();
        a.applyBackground();
    }

    // ------------------------------------------------------------------
    // Dashboards list
    // ------------------------------------------------------------------
    static Screen dashboards(final MainActivity a) {
        final Screen sc = new Screen();
        final LinearLayout body = Ui.vbox(a);
        sc.root = Ui.scaffold(a, "Dashboards", v -> a.pop(), body);
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            body.removeAllViews();
            for (final Model.Dashboard d : a.store.dashboards) {
                LinearLayout card = Ui.card(a);
                boolean isDef = d.id.equals(a.store.defaultId);
                card.addView(Ui.text(a, d.name + (isDef ? "   (default)" : ""), 16, Ui.TEXT));
                Model.Server srv = a.serverById(d.serverId);
                card.addView(Ui.text(a, (srv == null ? "no server" : srv.name) + "   \u2022 "
                        + d.widgets.size() + " widgets" + (d == a.dash ? "   \u2022 open now" : ""), 12, Ui.SUB));
                card.setOnClickListener(v -> {
                    a.switchDashboard(d);
                    a.openHome();
                });
                card.setOnLongClickListener(v -> {
                    dashboardMenu(a, d, refresh[0]);
                    return true;
                });
                Ui.add(body, card, 8);
            }
            Ui.add(body, Ui.button(a, "+ New dashboard", v -> newDashboardDialog(a)), 16);
            body.addView(Ui.text(a, "Tap to open. Long-press to set the default, rename, duplicate or "
                    + "delete. The default dashboard opens when the app starts.", 12, Ui.SUB));
        };
        refresh[0].run();
        return sc;
    }

    private static void dashboardMenu(final MainActivity a, final Model.Dashboard d, final Runnable done) {
        Ui.dialog(a).setTitle(d.name)
                .setItems(new CharSequence[]{"Open", "Set as default", "Rename", "Duplicate", "Delete"},
                        (dlg, which) -> {
                            switch (which) {
                                case 0:
                                    a.switchDashboard(d);
                                    a.openHome();
                                    break;
                                case 1:
                                    a.store.defaultId = d.id;
                                    a.store.saveDashboards();
                                    done.run();
                                    break;
                                case 2:
                                    renameDashboard(a, d, done);
                                    break;
                                case 3:
                                    duplicateDashboard(a, d);
                                    done.run();
                                    break;
                                default:
                                    deleteDashboard(a, d, done);
                                    break;
                            }
                        }).show();
    }

    private static void renameDashboard(final MainActivity a, final Model.Dashboard d, final Runnable done) {
        final EditText name = Ui.edit(a, "Dashboard name", d.name, InputType.TYPE_CLASS_TEXT);
        LinearLayout box = Ui.vbox(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        box.addView(name);
        Ui.dialog(a).setTitle("Rename dashboard").setView(box)
                .setPositiveButton("OK", (dlg, which) -> {
                    String n = name.getText().toString().trim();
                    if (n.length() > 0) d.name = n;
                    a.store.saveDashboards();
                    done.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static void duplicateDashboard(MainActivity a, Model.Dashboard d) {
        try {
            Model.Dashboard cp = Model.Dashboard.fromJson(d.toJson());
            cp.id = Model.newId();
            cp.name = d.name + " copy";
            int i = a.store.dashboards.indexOf(d);
            a.store.dashboards.add(i + 1, cp);
            a.store.saveDashboards();
        } catch (JSONException e) {
            Ui.toast(a, "Could not duplicate");
        }
    }

    private static void deleteDashboard(final MainActivity a, final Model.Dashboard d, final Runnable done) {
        if (a.store.dashboards.size() <= 1) {
            Ui.toast(a, "Keep at least one dashboard");
            return;
        }
        Ui.dialog(a).setTitle("Delete \"" + d.name + "\"?")
                .setPositiveButton("Delete", (dlg, which) -> {
                    a.store.dashboards.remove(d);
                    if (d.id.equals(a.store.defaultId)) a.store.defaultId = a.store.dashboards.get(0).id;
                    a.store.saveDashboards();
                    if (d == a.dash) a.switchDashboard(a.store.defaultDashboard());
                    done.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static void newDashboardDialog(final MainActivity a) {
        final EditText name = Ui.edit(a, "Dashboard name",
                "Dashboard " + (a.store.dashboards.size() + 1), InputType.TYPE_CLASS_TEXT);
        LinearLayout box = Ui.vbox(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        box.addView(name);
        Ui.dialog(a).setTitle("New dashboard").setView(box)
                .setPositiveButton("Create", (dlg, which) -> {
                    Model.Dashboard nd = new Model.Dashboard();
                    String n = name.getText().toString().trim();
                    if (n.length() > 0) nd.name = n;
                    nd.serverId = a.dash.serverId;
                    nd.fit();
                    a.store.dashboards.add(nd);
                    a.store.saveDashboards();
                    a.switchDashboard(nd);
                    a.openHome();
                })
                .setNegativeButton("Cancel", null).show();
    }

    static Screen credits(final MainActivity a) {
        final Screen sc = new Screen();
        LinearLayout body = Ui.vbox(a);
        sc.root = Ui.scaffold(a, "Credits", v -> a.pop(), body);
        body.addView(Ui.text(a, "Grafalinux System Monitor", 22, Ui.TEXT));
        Ui.add(body, Ui.text(a, "Version: v0.3.0 Beta", 15, Ui.SUB), 6);
        Ui.add(body, Ui.text(a, "Author: ItsNotMeBro87 and Claude", 15, Ui.TEXT), 18);
        Ui.add(body, Ui.text(a, "License: GPLv3", 15, Ui.TEXT), 18);
        Ui.add(body, Ui.text(a, "This program is free software: you can redistribute it and/or modify it "
                + "under the terms of the GNU General Public License version 3 as published by the Free "
                + "Software Foundation. It is distributed in the hope that it will be useful, but WITHOUT "
                + "ANY WARRANTY. See https://www.gnu.org/licenses/gpl-3.0.html", 13, Ui.SUB), 6);
        Ui.add(body, Ui.text(a, "Data comes from the linmon-agent service running on your PC.", 13, Ui.SUB), 18);
        return sc;
    }

    // ------------------------------------------------------------------
    // Widget editor
    // ------------------------------------------------------------------
    static Screen widgetEditor(final MainActivity a, final Model.Widget w, final boolean isNew) {
        final Model.Settings st = a.store.settings;
        final LinearLayout body = Ui.vbox(a);
        final Screen sc = new Screen() {
            @Override
            void onClose() {
                if (isNew && w.series.isEmpty()) a.dash.widgets.remove(w);
                a.onDashChanged();
            }

            @Override
            boolean needsData() {
                return true;
            }
        };
        sc.root = Ui.scaffold(a, isNew ? "New widget" : "Edit widget", v -> a.pop(), body);

        body.addView(Ui.header(a, "Appearance"));
        body.addView(labeled(a, "Style", choice(a, "Widget style", Model.TYPE_NAMES, w.type,
                which -> w.type = which)));
        final EditText title = Ui.edit(a, "Title (optional)", w.title, InputType.TYPE_CLASS_TEXT);
        Ui.onText(title, s -> w.title = s);
        Ui.add(body, title, 4);
        final EditText textField = Ui.edit(a, "Text (for Text widgets)", w.text,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textField.setSingleLine(false);
        textField.setMinLines(2);
        Ui.onText(textField, s -> w.text = s);
        Ui.add(body, textField, 4);
        body.addView(labeled(a, "Text alignment", choice(a, "Text alignment", Model.ALIGN_NAMES, w.align,
                which -> w.align = which)));
        body.addView(labeled(a, "Sensor names", choice(a, "Sensor names", Model.NAME_MODE_NAMES, w.nameMode,
                which -> w.nameMode = which)));
        body.addView(labeled(a, "Text size", choice(a, "Text size", Model.TEXT_SIZE_NAMES, w.textSize,
                which -> w.textSize = which)));
        body.addView(check(a, "Stacked (bars touch with text inside; gauges become concentric rings)",
                w.stacked, (btn, on) -> w.stacked = on));
        body.addView(Ui.colorRow(a, "Title colour", w.titleColor != 0 ? w.titleColor : st.fontColor,
                st.fontColor, col -> w.titleColor = (col == st.fontColor) ? 0 : col));

        body.addView(Ui.header(a, "Position and size (grid cells)"));
        body.addView(Ui.stepper(a, "Column", new Ui.Range() {
            public int min() { return 0; }
            public int max() { return Math.max(0, a.dash.cols - w.w); }
            public int get() { return w.x; }
            public void set(int v) { w.x = v; }
        }, 1));
        body.addView(Ui.stepper(a, "Row", new Ui.Range() {
            public int min() { return 0; }
            public int max() { return Math.max(0, Model.MAX_ROWS - w.h); }
            public int get() { return w.y; }
            public void set(int v) { w.y = v; }
        }, 1));
        body.addView(Ui.stepper(a, "Width", new Ui.Range() {
            public int min() { return 1; }
            public int max() { return Math.max(1, a.dash.cols - w.x); }
            public int get() { return w.w; }
            public void set(int v) { w.w = v; }
        }));
        body.addView(Ui.stepper(a, "Height", new Ui.Range() {
            public int min() { return 1; }
            public int max() { return Math.max(1, Model.MAX_ROWS - w.y); }
            public int get() { return w.h; }
            public void set(int v) { w.h = v; }
        }));

        body.addView(Ui.header(a, "Sensors"));
        final LinearLayout list = Ui.vbox(a);
        body.addView(list);
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            if (w.series.isEmpty()) {
                list.addView(Ui.text(a, "No sensors yet. Add one below.", 13, Ui.SUB));
            }
            for (final Model.Series s : new ArrayList<Model.Series>(w.series)) {
                LinearLayout row = Ui.hbox(a);
                row.setBackground(Ui.round(a, Ui.SURFACE, 8));
                row.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
                row.addView(Ui.swatch(a, s.color), new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
                TextView name = Ui.text(a, seriesName(a, s), 14, Ui.TEXT);
                name.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
                row.addView(name, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                TextView del = Ui.text(a, "\u2715", 18, Ui.SUB);
                del.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
                del.setOnClickListener(v -> {
                    w.series.remove(s);
                    rebuild[0].run();
                });
                row.addView(del);
                row.setOnClickListener(v -> seriesDialog(a, s, rebuild[0]));
                Ui.add(list, row, 6);
            }
        };
        rebuild[0].run();
        Ui.add(body, Ui.button(a, "+ Add sensor", v -> a.push(picker(a, sensor -> {
            Model.Series ns = new Model.Series();
            ns.sensorId = sensor.id;
            ns.color = Ui.PALETTE[w.series.size() % Ui.PALETTE.length];
            w.series.add(ns);
            rebuild[0].run();
        }))), 10);
        body.addView(Ui.text(a, "Tap a sensor to change its label, colour or range. "
                + "Gauges, bars and graphs can show several sensors at once.", 12, Ui.SUB));

        TextView del = Ui.button(a, "Delete widget", v -> Ui.dialog(a).setTitle("Delete this widget?")
                .setPositiveButton("Delete", (dlg, which) -> {
                    a.dash.widgets.remove(w);
                    a.pop();
                })
                .setNegativeButton("Cancel", null).show());
        del.setTextColor(Ui.DANGER);
        Ui.add(body, del, 28);
        return sc;
    }

    private static String seriesName(MainActivity a, Model.Series s) {
        if (s.label.length() > 0) return s.label;
        Sensors.Sensor sn = a.latest == null ? null : a.latest.map.get(s.sensorId);
        return sn != null ? sn.label : s.sensorId;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static double parse(EditText e) {
        try {
            return Double.parseDouble(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static void seriesDialog(final MainActivity a, final Model.Series s, final Runnable done) {
        LinearLayout box = Ui.vbox(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        Sensors.Sensor sn = a.latest == null ? null : a.latest.map.get(s.sensorId);
        box.addView(Ui.text(a, "Sensor: " + (sn != null ? sn.label : s.sensorId), 13, Ui.SUB));
        final EditText label = Ui.edit(a, "Custom label (optional)", s.label, InputType.TYPE_CLASS_TEXT);
        box.addView(label);
        final int[] colour = {s.color};
        box.addView(Ui.colorRow(a, "Colour", s.color, Model.WHITE, c2 -> colour[0] = c2));
        int numType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED;
        final EditText mn = Ui.edit(a, "Minimum (blank = automatic)",
                Double.isNaN(s.min) ? "" : fmt(s.min), numType);
        final EditText mx = Ui.edit(a, "Maximum (blank = automatic)",
                Double.isNaN(s.max) ? "" : fmt(s.max), numType);
        box.addView(mn);
        box.addView(mx);
        Ui.dialog(a).setTitle("Edit sensor").setView(box)
                .setPositiveButton("OK", (dlg, which) -> {
                    s.label = label.getText().toString().trim();
                    s.color = colour[0];
                    s.min = parse(mn);
                    s.max = parse(mx);
                    done.run();
                })
                .setNegativeButton("Cancel", null).show();
    }
}
