package com.siddh.home;

import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import java.io.File;
import java.util.List;

/**
 * Keeps the 1 GB stick responsive: back on the home screen, when free memory runs low or a while
 * has passed, closes the background processes of the apps on the dock and empties this app's cache.
 * Never while music plays, so Spotify keeps going in the background.
 */
final class Tidy {
    private static final String TAG = "HomeTidy";
    private static final long EVERY_MS = 30 * 60_000L;
    private static final long LOW_BYTES = 300L << 20;
    private static long lastAt;

    private Tidy() {}

    /** Call off the UI thread. */
    static void maybe(Context c, List<Apps.App> apps) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long now = System.currentTimeMillis();
        if (mi.availMem > LOW_BYTES && now - lastAt < EVERY_MS) return;
        AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && audio.isMusicActive()) return;
        lastAt = now;
        for (Apps.App a : apps) am.killBackgroundProcesses(a.pkg);
        empty(c.getCacheDir());
        File ext = c.getExternalCacheDir();
        if (ext != null) empty(ext);
        am.getMemoryInfo(mi);
        Log.i(TAG, "tidied, " + (mi.availMem >> 20) + " MB free");
    }

    private static void empty(File d) {
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            empty(k);
            k.delete();
        }
    }
}
