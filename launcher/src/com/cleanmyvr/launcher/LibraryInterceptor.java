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
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/**
 * Replaces Meta's Library: when the Library/Navigator panel opens, open our launcher instead.
 * Pressing the Meta button while the launcher is showing closes it (toggle). Inside a VR game the launcher
 * opens with its in-game menu (Open another app / Restart / Close).
 * Only looks at the package/class name of windows that open, except for a few seconds after you press
 * Close/Restart App: then it reads Android's App info screen for that app to press Force stop for you.
 */
public class LibraryInterceptor extends AccessibilityService {
    private static final String TAG = "CleanMyVrLauncher";
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastFire;
    private static java.lang.ref.WeakReference<LibraryInterceptor> instance = new java.lang.ref.WeakReference<>(null);

    // A Force stop we're pressing for the user (see forceStop).
    private String stopPkg, stopLabel;
    private Runnable stopDone;
    private long stopDeadline;
    private boolean stopClicked;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { AppRepo.invalidate(); }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = new java.lang.ref.WeakReference<>(this);
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
        CharSequence p = e.getPackageName(), c = e.getClassName();
        if (p == null) return;
        if (stopPkg != null && p.toString().equals("com.android.settings")) { pressForceStop(); return; }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || c == null) return;
        String pkg = p.toString(), cls = c.toString();
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "window " + pkg + " / " + cls);

        if (!isMetaLibrary(pkg, cls)) return;

        if (new Prefs(this).takeoverPaused()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFire < 600) return;
        lastFire = now;

        // Inside a VR game the Meta button opens Navigator: show our in-game menu instead (unless turned off).
        String game = vrAppInFront();
        if (game != null && !new Prefs(this).inGameMenu()) {
            Log.i(TAG, "Navigator opened over " + game + ": in-game menu is off, leaving it to Meta");
            return;
        }

        // Close Meta's Library/Navigator (it has focus right now).
        performGlobalAction(GLOBAL_ACTION_BACK);

        if (MainActivity.wasInFront(now)) {
            Log.i(TAG, "Meta button pressed while launcher showing: closing launcher");
            main.postDelayed(MainActivity::closeIfOpen, 150);
            return;
        }

        Log.i(TAG, "Meta Library opened (" + cls + "), opening launcher instead" + (game != null ? " with in-game menu for " + game : ""));
        main.postDelayed(() -> startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_FROM_LIBRARY, true)
                .putExtra(MainActivity.EXTRA_GAME, game)), 80);
    }

    // ------------------------------------------------------------------ Close / Restart App

    /**
     * Force-stop an app the way a person would: open its Android App info page and press Force stop, then OK.
     * (Apps aren't allowed to stop other apps directly.) onDone gets true when it worked; false means the
     * App info page was left open for the user to finish.
     */
    static boolean forceStop(android.content.Context c, String pkg, String label, java.util.function.Consumer<Boolean> onDone) {
        LibraryInterceptor s = instance.get();
        if (s == null) return false;
        s.stopPkg = pkg;
        s.stopLabel = label;
        s.stopClicked = false;
        s.stopDeadline = SystemClock.elapsedRealtime() + 8000;
        s.stopDone = () -> onDone.accept(true);
        s.main.postDelayed(() -> {
            if (pkg.equals(s.stopPkg)) {               // never found the button: leave the page for the user
                Log.w(TAG, "Force stop: button not found for " + pkg + ", leaving App info open");
                s.stopPkg = null;
                onDone.accept(false);
            }
        }, 8000);
        if (!AppRepo.openAppInfo(c, pkg)) { s.stopPkg = null; return false; }
        Log.i(TAG, "Force stop: opened App info for " + pkg);
        return true;
    }

    private void pressForceStop() {
        if (SystemClock.elapsedRealtime() > stopDeadline) { stopPkg = null; return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!stopClicked) {
            // Only press anything on the App info page of the app the user chose (its name is on the page).
            if (stopLabel != null && root.findAccessibilityNodeInfosByText(stopLabel).isEmpty()
                    && root.findAccessibilityNodeInfosByText(stopPkg).isEmpty()) return;
            AccessibilityNodeInfo b = find(root, "com.android.settings:id/button3", "Force stop");
            if (b == null) return;
            if (!b.isEnabled()) { finishStop("already stopped"); return; }   // greyed out = not running
            if (clickable(b).performAction(AccessibilityNodeInfo.ACTION_CLICK)) stopClicked = true;
        } else {
            AccessibilityNodeInfo ok = find(root, "android:id/button1", "OK");
            if (ok != null && clickable(ok).performAction(AccessibilityNodeInfo.ACTION_CLICK)) finishStop("stopped");
        }
    }

    private void finishStop(String how) {
        Log.i(TAG, "Force stop: " + stopPkg + " " + how);
        Runnable done = stopDone;
        stopPkg = null;
        stopDone = null;
        main.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);   // close App info
            if (done != null) main.postDelayed(done, 400);
        }, 300);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String id, String text) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
        for (AccessibilityNodeInfo n : l) if (n.getText() == null || text.equalsIgnoreCase(n.getText().toString())) return n;
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text))
            if (n.getText() != null && text.equalsIgnoreCase(n.getText().toString().trim())) return n;
        return null;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo x = n; x != null; x = x.getParent()) if (x.isClickable()) return x;
        return n;
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
