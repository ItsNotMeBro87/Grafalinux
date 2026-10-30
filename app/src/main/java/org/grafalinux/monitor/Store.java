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

    Store(Context c) {
        sp = c.getSharedPreferences("grafalinux", Context.MODE_PRIVATE);
        loadSettings();
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

    // ---- dashboard ----------------------------------------------------
    Model.Dashboard dashboard() {
        String s = sp.getString("dashboard", null);
        if (s != null) {
            try {
                return Model.Dashboard.fromJson(new JSONObject(s));
            } catch (JSONException e) {
                // fall through to default
            }
        }
        Model.Dashboard d = new Model.Dashboard();
        d.fit();
        return d;
    }

    void saveDashboard(Model.Dashboard d) {
        try {
            sp.edit().putString("dashboard", d.toJson().toString()).apply();
        } catch (JSONException e) {
            // ignore
        }
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
        e.apply();
    }
}
