package org.grafalinux.monitor;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Small helpers for building the (fully programmatic) user interface. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF000000;
    static final int SURFACE = 0xFF1C1C1E;
    static final int SURFACE2 = 0xFF2C2C30;
    static final int TEXT = 0xFFFFFFFF;
    static final int SUB = 0xFFA0A0A8;
    static final int ACCENT = 0xFF4FC3F7;
    static final int DANGER = 0xFFDA4453;

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final int[] PALETTE = {0xFFFFFFFF, 0xFF4FC3F7, 0xFFFFB74D, 0xFF81C784,
            0xFFE57373, 0xFFBA68C8, 0xFFFFF176, 0xFF4DB6AC};

    interface ColorCb { void on(int color); }
    interface TextCb { void on(String s); }
    interface Range {
        int min();
        int max();
        int get();
        void set(int v);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static String hexOf(int color) {
        return String.format(Locale.US, "#%06X", color & 0xFFFFFF);
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float weight, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.topMargin = dp(c, topDp);
        return p;
    }

    /** Adds a full-width child with a top margin. */
    static void add(LinearLayout parent, View v, int topDp) {
        parent.addView(v, lp(parent.getContext(), MATCH, WRAP, 0, topDp));
    }

    static TextView header(Context c, String s) {
        TextView t = text(c, s.toUpperCase(Locale.US), 12, ACCENT);
        t.setPadding(0, dp(c, 18), 0, dp(c, 4));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(round(c, SURFACE, 10));
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return l;
    }

    static TextView button(Context c, String label, View.OnClickListener l) {
        TextView t = text(c, label, 14, ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        t.setBackground(round(c, SURFACE2, 8));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    static EditText edit(Context c, String hint, String value, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(inputType);
        e.setTextColor(TEXT);
        e.setHintTextColor(SUB);
        e.setSingleLine(true);
        return e;
    }

    static void onText(EditText e, final TextCb cb) {
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                cb.on(s.toString());
            }
        });
    }

    static AlertDialog.Builder dialog(Context c) {
        return new ScrollBuilder(c, android.R.style.Theme_Material_Dialog_Alert);
    }

    /** A dialog builder that always puts the content in a ScrollView, so nothing is cut off in landscape. */
    private static final class ScrollBuilder extends AlertDialog.Builder {
        ScrollBuilder(Context c, int theme) {
            super(c, theme);
        }

        @Override
        public AlertDialog.Builder setView(View v) {
            if (v instanceof ScrollView) return super.setView(v);
            ScrollView sv = new ScrollView(getContext());
            sv.setFillViewport(false);
            sv.addView(v, new ViewGroup.LayoutParams(MATCH, WRAP));
            return super.setView(sv);
        }
    }

    // ------------------------------------------------------------------
    static View swatch(Context c, int color) {
        View v = new View(c);
        setSwatch(c, v, color);
        return v;
    }

    static void setSwatch(Context c, View v, int color) {
        GradientDrawable g = round(c, color, 6);
        g.setStroke(dp(c, 1), 0x66FFFFFF);
        v.setBackground(g);
    }

    /** A labelled colour row: tap the swatch to pick a colour, "Reset" restores def. */
    static View colorRow(final Context c, String label, int color, final int def, final ColorCb cb) {
        LinearLayout row = hbox(c);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));
        row.addView(text(c, label, 15, TEXT), new LinearLayout.LayoutParams(0, WRAP, 1));
        final int[] cur = {color};
        final View sw = swatch(c, color);
        final TextView hex = text(c, hexOf(color), 12, SUB);
        hex.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        sw.setOnClickListener(v -> pickColor(c, cur[0], col -> {
            cur[0] = col;
            setSwatch(c, sw, col);
            hex.setText(hexOf(col));
            cb.on(col);
        }));
        TextView reset = text(c, "Reset", 13, SUB);
        reset.setPadding(dp(c, 8), dp(c, 8), dp(c, 4), dp(c, 8));
        reset.setOnClickListener(v -> {
            cur[0] = def;
            setSwatch(c, sw, def);
            hex.setText(hexOf(def));
            cb.on(def);
        });
        row.addView(hex);
        row.addView(sw, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 28)));
        row.addView(reset);
        return row;
    }

    /** Colour picker dialog: colour wheel, hex entry and a few presets. */
    static void pickColor(final Context c, int initial, final ColorCb cb) {
        final int[] cur = {initial | 0xFF000000};
        final boolean[] busy = {false};
        LinearLayout box = vbox(c);
        box.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 4));

        final ColorWheelView wheel = new ColorWheelView(c);
        wheel.setColor(cur[0]);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(MATCH, WRAP);
        wp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(wheel, wp);

        LinearLayout row = hbox(c);
        final View prev = new View(c);
        setSwatch(c, prev, cur[0]);
        row.addView(prev, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 36)));
        final EditText hex = edit(c, "#RRGGBB", hexOf(cur[0]),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, WRAP, 1);
        hp.leftMargin = dp(c, 12);
        row.addView(hex, hp);
        box.addView(row, lp(c, MATCH, WRAP, 0, 8));

        wheel.setListener(col -> {
            cur[0] = col | 0xFF000000;
            busy[0] = true;
            setSwatch(c, prev, cur[0]);
            hex.setText(hexOf(cur[0]));
            busy[0] = false;
        });

        onText(hex, s -> {
            if (busy[0]) return;
            try {
                String t = s.trim();
                if (!t.startsWith("#")) t = "#" + t;
                if (t.length() == 7) {
                    cur[0] = Color.parseColor(t) | 0xFF000000;
                    setSwatch(c, prev, cur[0]);
                    wheel.setColor(cur[0]);
                }
            } catch (IllegalArgumentException ignored) {
                // incomplete or invalid hex: wait for more input
            }
        });

        LinearLayout presets = hbox(c);
        for (int i = 0; i < PALETTE.length; i++) {
            final int col = PALETTE[i];
            View sw = swatch(c, col);
            sw.setOnClickListener(v -> {
                cur[0] = col;
                busy[0] = true;
                setSwatch(c, prev, col);
                hex.setText(hexOf(col));
                wheel.setColor(col);
                busy[0] = false;
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(c, 28), 1);
            p.rightMargin = dp(c, 4);
            presets.addView(sw, p);
        }
        box.addView(presets, lp(c, MATCH, WRAP, 0, 10));

        ScrollView sv = new ScrollView(c);
        sv.addView(box, new ViewGroup.LayoutParams(MATCH, WRAP));
        dialog(c).setTitle("Pick a colour").setView(sv)
                .setPositiveButton("OK", (dlg, w) -> cb.on(cur[0]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "label   [-]  value  [+]" row. */
    static View stepper(final Context c, String label, final Range r) {
        return stepper(c, label, r, 0);
    }

    /** Same, but shows value + offset (used to display 1-based rows and columns). */
    static View stepper(final Context c, String label, final Range r, final int offset) {
        LinearLayout row = hbox(c);
        row.setPadding(0, dp(c, 4), 0, dp(c, 4));
        row.addView(text(c, label, 15, TEXT), new LinearLayout.LayoutParams(0, WRAP, 1));
        final TextView val = text(c, String.valueOf(r.get() + offset), 16, TEXT);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(dp(c, 48));
        TextView minus = button(c, "\u2212", v -> {
            r.set(Math.max(r.min(), r.get() - 1));
            val.setText(String.valueOf(r.get() + offset));
        });
        TextView plus = button(c, "+", v -> {
            r.set(Math.min(r.max(), r.get() + 1));
            val.setText(String.valueOf(r.get() + offset));
        });
        row.addView(minus, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 40)));
        row.addView(val);
        row.addView(plus, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 40)));
        return row;
    }

    // ------------------------------------------------------------------
    /** Screen with a top bar (back arrow + title) and arbitrary content below it. */
    static LinearLayout scaffoldView(Context c, String title, View.OnClickListener back, View content) {
        LinearLayout root = vbox(c);
        root.setBackgroundColor(BG);
        LinearLayout bar = hbox(c);
        bar.setBackgroundColor(0xFF101012);
        TextView b = text(c, "\u2190", 22, TEXT);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(back);
        bar.addView(b, new LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)));
        bar.addView(text(c, title, 18, TEXT), new LinearLayout.LayoutParams(0, WRAP, 1));
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(c, 52)));
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1));
        return root;
    }

    /** Same, but wraps a vertical body in a ScrollView. */
    static LinearLayout scaffold(Context c, String title, View.OnClickListener back, LinearLayout body) {
        ScrollView sv = new ScrollView(c);
        body.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 28));
        sv.addView(body, new ViewGroup.LayoutParams(MATCH, WRAP));
        return scaffoldView(c, title, back, sv);
    }
}
