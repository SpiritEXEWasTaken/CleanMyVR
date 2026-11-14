package com.cleanmyvr.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Keeps a copy of the launcher's settings (favorites, renames, categories, game/app choices, store sources)
 * next to the covers, in Android/data/com.cleanmyvr.launcher/files/settings-backup.json. If the launcher
 * starts with empty settings and that file is there (e.g. the files were copied back after a reinstall),
 * it restores them. Covers in the same folder come along the same way.
 */
final class SettingsBackup {
    private static final String[] FILES = {"launcher", "store"};
    private static final String NAME = "settings-backup.json";

    private SettingsBackup() { }

    static void save(Context c) {
        try {
            JSONObject root = new JSONObject();
            for (String f : FILES) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, ?> e : c.getSharedPreferences(f, Context.MODE_PRIVATE).getAll().entrySet()) {
                    Object v = e.getValue();
                    JSONObject item = new JSONObject();
                    if (v instanceof Set) {
                        item.put("t", "set").put("v", new JSONArray((Set<?>) v));
                    } else if (v != null) {
                        item.put("t", v.getClass().getSimpleName()).put("v", v);
                    }
                    o.put(e.getKey(), item);
                }
                root.put(f, o);
            }
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            File tmp = new File(dir, NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(new File(dir, NAME))) tmp.delete();
        } catch (Exception e) {
            Log.w("CleanMyVrLauncher", "settings backup failed: " + e);
        }
    }

    /** Restore into a fresh install (no settings yet), or when a "restore-now" file sits next to the backup. */
    static void restoreIfFresh(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, NAME), marker = new File(dir, "restore-now");
            boolean fresh = c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getAll().isEmpty();
            if (!f.exists() || !(fresh || marker.exists())) return;
            marker.delete();
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (String name : FILES) {
                JSONObject o = root.optJSONObject(name);
                if (o == null) continue;
                SharedPreferences.Editor ed = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject item = o.getJSONObject(k);
                    String t = item.optString("t");
                    switch (t) {
                        case "set": {
                            Set<String> s = new HashSet<>();
                            JSONArray a = item.getJSONArray("v");
                            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                            ed.putStringSet(k, s);
                            break;
                        }
                        case "Boolean": ed.putBoolean(k, item.getBoolean("v")); break;
                        case "Integer": ed.putInt(k, item.getInt("v")); break;
                        case "Long": ed.putLong(k, item.getLong("v")); break;
                        case "Float": ed.putFloat(k, (float) item.getDouble("v")); break;
                        default: ed.putString(k, item.optString("v")); break;
                    }
                }
                ed.commit();
            }
            Log.i("CleanMyVrLauncher", "restored settings from " + NAME);
        } catch (Exception e) {
            Log.w("CleanMyVrLauncher", "settings restore failed: " + e);
        }
    }
}
