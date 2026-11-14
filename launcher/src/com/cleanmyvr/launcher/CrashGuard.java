package com.cleanmyvr.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

/**
 * Brings the launcher back after a crash. A crash kills the Library takeover too, and Android doesn't restart a
 * crashed accessibility service by itself. The receiver below runs in its own process (":guard", see the
 * manifest), so it survives the launcher's crash: it switches the takeover off and on again, which makes Android
 * start it fresh, and reopens the launcher if it was on screen. Needs WRITE_SECURE_SETTINGS, which only
 * a computer can grant (CleanMyVR does: `pm grant com.cleanmyvr.launcher android.permission.WRITE_SECURE_SETTINGS`).
 */
public class CrashGuard extends BroadcastReceiver {
    private static final String TAG = "CleanMyVrLauncher";
    static final String EXTRA_REOPEN = "reopen";
    private static volatile boolean installed;

    /** Call once per process start (launcher window and takeover service). */
    static void install(Context c) {
        if (installed) return;
        installed = true;
        Context app = c.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                Log.e(TAG, "launcher crashed; asking the guard to bring it back", e);
                app.sendBroadcast(new Intent(app, CrashGuard.class).putExtra(EXTRA_REOPEN, MainActivity.isVisible()));
            } catch (Throwable ignored) { }
            // End the process ourselves. Android's own handling shows a "keeps stopping" dialog after repeat
            // crashes and keeps the dead process around until it's dismissed, which leaves the takeover stuck.
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        PendingResult pending = goAsync();
        boolean reopen = intent.getBooleanExtra(EXTRA_REOPEN, false);
        Handler h = new Handler(Looper.getMainLooper());
        // Wait until Android has finished cleaning up the crashed process: restarting the takeover earlier leaves
        // it stuck "binding" (only a force-stop from a computer clears that).
        h.postDelayed(() -> {
            boolean ok = restartTakeover(c, h, () -> {
                if (reopen && recentReopenAllowed(c)) {
                    try {
                        c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) { }
                }
                pending.finish();
            });
            if (!ok) pending.finish();
        }, 5000);
    }

    /** Switch our accessibility service off and on (Android then starts it fresh). False without permission. */
    static boolean restartTakeover(Context c, Handler h, Runnable then) {
        String svc = new ComponentName(c, LibraryInterceptor.class).flattenToString();
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder others = new StringBuilder();
            if (cur != null) for (String s : cur.split(":"))
                if (!s.isEmpty() && !s.startsWith(c.getPackageName() + "/")) others.append(others.length() > 0 ? ":" : "").append(s);
            String without = others.toString();
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
            h.postDelayed(() -> {
                try {
                    Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            without.isEmpty() ? svc : without + ":" + svc);
                    Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    Log.i(TAG, "guard: Library takeover restarted");
                } catch (Exception e) {
                    Log.w(TAG, "guard: couldn't restart the takeover: " + e);
                }
                if (then != null) then.run();
            }, 600);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "guard: no WRITE_SECURE_SETTINGS (CleanMyVR grants it); can't restart the takeover");
            return false;
        }
    }

    /** Is the takeover switched on but not actually running (e.g. it crashed earlier)? */
    static boolean takeoverStuck(Context c) {
        String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur == null || !cur.contains(c.getPackageName() + "/")) return false;      // switched off on purpose
        AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        for (android.accessibilityservice.AccessibilityServiceInfo i :
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            if (i.getResolveInfo() != null && c.getPackageName().equals(i.getResolveInfo().serviceInfo.packageName)) return false;
        return true;
    }

    /** Don't loop if the launcher crashes right when it opens: reopen at most once a minute. */
    private static boolean recentReopenAllowed(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("guard", Context.MODE_PRIVATE);
        long now = SystemClock.elapsedRealtime(), last = sp.getLong("last_reopen", -60_000);
        if (now - last < 60_000 && now > last) return false;
        sp.edit().putLong("last_reopen", now).commit();
        return true;
    }
}
