package com.cleanmyvr.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The "Store" page: install and update apps from GitHub / GitLab / Codeberg releases. */
final class StorePage {
    private static final long RELEASE_TTL = 30 * 60 * 1000L;
    private static final Map<String, Store.Release> releases = new ConcurrentHashMap<>();
    private static final Map<String, Long> fetchedAt = new ConcurrentHashMap<>();
    private static final Map<String, String> errors = new ConcurrentHashMap<>();
    private static final Map<String, String> progress = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> fetching = new ConcurrentHashMap<>();

    private final MainActivity m;
    private final Store store;

    StorePage(MainActivity m) {
        this.m = m;
        store = new Store(m);
    }

    /** Install finished or was cancelled: drop the "Installing…" labels. */
    static void clearInstalling() {
        for (Map.Entry<String, String> e : new ArrayList<>(progress.entrySet()))
            if (e.getValue().startsWith("Installing")) progress.remove(e.getKey());
    }

    /** Number of apps with an update available (for the Store tab label). */
    int updates() {
        int n = 0;
        for (Store.Source s : store.allSources()) if ("update".equals(store.status(s, releases.get(key(s))))) n++;
        return n;
    }

    private static boolean catalogChecked;

    void render(LinearLayout content, String query) {
        if (!catalogChecked) {
            catalogChecked = true;
            m.net.execute(() -> {
                store.refreshCatalog();
                m.ui.post(m::renderIfStore);
            });
        }
        content.removeAllViews();

        LinearLayout bar = new LinearLayout(m);
        bar.setPadding(0, 0, 0, m.dp(12));
        TextView back = m.pill("←  Library", false);
        back.setOnClickListener(v -> m.setStoreMode(false));
        bar.addView(back, lp(0));
        TextView add = m.pill("+  Add source", false);
        add.setOnClickListener(v -> addSource());
        bar.addView(add, lp(m.dp(10)));
        TextView check = m.pill("↻  Check for updates", false);
        check.setOnClickListener(v -> refreshAll());
        bar.addView(check, lp(m.dp(10)));
        content.addView(bar);

        if (!m.getPackageManager().canRequestPackageInstalls()) {
            LinearLayout warn = card();
            TextView t = m.text("The launcher isn't allowed to install apps yet.", 15, MainActivity.TEXT, false);
            warn.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            TextView allow = m.pill("Allow", true);
            allow.setOnClickListener(v -> askInstallPermission());
            warn.addView(allow);
            content.addView(warn, cardLp());
        }

        String q = query.toLowerCase(Locale.ROOT);
        List<Store.Source> mine = new ArrayList<>(), recommended = new ArrayList<>();
        for (Store.Source s : store.allSources()) {
            if (!q.isEmpty() && !(s.name + " " + s.url + " " + s.description).toLowerCase(Locale.ROOT).contains(q)) continue;
            (s.builtIn ? recommended : mine).add(s);
            ensureRelease(s);
        }
        if (!mine.isEmpty()) {
            m.section("Your sources");
            for (Store.Source s : mine) content.addView(sourceCard(s), cardLp());
        }
        m.section("Recommended for Quest");
        if (recommended.isEmpty()) {
            content.addView(m.text("Nothing matches.", 15, MainActivity.MUTED, false));
        }
        for (Store.Source s : recommended) content.addView(sourceCard(s), cardLp());
        TextView foot = m.text("Apps install straight from each project's official releases. Add any GitHub, GitLab or "
                + "Codeberg repository with “Add source”; updates show up here.", 13, MainActivity.MUTED, false);
        foot.setPadding(0, m.dp(16), 0, 0);
        content.addView(foot);
    }

    /** Re-check every app's latest release (and re-read the list). */
    void refreshAll() { fetchedAt.clear(); errors.clear(); m.render(); }

    private static String key(Store.Source s) { return Store.normalize(s.url); }

    private void ensureRelease(Store.Source s) {
        String k = key(s);
        Long at = fetchedAt.get(k);
        if (fetching.containsKey(k) || (at != null && System.currentTimeMillis() - at < RELEASE_TTL)) return;
        fetching.put(k, true);
        m.net.execute(() -> {
            try {
                releases.put(k, store.latest(s));
                errors.remove(k);
            } catch (Exception e) {
                errors.put(k, e.getMessage() == null ? "Couldn't check" : e.getMessage());
            }
            fetchedAt.put(k, System.currentTimeMillis());
            fetching.remove(k);
            m.ui.post(m::renderIfStore);
        });
    }

    private View sourceCard(Store.Source s) {
        String k = key(s);
        Store.Release r = releases.get(k);
        String status = store.status(s, r);
        PackageManager pm = m.getPackageManager();

        LinearLayout card = card();
        ImageView icon = new ImageView(m);
        Drawable d = null;
        if (s.pkg != null && store.isInstalled(s.pkg)) {
            try { d = pm.getApplicationIcon(s.pkg); } catch (Exception ignored) { }
        }
        if (d != null) {
            icon.setImageDrawable(d);
            card.addView(icon, new LinearLayout.LayoutParams(m.dp(48), m.dp(48)));
        } else {
            TextView letter = m.text(s.name.substring(0, 1).toUpperCase(Locale.ROOT), 22, MainActivity.TEXT, true);
            letter.setGravity(Gravity.CENTER);
            letter.setBackground(MainActivity.round(MainActivity.SURFACE_HI, m.dp(12)));
            card.addView(letter, new LinearLayout.LayoutParams(m.dp(48), m.dp(48)));
        }

        LinearLayout texts = new LinearLayout(m);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(m.dp(16), 0, m.dp(12), 0);
        texts.addView(m.text(s.name, 17, MainActivity.TEXT, true));
        if (s.description != null && !s.description.isEmpty()) {
            TextView desc = m.text(s.description, 13, MainActivity.MUTED, false);
            desc.setMaxLines(2);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(desc);
        }
        StringBuilder meta = new StringBuilder(Uri.parse(s.url).getHost() + Uri.parse(s.url).getPath());
        if (r != null) meta.append("  ·  latest ").append(r.tag);
        String installedV = s.pkg != null ? store.installedVersion(s.pkg) : null;
        if (installedV != null) meta.append("  ·  installed ").append(installedV);
        String err = errors.get(k);
        if (err != null && r == null) meta.append("  ·  ").append(err);
        TextView metaT = m.text(meta.toString(), 12, MainActivity.MUTED, false);
        metaT.setSingleLine(true);
        metaT.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(metaT);
        String stats = stats(r);
        if (!stats.isEmpty()) {
            TextView st = m.text(stats, 12, MainActivity.MUTED, false);
            st.setSingleLine(true);
            texts.addView(st);
        }
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        String prog = progress.get(k);
        TextView btn;
        if (prog != null) {
            btn = m.pill(prog, false);
        } else if (r == null && err == null) {
            btn = m.pill("Checking…", false);
        } else if (r == null) {
            btn = m.pill("Retry", false);
            btn.setOnClickListener(v -> { fetchedAt.remove(k); errors.remove(k); m.render(); });
        } else if (status.equals("update")) {
            btn = m.pill("Update", true);
            btn.setOnClickListener(v -> install(s, r));
        } else if (status.equals("installed")) {
            btn = m.pill("Open", false);
            btn.setOnClickListener(v -> {
                Intent i = pm.getLaunchIntentForPackage(s.pkg);
                if (i == null) {
                    i = new Intent(Intent.ACTION_MAIN).addCategory(AppRepo.VR_CATEGORY).setPackage(s.pkg);
                }
                try { m.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { m.toast("Couldn't open it"); }
            });
        } else {
            btn = m.pill("Install", true);
            btn.setOnClickListener(v -> install(s, r));
        }
        card.addView(btn);

        card.setOnLongClickListener(v -> { sourceMenu(v, s, r); return true; });
        return card;
    }

    private void sourceMenu(View anchor, Store.Source s, Store.Release r) {
        PopupMenu pm = new PopupMenu(m, anchor);
        if (r != null && s.pkg != null && store.isInstalled(s.pkg))
            pm.getMenu().add("Reinstall " + r.tag).setOnMenuItemClickListener(i -> { install(s, r); return true; });
        if (s.pkg != null && store.isInstalled(s.pkg))
            pm.getMenu().add("Uninstall").setOnMenuItemClickListener(i -> {
                m.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + s.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            });
        if (r != null)
            pm.getMenu().add("File: " + r.assetName).setEnabled(false);
        if (!s.builtIn)
            pm.getMenu().add("Remove source").setOnMenuItemClickListener(i -> { store.removeSource(s.url); m.render(); return true; });
        pm.show();
    }

    private void install(Store.Source s, Store.Release r) {
        if (!m.getPackageManager().canRequestPackageInstalls()) { askInstallPermission(); return; }
        String k = key(s);
        if (progress.containsKey(k)) return;
        progress.put(k, "Starting…");
        m.render();
        m.net.execute(() -> {
            final int[] lastPct = {-1};
            try {
                store.downloadAndInstall(s, r, (done, total) -> {
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct == lastPct[0]) return;
                    lastPct[0] = pct;
                    progress.put(k, pct >= 0 ? "Downloading " + pct + "%" : "Downloading " + (done >> 20) + " MB");
                    m.ui.post(m::renderIfStore);
                });
                progress.put(k, "Installing…");
            } catch (Exception e) {
                progress.remove(k);
                String msg = e.getMessage() == null ? "Download failed" : e.getMessage();
                m.ui.post(() -> m.toast(msg));
            }
            m.ui.post(m::renderIfStore);
        });
    }

    private void addSource() {
        m.promptText("Add a source", "Paste a GitHub, GitLab or Codeberg repository link (github.com/owner/app), "
                        + "or a direct link that always downloads the latest APK.",
                "https://", url -> {
                    if (url.trim().isEmpty() || url.trim().equals("https://")) return;
                    m.toast("Checking link…");
                    m.net.execute(() -> {
                        boolean direct = Store.isDirectApk(url);
                        m.ui.post(() -> {
                            if (direct) addDirect(url); else addRepo(url);
                        });
                    });
                });
    }

    private void addDirect(String url) {
        m.promptText("Name this app", "That link downloads an APK directly. What should it be called in the store?",
                "", name -> m.net.execute(() -> {
                    try {
                        store.addDirectSource(url, name);
                        m.ui.post(m::render);
                    } catch (Exception e) {
                        m.ui.post(() -> m.toast("Couldn't add that link"));
                    }
                }));
    }

    private void addRepo(String url) {
        m.promptText("APK name filter (optional)",
                "Only if the project ships several APKs: part of the right file name, like arm64 or oculus. "
                        + "Leave empty to pick automatically.", "", hint -> {
                    m.toast("Adding…");
                    m.net.execute(() -> {
                        try {
                            store.addSource(url, hint);
                            m.ui.post(m::render);
                        } catch (Exception e) {
                            String msg = e.getMessage() == null ? "Couldn't add that link" : e.getMessage();
                            m.ui.post(() -> m.toast(msg));
                        }
                    });
                });
    }

    private void askInstallPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + m.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            m.startActivity(new Intent(i).setComponent(new ComponentName("com.android.settings",
                    "com.android.settings.Settings$ManageExternalSourcesActivity")));
        } catch (Exception e) {
            try { m.startActivity(i); } catch (Exception e2) { m.toast("Allow it with: adb shell appops set com.cleanmyvr.launcher REQUEST_INSTALL_PACKAGES allow"); }
        }
    }

    /** "★ 12  ·  ⬇ 3.4k downloads  ·  GPL-3.0" (whatever is known). */
    private static String stats(Store.Release r) {
        if (r == null) return "";
        List<String> parts = new ArrayList<>();
        if (r.stars >= 0) parts.add("★ " + shortNumber(r.stars));
        if (r.downloads >= 0) parts.add("⬇ " + shortNumber(r.downloads) + (r.downloads == 1 ? " download" : " downloads"));
        if (r.license != null) parts.add(r.license);
        return String.join("  ·  ", parts);
    }

    private static String shortNumber(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.ROOT, n < 10_000 ? "%.1fk" : "%.0fk", n / 1000.0);
        return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(m);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(m.dp(16), m.dp(14), m.dp(16), m.dp(14));
        c.setBackground(MainActivity.round(MainActivity.SURFACE, m.dp(14)));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = m.dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams lp(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = left;
        return p;
    }
}
