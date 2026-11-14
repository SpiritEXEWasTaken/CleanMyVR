package com.cleanmyvr.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Favorites, custom categories, hidden apps and launch history, stored in SharedPreferences. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    // ---- favorites / hidden ----
    Set<String> favorites() { return new HashSet<>(sp.getStringSet("favorites", new HashSet<>())); }
    boolean isFavorite(String pkg) { return favorites().contains(pkg); }
    void toggleFavorite(String pkg) { toggle("favorites", pkg); }

    Set<String> hidden() { return new HashSet<>(sp.getStringSet("hidden", new HashSet<>())); }
    void toggleHidden(String pkg) { toggle("hidden", pkg); }

    private void toggle(String key, String pkg) {
        Set<String> s = new HashSet<>(sp.getStringSet(key, new HashSet<>()));
        if (!s.remove(pkg)) s.add(pkg);
        sp.edit().putStringSet(key, s).apply();
    }

    // ---- custom categories (ordered) ----
    LinkedHashMap<String, List<String>> categories() {
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("categories", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                List<String> apps = new ArrayList<>();
                JSONArray a = o.getJSONArray("apps");
                for (int j = 0; j < a.length(); j++) apps.add(a.getString(j));
                out.put(o.getString("name"), apps);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    void saveCategories(LinkedHashMap<String, List<String>> cats) {
        JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, List<String>> e : cats.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("name", e.getKey());
                o.put("apps", new JSONArray(e.getValue()));
                arr.put(o);
            }
        } catch (JSONException ignored) { }
        sp.edit().putString("categories", arr.toString()).apply();
    }

    void addCategory(String name) {
        LinkedHashMap<String, List<String>> c = categories();
        if (!c.containsKey(name)) c.put(name, new ArrayList<>());
        saveCategories(c);
    }

    void renameCategory(String from, String to) {
        LinkedHashMap<String, List<String>> c = categories(), n = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : c.entrySet())
            n.put(e.getKey().equals(from) ? to : e.getKey(), e.getValue());
        saveCategories(n);
    }

    void deleteCategory(String name) {
        LinkedHashMap<String, List<String>> c = categories();
        c.remove(name);
        saveCategories(c);
    }

    void toggleInCategory(String cat, String pkg) {
        LinkedHashMap<String, List<String>> c = categories();
        List<String> l = c.get(cat);
        if (l == null) return;
        if (!l.remove(pkg)) l.add(pkg);
        saveCategories(c);
    }

    // ---- own launch history (fallback when usage access isn't granted) ----
    long lastLaunch(String pkg) {
        try { return new JSONObject(sp.getString("launches", "{}")).optLong(pkg, 0); }
        catch (JSONException e) { return 0; }
    }

    void recordLaunch(String pkg) {
        try {
            JSONObject o = new JSONObject(sp.getString("launches", "{}"));
            o.put(pkg, System.currentTimeMillis());
            sp.edit().putString("launches", o.toString()).apply();
        } catch (JSONException ignored) { }
    }

    // ---- "is this a game?" (default: Meta Store apps are games, sideloaded/system apps aren't) ----
    boolean isGame(String pkg, boolean byDefault) {
        if (sp.getStringSet("game_yes", new HashSet<>()).contains(pkg)) return true;
        if (sp.getStringSet("game_no", new HashSet<>()).contains(pkg)) return false;
        return byDefault;
    }

    void setGame(String pkg, boolean game, boolean byDefault) {
        Set<String> yes = new HashSet<>(sp.getStringSet("game_yes", new HashSet<>()));
        Set<String> no = new HashSet<>(sp.getStringSet("game_no", new HashSet<>()));
        yes.remove(pkg);
        no.remove(pkg);
        if (game != byDefault) (game ? yes : no).add(pkg);
        sp.edit().putStringSet("game_yes", yes).putStringSet("game_no", no).apply();
    }

    // ---- wallpaper ----
    static final String WALL_DEFAULT = "default", WALL_SOLID = "solid", WALL_GRADIENT = "gradient", WALL_IMAGE = "image";
    String wallType() { return sp.getString("wall_type", WALL_DEFAULT); }
    int wallColor1() { return sp.getInt("wall_c1", 0xFF0E0F13); }
    int wallColor2() { return sp.getInt("wall_c2", 0xFF1C2541); }
    /** Index into GradientDrawable.Orientation.values(). */
    int wallDirection() { return sp.getInt("wall_dir", 0); }
    float wallDim() { return sp.getFloat("wall_dim", 0.35f); }
    void setWallpaper(String type, int c1, int c2, int dir) {
        sp.edit().putString("wall_type", type).putInt("wall_c1", c1).putInt("wall_c2", c2).putInt("wall_dir", dir).apply();
    }
    void setWallType(String type) { sp.edit().putString("wall_type", type).apply(); }
    void setWallDim(float d) { sp.edit().putFloat("wall_dim", d).apply(); }

    // ---- covers ----
    String sgdbKey() { return sp.getString("sgdb_key", ""); }
    void setSgdbKey(String k) { sp.edit().putString("sgdb_key", k.trim()).apply(); }
    /** Use the Android TV banner some apps ship when there's no custom cover. */
    boolean useAppBanners() { return sp.getBoolean("app_banners", true); }
    void setUseAppBanners(boolean b) { sp.edit().putBoolean("app_banners", b).apply(); }

    /** Record a definite answer (also used for "not sure" apps the user sorted). */
    void setGameExplicit(String pkg, boolean game) {
        Set<String> yes = new HashSet<>(sp.getStringSet("game_yes", new HashSet<>()));
        Set<String> no = new HashSet<>(sp.getStringSet("game_no", new HashSet<>()));
        yes.remove(pkg);
        no.remove(pkg);
        (game ? yes : no).add(pkg);
        sp.edit().putStringSet("game_yes", yes).putStringSet("game_no", no).apply();
    }

    /** null = you haven't said; true/false = your answer. */
    Boolean gameAnswer(String pkg) {
        if (sp.getStringSet("game_yes", new HashSet<>()).contains(pkg)) return true;
        if (sp.getStringSet("game_no", new HashSet<>()).contains(pkg)) return false;
        return null;
    }

    // ---- custom names (e.g. tell a private Rec Room server apart from the real one) ----
    String customLabel(String pkg) {
        try { return new JSONObject(sp.getString("labels", "{}")).optString(pkg, null); }
        catch (JSONException e) { return null; }
    }

    void setCustomLabel(String pkg, String label) {
        try {
            JSONObject o = new JSONObject(sp.getString("labels", "{}"));
            if (label == null || label.trim().isEmpty()) o.remove(pkg); else o.put(pkg, label.trim());
            sp.edit().putString("labels", o.toString()).apply();
        } catch (JSONException ignored) { }
    }

    // ---- which page is showing ----
    boolean storeMode() { return sp.getBoolean("store_mode", false); }
    void setStoreMode(boolean b) { sp.edit().putBoolean("store_mode", b).apply(); }

    // ---- store names / categories / banners from the MetaMetadata database ----
    boolean onlineInfo() { return sp.getBoolean("online_info", true); }
    void setOnlineInfo(boolean b) { sp.edit().putBoolean("online_info", b).apply(); }

    // ---- misc ----
    String tab() { return sp.getString("tab", MainActivity.TAB_ALL); }
    void setTab(String t) { sp.edit().putString("tab", t).apply(); }

    /** Android system apps (Settings, file pickers…) are hidden unless you turn this on. */
    boolean showSystem() { return sp.getBoolean("show_system", false); }
    void setShowSystem(boolean b) { sp.edit().putBoolean("show_system", b).apply(); }

    boolean showHidden() { return sp.getBoolean("show_hidden", false); }
    void setShowHidden(boolean b) { sp.edit().putBoolean("show_hidden", b).apply(); }

    /** Lets the user open Meta's Library on purpose without being bounced back. */
    void pauseTakeover(long ms) { sp.edit().putLong("pause_until", System.currentTimeMillis() + ms).apply(); }
    boolean takeoverPaused() { return System.currentTimeMillis() < sp.getLong("pause_until", 0); }

    /** Meta button inside a VR game opens the launcher's in-game menu (instead of Meta's Navigator). */
    boolean inGameMenu() { return sp.getBoolean("in_game_menu", true); }
    void setInGameMenu(boolean on) { sp.edit().putBoolean("in_game_menu", on).apply(); }
}
