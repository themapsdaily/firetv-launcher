package com.siddh.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The screensaver, after the iOS lock screen: a landscape photo drifting slowly, the date, a giant
 * clock, the weather, and a glass "now playing" bar while music or a video plays. Barlow Condensed,
 * like Home's status bar.
 */
public final class Dream extends DreamService {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private Gallery gallery;
    private LinearLayout face, bar;
    private TextView weather, songTitle, songArtist;
    private ImageView art;
    private Progress progress;
    private RectF barRect;
    private float s;
    private int W, H;
    private boolean barShown;
    /** A small anti burn-in drift of the face. */
    private float driftX, driftY;

    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            // Shift the clock a little every minute so it never burns into the panel.
            driftX = (random.nextFloat() - 0.5f) * 40 * s;
            driftY = (random.nextFloat() - 0.5f) * 30 * s;
            place(3000);
            ui.postDelayed(this, 60_000);
        }
    };

    /** Moves the face to the current drift and bar lift. */
    private void place(long ms) {
        float y = driftY + (barShown ? -px(40) : 0);
        face.animate().translationX(driftX).translationY(y).setDuration(ms).start();
    }
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (barShown) ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = Math.max(dm.widthPixels, dm.heightPixels);
        H = Math.min(dm.widthPixels, dm.heightPixels);
        s = W / 1920f;
        setContentView(build());
        List<Feed.Item> pool = Feed.pool(this);
        Collections.shuffle(pool);
        gallery.setItems(pool);
        showWeather();
        Feed.refresh(this, () -> ui.post(this::showWeather));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        gallery.start();
        Watcher.onPlayback = this::updateBar;
        updateBar();
        ui.postDelayed(drift, 60_000);
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        Watcher.onPlayback = null;
        gallery.stop();
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDetachedFromWindow() {
        gallery.release();
        super.onDetachedFromWindow();
    }

    private int px(float v) {
        return Math.round(v * s);
    }

    private Typeface outfit(int weight) {
        return Fonts.outfit(this, weight);
    }

    private TextView text(float size, int weight, int color) {
        TextView t = new TextView(this);
        t.setTypeface(outfit(weight));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(px(18), 0, px(3), 0x99000000);
        return t;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        gallery = new Gallery(this);
        gallery.setTiming(40_000, 2500, 1.02f, 0.06f, 1920);
        gallery.setFrameMs(50);     // the drift is slow enough that 20 fps looks the same
        gallery.setScrims(false);
        // A soft dark pool behind the clock and weather, so they never melt into a bright photo.
        gallery.setSpot(W / 2f, H * 0.30f, W * 0.46f, 1f);
        root.addView(gallery, new FrameLayout.LayoutParams(-1, -1));

        // The iOS lock screen: date, a big clock, then the weather.
        face = face();
        root.addView(face, faceParams());

        // Now playing: a glass bar near the bottom. Gallery draws the glass; these sit on it.
        int bw = Math.round(W * 0.44f), bh = px(132);
        int bx = (W - bw) / 2, by = H - px(72) - bh;
        barRect = new RectF(bx, by, bx + bw, by + bh);
        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(18), px(18), px(32), px(18));
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(14));
            }
        });
        art.setClipToOutline(true);
        bar.addView(art, new LinearLayout.LayoutParams(bh - px(36), bh - px(36)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        songTitle = text(32, 600, 0xFFFFFFFF);
        songTitle.setSingleLine(true);
        songTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        songArtist = text(26, 400, 0xB3FFFFFF);
        songArtist.setSingleLine(true);
        songArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        progress = new Progress(this);
        words.addView(songTitle);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = px(6);
        words.addView(songArtist, al);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, px(6));
        pl.topMargin = px(16);
        words.addView(progress, pl);
        LinearLayout.LayoutParams wds = new LinearLayout.LayoutParams(0, -2, 1f);
        wds.leftMargin = px(24);
        bar.addView(words, wds);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(bw, bh);
        bl.leftMargin = bx;
        bl.topMargin = by;
        bar.setAlpha(0f);
        root.addView(bar, bl);
        gallery.setGlass(barRect, bh / 2f);
        gallery.setGlassShown(false);
        return root;
    }

    /** Date, clock and weather. */
    private LinearLayout face() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setGravity(Gravity.CENTER_HORIZONTAL);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        date.setTypeface(Fonts.file(this, "BarlowCondensed-SemiBold"));
        date.setLetterSpacing(0.02f);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(46));
        date.setTextColor(0xF2FFFFFF);
        date.setIncludeFontPadding(false);
        date.setShadowLayer(px(18), 0, px(3), 0x99000000);
        date.setGravity(Gravity.CENTER_HORIZONTAL);
        f.addView(date, new LinearLayout.LayoutParams(-1, -2));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTypeface(Fonts.file(this, "BarlowCondensed-Bold"));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.34f);
        clock.setTextColor(0xE6EEF4FF);
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(px(40), 0, px(6), 0x80000000);
        // Full width, centred text: a wrap_content clock can keep a stale width and clip a digit.
        clock.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = px(2);
        f.addView(clock, cl);
        TextView w = text(40, 500, 0xEBFFFFFF);    // a step below the date: the time leads
        w.setTypeface(Fonts.file(this, "BarlowCondensed-SemiBold"));
        w.setLetterSpacing(0.02f);
        w.setGravity(Gravity.CENTER_VERTICAL);
        w.setCompoundDrawablePadding(px(16));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.topMargin = -Math.round(H * 0.045f);     // into the clock's empty descender space
        f.addView(w, wl);
        weather = w;
        return f;
    }

    private FrameLayout.LayoutParams faceParams() {
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL);
        fl.topMargin = Math.round(H * 0.09f);
        return fl;
    }

    private void showWeather() {
        Feed.Weather w = Feed.weather(this);
        for (TextView v : new TextView[] {weather}) {
            if (w == null) {
                v.setText("");
                v.setCompoundDrawables(null, null, null, null);
                continue;
            }
            v.setText(String.format("%d°  %s  ·  H %d°  L %d°  ·  %s", Math.round(w.temp), w.describe(),
                    Math.round(w.high), Math.round(w.low), w.city));
            v.setCompoundDrawables(new WeatherIcon(w.code, w.day, px(56)), null, null, null);
        }
    }

    private void updateBar() {
        MediaController mc = Watcher.playing();
        MediaMetadata md = mc == null ? null : mc.getMetadata();
        boolean show = md != null && Feed.has(text(md, MediaMetadata.METADATA_KEY_TITLE));
        if (show) {
            songTitle.setText(text(md, MediaMetadata.METADATA_KEY_TITLE));
            String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST);
            String app = appName(mc.getPackageName());
            songArtist.setText(Feed.has(artist) ? artist + "  ·  " + app : app);
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            art.setImageBitmap(b);
            art.setVisibility(b == null ? View.GONE : View.VISIBLE);
        }
        if (show != barShown) {
            barShown = show;
            gallery.setGlassShown(show);
            bar.animate().alpha(show ? 1f : 0f).setDuration(400).start();
            // Lift the clock while the bar is up, so they never crowd each other.
            place(600);
        }
        ui.removeCallbacks(tick);
        if (show) tick.run();
    }

    private void updateProgress() {
        MediaController mc = Watcher.playing();
        if (mc == null || mc.getMetadata() == null || mc.getPlaybackState() == null) return;
        long dur = mc.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
        PlaybackState st = mc.getPlaybackState();
        long pos = st.getPosition() + (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                * st.getPlaybackSpeed());
        progress.set(dur > 0 ? Math.min(1f, pos / (float) dur) : -1f);
    }

    private static String text(MediaMetadata m, String key) {
        CharSequence c = m.getText(key);
        return c == null ? null : c.toString();
    }

    private String appName(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** A thin rounded track with a white fill; hidden when the duration is unknown. */
    private static final class Progress extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), done = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value = -1f;

        Progress(android.content.Context c) {
            super(c);
            track.setColor(0x40FFFFFF);
            done.setColor(0xE6FFFFFF);
        }

        void set(float v) {
            value = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (value < 0f) return;
            float h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, getWidth(), h, r, r, track);
            c.drawRoundRect(0, 0, Math.max(h, getWidth() * value), h, r, r, done);
        }
    }
}
