package org.grafalinux.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

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
    private Sensors.Sensor sensor(Model.Series s) {
        return table == null ? null : table.map.get(s.sensorId);
    }

    private String valueText(Model.Series s, Sensors.Sensor sn) {
        if (sn == null) return "--";
        if (st.fanPercent && sn.unit == Sensors.Unit.RPM) {
            double mx = !Double.isNaN(s.max) ? s.max : (!Double.isNaN(sn.max) ? sn.max : 3000);
            return String.format(Locale.US, "%.0f%%", 100.0 * sn.value / Math.max(1, mx));
        }
        return Sensors.format(sn.unit, sn.value, st.tempF);
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
        float ts = tscale(w);
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
            float tsz = size(13 * d, (b - t) * 0.2f, ts);
            textP.setColor(w.titleColor != 0 ? w.titleColor : st.fontColor);
            textP.setFakeBoldText(true);
            drawText(c, w.title, l, rr, t + tsz, w.align, tsz);
            textP.setFakeBoldText(false);
            t += tsz * 1.5f;
        }
        if (rr - l < 4 || b - t < 4) return;

        if (w.type == Model.TEXT) {
            drawTextWidget(c, w, l, t, rr, b);
            return;
        }

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
            case Model.DONUT:
                drawRings(c, w, l, t, rr, b, -90, 360);
                break;
            case Model.PIE:
                drawPie(c, w, l, t, rr, b);
                break;
            default:
                drawGauge(c, w, l, t, rr, b);
                break;
        }
    }

    // ---- number ----
    private void drawNumber(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float ts = tscale(w);
        boolean lbl = anyLabel(w);
        float band = (b - t) / n;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float top = t + i * band;
            float vs = size(34 * d, band * (lbl ? 0.5f : 0.7f), ts);
            float ls = size(12 * d, band * 0.22f, ts);
            textP.setColor(colorFor(s, sn));
            textP.setFakeBoldText(true);
            if (lbl) {
                drawText(c, valueText(s, sn), l, r, top + band * 0.12f + vs, w.align, vs);
            } else {
                drawText(c, valueText(s, sn), l, r, top + band / 2f + vs * 0.35f, w.align, vs);
            }
            textP.setFakeBoldText(false);
            if (lbl) {
                textP.setColor(st.labelColor);
                drawText(c, labelFor(w, s, sn), l, r, top + band * 0.12f + vs + ls * 1.3f, w.align, ls);
            }
        }
    }

    // ---- gauge ----
    private void drawGauge(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        if (w.stacked && n > 1) {
            drawStackedGauge(c, w, l, t, r, b);
            return;
        }
        float ts = tscale(w);
        boolean lbl = anyLabel(w);
        int cols = n <= 3 ? n : (int) Math.ceil(Math.sqrt(n));
        int rows = (n + cols - 1) / cols;
        float cw = (r - l) / cols;
        float ch = (b - t) / rows;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float cl = l + (i % cols) * cw;
            float ct = t + (i / cols) * ch;
            float ls = lbl ? size(12 * d, ch * 0.18f, ts) : 0;
            float reserve = lbl ? ls * 1.5f : 0;
            float dia = Math.max(8 * d, Math.min(cw, ch - reserve));
            float sw = Math.max(3 * d, dia * 0.11f);
            float cx = cl + cw / 2f;
            float cy = ct + (ch - reserve) / 2f;
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

            float vsz = size(dia * 0.26f, dia * 0.32f, ts);
            textP.setColor(st.fontColor);
            textP.setFakeBoldText(true);
            drawText(c, valueText(s, sn), cx - dia * 0.36f, cx + dia * 0.36f, cy + vsz * 0.35f, 1, vsz);
            textP.setFakeBoldText(false);
            if (lbl) {
                textP.setColor(st.labelColor);
                drawText(c, labelFor(w, s, sn), cl + 2 * d, cl + cw - 2 * d, ct + ch - ls * 0.3f, 1, ls);
            }
        }
    }

    /** Concentric rings: the first sensor is the outer ring, later ones go inwards. */
    private void drawRings(Canvas c, Model.Widget w, float l, float t, float r, float b,
                           float start, float sweepMax) {
        int n = w.series.size();
        float ts = tscale(w);
        boolean lbl = anyLabel(w);
        float ls = lbl ? size(11 * d, (b - t) * 0.14f, ts) : 0;
        float legendH = lbl ? ls * 1.8f : 0;
        float areaH = (b - t) - legendH;
        float dia = Math.max(8 * d, Math.min(r - l, areaH));
        float cx = (l + r) / 2f;
        float cy = t + areaH / 2f;
        float rad0 = dia / 2f;
        float pitch = n == 1 ? rad0 * 0.32f : (rad0 * 0.62f) / n;
        float sw = Math.max(2 * d, pitch * 0.72f);

        stroke.setStrokeCap(sweepMax < 360 ? Paint.Cap.ROUND : Paint.Cap.BUTT);
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float rad = rad0 - sw / 2f - i * pitch - rad0 * 0.02f;
            if (rad < sw) break;
            tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
            stroke.setStrokeWidth(sw);
            stroke.setColor(0x33FFFFFF);
            c.drawArc(tmp, start, sweepMax, false, stroke);
            float f = fraction(s, sn);
            if (f > 0.005f) {
                stroke.setColor(colorFor(s, sn));
                c.drawArc(tmp, start, sweepMax * f, false, stroke);
            }
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);

        // values in the middle, one line per ring, in the ring's colour
        float boxH = rad0 * 0.62f;
        float lineH = Math.min(boxH / n, size(n == 1 ? 30 * d : 16 * d, boxH, ts));
        float y0 = cy - (lineH * n) / 2f;
        textP.setFakeBoldText(true);
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            textP.setColor(colorFor(s, sn));
            drawText(c, valueText(s, sn), cx - rad0 * 0.34f, cx + rad0 * 0.34f, y0 + (i + 0.8f) * lineH, 1, lineH * 0.85f);
        }
        textP.setFakeBoldText(false);

        if (lbl) drawLegend(c, w, l, r, b, ls, false);
    }

    private void drawStackedGauge(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        drawRings(c, w, l, t, r, b, 135, 270);
    }

    /** Pie: one sensor = its share of the maximum; several sensors = slices of their sum. */
    private void drawPie(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float ts = tscale(w);
        float ls = size(11 * d, (b - t) * 0.14f, ts);
        float areaH = (b - t) - ls * 1.8f;
        float dia = Math.max(8 * d, Math.min(r - l, areaH));
        float cx = (l + r) / 2f;
        float cy = t + areaH / 2f;
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
        drawLegend(c, w, l, r, b, ls, true);
    }

    /** Free text; each line is fitted to the widget. */
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

    // ---- bars ----
    private void drawBar(Canvas c, Model.Widget w, float l, float t, float r, float b) {
        int n = w.series.size();
        float ts = tscale(w);
        boolean lbl = anyLabel(w);
        if (w.stacked || !lbl) {
            drawInsideBars(c, w, l, t, r, b, ts);
            return;
        }
        float band = (b - t) / n;
        float split = l + (r - l) * 0.62f;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float top = t + i * band;
            float ls = size(13 * d, band * 0.38f, ts);
            float bh = Math.max(4 * d, Math.min(band * 0.32f, 14 * d));
            float baseline = top + ls;

            textP.setColor(st.labelColor);
            drawText(c, labelFor(w, s, sn), l, split - 4 * d, baseline, 0, ls);
            textP.setColor(st.fontColor);
            textP.setFakeBoldText(true);
            drawText(c, valueText(s, sn), split, r, baseline, 2, ls);
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

    /** Bars that use all the space, with the value (and label) drawn inside the bar. */
    private void drawInsideBars(Canvas c, Model.Widget w, float l, float t, float r, float b, float ts) {
        int n = w.series.size();
        float gapPx = w.stacked ? 0 : 3 * d;
        float bh = ((b - t) - gapPx * (n - 1)) / n;
        float cap = 48 * d * Math.max(1f, ts);
        if (bh > cap) bh = cap;
        float total = n * bh + gapPx * (n - 1);
        float y0 = t + ((b - t) - total) / 2f;
        float rad = w.stacked ? 0 : Math.min(bh / 2f, 8 * d);

        boolean clipped = false;
        if (w.stacked) {
            tmp.set(l, y0, r, y0 + total);
            path.reset();
            path.addRoundRect(tmp, 8 * d, 8 * d, Path.Direction.CW);
            c.save();
            c.clipPath(path);
            clipped = true;
        }
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            float y = y0 + i * (bh + gapPx);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x33FFFFFF);
            tmp.set(l, y, r, y + bh);
            c.drawRoundRect(tmp, rad, rad, fill);

            int col = colorFor(s, sn);
            float fillRight = l;
            float f = fraction(s, sn);
            if (sn != null && f > 0.002f) {
                float fw = w.stacked ? (r - l) * f : Math.max(Math.min(bh, r - l), (r - l) * f);
                fill.setColor(col);
                tmp.set(l, y, l + fw, y + bh);
                c.drawRoundRect(tmp, rad, rad, fill);
                fillRight = l + fw;
            }
            float tsz = size(14 * d, bh * 0.62f, ts);
            drawBarText(c, w, labelFor(w, s, sn), valueText(s, sn), l, r, y, bh, fillRight, col, tsz);

            if (w.stacked && i < n - 1) {
                stroke.setStrokeWidth(Math.max(1f, d));
                stroke.setColor(st.bgColor);
                c.drawLine(l, y + bh, r, y + bh, stroke);
            }
        }
        if (clipped) c.restore();
    }

    /** Draws text over a bar twice (clipped): dark-or-light over the fill, normal over the track. */
    private void drawBarText(Canvas c, Model.Widget w, String label, String value, float l, float r,
                             float y, float bh, float fillRight, int fillColor, float size) {
        float pad2 = Math.min(6 * d, bh * 0.3f);
        float base = y + bh / 2f + size * 0.35f;
        float lum = (0.299f * Color.red(fillColor) + 0.587f * Color.green(fillColor)
                + 0.114f * Color.blue(fillColor)) / 255f;
        int onFill = lum > 0.55f ? 0xFF000000 : 0xFFFFFFFF;
        if (fillRight > l + 0.5f) {
            c.save();
            c.clipRect(l, y, fillRight, y + bh);
            textP.setColor(onFill);
            barTextLine(c, w, label, value, l + pad2, r - pad2, base, size);
            c.restore();
        }
        if (fillRight < r - 0.5f) {
            c.save();
            c.clipRect(fillRight, y, r, y + bh);
            textP.setColor(st.fontColor);
            barTextLine(c, w, label, value, l + pad2, r - pad2, base, size);
            c.restore();
        }
    }

    private void barTextLine(Canvas c, Model.Widget w, String label, String value, float l, float r,
                             float base, float size) {
        if (label.length() > 0) {
            float split = l + (r - l) * 0.6f;
            drawText(c, label, l, split - 4 * d, base, 0, size);
            drawText(c, value, split, r, base, 2, size);
        } else {
            drawText(c, value, l, r, base, w.align, size);
        }
    }

    // ---- line / area ----
    private void drawChart(Canvas c, Model.Widget w, float l, float t, float r, float b, boolean area) {
        int n = w.series.size();
        float ts = tscale(w);
        float ls = size(11 * d, (b - t) * 0.16f, ts);
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
            double[] g = range(s, sn);
            double mn = g[0], mx = g[1];
            path.reset();
            float firstX = 0, lastX = 0;
            for (int k = 0; k < ring.count; k++) {
                float x = r - (ring.count - 1 - k) * step;
                float f = (float) Math.max(0, Math.min(1, (ring.get(k) - mn) / (mx - mn)));
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
        drawLegend(c, w, l, r, b, ls, true);
    }

    /** One cell per series along the bottom: colour dot, label (if enabled) and optionally the value. */
    private void drawLegend(Canvas c, Model.Widget w, float l, float r, float b, float ls, boolean withValue) {
        int n = w.series.size();
        float cw = (r - l) / n;
        for (int i = 0; i < n; i++) {
            Model.Series s = w.series.get(i);
            Sensors.Sensor sn = sensor(s);
            String lab = labelFor(w, s, sn);
            String txt = withValue ? (lab.length() > 0 ? lab + " " + valueText(s, sn) : valueText(s, sn)) : lab;
            float cl = l + i * cw;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(s.color);
            c.drawCircle(cl + ls * 0.4f, b - ls * 0.35f, ls * 0.3f, fill);
            textP.setColor(st.labelColor);
            drawText(c, txt, cl + ls, cl + cw - 2 * d, b - ls * 0.1f, 0, ls);
        }
    }
}
