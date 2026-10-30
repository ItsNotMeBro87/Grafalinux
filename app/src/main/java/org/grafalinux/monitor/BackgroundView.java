package org.grafalinux.monitor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** Fixed dashboard background (solid colour, gradient or image) drawn behind the scrolling widgets. */
final class BackgroundView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private Model.Settings st;
    private Bitmap bmp;
    private Shader shader;

    BackgroundView(Context c) {
        super(c);
        p.setFilterBitmap(true);
    }

    void apply(Model.Settings s, Bitmap b) {
        st = s;
        bmp = b;
        rebuild();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rebuild();
    }

    private void rebuild() {
        shader = null;
        if (st == null || getWidth() == 0 || getHeight() == 0 || st.bgMode != 1) return;
        int[] cols = st.bgGrad3On
                ? new int[]{st.bgGrad1, st.bgGrad2, st.bgGrad3}
                : new int[]{st.bgGrad1, st.bgGrad2};
        float w = getWidth();
        float h = getHeight();
        float x1 = 0, y1 = 0;
        if (st.bgGradDir == 1) {
            x1 = w;
        } else if (st.bgGradDir == 2) {
            x1 = w;
            y1 = h;
        } else {
            y1 = h;
        }
        shader = new LinearGradient(0, 0, x1, y1, cols, null, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (st == null) {
            c.drawColor(Color.BLACK);
            return;
        }
        if (st.bgMode == 1 && shader != null) {
            p.setShader(shader);
            c.drawPaint(p);
            p.setShader(null);
        } else if (st.bgMode == 2 && bmp != null) {
            c.drawColor(Color.BLACK);
            float sw = getWidth();
            float sh = getHeight();
            float sc = Math.max(sw / bmp.getWidth(), sh / bmp.getHeight());   // fill the screen, centre-crop
            m.setScale(sc, sc);
            m.postTranslate((sw - bmp.getWidth() * sc) / 2f, (sh - bmp.getHeight() * sc) / 2f);
            c.drawBitmap(bmp, m, p);
            if (st.bgDim > 0) c.drawColor((st.bgDim * 255 / 100) << 24);       // darken for readability
        } else {
            c.drawColor(st.bgColor);
        }
    }
}
