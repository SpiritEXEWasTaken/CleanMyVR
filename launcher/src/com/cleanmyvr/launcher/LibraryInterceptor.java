package com.cleanmyvr.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Replaces Meta's Library: when the Library/Navigator panel opens, open our launcher instead.
 * Pressing the Meta button while the launcher is showing closes it (toggle).
 * Only looks at the package/class name of windows that open.
 */
public class LibraryInterceptor extends AccessibilityService {
    private static final String TAG = "CleanMyVrLauncher";
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastFire;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { AppRepo.invalidate(); }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f, Context.RECEIVER_NOT_EXPORTED);
        // Warm the app list now, so the first Meta-button press opens the launcher instantly.
        new Thread(() -> MainActivity.preload(this), "launcher-preload").start();
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(packages); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName(), c = e.getClassName();
        if (p == null || c == null) return;
        String pkg = p.toString(), cls = c.toString();
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "window " + pkg + " / " + cls);

        if (!isMetaLibrary(pkg, cls)) return;

        if (new Prefs(this).takeoverPaused()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFire < 600) return;
        lastFire = now;

        // Inside a VR game the Meta button opens Navigator to quit/resume it: leave that alone.
        String game = vrAppInFront();
        if (game != null) {
            Log.i(TAG, "Navigator opened over " + game + ": leaving it to Meta");
            return;
        }

        // Close Meta's Library/Navigator (it has focus right now).
        performGlobalAction(GLOBAL_ACTION_BACK);

        if (MainActivity.wasInFront(now)) {
            Log.i(TAG, "Meta button pressed while launcher showing: closing launcher");
            main.postDelayed(MainActivity::closeIfOpen, 150);
            return;
        }

        Log.i(TAG, "Meta Library opened (" + cls + "), opening launcher instead");
        main.postDelayed(() -> startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_FROM_LIBRARY, true)), 80);
    }

    static boolean isMetaLibrary(String pkg, String cls) {
        return (pkg.equals("com.oculus.systemux") || pkg.equals("com.oculus.panelapp.library"))
                && cls.endsWith("LibraryActivity");
    }

    /**
     * The VR (immersive) app that's currently running in front, if any, from Android's usage events:
     * the most recently resumed non-shell app, if it's a VR app and hasn't stopped since.
     * (Immersive apps don't show up as windows to accessibility services, so this is the reliable way.)
     */
    private String vrAppInFront() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - 12L * 3600 * 1000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            java.util.Map<String, Integer> state = new java.util.HashMap<>();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                String p = e.getPackageName();
                int t = e.getEventType();
                if (t == UsageEvents.Event.ACTIVITY_RESUMED) {
                    state.put(p, t);
                    if (!isShellChrome(p) && !p.equals(getPackageName())) last = p;
                } else if (t == UsageEvents.Event.ACTIVITY_STOPPED) {
                    state.put(p, t);
                }
            }
            if (last == null || Integer.valueOf(UsageEvents.Event.ACTIVITY_STOPPED).equals(state.get(last))) return null;
            return isVrApp(last) ? last : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A VR game/app: has a VR activity and isn't part of the system (Meta's Browser has a VR mode too). */
    private boolean isVrApp(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return false;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(AppRepo.VR_CATEGORY).setPackage(pkg);
        return !getPackageManager().queryIntentActivities(q, PackageManager.MATCH_ALL).isEmpty();
    }

    /** Shell/system overlays that appear around app windows without replacing them. */
    private static boolean isShellChrome(String pkg) {
        return pkg.equals("com.oculus.vrshell") || pkg.equals("com.meta.systemui")
                || pkg.equals("com.oculus.panelapp.library") || pkg.equals("com.oculus.systemux")
                || pkg.equals("android") || pkg.equals("com.oculus.panelapp.settings")
                || pkg.startsWith("com.oculus.os.") || pkg.equals("com.oculus.guardian");
    }

    @Override
    public void onInterrupt() { }
}
