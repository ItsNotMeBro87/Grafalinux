package org.grafalinux.monitor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Saves servers, the dashboard and settings on the phone. */
final class Store {
    private final SharedPreferences sp;
    final Model.Settings settings = new Model.Settings();
    final ArrayList<Model.Dashboard> dashboards = new ArrayList<Model.Dashboard>();
    String defaultId = "";

    Store(Context c) {
        sp = c.getSharedPreferences("grafalinux", Context.MODE_PRIVATE);
        loadSettings();
        loadDashboards();
    }

    // ---- servers ------------------------------------------------------
    List<Model.Server> servers() {
        ArrayList<Model.Server> out = new ArrayList<Model.Server>();
        try {
            JSONArray arr = new JSONArray(sp.getString("servers", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Model.Server.fromJson(o));
            }
        } catch (JSONException e) {
            // ignore: start with an empty list
        }
        return out;
    }

    void saveServers(List<Model.Server> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Model.Server s : list) arr.put(s.toJson());
            sp.edit().putString("servers", arr.toString()).apply();
        } catch (JSONException e) {
            // ignore
        }
    }

    // ---- dashboards ---------------------------------------------------
    private void loadDashboards() {
        String s = sp.getString("dashboards", null);
        if (s != null) {
            try {
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) dashboards.add(Model.Dashboard.fromJson(o));
                }
            } catch (JSONException e) {
                // ignore: fall back below
            }
        }
        if (dashboards.isEmpty()) {
            // first run, or upgrading from the single-dashboard version
            Model.Dashboard d = null;
            String old = sp.getString("dashboard", null);
            if (old != null) {
                try {
                    d = Model.Dashboard.fromJson(new JSONObject(old));
                } catch (JSONException e) {
                    d = null;
                }
            }
            if (d == null) {
                d = new Model.Dashboard();
                d.fit();
            }
            dashboards.add(d);
        }
        defaultId = sp.getString("defaultDash", "");
        if (find(defaultId) == null) defaultId = dashboards.get(0).id;
        saveDashboards();
    }

    Model.Dashboard find(String id) {
        for (Model.Dashboard d : dashboards) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    Model.Dashboard defaultDashboard() {
        Model.Dashboard d = find(defaultId);
        return d != null ? d : dashboards.get(0);
    }

    void saveDashboards() {
        try {
            JSONArray arr = new JSONArray();
            for (Model.Dashboard d : dashboards) arr.put(d.toJson());
            sp.edit().putString("dashboards", arr.toString()).putString("defaultDash", defaultId).apply();
        } catch (JSONException e) {
            // ignore
        }
    }

    /** Saves all dashboards (d is one of them). */
    void saveDashboard(Model.Dashboard d) {
        saveDashboards();
    }

    // ---- settings -----------------------------------------------------
    private void loadSettings() {
        Model.Settings s = settings;
        s.fontColor = sp.getInt("fontColor", Model.WHITE);
        s.labelColor = sp.getInt("labelColor", Model.WHITE);
        s.bgColor = sp.getInt("bgColor", Model.BLACK);
        s.thEnabled = sp.getBoolean("thEnabled", true);
        s.warnColor = sp.getInt("warnColor", 0xFFE5A50A);
        s.critColor = sp.getInt("critColor", 0xFFDA4453);
        for (int i = 0; i < Model.TH_KEYS.length; i++) {
            s.thWarn[i] = sp.getFloat("thw_" + Model.TH_KEYS[i], Model.TH_WARN_DEF[i]);
            s.thCrit[i] = sp.getFloat("thc_" + Model.TH_KEYS[i], Model.TH_CRIT_DEF[i]);
        }
        s.tempF = sp.getBoolean("tempF", false);
        s.intervalMs = sp.getInt("intervalMs", 1000);
        s.keepOn = sp.getBoolean("keepOn", false);
        s.orientation = sp.getInt("orientation", 0);
        s.textScale = sp.getInt("textScale", 100);
        s.showNames = sp.getBoolean("showNames", false);
        s.fullscreen = sp.getBoolean("fullscreen", false);
        s.menuMode = sp.getInt("menuMode", 1);
        s.bgMode = sp.getInt("bgMode", 0);
        s.bgGrad1 = sp.getInt("bgGrad1", 0xFF0F2027);
        s.bgGrad2 = sp.getInt("bgGrad2", 0xFF2C5364);
        s.bgGrad3 = sp.getInt("bgGrad3", 0xFF203A43);
        s.bgGrad3On = sp.getBoolean("bgGrad3On", false);
        s.bgGradDir = sp.getInt("bgGradDir", 0);
        s.bgDim = sp.getInt("bgDim", 30);
        s.rateBits = sp.getBoolean("rateBits", false);
        s.fanPercent = sp.getBoolean("fanPercent", false);
        s.innerPad = sp.getInt("innerPad", 2);
        s.iconColor = sp.getInt("iconColor", Model.WHITE);
        s.showIcons = sp.getBoolean("showIcons", true);
        s.thGradient = sp.getBoolean("thGradient", false);
        s.wakeMode = sp.getInt("wakeMode", 0);
        s.wakeInterval = sp.getInt("wakeInterval", 30);
        s.panelStyle = sp.getInt("panelStyle", 1);
    }

    void saveSettings() {
        Model.Settings s = settings;
        SharedPreferences.Editor e = sp.edit();
        e.putInt("fontColor", s.fontColor);
        e.putInt("labelColor", s.labelColor);
        e.putInt("bgColor", s.bgColor);
        e.putBoolean("thEnabled", s.thEnabled);
        e.putInt("warnColor", s.warnColor);
        e.putInt("critColor", s.critColor);
        for (int i = 0; i < Model.TH_KEYS.length; i++) {
            e.putFloat("thw_" + Model.TH_KEYS[i], s.thWarn[i]);
            e.putFloat("thc_" + Model.TH_KEYS[i], s.thCrit[i]);
        }
        e.putBoolean("tempF", s.tempF);
        e.putInt("intervalMs", s.intervalMs);
        e.putBoolean("keepOn", s.keepOn);
        e.putInt("orientation", s.orientation);
        e.putInt("textScale", s.textScale);
        e.putBoolean("showNames", s.showNames);
        e.putBoolean("fullscreen", s.fullscreen);
        e.putInt("menuMode", s.menuMode);
        e.putInt("bgMode", s.bgMode);
        e.putInt("bgGrad1", s.bgGrad1);
        e.putInt("bgGrad2", s.bgGrad2);
        e.putInt("bgGrad3", s.bgGrad3);
        e.putBoolean("bgGrad3On", s.bgGrad3On);
        e.putInt("bgGradDir", s.bgGradDir);
        e.putInt("bgDim", s.bgDim);
        e.putBoolean("rateBits", s.rateBits);
        e.putBoolean("fanPercent", s.fanPercent);
        e.putInt("innerPad", s.innerPad);
        e.putInt("iconColor", s.iconColor);
        e.putBoolean("showIcons", s.showIcons);
        e.putBoolean("thGradient", s.thGradient);
        e.putInt("wakeMode", s.wakeMode);
        e.putInt("wakeInterval", s.wakeInterval);
        e.putInt("panelStyle", s.panelStyle);
        e.apply();
    }
}
