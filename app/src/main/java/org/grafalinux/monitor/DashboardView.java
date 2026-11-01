package org.grafalinux.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Draws the whole dashboard (grid + widgets) itself on one canvas. No child views and no chart
 * library, so it stays light on memory and CPU; it redraws only when new data arrives or while
 * you drag something in edit mode.
 */
final class DashboardView extends View {
    interface Host {
        void onWidgetTap(Model.Widget w);

        void onLongPress();

        /** A drag or resize was committed. */
        void onChanged();
    }

    Model.Dashboard dash;
    Sensors.Table table;
    History hist;
    Model.Settings st;
    Host host;
    boolean editing = false;
    String hint = "";

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path path2 = new Path();
    private final RectF wr = new RectF();
    private final RectF tmp = new RectF();
    private final double[] rg = new double[2];
    private final float d;
    private float cell, gap, pad;
    private final GestureDetector gd;

    // drag / resize state (edit mode)
    private Model.Widget dragW, resW;
    private int ghostX, ghostY, ghostW, ghostH;
    private boolean ghostOk = true;
    private float fx, fy, offX, offY;

    DashboardView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                Model.Widget w = hit(e.getX(), e.getY());
                if (editing && w != null && host != null) host.onWidgetTap(w);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (dash == null) return;
                Model.Widget w = hit(fx, fy);
                if (editing && !dash.locked && w != null) {
                    startDrag(w);
                    return;
                }
                if (host != null) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    host.onLongPress();
                }
            }
        });
    }

    void setTable(Sensors.Table t) {
        table = t;
        invalidate();
    }

    void relayout() {
        requestLayout();
        invalidate();
    }

    // ------------------------------------------------------------------
    // layout
    // ------------------------------------------------------------------
    private int visibleRows() {
        return Math.max(1, dash.rows) + (editing ? 2 : 0);   // spare rows to drop widgets into
    }

    private void metrics(int w) {
        gap = dash.gap * d;
        pad = dash.outerPadding ? gap : 0;
        cell = (w - 2 * pad - (dash.cols - 1) * gap) / dash.cols;
        if (cell < 1) cell = 1;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (dash == null) {
            setMeasuredDimension(w, 0);
            return;
        }
        metrics(w);
        int rows = visibleRows();
        int h = (int) (2 * pad + rows * cell + (rows - 1) * gap);
        setMeasuredDimension(w, h);
    }

    private void widgetRect(Model.Widget w, RectF out) {
        float l = pad + w.x * (cell + gap);
        float t = pad + w.y * (cell + gap);
        out.set(l, t, l + w.w * cell + (w.w - 1) * gap, t + w.h * cell + (w.h - 1) * gap);
    }

    private Model.Widget hit(float x, float y) {
        if (dash == null) return null;
        for (int i = dash.widgets.size() - 1; i >= 0; i--) {
            Model.Widget w = dash.widgets.get(i);
            widgetRect(w, tmp);
            if (tmp.contains(x, y)) return w;
        }
        return null;
    }

    private Model.Widget handleHit(float x, float y) {
        float hs = 30 * d;
        for (int i = dash.widgets.size() - 1; i >= 0; i--) {
            Model.Widget w = dash.widgets.get(i);
            widgetRect(w, tmp);
            if (x <= tmp.right && x > tmp.right - hs && y <= tmp.bottom && y > tmp.bottom - hs) return w;
        }
        return null;
    }

    private boolean overlaps(Model.Widget self, int x, int y, int w, int h) {
        for (Model.Widget o : dash.widgets) {
            if (o == self) continue;
            if (x < o.x + o.w && x + w > o.x && y < o.y + o.h && y + h > o.y) return true;
        }
        return false;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------
    // touch: tap / long-press / drag / resize
    // ------------------------------------------------------------------
    private void disallowIntercept() {
        ViewParent p = getParent();
        if (p != null) p.requestDisallowInterceptTouchEvent(true);
    }

    private void startDrag(Model.Widget w) {
        widgetRect(w, tmp);
        dragW = w;
        offX = fx - tmp.left;
        offY = fy - tmp.top;
        ghostX = w.x;
        ghostY = w.y;
        ghostOk = true;
        disallowIntercept();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        postOnAnimation(scroller);
        invalidate();
    }

    private void startResize(Model.Widget w) {
        resW = w;
        ghostW = w.w;
        ghostH = w.h;
        ghostOk = true;
        disallowIntercept();
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        postOnAnimation(scroller);
        invalidate();
    }

    private void updateGhost() {
        float step = cell + gap;
        if (dragW != null) {
            int nx = clamp(Math.round((fx - offX - pad) / step), 0, dash.cols - dragW.w);
            int ny = clamp(Math.round((fy - offY - pad) / step), 0, Model.MAX_ROWS - dragW.h);
            ghostX = nx;
            ghostY = ny;
            ghostOk = !overlaps(dragW, nx, ny, dragW.w, dragW.h);
        } else if (resW != null) {
            widgetRect(resW, tmp);
            int nw = clamp(Math.round((fx - tmp.left + gap) / step), 1, dash.cols - resW.x);
            int nh = clamp(Math.round((fy - tmp.top + gap) / step), 1, Model.MAX_ROWS - resW.y);
            ghostW = nw;
            ghostH = nh;
            ghostOk = !overlaps(resW, resW.x, resW.y, nw, nh);
        }
        invalidate();
    }

    private void finishDrag(boolean commit) {
        Model.Widget w = dragW;
        dragW = null;
        if (commit && w != null && ghostOk && (ghostX != w.x || ghostY != w.y)) {
            w.x = ghostX;
            w.y = ghostY;
            if (host != null) host.onChanged();
        }
        invalidate();
    }

    private void finishResize(boolean commit) {
        Model.Widget w = resW;
        resW = null;
        if (commit && w != null && ghostOk && (ghostW != w.w || ghostH != w.h)) {
            w.w = ghostW;
            w.h = ghostH;
            if (host != null) host.onChanged();
        }
        invalidate();
    }

    /** Scrolls the dashboard while a widget is dragged near the top or bottom edge. */
    private final Runnable scroller = new Runnable() {
        @Override
        public void run() {
            if (dragW == null && resW == null) return;
            ViewParent vp = getParent();
            if (vp instanceof View) {
                View p = (View) vp;
                float edge = 56 * d;
                float rel = fy - p.getScrollY();
                int dy = 0;
                if (rel < edge) {
                    dy = -(int) (Math.min(1f, (edge - rel) / edge) * 9 * d);
                } else if (rel > p.getHeight() - edge) {
                    dy = (int) (Math.min(1f, (rel - (p.getHeight() - edge)) / edge) * 9 * d);
                }
                if (dy != 0) {
                    int before = p.getScrollY();
                    p.scrollBy(0, dy);
                    int moved = p.getScrollY() - before;
                    if (moved != 0) {
                        fy += moved;
                        updateGhost();
                    }
                }
            }
            postOnAnimation(this);
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        fx = e.getX();
        fy = e.getY();
        if (editing && dash != null && !dash.locked) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    Model.Widget h = handleHit(fx, fy);
                    if (h != null) {
                        startResize(h);
                        return true;
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    if (resW != null) {
                        updateGhost();
                        return true;
                    }
                    if (dragW != null) {
                        updateGhost();
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (resW != null) {
                        finishResize(true);
                        return true;
                    }
                    if (dragW != null) {
                        finishDrag(true);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (resW != null) {
                        finishResize(false);
                        return true;
                    }
                    if (dragW != null) {
                        finishDrag(false);
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return gd.onTouchEvent(e);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private static final float[] THICK = {0.07f, 0.11f, 0.16f};   // single ring thickness / diameter
    private static final float[] BAND = {0.45f, 0.62f, 0.82f};    // stacked rings: share of the radius

    private Sensors.Sensor sensor(Model.Series s) {
        return table == null ? null : table.map.get(s.sensorId);
    }

    private float tscale(Model.Widget w) {
        switch (w.textSize) {
            case 1:
                return 0.8f;
            case 2:
                return 1f;
            case 3:
                return 1.3f;
            case 4:
                return 1.6f;
            default:
                return st.textScale / 100f;
        }
    }

    /** Text size: cap scaled by the user's text size, but never larger than what fits (limit). */
    private static float size(float cap, float limit, float ts) {
        return ts >= 1f ? Math.min(cap * ts, limit) : Math.min(cap, limit) * ts;
    }

    private boolean showNames(Model.Widget w) {
        return w.nameMode == 0 ? st.showNames : w.nameMode == 1;
    }

    /** The label to draw for a series: a custom label always, the sensor name only if enabled. */
    private String labelFor(Model.Widget w, Model.Series s, Sensors.Sensor sn) {
        if (s.label.length() > 0) return s.label;
        if (!showNames(w)) return "";
        return sn != null ? sn.label : s.sensorId;
    }

    private boolean anyLabel(Model.Widget w) {
        for (int i = 0; i < w.series.size(); i++) {
            Model.Series s = w.series.get(i);
            if (labelFor(w, s, sensor(s)).length() > 0) return true;
        }
        return false;
    }

    private String iconName(Model.Series s, Sensors.Sensor sn) {
        if (!st.showIcons || "none".equals(s.icon)) return null;
        if (s.icon.length() > 0) return Icons.indexOf(s.icon) >= 0 ? s.icon : null;
        return Icons.auto(sn);
    }

    private boolean anyIcon(Model.Widget w) {
        for (int i = 0; i < w.series.size(); i++) {
            Model.Series s = w.series.get(i);
            if (iconName(s, sensor(s)) != null) return true;
        }
        return false;
    }

    // ---- fan speed: percent <-> RPM using the series min / max (RPM at 0 % and 100 %) ----
    private double fanPct(Model.Series s, Sensors.Sensor sn, double v) {
        if (sn.unit != Sensors.Unit.RPM) return v;
        double mn = !Double.isNaN(s.min) ? s.min : 0;
        double mx = !Double.isNaN(s.max) ? s.max : (!Double.isNaN(sn.max) ? sn.max : 3000);
        if (mx <= mn) mx = mn + 1;
        return 100.0 * (v - mn) / (mx - mn);
    }

    private double fanRpm(Model.Series s, Sensors.Sensor sn, double v) {
        if (sn.unit == Sensors.Unit.RPM) return v;
        if (Double.isNaN(s.min) || Double.isNaN(s.max) || s.max <= s.min) return Double.NaN;
        return s.min + v / 100.0 * (s.max - s.min);
    }

    /** Value and unit as two strings. */
    private String[] parts(Model.Series s, Sensors.Sensor sn) {
        if (sn == null) return new String[]{"--", ""};
        return partsAt(s, sn, sn.value);
    }

    /** Same as parts(), for any value of the sensor (used for the "max" column of a legend table). */
    private String[] partsAt(Model.Series s, Sensors.Sensor sn, double v) {
        if (sn.fan) {
            int mode = s.fanMode == 0 ? (st.fanPercent ? 1 : 2) : s.fanMode;
            if (mode == 2) {
                double rpm = fanRpm(s, sn, v);
                if (!Double.isNaN(rpm)) return new String[]{String.format(Locale.US, "%.0f", rpm), "RPM"};
            }
            return new String[]{String.format(Locale.US, "%.0f", fanPct(s, sn, v)), "%"};
        }
        return Sensors.formatParts(sn.unit, v, st.tempF);
    }

    private static String join(String[] p) {
        if (p[1].length() == 0) return p[0];
        if (p[1].equals("%") || p[1].startsWith("\u00B0")) return p[0] + p[1];
        return p[0] + " " + p[1];
    }

    private String valueText(Model.Series s, Sensors.Sensor sn) {
        return join(parts(s, sn));
    }

    private double[] range(Model.Series s, Sensors.Sensor sn) {
        double mn = !Double.isNaN(s.min) ? s.min : (sn != null ? sn.min : 0);
        double mx;
        if (!Double.isNaN(s.max)) mx = s.max;
        else if (sn != null && !Double.isNaN(sn.max)) mx = sn.max;
        else if (sn != null) mx = Math.max(hist.peak(s.sensorId), 1048576.0);
        else mx = 100;
        if (mx <= mn) mx = mn + 1;
        rg[0] = mn;
        rg[1] = mx;
        return rg;
    }

    /** 0..1 position of a value inside the series range (fans are measured in percent). */
    private float norm(Model.Series s, Sensors.Sensor sn, double v) {
        if (sn == null) return 0;
        double f;
        if (sn.fan) {
            f = fanPct(s, sn, v) / 100.0;
        } else {
            double[] r = range(s, sn);
            f = (v - r[0]) / (r[1] - r[0]);
        }
        return (float) Math.max(0, Math.min(1, f));
    }

    private float fraction(Model.Series s, Sensors.Sensor sn) {
        return sn == null ? 0 : norm(s, sn, sn.value);
    }

    private static int lerp(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int ar = Color.alpha(a), rr = Color.red(a), gg = Color.green(a), bb = Color.blue(a);
        return Color.argb((int) (ar + (Color.alpha(b) - ar) * t), (int) (rr + (Color.red(b) - rr) * t),
                (int) (gg + (Color.green(b) - gg) * t), (int) (bb + (Color.blue(b) - bb) * t));
    }

    private int colorFor(Model.Series s, Sensors.Sensor sn) {
        return colorAt(s.color, sn, sn != null ? sn.value : 0);
    }

    /** The colour for value v of sensor sn: base, or a warning / critical colour past a threshold. */
    private int colorAt(int base, Sensors.Sensor sn, double v) {
        if (st.thEnabled && sn != null && "battery".equals(sn.thKey)) {
            // battery: LOW is bad (warning is above critical)
            float crit = st.batCrit;
            float warn = Math.max(st.batWarn, crit + 1);
            if (v <= crit) return st.critColor;
            if (v <= warn) {
                return st.thGradient ? lerp(st.warnColor, st.critColor, (warn - v) / (warn - crit)) : st.warnColor;
            }
            if (st.thGradient) {
                float end = Math.min(100f, warn * 1.25f);
                if (v < end && end > warn) return lerp(base, st.warnColor, (end - v) / (end - warn));
            }
            return base;
        }
        if (st.thEnabled && sn != null) {
            int i = Model.thIndex(sn.thKey);
            if (i >= 0) {
                float warn = st.thWarn[i];
                float crit = Math.max(st.thCrit[i], warn + 1);
                if (v >= crit) return st.critColor;
                if (v >= warn) {
                    return st.thGradient ? lerp(st.warnColor, st.critColor, (v - warn) / (crit - warn)) : st.warnColor;
                }
                if (st.thGradient) {
                    float start = warn * 0.75f;
                    if (v > start) return lerp(base, st.warnColor, (v - start) / (warn - start));
                }
            }
        }
        return base;
    }

    /** Draws s inside [l, r], shrinking the text to fit; align 0 = left, 1 = centre, 2 = right. */
    private void drawText(Canvas c, String s, float l, float r, float baseline, int align, float size) {
        if (size < 1 || s.length() == 0) return;
        textP.setTextSize(size);
        float mw = textP.measureText(s);
        float avail = r - l;
        if (mw > avail && mw > 0) textP.setTextSize(Math.max(1, size * avail / mw));
        float x;
        if (align == 0) {
            textP.setTextAlign(Paint.Align.LEFT);
            x = l;
        } else if (align == 2) {
            textP.setTextAlign(Paint.Align.RIGHT);
            x = r;
        } else {
            textP.setTextAlign(Paint.Align.CENTER);
            x = (l + r) / 2f;
        }
        c.drawText(s, x, baseline, textP);
    }

    /** Draws a number with its unit in a smaller size next to it, fitted into [l, r]. */
    private void drawParts(Canvas c, String[] p, float l, float r, float baseline, int align, float size,
                           int color, boolean bold) {
        if (size < 1) return;
        boolean unit = p[1].length() > 0;
        float wn = 0, wu = 0, g = 0;
        for (int pass = 0; pass < 2; pass++) {
            textP.setFakeBoldText(bold);
            textP.setTextSize(size);
            wn = textP.measureText(p[0]);
            g = unit ? size * 0.08f : 0;
            wu = 0;
            if (unit) {
                textP.setTextSize(size * 0.5f);
                wu = textP.measureText(p[1]);
            }
            float total = wn + g + wu;
            if (total <= r - l || total <= 0) break;
            size *= (r - l) / total;
        }
        float total = wn + g + wu;
        float x = align == 0 ? l : (align == 2 ? r - total : (l + r - total) / 2f);
        textP.setColor(color);
        textP.setTextAlign(Paint.Align.LEFT);
        textP.setTextSize(size);
        c.drawText(p[0], x, baseline, textP);
        if (unit) {
            textP.setFakeBoldText(false);
            textP.setTextSize(size * 0.5f);
            c.drawText(p[1], x + wn + g, baseline, textP);
        }
        textP.setFakeBoldText(false);
    }

    /** "icon label" on one line, aligned like the rest of the widget. */
    private void drawIconLabel(Canvas c, String ic, String lab, float l, float r, float baseline,
                               int align, float size) {
        if (ic == null && lab.length() == 0) return;
        float is = size * 1.15f;
        float gapW = (ic != null && lab.length() > 0) ? size * 0.3f : 0;
        float tw = 0;
        if (lab.length() > 0) {
            textP.setTextSize(size);
            tw = textP.measureText(lab);
        }
        float total = (ic != null ? is : 0) + gapW + tw;
        float k = total > r - l && total > 0 ? (r - l) / total : 1f;
        size *= k;
        is *= k;
        gapW *= k;
        tw *= k;
        total *= k;
        float x = align == 0 ? l : (align == 2 ? r - total : (l + r - total) / 2f);
        if (ic != null) {
            Icons.draw(c, ic, x, baseline - is * 0.82f, is, st.iconColor);
            x += is + gapW;
        }
        if (lab.length() > 0) {
            textP.setColor(st.labelColor);
            textP.setTextSize(size);
            textP.setTextAlign(Paint.Align.LEFT);
            c.drawText(lab, x, baseline, textP);
        }
    }

    // ------------------------------------------------------------------
    // drawing
    // ------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas c) {
        if (st == null) return;
        if (dash == null) return;

        boolean canEdit = editing && !dash.locked;

        if (editing) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x14FFFFFF);
            int rows = visibleRows();
            for (int r = 0; r < rows; r++) {
                for (int col = 0; col < dash.cols; col++) {
                    float l = pad + col * (cell + gap);
                    float t = pad + r * (cell + gap);
                    tmp.set(l, t, l + cell, t + cell);
                    if (c.quickReject(tmp, Canvas.EdgeType.AA)) continue;
                    c.drawRoundRect(tmp, 6 * d, 6 * d, fill);
                }
            }
        }

        for (int i = 0; i < dash.widgets.size(); i++) {
            Model.Widget w = dash.widgets.get(i);
            if (w == dragW) continue;
            widgetRect(w, wr);
            if (c.quickReject(wr, Canvas.EdgeType.AA)) continue;   // off-screen: skip the work
            drawWidget(c, w, wr);
            if (canEdit) {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(0xCC4FC3F7);
                tmp.set(wr.right - 20 * d, wr.bottom - 20 * d, wr.right - 6 * d, wr.bottom - 6 * d);
                c.drawRoundRect(tmp, 3 * d, 3 * d, fill);
            }
        }

        if (resW != null) {
            widgetRect(resW, wr);
            tmp.set(wr.left, wr.top, wr.left + ghostW * cell + (ghostW - 1) * gap,
                    wr.top + ghostH * cell + (ghostH - 1) * gap);
            drawGhostOutline(c, tmp, ghostOk);
        }

        if (dragW != null) {
            float l = pad + ghostX * (cell + gap);
            float t = pad + ghostY * (cell + gap);
            tmp.set(l, t, l + dragW.w * cell + (dragW.w - 1) * gap, t + dragW.h * cell + (dragW.h - 1) * gap);
            drawGhostOutline(c, tmp, ghostOk);

            widgetRect(dragW, wr);
            float dx = fx - offX - wr.left;
            float dy = fy - offY - wr.top;
            c.save();
            c.translate(dx, dy);
            c.saveLayerAlpha(wr.left, wr.top, wr.right, wr.bottom, 210);
            drawWidget(c, dragW, wr);
            c.restore();
            c.restore();
        }

        if (dash.widgets.isEmpty() && !editing && hint.length() > 0) {
            String[] lines = hint.split("\n");
            textP.setColor(0xFF888888);
            float y = getHeight() / 3f;
            for (int i = 0; i < lines.length; i++) {
                drawText(c, lines[i], 16 * d, getWidth() - 16 * d, y + i * 24 * d, 1, 15 * d);
            }
        }
    }

    private void drawGhostOutline(Canvas c, RectF r, boolean ok) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(ok ? 0x3381C784 : 0x33E57373);
        c.drawRoundRect(r, 10 * d, 10 * d, fill);
        stroke.setStrokeWidth(2 * d);
        stroke.setColor(ok ? 0xFF81C784 : 0xFFE57373);
        c.drawRoundRect(r, 10 * d, 10 * d, stroke);
    }

    private void drawWidget(Canvas c, Model.Widget w, RectF r) {
        boolean g = grafana(w);
        float ts = tscale(w);
        float cr = rnd(w) ? (g ? 4 * d : 10 * d) : 0;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(g ? G_PANEL : 0x14FFFFFF);
        c.drawRoundRect(r, cr, cr, fill);
        if (g) {
            stroke.setStrokeWidth(d);
            stroke.setColor(G_BORDER);
            c.drawRoundRect(r, cr, cr, stroke);
        }
        if (editing) {
            stroke.setStrokeWidth(1.5f * d);
            stroke.setColor(0xAA4FC3F7);
            c.drawRoundRect(r, cr, cr, stroke);
        }
        float ip = st.innerPad * d;
        if (g) ip = Math.max(ip, 7 * d);
        float l = r.left + ip, t = r.top + ip, rr = r.right - ip, b = r.bottom - ip;
        if (rr - l < 4 || b - t < 4) return;
        float top0 = t;

        boolean hasTitle = w.title.length() > 0 && w.titlePos != 2;
        boolean floating = hasTitle && w.titlePos == 1;
        if (hasTitle && w.titlePos == 0 && g) {
            // Grafana panel header: small title at the top-left
            float tsz = size(12 * d, (b - t) * 0.18f, ts);
            textP.setColor(w.titleColor != 0 ? w.titleColor : G_TITLE);
            textP.setFakeBoldText(false);
            drawText(c, w.title, l, rr, t + tsz, 0, tsz);
            t += tsz * 1.6f;
            if (b - t < 4) return;
        } else if (hasTitle && w.titlePos == 0) {
            // title above the graph: it never covers anything (the graph gets the rest of the box)
            float tsz = size(13 * d, (b - t) * 0.2f, ts);
            textP.setColor(w.titleColor != 0 ? w.titleColor : st.fontColor);
            textP.setFakeBoldText(true);
            drawText(c, w.title, l, rr, t + tsz, w.align, tsz);
            textP.setFakeBoldText(false);
            t += tsz * 1.5f;
            if (b - t < 4) return;
        }

        if (w.type == Model.TEXT) {
            drawTextWidget(c, w, l, t, rr, b);
        } else if (w.type == Model.CLOCK) {
            drawClock(c, w, l, t, rr, b);
        } else if (w.series.isEmpty()) {
            textP.setColor(0xFF777777);
            drawText(c, "No sensors", l, rr, (t + b) / 2f, 1, 12 * d);
        } else {
            switch (w.type) {
                case Model.NUMBER:
                    if (g) drawStat(c, w, l, t, rr, b);
                    else drawNumber(c, w, l, t, rr, b);
                    break;
                case Model.BAR:
                    if (g) drawGBars(c, w, l, t, rr, b);
                    else drawBars(c, w, l, t, rr, b);
                    break;
                case Model.LINE:
                    if (g) drawGChart(c, w, l, t, rr, b, false);
                    else drawChart(c, w, l, t, rr, b, false);
                    break;
                case Model.AREA:
                    if (g) drawGChart(c, w, l, t, rr, b, true);
                    else drawChart(c, w, l, t, rr, b, true);
                    break;
                case Model.DONUT:
                    drawDonut(c, w, l, t, rr, b);
                    break;
                case Model.PIE:
                    drawPie(c, w, l, t, rr, b);
                    break;
                default:
                    drawGauge(c, w, l, t, rr, b);
                    break;
            }
        }
        if (floating) drawTitleChip(c, w, l, top0, rr, ts);
    }

    /** Small label floating over the top of a graph (the graph itself is not resized). */
    private void drawTitleChip(Canvas c, Model.Widget w, float l, float top, float r, float ts) {
        float tsz = size(11 * d, 20 * d, ts);
        textP.setFakeBoldText(true);
        textP.setTextSize(tsz);
        float tw = textP.measureText(w.title);
        float avail = (r - l) - 10 * d;
        if (tw > avail && tw > 0) {
            tsz *= avail / tw;
            textP.setTextSize(tsz);
            tw = textP.measureText(w.title);
        }
        float chW = tw + 10 * d;
        float chH = tsz * 1.55f;
        float x0 = w.align == 0 ? l : (w.align == 2 ? r - chW : (l + r - chW) / 2f);
        float y0 = top + 2 * d;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x66000000);
        tmp.set(x0, y0, x0 + chW, y0 + chH);
        c.drawRoundRect(tmp, rnd(w) ? chH / 2f : 2 * d, rnd(w) ? chH / 2f : 2 * d, fill);
        textP.setColor(w.titleColor != 0 ? w.titleColor : st.fontColor);
        textP.setTextAlign(Paint.Align.LEFT);
        c.drawText(w.title, x0 + 5 * d, y0 + chH / 2f + tsz * 0.35f, textP);
        textP.setFakeBoldText(false);
    }

    // ---- clock ----
    private void drawClock(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        float ts = tscale(w);
        long now = System.currentTimeMillis();
        String dateStr = "";
        if (w.clockDate && w.clockFormat.length() > 0) {
            try {
                dateStr = new SimpleDateFormat(w.clockFormat, Locale.getDefault()).format(new Date(now));
            } catch (IllegalArgumentException e) {
                dateStr = "?";
            }
        }
        if (w.clockMode == 2) {
            drawAnalog(c, w, l, t, r, b, now, dateStr, ts);
            return;
        }
        String pattern = w.clockMode == 1 ? (w.clockSeconds ? "h:mm:ss a" : "h:mm a")
                : (w.clockSeconds ? "HH:mm:ss" : "HH:mm");
        String timeStr = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(now));
        float band = b - t;
        boolean date = dateStr.length() > 0;
        float vs = size(90 * d, band * (date ? 0.6f : 0.82f), ts);
        float ds = size(26 * d, band * 0.2f, ts);
        float total = date ? vs * 0.95f + ds * 1.5f : vs;
        float y0 = t + (band - total) / 2f;
        textP.setColor(st.fontColor);
        textP.setFakeBoldText(true);
        drawText(c, timeStr, l, r, y0 + vs * 0.8f, w.align, vs);
        textP.setFakeBoldText(false);
        if (date) {
            textP.setColor(st.labelColor);
            drawText(c, dateStr, l, r, y0 + vs * 0.95f + ds * 1.05f, w.align, ds);
        }
    }

    private void drawAnalog(Canvas c, Model.Widget w, float l, float t, float r, float b, long now,
                            String dateStr, float ts) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        float dia = Math.max(8 * d, Math.min(r - l, b - t));
        float cx = (l + r) / 2f;
        float cy = (t + b) / 2f;
        float rad = dia / 2f - 2 * d;
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setStrokeWidth(Math.max(2 * d, rad * 0.04f));
        stroke.setColor(0x44FFFFFF);
        c.drawCircle(cx, cy, rad, stroke);
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30 - 90);
            float r0 = rad * (i % 3 == 0 ? 0.80f : 0.88f);
            float r1 = rad * 0.95f;
            stroke.setStrokeWidth(i % 3 == 0 ? 2.5f * d : 1.5f * d);
            stroke.setColor(0x99FFFFFF);
            c.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                    cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, stroke);
        }
        if (dateStr.length() > 0) {
            textP.setColor(st.labelColor);
            drawText(c, dateStr, cx - rad * 0.55f, cx + rad * 0.55f, cy + rad * 0.52f, 1,
                    size(rad * 0.16f, rad * 0.2f, ts));
        }
        float hour = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f;
        float min = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f;
        hand(c, cx, cy, hour * 30f, rad * 0.5f, Math.max(3 * d, rad * 0.06f), st.fontColor);
        hand(c, cx, cy, min * 6f, rad * 0.75f, Math.max(2 * d, rad * 0.04f), st.fontColor);
        if (w.clockSeconds) {
            hand(c, cx, cy, cal.get(Calendar.SECOND) * 6f, rad * 0.86f, Math.max(1f, rad * 0.015f), st.warnColor);
        }
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(st.fontColor);
        c.drawCircle(cx, cy, Math.max(2.5f * d, rad * 0.05f), fill);
    }

    private void hand(Canvas c, float cx, float cy, float deg, float len, float width, int color) {
        double a = Math.toRadians(deg - 90);
        stroke.setStrokeWidth(width);
        stroke.setColor(color);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx, cy, cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    // ---- number ----
    private void drawNumber(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float ts = tscale(w);
        boolean lbl = anyLabel(w) || anyIcon(w);
        float band = (b - t) / n;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float top = t + i * band;
            float vs = size(40 * d, band * (lbl ? 0.55f : 0.8f), ts);
            float ls = size(13 * d, band * 0.24f, ts);
            int col = colorFor(s, sn);
            if (lbl) {
                drawParts(c, parts(s, sn), l, r, top + band * 0.1f + vs, w.align, vs, col, true);
                drawIconLabel(c, iconName(s, sn), labelFor(w, s, sn), l, r,
                        top + band * 0.1f + vs + ls * 1.4f, w.align, ls);
            } else {
                drawParts(c, parts(s, sn), l, r, top + band / 2f + vs * 0.35f, w.align, vs, col, true);
            }
        }
    }

    // ---- gauge ----
    private void drawGauge(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        if (w.stacked && n > 1) {
            drawRings(c, w, l, t, r, b, 135, 270);
            return;
        }
        boolean g = grafana(w);
        float ts = tscale(w);
        int cols = n <= 3 ? n : (int) Math.ceil(Math.sqrt(n));
        int rows = (n + cols - 1) / cols;
        float cw = (r - l) / cols;
        float ch = (b - t) / rows;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            float cl = l + (i % cols) * cw;
            float ct = t + (i / cols) * ch;
            float dia = Math.max(8 * d, Math.min(cw, ch));
            if (g) drawGGauge(c, w, s, i, sensor(s), cl + cw / 2f, ct + ch / 2f, dia, ts);
            else drawSingleRing(c, w, s, sensor(s), cl + cw / 2f, ct + ch / 2f, dia, 135, 270, ts);
        }
    }

    /** One ring (gauge: 270 degrees, donut: full circle) with icon, value, unit and label inside. */
    private void drawSingleRing(Canvas c, Model.Widget w, Model.Series s, Sensors.Sensor sn, float cx,
                                float cy, float dia, float start, float sweepMax, float ts) {
        float sw = Math.max(2 * d, dia * THICK[Math.max(0, Math.min(2, w.ringThick))]);
        tmp.set(cx - dia / 2f + sw / 2f, cy - dia / 2f + sw / 2f, cx + dia / 2f - sw / 2f, cy + dia / 2f - sw / 2f);
        Paint.Cap cap = rnd(w) ? Paint.Cap.ROUND : Paint.Cap.BUTT;
        stroke.setStrokeWidth(sw);
        stroke.setStrokeCap(sweepMax < 360 ? cap : Paint.Cap.BUTT);
        stroke.setColor(0x33FFFFFF);
        c.drawArc(tmp, start, sweepMax, false, stroke);
        float f = fraction(s, sn);
        if (f > 0.005f) {
            stroke.setStrokeCap(cap);
            stroke.setColor(colorFor(s, sn));
            c.drawArc(tmp, start, sweepMax * f, false, stroke);
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);
        drawCenter(c, w, s, sn, cx, cy, dia, sw, sweepMax < 360, ts);
    }

    /**
     * Inside a ring: icon above the value (unit drawn smaller), label below. In a gauge the label goes
     * into the open gap at the bottom. When the ring is too small, the label and then the icon are dropped.
     */
    private void drawCenter(Canvas c, Model.Widget w, Model.Series s, Sensors.Sensor sn, float cx, float cy,
                            float dia, float sw, boolean gapLabel, float ts) {
        float inner = dia - 2 * sw;
        if (inner < 10 * d) return;
        String lab = labelFor(w, s, sn);
        String ic = iconName(s, sn);
        boolean hasIcon = ic != null;
        boolean labelInStack = lab.length() > 0 && !gapLabel;
        float vs = size(inner * 0.36f, inner * 0.42f, ts);
        float is = vs * 0.75f;
        float ls = size(inner * 0.15f, inner * 0.2f, ts);
        float avail = inner * 0.86f;
        float need;
        while (true) {
            need = vs * 1.05f + (hasIcon ? is * 1.15f : 0) + (labelInStack ? ls * 1.4f : 0);
            if (need <= avail) break;
            if (labelInStack) {
                labelInStack = false;
                continue;
            }
            if (hasIcon) {
                hasIcon = false;
                continue;
            }
            float k = avail / need;
            vs *= k;
            is *= k;
            ls *= k;
            need = avail;
            break;
        }
        float y = cy - need / 2f + (gapLabel ? -dia * 0.03f : 0);
        if (hasIcon) {
            Icons.draw(c, ic, cx - is / 2f, y, is, st.iconColor);
            y += is * 1.15f;
        }
        drawParts(c, parts(s, sn), cx - inner * 0.46f, cx + inner * 0.46f, y + vs * 0.8f, 1, vs,
                st.fontColor, true);
        y += vs * 1.05f;
        textP.setColor(st.labelColor);
        if (labelInStack) {
            drawText(c, lab, cx - inner * 0.46f, cx + inner * 0.46f, y + ls * 0.95f, 1, ls);
        } else if (gapLabel && lab.length() > 0) {
            float rad = dia / 2f;
            drawText(c, lab, cx - rad * 0.5f, cx + rad * 0.5f, cy + rad * 0.86f, 1, size(rad * 0.22f, rad * 0.26f, ts));
        }
    }

    // ---- donut ----
    private void drawDonut(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        if (w.ringThick == 3) {
            drawPie(c, w, l, t, r, b);
            return;
        }
        int n = w.series.size();
        float ts = tscale(w);
        float dia = Math.max(8 * d, Math.min(r - l, b - t));
        float cx = (l + r) / 2f;
        float cy = (t + b) / 2f;
        if (n == 1) {
            Model.Series s = w.series.get(0);
            drawSingleRing(c, w, s, sensor(s), cx, cy, dia, -90, 360, ts);
        } else if (w.stacked) {
            drawRings(c, w, l, t, r, b, -90, 360);
        } else {
            drawDonutSegments(c, w, l, t, r, b);
        }
    }

    /** One ring cut into slices, one slice per sensor (parts of a whole). */
    private void drawDonutSegments(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float ts = tscale(w);
        float dia = Math.max(8 * d, Math.min(r - l, b - t));
        float cx = (l + r) / 2f;
        float cy = (t + b) / 2f;
        float sw = Math.max(3 * d, dia * THICK[Math.max(0, Math.min(2, w.ringThick))] * 1.3f);
        float rad = dia / 2f - sw / 2f;
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        stroke.setStrokeWidth(sw);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setColor(0x33FFFFFF);
        c.drawArc(tmp, 0, 360, false, stroke);

        double sum = 0;
        for (int i = 0; i < n; i++) {
            Sensors.Sensor sn = sensor(w.series.get(i));
            if (sn != null && sn.value > 0) sum += sn.value;
        }
        if (sum > 0) {
            float gapDeg = (float) Math.toDegrees((w.ringGap * d) / Math.max(1f, rad));
            float a0 = -90;
            for (int i = 0; i < n; i++) {
                Model.Series s = w.series.get(i);
                Sensors.Sensor sn = sensor(s);
                if (sn == null || sn.value <= 0) continue;
                float sweep = (float) (360.0 * sn.value / sum);
                if (sweep > gapDeg + 0.5f) {
                    stroke.setColor(s.color);
                    c.drawArc(tmp, a0 + gapDeg / 2f, sweep - gapDeg, false, stroke);
                }
                a0 += sweep;
            }
            // total in the middle when every slice has the same unit
            Sensors.Sensor first = sensor(w.series.get(0));
            boolean same = first != null;
            for (int i = 1; i < n && same; i++) {
                Sensors.Sensor o = sensor(w.series.get(i));
                same = o != null && o.unit == first.unit;
            }
            if (same) {
                float inner = dia - 2 * sw;
                float vs = size(inner * 0.3f, inner * 0.36f, ts);
                drawParts(c, Sensors.formatParts(first.unit, sum, st.tempF), cx - inner * 0.46f, cx + inner * 0.46f,
                        cy + vs * 0.35f, 1, vs, st.fontColor, true);
            }
        }
        drawLegend(c, w, l, r, b, ts, true);
    }

    /** Concentric rings: the first sensor is the outer ring, later ones go inwards. */
    private void drawRings(Canvas c, Model.Widget w, float l, float t, float r, float b, float start,
                           float sweepMax) {
        int n = w.series.size();
        float ts = tscale(w);
        float dia = Math.max(8 * d, Math.min(r - l, b - t));
        float cx = (l + r) / 2f;
        float cy = (t + b) / 2f;
        float rad0 = dia / 2f;
        float band = rad0 * BAND[Math.max(0, Math.min(2, w.ringThick))];
        float pitch = band / n;
        float sw = Math.max(2 * d, pitch - w.ringGap * d);
        Paint.Cap cap = rnd(w) ? Paint.Cap.ROUND : Paint.Cap.BUTT;

        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float rad = rad0 - sw / 2f - i * pitch;
            if (rad <= sw * 0.5f) break;
            tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
            stroke.setStrokeWidth(sw);
            stroke.setStrokeCap(sweepMax < 360 ? cap : Paint.Cap.BUTT);
            stroke.setColor(0x33FFFFFF);
            c.drawArc(tmp, start, sweepMax, false, stroke);
            float f = fraction(s, sn);
            if (f > 0.005f) {
                stroke.setStrokeCap(cap);
                stroke.setColor(colorFor(s, sn));
                c.drawArc(tmp, start, sweepMax * f, false, stroke);
            }
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);

        // one line per ring in the middle: icon (if there is room) and value, in the ring's colour
        float ri = rad0 - band - 2 * d;
        if (ri > 8 * d) {
            float boxH = ri * 1.5f;
            float lineH = Math.min(boxH / n, size(18 * d, boxH, ts));
            float y0 = cy - (lineH * n) / 2f;
            for (int i = 0; i < n; i++) {
                Model.Series s = w.series.get(i);
                Sensors.Sensor sn = sensor(s);
                float base = y0 + (i + 0.5f) * lineH + lineH * 0.28f;
                String ic = iconName(s, sn);
                float x0 = cx - ri * 0.75f;
                float x1 = cx + ri * 0.75f;
                int col = colorFor(s, sn);
                if (ic != null && lineH >= 11 * d) {
                    float is = lineH * 0.8f;
                    Icons.draw(c, ic, x0, y0 + i * lineH + (lineH - is) / 2f, is, col);
                    x0 += is * 1.15f;
                }
                drawParts(c, parts(s, sn), x0, x1, base, ic != null && lineH >= 11 * d ? 2 : 1,
                        lineH * 0.8f, col, true);
            }
        }
        drawLegend(c, w, l, r, b, ts, false);
    }

    // ---- pie ----
    private void drawPie(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float ts = tscale(w);
        float dia = Math.max(8 * d, Math.min(r - l, b - t));
        float cx = (l + r) / 2f;
        float cy = (t + b) / 2f;
        float rad = dia / 2f - d;
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        fill.setStyle(Paint.Style.FILL);
        if (n == 1) {
            Model.Series s = w.series.get(0);
            Sensors.Sensor sn = sensor(s);
            fill.setColor(0x33FFFFFF);
            c.drawCircle(cx, cy, rad, fill);
            float f = fraction(s, sn);
            if (f > 0.002f) {
                fill.setColor(colorFor(s, sn));
                c.drawArc(tmp, -90, 360 * f, true, fill);
            }
        } else {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                Sensors.Sensor sn = sensor(w.series.get(i));
                if (sn != null && sn.value > 0) sum += sn.value;
            }
            if (sum <= 0) {
                fill.setColor(0x33FFFFFF);
                c.drawCircle(cx, cy, rad, fill);
            } else {
                float startAngle = -90;
                for (int i = 0; i < n; i++) {
                    Model.Series s = w.series.get(i);
                    Sensors.Sensor sn = sensor(s);
                    if (sn == null || sn.value <= 0) continue;
                    float sweep = (float) (360.0 * sn.value / sum);
                    fill.setColor(s.color);
                    c.drawArc(tmp, startAngle, sweep, true, fill);
                    stroke.setStrokeWidth(Math.max(1f, d));
                    stroke.setColor(0xFF000000);
                    c.drawArc(tmp, startAngle, sweep, true, stroke);
                    startAngle += sweep;
                }
            }
        }
        drawLegend(c, w, l, r, b, ts, true);
    }

    // ---- text widget ----
    private void drawTextWidget(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        if (w.text.length() == 0) {
            textP.setColor(0xFF777777);
            drawText(c, "Empty text", l, r, (t + b) / 2f, 1, 12 * d);
            return;
        }
        String[] lines = w.text.split("\n");
        float ts = tscale(w);
        float band = (b - t) / lines.length;
        float sz = size(28 * d, band * 0.8f, ts);
        textP.setColor(w.titleColor != 0 ? w.titleColor : st.fontColor);
        for (int i = 0; i < lines.length; i++) {
            drawText(c, lines[i], l, r, t + (i + 0.5f) * band + sz * 0.35f, w.align, sz);
        }
    }

    // ---- bars (horizontal or vertical) ----
    private void drawBars(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float ts = tscale(w);
        boolean vert = w.vertical;
        float gapPx = w.stacked ? 0 : 3 * d;
        float avail = vert ? (r - l) : (b - t);
        float bs = Math.max(2 * d, (avail - gapPx * (n - 1)) / n);          // bar thickness
        float rad = rnd(w) ? (w.stacked ? 0 : Math.min(bs / 2f, 10 * d)) : 0;

        boolean clipped = false;
        if (w.stacked && rnd(w)) {
            tmp.set(l, t, r, b);
            path.reset();
            path.addRoundRect(tmp, 8 * d, 8 * d, Path.Direction.CW);
            c.save();
            c.clipPath(path);
            clipped = true;
        }
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float a0 = (vert ? l : t) + i * (bs + gapPx);
            float x0 = vert ? a0 : l;
            float x1 = vert ? a0 + bs : r;
            float y0 = vert ? t : a0;
            float y1 = vert ? b : a0 + bs;

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x33FFFFFF);
            tmp.set(x0, y0, x1, y1);
            c.drawRoundRect(tmp, rad, rad, fill);

            int col = colorFor(s, sn);
            float f = fraction(s, sn);
            boolean has = sn != null && f > 0.002f;
            float edge;                       // horizontal: right edge of the fill; vertical: its top edge
            if (!vert) {
                float full = r - l;
                float fw = has ? (w.stacked ? full * f : Math.max(Math.min(bs, full), full * f)) : 0;
                edge = x0 + fw;
                if (has) {
                    fill.setColor(col);
                    tmp.set(x0, y0, edge, y1);
                    c.drawRoundRect(tmp, rad, rad, fill);
                }
            } else {
                float full = b - t;
                float fh = has ? (w.stacked ? full * f : Math.max(Math.min(bs, full), full * f)) : 0;
                edge = y1 - fh;
                if (has) {
                    fill.setColor(col);
                    tmp.set(x0, edge, x1, y1);
                    c.drawRoundRect(tmp, rad, rad, fill);
                }
            }

            if (!vert) {
                float tsz = size(14 * d, bs * 0.62f, ts);
                drawBarText(c, w, iconName(s, sn), labelFor(w, s, sn), valueText(s, sn), l, r, y0, bs, edge, col, tsz);
            } else {
                drawVBarText(c, w, s, sn, x0, x1, y0, y1, edge, col, ts);
            }

            if (w.stacked && i < n - 1) {
                stroke.setStrokeWidth(Math.max(1f, d));
                stroke.setColor(0xFF000000);
                if (!vert) c.drawLine(l, y1, r, y1, stroke);
                else c.drawLine(x1, t, x1, b, stroke);
            }
        }
        if (clipped) c.restore();
    }

    private static int onColor(int fill) {
        float lum = (0.299f * Color.red(fill) + 0.587f * Color.green(fill) + 0.114f * Color.blue(fill)) / 255f;
        return lum > 0.55f ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** Text inside a horizontal bar, drawn twice (clipped): dark-or-light over the fill, normal over the track. */
    private void drawBarText(Canvas c, Model.Widget w, String ic, String label, String value, float l, float r,
                             float y, float bh, float fillRight, int fillColor, float size) {
        float pad2 = Math.min(6 * d, bh * 0.3f);
        float base = y + bh / 2f + size * 0.35f;
        if (fillRight > l + 0.5f) {
            c.save();
            c.clipRect(l, y, fillRight, y + bh);
            barTextLine(c, w, ic, label, value, l + pad2, r - pad2, base, size, onColor(fillColor));
            c.restore();
        }
        if (fillRight < r - 0.5f) {
            c.save();
            c.clipRect(fillRight, y, r, y + bh);
            barTextLine(c, w, ic, label, value, l + pad2, r - pad2, base, size, st.fontColor);
            c.restore();
        }
    }

    private void barTextLine(Canvas c, Model.Widget w, String ic, String label, String value, float l, float r,
                             float base, float size, int color) {
        textP.setColor(color);
        float x = l;
        if (ic != null) {
            float is = size * 1.15f;
            Icons.draw(c, ic, x, base - is * 0.82f, is, color);
            x += is + size * 0.3f;
        }
        if (label.length() > 0) {
            float split = x + (r - x) * 0.6f;
            drawText(c, label, x, split - 4 * d, base, 0, size);
            drawText(c, value, split, r, base, 2, size);
        } else if (ic != null) {
            drawText(c, value, x, r, base, 2, size);
        } else {
            drawText(c, value, l, r, base, w.align, size);
        }
    }

    /** Text inside a vertical bar: value at the top, icon and label at the bottom. */
    private void drawVBarText(Canvas c, Model.Widget w, Model.Series s, Sensors.Sensor sn, float x0, float x1,
                              float y0, float y1, float fillTop, int fillColor, float ts) {
        float tsz = size(13 * d, Math.max(6 * d, (x1 - x0) * 0.38f), ts);
        String value = valueText(s, sn);
        String lab = labelFor(w, s, sn);
        String ic = iconName(s, sn);
        if (fillTop < y1 - 0.5f) {
            c.save();
            c.clipRect(x0, fillTop, x1, y1);
            vbarLines(c, value, lab, ic, x0, x1, y0, y1, tsz, onColor(fillColor));
            c.restore();
        }
        if (fillTop > y0 + 0.5f) {
            c.save();
            c.clipRect(x0, y0, x1, fillTop);
            vbarLines(c, value, lab, ic, x0, x1, y0, y1, tsz, st.fontColor);
            c.restore();
        }
    }

    private void vbarLines(Canvas c, String value, String lab, String ic, float x0, float x1, float y0, float y1,
                           float tsz, int color) {
        float pad2 = 3 * d;
        textP.setColor(color);
        drawText(c, value, x0 + pad2, x1 - pad2, y0 + tsz * 1.25f, 1, tsz);
        float yb = y1 - pad2;
        if (lab.length() > 0) {
            drawText(c, lab, x0 + pad2, x1 - pad2, yb, 1, tsz * 0.85f);
            yb -= tsz * 1.1f;
        }
        if (ic != null && (y1 - y0) > tsz * 5) {
            Icons.draw(c, ic, (x0 + x1) / 2f - tsz * 0.5f, yb - tsz, tsz, color);
        }
    }

    // ---- line / area ----
    private void drawChart(Canvas c, Model.Widget w, float l, float t, float r, float b, boolean area) {
        int n = w.series.size();
        float ts = tscale(w);
        stroke.setStrokeWidth(1);
        stroke.setColor(0x22FFFFFF);
        for (int k = 0; k <= 3; k++) {
            float y = t + (b - t) * k / 3f;
            c.drawLine(l, y, r, y, stroke);
        }

        float step = (r - l) / (History.N - 1);
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            History.Ring ring = hist.ring(s.sensorId);
            if (sn == null || ring == null || ring.count < 2) continue;
            path.reset();
            float firstX = 0, lastX = 0;
            for (int k = 0; k < ring.count; k++) {
                float x = r - (ring.count - 1 - k) * step;
                float y = b - norm(s, sn, ring.get(k)) * (b - t);
                if (k == 0) {
                    path.moveTo(x, y);
                    firstX = x;
                } else {
                    path.lineTo(x, y);
                }
                lastX = x;
            }
            if (area) {
                path2.reset();
                path2.addPath(path);
                path2.lineTo(lastX, b);
                path2.lineTo(firstX, b);
                path2.close();
                fill.setStyle(Paint.Style.FILL);
                fill.setColor((s.color & 0x00FFFFFF) | 0x55000000);
                c.drawPath(path2, fill);
            }
            stroke.setStrokeWidth(2 * d);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(s.color);
            c.drawPath(path, stroke);
        }
        drawLegend(c, w, l, r, b, ts, true);
    }

    /**
     * Legend chips floating over the bottom of the graph (they do not resize it): the sensor's icon (or a
     * colour dot) in the series colour, then its label and, optionally, its value.
     */
    private void drawLegend(Canvas c, Model.Widget w, float l, float r, float b, float ts, boolean withValue) {
        int n = w.series.size();
        float ls = size(10 * d, 16 * d, ts);
        float chH = ls * 1.7f;
        float cw = (r - l) / n;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            String lab = labelFor(w, s, sn);
            String ic = iconName(s, sn);
            String txt = withValue ? (lab.length() > 0 ? lab + " " + valueText(s, sn) : valueText(s, sn)) : lab;
            if (txt.length() == 0 && ic == null) continue;
            float x0 = l + i * cw + d;
            float x1 = l + (i + 1) * cw - d;
            if (x1 - x0 < ls * 2) continue;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x66000000);
            tmp.set(x0, b - chH, x1, b);
            c.drawRoundRect(tmp, rnd(w) ? chH / 2f : 2 * d, rnd(w) ? chH / 2f : 2 * d, fill);
            float mx = x0 + 4 * d;
            if (ic != null) {
                Icons.draw(c, ic, mx, b - chH + (chH - ls * 1.15f) / 2f, ls * 1.15f, s.color);
                mx += ls * 1.15f + 3 * d;
            } else {
                fill.setColor(s.color);
                c.drawCircle(mx + ls * 0.3f, b - chH / 2f, ls * 0.3f, fill);
                mx += ls * 0.6f + 3 * d;
            }
            textP.setColor(st.labelColor);
            drawText(c, txt, mx, x1 - 4 * d, b - chH / 2f + ls * 0.35f, 0, ls);
        }
    }

    // ==================================================================
    // Grafana style
    // ==================================================================
    private static final int G_PANEL = 0xE6181B1F;
    private static final int G_BORDER = 0xFF2C3037;
    private static final int G_TRACK = 0xFF262A30;
    private static final int G_LCD_OFF = 0xFF24282E;
    private static final int G_TITLE = 0xFFD8D9DA;
    private static final int G_DIM = 0xFF8E8E99;
    /** Grafana's classic colours: used instead of white (the untouched default series colour). */
    private static final int[] GPAL = {0xFF73BF69, 0xFF5794F2, 0xFFFF9830, 0xFFF2CC0C, 0xFFB877D9, 0xFF8AB8FF};

    private final float[] fr = new float[2];
    private boolean frInv = false;   // set by thFracs: the last thresholds are "low is bad" (battery)
    private final float[] px = new float[History.N];
    private final float[] py = new float[History.N];

    private boolean rnd(Model.Widget w) {
        return w.corners == 0 ? st.rounded : w.corners == 1;
    }

    private boolean grafana(Model.Widget w) {
        return w.style == 0 ? st.panelStyle == 1 : w.style == 2;
    }

    private static int gcol(Model.Series s, int i) {
        return s.color == Model.WHITE ? GPAL[i % GPAL.length] : s.color;
    }

    private static float cl(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Warning / critical positions (0..1) inside the series range; false when there are no thresholds. */
    private boolean thFracs(Model.Series s, Sensors.Sensor sn, float[] out) {
        frInv = false;
        if (!st.thEnabled || sn == null || sn.fan) return false;
        if ("battery".equals(sn.thKey)) {
            // low is bad: work in a flipped space (q = 1 - position) so base / warning / critical keep their order
            double[] rb = range(s, sn);
            double span = rb[1] - rb[0];
            float crit = st.batCrit;
            float warn = Math.max(st.batWarn, crit + 1);
            out[0] = 1f - (float) Math.max(0, Math.min(1, (warn - rb[0]) / span));
            out[1] = 1f - (float) Math.max(0, Math.min(1, (crit - rb[0]) / span));
            frInv = true;
            return true;
        }
        int i = Model.thIndex(sn.thKey);
        if (i < 0) return false;
        double[] r = range(s, sn);
        double mn = r[0];
        double span = r[1] - r[0];
        float warn = st.thWarn[i];
        float crit = Math.max(st.thCrit[i], warn + 1);
        out[0] = (float) Math.max(0, Math.min(1, (warn - mn) / span));
        out[1] = (float) Math.max(0, Math.min(1, (crit - mn) / span));
        return true;
    }

    /** Colour at position pos (0..1) along a bar: base, then warning, then critical. */
    private int gradColor(int base, boolean th, float[] f, float pos) {
        if (th && frInv) pos = 1f - pos;
        if (!th) return lerp(lerp(base, Model.BLACK, 0.4), base, pos);
        float p1 = cl(f[0], 0.02f, 0.98f);
        float p2 = cl(f[1], p1 + 0.01f, 1f);
        if (pos <= p1) return lerp(base, st.warnColor, pos / p1);
        if (pos <= p2) return lerp(st.warnColor, st.critColor, (pos - p1) / (p2 - p1));
        return st.critColor;
    }

    private Shader gradShader(int base, boolean th, float[] f, float x0, float y0, float x1, float y1) {
        if (!th) {
            return new LinearGradient(x0, y0, x1, y1, lerp(base, Model.BLACK, 0.4), base, Shader.TileMode.CLAMP);
        }
        float p1 = cl(f[0], 0.02f, 0.98f);
        float p2 = cl(f[1], p1 + 0.01f, 1f);
        if (frInv) {   // battery: the good (base) colour is at the full end
            return new LinearGradient(x1, y1, x0, y0, new int[]{base, st.warnColor, st.critColor},
                    new float[]{0f, p1, p2}, Shader.TileMode.CLAMP);
        }
        return new LinearGradient(x0, y0, x1, y1, new int[]{base, st.warnColor, st.critColor},
                new float[]{0f, p1, p2}, Shader.TileMode.CLAMP);
    }

    /** Smooth curve through the first n points of px / py. */
    private void smooth(Path p, int n) {
        p.reset();
        p.moveTo(px[0], py[0]);
        for (int i = 1; i < n; i++) {
            p.quadTo(px[i - 1], py[i - 1], (px[i - 1] + px[i]) / 2f, (py[i - 1] + py[i]) / 2f);
        }
        p.lineTo(px[n - 1], py[n - 1]);
    }

    // ---- stat panel: big number with a sparkline behind it ----
    private void drawStat(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float ts = tscale(w);
        boolean lbl = anyLabel(w) || anyIcon(w);
        float band = (b - t) / n;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float top = t + i * band;
            float bot = top + band - (i < n - 1 ? 3 * d : 0);
            int col = colorAt(gcol(s, i), sn, sn != null ? sn.value : 0);
            if (w.sparkline) drawSpark(c, s, l, top + (bot - top) * 0.4f, r, bot, col);
            float h = bot - top;
            float ls = size(12 * d, h * 0.2f, ts);
            float vs = size(46 * d, h * (lbl ? 0.5f : 0.62f), ts);
            if (lbl) {
                drawIconLabel(c, iconName(s, sn), labelFor(w, s, sn), l, r, top + ls * 1.2f, w.align, ls);
                float rest = h - ls * 1.6f;
                drawParts(c, parts(s, sn), l, r, top + ls * 1.6f + rest * 0.42f + vs * 0.35f, w.align, vs, col, true);
            } else {
                drawParts(c, parts(s, sn), l, r, top + h * 0.45f + vs * 0.35f, w.align, vs, col, true);
            }
        }
    }

    private void drawSpark(Canvas c, Model.Series s, float l, float t, float r, float b, int col) {
        History.Ring ring = hist.ring(s.sensorId);
        if (ring == null || ring.count < 2 || b - t < 6 * d) return;
        float mn = ring.get(0), mx = mn;
        for (int k = 1; k < ring.count; k++) {
            float v = ring.get(k);
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        float span = mx - mn;
        if (span < 1e-6f) {
            mn -= 1;
            span = 2;
        } else {
            mn -= span * 0.08f;
            span *= 1.16f;
        }
        int n = ring.count;
        for (int k = 0; k < n; k++) {
            px[k] = l + (r - l) * k / (float) (n - 1);
            py[k] = b - 1.5f * d - (ring.get(k) - mn) / span * (b - t - 1.5f * d);
        }
        smooth(path, n);
        path2.reset();
        path2.addPath(path);
        path2.lineTo(px[n - 1], b);
        path2.lineTo(px[0], b);
        path2.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor((col & 0x00FFFFFF) | 0x38000000);
        c.drawPath(path2, fill);
        stroke.setStrokeWidth(1.4f * d);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor((col & 0x00FFFFFF) | 0xB0000000);
        c.drawPath(path, stroke);
    }

    // ---- gauge with threshold bands ----
    private void drawGGauge(Canvas c, Model.Widget w, Model.Series s, int idx, Sensors.Sensor sn, float cx,
                            float cy, float dia, float ts) {
        int base = gcol(s, idx);
        int vcol = colorAt(base, sn, sn != null ? sn.value : 0);
        boolean bands = w.bands && thFracs(s, sn, fr);
        float rad = dia / 2f;
        float bw = bands ? Math.max(2 * d, dia * 0.035f) : 0;
        float sw = Math.max(3 * d, dia * 0.10f);
        float start = 135, sweep = 270;
        float f0 = fr[0], f1 = fr[1];
        if (bands) {
            float rb = rad - bw / 2f;
            tmp.set(cx - rb, cy - rb, cx + rb, cy + rb);
            stroke.setStrokeWidth(bw);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            bandArc(c, start, sweep, 0f, f0, base);
            bandArc(c, start, sweep, f0, f1, st.warnColor);
            bandArc(c, start, sweep, f1, 1f, st.critColor);
        }
        float rv = rad - (bands ? bw + dia * 0.03f : 0) - sw / 2f;
        if (rv < sw) return;
        tmp.set(cx - rv, cy - rv, cx + rv, cy + rv);
        Paint.Cap cap = rnd(w) ? Paint.Cap.ROUND : Paint.Cap.BUTT;
        stroke.setStrokeWidth(sw);
        stroke.setStrokeCap(cap);
        stroke.setColor(G_TRACK);
        c.drawArc(tmp, start, sweep, false, stroke);
        float f = fraction(s, sn);
        if (f > 0.005f) {
            stroke.setColor(vcol);
            c.drawArc(tmp, start, sweep * f, false, stroke);
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);

        // value, icon and label inside
        float ri = rv - sw / 2f;
        float inner = ri * 2f;
        if (inner < 10 * d) return;
        String ic = iconName(s, sn);
        String lab = labelFor(w, s, sn);
        float vs = size(inner * 0.38f, inner * 0.44f, ts);
        float is = vs * 0.6f;
        float cyv = cy - dia * 0.02f;
        if (ic != null && ri > 22 * d) {
            Icons.draw(c, ic, cx - is / 2f, cyv - vs * 0.55f - is * 1.1f, is, st.iconColor);
        }
        drawParts(c, parts(s, sn), cx - inner * 0.46f, cx + inner * 0.46f, cyv + vs * 0.35f, 1, vs, vcol, true);
        if (dia >= 70 * d && sn != null) {
            double[] rg2 = range(s, sn);
            double lo = sn.fan ? 0 : rg2[0];
            double hi = sn.fan ? 100 : rg2[1];
            float ms = size(rad * 0.13f, rad * 0.17f, ts);
            float ex = rv * 0.7071f;
            float my = cy + ex + sw / 2f + ms * 1.15f;
            textP.setColor(G_DIM);
            drawText(c, axisText(sn, lo), cx - ex - ms * 1.8f, cx - ex + ms * 1.8f, my, 1, ms);
            drawText(c, axisText(sn, hi), cx + ex - ms * 1.8f, cx + ex + ms * 1.8f, my, 1, ms);
        }
        if (lab.length() > 0) {
            textP.setColor(st.labelColor);
            float lsz = size(rad * 0.2f, rad * 0.24f, ts);
            drawText(c, lab, cx - rad * 0.3f, cx + rad * 0.3f, cy + rv * 0.82f, 1, lsz);
        }
    }

    /** One coloured piece of the threshold band (positions in the flipped space when frInv). */
    private void bandArc(Canvas c, float start, float sweep, float qa, float qb, int color) {
        if (qb - qa <= 0.003f) return;
        float a0 = frInv ? start + sweep * (1f - qb) : start + sweep * qa;
        stroke.setColor(color);
        c.drawArc(tmp, a0, sweep * (qb - qa), false, stroke);
    }

    /** Axis number without clutter: no unit for percent and temperature. */
    private String axisText(Sensors.Sensor sn, double v) {
        if (sn.fan) return String.format(Locale.US, "%.0f", v);
        String[] p = Sensors.formatParts(sn.unit, v, st.tempF);
        if (sn.unit == Sensors.Unit.PERCENT || sn.unit == Sensors.Unit.CELSIUS) return p[0];
        return join(p);
    }

    // ---- bar gauge: gradient bars or LCD blocks ----
    private void drawGBars(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float ts = tscale(w);
        boolean vert = w.vertical;
        float gapPx = 4 * d;
        float avail = vert ? (r - l) : (b - t);
        float bs = Math.max(4 * d, (avail - gapPx * (n - 1)) / n);
        float tsz = size(12 * d, bs * 0.7f, ts);
        boolean texts = tsz >= 6 * d;

        if (!vert) {
            float lw = 0, vw = 0;
            if (texts) {
                for (int i = 0; i < n; i++) {
                    Model.Series s = w.series.get(i);
                    Sensors.Sensor sn = sensor(s);
                    String lab = labelFor(w, s, sn);
                    boolean ic = iconName(s, sn) != null;
                    textP.setFakeBoldText(false);
                    textP.setTextSize(tsz);
                    float lwi = (lab.length() > 0 ? textP.measureText(lab) : 0)
                            + (ic ? tsz * 1.15f + (lab.length() > 0 ? tsz * 0.3f : 0) : 0);
                    lw = Math.max(lw, lwi);
                    textP.setFakeBoldText(true);
                    vw = Math.max(vw, textP.measureText(valueText(s, sn)));
                    textP.setFakeBoldText(false);
                }
                lw = lw > 0 ? Math.min(lw, (r - l) * 0.34f) + 6 * d : 0;
                vw = Math.min(vw, (r - l) * 0.3f) + 6 * d;
                if ((r - l) - lw - vw < 24 * d) vw = 0;
                if ((r - l) - lw < 24 * d) lw = 0;
            }
            for (int i = 0; i < n; i++) {
                Model.Series s = w.series.get(i);
                Sensors.Sensor sn = sensor(s);
                float a0 = t + i * (bs + gapPx);
                float barH = Math.min(bs, 26 * d);
                float y0 = a0 + (bs - barH) / 2f;
                float base = a0 + bs / 2f + tsz * 0.35f;
                if (lw > 0) {
                    drawIconLabel(c, iconName(s, sn), labelFor(w, s, sn), l, l + lw - 6 * d, base, 0, tsz);
                }
                if (vw > 0) {
                    textP.setColor(st.fontColor);
                    textP.setFakeBoldText(true);
                    drawText(c, valueText(s, sn), r - vw + 6 * d, r, base, 2, tsz);
                    textP.setFakeBoldText(false);
                }
                drawGBar(c, w, s, i, sn, l + lw, y0, r - vw, y0 + barH, false);
            }
        } else {
            float barW = Math.min(bs * 0.8f, 36 * d);
            for (int i = 0; i < n; i++) {
                Model.Series s = w.series.get(i);
                Sensors.Sensor sn = sensor(s);
                float a0 = l + i * (bs + gapPx);
                float top = t, bot = b;
                if (texts && (b - t) > tsz * 5) {
                    textP.setColor(st.fontColor);
                    textP.setFakeBoldText(true);
                    drawText(c, valueText(s, sn), a0, a0 + bs, t + tsz, 1, tsz);
                    textP.setFakeBoldText(false);
                    top = t + tsz * 1.6f;
                    String lab = labelFor(w, s, sn);
                    if (lab.length() > 0) {
                        textP.setColor(st.labelColor);
                        drawText(c, lab, a0, a0 + bs, b - tsz * 0.2f, 1, tsz * 0.9f);
                        bot = b - tsz * 1.5f;
                    }
                }
                float x0 = a0 + (bs - barW) / 2f;
                drawGBar(c, w, s, i, sn, x0, top, x0 + barW, bot, true);
            }
        }
    }

    private void drawGBar(Canvas c, Model.Widget w, Model.Series s, int idx, Sensors.Sensor sn, float x0, float y0,
                          float x1, float y1, boolean vert) {
        if (x1 - x0 < 2 || y1 - y0 < 2) return;
        int base = gcol(s, idx);
        boolean th = thFracs(s, sn, fr);
        float f = fraction(s, sn);
        float thick = vert ? x1 - x0 : y1 - y0;
        float len = vert ? y1 - y0 : x1 - x0;
        float rad = rnd(w) ? Math.min(3 * d, thick / 2f) : 0;
        fill.setStyle(Paint.Style.FILL);

        if (w.barStyle == 1) {
            float bl = 6 * d, g = 2 * d;
            int cnt = Math.max(1, (int) ((len + g) / (bl + g)));
            float blk = (len - g * (cnt - 1)) / cnt;
            int lit = Math.round(f * cnt);
            if (lit == 0 && f > 0.002f) lit = 1;
            float lr = rnd(w) ? Math.min(1.5f * d, blk / 2f) : 0;
            for (int k = 0; k < cnt; k++) {
                float off = k * (blk + g);
                fill.setColor(k < lit ? gradColor(base, th, fr, (k + 0.5f) / cnt) : G_LCD_OFF);
                if (!vert) tmp.set(x0 + off, y0, x0 + off + blk, y1);
                else tmp.set(x0, y1 - off - blk, x1, y1 - off);
                c.drawRoundRect(tmp, lr, lr, fill);
            }
            return;
        }

        fill.setColor(G_TRACK);
        tmp.set(x0, y0, x1, y1);
        c.drawRoundRect(tmp, rad, rad, fill);
        if (sn == null || f <= 0.002f) return;
        float fl = Math.max(Math.min(thick, len), len * f);
        if (w.barStyle == 2) {
            fill.setColor(colorAt(base, sn, sn.value));
        } else if (!vert) {
            fill.setShader(gradShader(base, th, fr, x0, 0, x1, 0));
        } else {
            fill.setShader(gradShader(base, th, fr, 0, y1, 0, y0));
        }
        if (!vert) tmp.set(x0, y0, x0 + fl, y1);
        else tmp.set(x0, y1 - fl, x1, y1);
        c.drawRoundRect(tmp, rad, rad, fill);
        fill.setShader(null);
    }

    // ---- time series: smooth lines, axis labels and a legend table ----
    private void drawGChart(Canvas c, Model.Widget w, float l, float t, float r, float b, boolean area) {
        int n = w.series.size();
        float ts = tscale(w);

        // legend table at the bottom (with header if it fits, else without, else none)
        float lsz = size(11 * d, 16 * d, ts);
        float rh = lsz * 1.45f;
        int mode = 0;
        float legendH = 0;
        if (w.legendTable) {
            float limit = (b - t) * 0.5f;
            if ((n + 1) * rh <= limit) {
                mode = 2;
                legendH = (n + 1) * rh;
            } else if (n * rh <= limit) {
                mode = 1;
                legendH = n * rh;
            }
        }
        float gb = b - legendH - (mode > 0 ? 4 * d : 0);

        // shared value axis when all series have the same unit
        boolean shared = true, custom = false;
        Sensors.Unit unit = null;
        Model.Series first = null;
        Sensors.Sensor firstSn = null;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            if (sn == null) continue;
            if (first == null) {
                first = s;
                firstSn = sn;
            }
            if (unit == null) unit = sn.unit;
            else if (unit != sn.unit) shared = false;
            if (sn.fan) shared = false;
            if (!Double.isNaN(s.min) || !Double.isNaN(s.max)) custom = true;
        }
        if (first == null) shared = false;

        double lo = 0, hi = 1, step = 1;
        float asz = size(10 * d, 14 * d, ts);
        float pl = l;
        float pt = t + asz * 0.5f;
        if (shared) {
            if (unit == Sensors.Unit.PERCENT || custom) {
                double[] rg2 = range(first, firstSn);
                lo = rg2[0];
                hi = rg2[1];
                step = (hi - lo) / 4.0;
            } else {
                double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    Model.Series s = w.series.get(i);
                    History.Ring ring = hist.ring(s.sensorId);
                    if (ring == null) continue;
                    for (int k = 0; k < ring.count; k++) {
                        float v = ring.get(k);
                        if (v < mn) mn = v;
                        if (v > mx) mx = v;
                    }
                }
                if (mn > mx) {
                    mn = 0;
                    mx = 1;
                }
                double span = Math.max(mx - mn, 1e-9);
                double raw = Math.max(span * 1.1, 1e-9) / 3.0;
                double mag = Math.pow(10, Math.floor(Math.log10(raw)));
                double fm = raw / mag;
                step = (fm <= 1 ? 1 : fm <= 2 ? 2 : fm <= 5 ? 5 : 10) * mag;
                lo = Math.floor(mn / step) * step;
                hi = Math.ceil(mx / step) * step;
                if (unit == Sensors.Unit.BYTES_PS || (mn >= 0 && lo < 0)) lo = 0;
                if (hi - lo < step) hi = lo + step;
            }
            textP.setTextSize(asz);
            textP.setFakeBoldText(false);
            float mw = 0;
            for (double v = lo; v <= hi + step * 0.01; v += step) {
                mw = Math.max(mw, textP.measureText(axisText(firstSn, v)));
            }
            pl = l + mw + 5 * d;
            if ((r - pl) < (r - l) * 0.4f) pl = l;
        }
        if (gb - pt < 16 * d) {
            if (mode == 0) drawLegend(c, w, l, r, b, ts, true);
            return;
        }

        // grid and axis labels
        stroke.setStrokeWidth(1);
        stroke.setColor(0x1CFFFFFF);
        if (shared) {
            for (double v = lo; v <= hi + step * 0.01; v += step) {
                float y = gb - (float) ((v - lo) / (hi - lo)) * (gb - pt);
                c.drawLine(pl, y, r, y, stroke);
                if (pl > l) {
                    textP.setColor(G_DIM);
                    drawText(c, axisText(firstSn, v), l, pl - 4 * d, y + asz * 0.35f, 2, asz);
                }
            }
        } else {
            for (int k = 0; k <= 3; k++) {
                float y = pt + (gb - pt) * k / 3f;
                c.drawLine(pl, y, r, y, stroke);
            }
        }

        // series
        float stepX = (r - pl) / (History.N - 1);
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            History.Ring ring = hist.ring(s.sensorId);
            if (sn == null || ring == null || ring.count < 2) continue;
            int col = gcol(s, i);
            int cnt = ring.count;
            for (int k = 0; k < cnt; k++) {
                float fv = shared ? (float) ((ring.get(k) - lo) / (hi - lo)) : norm(s, sn, ring.get(k));
                px[k] = r - (cnt - 1 - k) * stepX;
                py[k] = gb - cl(fv, 0f, 1f) * (gb - pt);
            }
            smooth(path, cnt);
            if (area) {
                path2.reset();
                path2.addPath(path);
                path2.lineTo(px[cnt - 1], gb);
                path2.lineTo(px[0], gb);
                path2.close();
                fill.setStyle(Paint.Style.FILL);
                fill.setShader(new LinearGradient(0, pt, 0, gb, (col & 0x00FFFFFF) | 0x66000000,
                        (col & 0x00FFFFFF) | 0x0C000000, Shader.TileMode.CLAMP));
                c.drawPath(path2, fill);
                fill.setShader(null);
            }
            stroke.setStrokeWidth(1.6f * d);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeCap(rnd(w) ? Paint.Cap.ROUND : Paint.Cap.BUTT);
            stroke.setColor(col);
            c.drawPath(path, stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
        }

        if (mode == 0) {
            drawLegend(c, w, pl, r, b, ts, true);
            return;
        }
        drawLegendTable(c, w, l, r, b - legendH, lsz, rh, mode == 2);
    }

    private void drawLegendTable(Canvas c, Model.Widget w, float l, float r, float top, float lsz, float rh,
                                 boolean header) {
        int n = w.series.size();
        String[] last = new String[n];
        String[] max = new String[n];
        textP.setTextSize(lsz);
        textP.setFakeBoldText(false);
        float lastW = textP.measureText("Last");
        float maxW = textP.measureText("Max");
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            last[i] = valueText(s, sn);
            History.Ring ring = hist.ring(s.sensorId);
            double mv = sn != null ? sn.value : 0;
            if (ring != null) {
                for (int k = 0; k < ring.count; k++) mv = Math.max(mv, ring.get(k));
            }
            max[i] = sn == null ? "--" : join(partsAt(s, sn, mv));
            lastW = Math.max(lastW, textP.measureText(last[i]));
            maxW = Math.max(maxW, textP.measureText(max[i]));
        }
        float colGap = 10 * d;
        boolean showMax = (r - l) - (lastW + colGap) - (maxW + colGap) >= (r - l) * 0.3f;
        boolean showLast = (r - l) - (lastW + colGap) >= (r - l) * 0.3f;
        float xMax = r;
        float xLast = showMax ? r - maxW - colGap : r;
        float nameR = (showLast ? xLast - lastW - colGap : r);

        float y = top;
        if (header) {
            textP.setColor(G_DIM);
            textP.setFakeBoldText(true);
            if (showLast) drawText(c, "Last", xLast - lastW, xLast, y + rh * 0.5f + lsz * 0.35f, 2, lsz);
            if (showMax) drawText(c, "Max", xMax - maxW, xMax, y + rh * 0.5f + lsz * 0.35f, 2, lsz);
            textP.setFakeBoldText(false);
            y += rh;
        }
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            int col = gcol(s, i);
            float base = y + rh / 2f + lsz * 0.35f;
            float x = l;
            String ic = iconName(s, sn);
            if (ic != null) {
                float is = lsz * 1.15f;
                Icons.draw(c, ic, x, y + (rh - is) / 2f, is, col);
                x += is + 4 * d;
            } else {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(col);
                tmp.set(x, y + rh / 2f - 1.5f * d, x + 12 * d, y + rh / 2f + 1.5f * d);
                c.drawRoundRect(tmp, d, d, fill);
                x += 12 * d + 4 * d;
            }
            String lab = labelFor(w, s, sn);
            if (lab.length() > 0) {
                textP.setColor(st.labelColor);
                drawText(c, lab, x, nameR, base, 0, lsz);
            }
            textP.setColor(st.fontColor);
            if (showLast) drawText(c, last[i], xLast - lastW, xLast, base, 2, lsz);
            if (showMax) drawText(c, max[i], xMax - maxW, xMax, base, 2, lsz);
            y += rh;
        }
    }
}
