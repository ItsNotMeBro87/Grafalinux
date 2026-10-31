package org.grafalinux.monitor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

/** Plain data classes plus their JSON (de)serialisation. */
final class Model {
    private Model() {}

    static final int WHITE = 0xFFFFFFFF;
    static final int BLACK = 0xFF000000;

    static final int NUMBER = 0, GAUGE = 1, BAR = 2, LINE = 3, AREA = 4, DONUT = 5, PIE = 6, TEXT = 7;
    static final String[] TYPE_NAMES = {"Number", "Gauge", "Bar", "Line graph", "Area graph",
            "Donut", "Pie", "Text"};
    static final String[] ALIGN_NAMES = {"Left", "Center", "Right"};
    static final String[] NAME_MODE_NAMES = {"Follow global setting", "Show sensor names", "Hide sensor names"};
    static final String[] TEXT_SIZE_NAMES = {"Follow global setting", "Small", "Normal", "Large", "Extra large"};

    static final int MAX_COLS = 12;
    static final int MAX_ROWS = 50;
    static final int MAX_GAP_DP = 24;

    // Threshold groups: key, display name, is-percent, default warning, default critical
    static final String[] TH_KEYS = {"cpu_usage", "cpu_temp", "gpu_usage", "gpu_temp",
            "ram_usage", "disk_usage", "temp", "drive_temp"};
    static final String[] TH_NAMES = {"CPU usage", "CPU temperature", "GPU usage", "GPU temperature",
            "RAM / VRAM usage", "Disk usage", "Board / other temperature", "Drive temperature"};
    static final boolean[] TH_PERCENT = {true, false, true, false, true, true, false, false};
    static final float[] TH_WARN_DEF = {70, 60, 70, 60, 70, 80, 60, 45};
    static final float[] TH_CRIT_DEF = {90, 80, 90, 80, 90, 90, 85, 55};

    static int thIndex(String key) {
        if (key == null) return -1;
        for (int i = 0; i < TH_KEYS.length; i++) {
            if (TH_KEYS[i].equals(key)) return i;
        }
        return -1;
    }

    static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------
    static final class Server {
        String id = newId();
        String name = "";
        String host = "";
        int port = 8787;
        String token = "";

        String url(String path) {
            return "http://" + host + ":" + port + path;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("host", host);
            o.put("port", port);
            o.put("token", token);
            return o;
        }

        static Server fromJson(JSONObject o) {
            Server s = new Server();
            s.id = o.optString("id", newId());
            s.name = o.optString("name", "");
            s.host = o.optString("host", "");
            s.port = o.optInt("port", 8787);
            s.token = o.optString("token", "");
            return s;
        }
    }

    // ------------------------------------------------------------------
    static final class Series {
        String sensorId = "";
        String label = "";          // empty = use the sensor's own label
        int color = WHITE;
        double min = Double.NaN;    // NaN = automatic
        double max = Double.NaN;
        String icon = "";           // "" = automatic, "none" = no icon, otherwise an icon name
        int fanMode = 0;            // fan sensors only: 0 follow global, 1 percent, 2 RPM

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("sensor", sensorId);
            o.put("icon", icon);
            o.put("fanMode", fanMode);
            o.put("label", label);
            o.put("color", color);
            if (!Double.isNaN(min)) o.put("min", min);
            if (!Double.isNaN(max)) o.put("max", max);
            return o;
        }

        static Series fromJson(JSONObject o) {
            Series s = new Series();
            s.sensorId = o.optString("sensor", "");
            s.label = o.optString("label", "");
            s.color = o.optInt("color", WHITE);
            s.icon = o.optString("icon", "");
            s.fanMode = o.optInt("fanMode", 0);
            s.min = o.has("min") ? o.optDouble("min", Double.NaN) : Double.NaN;
            s.max = o.has("max") ? o.optDouble("max", Double.NaN) : Double.NaN;
            return s;
        }
    }

    // ------------------------------------------------------------------
    static final class Widget {
        String id = newId();
        int type = GAUGE;
        String title = "";
        int x = 0, y = 0, w = 2, h = 2;
        int align = 1;              // 0 left, 1 center, 2 right
        int titleColor = 0;         // 0 = use the global font colour
        int nameMode = 0;           // 0 follow global, 1 show sensor names, 2 hide them
        int textSize = 0;           // 0 follow global, 1 small, 2 normal, 3 large, 4 extra large
        boolean stacked = false;    // stacked bars / concentric gauges
        String text = "";           // content of a Text widget
        boolean rounded = true;     // rounded or sharp corners / line ends
        boolean vertical = false;   // bars: vertical columns instead of horizontal bars
        int ringGap = 2;            // dp between stacked rings
        int ringThick = 1;          // 0 thin, 1 normal, 2 thick
        ArrayList<Series> series = new ArrayList<Series>();

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("type", type);
            o.put("title", title);
            o.put("x", x);
            o.put("y", y);
            o.put("w", w);
            o.put("h", h);
            o.put("align", align);
            o.put("titleColor", titleColor);
            o.put("nameMode", nameMode);
            o.put("textSize", textSize);
            o.put("stacked", stacked);
            o.put("text", text);
            o.put("rounded", rounded);
            o.put("vertical", vertical);
            o.put("ringGap", ringGap);
            o.put("ringThick", ringThick);
            JSONArray arr = new JSONArray();
            for (Series s : series) arr.put(s.toJson());
            o.put("series", arr);
            return o;
        }

        static Widget fromJson(JSONObject o) {
            Widget w = new Widget();
            w.id = o.optString("id", newId());
            w.type = o.optInt("type", GAUGE);
            w.title = o.optString("title", "");
            w.x = o.optInt("x", 0);
            w.y = o.optInt("y", 0);
            w.w = o.optInt("w", 2);
            w.h = o.optInt("h", 2);
            w.align = o.optInt("align", 1);
            w.titleColor = o.optInt("titleColor", 0);
            w.nameMode = o.optInt("nameMode", 0);
            w.textSize = o.optInt("textSize", 0);
            w.stacked = o.optBoolean("stacked", false);
            w.text = o.optString("text", "");
            w.rounded = o.optBoolean("rounded", true);
            w.vertical = o.optBoolean("vertical", false);
            w.ringGap = o.optInt("ringGap", 2);
            w.ringThick = o.optInt("ringThick", 1);
            JSONArray arr = o.optJSONArray("series");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject so = arr.optJSONObject(i);
                    if (so != null) w.series.add(Series.fromJson(so));
                }
            }
            return w;
        }
    }

    // ------------------------------------------------------------------
    static final class Dashboard {
        String id = newId();
        String name = "Main";
        String serverId = "";
        int cols = 5;
        int rows = 4;
        int gap = 6;                    // dp
        boolean outerPadding = true;
        boolean locked = false;
        ArrayList<Widget> widgets = new ArrayList<Widget>();

        /** Copies layout and widgets (not the data source or lock state) from another dashboard. */
        void copyFrom(Dashboard o) {
            name = o.name;
            cols = o.cols;
            rows = o.rows;
            gap = o.gap;
            outerPadding = o.outerPadding;
            widgets = o.widgets;
        }

        /** Keep every widget inside the grid and make sure the grid has enough rows. */
        void fit() {
            cols = Math.max(1, Math.min(MAX_COLS, cols));
            for (Widget w : widgets) {
                w.w = Math.max(1, Math.min(w.w, cols));
                w.h = Math.max(1, w.h);
                w.x = Math.max(0, Math.min(w.x, cols - w.w));
                w.y = Math.max(0, w.y);
                rows = Math.max(rows, w.y + w.h);
            }
            rows = Math.max(1, Math.min(MAX_ROWS, rows));
        }

        int minRows() {
            int m = 1;
            for (Widget w : widgets) m = Math.max(m, w.y + w.h);
            return m;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("serverId", serverId);
            o.put("cols", cols);
            o.put("rows", rows);
            o.put("gap", gap);
            o.put("outerPadding", outerPadding);
            o.put("locked", locked);
            JSONArray arr = new JSONArray();
            for (Widget w : widgets) arr.put(w.toJson());
            o.put("widgets", arr);
            return o;
        }

        static Dashboard fromJson(JSONObject o) {
            Dashboard d = new Dashboard();
            d.id = o.optString("id", newId());
            d.name = o.optString("name", "Main");
            d.serverId = o.optString("serverId", "");
            d.cols = o.optInt("cols", 5);
            d.rows = o.optInt("rows", 4);
            d.gap = o.optInt("gap", 6);
            d.outerPadding = o.optBoolean("outerPadding", true);
            d.locked = o.optBoolean("locked", false);
            JSONArray arr = o.optJSONArray("widgets");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject wo = arr.optJSONObject(i);
                    if (wo != null) d.widgets.add(Widget.fromJson(wo));
                }
            }
            d.fit();
            return d;
        }
    }

    // ------------------------------------------------------------------
    static final class Settings {
        int fontColor = WHITE;
        int labelColor = WHITE;
        int bgColor = BLACK;

        boolean thEnabled = true;
        int warnColor = 0xFFE5A50A;
        int critColor = 0xFFDA4453;
        final float[] thWarn = new float[TH_KEYS.length];
        final float[] thCrit = new float[TH_KEYS.length];

        boolean tempF = false;
        int intervalMs = 1000;
        boolean keepOn = false;
        int orientation = 0;            // 0 auto, 1 portrait, 2 landscape
        int textScale = 100;            // percent, 60-160
        boolean showNames = false;      // show automatic sensor names on widgets
        boolean fullscreen = false;
        int menuMode = 1;               // 0 always visible, 1 auto-hide, 2 hidden
        int bgMode = 0;                 // 0 solid, 1 gradient, 2 image
        int bgGrad1 = 0xFF0F2027;
        int bgGrad2 = 0xFF2C5364;
        int bgGrad3 = 0xFF203A43;
        boolean bgGrad3On = false;
        int bgGradDir = 0;              // 0 top to bottom, 1 left to right, 2 diagonal
        int bgDim = 30;                 // percent, darkens a background image
        boolean rateBits = false;       // show network / disk speeds in bits
        boolean fanPercent = false;     // show fan speed as a percentage (fan sensors only)
        int innerPad = 2;               // dp inside each widget card
        int iconColor = WHITE;
        boolean showIcons = true;
        boolean thGradient = false;     // fade gradually towards the warning / critical colours

        Settings() {
            resetThresholds();
        }

        void resetThresholds() {
            for (int i = 0; i < TH_KEYS.length; i++) {
                thWarn[i] = TH_WARN_DEF[i];
                thCrit[i] = TH_CRIT_DEF[i];
            }
        }
    }
}
