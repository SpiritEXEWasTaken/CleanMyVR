package com.cleanmyvr.launcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * App store that installs and updates APKs straight from code-hosting releases, like Obtainium:
 * GitHub, GitLab (gitlab.com or self-hosted) and Gitea/Forgejo (e.g. Codeberg).
 */
final class Store {

    static final class Source {
        String url;          // repo page, e.g. https://github.com/owner/repo
        String name;
        String description;
        String assetHint;    // prefer APKs whose file name contains this (e.g. "oculusvr")
        String pkg;          // package name once known
        boolean builtIn;
        String directUrl;    // a "latest APK" link instead of a code repository (e.g. SideQuest)
    }

    static final class Release {
        String tag, assetName, assetUrl;
        long size;
        long downloads = -1;   // all releases' APK downloads together (GitHub, Gitea); -1 = unknown
        int stars = -1;        // -1 = unknown
        String license;        // e.g. "GPL-3.0", null = unknown
    }

    private static final long INFO_TTL = 24L * 3600 * 1000;

    /** Stars and license for a repository, cached for a day (GitHub allows 60 anonymous requests an hour). */
    private void addRepoInfo(Repo r, Release rel) {
        String key = "info:" + r.host + "/" + r.path;
        try {
            JSONObject cached = sp.contains(key) ? new JSONObject(sp.getString(key, "{}")) : null;
            if (cached == null || System.currentTimeMillis() - cached.optLong("at") > INFO_TTL) {
                JSONObject o, out = new JSONObject();
                switch (r.kind) {
                    case "github": {
                        o = Http.getObject("https://api.github.com/repos/" + r.path);
                        out.put("stars", o.optInt("stargazers_count", -1));
                        JSONObject lic = o.optJSONObject("license");
                        String id = lic != null ? lic.optString("spdx_id", "") : "";
                        if (!id.isEmpty() && !id.equals("NOASSERTION")) out.put("license", id);
                        break;
                    }
                    case "gitlab":
                        o = Http.getObject("https://" + r.host + "/api/v4/projects/" + URLEncoder.encode(r.path, "UTF-8") + "?license=true");
                        out.put("stars", o.optInt("star_count", -1));
                        if (o.optJSONObject("license") != null) out.put("license", o.getJSONObject("license").optString("nickname", o.getJSONObject("license").optString("key", "")));
                        break;
                    default: {
                        o = Http.getObject("https://" + r.host + "/api/v1/repos/" + r.path);
                        out.put("stars", o.optInt("stars_count", -1));
                        JSONArray lics = o.optJSONArray("licenses");
                        if (lics != null && lics.length() > 0) out.put("license", lics.getString(0));
                    }
                }
                out.put("at", System.currentTimeMillis());
                sp.edit().putString(key, out.toString()).apply();
                cached = out;
            }
            rel.stars = cached.optInt("stars", -1);
            String lic = cached.optString("license", "");
            rel.license = lic.isEmpty() ? null : lic;
        } catch (Exception ignored) { }   // the card just shows less
    }

    /**
     * Where the recommended list comes from. Bundled in the app (assets/catalog.json); when
     * CATALOG_URL is set (e.g. to the GitHub repo's store/catalog.json) a fresh copy is fetched at
     * most once a day, so new picks show up without updating the launcher.
     */
    static final String CATALOG_URL =
            "https://raw.githubusercontent.com/SpiritEXEWasTaken/launcher-appstore/main/catalog.json";
    private static final long CATALOG_TTL = 24L * 3600 * 1000;

    List<Source> defaults() {
        List<Source> l = new ArrayList<>();
        JSONObject cat = null;
        File remote = new File(ctx.getFilesDir(), "catalog.json");
        try {
            if (remote.exists()) cat = new JSONObject(new String(java.nio.file.Files.readAllBytes(remote.toPath()), "UTF-8"));
        } catch (Exception ignored) { }
        if (cat == null) {
            try (InputStream in = ctx.getAssets().open("catalog.json")) {
                cat = new JSONObject(new String(Http.read(in), "UTF-8"));
            } catch (Exception e) {
                return l;
            }
        }
        JSONArray apps = cat.optJSONArray("apps");
        if (apps == null) return l;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject o = apps.optJSONObject(i);
            if (o == null || o.optString("url").isEmpty()) continue;
            Source s = src(o.optString("url"), o.optString("name", repoName(o.optString("url"))),
                    o.optString("description", ""), emptyToNull(o.optString("hint", "")), emptyToNull(o.optString("pkg", "")));
            s.directUrl = emptyToNull(o.optString("direct", ""));
            l.add(s);
        }
        return l;
    }

    /** Refresh the remote catalog if one is configured (background thread). */
    void refreshCatalog() { refreshCatalog(false); }

    /** force: pull to refresh on the Store page fetches the list now instead of waiting a day. */
    void refreshCatalog(boolean force) {
        if (CATALOG_URL.isEmpty()) return;
        File remote = new File(ctx.getFilesDir(), "catalog.json");
        if (!force && remote.exists() && System.currentTimeMillis() - remote.lastModified() < CATALOG_TTL) return;
        try {
            String body = Http.getText(CATALOG_URL);
            if (new JSONObject(body).optJSONArray("apps") == null) return;   // don't replace with junk
            try (OutputStream out = new java.io.FileOutputStream(remote)) { out.write(body.getBytes("UTF-8")); }
        } catch (Exception ignored) { }
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    private static Source src(String url, String name, String desc, String hint, String pkg) {
        Source s = new Source();
        s.url = url;
        s.name = name;
        s.description = desc;
        s.assetHint = hint;
        s.pkg = pkg;
        s.builtIn = true;
        return s;
    }

    private final Context ctx;
    private final SharedPreferences sp;

    Store(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("store", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ sources

    List<Source> userSources() {
        List<Source> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("sources", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Source s = new Source();
                s.url = o.getString("url");
                s.name = o.optString("name", repoName(s.url));
                s.description = o.optString("description", "");
                s.assetHint = o.optString("hint", null);
                if (s.assetHint != null && s.assetHint.isEmpty()) s.assetHint = null;
                String direct = o.optString("direct", "");
                if (!direct.isEmpty()) s.directUrl = direct;
                out.add(s);
            }
        } catch (Exception ignored) { }
        for (Source s : out) if (s.pkg == null) s.pkg = installedPkg(s.url);
        return out;
    }

    List<Source> allSources() {
        List<Source> l = new ArrayList<>(userSources());
        for (Source d : defaults()) {
            boolean dup = false;
            for (Source u : l) if (normalize(u.url).equals(normalize(d.url))) dup = true;
            if (dup) continue;
            String known = installedPkg(d.url);
            if (known != null) d.pkg = known;
            l.add(d);
        }
        return l;
    }

    void addSource(String url, String hint) throws Exception {
        String n = normalize(url);
        parse(n);   // throws if it isn't a repo URL we understand
        JSONArray a = new JSONArray(sp.getString("sources", "[]"));
        for (int i = 0; i < a.length(); i++) if (normalize(a.getJSONObject(i).getString("url")).equals(n)) return;
        JSONObject o = new JSONObject();
        o.put("url", n);
        o.put("name", repoName(n));
        o.put("description", fetchDescription(n));
        if (hint != null && !hint.trim().isEmpty()) o.put("hint", hint.trim());
        a.put(o);
        sp.edit().putString("sources", a.toString()).apply();
    }

    /**
     * Does this link lead straight to an APK (possibly through redirects)? Checks headers only.
     * Background thread.
     */
    static boolean isDirectApk(String url) {
        String u = url.trim();
        if (!u.startsWith("http")) u = "https://" + u;
        try {
            for (int hop = 0; hop < 6; hop++) {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(15_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("User-Agent", "CleanMyVR-Launcher");
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                String type = c.getContentType();
                String disp = c.getHeaderField("Content-Disposition");
                c.disconnect();
                if (code >= 300 && code < 400 && loc != null) {
                    u = new java.net.URL(new java.net.URL(u), loc).toString();
                    if (u.toLowerCase(Locale.ROOT).split("\\?")[0].endsWith(".apk")) return true;
                    continue;
                }
                if (code != 200) return false;
                String path = u.toLowerCase(Locale.ROOT).split("\\?")[0];
                return path.endsWith(".apk")
                        || (type != null && type.contains("android.package-archive"))
                        || (disp != null && disp.toLowerCase(Locale.ROOT).contains(".apk"));
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Add a "latest APK" link (not a code repository). */
    void addDirectSource(String url, String name) throws Exception {
        String u = url.trim();
        if (!u.startsWith("http")) u = "https://" + u;
        JSONArray a = new JSONArray(sp.getString("sources", "[]"));
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).getString("url").equals(u)) return;
        JSONObject o = new JSONObject();
        o.put("url", u);
        o.put("direct", u);
        o.put("name", name == null || name.trim().isEmpty() ? Uri.parse(u).getHost() : name.trim());
        o.put("description", "Direct download link");
        a.put(o);
        sp.edit().putString("sources", a.toString()).apply();
    }

    void removeSource(String url) {
        try {
            JSONArray a = new JSONArray(sp.getString("sources", "[]")), b = new JSONArray();
            for (int i = 0; i < a.length(); i++)
                if (!normalize(a.getJSONObject(i).getString("url")).equals(normalize(url))) b.put(a.getJSONObject(i));
            sp.edit().putString("sources", b.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ install state

    /** Package and release tag we last installed for a source. */
    String installedPkg(String url) { return sp.getString("pkg:" + normalize(url), null); }
    String installedTag(String url) { return sp.getString("tag:" + normalize(url), null); }

    void rememberPending(String pkg, String url, String tag) {
        sp.edit().putString("pending:" + pkg, normalize(url) + "\n" + tag).apply();
    }

    /** Called when Android reports a successful install. */
    void installed(String pkg) {
        String p = sp.getString("pending:" + pkg, null);
        if (p == null) return;
        String[] parts = p.split("\n", 2);
        sp.edit().putString("pkg:" + parts[0], pkg).putString("tag:" + parts[0], parts.length > 1 ? parts[1] : "")
                .remove("pending:" + pkg).apply();
    }

    /** "installed", "update", or "none" for a source given its latest release. */
    String status(Source s, Release r) {
        if (s.pkg == null || !isInstalled(s.pkg)) return "none";
        if (r == null) return "installed";
        String tag = installedTag(s.url);
        if (s.directUrl != null && (tag == null || tag.isEmpty())) {
            // Installed some other way: the direct link has no version number to compare, so take what's
            // there now as current and offer updates when the link starts pointing at a new file.
            sp.edit().putString("pkg:" + normalize(s.url), s.pkg).putString("tag:" + normalize(s.url), r.tag).apply();
            return "installed";
        }
        if (tag != null && !tag.isEmpty()) return tag.equals(r.tag) ? "installed" : "update";
        String v = installedVersion(s.pkg);
        return v != null && digits(v).startsWith(digits(r.tag)) ? "installed" : "update";
    }

    boolean isInstalled(String pkg) {
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    String installedVersion(String pkg) {
        try { return ctx.getPackageManager().getPackageInfo(pkg, 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return null; }
    }

    private static String digits(String v) {
        String s = v.replaceAll("^[^0-9]*", "");
        return s.replaceAll("[^0-9.].*$", "");
    }

    // ------------------------------------------------------------------ releases

    private static final class Repo {
        String kind, host, path;   // kind: github | gitlab | gitea
    }

    static String normalize(String url) {
        String u = url.trim();
        if (!u.startsWith("http")) u = "https://" + u;
        if (u.contains("?")) return u;   // direct links with query strings: keep as-is
        u = u.replaceAll("\\.git$", "").replaceAll("/+$", "");
        u = u.replaceAll("/(releases|tags|-/releases|-/tags)(/.*)?$", "");
        return u;
    }

    private static Repo parse(String url) throws Exception {
        Uri u = Uri.parse(normalize(url));
        String host = u.getHost(), path = u.getPath();
        if (host == null || path == null || (path.split("/").length < 3 && !host.contains("f-droid.org")))
            throw new Exception("That doesn't look like a repository link (expected host/owner/repo)");
        Repo r = new Repo();
        r.host = host.toLowerCase(Locale.ROOT);
        r.path = path.replaceAll("^/+", "");
        if (r.host.equals("f-droid.org") || r.host.equals("www.f-droid.org")) {
            r.kind = "fdroid";
            java.util.List<String> seg = u.getPathSegments();
            int i = seg.indexOf("packages");
            if (i < 0 || i + 1 >= seg.size()) throw new Exception("Expected an f-droid.org/packages/<app> link");
            r.path = seg.get(i + 1);
            return r;
        }
        if (r.host.equals("github.com") || r.host.equals("www.github.com")) {
            r.kind = "github";
            String[] p = r.path.split("/");
            r.path = p[0] + "/" + p[1];
        } else if (r.host.contains("gitlab")) {
            r.kind = "gitlab";
        } else {
            r.kind = "gitea";   // Codeberg, Forgejo, Gitea
            String[] p = r.path.split("/");
            r.path = p[0] + "/" + p[1];
        }
        return r;
    }

    static String repoName(String url) {
        String[] p = normalize(url).split("/");
        return p[p.length - 1];
    }

    /** A direct "latest APK" link: see where it redirects (no download) to tell versions apart. */
    private static Release latestDirect(Source s) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(s.directUrl).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("HEAD");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "CleanMyVR-Launcher");
        try {
            int code = c.getResponseCode();
            Release r = new Release();
            String loc = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) {
                r.assetUrl = loc;
                Uri u = Uri.parse(loc);
                java.util.List<String> seg = u.getPathSegments();
                r.assetName = seg.isEmpty() ? "app.apk" : seg.get(seg.size() - 1);
                // e.g. .../file/4756484/app-release.apk -> "file 4756484"
                r.tag = seg.size() >= 2 ? "file " + seg.get(seg.size() - 2) : loc;
            } else if (code == 200) {
                r.assetUrl = s.directUrl;
                r.assetName = "app.apk";
                String etag = c.getHeaderField("ETag");
                r.tag = etag != null ? etag : String.valueOf(c.getHeaderFieldLong("Content-Length", 0));
            } else {
                throw new Exception("Download link returned HTTP " + code);
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** F-Droid's suggested version of a package, straight from f-droid.org. */
    private static Release latestFdroid(String pkg) throws Exception {
        JSONObject o = Http.getObject("https://f-droid.org/api/v1/packages/" + pkg);
        long code = o.getLong("suggestedVersionCode");
        String name = String.valueOf(code);
        JSONArray pk = o.optJSONArray("packages");
        if (pk != null) for (int i = 0; i < pk.length(); i++)
            if (pk.getJSONObject(i).optLong("versionCode") == code) name = pk.getJSONObject(i).optString("versionName", name);
        Release r = new Release();
        r.tag = name;
        r.assetName = pkg + "_" + code + ".apk";
        r.assetUrl = "https://f-droid.org/repo/" + r.assetName;
        return r;
    }

    String fetchDescription(String url) {
        try {
            Repo r = parse(url);
            JSONObject o;
            switch (r.kind) {
                case "fdroid": return "From F-Droid";
                case "github": o = Http.getObject("https://api.github.com/repos/" + r.path); break;
                case "gitlab": o = Http.getObject("https://" + r.host + "/api/v4/projects/" + URLEncoder.encode(r.path, "UTF-8")); break;
                default: o = Http.getObject("https://" + r.host + "/api/v1/repos/" + r.path);
            }
            String d = o.optString("description", "");
            return d.equals("null") ? "" : d;
        } catch (Exception e) {
            return "";
        }
    }

    /** Newest release that has an APK (stable preferred). */
    Release latest(Source s) throws Exception {
        if (s.directUrl != null) return latestDirect(s);
        Repo r = parse(s.url);
        if (r.kind.equals("fdroid")) return latestFdroid(r.path);
        List<JSONObject> releases = new ArrayList<>();
        switch (r.kind) {
            case "github": {
                JSONArray a = Http.getArray("https://api.github.com/repos/" + r.path + "/releases?per_page=15");
                for (int i = 0; i < a.length(); i++) releases.add(a.getJSONObject(i));
                break;
            }
            case "gitlab": {
                JSONArray a = Http.getArray("https://" + r.host + "/api/v4/projects/"
                        + URLEncoder.encode(r.path, "UTF-8") + "/releases?per_page=15");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject rel = a.getJSONObject(i), norm = new JSONObject();
                    norm.put("tag_name", rel.optString("tag_name"));
                    JSONArray assets = new JSONArray();
                    JSONArray links = rel.optJSONObject("assets") != null ? rel.getJSONObject("assets").optJSONArray("links") : null;
                    if (links != null) for (int j = 0; j < links.length(); j++) {
                        JSONObject l = links.getJSONObject(j), as = new JSONObject();
                        as.put("name", l.optString("name"));
                        as.put("browser_download_url", l.optString("direct_asset_url", l.optString("url")));
                        assets.put(as);
                    }
                    norm.put("assets", assets);
                    releases.add(norm);
                }
                break;
            }
            default: {
                JSONArray a = Http.getArray("https://" + r.host + "/api/v1/repos/" + r.path + "/releases?limit=15");
                for (int i = 0; i < a.length(); i++) releases.add(a.getJSONObject(i));
            }
        }
        long downloads = r.kind.equals("gitlab") ? -1 : 0;
        if (downloads == 0) for (JSONObject rel : releases) {
            JSONArray as = rel.optJSONArray("assets");
            if (as != null) for (int i = 0; i < as.length(); i++)
                if (as.getJSONObject(i).optString("name").toLowerCase(Locale.ROOT).endsWith(".apk"))
                    downloads += as.getJSONObject(i).optLong("download_count", 0);
        }
        Release chosen = null, fallback = null;
        for (JSONObject rel : releases) {
            if (rel.optBoolean("draft")) continue;
            Release pick = pickAsset(rel, s.assetHint);
            if (pick == null) continue;
            if (!rel.optBoolean("prerelease")) { chosen = pick; break; }
            if (fallback == null) fallback = pick;
        }
        if (chosen == null) chosen = fallback;
        if (chosen == null) throw new Exception("No release with an APK found");
        chosen.downloads = downloads;
        addRepoInfo(r, chosen);
        return chosen;
    }

    private static Release pickAsset(JSONObject rel, String hint) throws Exception {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) return null;
        Release best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
            int score = score(name, hint);
            if (score > bestScore) {
                bestScore = score;
                best = new Release();
                best.tag = rel.optString("tag_name");
                best.assetName = name;
                best.assetUrl = a.optString("browser_download_url");
                best.size = a.optLong("size", 0);
            }
        }
        return best;
    }

    /** Prefer Quest/arm64 builds, avoid other CPUs, debug and root builds. */
    static int score(String fileName, String hint) {
        String n = fileName.toLowerCase(Locale.ROOT);
        int s = 0;
        if (hint != null && n.contains(hint.toLowerCase(Locale.ROOT))) s += 20;
        if (n.contains("oculus") || n.contains("quest") || n.contains("meta")) s += 6;
        if (n.contains("arm64") || n.contains("aarch64") || n.contains("v8a")) s += 4;
        if (n.contains("universal")) s += 2;
        if (n.contains("x86") || n.contains("x64")) s -= 10;
        if (n.contains("armeabi") || n.contains("arm32") || n.contains("armv7") || n.contains("v7a")) s -= 6;
        if (n.contains("debug")) s -= 4;
        if (n.contains("nonroot")) s += 1; else if (n.contains("root")) s -= 3;
        if (n.contains("play")) s -= 2;
        for (String other : new String[]{"pico", "lynx", "hvr", "visionglass", "magicleap", "androidxr"})
            if (n.contains(other)) s -= 8;
        return s;
    }

    // ------------------------------------------------------------------ download + install

    /** Downloads and hands the APK to Android's installer. Returns the package name. Background thread. */
    String downloadAndInstall(Source s, Release r, Http.Progress p) throws Exception {
        File apk = new File(ctx.getExternalCacheDir(), "store/" + repoName(s.url) + ".apk");
        Http.download(r.assetUrl, apk, p);
        PackageManager pm = ctx.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
        if (info == null) throw new Exception("The downloaded file isn't a valid app");
        rememberPending(info.packageName, s.url, r.tag);
        return install(apk, info);
    }

    /** "Install an APK file…": copy the picked file (any app, not just store ones) and install it. */
    String installLocalApk(android.net.Uri uri) throws Exception {
        File apk = new File(ctx.getExternalCacheDir(), "store/picked.apk");
        apk.getParentFile().mkdirs();
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); OutputStream out = new java.io.FileOutputStream(apk)) {
            if (in == null) throw new Exception("Couldn't read that file");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        PackageInfo info = ctx.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        if (info == null) throw new Exception("That file isn't an Android app (.apk)");
        return install(apk, info);
    }

    private String install(File apk, PackageInfo info) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(info.packageName);
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent done = new Intent(ctx, InstallReceiver.class).putExtra("pkg", info.packageName);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, id, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pi.getIntentSender());
        }
        return info.packageName;
    }
}
