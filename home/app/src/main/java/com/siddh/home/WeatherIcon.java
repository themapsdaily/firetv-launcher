package com.siddh.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Plain white line-and-fill weather glyphs, drawn from the WMO code; no emoji, no image files. */
final class WeatherIcon extends Drawable {
    private final int code;
    private final boolean day;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), cut = new Path();

    WeatherIcon(int code, boolean day, int size) {
        this.code = code;
        this.day = day;
        fill.setColor(0xFFFFFFFF);
        line.setColor(0xFFFFFFFF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        setBounds(0, 0, size, size);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = b.width(), x = b.left, y = b.top;
        line.setStrokeWidth(s * 0.07f);
        boolean clear = code == 0, partly = code == 1 || code == 2;
        if (clear) {
            if (day) sun(c, x + s / 2, y + s / 2, s * 0.22f, s * 0.12f);
            else moon(c, x + s / 2, y + s / 2, s * 0.32f);
            return;
        }
        if (code == 45 || code == 48) {
            for (int i = 0; i < 3; i++) {
                float ly = y + s * (0.32f + i * 0.18f);
                c.drawLine(x + s * (0.12f + i * 0.06f), ly, x + s * (0.88f - i * 0.04f), ly, line);
            }
            return;
        }
        if (partly) {
            // Yellow, or a white cloud over a white sun reads as one blob.
            int a = fill.getAlpha();
            fill.setColor(0xFFFFD60A);
            line.setColor(0xFFFFD60A);
            fill.setAlpha(a);
            line.setAlpha(a);
            if (day) sun(c, x + s * 0.64f, y + s * 0.34f, s * 0.15f, s * 0.08f);
            else moon(c, x + s * 0.66f, y + s * 0.34f, s * 0.2f);
            fill.setColor(0xFFFFFFFF);
            line.setColor(0xFFFFFFFF);
            fill.setAlpha(a);
            line.setAlpha(a);
        }
        boolean wet = code >= 51 && code <= 82 || code >= 95;
        boolean snow = code >= 71 && code <= 77 || code == 85 || code == 86;
        float cy = wet || snow ? y + s * 0.42f : y + s * 0.55f;
        cloud(c, x + s / 2, cy, s);
        if (code >= 95) {
            path.reset();
            path.moveTo(x + s * 0.52f, y + s * 0.6f);
            path.lineTo(x + s * 0.42f, y + s * 0.78f);
            path.lineTo(x + s * 0.52f, y + s * 0.78f);
            path.lineTo(x + s * 0.45f, y + s * 0.95f);
            path.lineTo(x + s * 0.64f, y + s * 0.72f);
            path.lineTo(x + s * 0.54f, y + s * 0.72f);
            path.lineTo(x + s * 0.6f, y + s * 0.6f);
            path.close();
            c.drawPath(path, fill);
        } else if (snow) {
            for (int i = 0; i < 3; i++) c.drawCircle(x + s * (0.32f + i * 0.18f), y + s * (0.8f + (i % 2) * 0.08f), s * 0.045f, fill);
        } else if (wet) {
            for (int i = 0; i < 3; i++) {
                float lx = x + s * (0.34f + i * 0.16f);
                c.drawLine(lx, y + s * 0.72f, lx - s * 0.06f, y + s * 0.9f, line);
            }
        }
    }

    private void sun(Canvas c, float cx, float cy, float r, float ray) {
        c.drawCircle(cx, cy, r, fill);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * (r + ray * 0.6f), cy + sin * (r + ray * 0.6f),
                    cx + cos * (r + ray * 1.4f), cy + sin * (r + ray * 1.4f), line);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        cut.reset();
        cut.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW);
        path.op(cut, Path.Op.DIFFERENCE);
        c.drawPath(path, fill);
    }

    private void cloud(Canvas c, float cx, float cy, float s) {
        path.reset();
        path.addCircle(cx - s * 0.17f, cy + s * 0.04f, s * 0.15f, Path.Direction.CW);
        path.addCircle(cx + s * 0.02f, cy - s * 0.06f, s * 0.2f, Path.Direction.CW);
        path.addCircle(cx + s * 0.2f, cy + s * 0.05f, s * 0.14f, Path.Direction.CW);
        path.addRoundRect(cx - s * 0.32f, cy + s * 0.02f, cx + s * 0.34f, cy + s * 0.19f, s * 0.08f, s * 0.08f, Path.Direction.CW);
        path.setFillType(Path.FillType.WINDING);
        c.drawPath(path, fill);
    }

    @Override
    public void setAlpha(int a) {
        fill.setAlpha(a);
        line.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter f) {
        fill.setColorFilter(f);
        line.setColorFilter(f);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return getBounds().width();
    }

    @Override
    public int getIntrinsicHeight() {
        return getBounds().height();
    }
}
