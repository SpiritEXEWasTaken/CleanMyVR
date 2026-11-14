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
    private long lastFire, lastGameMenu;
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
        SettingsBackup.restoreIfFresh(this);
        new Thread(() -> SettingsBackup.save(this), "settings-backup").start();
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
        if (Log.isLoggable(TAG, Log.VERBOSE))
            Log.v(TAG, "event " + AccessibilityEvent.eventTypeToString(e.getEventType()) + " " + p + " / " + c
                    + (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED ? " windows=" + describeWindows() : ""));
        if (stopPkg != null && p.toString().equals("com.android.settings")) { pressForceStop(); return; }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || c == null) return;
        String pkg = p.toString(), cls = c.toString();
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "window " + pkg + " / " + cls);

        // The Meta button opens Navigator: as the Library at an empty Home, but only as Meta's system bar
        // when a panel is open or inside a VR game. Either one means "Meta button pressed".
        if (isMetaLibrary(pkg, cls) || isSystemBar(pkg, cls)) { onLibrary(cls, null); return; }
        // Inside a VR game Navigator's first window is sometimes just a ViewGroup from the Library app.
        if (pkg.equals("com.oculus.panelapp.library") || pkg.equals("com.oculus.systemux")) {
            String game = vrAppInFront();
            if (game != null) onLibrary(cls, game);
        }
    }

    static boolean isSystemBar(String pkg, String cls) {
        return (pkg.equals("com.oculus.panelapp.library") || pkg.equals("com.oculus.systemux")) && cls.endsWith("SystemBarActivity");
    }

    private String describeWindows() {
        StringBuilder b = new StringBuilder();
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                b.append('[').append(r != null ? r.getPackageName() : w.getTitle()).append(w.isFocused() ? " focused" : "")
                        .append(w.isActive() ? " active" : "").append(']');
            }
        } catch (Exception ignored) { }
        return b.toString();
    }

    private void onLibrary(String cls, String knownGame) {

        Prefs prefs = new Prefs(this);
        if (prefs.takeoverPaused()) return;
        if (prefs.consumeSkip()) {
            // One opening sends a few events (Library, system bar): let all of them through, then take over again.
            prefs.pauseTakeover(3000);
            Log.i(TAG, "Letting Meta's menu open this once");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastFire < 1200) return;      // one press can send Library + system bar events
        // Navigator closing behind the in-game menu sends Library events too: don't let them toggle it shut.
        if (now - lastGameMenu < 2500) return;
        lastFire = now;

        // Inside a VR game the Meta button opens Navigator: show our in-game menu instead (unless turned off).
        String game = knownGame != null ? knownGame : vrAppInFront();
        if (game != null && !new Prefs(this).inGameMenu()) {
            Log.i(TAG, "Navigator opened over " + game + ": in-game menu is off, leaving it to Meta");
            return;
        }

        // Close Meta's Library/Navigator (it has focus right now).
        performGlobalAction(GLOBAL_ACTION_BACK);

        if (MainActivity.wasInFront(now)) {
            Log.i(TAG, "Meta button pressed while launcher showing: closing launcher (and Navigator)");
            // Back/Home don't close Navigator, but an app coming to the front does: bring the launcher forward
            // for a moment (that dismisses Navigator), and let it close itself right after.
            main.postDelayed(() -> startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_CLOSE, true)), 80);
            return;
        }

        if (game != null) lastGameMenu = now;
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
        s.stopMisses = 0;
        s.stopDeadline = SystemClock.elapsedRealtime() + 8000;
        s.stopDone = () -> onDone.accept(true);
        s.main.postDelayed(() -> {
            if (pkg.equals(s.stopPkg)) {               // never found the button: leave the page for the user
                Log.w(TAG, "Force stop: button not found for " + pkg + ", leaving App info open");
                s.stopPkg = null;
                onDone.accept(false);
            }
        }, 8000);
        boolean opened;
        if (c instanceof android.app.Activity) {
            // In the launcher's own task: bringing the launcher back then closes App info with it.
            try {
                c.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + pkg)).setPackage("com.android.settings"));
                opened = true;
            } catch (Exception e) {
                opened = AppRepo.openAppInfo(c, pkg);
            }
        } else {
            opened = AppRepo.openAppInfo(c, pkg);
        }
        if (!opened) { s.stopPkg = null; return false; }
        Log.i(TAG, "Force stop: opened App info for " + pkg);
        s.main.postDelayed(s.stopPoll, 500);
        return true;
    }

    private int stopMisses;

    private void pressForceStop() {
        if (SystemClock.elapsedRealtime() > stopDeadline) { stopPkg = null; return; }
        List<AccessibilityNodeInfo> roots = settingsRoots();
        if (roots.isEmpty()) return;
        if (!stopClicked) {
            // Only press anything on the App info page of the app the user chose (its name is on the page).
            boolean ours = false;
            AccessibilityNodeInfo button = null;
            for (AccessibilityNodeInfo r : roots) {
                if (containsText(r, stopLabel) || containsText(r, stopPkg)) ours = true;
                if (button == null) button = findText(r, "Force stop");
            }
            if (!ours || button == null) {
                if (++stopMisses == 10 && Log.isLoggable(TAG, Log.VERBOSE))
                    for (AccessibilityNodeInfo r : roots) Log.v(TAG, "Force stop: page text = " + allText(r));
                return;
            }
            if (!button.isEnabled() || !clickable(button).isEnabled()) { finishStop("already stopped"); return; }
            if (clickable(button).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                stopClicked = true;
                Log.i(TAG, "Force stop: pressed Force stop");
            }
        } else {
            for (AccessibilityNodeInfo r : roots) {
                AccessibilityNodeInfo ok = findText(r, "OK");
                if (ok != null && clickable(ok).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { finishStop("stopped"); return; }
            }
        }
    }

    /** Every Settings window (the confirm dialog is its own window). */
    private List<AccessibilityNodeInfo> settingsRoots() {
        List<AccessibilityNodeInfo> out = new java.util.ArrayList<>();
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && r.getPackageName().toString().equals("com.android.settings")) out.add(r);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private boolean navigatorFocused() {
        String p = focusedPackage();
        return "com.oculus.panelapp.library".equals(p) || "com.oculus.systemux".equals(p);
    }

    private String focusedPackage() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (w.isFocused() && r != null && r.getPackageName() != null) return r.getPackageName().toString();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean settingsFocused() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (w.isFocused() && r != null && "com.android.settings".contentEquals(r.getPackageName())) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String nodeText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        return t != null ? t.toString().trim() : "";
    }

    /** Walks the tree ourselves: findAccessibilityNodeInfosByText misses text in Compose screens. */
    private static AccessibilityNodeInfo findText(AccessibilityNodeInfo n, String text) {
        if (n == null) return null;
        if (nodeText(n).equalsIgnoreCase(text)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findText(n.getChild(i), text);
            if (hit != null) return hit;
        }
        return null;
    }

    private static boolean containsText(AccessibilityNodeInfo n, String text) {
        if (n == null || text == null || text.isEmpty()) return false;
        if (nodeText(n).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT))) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (containsText(n.getChild(i), text)) return true;
        return false;
    }

    private static String allText(AccessibilityNodeInfo n) {
        StringBuilder b = new StringBuilder();
        collect(n, b, 0);
        return b.toString();
    }

    private static void collect(AccessibilityNodeInfo n, StringBuilder b, int depth) {
        if (n == null || depth > 40) return;
        String t = nodeText(n);
        if (!t.isEmpty()) b.append('[').append(t).append(n.isClickable() ? "*" : "").append(']');
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), b, depth + 1);
    }

    private final Runnable stopPoll = new Runnable() {
        @Override public void run() {
            if (stopPkg == null) return;
            pressForceStop();
            if (stopPkg != null) main.postDelayed(this, 300);
        }
    };

    private void finishStop(String how) {
        Log.i(TAG, "Force stop: " + stopPkg + " " + how);
        Runnable done = stopDone;
        stopPkg = null;
        stopDone = null;
        main.postDelayed(() -> closeSettings(done, 0), 500);
    }

    /**
     * Close the App info page we opened. A single Back right after OK gets eaten by the closing dialog, so:
     * wait for the dialog to go, press the page's own "Navigate up" arrow (or Back), and check it's gone.
     */
    private void closeSettings(Runnable done, int attempt) {
        List<AccessibilityNodeInfo> roots = settingsRoots();
        if (roots.isEmpty() || attempt >= 6) {
            Log.i(TAG, "Force stop: " + (roots.isEmpty() ? "App info closed" : "App info didn't close") + " after " + attempt + " tries");
            if (done != null) main.postDelayed(done, 200);
            return;
        }
        boolean dialog = false;
        for (AccessibilityNodeInfo r : roots) if (findText(r, "OK") != null || findText(r, "Cancel") != null) dialog = true;
        if (!dialog) {
            AccessibilityNodeInfo up = null;
            for (AccessibilityNodeInfo r : roots) if (up == null) up = findText(r, "Navigate up");
            if ((up == null || !clickable(up).performAction(AccessibilityNodeInfo.ACTION_CLICK)) && settingsFocused())
                performGlobalAction(GLOBAL_ACTION_BACK);   // only when Back would reach Settings, not the launcher
        }
        main.postDelayed(() -> closeSettings(done, attempt + 1), 400);
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
