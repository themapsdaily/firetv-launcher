package com.siddh.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.RadialGradient;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A slideshow that slowly zooms and pans each picture and cross-fades to the next, plus an
 * optional "liquid glass" panel (the dock, or the screensaver's music bar).
 *
 * The glass is not a live blur: every slide carries a tiny pre-blurred copy of itself, and the
 * panel draws that copy with exactly the slide's current transform, clipped to its rounded shape.
 * It looks like frosted glass over moving content for the price of one more small bitmap draw.
 *
 * Frames are drawn at about 30 fps, and only while there is something moving; a single picture
 * stops redrawing once its zoom has finished.
 */
final class Gallery extends View {
    interface Listener {
        /** A new slide started; null when there is nothing to show. Main thread. */
        void onSlide(Feed.Item item, Bitmap logo);
    }

    interface DepthListener {
        /** A new slide wants the clock {@code dy} px lower (negative: higher). Main thread. */
        void onDepth(float dy);
    }

    private static final long QUICK_FADE_MS = 450;

    private static final class Slide {
        Feed.Item item;
        Bitmap bmp, frost, logo;
        long start, fade;
        float dx, dy;
        boolean card;           // square art: a sharp card over its own blur
        BitmapShader shader;
        /** Depth effect: the picture below its skyline (rows from fgTop), drawn over the clock. */
        Bitmap fg;
        int fgTop;
        /** Framing: extra zoom, vertical anchor (0 top .. 1 bottom), and how far the clock moves. */
        float zoom = 1f, anchor = 0.5f, shift;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Runnable frame = this::redraw;

    private long frameMs = 33, slideMs = 8000, fadeMs = 1200;
    private float zoomFrom = 1.04f, zoomBy = 0.08f;
    private int maxW = 1280;
    private boolean scrims = true;
    private Listener listener;

    private List<Feed.Item> items = new ArrayList<>();
    private int index, generation;
    private boolean loading, running;
    private long stoppedAt;
    private Slide cur, prev;
    private Bitmap fallback;
    private int base = 0xFF0E0F13;
    private float dim, glassAlpha = 1f;
    /** A video plays underneath: draw no pictures, only scrims and glass, so it shows through. */
    private boolean videoMode;
    private ValueAnimator dimmer, glassFader;

    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final Paint left = new Paint(), bottom = new Paint(), top = new Paint();
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private RectF glass;
    private float glassRadius;
    private final Path glassPath = new Path();
    private final Paint cardShadow = new Paint(Paint.ANTI_ALIAS_FLAG), cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF(), cardRect = new RectF();
    private final Matrix cm = new Matrix();
    private final Paint spot = new Paint();
    private boolean spotOn;
    private float spotX, spotY, spotR, spotStrength = 1f;
    private final boolean still;
    private RectF depth;
    private DepthListener depthListener;
    private View depthLayer;

    Gallery(Context c) {
        super(c);
        // "Remove animations" in Accessibility: still pictures, cross-fades only.
        still = android.provider.Settings.Global.getFloat(c.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        rim.setStyle(Paint.Style.STROKE);
        shadow.setColor(0xFF000000);
        cardShadow.setColor(0xFF000000);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Screensaver pacing: long, calm slides. */
    void setTiming(long slide, long fade, float zoomFrom, float zoomBy, int maxWidth) {
        slideMs = slide;
        fadeMs = fade;
        this.zoomFrom = zoomFrom;
        this.zoomBy = zoomBy;
        maxW = maxWidth;
    }

    /** Time between frames while pictures move; the screensaver's slow drift needs fewer. */
    void setFrameMs(long ms) {
        frameMs = ms;
    }

    /**
     * The iOS lock screen's depth effect: {@code glyphs} is where the clock's digits are on screen.
     * Pictures with a clear skyline get the part in front of it cut out, which {@link #depthLayer()}
     * draws over the clock so a mountain or skyline hides the bottom of the time. Set before
     * {@link #setItems}.
     */
    void setDepth(RectF glyphs, DepthListener l) {
        depth = glyphs;
        depthListener = l;
    }

    /** A view to stack over the clock: the current pictures' foregrounds, moving with them. */
    View depthLayer() {
        if (depthLayer == null) {
            depthLayer = new View(getContext()) {
                @Override
                protected void onDraw(Canvas c) {
                    drawDepth(c);
                }
            };
        }
        return depthLayer;
    }

    private void redraw() {
        invalidate();
        if (depthLayer != null) depthLayer.invalidate();
    }

    void setScrims(boolean on) {
        scrims = on;
    }

    /**
     * A soft dark pool behind text drawn over the pictures: the screensaver's clock (strength 1),
     * the home screen's title logos (lighter).
     */
    void setSpot(float x, float y, float radius, float strength) {
        spotOn = true;
        spotX = x;
        spotY = y;
        spotR = radius;
        spotStrength = strength;
        if (getWidth() > 0) makeSpot();
    }

    private void makeSpot() {
        int inner = Math.round(0x8C * spotStrength) << 24, mid = Math.round(0x4D * spotStrength) << 24;
        spot.setShader(new RadialGradient(spotX, spotY, spotR, new int[] {inner, mid, 0x00000000},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    void setVideoMode(boolean on) {
        if (videoMode == on) return;
        videoMode = on;
        invalidate();
    }

    void setBase(int color) {
        base = color;
        invalidate();
    }

    /** What to show (stretched, as a colour field) when there are no pictures. */
    void setFallback(Bitmap haze) {
        fallback = haze;
        invalidate();
    }

    void setGlass(RectF rect, float radius) {
        glass = rect;
        glassRadius = radius;
        glassPath.reset();
        if (rect == null) return;
        glassPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        float h = rect.height();
        sheen.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.6f,
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setStrokeWidth(Math.max(1.5f, h * 0.012f));
        rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[] {0xB3FFFFFF, 0x26FFFFFF, 0x59FFFFFF}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        shadow.setShadowLayer(h * 0.18f, 0, h * 0.06f, 0x80000000);
        invalidate();
    }

    /** Moves the glass's left and right edges, for the dock growing with magnification. */
    void setGlassSpan(float left, float right) {
        if (glass == null) return;
        glass.left = left;
        glass.right = right;
        glassPath.reset();
        glassPath.addRoundRect(glass, glassRadius, glassRadius, Path.Direction.CW);
        invalidate();
    }

    void setGlassShown(boolean on) {
        if (glassFader != null) glassFader.cancel();
        glassFader = ValueAnimator.ofFloat(glassAlpha, on ? 1f : 0f).setDuration(260);
        glassFader.addUpdateListener(a -> {
            glassAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        glassFader.start();
    }

    /** Darkens the pictures while the full app grid is up. */
    void setGridMode(boolean on) {
        if (dimmer != null) dimmer.cancel();
        dimmer = ValueAnimator.ofFloat(dim, on ? 1f : 0f).setDuration(260);
        dimmer.addUpdateListener(a -> {
            dim = (float) a.getAnimatedValue();
            invalidate();
        });
        dimmer.start();
    }

    /** Starts a new playlist, cross-fading quickly to its first picture. */
    void setItems(List<Feed.Item> list) {
        generation++;
        items = new ArrayList<>(list);
        index = 0;
        loading = false;
        if (items.isEmpty()) {
            prev = null;
            cur = null;
            if (listener != null) listener.onSlide(null, null);
            invalidate();
            return;
        }
        load(0, true);
    }

    void start() {
        if (running) return;
        running = true;
        if (stoppedAt > 0) {
            long gap = SystemClock.uptimeMillis() - stoppedAt;
            if (cur != null) cur.start += gap;
            if (prev != null) prev.start += gap;
        }
        invalidate();
    }

    void stop() {
        running = false;
        stoppedAt = SystemClock.uptimeMillis();
        ui.removeCallbacks(frame);
    }

    void release() {
        stop();
        decoder.shutdownNow();
    }

    private void load(int i, boolean quick) {
        loading = true;
        final int gen = generation;
        final Feed.Item item = items.get(i);
        final int w = maxW;
        final RectF glyphs = depth == null ? null : new RectF(depth);
        final int vw = getWidth(), vh = getHeight();
        decoder.execute(() -> {
            Slide s = new Slide();
            s.item = item;
            s.bmp = decode(item.image, w, Bitmap.Config.RGB_565);
            if (s.bmp != null) s.frost = frost(s.bmp);
            s.card = item.is(Feed.CARD) && s.bmp != null;
            if (s.bmp != null && !s.card && glyphs != null && vw > 0) cutout(s, glyphs, vw, vh);
            if (s.card) s.shader = new BitmapShader(s.bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (item.logo != null) s.logo = decode(item.logo, 640, Bitmap.Config.ARGB_8888);
            ui.post(() -> shown(s, gen, quick));
        });
    }

    private void shown(Slide s, int gen, boolean quick) {
        if (gen != generation) return;
        loading = false;
        if (s.bmp == null) {
            // Unreadable picture: drop it and move on.
            items.remove(s.item);
            if (items.isEmpty()) {
                setItems(items);
            } else {
                index %= items.size();
                load(index, quick);
            }
            return;
        }
        s.start = SystemClock.uptimeMillis();
        s.fade = quick || cur == null ? QUICK_FADE_MS : fadeMs;
        s.dx = random.nextBoolean() ? 1f : -1f;
        s.dy = s.fg != null ? 0f : random.nextBoolean() ? 1f : -1f;    // a framed skyline stays put
        prev = cur;
        cur = s;
        if (listener != null) listener.onSlide(s.item, s.logo);
        if (depthListener != null) depthListener.onDepth(s.fg != null ? s.shift : 0f);
        redraw();
    }

    static Bitmap decode(File f, int maxW, Bitmap.Config config) {
        if (f == null || !f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= maxW) o.inSampleSize *= 2;
        int sampled = o.outWidth / o.inSampleSize;
        if (sampled > maxW) {
            // Scale the rest of the way while decoding: a 1920 px photo shown at 1280 px costs
            // 1280 px of memory, not 1920.
            o.inScaled = true;
            o.inDensity = sampled;
            o.inTargetDensity = maxW;
        }
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = config;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /**
     * 160 px wide and box-blurred three times (close to a Gaussian). Stretched to full screen with
     * filtering it reads as smooth frosted glass, with none of the blockiness of a tiny bitmap.
     * Runs once per slide on the decoder thread.
     */
    private static Bitmap frost(Bitmap b) {
        int w = 160, h = Math.max(4, Math.round(160f * b.getHeight() / b.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, 6, true);
            box(tmp, px, w, h, 6, false);
        }
        Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        if (small != b) small.recycle();
        return out;
    }

    /** One running-sum box blur along rows (or columns), clamping at the edges. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean rows) {
        int lines = rows ? h : w, len = rows ? w : h, step = rows ? 1 : w;
        float div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = rows ? l * w : l;
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[base + Math.min(len - 1, Math.max(0, k)) * step];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int k = 0; k < len; k++) {
                out[base + k * step] = 0xFF000000 | ((int) (sr / div) << 16) | ((int) (sg / div) << 8) | (int) (sb / div);
                int add = in[base + Math.min(len - 1, k + r + 1) * step];
                int sub = in[base + Math.max(0, k - r) * step];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        left.setShader(new LinearGradient(0, 0, w * 0.62f, 0, 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
        top.setShader(new LinearGradient(0, 0, 0, h * 0.2f, 0x59000000, 0x00000000, Shader.TileMode.CLAMP));
        bottom.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0xB3000000, Shader.TileMode.CLAMP));
        box.set(w * 0.58f, h * 0.1f, w * 0.92f, h * 0.64f);
        cardShadow.setShadowLayer(h * 0.05f, 0, h * 0.02f, 0xB3000000);
        if (spotOn) makeSpot();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = running ? SystemClock.uptimeMillis() : stoppedAt;
        float curAlpha = 0f;
        if (videoMode) {
            curAlpha = 1f;
            prev = null;
        } else if (cur != null) {
            c.drawColor(base);
            curAlpha = Math.min(1f, (now - cur.start) / (float) cur.fade);
            if (curAlpha >= 1f) prev = null;
            if (prev != null) slide(c, prev, 1f, now, w, h);
            slide(c, cur, curAlpha, now, w, h);
        } else {
            c.drawColor(base);
            if (fallback != null) haze(c, fallback, 255, w, h);
        }

        if (scrims) {
            c.drawRect(0, 0, w, h, left);
            c.drawRect(0, 0, w, h, top);
            c.drawRect(0, 0, w, h, bottom);
        }
        if (spotOn) c.drawRect(0, 0, w, h, spot);
        if (dim > 0f) {
            fill.setColor(base);
            fill.setAlpha((int) (225 * dim));
            c.drawRect(0, 0, w, h, fill);
        }
        if (glass != null && glassAlpha > 0f) drawGlass(c, now, curAlpha, w, h);

        if (!running) return;
        // Nothing moves on screen under the grid's overlay or behind a video: draw no frames.
        boolean hidden = videoMode || (dim >= 1f && (dimmer == null || !dimmer.isRunning()));
        if (hidden) {
            freeze(now);
            return;
        }
        thaw(now);
        if (cur != null && items.size() > 1 && !loading && now - cur.start >= slideMs) {
            index = (index + 1) % items.size();
            load(index, false);
        }
        boolean moving = cur != null && (items.size() > 1 || now - cur.start < slideMs + cur.fade);
        if (moving || (dimmer != null && dimmer.isRunning()) || (glassFader != null && glassFader.isRunning())) {
            ui.removeCallbacks(frame);
            ui.postDelayed(frame, frameMs);
        }
    }

    private long frozenAt;

    /** Remembers when the slideshow stopped moving, so it resumes where it left off. */
    private void freeze(long now) {
        if (frozenAt == 0) frozenAt = now;
    }

    private void thaw(long now) {
        if (frozenAt == 0) return;
        long gap = now - frozenAt;
        if (cur != null) cur.start += gap;
        if (prev != null) prev.start += gap;
        frozenAt = 0;
    }

    private void drawGlass(Canvas c, long now, float curAlpha, int w, int h) {
        int ga = (int) (255 * glassAlpha);
        shadow.setAlpha(ga);
        c.drawRoundRect(glass, glassRadius, glassRadius, shadow);
        c.save();
        c.clipPath(glassPath);
        fill.setColor(base);
        fill.setAlpha(ga);
        c.drawRect(glass, fill);
        if (cur != null) {
            if (prev != null) picture(c, prev, prev.frost, glassAlpha, now, w, h);
            picture(c, cur, cur.frost, curAlpha * glassAlpha, now, w, h);
        } else if (fallback != null) {
            haze(c, fallback, ga, w, h);
        }
        // A milky lift, a bright sheen across the top, then the light rim: liquid glass.
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha((int) (26 * glassAlpha));
        c.drawRect(glass, fill);
        sheen.setAlpha(ga);
        c.drawRect(glass, sheen);
        c.restore();
        rim.setAlpha(ga);
        float in = rim.getStrokeWidth() / 2f;
        c.drawRoundRect(glass.left + in, glass.top + in, glass.right - in, glass.bottom - in,
                glassRadius, glassRadius, rim);
    }

    /** A full-bleed picture, or for card slides its blur with the sharp card floating on the right. */
    private void slide(Canvas c, Slide s, float alpha, long now, int w, int h) {
        if (!s.card) {
            picture(c, s, s.bmp, alpha, now, w, h);
            return;
        }
        picture(c, s, s.frost, alpha, now, w, h);
        fill.setColor(0xFF000000);
        fill.setAlpha((int) (90 * alpha));
        c.drawRect(0, 0, w, h, fill);
        float k = Math.min(box.width() / s.bmp.getWidth(), box.height() / s.bmp.getHeight());
        float cw = s.bmp.getWidth() * k, ch = s.bmp.getHeight() * k;
        cardRect.set(box.right - cw, box.centerY() - ch / 2f, box.right, box.centerY() + ch / 2f);
        float r = h * 0.025f;
        cardShadow.setAlpha((int) (255 * alpha));
        c.drawRoundRect(cardRect, r, r, cardShadow);
        cm.setScale(k, k);
        cm.postTranslate(cardRect.left, cardRect.top);
        s.shader.setLocalMatrix(cm);
        cardPaint.setShader(s.shader);
        cardPaint.setAlpha((int) (255 * alpha));
        c.drawRoundRect(cardRect, r, r, cardPaint);
    }

    /** Draws {@code b} (the slide's picture or its frost) with the slide's zoom and pan at {@code now}. */
    private void picture(Canvas c, Slide s, Bitmap b, float alpha, long now, int w, int h) {
        if (b == null || alpha <= 0f) return;
        float t = still ? 0.5f : Math.min(1f, Math.max(0f, (now - s.start) / (float) (slideMs + s.fade)));
        float bw = s.bmp.getWidth(), bh = s.bmp.getHeight();
        float scale = Math.max(w / bw, h / bh) * (zoomFrom + zoomBy * t) * s.zoom;
        float px = (t - 0.5f) * s.dx * w * 0.03f, py = (t - 0.5f) * s.dy * h * 0.02f;
        m.setScale(scale * bw / b.getWidth(), scale * bh / b.getHeight());
        m.postTranslate((w - bw * scale) / 2f + px, (h - bh * scale) * s.anchor + py);
        bmp.setAlpha((int) (255 * alpha));
        c.drawBitmap(b, m, bmp);
    }

    private void drawDepth(Canvas c) {
        if (videoMode || cur == null) return;
        int w = getWidth(), h = getHeight();
        long now = running ? SystemClock.uptimeMillis() : stoppedAt;
        float curAlpha = Math.min(1f, (now - cur.start) / (float) cur.fade);
        if (prev != null && prev.fg != null && curAlpha < 1f) foreground(c, prev, 1f - curAlpha, now, w, h);
        if (cur.fg != null) foreground(c, cur, curAlpha, now, w, h);
    }

    /** A slide's foreground strip, placed exactly over the picture as {@link #picture} draws it. */
    private void foreground(Canvas c, Slide s, float alpha, long now, int w, int h) {
        float t = still ? 0.5f : Math.min(1f, Math.max(0f, (now - s.start) / (float) (slideMs + s.fade)));
        float bw = s.bmp.getWidth(), bh = s.bmp.getHeight();
        float scale = Math.max(w / bw, h / bh) * (zoomFrom + zoomBy * t) * s.zoom;
        float px = (t - 0.5f) * s.dx * w * 0.03f, py = (t - 0.5f) * s.dy * h * 0.02f;
        m.setTranslate(0, s.fgTop);
        m.postScale(scale, scale);
        m.postTranslate((w - bw * scale) / 2f + px, (h - bh * scale) * s.anchor + py);
        bmp.setAlpha((int) (255 * alpha));
        c.drawBitmap(s.fg, m, bmp);
    }

    /**
     * Finds the picture's skyline and, if it would hide a little (not a lot) of the clock, cuts out
     * what's in front of it as {@code s.fg}. Decoder thread.
     *
     * The skyline is where each column stops looking like the sky above it, found on a 192 px copy
     * and then snapped to the strongest edge nearby at full size. Pictures whose sky is busy
     * (trees, close-ups) or that would cover too much of the time get no depth effect.
     */
    private void cutout(Slide s, RectF glyphs, int vw, int vh) {
        Bitmap b = s.bmp;
        int bw = b.getWidth(), bh = b.getHeight();
        float cover = Math.max(vw / (float) bw, vh / (float) bh) * (zoomFrom + zoomBy * 0.5f);

        int sw = 192, sh = Math.max(16, Math.round(192f * bh / bw));
        Bitmap small = Bitmap.createScaledBitmap(b, sw, sh, true);
        int[] p = new int[sw * sh];
        small.getPixels(p, 0, sw, 0, 0, sw, sh);
        if (small != b) small.recycle();
        int[] edge = new int[sw];
        for (int x = 0; x < sw; x++) edge[x] = skyline(p, sw, sh, x);
        edge = median(edge, 3);
        if (!calmSky(p, sw, sh, edge)) return;

        // Frame the picture (zoom in a little, anchor it higher) and move the clock up or down so
        // the skyline hides about a fifth of the digits, and never more than 60% of any column.
        // Skylines on a TV-shaped photo usually sit higher than that, hence the search.
        float k = bh / (float) sh, best = Float.MAX_VALUE, bestZoom = 0, bestAnchor = 0, bestShift = 0;
        for (float zoom : new float[] {1f, 1.1f, 1.2f, 1.3f}) {
            float scale = cover * zoom, ox = (vw - bw * scale) / 2f;
            for (float anchor : new float[] {0f, 0.25f, 0.5f}) {
                float oy = (vh - bh * scale) * anchor;
                for (float shift = -0.07f * vh; shift <= 0.15f * vh; shift += 6) {
                    float sum = 0, most = 0;
                    int n = 0;
                    for (float x = glyphs.left; x <= glyphs.right; x += 6) {
                        int xs = Math.min(sw - 1, Math.max(0, (int) ((x - ox) / scale * sw / bw)));
                        float ys = oy + (edge[xs] + 0.5f) * k * scale;
                        float hidden = Math.min(1f, Math.max(0f, (glyphs.bottom + shift - ys) / glyphs.height()));
                        sum += hidden;
                        most = Math.max(most, hidden);
                        n++;
                    }
                    float mean = n == 0 ? 0 : sum / n;
                    if (mean < 0.06f || mean > 0.35f || most > 0.6f) continue;
                    // Prefer a fifth hidden, then the least zoom, clock movement and off-centre framing.
                    float cost = Math.abs(mean - 0.2f) + 0.3f * (zoom - 1f) + 0.3f * Math.abs(shift) / vh
                            + 0.05f * Math.abs(anchor - 0.5f);
                    if (cost < best) {
                        best = cost;
                        bestZoom = zoom;
                        bestAnchor = anchor;
                        bestShift = shift;
                    }
                }
            }
        }
        if (best == Float.MAX_VALUE) return;
        float scale = cover * bestZoom, ox = (vw - bw * scale) / 2f, oy = (vh - bh * scale) * bestAnchor;
        glyphs = new RectF(glyphs.left, glyphs.top + bestShift, glyphs.right, glyphs.bottom + bestShift);

        // Full-size strip around the clock; below it the picture is already in front.
        int top = Math.max(0, (int) ((glyphs.top - vh * 0.04f - oy) / scale));
        int bottom = Math.min(bh, (int) ((glyphs.bottom + vh * 0.06f - oy) / scale));
        int rows = bottom - top;
        if (rows < 8) return;
        int[] px = new int[bw * rows];
        b.getPixels(px, 0, bw, 0, top, bw, rows);
        int[] fine = new int[bw];
        int reach = Math.max(2, Math.round(k * 1.5f));
        for (int x = 0; x < bw; x++) {
            float cx = Math.max(0f, Math.min(sw - 1f, (x + 0.5f) * sw / bw - 0.5f));
            int x0 = (int) cx, x1 = Math.min(sw - 1, x0 + 1);
            float yc = ((edge[x0] + (edge[x1] - edge[x0]) * (cx - x0)) + 0.5f) * k;
            if (yc >= bh - 1) {
                fine[x] = bottom;
                continue;
            }
            int sharpest = Math.round(yc), score = -1;
            for (int y = Math.max(top + 2, Math.round(yc) - reach); y <= Math.min(bottom - 1, Math.round(yc) + reach); y++) {
                int d = dist(px[(y - top) * bw + x], px[(y - 2 - top) * bw + x]);
                if (d > score) {
                    score = d;
                    sharpest = y - 1;
                }
            }
            fine[x] = sharpest;
        }
        fine = median(fine, 5);

        for (int r = 0; r < rows; r++) {
            int y = top + r;
            for (int x = 0; x < bw; x++) {
                int i = r * bw + x;
                float a = Math.min(1f, Math.max(0f, (y - fine[x] + 1.5f) / 3f));
                if (a <= 0f) {
                    px[i] = 0;
                    continue;
                }
                int c = px[i];
                // The spot darkens the picture under the clock; darken the cut-out to match.
                float keep = 1f;
                if (spotOn) keep = 1f - spotStrength * spotAlpha((float) Math.hypot(ox + x * scale - spotX, oy + y * scale - spotY) / spotR);
                int rr = (int) (((c >> 16) & 0xFF) * keep), gg = (int) (((c >> 8) & 0xFF) * keep), bb = (int) ((c & 0xFF) * keep);
                px[i] = ((int) (a * 255) << 24) | (rr << 16) | (gg << 8) | bb;
            }
        }
        s.fg = Bitmap.createBitmap(px, bw, rows, Bitmap.Config.ARGB_8888);
        s.fgTop = top;
        s.zoom = bestZoom;
        s.anchor = bestAnchor;
        s.shift = bestShift;
    }

    /** The first row where column x stops looking like the sky above it for 5 rows, or sh. */
    private static int skyline(int[] p, int sw, int sh, int x) {
        float r = 0, g = 0, b = 0;
        for (int y = 0; y < 3; y++) {
            int c = p[y * sw + x];
            r += ((c >> 16) & 0xFF) / 3f;
            g += ((c >> 8) & 0xFF) / 3f;
            b += (c & 0xFF) / 3f;
        }
        int run = 0;
        for (int y = 3; y < sh; y++) {
            int c = p[y * sw + x];
            int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF;
            if (Math.abs(cr - r) + Math.abs(cg - g) + Math.abs(cb - b) > 54) {
                if (++run == 5) return y - 4;
            } else {
                run = 0;
                r += (cr - r) * 0.2f;
                g += (cg - g) * 0.2f;
                b += (cb - b) * 0.2f;
            }
        }
        return sh;
    }

    /** A real sky: at least a quarter of the picture on average, and smooth (no leaves or fur). */
    private static boolean calmSky(int[] p, int sw, int sh, int[] edge) {
        long rough = 0, area = 0, depthSum = 0;
        for (int x = 0; x < sw - 1; x++) {
            depthSum += edge[x];
            for (int y = 0; y < edge[x] - 1; y++) {
                int c = p[y * sw + x];
                rough += dist(c, p[(y + 1) * sw + x]) + dist(c, p[y * sw + x + 1]);
                area++;
            }
        }
        return depthSum / (float) (sw - 1) >= sh * 0.25f && area > 0 && rough / (float) area < 18f;
    }

    private static int dist(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static int[] median(int[] v, int width) {
        int[] out = new int[v.length], win = new int[width];
        for (int i = 0; i < v.length; i++) {
            for (int j = 0; j < width; j++) win[j] = v[Math.min(v.length - 1, Math.max(0, i + j - width / 2))];
            java.util.Arrays.sort(win);
            out[i] = win[width / 2];
        }
        return out;
    }

    /** The spot's darkness at distance d (in radii) from its centre, matching {@link #makeSpot}. */
    private static float spotAlpha(float d) {
        if (d >= 1f) return 0f;
        if (d <= 0.55f) return (0x8C + (0x4D - 0x8C) * d / 0.55f) / 255f;
        return 0x4D * (1f - (d - 0.55f) / 0.45f) / 255f;
    }

    private void haze(Canvas c, Bitmap b, int alpha, int w, int h) {
        src.set(0, 0, b.getWidth(), b.getHeight());
        dst.set(-w * 0.1f, -h * 0.1f, w * 1.1f, h * 1.1f);
        bmp.setAlpha(alpha);
        c.drawBitmap(b, src, dst, bmp);
        fill.setColor(0xFF000000);
        fill.setAlpha(alpha * 45 / 100);
        c.drawRect(0, 0, w, h, fill);
    }
}
