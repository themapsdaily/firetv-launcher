package com.siddh.home;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: a full-screen shelf for the focused app, a greeting, the clock, and a dock of
 * app tiles with the rest of the apps in rows below it.
 *
 * Focus is tracked here rather than through Android's focus system, so every move is one index
 * change and one animation, which keeps the remote responsive on a slow Fire TV Stick.
 */
public final class Home extends Activity {
    /** The current background tint, for Guard's cover window. */
    static volatile int lastTint = 0xFF0E0F13;

    private static final String NAME = "Siddharth";
    private static final int COLS = 6;
    private static final long SHELF_DELAY_MS = 140;
    private static final long LINE_EVERY_MS = 6000;
    private static final long AWAY_GREETING_MS = 15 * 60_000;
    /** YouTube has no artwork to show, so its shelf is the greeting. */
    private static final String YOUTUBE = "com.amazon.firetv.youtube";
    /** macOS-style dock magnification by distance from the focused tile. */
    private static final float[] MAGNIFY = {1.5f, 1.2f, 1.06f};
    private static final long MAGNIFY_MS = 260;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Greeting words = new Greeting();
    private float s;
    private int W, H;
    private Gallery gallery;
    private SurfaceView video;
    private boolean surfaceReady;
    private MediaPlayer player;
    private File playing;
    private Grid grid;
    private LinearLayout titles, hello, top;
    private TextView overline, title, detail, helloLine, helloHead, weatherPill;
    private ImageView logo;
    private final List<TextView> pills = new ArrayList<>();
    private final List<Runnable> pillActions = new ArrayList<>();

    private List<Apps.App> apps = new ArrayList<>();
    private int focus;
    private boolean inTop;
    private int topFocus;
    private boolean greeting;
    private boolean selectHeld, selectLong;
    private float dockPad, glassLeft, glassRight;
    private float[] dockScale = new float[0], dockShift = new float[0];
    private ValueAnimator magnifier;
    private long pausedAt;
    private int tint = lastTint;
    private ValueAnimator tintAnim;
    private String shelfKey;

    private final Runnable updateShelf = this::updateShelf;
    private final Runnable nextLine = this::nextLine;
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = Math.max(dm.widthPixels, dm.heightPixels);
        H = Math.min(dm.widthPixels, dm.heightPixels);
        s = W / 1920f;
        rememberConfig(getIntent());
        setContentView(build());
        reload();
        IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        updateGreeting();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        worker.shutdownNow();
        gallery.release();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Guard.arrived(this);
        Watcher.onChange = () -> {
            shelfKey = null;
            updateShelf();
        };
        gallery.start();
        showWeather();
        final List<Apps.App> dock = new ArrayList<>(apps);
        worker.execute(() -> Tidy.maybe(this, dock));
        Feed.refresh(this, () -> ui.post(() -> {
            shelfKey = null;
            updateShelf();
            showWeather();
        }));
        // Back after a while: a fresh greeting.
        if (greeting && pausedAt > 0 && SystemClock.uptimeMillis() - pausedAt > AWAY_GREETING_MS) showGreeting();
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Watcher.onChange = null;
        gallery.stop();
        stopVideo();
        shelfKey = null;    // so onResume restarts the clip
        pausedAt = SystemClock.uptimeMillis();
        ui.removeCallbacks(nextLine);
    }

    /** HOME pressed while already home: back to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        rememberConfig(intent);
        if (inTop) leaveTop();
        if (row() > 0) move(focus % COLS, 0, -1);
    }

    @Override
    public void onBackPressed() {
        if (inTop) leaveTop();
        else if (row() > 0) move(focus % COLS, 0, -1);
        // On the dock BACK does nothing: this is home.
    }

    /**
     * Settings arrive as launch extras, e.g. from install.sh:
     * {@code am start -n com.siddh.home/.Home --es name Sid --es city Pune --es tmdb <key>}.
     */
    private void rememberConfig(Intent i) {
        if (i == null) return;
        String n = i.getStringExtra("name"), key = i.getStringExtra("tmdb"), city = i.getStringExtra("city");
        if (!TextUtils.isEmpty(n)) Apps.prefs(this).edit().putString("name", n.trim()).apply();
        if (i.getBooleanExtra("refresh", false)) Feed.invalidate(this);
        if (!TextUtils.isEmpty(key) && !key.trim().equals(Apps.prefs(this).getString("tmdb", ""))) {
            Apps.prefs(this).edit().putString("tmdb", key.trim()).apply();
            Feed.invalidate(this);
        }
        if (!TextUtils.isEmpty(city) && !city.trim().equalsIgnoreCase(Feed.city(this))) {
            worker.execute(() -> {
                if (Feed.setCity(this, city.trim())) Feed.refresh(this, () -> ui.post(this::showWeather));
            });
        }
    }

    private String name() {
        return Apps.prefs(this).getString("name", NAME);
    }

    // ---- layout ----

    private int px(float v) {
        return Math.round(v * s);
    }

    private TextView text(float size, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(Fonts.outfit(this, bold ? 700 : 400));
        t.setShadowLayer(px(12), 0, px(2), 0x80000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);

        // A clip plays here, under the gallery, for apps that have one (FanCode's F1 video). A
        // SurfaceView goes straight to the display's video plane; the gallery stays transparent
        // over it while it plays.
        video = new SurfaceView(this);
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                if (playing != null && player == null) startPlayer(playing, holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                releasePlayer();
            }
        });
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        gallery = new Gallery(this);
        gallery.setListener(this::onSlide);
        // A soft shade where the title logo sits, so a red logo on dark art (Inception) still reads.
        gallery.setSpot(px(96) + W * 0.17f, H * 0.32f, W * 0.36f, 0.65f);
        root.addView(gallery, new FrameLayout.LayoutParams(-1, -1));

        // Title block, top-left: overline ("NETFLIX · CONTINUE WATCHING"), title, detail.
        titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        overline = text(26, false);
        overline.setLetterSpacing(0.12f);
        overline.setAlpha(0.8f);
        title = text(88, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 0.95f);
        detail = text(30, false);
        detail.setAlpha(0.85f);
        detail.setMaxLines(3);
        detail.setLineSpacing(px(10), 1f);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        logo = new ImageView(this);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setMaxWidth(Math.round(W * 0.34f));
        logo.setMaxHeight(Math.round(H * 0.2f));
        logo.setVisibility(View.GONE);
        titles.addView(overline);
        titles.addView(logo, margins(-2, -2, 0, px(18), 0, 0));
        titles.addView(title, margins(-2, -2, 0, px(14), 0, 0));
        titles.addView(detail, margins(-2, -2, 0, px(14), 0, 0));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Math.round(W * 0.46f), -2);
        tl.leftMargin = px(96);
        tl.topMargin = Math.round(H * 0.22f);
        root.addView(titles, tl);

        // Greeting, same place; replaces the title block while it shows.
        hello = new LinearLayout(this);
        hello.setOrientation(LinearLayout.VERTICAL);
        helloHead = text(96, true);
        helloLine = text(44, false);
        helloLine.setAlpha(0.9f);
        hello.addView(helloHead);
        hello.addView(helloLine, margins(-2, -2, 0, px(22), 0, 0));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(Math.round(W * 0.6f), -2);
        hl.leftMargin = px(96);
        hl.topMargin = Math.round(H * 0.22f);
        hello.setAlpha(0f);
        root.addView(hello, hl);

        root.addView(buildTop(), topParams());

        // Dock: a semi-transparent panel behind row 0. Solid, not blurred: cheap on the Stick's GPU.
        int gap = px(32);
        int tileW = px(232);
        int tileH = tileW * 9 / 16;
        int rowW = tileW * COLS + gap * (COLS - 1);
        int gridLeft = (W - rowW) / 2;
        int gridTop = Math.round(H * 0.745f);
        int pad = px(22);
        dockPad = pad;
        // The dock is liquid glass drawn by the gallery itself, so it frosts the moving picture.
        gallery.setGlass(new RectF(gridLeft - pad, gridTop - pad, gridLeft + rowW + pad, gridTop + tileH + pad), px(40));

        TextView label = text(30, false);
        label.setVisibility(View.GONE);
        label.setTranslationZ(px(100));   // above the focused tile, whose elevation lifts it
        label.setGravity(Gravity.CENTER);
        grid = new Grid(this, COLS, tileW, tileH, gap, px(96), gridLeft, gridTop, label);
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private FrameLayout.LayoutParams topParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.topMargin = px(56);
        lp.rightMargin = px(72);
        return lp;
    }

    /** Top-right: quick buttons (shown when focus moves up), then the clock pill with an avatar. */
    private View buildTop() {
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        if (!inputs().isEmpty()) pill("Inputs", this::openInputs);
        pill("Wi-Fi", () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        pill("Settings", this::openSettings);

        LinearLayout clock = new LinearLayout(this);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        clock.setBackground(round(0x40000000, 0x26FFFFFF));
        clock.setPadding(px(26), px(8), px(8), px(8));
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("H:mm");
        time.setTextColor(0xFFFFFFFF);
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        weatherPill = new TextView(this);
        weatherPill.setTextColor(0xFFFFFFFF);
        weatherPill.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        weatherPill.setGravity(Gravity.CENTER_VERTICAL);
        weatherPill.setCompoundDrawablePadding(px(8));
        clock.addView(weatherPill, margins(-2, -2, 0, 0, px(22), 0));
        clock.addView(time);
        clock.addView(avatar(px(54)), margins(px(54), px(54), px(18), 0, 0, 0));
        top.addView(clock);
        return top;
    }

    /** The owner's picture (assets/avatar.jpg) in a circle, or their initial if it's missing. */
    private View avatar(int size) {
        Bitmap face = null;
        try (java.io.InputStream in = getAssets().open("avatar.jpg")) {
            Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            if (b != null) {
                face = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(face);
                android.graphics.Paint p = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(b,
                        android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(size / (float) b.getWidth(), size / (float) b.getHeight());
                shader.setLocalMatrix(m);
                p.setShader(shader);
                c.drawCircle(size / 2f, size / 2f, size / 2f, p);
                // A hairline rim, like the pill it sits in.
                p.setShader(null);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1, px(2)));
                p.setColor(0x40FFFFFF);
                c.drawCircle(size / 2f, size / 2f, size / 2f - px(1), p);
                b.recycle();
            }
        } catch (IOException e) {
            // fall through to the initial
        }
        if (face != null) {
            ImageView v = new ImageView(this);
            v.setImageBitmap(face);
            return v;
        }
        TextView initial = text(28, true);
        initial.setShadowLayer(0, 0, 0, 0);
        initial.setGravity(Gravity.CENTER);
        initial.setText(name().substring(0, 1).toUpperCase());
        GradientDrawable dot = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFFFF8A65, 0xFFD81B60});
        dot.setShape(GradientDrawable.OVAL);
        initial.setBackground(dot);
        return initial;
    }

    private void pill(String label, Runnable action) {
        TextView t = text(28, false);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(label);
        t.setPadding(px(30), px(16), px(30), px(16));
        t.setBackground(round(0x40000000, 0x26FFFFFF));
        t.setAlpha(0f);
        top.addView(t, margins(-2, -2, 0, 0, px(16), 0));
        pills.add(t);
        pillActions.add(action);
    }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(40));
        d.setColor(fill);
        if (stroke != 0) d.setStroke(Math.max(1, px(1.5f)), stroke);
        return d;
    }

    // ---- apps ----

    private void reload() {
        String keep = current();
        apps = Apps.load(this);
        grid.clearTiles();
        for (int i = 0; i < apps.size(); i++) {
            Tile t = new Tile(this, apps.get(i), s);
            t.docked = i < COLS;
            grid.addTile(t);
        }
        dockScale = new float[Math.min(COLS, apps.size())];
        dockShift = new float[dockScale.length];
        java.util.Arrays.fill(dockScale, 1f);
        focus = 0;
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(keep)) focus = i;
        if (!apps.isEmpty()) grid.tile(focus).setFocus(true, 0, 0);
        placeGrid(false);
        magnify(false);
        final List<Apps.App> load = new ArrayList<>(apps);
        worker.execute(() -> {
            for (Apps.App a : load) {
                Apps.art(getPackageManager(), a);
                ui.post(() -> artLoaded(a));
            }
        });
        shelfKey = null;
        updateShelf();
    }

    private void artLoaded(Apps.App a) {
        int i = apps.indexOf(a);
        if (i < 0) return;
        grid.tile(i).invalidate();
        if (i == focus) {
            gallery.setFallback(a.haze);
            fadeTint(Apps.tint(a.color));
        }
    }

    private String current() {
        return apps.isEmpty() || focus >= apps.size() ? null : apps.get(focus).pkg;
    }

    private int row() {
        return focus / COLS;
    }

    // ---- keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            // Like Apple TV: OK acts on release, so holding it can open the menu instead.
            if (down) {
                if (e.getRepeatCount() == 0) {
                    selectHeld = true;
                    selectLong = false;
                } else if (e.getRepeatCount() == 1 && selectHeld && !inTop) {
                    selectLong = true;
                    menu();
                }
                return true;
            }
            boolean act = selectHeld && !selectLong;
            selectHeld = false;
            if (act) key(code);
            return true;
        }
        if (!isNav(code)) {
            if (down && code == KeyEvent.KEYCODE_MENU) {
                if (!inTop) menu();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (down) key(code);
        return true;
    }

    private void key(int code) {
        if (inTop) topKey(code);
        else gridKey(code);
    }

    private static boolean isNav(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
    }

    private void gridKey(int code) {
        if (apps.isEmpty()) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) enterTop();
            return;
        }
        int col = focus % COLS, r = row();
        int rows = (apps.size() + COLS - 1) / COLS;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col > 0) move(col - 1, r, 0);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < COLS - 1 && focus + 1 < apps.size()) move(col + 1, r, 0);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (r > 0) move(col, r - 1, -1);
                else enterTop();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (r + 1 < rows) move(col, r + 1, 1);
                break;
            default:
                open(apps.get(focus));
        }
    }

    private void move(int col, int r, int dy) {
        int next = Math.min(r * COLS + col, apps.size() - 1);
        if (next == focus) return;
        int dx = Integer.signum((next % COLS) - (focus % COLS));
        int oldRow = row();
        grid.tile(focus).setFocus(false, 0, 0);
        focus = next;
        grid.tile(focus).setFocus(true, dy == 0 ? dx : 0, dy);
        if (row() != oldRow) placeGrid(true);
        if (row() == 0 || oldRow == 0) magnify(true);
        grid.labelUnder(row() > 0 ? focus : -1);
        updateGreeting();
        ui.removeCallbacks(updateShelf);
        ui.postDelayed(updateShelf, SHELF_DELAY_MS);
    }

    /** Row 0 sits in the dock over the shelf; any later row slides up into a full grid. */
    private void placeGrid(boolean animate) {
        int r = row();
        float ty = r == 0 ? 0 : H * 0.42f - grid.rowTop(r);
        boolean full = r > 0;
        if (animate) {
            grid.animate().translationY(ty).setDuration(300).setInterpolator(new DecelerateInterpolator()).start();
            titles.animate().alpha(full ? 0f : 1f).setDuration(200).start();
        } else {
            grid.setTranslationY(ty);
            titles.setAlpha(full ? 0f : 1f);
        }
        gallery.setGridMode(full);
        gallery.setGlassShown(!full);
        updateGreeting();
    }

    /**
     * macOS dock magnification: the focused tile grows to 1.5x, its neighbours a little, and
     * every tile slides so none overlap; the glass widens with them. One animator moves all of it
     * along the same curve. Off (all 1x) while focus is in the top bar or the rows below.
     */
    private void magnify(boolean animate) {
        int n = dockScale.length;
        if (n == 0) return;
        boolean on = !inTop && row() == 0;
        float[] scale = new float[n], shift = new float[n];
        float total = 0;
        for (int i = 0; i < n; i++) {
            int d = Math.abs(i - focus);
            scale[i] = on && d < MAGNIFY.length ? MAGNIFY[d] : 1f;
            total += grid.tileW * scale[i];
        }
        // Keep the grown dock inside the TV-safe area (80 px a side at 1080p): overscan can crop
        // anything nearer the edge. Squeeze every tile alike, so the magnification curve survives.
        float room = W - 2 * px(80) - 2 * dockPad - grid.gap * (n - 1);
        if (total > room) {
            float k = room / total;
            total = 0;
            for (int i = 0; i < n; i++) {
                scale[i] *= k;
                total += grid.tileW * scale[i];
            }
        }
        total += grid.gap * (n - 1);
        float x = W / 2f - total / 2f;
        for (int i = 0; i < n; i++) {
            float w = grid.tileW * scale[i];
            float baseCentre = grid.left + i * (grid.tileW + grid.gap) + grid.tileW / 2f;
            shift[i] = x + w / 2f - baseCentre;
            x += w + grid.gap;
        }
        float toLeft = W / 2f - total / 2f - dockPad, toRight = W / 2f + total / 2f + dockPad;
        if (magnifier != null) magnifier.cancel();
        if (!animate) {
            applyDock(scale, shift, toLeft, toRight);
            return;
        }
        final float[] fromScale = dockScale.clone(), fromShift = dockShift.clone();
        final float fromLeft = glassLeft, fromRight = glassRight;
        magnifier = ValueAnimator.ofFloat(0f, 1f).setDuration(MAGNIFY_MS);
        magnifier.setInterpolator(new DecelerateInterpolator(1.6f));
        magnifier.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float[] sc = new float[n], sh = new float[n];
            for (int i = 0; i < n; i++) {
                sc[i] = fromScale[i] + (scale[i] - fromScale[i]) * f;
                sh[i] = fromShift[i] + (shift[i] - fromShift[i]) * f;
            }
            applyDock(sc, sh, fromLeft + (toLeft - fromLeft) * f, fromRight + (toRight - fromRight) * f);
        });
        magnifier.start();
    }

    private void applyDock(float[] scale, float[] shift, float left, float right) {
        for (int i = 0; i < scale.length && i < grid.tiles(); i++) {
            Tile t = grid.tile(i);
            t.setScaleX(scale[i]);
            t.setScaleY(scale[i]);
            t.setTranslationX(shift[i]);
        }
        dockScale = scale;
        dockShift = shift;
        glassLeft = left;
        glassRight = right;
        gallery.setGlassSpan(left, right);
    }

    private void enterTop() {
        inTop = true;
        topFocus = pills.size() - 1;
        if (!apps.isEmpty()) grid.tile(focus).setFocus(false, 0, 0);
        for (TextView p : pills) p.animate().alpha(1f).setDuration(200).start();
        paintPills();
        magnify(true);
    }

    private void leaveTop() {
        inTop = false;
        for (TextView p : pills) p.animate().alpha(0f).setDuration(200).start();
        if (!apps.isEmpty()) grid.tile(focus).setFocus(true, 0, 1);
        magnify(true);
    }

    private void topKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (topFocus > 0) topFocus--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (topFocus < pills.size() - 1) topFocus++;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                leaveTop();
                return;
            case KeyEvent.KEYCODE_DPAD_UP:
                return;
            default:
                pillActions.get(topFocus).run();
                return;
        }
        paintPills();
    }

    private void paintPills() {
        for (int i = 0; i < pills.size(); i++) {
            TextView p = pills.get(i);
            boolean on = i == topFocus;
            p.setBackground(on ? round(0xFFFFFFFF, 0) : round(0x40000000, 0x26FFFFFF));
            p.setTextColor(on ? 0xFF111111 : 0xFFFFFFFF);
            p.animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).setDuration(160).start();
        }
    }

    // ---- shelf and titles ----

    /** Starts the focused app's slideshow: what it last played, trending titles, photos. */
    private void updateShelf() {
        if (apps.isEmpty()) return;
        Apps.App a = apps.get(focus);
        if (a.pkg.equals(shelfKey)) return;
        shelfKey = a.pkg;
        fadeTint(Apps.tint(a.color));
        gallery.setFallback(a.haze);
        List<Feed.Item> items = Feed.items(this, a.pkg, a.label);
        File clip = items.isEmpty() ? null : items.get(0).video;
        if (clip != null) {
            items = items.subList(0, 1);    // the clip's still: text and the glass's frost
            play(clip);
        } else {
            stopVideo();
        }
        gallery.setItems(items);
    }

    /** Plays {@code clip} muted and looping under the gallery, from its first rendered frame. */
    private void play(File clip) {
        if (clip.equals(playing) && player != null) return;
        releasePlayer();
        playing = clip;
        if (surfaceReady) startPlayer(clip, video.getHolder());
    }

    private void startPlayer(File clip, SurfaceHolder holder) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(clip.getPath());
            mp.setDisplay(holder);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) gallery.setVideoMode(true);
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                stopVideo();
                return true;
            });
            mp.prepareAsync();
            player = mp;
        } catch (IOException | RuntimeException e) {
            mp.release();
            playing = null;
        }
    }

    private void releasePlayer() {
        gallery.setVideoMode(false);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void stopVideo() {
        playing = null;
        releasePlayer();
    }

    /** A slide started: show its logo or title, the overline above and the caption below. */
    /** Whether a logo's visible pixels are dark on average (relative luminance under 0.18). */
    private static boolean dark(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 64, Math.max(1, 64 * b.getHeight() / b.getWidth()), true);
        double sum = 0, weight = 0;
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int p = s.getPixel(x, y);
                double a = (p >>> 24) / 255.0;
                if (a < 0.5) continue;
                sum += a * (0.2126 * lin((p >> 16) & 0xFF) + 0.7152 * lin((p >> 8) & 0xFF) + 0.0722 * lin(p & 0xFF));
                weight += a;
            }
        }
        if (s != b) s.recycle();
        return weight > 0 && sum / weight < 0.18;
    }

    private static double lin(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private void onSlide(Feed.Item item, Bitmap logoArt) {
        // Quiet by default: just the title (or its logo). Overline and caption only where they
        // carry information: Continue watching, F1 times, the artist under an album.
        boolean showOver = item != null && item.is(Feed.OVERLINE) && Feed.has(item.overline);
        overline.setText(showOver ? item.overline.toUpperCase() : "");
        overline.setVisibility(showOver ? View.VISIBLE : View.GONE);
        String name = item == null ? "" : item.title;
        logo.setImageBitmap(logoArt);
        logo.setVisibility(logoArt != null ? View.VISIBLE : View.GONE);
        // A dark title treatment (Inception's deep red) can't reach 3:1 over dark art however much
        // we shade it; show those in white, as the Apple TV app does. Bright ones keep their colour.
        logo.setColorFilter(logoArt != null && dark(logoArt)
                ? new android.graphics.PorterDuffColorFilter(0xF2FFFFFF, android.graphics.PorterDuff.Mode.SRC_IN) : null);
        title.setText(name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(name.length() > 20 ? 68 : 88));
        title.setVisibility(logoArt == null && Feed.has(name) ? View.VISIBLE : View.GONE);
        String caption = item != null && item.is(Feed.CAPTION) ? item.caption : "";
        detail.setText(caption.replace(Feed.LINE, "\n"));
        detail.setVisibility(Feed.has(caption) ? View.VISIBLE : View.GONE);
        if (!greeting && row() == 0) {
            titles.setAlpha(0f);
            titles.setTranslationY(px(12));
            titles.animate().alpha(1f).translationY(0).setDuration(320).start();
        }
    }

    private void showWeather() {
        Feed.Weather w = Feed.weather(this);
        if (w == null) {
            weatherPill.setVisibility(View.GONE);
            return;
        }
        weatherPill.setVisibility(View.VISIBLE);
        weatherPill.setText(Math.round(w.temp) + "°");
        weatherPill.setCompoundDrawables(new WeatherIcon(w.code, w.day, px(34)), null, null, null);
    }

    private void fadeTint(int to) {
        if (tintAnim != null) tintAnim.cancel();
        tintAnim = ValueAnimator.ofObject(new ArgbEvaluator(), tint, to).setDuration(300);
        tintAnim.addUpdateListener(an -> {
            tint = (int) an.getAnimatedValue();
            lastTint = tint;
            gallery.setBase(tint);
        });
        tintAnim.start();
    }

    // ---- greeting ----

    private void showGreeting() {
        greeting = true;
        Calendar now = Calendar.getInstance();
        helloHead.setText(greeting(now));
        helloLine.setText(words.line(now, apps));
        titles.animate().alpha(0f).setDuration(200).start();
        hello.setTranslationY(px(18));
        hello.animate().alpha(1f).translationY(0).setDuration(450).setInterpolator(new DecelerateInterpolator()).start();
        ui.removeCallbacks(nextLine);
        ui.postDelayed(nextLine, LINE_EVERY_MS);
    }

    private String greeting(Calendar now) {
        return words.headline(name(), now);
    }

    private void nextLine() {
        if (!greeting) return;
        helloLine.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            helloLine.setText(words.line(Calendar.getInstance(), apps));
            helloLine.animate().alpha(0.9f).setDuration(350).start();
        }).start();
        ui.postDelayed(nextLine, LINE_EVERY_MS);
    }

    private void hideGreeting() {
        greeting = false;
        ui.removeCallbacks(nextLine);
        hello.animate().alpha(0f).translationY(-px(12)).setDuration(220).start();
        if (row() == 0) titles.animate().alpha(1f).setDuration(260).setStartDelay(120).start();
    }

    /** Shows the greeting while YouTube is focused in the dock, and hides it everywhere else. */
    private void updateGreeting() {
        boolean want = !apps.isEmpty() && YOUTUBE.equals(current()) && row() == 0;
        if (want && !greeting) showGreeting();
        else if (!want && greeting) hideGreeting();
    }

    // ---- actions ----

    private void open(Apps.App a) {
        launch(new Intent(a.intent));
    }

    private void launch(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home; the TV
     * settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS");
        launch(tv.resolveActivity(getPackageManager()) != null ? tv : new Intent(Settings.ACTION_SETTINGS));
    }

    private List<TvInputInfo> inputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tv = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tv == null) return out;
        for (TvInputInfo in : tv.getTvInputList()) {
            if (in.isPassthroughInput() && !in.isHidden(this)) out.add(in);
        }
        return out;
    }

    private void openInputs() {
        final List<TvInputInfo> list = inputs();
        CharSequence[] names = new CharSequence[list.size()];
        for (int i = 0; i < names.length; i++) {
            CharSequence l = list.get(i).loadCustomLabel(this);
            names[i] = TextUtils.isEmpty(l) ? list.get(i).loadLabel(this) : l;
        }
        dialog().setTitle("Inputs").setItems(names, (d, i) -> launch(new Intent(Intent.ACTION_VIEW,
                TvContract.buildChannelUriForPassthroughInput(list.get(i).getId())))).show();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    /** Menu or long-press OK on a tile. */
    private void menu() {
        if (apps.isEmpty()) return;
        final Apps.App a = apps.get(focus);
        List<String> hidden = Apps.hidden(this);
        final String[] items = {"Move left", "Move right", "Move to front", "Hide from Home",
                "App info", hidden.isEmpty() ? "No hidden apps" : "Show hidden apps (" + hidden.size() + ")",
                "Open Amazon's home"};
        dialog().setTitle(a.label).setItems(items, (d, i) -> {
            switch (i) {
                case 0: reorder(a, focus - 1); break;
                case 1: reorder(a, focus + 1); break;
                case 2: reorder(a, 0); break;
                case 3:
                    Apps.setHidden(this, a.pkg, true);
                    reload();
                    break;
                case 4:
                    launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                    break;
                case 5:
                    for (String p : Apps.hidden(this)) Apps.setHidden(this, p, false);
                    reload();
                    break;
                default:
                    Guard.amazon(this);
            }
        }).show();
    }

    private void reorder(Apps.App a, int to) {
        if (to < 0 || to >= apps.size()) return;
        List<Apps.App> order = new ArrayList<>(apps);
        order.remove(a);
        order.add(to, a);
        Apps.saveOrder(this, order);
        reload();
    }
}
