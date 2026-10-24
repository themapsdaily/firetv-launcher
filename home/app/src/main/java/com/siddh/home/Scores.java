package com.siddh.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The score pill in the top-left corner, over Home and every other app (ROADMAP sections 5 and 6),
 * and the polling behind it.
 *
 * It lives in {@link Guard}, the accessibility service that is always bound. Guard's
 * TYPE_ACCESSIBILITY_OVERLAY windows need no "display over other apps" permission, never take
 * remote focus, and Guard's window events say which app is in front, so FanCode is left alone
 * during F1. Nothing on screen is read.
 *
 * What it shows, in order: a live followed match ("NZ 123/5 (11.2) vs IND" after a dot in the
 * sport's colour), else the F1 session countdown ({@link Countdown}), else nothing. Small, so it
 * stays out of the way over a film. It fades to half strength after a few seconds and comes back
 * when the score or state changes.
 *
 * Over another app the remote's menu button (three lines) closes it until Home is back in front.
 * Guard filters key events for this and passes every other key, and menu while the pill is
 * hidden, through untouched.
 *
 * Network only when there is a reason: fixtures once a day, live scores only inside a window
 * around a followed match ({@link Sports#pollable}). The F1 countdown is worked out on the stick.
 *
 * Settings, as Home launch extras: {@code --ez scores false} turns the pill off;
 * {@code --es scores_hide <pkg,pkg>} keeps it off over those apps;
 * {@code --es f1_sessions Race,Qualifying} limits the countdown to those sessions.
 */
final class Scores {
    private static final String FANCODE = "com.fancode.tv";
    private static final long CRICKET_EVERY_MS = 45_000, TENNIS_EVERY_MS = 60_000;
    private static final long LIVE_TICK_MS = 15_000, IDLE_TICK_MS = 60_000;
    private static final long DIM_AFTER_MS = 5000;
    private static final float DIM = 0.5f;

    private final Context c;
    private final float s;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final WindowManager wm;
    private final LinearLayout pill;
    private final View dot;
    private final TextView text;
    private final Map<String, Long> polled = new HashMap<>();
    private volatile boolean fetching;
    private String front = "", frontClass = "";
    private String lastChange = "";
    /** Closed with the menu button; open again once Home is in front. */
    private boolean closed;
    private boolean menuDown;

    private final Runnable tick = this::tick;
    private final Runnable dim = () -> pillAlpha(DIM, 600);

    Scores(Context service) {
        c = service;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        s = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);

        pill = new LinearLayout(c);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(px(14), px(8), px(16), px(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(12));
        bg.setColor(0xE60A0B0D);     // a floating tile, like Home's status bar
        bg.setStroke(Math.max(1, px(1)), 0x1AFFFFFF);
        pill.setBackground(bg);

        dot = new View(c);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        dot.setBackground(round);
        pill.addView(dot, new LinearLayout.LayoutParams(px(9), px(9)));

        text = new TextView(c);
        text.setTextColor(0xFFF4F5F7);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22));
        text.setTypeface(Fonts.file(c, "BarlowCondensed-SemiBold"));
        text.setLetterSpacing(0.02f);
        text.setIncludeFontPadding(false);
        text.setSingleLine();
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(-2, -2);
        xl.leftMargin = px(10);
        pill.addView(text, xl);

        pill.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // Inside the TV-safe area, level with Home's clock pill on the right.
        lp.x = px(80);
        lp.y = px(52);
        lp.windowAnimations = 0;
        wm.addView(pill, lp);
    }

    private int px(float v) {
        return Math.round(v * s);
    }

    void start() {
        ui.post(tick);
    }

    void stop() {
        ui.removeCallbacksAndMessages(null);
        net.shutdownNow();
        try {
            wm.removeView(pill);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** The app in front changed (Guard's window events). */
    void foreground(String pkg, String cls) {
        if ("com.android.systemui".equals(pkg) || "android".equals(pkg)) return;
        front = pkg;
        frontClass = cls;
        if (c.getPackageName().equals(pkg) && cls.endsWith(".Home")) closed = false;
        render();
    }

    /** A remote key, from Guard's key filter. Returns true to keep it from the app in front. */
    boolean key(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_MENU) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) return menuDown;
            menuDown = pill.getVisibility() == View.VISIBLE && !c.getPackageName().equals(front);
            if (menuDown) ui.post(() -> {
                closed = true;
                render();
            });
            return menuDown;
        }
        boolean mine = menuDown;
        menuDown = false;
        return mine;
    }

    /** Anything new: fetch what is due, redraw, and come back when something can change. */
    private void tick() {
        ui.removeCallbacks(tick);
        fetch(System.currentTimeMillis());
        ui.postDelayed(tick, render());
    }

    private void fetch(long now) {
        if (fetching) return;
        Sports.Data d = Sports.load(c);
        final boolean daily = Sports.stale(d, now);
        final List<String> due = new ArrayList<>();
        for (String sport : Sports.pollable(d, now)) {
            long every = Sports.CRICKET.equals(sport) ? CRICKET_EVERY_MS : TENNIS_EVERY_MS;
            Long at = polled.get(sport);
            if (at == null || now - at >= every - 1000) {
                due.add(sport);
                polled.put(sport, now);
            }
        }
        if (!daily && due.isEmpty()) return;
        fetching = true;
        net.execute(() -> {
            try {
                if (daily) Sports.daily(c);
                for (String sport : due) Sports.live(c, sport);
            } finally {
                fetching = false;
                ui.post(this::render);
            }
        });
    }

    /** Shows what matters now; returns how soon to look again. */
    private long render() {
        long now = System.currentTimeMillis();
        Sports.Data d = Sports.load(c);
        long next = Sports.pollable(d, now).isEmpty() ? IDLE_TICK_MS : LIVE_TICK_MS;
        if (!wanted()) {
            hide();
            return next;
        }
        Sports.Fixture f = Sports.headline(d, now);
        if (f != null) {
            show(f.sport, true, Sports.pillText(f), f.id + f.change());
            return next;
        }
        Countdown.State cd = Countdown.at(Sports.sessions(c, d), now, FANCODE.equals(front));
        if (cd.kind == Countdown.Kind.HIDDEN) {
            hide();
        } else {
            // Keyed on the state, not the ticking text, so the pill dims and only pops back on a new stage.
            show(Sports.F1, cd.kind == Countdown.Kind.UNDERWAY, cd.text, cd.kind + cd.session.name + cd.session.start);
        }
        return Math.max(100, Math.min(next, cd.next));
    }

    private boolean wanted() {
        SharedPreferences p = Apps.prefs(c);
        if (closed || p.getBoolean("scores.off", false)) return false;
        // The Sports view shows it all already.
        if (c.getPackageName().equals(front) && frontClass.endsWith(".Scoreboard")) return false;
        for (String pkg : p.getString("scores.hide", "").split(",")) {
            if (!pkg.trim().isEmpty() && pkg.trim().equals(front)) return false;
        }
        return true;
    }

    private void show(String sport, boolean live, String words, String change) {
        GradientDrawable round = (GradientDrawable) dot.getBackground();
        round.setColor(Sports.colour(sport));
        // A match or session that is on gets a solid dot; a countdown a faint one.
        round.setAlpha(live ? 255 : 90);
        text.setText(words);
        boolean appearing = pill.getVisibility() != View.VISIBLE;
        if (!appearing && change.equals(lastChange)) return;
        lastChange = change;
        if (appearing) {
            pill.setAlpha(0f);
            pill.setVisibility(View.VISIBLE);
        }
        pillAlpha(1f, 250);
        ui.removeCallbacks(dim);
        ui.postDelayed(dim, DIM_AFTER_MS);
    }

    private void hide() {
        ui.removeCallbacks(dim);
        lastChange = "";
        if (pill.getVisibility() == View.VISIBLE) {
            pill.animate().cancel();
            pill.setVisibility(View.GONE);
        }
    }

    private void pillAlpha(float to, long ms) {
        pill.animate().cancel();
        pill.animate().alpha(to).setDuration(ms).start();
    }
}
