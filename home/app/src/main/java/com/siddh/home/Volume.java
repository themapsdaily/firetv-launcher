package com.siddh.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.util.Log;

/**
 * Half volume steps (ROADMAP section 3). The stick's media volume has 15 steps, and on the owner's
 * Bluetooth speaker 11 is too quiet and 12 too loud. Each press of the remote's volume keys moves
 * half a step instead: "11½" is the stick at 12 with a small cut on the whole output mix (audio
 * session 0), so it works in every app that plays through the mixer.
 *
 * On Android TV builds the window manager handles the volume keys before any accessibility
 * filter (KeyboardInterceptor drops them), so Guard never sees them. Fire OS makes its whole step
 * and this follows the volume-changed broadcast: each step is read as half a step, and the stick
 * is put back on the step the half level needs. The step is heard for a moment before that.
 * Fire OS's own volume bar shows the stick's whole step (11½ shows as 12). A pill of our own
 * showed the half level, but Fire OS's bar cannot be hidden and two bars at once was too much.
 *
 * The cut is a DynamicsProcessing input gain, or an Equalizer with every band lowered where that
 * is refused. Session 0 effects are deprecated but still work on Android 9; if neither can be made,
 * Fire OS keeps its own whole steps.
 *
 * At the top, a press up from 14 goes straight to 15: from there Fire OS cannot step any higher,
 * so no broadcast would come for 14½ → 15. A jump of more than one step (an output change such as
 * the speaker connecting, or another app) starts again from the stick's step. The level is
 * remembered per app and set again when that app comes back in front, if it is within two steps
 * of the current one.
 *
 * Settings, as Home launch extras: {@code --ez volume false} leaves Fire OS's whole steps alone;
 * {@code --es volume_half_db 2} sets the size of the cut (default 1.5 dB, about half of the
 * stick's step near the top of its range).
 */
final class Volume {
    private static final String TAG = "HomeVolume";
    private static final int STREAM = AudioManager.STREAM_MUSIC;
    private static final float HALF_DB = 1.5f;
    private static final int RESTORE_HALVES = 4;
    // AudioManager's hidden broadcast and extras.
    private static final String CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";

    private final Context c;
    private final AudioManager audio;
    private final int max;
    private AudioEffect effect;
    /** The level in half steps, 0 to 2 × max. */
    private int half;
    private float cutDb;
    private String front = "";
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent i) {
            if (i.getIntExtra(EXTRA_STREAM, -1) == STREAM) stepped(i.getIntExtra(EXTRA_VALUE, -1));
        }
    };

    Volume(Context service) {
        c = service;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        max = audio.getStreamMaxVolume(STREAM);
        effect = makeEffect();
        half = 2 * audio.getStreamVolume(STREAM);
        if (effect != null) c.registerReceiver(changed, new IntentFilter(CHANGED));
    }

    private static AudioEffect makeEffect() {
        try {
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    false, 0, false, 0, false, 0, false).build();
            DynamicsProcessing dp = new DynamicsProcessing(Integer.MAX_VALUE, 0, cfg);
            dp.setInputGainAllChannelsTo(0f);
            dp.setEnabled(true);
            Log.i(TAG, "cut: DynamicsProcessing on session 0");
            return dp;
        } catch (RuntimeException e) {
            Log.w(TAG, "DynamicsProcessing refused: " + e);
        }
        try {
            Equalizer eq = new Equalizer(Integer.MAX_VALUE, 0);
            eq.setEnabled(true);
            Log.i(TAG, "cut: Equalizer on session 0");
            return eq;
        } catch (RuntimeException e) {
            Log.w(TAG, "Equalizer refused: " + e);
        }
        return null;
    }

    void stop() {
        if (effect != null) {
            c.unregisterReceiver(changed);
            effect.release();
        }
        effect = null;
    }

    /** The app in front changed (Guard's window events). */
    void foreground(String pkg) {
        if ("com.android.systemui".equals(pkg) || "android".equals(pkg) || pkg.equals(front)) return;
        front = pkg;
        int saved = Apps.prefs(c).getInt("volume.app." + pkg, -1);
        // Only near the current level: a level kept on another output must not blast this one.
        if (effect != null && saved >= 0 && Math.abs(saved - half) <= RESTORE_HALVES && !off()) {
            apply(Math.min(saved, 2 * max));
        }
    }

    /** Fire OS moved the stick to step {@code index}: by a key press, or back to where we put it. */
    private void stepped(int index) {
        int want = (half + 1) / 2;
        if (index < 0 || index == want) return;
        if (off()) {
            half = 2 * index;
            cut(0f);
            return;
        }
        if (Math.abs(index - want) != 1) {
            apply(2 * index);
            return;
        }
        apply(index == max && index > want ? 2 * max : half + (index > want ? 1 : -1));
        if (!front.isEmpty()) Apps.prefs(c).edit().putInt("volume.app." + front, half).apply();
    }

    private boolean off() {
        return Apps.prefs(c).getBoolean("volume.off", false);
    }

    /** An odd level is the step above with the cut: 23 is the stick at 12, heard as "11½". */
    private void apply(int to) {
        half = to;
        int index = (to + 1) / 2;
        if (audio.getStreamVolume(STREAM) != index) audio.setStreamVolume(STREAM, index, 0);
        cut(to % 2 == 1 ? -Apps.prefs(c).getFloat("volume.half_db", HALF_DB) : 0f);
    }

    private void cut(float db) {
        if (effect == null || db == cutDb) return;
        try {
            if (effect instanceof DynamicsProcessing) {
                ((DynamicsProcessing) effect).setInputGainAllChannelsTo(db);
            } else {
                Equalizer eq = (Equalizer) effect;
                short min = eq.getBandLevelRange()[0];
                short level = (short) Math.max(min, Math.round(db * 100));
                for (short b = 0; b < eq.getNumberOfBands(); b++) eq.setBandLevel(b, level);
            }
            cutDb = db;
        } catch (RuntimeException e) {
            // The audio server restarted under us; make the effect again on the next press.
            Log.w(TAG, "cut failed: " + e);
            effect.release();
            effect = makeEffect();
            cutDb = 0f;
        }
    }
}
