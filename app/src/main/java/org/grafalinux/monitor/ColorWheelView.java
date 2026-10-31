package org.grafalinux.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/** Colour wheel: a hue ring with a saturation / brightness square inside. */
final class ColorWheelView extends View {
    interface Listener {
        void onColor(int color);
    }

    private final float[] hsv = {0f, 1f, 1f};
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private Listener listener;
    private float cx, cy, rOuter, ringW, half;
    private SweepGradient sweep;
    private int mode = 0;      // 1 = dragging the hue ring, 2 = dragging the square

    ColorWheelView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setColor(int color) {
        Color.colorToHSV(color, hsv);
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int size = Math.min(MeasureSpec.getSize(wSpec), Math.round(260 * d));
        if (size <= 0) size = Math.round(260 * d);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        rOuter = Math.min(w, h) / 2f - 3 * d;
        ringW = rOuter * 0.18f;
        half = (rOuter - ringW) * 0.68f;
        sweep = new SweepGradient(cx, cy, new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000}, null);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (sweep == null) return;
        // hue ring
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(ringW);
        p.setShader(sweep);
        c.drawCircle(cx, cy, rOuter - ringW / 2f, p);
        p.setShader(null);

        // saturation (left to right) and brightness (top to bottom) square
        int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
        float l = cx - half, t = cy - half, r = cx + half, b = cy + half;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(l, 0, r, 0, Color.WHITE, pure, Shader.TileMode.CLAMP));
        c.drawRect(l, t, r, b, p);
        p.setShader(new LinearGradient(0, t, 0, b, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRect(l, t, r, b, p);
        p.setShader(null);

        // markers
        double a = Math.toRadians(hsv[0]);
        float hr = rOuter - ringW / 2f;
        marker(c, cx + (float) Math.cos(a) * hr, cy + (float) Math.sin(a) * hr, ringW * 0.42f);
        marker(c, l + hsv[1] * 2 * half, t + (1 - hsv[2]) * 2 * half, 7 * d);
    }

    private void marker(Canvas c, float x, float y, float rad) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f * d);
        p.setColor(0xFF000000);
        c.drawCircle(x, y, rad + d, p);
        p.setStrokeWidth(1.5f * d);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, rad, p);
    }

    private void update(float x, float y) {
        if (mode == 1) {
            float deg = (float) Math.toDegrees(Math.atan2(y, x));
            hsv[0] = (deg + 360f) % 360f;
        } else if (mode == 2) {
            hsv[1] = Math.max(0f, Math.min(1f, (x + half) / (2 * half)));
            hsv[2] = 1f - Math.max(0f, Math.min(1f, (y + half) / (2 * half)));
        } else {
            return;
        }
        invalidate();
        if (listener != null) listener.onColor(Color.HSVToColor(hsv));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() - cx;
        float y = e.getY() - cy;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float dist = (float) Math.hypot(x, y);
                if (dist >= rOuter - ringW - 6 * d && dist <= rOuter + 10 * d) {
                    mode = 1;
                } else if (Math.abs(x) <= half + 8 * d && Math.abs(y) <= half + 8 * d) {
                    mode = 2;
                } else {
                    mode = 0;
                }
                ViewParent vp = getParent();
                if (vp != null) vp.requestDisallowInterceptTouchEvent(true);
                update(x, y);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                update(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = 0;
                return true;
            default:
                return true;
        }
    }
}
