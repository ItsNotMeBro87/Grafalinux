package org.grafalinux.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * Draws the whole dashboard (grid + widgets) itself on one canvas. No child views and no chart
 * library, so it stays light on memory and CPU; it redraws only when new data arrives.
 */
final class DashboardView extends View {
    interface Host {
        void onWidgetTap(Model.Widget w);

        void onLongPress();
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
    private final float d;
    private float cell, gap, pad;
    private final GestureDetector gd;

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
        int rows = Math.max(1, dash.rows);
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

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gd.onTouchEvent(e);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private Sensors.Sensor sensor(Model.Series s) {
        return table == null ? null : table.map.get(s.sensorId);
    }

    private String valueText(Sensors.Sensor sn) {
        return sn == null ? "--" : Sensors.format(sn.unit, sn.value, st.tempF);
    }

    private String labelOf(Model.Series s, Sensors.Sensor sn) {
        if (s.label.length() > 0) return s.label;
        return sn != null ? sn.label : s.sensorId;
    }

    private double[] range(Model.Series s, Sensors.Sensor sn) {
        double mn = !Double.isNaN(s.min) ? s.min : (sn != null ? sn.min : 0);
        double mx;
        if (!Double.isNaN(s.max)) mx = s.max;
        else if (sn != null && !Double.isNaN(sn.max)) mx = sn.max;
        else if (sn != null) mx = Math.max(hist.peak(s.sensorId), 1048576.0);
        else mx = 100;
        if (mx <= mn) mx = mn + 1;
        return new double[]{mn, mx};
    }

    private float fraction(Model.Series s, Sensors.Sensor sn) {
        if (sn == null) return 0;
        double[] r = range(s, sn);
        double f = (sn.value - r[0]) / (r[1] - r[0]);
        return (float) Math.max(0, Math.min(1, f));
    }

    private int colorFor(Model.Series s, Sensors.Sensor sn) {
        if (st.thEnabled && sn != null) {
            int i = Model.thIndex(sn.thKey);
            if (i >= 0) {
                if (sn.value >= st.thCrit[i]) return st.critColor;
                if (sn.value >= st.thWarn[i]) return st.warnColor;
            }
        }
        return s.color;
    }

    /** Draws s inside [l, r], shrinking the text to fit; align 0 = left, 1 = centre, 2 = right. */
    private void drawText(Canvas c, String s, float l, float r, float baseline, int align, float size) {
        if (size < 1) return;
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

    // ------------------------------------------------------------------
    // drawing
    // ------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas c) {
        if (st == null) return;
        c.drawColor(st.bgColor);
        if (dash == null) return;

        if (editing) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x14FFFFFF);
            for (int r = 0; r < dash.rows; r++) {
                for (int col = 0; col < dash.cols; col++) {
                    float l = pad + col * (cell + gap);
                    float t = pad + r * (cell + gap);
                    tmp.set(l, t, l + cell, t + cell);
                    c.drawRoundRect(tmp, 6 * d, 6 * d, fill);
                }
            }
        }

        for (int i = 0; i < dash.widgets.size(); i++) {
            Model.Widget w = dash.widgets.get(i);
            widgetRect(w, wr);
            drawWidget(c, w, wr);
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

    private void drawWidget(Canvas c, Model.Widget w, RectF r) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x14FFFFFF);
        c.drawRoundRect(r, 10 * d, 10 * d, fill);
        if (editing) {
            stroke.setStrokeWidth(1.5f * d);
            stroke.setColor(0xAA4FC3F7);
            c.drawRoundRect(r, 10 * d, 10 * d, stroke);
        }
        float ip = 8 * d;
        float l = r.left + ip, t = r.top + ip, rr = r.right - ip, b = r.bottom - ip;

        if (w.title.length() > 0) {
            float ts = Math.min(13 * d, (b - t) * 0.2f);
            textP.setColor(w.titleColor != 0 ? w.titleColor : st.fontColor);
            textP.setFakeBoldText(true);
            drawText(c, w.title, l, rr, t + ts, w.align, ts);
            textP.setFakeBoldText(false);
            t += ts * 1.5f;
        }
        if (rr - l < 4 || b - t < 4) return;

        if (w.series.isEmpty()) {
            textP.setColor(0xFF777777);
            drawText(c, "No sensors", l, rr, (t + b) / 2f, 1, 12 * d);
            return;
        }

        switch (w.type) {
            case Model.NUMBER:
                drawNumber(c, w, l, t, rr, b);
                break;
            case Model.BAR:
                drawBar(c, w, l, t, rr, b);
                break;
            case Model.LINE:
                drawChart(c, w, l, t, rr, b, false);
                break;
            case Model.AREA:
                drawChart(c, w, l, t, rr, b, true);
                break;
            default:
                drawGauge(c, w, l, t, rr, b);
                break;
        }
    }

    private void drawNumber(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float band = (b - t) / n;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float top = t + i * band;
            float vs = Math.min(band * 0.5f, 34 * d);
            float ls = Math.min(band * 0.22f, 12 * d);
            textP.setColor(colorFor(s, sn));
            textP.setFakeBoldText(true);
            drawText(c, valueText(sn), l, r, top + band * 0.12f + vs, w.align, vs);
            textP.setFakeBoldText(false);
            textP.setColor(st.labelColor);
            drawText(c, labelOf(s, sn), l, r, top + band * 0.12f + vs + ls * 1.3f, w.align, ls);
        }
    }

    private void drawGauge(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        int cols = n <= 3 ? n : (int) Math.ceil(Math.sqrt(n));
        int rows = (n + cols - 1) / cols;
        float cw = (r - l) / cols;
        float ch = (b - t) / rows;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float cl = l + (i % cols) * cw;
            float ct = t + (i / cols) * ch;
            float ls = Math.min(12 * d, ch * 0.18f);
            float dia = Math.max(8 * d, Math.min(cw, ch - ls * 1.5f));
            float sw = Math.max(3 * d, dia * 0.11f);
            float cx = cl + cw / 2f;
            float cy = ct + (ch - ls * 1.5f) / 2f;
            tmp.set(cx - dia / 2f + sw / 2f, cy - dia / 2f + sw / 2f,
                    cx + dia / 2f - sw / 2f, cy + dia / 2f - sw / 2f);

            stroke.setStrokeWidth(sw);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(0x33FFFFFF);
            c.drawArc(tmp, 135, 270, false, stroke);
            float f = fraction(s, sn);
            if (f > 0.005f) {
                stroke.setColor(colorFor(s, sn));
                c.drawArc(tmp, 135, 270 * f, false, stroke);
            }
            stroke.setStrokeCap(Paint.Cap.BUTT);

            textP.setColor(st.fontColor);
            textP.setFakeBoldText(true);
            drawText(c, valueText(sn), cx - dia * 0.36f, cx + dia * 0.36f, cy + dia * 0.09f, 1, dia * 0.26f);
            textP.setFakeBoldText(false);
            textP.setColor(st.labelColor);
            drawText(c, labelOf(s, sn), cl + 2 * d, cl + cw - 2 * d, ct + ch - ls * 0.3f, 1, ls);
        }
    }

    private void drawBar(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float band = (b - t) / n;
        float split = l + (r - l) * 0.62f;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float top = t + i * band;
            float ls = Math.min(band * 0.38f, 13 * d);
            float bh = Math.max(4 * d, Math.min(band * 0.32f, 14 * d));
            float baseline = top + ls;

            textP.setColor(st.labelColor);
            drawText(c, labelOf(s, sn), l, split - 4 * d, baseline, 0, ls);
            textP.setColor(st.fontColor);
            textP.setFakeBoldText(true);
            drawText(c, valueText(sn), split, r, baseline, 2, ls);
            textP.setFakeBoldText(false);

            float y = top + ls * 1.4f;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x33FFFFFF);
            tmp.set(l, y, r, y + bh);
            c.drawRoundRect(tmp, bh / 2f, bh / 2f, fill);
            float f = fraction(s, sn);
            if (f > 0.002f) {
                fill.setColor(colorFor(s, sn));
                float fw = Math.max(bh, (r - l) * f);
                tmp.set(l, y, l + Math.min(fw, r - l), y + bh);
                c.drawRoundRect(tmp, bh / 2f, bh / 2f, fill);
            }
        }
    }

    private void drawChart(Canvas c, Model.Widget w, float l, float t, float r, float b, boolean area) {
        int n = w.series.size();
        float ls = Math.min(11 * d, (b - t) * 0.16f);
        float cb = b - ls * 1.7f;
        if (cb - t < 6 * d) return;

        stroke.setStrokeWidth(1);
        stroke.setColor(0x22FFFFFF);
        for (int k = 0; k <= 3; k++) {
            float y = t + (cb - t) * k / 3f;
            c.drawLine(l, y, r, y, stroke);
        }

        float step = (r - l) / (History.N - 1);
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            History.Ring ring = hist.ring(s.sensorId);
            if (ring == null || ring.count < 2) continue;
            double[] rg = range(s, sn);
            path.reset();
            float firstX = 0, lastX = 0;
            for (int k = 0; k < ring.count; k++) {
                float x = r - (ring.count - 1 - k) * step;
                float f = (float) Math.max(0, Math.min(1, (ring.get(k) - rg[0]) / (rg[1] - rg[0])));
                float y = cb - f * (cb - t);
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
                path2.lineTo(lastX, cb);
                path2.lineTo(firstX, cb);
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

        // legend: one cell per series along the bottom
        float cw = (r - l) / n;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float cl = l + i * cw;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(s.color);
            c.drawCircle(cl + ls * 0.4f, b - ls * 0.35f, ls * 0.3f, fill);
            textP.setColor(st.labelColor);
            drawText(c, labelOf(s, sn) + " " + valueText(sn), cl + ls, cl + cw - 2 * d, b - ls * 0.1f, 0, ls);
        }
    }
}
