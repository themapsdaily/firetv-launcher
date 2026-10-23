package com.siddh.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * One app tile. Focus lifts it: scale with a small spring overshoot, a shadow, a white ring set off
 * by a gap, a brief tilt towards the direction focus came from, and one shine sweep. Only the
 * focused tile ever animates, and nothing runs once it has settled.
 */
final class Tile extends View {
    static final float FOCUS_SCALE = 1.12f;

    final Apps.App app;
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final float radius, gap;
    private boolean focused;
    private float shineAt = -1f;
    private ValueAnimator sweep;
    private Bitmap shaded;
    /** In the dock: Home drives scale and position (magnification); grows up from the bottom edge. */
    boolean docked;

    Tile(Context c, Apps.App app, float scale) {
        super(c);
        this.app = app;
        radius = 16f * scale;
        gap = 5f * scale;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(2f, 3f * scale));
        ring.setColor(0xFFFFFFFF);
        // A dark halo under the white ring, so it reads over light glass and photos too.
        ring.setShadowLayer(5f * scale, 0, 0, 0xA6000000);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        setClipToOutline(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rect.set(0, 0, w, h);
        gloss.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        shaded = null;
        setPivotX(w / 2f);
        setPivotY(docked ? h : h / 2f);
    }

    /** dx, dy: the direction focus moved in, for the tilt. 0, 0 for no tilt. */
    void setFocus(boolean on, int dx, int dy) {
        if (focused == on) return;
        focused = on;
        animate().cancel();
        if (on) {
            setElevation(getWidth() * 0.08f);
            if (!docked) {
                animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE)
                        .setInterpolator(new OvershootInterpolator(1.6f)).setDuration(240).start();
            }
            if (dx != 0 || dy != 0) {
                setRotationY(dx * 5f);
                setRotationX(-dy * 4f);
                animate().rotationX(0f).rotationY(0f);
            }
            startShine();
        } else {
            setElevation(0f);
            if (sweep != null) sweep.cancel();
            shineAt = -1f;
            if (docked) animate().rotationX(0f).rotationY(0f).setDuration(160).start();
            else animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(160).start();
        }
        invalidate();
    }

    private void startShine() {
        if (sweep != null) sweep.cancel();
        sweep = ValueAnimator.ofFloat(-0.4f, 1.4f).setDuration(650);
        sweep.setStartDelay(120);
        sweep.setInterpolator(new DecelerateInterpolator());
        sweep.addUpdateListener(a -> {
            shineAt = (float) a.getAnimatedValue();
            invalidate();
        });
        sweep.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (app.art != null) {
            if (shaded != app.art) {
                BitmapShader s = new BitmapShader(app.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                matrix.setScale((float) w / app.art.getWidth(), (float) h / app.art.getHeight());
                s.setLocalMatrix(matrix);
                art.setShader(s);
                shaded = app.art;
            }
        } else {
            art.setShader(null);
            art.setColor(app.color);
        }
        c.drawRoundRect(rect, radius, radius, art);
        if (!focused) return;
        c.drawRoundRect(rect, radius, radius, gloss);
        if (shineAt > -0.4f && shineAt < 1.4f) {
            float x = shineAt * w;
            shine.setShader(new LinearGradient(x - w * 0.25f, 0, x + w * 0.25f, h,
                    new int[] {0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, radius, radius, shine);
        }
        // The ring stands off the tile by a small gap, so it shows on white art (YouTube, Netflix)
        // as clearly as on dark. The grid doesn't clip its children, so it can draw outside.
        float out = gap + ring.getStrokeWidth() / 2f;
        c.drawRoundRect(-out, -out, w + out, h + out, radius + out, radius + out, ring);
    }
}
