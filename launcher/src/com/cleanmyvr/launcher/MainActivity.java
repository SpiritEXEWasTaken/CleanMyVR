package com.cleanmyvr.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String EXTRA_FROM_LIBRARY = "from_library";
    /** Package of the VR app that's running behind the launcher: shows the in-game menu. */
    static final String EXTRA_GAME = "game";
    /** Meta pressed while the launcher was showing: come forward (dismisses Navigator), then close. */
    static final String EXTRA_CLOSE = "close";
    static final String TAB_FAV = "fav", TAB_ALL = "all", TAB_VR = "vr", TAB_2D = "2d", TAB_SIDE = "side";
    private static final String CAT_PREFIX = "cat:";
    private static final int REQ_COVER = 1, REQ_WALLPAPER = 2, REQ_APK = 3;

    // Dark minimal palette
    static final int BG = 0xFF0E0F13, SURFACE = 0xD91A1D24, SURFACE_HI = 0xE6262A33,
            TEXT = 0xFFE7EAF0, MUTED = 0xFF8A90A0, ACCENT = 0xFF7C9CFF, STAR = 0xFFFFC857;

    private Prefs prefs;
    private AppRepo repo;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    // Shared across launcher windows so reopening is instant (filled in the background).
    private static final Map<String, Bitmap> coverCache = java.util.Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Integer> colorCache = java.util.Collections.synchronizedMap(new HashMap<>());

    private List<AppRepo.App> apps = new ArrayList<>();
    private String tab, query = "", pendingCoverPkg;

    private LinearLayout tabsRow, content;
    private ScrollView scroll;
    private EditText search;
    private ImageView wallpaperView;
    private View wallpaperScrim;
    final ExecutorService net = Executors.newFixedThreadPool(4);
    private StorePage storePage;
    private TextView libTitle, storeTitle;
    private HorizontalScrollView tabsScroll;
    private FrameLayout rootFrame;
    private View gameMenu, backToGame;
    private String gamePkg;
    private boolean stopAttempted;   // after Close/Restart, coming back means the game is gone (or being dealt with)
    private TextView gameStatus;

    /** The live launcher window, so the Meta button can close it (see LibraryInterceptor). */
    private static java.lang.ref.WeakReference<MainActivity> current = new java.lang.ref.WeakReference<>(null);

    static boolean isAlive() {
        MainActivity a = current.get();
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    private static volatile boolean resumed;
    private static volatile long pausedAt;

    /** Was the launcher what you were looking at when the Meta button was pressed? */
    static boolean wasInFront(long now) {
        // Navigator taking focus pauses us a moment before the accessibility event arrives.
        return isAlive() && (resumed || now - pausedAt < 1500);
    }

    @Override
    protected void onPause() {
        resumed = false;
        pausedAt = android.os.SystemClock.elapsedRealtime();
        bg.execute(() -> SettingsBackup.save(this));
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (gameMenu != null) finish();                // Back in the in-game menu = resume the game
        else if (prefs.storeMode()) setStoreMode(false);   // Back from the Store goes to the Library
        else super.onBackPressed();
    }

    static void closeIfOpen() {
        MainActivity a = current.get();
        if (a != null && !a.isFinishing()) a.finish();
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current.clear();
        bg.shutdown();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = new java.lang.ref.WeakReference<>(this);
        SettingsBackup.restoreIfFresh(this);
        prefs = new Prefs(this);
        repo = new AppRepo(this);
        tab = prefs.tab();
        storePage = new StorePage(this);
        getWindow().getDecorView().setBackgroundColor(BG);
        rootFrame = new FrameLayout(this);
        rootFrame.addView(buildShell(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);
        List<AppRepo.App> cached = AppRepo.cached();
        if (cached != null) {
            apps = cached;
            renderTabs();
            render();
        }
        showGameMenu(getIntent().getStringExtra(EXTRA_GAME));
        if (getIntent().getBooleanExtra(EXTRA_CLOSE, false)) ui.postDelayed(this::finish, 250);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (stopAttempted) { stopAttempted = false; showGameMenu(null); }
        if (AppRepo.stale()) {
            reload();
        } else {
            apps = AppRepo.cached();
            renderTabs();
            render();
        }
    }

    /** Fill the app list and image caches ahead of time (called by the background service). */
    static void preload(android.content.Context c) {
        try {
            List<AppRepo.App> l = new AppRepo(c).load(new Prefs(c));
            warmImages(c, l);
            fetchMeta(c, l);
        } catch (Exception ignored) { }
    }

    private static void warmImages(android.content.Context c, List<AppRepo.App> list) {
        File dir = new File(c.getExternalFilesDir(null), "covers");
        for (AppRepo.App a : list) {
            if (!coverCache.containsKey(a.pkg)) {
                File f = coverSource(c, a.pkg);
                coverCache.put(a.pkg, f.exists() ? decodeSampled(f, 640) : null);
            }
            if (!colorCache.containsKey(a.pkg)) colorCache.put(a.pkg, computeDominant(a));
        }
    }

    /** Your own cover if you set one, otherwise the store banner (when online info is on). */
    static File coverSource(android.content.Context c, String pkg) {
        File custom = new File(new File(c.getExternalFilesDir(null), "covers"), pkg + ".img");
        if (custom.exists() || !new Prefs(c).onlineInfo()) return custom;
        File store = MetaInfo.bannerFile(c, pkg);
        return store.exists() && store.length() > 0 ? store : custom;
    }

    private static final ExecutorService metaPool = Executors.newFixedThreadPool(3);
    private static final java.util.Set<String> metaInFlight = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Look up store names/banners for apps we don't know yet (background), then refresh. */
    static void fetchMeta(android.content.Context c, List<AppRepo.App> list) {
        Prefs p = new Prefs(c);
        if (!p.onlineInfo()) return;
        for (AppRepo.App a : list) {
            if (a.system || !MetaInfo.needsFetch(c, a.pkg) || !metaInFlight.add(a.pkg)) continue;
            metaPool.execute(() -> {
                try {
                    if (MetaInfo.fetch(c, a.pkg)) {
                        AppRepo.applyMeta(c, a);
                        coverCache.remove(a.pkg);
                        colorCache.remove(a.pkg);
                        MainActivity m = current.get();
                        if (m != null && !m.isFinishing()) m.ui.removeCallbacks(m.debouncedRender);
                        if (m != null && !m.isFinishing()) m.ui.postDelayed(m.debouncedRender, 400);
                    }
                } finally {
                    metaInFlight.remove(a.pkg);
                }
            });
        }
    }

    private final Runnable debouncedRender = () -> { renderTabs(); render(); };

    /** An install finished: refresh whatever is showing. */
    static void refreshIfOpen() {
        StorePage.clearInstalling();
        AppRepo.invalidate();
        MainActivity a = current.get();
        if (a != null && !a.isFinishing()) a.runOnUiThread(a::reload);
    }

    void renderIfStore() { if (prefs.storeMode()) render(); }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_CLOSE, false)) { ui.postDelayed(this::finish, 250); return; }
        setIntent(intent);
        showGameMenu(intent.getStringExtra(EXTRA_GAME));
        reload();
    }

    void reload() {
        bg.execute(() -> {
            List<AppRepo.App> loaded = repo.load(prefs);
            warmImages(this, loaded);
            ui.post(() -> { apps = loaded; renderTabs(); render(); });
            fetchMeta(this, loaded);
        });
    }

    // ------------------------------------------------------------------ layout

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(36), dp(28), dp(36), 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setGravity(Gravity.BOTTOM);
        libTitle = text("Library", 26, TEXT, true);
        storeTitle = text("Store", 26, MUTED, true);
        libTitle.setPadding(dp(4), dp(6), dp(14), dp(6));
        storeTitle.setPadding(dp(14), dp(6), dp(14), dp(6));
        libTitle.setOnClickListener(v -> setStoreMode(false));
        storeTitle.setOnClickListener(v -> setStoreMode(true));
        titles.addView(libTitle);
        titles.addView(storeTitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        search = new EditText(this);
        search.setHint("Search");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setPadding(dp(18), dp(10), dp(18), dp(10));
        search.setBackground(round(SURFACE, dp(22)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            public void onTextChanged(CharSequence s, int a, int c, int d) { }
            public void afterTextChanged(Editable e) { query = e.toString().trim(); render(); }
        });
        header.addView(search, new LinearLayout.LayoutParams(dp(420), -2));

        TextView more = pill("⋯", false);
        more.setTextSize(20);
        more.setOnClickListener(this::showMainMenu);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.leftMargin = dp(12);
        header.addView(more, mlp);
        root.addView(header);

        tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsRow = new LinearLayout(this);
        tabsRow.setPadding(0, dp(18), 0, dp(14));
        tabsScroll.addView(tabsRow);
        root.addView(tabsScroll);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(36));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol) render();
        });

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(wallpaperView, new FrameLayout.LayoutParams(-1, -1));
        wallpaperScrim = new View(this);
        frame.addView(wallpaperScrim, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        applyWallpaper();
        return frame;
    }

    // ------------------------------------------------------------------ wallpaper

    private File wallpaperFile() { return new File(getFilesDir(), "wallpaper.img"); }

    private void applyWallpaper() {
        String type = prefs.wallType();
        wallpaperView.setImageDrawable(null);
        wallpaperView.setBackground(null);
        wallpaperScrim.setBackgroundColor(0);
        if (Prefs.WALL_SOLID.equals(type)) {
            wallpaperView.setBackgroundColor(prefs.wallColor1());
        } else if (Prefs.WALL_GRADIENT.equals(type)) {
            GradientDrawable.Orientation[] o = GradientDrawable.Orientation.values();
            wallpaperView.setBackground(new GradientDrawable(o[Math.max(0, Math.min(o.length - 1, prefs.wallDirection()))],
                    new int[]{prefs.wallColor1(), prefs.wallColor2()}));
        } else if (Prefs.WALL_IMAGE.equals(type) && wallpaperFile().exists()) {
            bg.execute(() -> {
                Bitmap b = decodeSampled(wallpaperFile(), 2048);
                ui.post(() -> {
                    wallpaperView.setImageBitmap(b);
                    wallpaperScrim.setBackgroundColor(Color.argb((int) (prefs.wallDim() * 255), 0, 0, 0));
                });
            });
        } else {
            wallpaperView.setBackgroundColor(BG);
        }
    }

    private static Bitmap decodeSampled(File f, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (o.outWidth / (s * 2) >= maxW) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    private void wallpaperMenu() {
        String[] items = {"Default (dark)", "Solid color…", "Gradient…", "Image…", "Image dimming…"};
        new AlertDialog.Builder(this).setTitle("Wallpaper").setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    prefs.setWallType(Prefs.WALL_DEFAULT); applyWallpaper(); break;
                case 1:
                    pickColor("Background color", prefs.wallColor1(), c -> {
                        prefs.setWallpaper(Prefs.WALL_SOLID, c, prefs.wallColor2(), prefs.wallDirection());
                        applyWallpaper();
                    });
                    break;
                case 2:
                    pickColor("Gradient: first color", prefs.wallColor1(), c1 ->
                        pickColor("Gradient: second color", prefs.wallColor2(), c2 -> {
                            String[] dirs = {"Top → bottom", "Top-right → bottom-left", "Right → left",
                                    "Bottom-right → top-left", "Bottom → top", "Bottom-left → top-right",
                                    "Left → right", "Top-left → bottom-right"};
                            new AlertDialog.Builder(this).setTitle("Direction").setItems(dirs, (d2, dir) -> {
                                prefs.setWallpaper(Prefs.WALL_GRADIENT, c1, c2, dir);
                                applyWallpaper();
                            }).show();
                        }));
                    break;
                case 3:
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                    try { startActivityForResult(pick, REQ_WALLPAPER); } catch (Exception ex) { toast("No file picker available"); }
                    break;
                case 4:
                    String[] levels = {"None", "Light (20%)", "Medium (35%)", "Strong (50%)", "Very strong (65%)"};
                    float[] vals = {0f, 0.2f, 0.35f, 0.5f, 0.65f};
                    new AlertDialog.Builder(this).setTitle("Darken image so text stays readable").setItems(levels, (d2, i) -> {
                        prefs.setWallDim(vals[i]);
                        applyWallpaper();
                    }).show();
                    break;
            }
        }).show();
    }

    private interface ColorCallback { void got(int color); }

    private static final int[] SWATCHES = {
            0xFF0E0F13, 0xFF1C1F26, 0xFF2B2F38, 0xFF4A4F5C, 0xFF8A90A0, 0xFFE7EAF0,
            0xFF1C2541, 0xFF243B6B, 0xFF3A6EA5, 0xFF7C9CFF, 0xFF00A6A6, 0xFF0B6E4F,
            0xFF2D6A4F, 0xFF52B788, 0xFFFFC857, 0xFFF4A261, 0xFFE76F51, 0xFFD62828,
            0xFF9D0208, 0xFF6A040F, 0xFF7B2CBF, 0xFFC77DFF, 0xFFFF8FAB, 0xFF3D1A3A};

    private void pickColor(String title, int initial, ColorCallback cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        View preview = new View(this);
        preview.setBackground(round(initial, dp(10)));
        box.addView(preview, new LinearLayout.LayoutParams(-1, dp(44)));
        EditText hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setText(String.format("#%06X", initial & 0xFFFFFF));
        final int[] chosen = {initial};
        LinearLayout row = null;
        for (int i = 0; i < SWATCHES.length; i++) {
            if (i % 8 == 0) {
                row = new LinearLayout(this);
                row.setPadding(0, dp(8), 0, 0);
                box.addView(row);
            }
            int c = SWATCHES[i];
            View sw = new View(this);
            sw.setBackground(round(c, dp(8)));
            sw.setOnClickListener(v -> {
                chosen[0] = c;
                preview.setBackground(round(c, dp(10)));
                hex.setText(String.format("#%06X", c & 0xFFFFFF));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.rightMargin = dp(8);
            row.addView(sw, lp);
        }
        hex.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            public void onTextChanged(CharSequence s, int a, int c, int d) { }
            public void afterTextChanged(Editable e) {
                try {
                    int c = Color.parseColor(e.toString().trim().startsWith("#") ? e.toString().trim() : "#" + e.toString().trim());
                    chosen[0] = c | 0xFF000000;
                    preview.setBackground(round(chosen[0], dp(10)));
                } catch (IllegalArgumentException ignored) { }
            }
        });
        box.addView(hex);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("OK", (d, w) -> cb.got(chosen[0]))
                .setNegativeButton("Cancel", null).show();
    }

    void setStoreMode(boolean store) {
        prefs.setStoreMode(store);
        search.setText("");
        renderTabs();
        render();
        scroll.scrollTo(0, 0);
    }

    private void renderTabs() {
        boolean store = prefs.storeMode();
        libTitle.setTextColor(store ? MUTED : TEXT);
        storeTitle.setTextColor(store ? TEXT : MUTED);
        int n = storePage.updates();
        storeTitle.setText(n > 0 ? "Store · " + n : "Store");
        search.setHint(store ? "Search store" : "Search");
        tabsScroll.setVisibility(store ? View.GONE : View.VISIBLE);
        if (store) {
            tabsRow.removeAllViews();
            return;
        }
        tabsRow.removeAllViews();
        addTab("★  Favorites", TAB_FAV);
        addTab("All", TAB_ALL);
        addTab("Games", TAB_VR);
        addTab("Apps", TAB_2D);
        addTab("Sideloaded", TAB_SIDE);
        LinkedHashMap<String, List<String>> cats = prefs.categories();
        for (String c : cats.keySet()) addTab(c, CAT_PREFIX + c);
        if (tab.startsWith(CAT_PREFIX) && !cats.containsKey(tab.substring(CAT_PREFIX.length()))) {
            tab = TAB_ALL;
            renderTabs();
            return;
        }
        TextView add = pill("+  New category", false);
        add.setTextColor(MUTED);
        add.setOnClickListener(v -> promptName("New category", "", name -> {
            prefs.addCategory(name);
            selectTab(CAT_PREFIX + name);
        }));
        tabsRow.addView(add, tabLp());
    }

    private void addTab(String label, String key) {
        TextView t = pill(label, key.equals(tab));
        t.setOnClickListener(v -> selectTab(key));
        if (key.startsWith(CAT_PREFIX)) t.setOnLongClickListener(v -> { categoryMenu(v, key.substring(CAT_PREFIX.length())); return true; });
        tabsRow.addView(t, tabLp());
    }

    private void selectTab(String key) {
        tab = key;
        prefs.setTab(key);
        renderTabs();
        render();
        scroll.scrollTo(0, 0);
    }

    void render() {
        if (content == null) return;
        int width = scroll.getWidth();
        if (width == 0) { scroll.post(this::render); return; }
        if (prefs.storeMode()) {
            storePage.render(content, query);
            return;
        }
        content.removeAllViews();

        int gap = dp(16);
        int cols = Math.max(3, Math.min(6, width / dp(250)));
        int tileW = (width - gap * (cols - 1)) / cols;
        int tileH = tileW * 9 / 16 + dp(4);

        Set<String> fav = prefs.favorites(), hidden = prefs.hidden();
        boolean showHidden = prefs.showHidden();
        List<AppRepo.App> visible = new ArrayList<>();
        boolean showSystem = prefs.showSystem();
        for (AppRepo.App a : apps)
            if ((showHidden || !hidden.contains(a.pkg)) && (showSystem || !a.system)) visible.add(a);

        if (!query.isEmpty()) {
            List<AppRepo.App> hits = new ArrayList<>();
            String q = query.toLowerCase(Locale.ROOT);
            for (AppRepo.App a : visible)
                if (displayName(a).toLowerCase(Locale.ROOT).contains(q) || a.label.toLowerCase(Locale.ROOT).contains(q)
                        || a.pkg.toLowerCase(Locale.ROOT).contains(q)) hits.add(a);
            section("Results for “" + query + "”");
            grid(hits, cols, tileW, tileH, gap, fav, hidden, "Nothing matches.");
            return;
        }

        List<AppRepo.App> unsure = new ArrayList<>();
        for (AppRepo.App a : visible) if (prefs.gameAnswer(a.pkg) == null && guess(a) == GUESS_UNSURE) unsure.add(a);
        if (!unsure.isEmpty()) {
            LinearLayout ask = new LinearLayout(this);
            ask.setGravity(Gravity.CENTER_VERTICAL);
            ask.setPadding(dp(16), dp(12), dp(16), dp(12));
            ask.setBackground(round(SURFACE, dp(14)));
            TextView t = text("Not sure whether " + unsure.size() + (unsure.size() == 1 ? " app is" : " apps are")
                    + " a game or an app.", 15, TEXT, false);
            ask.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            TextView sort = pill("Sort them", true);
            sort.setOnClickListener(v -> sortUnsure(unsure, 0));
            ask.addView(sort);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
            alp.bottomMargin = dp(8);
            content.addView(ask, alp);
        }

        if (tab.equals(TAB_ALL) || tab.equals(TAB_FAV)) {
            List<AppRepo.App> recent = new ArrayList<>();
            for (AppRepo.App a : visible) if (a.lastUsed > 0 && isGame(a)) recent.add(a);
            recent.sort((x, y) -> Long.compare(y.lastUsed, x.lastUsed));
            if (!recent.isEmpty()) {
                section("Continue playing");
                grid(recent.subList(0, Math.min(cols, recent.size())), cols, tileW, tileH, gap, fav, hidden, null);
            }
        }

        List<AppRepo.App> list = new ArrayList<>();
        String empty = "Nothing here yet.";
        String heading;
        if (tab.equals(TAB_FAV)) {
            heading = "Favorites";
            for (AppRepo.App a : visible) if (fav.contains(a.pkg)) list.add(a);
            empty = "No favorites yet. Long-press (hold the trigger on) any game and choose “Add to Favorites”, or tap the ☆ on a tile.";
        } else if (tab.equals(TAB_VR)) {
            heading = "Games";
            for (AppRepo.App a : visible) if (isGame(a)) list.add(a);
            empty = "No games. Long-press an app → “Mark as game”.";
        } else if (tab.equals(TAB_2D)) {
            heading = "Apps";
            for (AppRepo.App a : visible) if (!isGame(a)) list.add(a);
        } else if (tab.equals(TAB_SIDE)) {
            heading = "Sideloaded";
            for (AppRepo.App a : visible) if (a.sideloaded) list.add(a);
            empty = "No sideloaded apps.";
        } else if (tab.startsWith(CAT_PREFIX)) {
            String c = tab.substring(CAT_PREFIX.length());
            heading = c;
            List<String> pkgs = prefs.categories().get(c);
            if (pkgs != null) for (AppRepo.App a : visible) if (pkgs.contains(a.pkg)) list.add(a);
            empty = "Empty category. Long-press any game → Categories → “" + c + "”.";
        } else {
            heading = "All apps";
            list = visible;
        }
        section(heading + "  ·  " + list.size());
        grid(list, cols, tileW, tileH, gap, fav, hidden, empty);
    }

    void section(String label) {
        TextView t = text(label, 15, MUTED, true);
        t.setPadding(0, dp(10), 0, dp(10));
        content.addView(t);
    }

    private void grid(List<AppRepo.App> list, int cols, int w, int h, int gap,
                      Set<String> fav, Set<String> hidden, String emptyText) {
        if (list.isEmpty()) {
            if (emptyText != null) {
                TextView t = text(emptyText, 16, MUTED, false);
                t.setPadding(0, dp(24), 0, dp(24));
                content.addView(t);
            }
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = gap;
                content.addView(row, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i % cols != 0) lp.leftMargin = gap;
            AppRepo.App a = list.get(i);
            row.addView(tile(a, fav.contains(a.pkg), hidden.contains(a.pkg)), lp);
        }
    }

    // ------------------------------------------------------------------ tiles

    private View tile(AppRepo.App a, boolean isFav, boolean isHidden) {
        FrameLayout f = new FrameLayout(this);
        int radius = dp(14);
        f.setBackground(round(SURFACE, radius));
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        f.setClipToOutline(true);
        if (isHidden) f.setAlpha(0.45f);

        Bitmap cover = cover(a);
        if (cover != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(cover);
            f.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else if (a.banner != null && prefs.useAppBanners()) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(a.banner);
            f.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            int c = dominant(a);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{shade(c, 0.55f), shade(c, 0.22f)});
            View bgv = new View(this);
            bgv.setBackground(g);
            f.addView(bgv, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(a.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER);
            ip.bottomMargin = dp(18);
            f.addView(icon, ip);
        }

        // Name and package over a bottom scrim
        LinearLayout label = new LinearLayout(this);
        label.setOrientation(LinearLayout.VERTICAL);
        label.setPadding(dp(14), dp(26), dp(14), dp(9));
        label.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xD8000000}));
        TextView name = text(displayName(a), 15, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        label.addView(name);
        TextView pkgLine = text(a.pkg, 10.5f, 0xA8FFFFFF, false);
        pkgLine.setSingleLine(true);
        pkgLine.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        label.addView(pkgLine);
        f.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Star toggle
        TextView star = text(isFav ? "★" : "☆", 20, isFav ? STAR : 0xB0FFFFFF, false);
        star.setPadding(dp(10), dp(4), dp(10), dp(6));
        star.setBackground(round(0x66000000, dp(14)));
        star.setVisibility(isFav ? View.VISIBLE : View.INVISIBLE);
        star.setOnClickListener(v -> { prefs.toggleFavorite(a.pkg); render(); });
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sp.setMargins(0, dp(8), dp(8), 0);
        f.addView(star, sp);

        if (a.sideloaded) {
            TextView chip = text("SIDELOADED", 10, TEXT, true);
            chip.setLetterSpacing(0.08f);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            chip.setBackground(round(0x99000000, dp(8)));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            cp.setMargins(dp(10), dp(10), 0, 0);
            f.addView(chip, cp);
        }

        // Hover: lift the tile and reveal the star (Quest controllers/hands hover)
        f.setOnHoverListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                v.animate().scaleX(1.04f).scaleY(1.04f).translationZ(dp(8)).setDuration(120).start();
                star.setVisibility(View.VISIBLE);
            } else if (e.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                v.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(120).start();
                if (!prefs.isFavorite(a.pkg)) star.setVisibility(View.INVISIBLE);
            }
            return false;
        });
        f.setOnClickListener(v -> {
            prefs.recordLaunch(a.pkg);
            boolean opened;
            try { opened = repo.launch(a.pkg); } catch (Exception ex) { opened = false; }
            if (!opened) toast("Couldn't open " + displayName(a));
        });
        f.setOnLongClickListener(v -> { appMenu(v, a); return true; });
        return f;
    }

    private Bitmap cover(AppRepo.App a) {
        if (coverCache.containsKey(a.pkg)) return coverCache.get(a.pkg);
        File f = coverSource(this, a.pkg);
        if (!f.exists()) {
            coverCache.put(a.pkg, null);
            return null;
        }
        coverCache.put(a.pkg, null);   // placeholder until decoded
        bg.execute(() -> {
            coverCache.put(a.pkg, decodeSampled(f, 640));
            ui.post(this::render);
        });
        return null;
    }

    /** Custom covers live in /sdcard/Android/data/com.cleanmyvr.launcher/files/covers/<package>.img */
    private File coverFile(String pkg) {
        File dir = new File(getExternalFilesDir(null), "covers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, pkg + ".img");
    }

    private int dominant(AppRepo.App a) {
        Integer cached = colorCache.get(a.pkg);
        if (cached != null) return cached;
        int c = computeDominant(a);
        colorCache.put(a.pkg, c);
        return c;
    }

    private static int computeDominant(AppRepo.App a) {
        int c = 0xFF3A4A6B;
        try {
            Bitmap b = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
            Drawable d = a.icon.getConstantState() != null ? a.icon.getConstantState().newDrawable().mutate() : a.icon;
            d.setBounds(0, 0, 12, 12);
            d.draw(new Canvas(b));
            long r = 0, g = 0, bl = 0, n = 0;
            for (int x = 0; x < 12; x++) for (int y = 0; y < 12; y++) {
                int p = b.getPixel(x, y);
                int al = Color.alpha(p);
                if (al < 128) continue;
                float[] hsv = new float[3];
                Color.colorToHSV(p, hsv);
                int w = 1 + (int) (hsv[1] * 4); // favor saturated pixels
                r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; n += w;
            }
            if (n > 0) c = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        } catch (Exception ignored) { }
        return c;
    }

    private static int shade(int c, float v) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.min(hsv[2], 1f) * v + 0.04f;
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        return Color.HSVToColor(hsv);
    }

    // ------------------------------------------------------------------ menus

    private void appMenu(View anchor, AppRepo.App a) {
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        boolean fav = prefs.isFavorite(a.pkg);
        menu.add(fav ? "★  Remove from Favorites" : "☆  Add to Favorites").setOnMenuItemClickListener(i -> {
            prefs.toggleFavorite(a.pkg); render(); return true; });

        SubMenu cats = menu.addSubMenu("Categories ▸");
        for (Map.Entry<String, List<String>> e : prefs.categories().entrySet()) {
            String name = e.getKey();
            MenuItem it = cats.add(name).setCheckable(true).setChecked(e.getValue().contains(a.pkg));
            it.setOnMenuItemClickListener(i -> { prefs.toggleInCategory(name, a.pkg); render(); return true; });
        }
        cats.add("+ New category…").setOnMenuItemClickListener(i -> {
            promptName("New category", "", name -> {
                prefs.addCategory(name);
                prefs.toggleInCategory(name, a.pkg);
                renderTabs(); render();
            });
            return true;
        });

        boolean game = isGame(a);
        menu.add(game ? "Not a game" : "Mark as game").setOnMenuItemClickListener(i -> {
            prefs.setGameExplicit(a.pkg, !game); render(); return true; });

        menu.add("Rename…").setOnMenuItemClickListener(i -> {
            promptText("Rename", null, displayName(a), t -> {
                // Only clear the custom name if it's the name that would show anyway
                // (the store name when known, otherwise the app's own name).
                prefs.setCustomLabel(a.pkg, t.equals(autoName(a)) ? null : t);
                render();
            });
            return true;
        });
        if (prefs.customLabel(a.pkg) != null)
            menu.add("Reset name (" + autoName(a) + ")").setOnMenuItemClickListener(i -> {
                prefs.setCustomLabel(a.pkg, null); render(); return true; });

        menu.add("Find cover online…").setOnMenuItemClickListener(i -> { findCover(a); return true; });
        menu.add("Upload cover image…").setOnMenuItemClickListener(i -> {
            pendingCoverPkg = a.pkg;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try { startActivityForResult(pick, REQ_COVER); } catch (Exception ex) { toast("No file picker available"); }
            return true;
        });
        if (coverFile(a.pkg).exists()) menu.add("Remove cover image").setOnMenuItemClickListener(i -> {
            //noinspection ResultOfMethodCallIgnored
            coverFile(a.pkg).delete(); coverCache.remove(a.pkg); render(); return true; });

        boolean hidden = prefs.hidden().contains(a.pkg);
        menu.add(hidden ? "Unhide" : "Hide from launcher").setOnMenuItemClickListener(i -> {
            prefs.toggleHidden(a.pkg); render(); return true; });
        menu.add("App info").setOnMenuItemClickListener(i -> { openAppInfo(a.pkg); return true; });
        m.show();
    }

    private void categoryMenu(View anchor, String cat) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add("Rename…").setOnMenuItemClickListener(i -> {
            promptName("Rename category", cat, name -> { prefs.renameCategory(cat, name); selectTab(CAT_PREFIX + name); });
            return true;
        });
        m.getMenu().add("Delete category").setOnMenuItemClickListener(i -> {
            new AlertDialog.Builder(this).setTitle("Delete “" + cat + "”?")
                    .setMessage("The games stay installed; only the category is removed.")
                    .setPositiveButton("Delete", (d, w) -> { prefs.deleteCategory(cat); selectTab(TAB_ALL); })
                    .setNegativeButton("Cancel", null).show();
            return true;
        });
        m.show();
    }

    private void showMainMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        menu.add("Show hidden apps").setCheckable(true).setChecked(prefs.showHidden())
                .setOnMenuItemClickListener(i -> { prefs.setShowHidden(!prefs.showHidden()); render(); return true; });
        menu.add("Show system apps").setCheckable(true).setChecked(prefs.showSystem())
                .setOnMenuItemClickListener(i -> { prefs.setShowSystem(!prefs.showSystem()); render(); return true; });
        menu.add(takeoverEnabled() ? "Library takeover: ON" : "Library takeover: OFF (tap to enable)")
                .setOnMenuItemClickListener(i -> {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                });
        menu.add("Wallpaper…").setOnMenuItemClickListener(i -> { wallpaperMenu(); return true; });
        SubMenu covers = menu.addSubMenu("Covers ▸");
        covers.add("Fetch missing game covers").setOnMenuItemClickListener(i -> { fetchMissingCovers(); return true; });
        covers.add("SteamGridDB API key…").setOnMenuItemClickListener(i -> { askSgdbKey(null); return true; });
        covers.add("Online names & banners (MetaMetadata)").setCheckable(true).setChecked(prefs.onlineInfo())
                .setOnMenuItemClickListener(i -> {
                    prefs.setOnlineInfo(!prefs.onlineInfo());
                    coverCache.clear();
                    AppRepo.invalidate();
                    reload();
                    return true;
                });
        covers.add("Use built-in app banners").setCheckable(true).setChecked(prefs.useAppBanners())
                .setOnMenuItemClickListener(i -> { prefs.setUseAppBanners(!prefs.useAppBanners()); render(); return true; });
        menu.add("Install an APK file…").setOnMenuItemClickListener(i -> { pickApk(); return true; });
        menu.add("Android settings").setOnMenuItemClickListener(i -> { openAndroidSettings(); return true; });
        menu.add("In-game menu (Meta button in VR games)").setCheckable(true).setChecked(prefs.inGameMenu())
                .setOnMenuItemClickListener(i -> {
                    prefs.setInGameMenu(!prefs.inGameMenu());
                    toast(prefs.inGameMenu() ? "The Meta button opens the in-game menu inside VR games"
                            : "Inside VR games the Meta button opens Meta's menu again");
                    return true;
                });
        menu.add("Open Meta's Library once").setOnMenuItemClickListener(i -> { openMetaLibrary(); return true; });
        menu.add("Refresh").setOnMenuItemClickListener(i -> { coverCache.clear(); reload(); return true; });
        m.show();
    }

    /** Android's own settings app (Quest normally routes you to Meta's settings instead). */
    /**
     * Android's own Settings app. Quest blocks other apps from opening it directly, so (like the
     * "Go To Settings" app) fall back to Settings' own App info page, where OPEN starts it.
     */
    private void openAndroidSettings() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.android.settings");
        if (i != null) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME));
                return;
            } catch (Exception ignored) { }
        }
        if (AppRepo.openAppInfo(this, "com.android.settings")) toast("Press OPEN to go to Android settings");
        else toast("Android settings aren't available");
    }

    /** Pick any .apk (Downloads, a USB drive, ...) and install it. */
    private void pickApk() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
        try { startActivityForResult(pick, REQ_APK); } catch (Exception e) { toast("No file picker available"); }
    }

    private void installPickedApk(android.net.Uri uri) {
        toast("Installing…");
        net.execute(() -> {
            try {
                new Store(this).installLocalApk(uri);           // Android shows its confirm dialog next
            } catch (Exception e) {
                ui.post(() -> toast(e.getMessage() != null ? e.getMessage() : "Couldn't install that file"));
            }
        });
    }

    /** Android's App info page, not Meta's. */
    private void openAppInfo(String pkg) {
        if (!AppRepo.openAppInfo(this, pkg)) toast("App info isn't available");
    }

    private boolean takeoverEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private void openMetaLibrary() {
        prefs.skipNextTakeover(30_000);
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.oculus.panelapp.library", "com.oculus.navigator.library.app.NavigatorLibraryActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Open the Library from the menu bar now (just this once).");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_APK) {
            if (res == RESULT_OK && data != null && data.getData() != null) installPickedApk(data.getData());
            return;
        }
        if (req == REQ_WALLPAPER && res == RESULT_OK && data != null) {
            Uri wuri = data.getData();
            bg.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(wuri);
                     OutputStream out = new FileOutputStream(wallpaperFile())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ui.post(() -> { prefs.setWallType(Prefs.WALL_IMAGE); applyWallpaper(); });
                } catch (Exception e) {
                    ui.post(() -> toast("Couldn't use that image"));
                }
            });
            return;
        }
        if (req != REQ_COVER || res != RESULT_OK || data == null || pendingCoverPkg == null) return;
        String pkg = pendingCoverPkg;
        Uri uri = data.getData();
        bg.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(coverFile(pkg))) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                ui.post(() -> { coverCache.remove(pkg); render(); });
            } catch (Exception e) {
                ui.post(() -> toast("Couldn't use that image"));
            }
        });
    }

    // ------------------------------------------------------------------ games

    static final int GUESS_APP = 0, GUESS_GAME = 1, GUESS_UNSURE = 2;
    private static final java.util.Set<String> KNOWN_TOOLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "VirtualDesktop.Android", "com.bigscreenvr.bigscreen", "quest.side.vr", "com.gbls.bndk.realityOS",
            "com.mgatelabs.mobilevrstationthree", "com.facebook.arvr.quillplayer"));
    private static final java.util.Set<String> TOOL_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "browser", "player", "desktop", "launcher", "settings", "store", "manager", "files", "file", "explorer",
            "keyboard", "camera", "gallery", "tv", "video", "music", "stream", "streaming", "cast", "remote", "vpn",
            "chat", "messenger", "discord", "youtube", "meta", "quest", "account", "accounts", "center"));

    /** Best guess from what the app declares about itself, how it's installed and its name. */
    static int guess(AppRepo.App a) {
        String p = a.pkg;
        if (a.system || p.startsWith("com.oculus.") || p.startsWith("com.meta.") || p.startsWith("com.facebook.")
                || p.startsWith("com.android.") || p.startsWith("horizonos")) return GUESS_APP;
        if (a.storeCategory != null) return a.storeCategory.equalsIgnoreCase("Games") ? GUESS_GAME : GUESS_APP;
        if (a.gameFlag || a.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) return GUESS_GAME;
        if (a.category != android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED) return GUESS_APP;
        if (!a.vr) return GUESS_APP;                 // flat 2D apps are almost never games on Quest
        if (KNOWN_TOOLS.contains(p)) return GUESS_APP;
        for (String w : a.label.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            if (TOOL_WORDS.contains(w)) return GUESS_APP;
        return a.sideloaded ? GUESS_UNSURE : GUESS_GAME;
    }

    private boolean isGame(AppRepo.App a) {
        Boolean answer = prefs.gameAnswer(a.pkg);
        return answer != null ? answer : guess(a) != GUESS_APP;
    }

    /** Ask about each app the guesser isn't sure of, one dialog at a time. */
    private void sortUnsure(List<AppRepo.App> list, int i) {
        if (i >= list.size()) { render(); return; }
        AppRepo.App a = list.get(i);
        new AlertDialog.Builder(this)
                .setTitle("Is “" + displayName(a) + "” a game?")
                .setMessage(a.pkg + (a.sideloaded ? "  ·  sideloaded" : "") + "\n\n" + (i + 1) + " of " + list.size())
                .setPositiveButton("Game", (d, w) -> { prefs.setGameExplicit(a.pkg, true); sortUnsure(list, i + 1); })
                .setNegativeButton("App", (d, w) -> { prefs.setGameExplicit(a.pkg, false); sortUnsure(list, i + 1); })
                .setNeutralButton("Skip", (d, w) -> sortUnsure(list, i + 1))
                .show();
    }

    String displayName(AppRepo.App a) {
        String c = prefs.customLabel(a.pkg);
        return c != null ? c : autoName(a);
    }

    /** The name shown when you haven't renamed an app: its store name if known, else its own name. */
    String autoName(AppRepo.App a) {
        return a.storeName != null && prefs.onlineInfo() ? a.storeName : a.label;
    }

    // ------------------------------------------------------------------ in-game menu

    /** Show the in-game menu for the VR app running behind the launcher (null hides it). */
    void showGameMenu(String pkg) {
        if (gameMenu != null) { rootFrame.removeView(gameMenu); gameMenu = null; }
        if (backToGame != null) { rootFrame.removeView(backToGame); backToGame = null; }
        if (pkg == null || !repo.isInstalled(pkg)) { gamePkg = null; return; }
        gamePkg = pkg;
        AppRepo.App app = null;
        if (apps != null) for (AppRepo.App a : apps) if (a.pkg.equals(pkg)) app = a;
        String name = app != null ? displayName(app) : repo.label(pkg);

        FrameLayout ov = new FrameLayout(this);
        ov.setClickable(true);                       // nothing behind the menu reacts to clicks
        // Background: the game's own cover/banner (or icon), heavily blurred and dimmed.
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap cover = coverCache.get(pkg);
        if (cover == null) { File f = coverSource(this, pkg); if (f.exists()) cover = decodeSampled(f, 640); }
        if (cover != null) art.setImageBitmap(cover);
        else if (app != null && app.banner != null) art.setImageDrawable(app.banner);
        else art.setImageDrawable(repo.icon(pkg));
        if (android.os.Build.VERSION.SDK_INT >= 31)
            art.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(36), dp(36), android.graphics.Shader.TileMode.CLAMP));
        ov.addView(art, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackgroundColor(0xB80E0F13);
        ov.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(40), dp(34), dp(40), dp(28));
        card.setBackground(round(0xF01A1D24, dp(30)));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(repo.icon(pkg));
        card.addView(icon, new LinearLayout.LayoutParams(dp(84), dp(84)));
        TextView title = text(name, 28, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, 0);
        card.addView(title);
        TextView sub = text(pkg, 14, MUTED, false);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setPadding(0, dp(4), 0, dp(22));
        card.addView(sub);

        card.addView(menuButton("Open Another App", ACCENT, BG, v -> openAnotherApp(pkg, name)));
        card.addView(menuButton("Restart App", SURFACE_HI, TEXT, v -> stopApp(pkg, name, true)));
        card.addView(menuButton("Close App", SURFACE_HI, 0xFFFF7A7A, v -> stopApp(pkg, name, false)));

        gameStatus = text("", 14, MUTED, false);
        gameStatus.setGravity(Gravity.CENTER);
        gameStatus.setPadding(0, dp(10), 0, 0);
        card.addView(gameStatus);

        LinearLayout links = new LinearLayout(this);
        links.setGravity(Gravity.CENTER);
        links.setPadding(0, dp(8), 0, 0);
        TextView resume = text("Resume", 15, MUTED, true);
        TextView meta = text("Meta menu", 15, MUTED, true);
        resume.setPadding(dp(16), dp(8), dp(16), dp(8));
        meta.setPadding(dp(16), dp(8), dp(16), dp(8));
        resume.setOnClickListener(v -> finish());
        meta.setOnClickListener(v -> {
            prefs.skipNextTakeover(15_000);
            toast("Press the Meta button for Meta's menu");
            finish();
        });
        links.addView(resume);
        links.addView(meta);
        card.addView(links);

        ov.addView(card, new FrameLayout.LayoutParams(dp(520), -2, Gravity.CENTER));
        rootFrame.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        gameMenu = ov;
    }

    /** Hide the menu but keep a "Back to <game>" button, since the game is still running behind us. */
    private void openAnotherApp(String pkg, String name) {
        showGameMenu(null);
        TextView back = text("◀  Back to " + name + " menu", 16, BG, true);
        back.setPadding(dp(22), dp(12), dp(22), dp(12));
        back.setBackground(round(ACCENT, dp(24)));
        back.setElevation(dp(8));
        back.setOnClickListener(v -> showGameMenu(pkg));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(24);
        rootFrame.addView(back, lp);
        backToGame = back;
    }

    private TextView menuButton(String label, int bg, int fg, View.OnClickListener click) {
        TextView b = text(label, 18, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(16), dp(20), dp(16));
        b.setBackground(round(bg, dp(18)));
        b.setOnClickListener(click);
        b.setOnHoverListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER) v.setAlpha(0.85f);
            else if (e.getAction() == MotionEvent.ACTION_HOVER_EXIT) v.setAlpha(1f);
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    /** Close (or restart) the app behind the launcher by pressing Force stop on its App info page. */
    private void stopApp(String pkg, String name, boolean restart) {
        if (gameStatus != null) gameStatus.setText(restart ? "Restarting…" : "Closing…");
        stopAttempted = true;
        boolean started = LibraryInterceptor.forceStop(this, pkg, repo.label(pkg), ok -> ui.post(() -> {
            if (!ok) {
                if (gameStatus != null) gameStatus.setText("");
                toast("Press Force stop, then OK" + (restart ? ", then open " + name + " again" : ""));
                return;
            }
            // The launcher is singleTask: starting it again clears whatever is stacked on it (App info).
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            showGameMenu(null);
            if (restart) {
                if (!repo.launch(pkg)) toast("Couldn't open " + name);
            } else {
                toast(name + " closed");
            }
        }));
        if (!started) {
            if (gameStatus != null) gameStatus.setText("");
            toast("Turn on Library takeover (the launcher's accessibility service) to close apps");
        }
    }

    // ------------------------------------------------------------------ SteamGridDB covers

    private void askSgdbKey(Runnable then) {
        promptText("SteamGridDB API key",
                "Free key: steamgriddb.com → log in → Preferences → API. Paste it here.",
                prefs.sgdbKey(), key -> {
                    prefs.setSgdbKey(key);
                    if (then != null && !key.isEmpty()) then.run();
                });
    }

    /** Search Steam (no key needed) and SteamGridDB (if you added a key), then pick a banner. */
    private void findCover(AppRepo.App a) {
        promptText("Find a cover", null, displayName(a), term -> {
            toast("Searching…");
            String key = prefs.sgdbKey();
            net.execute(() -> {
                List<Object> results = new ArrayList<>();
                List<String> names = new ArrayList<>();
                try {
                    for (Steam.Game g : Steam.search(term)) { results.add(g); names.add("Steam: " + g.name); }
                } catch (Exception ignored) { }
                if (!key.isEmpty()) {
                    try {
                        for (SteamGridDb.Game g : new SteamGridDb(key).search(term)) { results.add(g); names.add("SteamGridDB: " + g); }
                    } catch (Exception ignored) { }
                }
                ui.post(() -> {
                    if (results.isEmpty()) {
                        toast(key.isEmpty() ? "Nothing on Steam. Add a free SteamGridDB key (⋯ → Covers) to search more."
                                : "No results for “" + term + "”");
                        return;
                    }
                    int n = Math.min(16, results.size());
                    new AlertDialog.Builder(this).setTitle("Which game?")
                            .setItems(names.subList(0, n).toArray(new String[0]), (d, w) -> {
                                Object r = results.get(w);
                                if (r instanceof Steam.Game)
                                    pickGrid(a, ((Steam.Game) r).name + " (Steam)", Steam.banners(((Steam.Game) r).id));
                                else
                                    showGrids(a, new SteamGridDb(key), (SteamGridDb.Game) r);
                            }).show();
                });
            });
        });
    }

    private void showGrids(AppRepo.App a, SteamGridDb api, SteamGridDb.Game game) {
        net.execute(() -> {
            try {
                List<SteamGridDb.Grid> grids = api.grids(game.id);
                ui.post(() -> {
                    if (grids.isEmpty()) { toast("No banners for " + game.name); return; }
                    pickGrid(a, game.name, grids);
                });
            } catch (Exception e) {
                ui.post(() -> toast(e.getMessage() == null ? "Couldn't load banners" : e.getMessage()));
            }
        });
    }

    private void pickGrid(AppRepo.App a, String title, List<SteamGridDb.Grid> grids) {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(8));
        sv.addView(col);
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(title + ": pick a banner")
                .setView(sv).setNegativeButton("Cancel", null).create();
        int perRow = 3, w = dp(260), h = w * 215 / 460;
        LinearLayout row = null;
        for (int i = 0; i < Math.min(30, grids.size()); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setPadding(0, 0, 0, dp(10));
                col.addView(row);
            }
            SteamGridDb.Grid g = grids.get(i);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(round(SURFACE_HI, dp(8)));
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i % perRow != 0) lp.leftMargin = dp(10);
            row.addView(iv, lp);
            net.execute(() -> {
                try {
                    byte[] b = SteamGridDb.download(g.thumb);
                    Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
                    ui.post(() -> { if (bmp != null) iv.setImageBitmap(bmp); else iv.setVisibility(View.GONE); });
                } catch (Exception e) {
                    ui.post(() -> iv.setVisibility(View.GONE));   // that image doesn't exist for this game
                }
            });
            iv.setOnClickListener(v -> {
                dlg.dismiss();
                toast("Downloading banner…");
                saveCoverFromUrl(a.pkg, g.url, true);
            });
        }
        dlg.show();
    }

    private void saveCoverFromUrl(String pkg, String url, boolean announce) {
        net.execute(() -> {
            try {
                byte[] b = SteamGridDb.download(url);
                try (OutputStream out = new FileOutputStream(coverFile(pkg))) { out.write(b); }
                ui.post(() -> { coverCache.remove(pkg); render(); if (announce) toast("Cover updated"); });
            } catch (Exception e) {
                if (announce) ui.post(() -> toast("Download failed"));
            }
        });
    }

    /** For every game without a custom cover: best SteamGridDB match (if you have a key), else Steam. */
    private void fetchMissingCovers() {
        List<AppRepo.App> todo = new ArrayList<>();
        for (AppRepo.App a : apps) if (isGame(a) && !coverSource(this, a.pkg).exists()) todo.add(a);
        if (todo.isEmpty()) { toast("Every game already has a cover"); return; }
        String key = prefs.sgdbKey();
        new AlertDialog.Builder(this).setTitle("Fetch " + todo.size() + " covers?")
                .setMessage("Uses the best match for each game's name" + (key.isEmpty() ? " on Steam" : " on SteamGridDB, then Steam")
                        + ". Fix any wrong ones with long-press → Find cover online.")
                .setPositiveButton("Fetch", (d, w) -> {
                    SteamGridDb api = key.isEmpty() ? null : new SteamGridDb(key);
                    toast("Fetching covers…");
                    bg.execute(() -> {
                        int ok = 0;
                        for (AppRepo.App a : todo) {
                            String term = displayName(a);
                            byte[] b = null;
                            if (api != null) {
                                try {
                                    List<SteamGridDb.Game> g = api.search(term);
                                    if (!g.isEmpty()) {
                                        List<SteamGridDb.Grid> grids = api.grids(g.get(0).id);
                                        if (!grids.isEmpty()) b = SteamGridDb.download(grids.get(0).url);
                                    }
                                } catch (Exception ignored) { }
                            }
                            if (b == null) {
                                try {
                                    List<Steam.Game> g = Steam.search(term);
                                    if (!g.isEmpty()) b = SteamGridDb.download(Steam.banners(g.get(0).id).get(0).url);
                                } catch (Exception ignored) { }
                            }
                            if (b == null) continue;
                            try (OutputStream out = new FileOutputStream(coverFile(a.pkg))) { out.write(b); } catch (Exception e) { continue; }
                            ok++;
                            coverCache.remove(a.pkg);
                            ui.post(this::render);
                        }
                        int done = ok;
                        ui.post(() -> toast("Got " + done + " of " + todo.size() + " covers"));
                    });
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ helpers

    interface TextCallback { void got(String text); }

    void promptText(String title, String message, String initial, TextCallback cb) {
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(initial);
        in.setSelection(initial.length());
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(in);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    String t = in.getText().toString().trim();
                    if (!t.isEmpty() || message != null) cb.got(t);
                })
                .setNegativeButton("Cancel", null);
        if (message != null) b.setMessage(message);
        submitOnDone(b.show(), in);
    }

    /**
     * The headset keyboard's Enter/Done key should confirm the dialog (otherwise people press Done,
     * the keyboard closes, they tap outside and nothing is saved).
     */
    private static void submitOnDone(AlertDialog dlg, EditText in) {
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        in.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN;
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || enter) {
                android.widget.Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                if (ok != null) ok.performClick();
                return true;
            }
            return false;
        });
        dlg.setCanceledOnTouchOutside(false);
    }

    private interface NameCallback { void got(String name); }

    private void promptName(String title, String initial, NameCallback cb) {
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(initial);
        in.setSelection(initial.length());
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(in);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (n.isEmpty()) return;
                    if (!n.equals(initial) && prefs.categories().containsKey(n)) { toast("Category already exists"); return; }
                    cb.got(n);
                })
                .setNegativeButton("Cancel", null);
        submitOnDone(b.show(), in);
    }

    TextView pill(String label, boolean selected) {
        TextView t = text(label, 15, selected ? BG : TEXT, true);
        t.setPadding(dp(18), dp(9), dp(18), dp(9));
        t.setBackground(round(selected ? ACCENT : SURFACE, dp(20)));
        t.setOnHoverListener((v, e) -> {
            if (!selected) {
                if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER) v.setBackground(round(SURFACE_HI, dp(20)));
                else if (e.getAction() == MotionEvent.ACTION_HOVER_EXIT) v.setBackground(round(SURFACE, dp(20)));
            }
            return false;
        });
        return t;
    }

    private LinearLayout.LayoutParams tabLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(10);
        return lp;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
