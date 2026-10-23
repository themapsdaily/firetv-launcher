// Ported from dumbtv (https://github.com/humblemuzzu/dumbtv, launcher/src/tv/dumbtv/home/Guard.java),
// MIT License, Copyright (c) 2026 dumbtv contributors. The Fire OS 6 Keeper path is left out.
package com.siddh.home;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS keeps HOME bound to Amazon's protected launcher and handles the HOME key before any
 * accessibility filter sees it, so this service waits for Amazon's home window and replaces it.
 *
 * A plain startActivity right after a HOME press is held back by Android's five-second
 * app-switch delay. An alarm-fired PendingIntent is sent by system_server, which is exempt,
 * so {@link #home} launches through an alarm. That alarm only fires at once when the global
 * {@code alarm_manager_constants} has {@code min_futurity=0}; install.sh sets it.
 *
 * Amazon's home is covered with a plain overlay until ours has drawn, so it barely shows.
 *
 * The remote's app buttons go through the Appstore's AppLaunchActivity. For an app that is disabled
 * (Amazon Music, after the debloat) the Appstore then shows its details page; when that page follows
 * a button launch within {@link #BUTTON_MS}, Spotify opens instead. Idea from bansiman's Home Return
 * (https://github.com/bansiman/cleanyourtv/tree/my-tv, MIT).
 */
public final class Guard extends AccessibilityService {
    static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long COVER_MAX_MS = 1500;
    private static final long REPEAT_MS = 1000;
    private static final long LAUNCH_FALLBACK_MS = 150;
    private static final String APP_LAUNCH = "com.amazon.venezia.pdi.AppLaunchActivity";
    private static final String APP_DETAILS = "com.amazon.venezia.details.AppDetailsActivity";
    private static final ComponentName MUSIC_BUTTON =
            new ComponentName("com.spotify.tv.android", "com.spotify.tv.android.SpotifyTVActivity");
    private static final long BUTTON_MS = 5000;

    private static volatile boolean paused;
    private static Guard running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private final Runnable launch = () -> home(this);
    private View cover;
    private long lastLaunch;
    private long buttonLaunch;

    /** Lets Amazon's home stay on screen until the user leaves it. */
    static void pause() {
        paused = true;
    }

    static void home(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), launch(c));
    }

    /** Drops a queued launch, so it cannot pull the user back after they opened an app. */
    static void arrived(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(launch(c));
    }

    /** Called once our home window is on screen. */
    static void shown() {
        if (running != null) running.uncover();
    }

    private static PendingIntent launch(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, Home.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    static void amazon(Context c) {
        pause();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(AMAZON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            paused = false;
        }
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        View view = new View(this);
        view.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
        cover = view;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(uncover);
        handler.removeCallbacks(launch);
        if (cover != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        if (cls != null && APP_LAUNCH.contentEquals(cls)) {
            buttonLaunch = SystemClock.uptimeMillis();
        } else if (cls != null && APP_DETAILS.contentEquals(cls) && buttonLaunch != 0
                && SystemClock.uptimeMillis() - buttonLaunch < BUTTON_MS) {
            buttonLaunch = 0;
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).setComponent(MUSIC_BUTTON)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (ActivityNotFoundException ignored) {
            }
            return;
        }
        if (!AMAZON.contentEquals(pkg)) {
            if ("com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            paused = false;
            if (!getPackageName().contentEquals(pkg)) arrived(this);
            return;
        }
        if (paused || cls == null || !AMAZON_HOME.contentEquals(cls)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < REPEAT_MS) return;
        lastLaunch = now;
        launchWhenCovered();
    }

    /**
     * Shows the cover, then starts the launch once that window is visible to the window manager
     * (Android 11 refuses a background start from an app with no visible window).
     */
    private void launchWhenCovered() {
        handler.removeCallbacks(launch);
        if (cover == null) {
            home(this);
            return;
        }
        boolean shown = cover.getVisibility() == View.VISIBLE;
        cover();
        if (shown) {
            home(this);
            return;
        }
        cover.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                cover.getViewTreeObserver().removeOnPreDrawListener(this);
                handler.removeCallbacks(launch);
                home(Guard.this);
                return true;
            }
        });
        handler.postDelayed(launch, LAUNCH_FALLBACK_MS);
    }

    /** The overlay window exists from service start; a GONE root keeps its surface hidden. */
    private void cover() {
        if (cover == null) return;
        cover.setBackgroundColor(Home.lastTint);
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MAX_MS);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    @Override
    public void onInterrupt() {}
}
