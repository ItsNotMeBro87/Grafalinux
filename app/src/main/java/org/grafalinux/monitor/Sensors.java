package org.grafalinux.monitor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Converts one linmon-agent snapshot into a flat list of sensors grouped by category. */
final class Sensors {
    private Sensors() {}

    /** Set from the settings: show transfer rates in bits instead of bytes. */
    static boolean rateBits = false;

    enum Unit { PERCENT, CELSIUS, MHZ, BYTES, BYTES_PS, RPM, VOLT, WATT, SECONDS, NONE }

    static final class Sensor {
        String id;
        String label;
        String category;
        Unit unit;
        double value;
        double min = 0;
        double max = Double.NaN;   // NaN = auto-scale (used for transfer rates)
        String thKey;              // threshold group, or null
        boolean fan;               // fan speed (RPM or duty percentage)
    }

    static final class Table {
        final LinkedHashMap<String, Sensor> map = new LinkedHashMap<String, Sensor>();
        final LinkedHashMap<String, ArrayList<Sensor>> byCat =
                new LinkedHashMap<String, ArrayList<Sensor>>();

        void markFan(String id) {
            Sensor s = map.get(id);
            if (s != null) s.fan = true;
        }

        void add(String cat, String id, String label, Unit unit, double v, double max, String th) {
            if (Double.isNaN(v) || map.containsKey(id)) return;
            Sensor s = new Sensor();
            s.id = id;
            s.label = label;
            s.category = cat;
            s.unit = unit;
            s.value = v;
            s.max = max;
            s.thKey = th;
            map.put(id, s);
            ArrayList<Sensor> l = byCat.get(cat);
            if (l == null) {
                l = new ArrayList<Sensor>();
                byCat.put(cat, l);
            }
            l.add(s);
        }
    }

    /** Adds this phone's own battery readings (from Android, not from the PC). Cheap: no polling. */
    static void addPhone(Context ctx, Table t) {
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        String cat = "This phone";
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) {
            t.add(cat, "phone.battery", "Battery level", Unit.PERCENT, 100.0 * level / scale, 100, null);
        }
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (temp != Integer.MIN_VALUE) {
            t.add(cat, "phone.battery_temp", "Battery temperature", Unit.CELSIUS, temp / 10.0, 70, "temp");
        }
        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        if (mv > 0) t.add(cat, "phone.battery_v", "Battery voltage", Unit.VOLT, mv / 1000.0, 5, null);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        t.add(cat, "phone.charging", "Charging (1 = plugged in)", Unit.NONE, plugged != 0 ? 1 : 0, 1, null);
    }

    static double opt(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return Double.NaN;
        return o.optDouble(k, Double.NaN);
    }

    private static String nice(String label, String key, String prefix) {
        if (label == null || label.length() == 0 || label.equals(key)) {
            return prefix + " " + key.replaceAll("\\D", "");
        }
        return label;
    }

    static Table parse(JSONObject s) throws JSONException {
        Table t = new Table();

        // ---- CPU ----
        JSONObject cpu = s.optJSONObject("cpu");
        if (cpu != null) {
            String cat = "CPU";
            t.add(cat, "cpu.usage", "CPU usage", Unit.PERCENT, opt(cpu, "usage_pct"), 100, "cpu_usage");
            t.add(cat, "cpu.temp", "CPU temperature", Unit.CELSIUS, opt(cpu, "temp_c"), 110, "cpu_temp");
            t.add(cat, "cpu.freq", "CPU frequency (average)", Unit.MHZ, opt(cpu, "freq_avg_mhz"), 5000, null);
            t.add(cat, "cpu.freqmax", "CPU frequency (highest core)", Unit.MHZ, opt(cpu, "freq_max_mhz"), 5000, null);
            JSONArray cores = cpu.optJSONArray("cores");
            if (cores != null) {
                for (int i = 0; i < cores.length(); i++) {
                    JSONObject c = cores.optJSONObject(i);
                    if (c == null) continue;
                    t.add(cat, "cpu.c" + i + ".usage", "Core " + i + " usage", Unit.PERCENT,
                            opt(c, "usage_pct"), 100, "cpu_usage");
                    t.add(cat, "cpu.c" + i + ".freq", "Core " + i + " frequency", Unit.MHZ,
                            opt(c, "freq_mhz"), 5000, null);
                }
            }
            JSONArray load = s.optJSONArray("load");
            if (load != null && load.length() >= 3) {
                double lmax = cores != null && cores.length() > 0 ? cores.length() : 8;
                t.add(cat, "load.1", "Load average (1 min)", Unit.NONE, load.optDouble(0, Double.NaN), lmax, null);
                t.add(cat, "load.5", "Load average (5 min)", Unit.NONE, load.optDouble(1, Double.NaN), lmax, null);
                t.add(cat, "load.15", "Load average (15 min)", Unit.NONE, load.optDouble(2, Double.NaN), lmax, null);
            }
        }

        // ---- memory ----
        JSONObject mem = s.optJSONObject("memory");
        if (mem != null) {
            String cat = "Memory";
            double total = opt(mem, "total_b");
            t.add(cat, "mem.used_pct", "RAM usage", Unit.PERCENT, opt(mem, "used_pct"), 100, "ram_usage");
            t.add(cat, "mem.used", "RAM used", Unit.BYTES, opt(mem, "used_b"), total, null);
            t.add(cat, "mem.avail", "RAM available", Unit.BYTES, opt(mem, "available_b"), total, null);
            t.add(cat, "mem.cached", "RAM cached", Unit.BYTES, opt(mem, "cached_b"), total, null);
            double st = opt(mem, "swap_total_b");
            double su = opt(mem, "swap_used_b");
            if (st > 0 && !Double.isNaN(su)) {
                t.add(cat, "swap.used_pct", "Swap usage", Unit.PERCENT, 100.0 * su / st, 100, "ram_usage");
                t.add(cat, "swap.used", "Swap used", Unit.BYTES, su, st, null);
            }
        }
        t.add("System", "uptime", "Uptime", Unit.SECONDS, opt(s, "uptime_s"), Double.NaN, null);

        // ---- GPUs ----
        JSONArray gpus = s.optJSONArray("gpus");
        if (gpus != null) {
            for (int i = 0; i < gpus.length(); i++) {
                JSONObject g = gpus.optJSONObject(i);
                if (g == null) continue;
                String gid = g.optString("id", "gpu" + i);
                String cat = "GPU: " + g.optString("name", gid) + (gpus.length() > 1 ? " (" + gid + ")" : "");
                String p = "gpu." + gid + ".";
                t.add(cat, p + "temp", "GPU temperature", Unit.CELSIUS, opt(g, "temp_c"), 110, "gpu_temp");
                t.add(cat, p + "util", "GPU usage", Unit.PERCENT, opt(g, "util_pct"), 100, "gpu_usage");
                t.add(cat, p + "memctrl", "Memory controller load", Unit.PERCENT, opt(g, "mem_ctrl_util_pct"), 100, null);
                t.add(cat, p + "vram_pct", "VRAM usage", Unit.PERCENT, opt(g, "mem_used_pct"), 100, "ram_usage");
                t.add(cat, p + "vram", "VRAM used", Unit.BYTES, opt(g, "mem_used_b"), opt(g, "mem_total_b"), null);
                t.add(cat, p + "fan_pct", "Fan speed", Unit.PERCENT, opt(g, "fan_pct"), 100, null);
                t.add(cat, p + "fan_rpm", "Fan speed (RPM)", Unit.RPM, opt(g, "fan_rpm"), 5000, null);
                t.markFan(p + "fan_pct");
                t.markFan(p + "fan_rpm");
                double pl = opt(g, "power_limit_w");
                t.add(cat, p + "power", "Power draw", Unit.WATT, opt(g, "power_w"), Double.isNaN(pl) ? 300 : pl, null);
                t.add(cat, p + "clk_core", "Core clock", Unit.MHZ, opt(g, "clock_core_mhz"), 2500, null);
                t.add(cat, p + "clk_mem", "Memory clock", Unit.MHZ, opt(g, "clock_mem_mhz"), 10000, null);
            }
        }

        // ---- storage ----
        JSONObject storage = s.optJSONObject("storage");
        if (storage != null) {
            JSONArray fs = storage.optJSONArray("filesystems");
            if (fs != null) {
                for (int i = 0; i < fs.length(); i++) {
                    JSONObject f = fs.optJSONObject(i);
                    if (f == null) continue;
                    String mnt = f.optString("mount", "?");
                    t.add("Storage", "fs." + mnt + ".pct", mnt + " usage", Unit.PERCENT, opt(f, "used_pct"), 100, "disk_usage");
                    t.add("Storage", "fs." + mnt + ".used", mnt + " used", Unit.BYTES, opt(f, "used_b"), opt(f, "total_b"), null);
                }
            }
            JSONArray io = storage.optJSONArray("io");
            if (io != null) {
                for (int i = 0; i < io.length(); i++) {
                    JSONObject d = io.optJSONObject(i);
                    if (d == null) continue;
                    String dev = d.optString("device", "?");
                    t.add("Disk I/O", "io." + dev + ".read", dev + " read", Unit.BYTES_PS, opt(d, "read_bps"), Double.NaN, null);
                    t.add("Disk I/O", "io." + dev + ".write", dev + " write", Unit.BYTES_PS, opt(d, "write_bps"), Double.NaN, null);
                }
            }
        }

        // ---- network ----
        JSONArray net = s.optJSONArray("network");
        if (net != null) {
            for (int i = 0; i < net.length(); i++) {
                JSONObject n = net.optJSONObject(i);
                if (n == null) continue;
                String ifc = n.optString("iface", "?");
                t.add("Network", "net." + ifc + ".rx", ifc + " download", Unit.BYTES_PS, opt(n, "rx_bps"), Double.NaN, null);
                t.add("Network", "net." + ifc + ".tx", ifc + " upload", Unit.BYTES_PS, opt(n, "tx_bps"), Double.NaN, null);
            }
        }

        // ---- hardware monitor chips (temps, fans, voltages...) ----
        JSONArray chips = s.optJSONArray("sensors");
        if (chips != null) {
            for (int i = 0; i < chips.length(); i++) {
                JSONObject ch = chips.optJSONObject(i);
                if (ch == null) continue;
                String cid = ch.optString("id", "chip" + i);
                String cat = ch.optString("label", cid);
                String kind = ch.optString("kind", "board");
                String tth = "temp";
                if (kind.equals("cpu")) tth = "cpu_temp";
                else if (kind.equals("gpu")) tth = "gpu_temp";
                else if (kind.equals("storage")) tth = "drive_temp";
                String p = "hw." + cid + ".";

                JSONArray a = ch.optJSONArray("temps");
                if (a != null) for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.optJSONObject(k);
                    if (o == null) continue;
                    String key = o.optString("key", "t" + k);
                    t.add(cat, p + key, nice(o.optString("label", ""), key, "Temp"),
                            Unit.CELSIUS, opt(o, "c"), 110, tth);
                }
                a = ch.optJSONArray("fans");
                if (a != null) for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.optJSONObject(k);
                    if (o == null) continue;
                    String key = o.optString("key", "f" + k);
                    t.add(cat, p + key, nice(o.optString("label", ""), key, "Fan"),
                            Unit.RPM, opt(o, "rpm"), 3000, null);
                    t.markFan(p + key);
                }
                a = ch.optJSONArray("pwms");
                if (a != null) for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.optJSONObject(k);
                    if (o == null) continue;
                    String key = o.optString("key", "p" + k);
                    t.add(cat, p + key, "PWM " + key.replaceAll("\\D", "") + " duty",
                            Unit.PERCENT, opt(o, "pct"), 100, null);
                    t.markFan(p + key);
                }
                a = ch.optJSONArray("volts");
                if (a != null) for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.optJSONObject(k);
                    if (o == null) continue;
                    String key = o.optString("key", "v" + k);
                    double v = opt(o, "v");
                    t.add(cat, p + key, nice(o.optString("label", ""), key, "Voltage"),
                            Unit.VOLT, v, v > 5 ? 14 : 5, null);
                }
                a = ch.optJSONArray("powers");
                if (a != null) for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.optJSONObject(k);
                    if (o == null) continue;
                    String key = o.optString("key", "w" + k);
                    t.add(cat, p + key, nice(o.optString("label", ""), key, "Power"),
                            Unit.WATT, opt(o, "w"), 200, null);
                }
            }
        }
        return t;
    }

    // ------------------------------------------------------------------
    static String format(Unit u, double v, boolean tempF) {
        switch (u) {
            case PERCENT:
                return String.format(Locale.US, "%.0f%%", v);
            case CELSIUS:
                if (tempF) return String.format(Locale.US, "%.0f\u00B0F", v * 9.0 / 5.0 + 32.0);
                return String.format(Locale.US, "%.0f\u00B0C", v);
            case MHZ:
                if (v >= 1000) return String.format(Locale.US, "%.2f GHz", v / 1000.0);
                return String.format(Locale.US, "%.0f MHz", v);
            case BYTES:
                return bytes(v, "");
            case BYTES_PS:
                return rateBits ? bits(v * 8, "/s") : bytes(v, "/s");
            case RPM:
                return String.format(Locale.US, "%.0f RPM", v);
            case VOLT:
                return String.format(Locale.US, "%.2f V", v);
            case WATT:
                return String.format(Locale.US, "%.1f W", v);
            case SECONDS:
                return duration(v);
            default:
                return String.format(Locale.US, "%.2f", v);
        }
    }

    /** Value and unit as two parts, e.g. {"42", "\u00B0C"}, so a widget can draw the unit smaller. */
    static String[] formatParts(Unit u, double v, boolean tempF) {
        switch (u) {
            case PERCENT:
                return new String[]{String.format(Locale.US, "%.0f", v), "%"};
            case CELSIUS:
                if (tempF) return new String[]{String.format(Locale.US, "%.0f", v * 9.0 / 5.0 + 32.0), "\u00B0F"};
                return new String[]{String.format(Locale.US, "%.0f", v), "\u00B0C"};
            case MHZ:
                if (v >= 1000) return new String[]{String.format(Locale.US, "%.2f", v / 1000.0), "GHz"};
                return new String[]{String.format(Locale.US, "%.0f", v), "MHz"};
            case BYTES:
                return scaled(v, 1024, new String[]{"B", "KB", "MB", "GB", "TB"}, "");
            case BYTES_PS:
                if (rateBits) return scaled(v * 8, 1000, new String[]{"b", "Kb", "Mb", "Gb", "Tb"}, "/s");
                return scaled(v, 1024, new String[]{"B", "KB", "MB", "GB", "TB"}, "/s");
            case RPM:
                return new String[]{String.format(Locale.US, "%.0f", v), "RPM"};
            case VOLT:
                return new String[]{String.format(Locale.US, "%.2f", v), "V"};
            case WATT:
                return new String[]{String.format(Locale.US, "%.1f", v), "W"};
            case SECONDS:
                return new String[]{duration(v), ""};
            default:
                return new String[]{String.format(Locale.US, "%.2f", v), ""};
        }
    }

    private static String[] scaled(double b, double base, String[] units, String suffix) {
        int i = 0;
        while (b >= base && i < units.length - 1) {
            b /= base;
            i++;
        }
        String f = (i == 0 || b >= 100) ? "%.0f" : "%.1f";
        return new String[]{String.format(Locale.US, f, b), units[i] + suffix};
    }

    static String bytes(double b, String suffix) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (b >= 1024 && i < units.length - 1) {
            b /= 1024;
            i++;
        }
        String f = (i == 0 || b >= 100) ? "%.0f %s%s" : "%.1f %s%s";
        return String.format(Locale.US, f, b, units[i], suffix);
    }

    static String bits(double b, String suffix) {
        String[] units = {"b", "Kb", "Mb", "Gb", "Tb"};
        int i = 0;
        while (b >= 1000 && i < units.length - 1) {
            b /= 1000;
            i++;
        }
        String f = (i == 0 || b >= 100) ? "%.0f %s%s" : "%.1f %s%s";
        return String.format(Locale.US, f, b, units[i], suffix);
    }

    static String duration(double sec) {
        long s = (long) sec;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        return m + "m " + (s % 60) + "s";
    }
}
