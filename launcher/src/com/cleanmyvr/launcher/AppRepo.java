package com.cleanmyvr.launcher;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds every launchable app and classifies it (VR / 2D / sideloaded). */
final class AppRepo {
    static final String VR_CATEGORY = "com.oculus.intent.category.VR";
    private static final Set<String> STORE_INSTALLERS = new HashSet<>();
    static {
        STORE_INSTALLERS.add("com.oculus.ocms");     // Meta Horizon Store
        STORE_INSTALLERS.add("com.oculus.horizon");
    }

    static final class App {
        String pkg, label;
        Drawable icon, banner;
        boolean vr, sideloaded, system;
        int category = ApplicationInfo.CATEGORY_UNDEFINED;   // what the app says it is (game, video, ...)
        boolean gameFlag;                                     // android:isGame
        String storeName, storeCategory;                      // from MetaMetadata (null if unknown)
        long lastUsed;
    }

    // App-wide cache so the launcher opens instantly; refreshed in the background.
    private static volatile List<App> cached;
    private static volatile long cachedAt;
    private static volatile boolean dirty = true;

    /** Something was installed/removed/updated: next open reloads. */
    static void invalidate() { dirty = true; }

    static List<App> cached() { return cached; }

    /** Reload if the cache is missing, invalidated or older than a minute. */
    static boolean stale() { return cached == null || dirty || System.currentTimeMillis() - cachedAt > 60_000; }

    private final Context ctx;
    private final PackageManager pm;

    AppRepo(Context c) {
        ctx = c;
        pm = c.getPackageManager();
    }

    List<App> load(Prefs prefs) {
        Map<String, App> apps = new HashMap<>();
        Set<String> vr = new HashSet<>();
        for (ResolveInfo ri : query(VR_CATEGORY)) vr.add(ri.activityInfo.packageName);

        List<ResolveInfo> all = query(Intent.CATEGORY_LAUNCHER);
        all.addAll(query(VR_CATEGORY));
        Map<String, Long> usage = usage();

        for (ResolveInfo ri : all) {
            String pkg = ri.activityInfo.packageName;
            if (apps.containsKey(pkg) || pkg.equals(ctx.getPackageName())) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.icon = ri.loadIcon(pm);
            try { a.banner = pm.getApplicationBanner(ai); } catch (Exception ignored) { }
            a.vr = vr.contains(pkg);
            a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            a.sideloaded = !a.system && !STORE_INSTALLERS.contains(installer(pkg));
            a.category = ai.category;
            //noinspection deprecation
            a.gameFlag = (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
            Long u = usage.get(pkg);
            a.lastUsed = Math.max(u == null ? 0 : u, prefs.lastLaunch(pkg));
            if (prefs.onlineInfo()) applyMeta(ctx, a);
            apps.put(pkg, a);
        }
        List<App> out = new ArrayList<>(apps.values());
        out.sort((x, y) -> sortName(x).compareToIgnoreCase(sortName(y)));
        cached = out;
        cachedAt = System.currentTimeMillis();
        dirty = false;
        return out;
    }

    static String sortName(App a) { return a.storeName != null ? a.storeName : a.label; }

    /** Fill in the store name/category from the local MetaMetadata cache (no network). */
    static void applyMeta(Context c, App a) {
        MetaInfo m = MetaInfo.cached(c, a.pkg);
        a.storeName = m != null && m.name != null && !m.name.isEmpty() ? m.name : null;
        a.storeCategory = m != null && m.category != null && !m.category.isEmpty() ? m.category : null;
    }

    private List<ResolveInfo> query(String category) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        return new ArrayList<>(pm.queryIntentActivities(i, 0));
    }

    private String installer(String pkg) {
        try {
            String s = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    /** Last-used times from Android's usage stats (needs usage access; empty otherwise). */
    private Map<String, Long> usage() {
        Map<String, Long> m = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            Map<String, UsageStats> s = usm.queryAndAggregateUsageStats(now - 60L * 24 * 3600 * 1000, now);
            for (UsageStats u : s.values())
                if (u.getTotalTimeInForeground() > 0) m.put(u.getPackageName(), u.getLastTimeUsed());
        } catch (Exception ignored) { }
        return m;
    }

    boolean isInstalled(String pkg) {
        try { pm.getApplicationInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    String label(String pkg) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { return pkg; }
    }

    Drawable icon(String pkg) {
        try { return pm.getApplicationIcon(pkg); } catch (Exception e) { return pm.getDefaultActivityIcon(); }
    }

    boolean launch(String pkg) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY).setPackage(pkg);
            List<ResolveInfo> r = pm.queryIntentActivities(q, 0);
            if (r.isEmpty()) return false;
            i = q.setClassName(pkg, r.get(0).activityInfo.name);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (SecurityException e) {
            // Quest blocks some system screens (e.g. Android Settings) from other apps. Open the app's
            // Android "App info" page instead; its OPEN button is allowed to start it.
            return openAppInfo(ctx, pkg);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /** Android's own App info page (Quest otherwise shows Meta's). Returns false if it can't be shown. */
    static boolean openAppInfo(Context ctx, String pkg) {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + pkg))
                .setPackage("com.android.settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                ctx.startActivity(i.setPackage(null));   // whatever handles it (may be Meta's page)
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }
}
