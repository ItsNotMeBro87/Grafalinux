package org.grafalinux.monitor;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Built-in outline icons (24 x 24 grid, 2 px round strokes), drawn as vector paths.
 * They are original artwork made for this app (GPLv3), in the same outline style as Tabler / Noctalia.
 * Path format: M x y | L x y | C x1 y1 x2 y2 x y | Z | O cx cy r | R x y w h r
 */
final class Icons {
    private Icons() {}

    static final String[] NAMES = {
            "cpu",
            "thermometer",
            "flame",
            "fan",
            "gpu",
            "ram",
            "drive",
            "network",
            "bolt",
            "gauge",
            "clock",
            "chart",
            "battery",
            "activity"
    };

    static final String[] LABELS = {
            "CPU",
            "Thermometer",
            "Flame",
            "Fan",
            "Graphics card",
            "Memory",
            "Drive",
            "Network",
            "Power",
            "Gauge",
            "Clock",
            "Chart",
            "Battery",
            "Activity"
    };

    private static final String[] DATA = {
            "R 6 6 12 12 2 R 9.5 9.5 5 5 1 M 9 3 L 9 6 M 15 3 L 15 6 M 9 18 L 9 21 M 15 18 L 15 21 M 3 9 L 6 9 M 3 15 L 6 15 M 18 9 L 21 9 M 18 15 L 21 15",
            "M 10 13.5 L 10 5 C 10 3.9 10.9 3 12 3 C 13.1 3 14 3.9 14 5 L 14 13.5 C 15.2 14.4 16 15.8 16 17.5 C 16 19.9 14.2 21.5 12 21.5 C 9.8 21.5 8 19.9 8 17.5 C 8 15.8 8.8 14.4 10 13.5 Z M 12 8.5 L 12 16.5 O 12 17.5 0.6",
            "M 12 21 C 8.5 21 6.5 18.3 6.5 15 C 6.5 11.5 9 9.5 10 6 C 10.5 4.5 11.4 3.5 12 3 C 12 6 13.5 8 15 10 C 16.4 11.8 17.5 13.3 17.5 15 C 17.5 18.3 15.5 21 12 21 Z M 12 17.5 C 10.8 17.5 10 16.6 10 15.5 C 10 14.4 11 13.5 12 12.5 C 13 13.5 14 14.4 14 15.5 C 14 16.6 13.2 17.5 12 17.5 Z",
            "O 12 12 1.2 M 12.00 10.40 C 10.50 6.50 13.20 3.80 16.20 3.20 M 13.39 11.20 C 16.01 7.95 19.70 8.94 21.72 11.24 M 13.39 12.80 C 17.51 13.45 18.50 17.14 17.52 20.04 M 12.00 13.60 C 13.50 17.50 10.80 20.20 7.80 20.80 M 10.61 12.80 C 7.99 16.05 4.30 15.06 2.28 12.76 M 10.61 11.20 C 6.49 10.55 5.50 6.86 6.48 3.96",
            "R 2 6 20 11 2 O 8.5 11.5 2.6 O 15.5 11.5 2.6 M 5 17 L 5 19.5 M 9 17 L 9 19.5",
            "R 3 7 18 9 1.5 M 7 16 L 7 19 M 12 16 L 12 19 M 17 16 L 17 19 M 8 10.5 L 8 12.5 M 12 10.5 L 12 12.5 M 16 10.5 L 16 12.5",
            "R 3 5 18 14 2 M 3 14 L 21 14 M 7 17 L 7.01 17 M 11 17 L 17 17",
            "M 8 4 L 8 20 M 4.5 7.5 L 8 4 L 11.5 7.5 M 16 4 L 16 20 M 12.5 16.5 L 16 20 L 19.5 16.5",
            "M 13 3 L 5.5 13.5 L 12 13.5 L 11 21 L 18.5 10.5 L 12 10.5 Z",
            "M 4.64 18.25 L 3.79 16.20 L 3.50 14.00 L 3.79 11.80 L 4.64 9.75 L 5.99 7.99 L 7.75 6.64 L 9.80 5.79 L 12.00 5.50 L 14.20 5.79 L 16.25 6.64 L 18.01 7.99 L 19.36 9.75 L 20.21 11.80 L 20.50 14.00 L 20.21 16.20 L 19.36 18.25 M 12 14 L 16 9 O 12 14 1",
            "O 12 12 9 M 12 7 L 12 12 L 15.5 14",
            "M 5 20 L 5 12 M 10 20 L 10 6 M 15 20 L 15 10 M 20 20 L 20 4",
            "R 2.5 7 16 10 2 M 21 11 L 21 13 R 5.5 10 7 4 0.5",
            "M 3 12 L 7 12 L 10 5 L 14 19 L 17 12 L 21 12"
    };

    private static final Path[] PATHS = new Path[NAMES.length];
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeJoin(Paint.Join.ROUND);
        P.setStrokeWidth(2f);
    }

    static int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) return i;
        }
        return -1;
    }

    /** The icon used when the user has not picked one for a sensor. */
    static String auto(Sensors.Sensor sn) {
        if (sn == null) return null;
        if (sn.fan) return "fan";
        String id = sn.id;
        if (id.startsWith("cpu.")) {
            if (sn.unit == Sensors.Unit.CELSIUS) return "thermometer";
            if (sn.unit == Sensors.Unit.MHZ) return "gauge";
            return "cpu";
        }
        if (id.startsWith("phone.")) {
            if (sn.unit == Sensors.Unit.CELSIUS) return "thermometer";
            if (sn.unit == Sensors.Unit.VOLT) return "bolt";
            return "battery";
        }
        if (id.startsWith("load.")) return "activity";
        if (id.startsWith("mem.") || id.startsWith("swap.")) return "ram";
        if (id.startsWith("gpu.")) {
            if (sn.unit == Sensors.Unit.CELSIUS) return "thermometer";
            if (sn.unit == Sensors.Unit.BYTES || id.endsWith(".vram_pct")) return "ram";
            if (sn.unit == Sensors.Unit.WATT) return "bolt";
            if (sn.unit == Sensors.Unit.MHZ) return "gauge";
            return "gpu";
        }
        if (id.startsWith("fs.") || id.startsWith("io.")) return "drive";
        if (id.startsWith("net.")) return "network";
        if (id.equals("uptime")) return "clock";
        switch (sn.unit) {
            case CELSIUS:
                return "thermometer";
            case WATT:
            case VOLT:
                return "bolt";
            case BYTES:
                return "ram";
            case BYTES_PS:
                return "network";
            case MHZ:
                return "gauge";
            case SECONDS:
                return "clock";
            default:
                return "activity";
        }
    }

    private static final java.util.HashMap<Integer, Path> SIZED = new java.util.HashMap<Integer, Path>();
    private static final Matrix M = new Matrix();

    /**
     * Draws an icon at its real pixel size. The path is scaled once (and cached) instead of scaling the
     * canvas, because a scaled canvas makes Android enlarge a small bitmap of the path and big icons blur.
     */
    static void draw(Canvas c, String name, float x, float y, float size, int color) {
        int i = indexOf(name);
        if (i < 0 || size < 2) return;
        int px = Math.round(size);
        int key = i * 100000 + px;
        Path p = SIZED.get(key);
        if (p == null) {
            Path base = PATHS[i];
            if (base == null) {
                base = parse(DATA[i]);
                PATHS[i] = base;
            }
            if (SIZED.size() > 80) SIZED.clear();
            p = new Path();
            M.setScale(px / 24f, px / 24f);
            base.transform(M, p);
            SIZED.put(key, p);
        }
        P.setColor(color);
        P.setStrokeWidth(Math.max(1f, 2f * px / 24f));
        c.save();
        c.translate(x, y);
        c.drawPath(p, P);
        c.restore();
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    private static Path parse(String d) {
        Path p = new Path();
        String[] t = d.trim().split("\\s+");
        int i = 0;
        while (i < t.length) {
            char cmd = t[i++].charAt(0);
            switch (cmd) {
                case 'M':
                    p.moveTo(f(t[i]), f(t[i + 1]));
                    i += 2;
                    break;
                case 'L':
                    p.lineTo(f(t[i]), f(t[i + 1]));
                    i += 2;
                    break;
                case 'C':
                    p.cubicTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]), f(t[i + 4]), f(t[i + 5]));
                    i += 6;
                    break;
                case 'Z':
                    p.close();
                    break;
                case 'O':
                    p.addCircle(f(t[i]), f(t[i + 1]), f(t[i + 2]), Path.Direction.CW);
                    i += 3;
                    break;
                case 'R': {
                    float x = f(t[i]), y = f(t[i + 1]), w = f(t[i + 2]), h = f(t[i + 3]), r = f(t[i + 4]);
                    p.addRoundRect(new RectF(x, y, x + w, y + h), r, r, Path.Direction.CW);
                    i += 5;
                    break;
                }
                default:
                    break;
            }
        }
        return p;
    }
}
