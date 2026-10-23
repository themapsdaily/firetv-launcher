package com.siddh.home;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers what each app last played, for the shelf's "Continue watching".
 *
 * Android only hands the list of media sessions to an enabled notification listener, which is
 * the only reason this is one; notifications themselves are never read. Whenever an app's
 * session publishes artwork, it is saved to files/watched/<package>.jpg with the title next to
 * it in <package>.txt. Nothing leaves the device: the app has no INTERNET permission.
 */
public final class Watcher extends NotificationListenerService {
    /** Set by Home while it is on screen; called on the main thread after a save. */
    static Runnable onChange;
    /** Set by the screensaver; called on the main thread when anything starts, stops or changes track. */
    static Runnable onPlayback;
    private static Watcher self;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> watching = new HashMap<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    /** The session that is playing right now, or null. Main thread. */
    static MediaController playing() {
        if (self == null) return null;
        for (MediaController mc : self.watching.keySet()) {
            PlaybackState st = mc.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc;
        }
        return null;
    }

    @Override
    public void onListenerConnected() {
        self = this;
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(this, Watcher.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, main);
            track(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            sessions = null;    // listener access was revoked between connect and now
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (Map.Entry<MediaController, MediaController.Callback> e : watching.entrySet()) {
            e.getKey().unregisterCallback(e.getValue());
        }
        watching.clear();
        if (self == this) self = null;
    }

    private void track(List<MediaController> list) {
        if (list == null) return;
        for (MediaController old : new ArrayList<>(watching.keySet())) {
            if (!list.contains(old)) old.unregisterCallback(watching.remove(old));
        }
        for (MediaController mc : list) {
            if (watching.containsKey(mc)) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata m) {
                    save(mc.getPackageName(), m);
                    if (onPlayback != null) onPlayback.run();
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    if (onPlayback != null) onPlayback.run();
                }
            };
            mc.registerCallback(cb, main);
            watching.put(mc, cb);
            save(mc.getPackageName(), mc.getMetadata());
        }
        if (onPlayback != null) onPlayback.run();
    }

    private void save(String pkg, MediaMetadata m) {
        if (m == null || pkg == null) return;
        Bitmap art = first(m, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String sub = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        if (art == null || art.getWidth() < 200 || TextUtils.isEmpty(title)) return;
        File dir = dir(this);
        if (dir == null) return;
        try (FileOutputStream out = new FileOutputStream(new File(dir, pkg + ".jpg"))) {
            art.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (IOException e) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, pkg + ".txt"))) {
            String s = title + "\n" + (sub == null ? "" : sub) + "\n" + System.currentTimeMillis();
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        if (onChange != null) onChange.run();
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "watched");
        return d.isDirectory() || d.mkdirs() ? d : null;
    }

    private static Bitmap first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence s = m.getText(k);
            if (!TextUtils.isEmpty(s)) return s.toString();
        }
        return null;
    }
}
