package com.cleanmyvr.launcher;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Store names, categories and banners for Quest apps, read from the public, community-maintained
 * MetaMetadata database (https://github.com/threethan/MetaMetadata, by threethan, updated daily).
 * Many games have a placeholder Android name (e.g. "Deadeye" for Animal Rivals) because Meta's own
 * UI shows the store name instead; this gives the launcher the same names and artwork.
 *
 * Fetched per package on demand (this sends the package name to GitHub), cached for a week.
 * Background threads only.
 */
final class MetaInfo {
    private static final String URL = "https://raw.githubusercontent.com/threethan/MetaMetadata/main/data/common/%s.json";
    private static final long MAX_AGE = 7L * 24 * 3600 * 1000;

    final String name, category, landscape;

    private MetaInfo(JSONObject o) {
        name = o.optString("name", null);
        category = o.optString("category", null);
        landscape = o.optString("landscape", null);
    }

    private static File jsonFile(Context c, String pkg) {
        File d = new File(c.getFilesDir(), "meta");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, pkg + ".json");
    }

    /** Store banner downloaded for this app (used when you haven't set your own cover). */
    static File bannerFile(Context c, String pkg) {
        File d = new File(c.getExternalFilesDir(null), "meta-covers");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, pkg + ".img");
    }

    /** Cached info only (no network). null if unknown or the app isn't in the database. */
    static MetaInfo cached(Context c, String pkg) {
        File f = jsonFile(c, pkg);
        if (!f.exists() || f.length() == 0) return null;
        try {
            return new MetaInfo(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /** True when there's no cached answer (including "not in the database") or it's older than a week. */
    static boolean needsFetch(Context c, String pkg) {
        File f = jsonFile(c, pkg);
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE;
    }

    /**
     * Fetch (or refresh) one app's info and its banner. Returns true if something new was saved.
     * Apps not in the database get an empty cache file, so we don't ask again for a week.
     */
    static boolean fetch(Context c, String pkg) {
        File f = jsonFile(c, pkg);
        String body;
        try {
            body = Http.getText(String.format(URL, pkg));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) touchEmpty(f);
            return false;
        }
        MetaInfo info;
        try {
            info = new MetaInfo(new JSONObject(body));
        } catch (Exception e) {
            touchEmpty(f);
            return false;
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        // Banner URLs are signed and expire, so download the image now while the link is fresh.
        if (info.landscape != null && !info.landscape.isEmpty()) {
            try {
                Http.download(info.landscape, bannerFile(c, pkg), null);
            } catch (Exception ignored) { }
        }
        return true;
    }

    private static void touchEmpty(File f) {
        try (OutputStream ignored = new FileOutputStream(f)) {
            // empty = "looked it up, not in the database"
        } catch (Exception ignored2) { }
    }
}
