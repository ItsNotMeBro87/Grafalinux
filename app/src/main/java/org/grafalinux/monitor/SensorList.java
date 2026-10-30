package org.grafalinux.monitor;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Collapsible, categorised list of sensors with live values (like HWiNFO / AIDA64). */
final class SensorList extends ScrollView {
    interface OnPick {
        void pick(Sensors.Sensor s);
    }

    private final LinearLayout box;
    private final HashMap<String, TextView> vals = new HashMap<String, TextView>();
    private final HashSet<String> open = new HashSet<String>();
    private final OnPick cb;
    private int builtCount = -1;

    SensorList(Context c, OnPick cb) {
        super(c);
        this.cb = cb;
        box = Ui.vbox(c);
        box.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 28));
        addView(box, new LayoutParams(Ui.MATCH, Ui.WRAP));
        open.add("CPU");
        showMessage("Connecting\u2026");
    }

    void showMessage(String m) {
        box.removeAllViews();
        vals.clear();
        builtCount = -1;
        box.addView(Ui.text(getContext(), m, 15, Ui.SUB));
    }

    void update(Sensors.Table t, boolean tempF) {
        if (t == null) return;
        if (t.map.size() != builtCount) rebuild(t, tempF);
        for (Map.Entry<String, TextView> e : vals.entrySet()) {
            Sensors.Sensor s = t.map.get(e.getKey());
            if (s != null) e.getValue().setText(Sensors.format(s.unit, s.value, tempF));
        }
    }

    private void setHead(TextView head, LinearLayout kids, String cat, int count, boolean isOpen) {
        head.setText((isOpen ? "\u25BE  " : "\u25B8  ") + cat + "  (" + count + ")");
        kids.setVisibility(isOpen ? View.VISIBLE : View.GONE);
    }

    private void rebuild(Sensors.Table t, boolean tempF) {
        Context c = getContext();
        box.removeAllViews();
        vals.clear();
        builtCount = t.map.size();
        for (Map.Entry<String, ArrayList<Sensors.Sensor>> ce : t.byCat.entrySet()) {
            final String cat = ce.getKey();
            final ArrayList<Sensors.Sensor> list = ce.getValue();
            final LinearLayout kids = Ui.vbox(c);
            final TextView head = Ui.text(c, "", 15, Ui.ACCENT);
            head.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
            head.setBackground(Ui.round(c, Ui.SURFACE, 8));
            setHead(head, kids, cat, list.size(), open.contains(cat));
            head.setOnClickListener(v -> {
                boolean now = !open.contains(cat);
                if (now) open.add(cat);
                else open.remove(cat);
                setHead(head, kids, cat, list.size(), now);
            });
            Ui.add(box, head, 8);

            for (final Sensors.Sensor s : list) {
                LinearLayout row = Ui.hbox(c);
                row.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
                row.addView(Ui.text(c, s.label, 14, Ui.TEXT), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                TextView val = Ui.text(c, Sensors.format(s.unit, s.value, tempF), 14, Ui.SUB);
                row.addView(val);
                vals.put(s.id, val);
                if (cb != null) {
                    row.setClickable(true);
                    row.setBackground(Ui.round(c, 0x22FFFFFF, 6));
                    row.setOnClickListener(v -> cb.pick(s));
                }
                Ui.add(kids, row, cb != null ? 4 : 0);
            }
            Ui.add(box, kids, 0);
        }
    }
}
