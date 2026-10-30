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
            Ui.add(body, Ui.button(a, "+ Add server", v -> addServerDialog(a, refresh[0])), 16);
            body.addView(Ui.text(a, "Tap a server to see its sensors. Long-press to remove it "
                    + "or use it for the dashboard.", 12, Ui.SUB));
        };
        refresh[0].run();
        return sc;
    }

    private static void serverMenu(final MainActivity a, final Model.Server s, final Runnable done) {
        Ui.dialog(a).setTitle(s.name)
                .setItems(new CharSequence[]{"Use for the dashboard", "Remove"}, (dlg, which) -> {
                    if (which == 0) {
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
        list.add(s);
        a.store.saveServers(list);
        if (a.dash.serverId.length() == 0) {
            a.dash.serverId = s.id;
            a.onDashChanged();
        }
    }

    private static void addServerDialog(final MainActivity a, final Runnable done) {
        LinearLayout box = Ui.vbox(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        final EditText name = Ui.edit(a, "Name (e.g. my PC)", "", InputType.TYPE_CLASS_TEXT);
        final EditText host = Ui.edit(a, "IP address or hostname", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText port = Ui.edit(a, "Port", "8787", InputType.TYPE_CLASS_NUMBER);
        final EditText token = Ui.edit(a, "Token (if the agent uses one)", "", InputType.TYPE_CLASS_TEXT);
        box.addView(name);
        box.addView(host);
        box.addView(port);
        box.addView(token);

        final AlertDialog dlg = Ui.dialog(a).setTitle("Add server").setView(box)
                .setPositiveButton("Test & save", null)
                .setNegativeButton("Cancel", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final Model.Server s = new Model.Server();
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
            body.addView(Ui.colorRow(a, "Background colour", st.bgColor, Model.BLACK,
                    col -> { st.bgColor = col; changed(a); }));

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

            body.addView(Ui.header(a, "Units"));
            body.addView(labeled(a, "Temperature", choice(a, "Temperature unit",
                    new String[]{"Celsius (\u00B0C)", "Fahrenheit (\u00B0F)"}, st.tempF ? 1 : 0,
                    which -> { st.tempF = which == 1; changed(a); build[0].run(); })));

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
    static Screen credits(final MainActivity a) {
        final Screen sc = new Screen();
        LinearLayout body = Ui.vbox(a);
        sc.root = Ui.scaffold(a, "Credits", v -> a.pop(), body);
        body.addView(Ui.text(a, "Grafalinux System Monitor", 22, Ui.TEXT));
        Ui.add(body, Ui.text(a, "Version: v0.1.0 Beta", 15, Ui.SUB), 6);
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
        body.addView(labeled(a, "Text alignment", choice(a, "Text alignment", Model.ALIGN_NAMES, w.align,
                which -> w.align = which)));
        body.addView(Ui.colorRow(a, "Title colour", w.titleColor != 0 ? w.titleColor : st.fontColor,
                st.fontColor, col -> w.titleColor = (col == st.fontColor) ? 0 : col));

        body.addView(Ui.header(a, "Position and size (grid cells)"));
        body.addView(Ui.stepper(a, "Column", new Ui.Range() {
            public int min() { return 0; }
            public int max() { return Math.max(0, a.dash.cols - w.w); }
            public int get() { return w.x; }
            public void set(int v) { w.x = v; }
        }));
        body.addView(Ui.stepper(a, "Row", new Ui.Range() {
            public int min() { return 0; }
            public int max() { return Math.max(0, Model.MAX_ROWS - w.h); }
            public int get() { return w.y; }
            public void set(int v) { w.y = v; }
        }));
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
