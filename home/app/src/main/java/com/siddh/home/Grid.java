package com.siddh.home;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Lays tiles out in rows of {@link #cols}. Row 0 sits in the dock; later rows peek in below it
 * and slide up when focus moves down. The name label is the last child and follows the focused
 * tile in those later rows.
 */
final class Grid extends ViewGroup {
    final int cols, tileW, tileH, gap, rowH, left, top;
    final TextView label;
    private int labelFor = -1;

    Grid(Context c, int cols, int tileW, int tileH, int gap, int rowGap, int left, int top, TextView label) {
        super(c);
        this.cols = cols;
        this.tileW = tileW;
        this.tileH = tileH;
        this.gap = gap;
        this.rowH = tileH + rowGap;
        this.left = left;
        this.top = top;
        this.label = label;
        setClipChildren(false);
        setClipToPadding(false);
        addView(label);
    }

    int tiles() {
        return getChildCount() - 1;
    }

    Tile tile(int i) {
        return (Tile) getChildAt(i);
    }

    void addTile(Tile t) {
        addView(t, tiles());
    }

    void clearTiles() {
        removeViews(0, tiles());
        labelFor = -1;
    }

    int rowTop(int row) {
        return top + row * rowH;
    }

    /** Puts the name label under tile i, or hides it for i < 0. */
    void labelUnder(int i) {
        labelFor = i;
        if (i < 0 || i >= tiles()) {
            label.setVisibility(GONE);
            return;
        }
        label.setText(tile(i).app.label);
        label.setVisibility(VISIBLE);
        requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int tw = MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY);
        int th = MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY);
        for (int i = 0; i < tiles(); i++) getChildAt(i).measure(tw, th);
        label.measure(MeasureSpec.makeMeasureSpec(tileW * 2, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < tiles(); i++) {
            int x = left + (i % cols) * (tileW + gap), y = rowTop(i / cols);
            getChildAt(i).layout(x, y, x + tileW, y + tileH);
        }
        if (labelFor >= 0 && labelFor < tiles()) {
            View tile = getChildAt(labelFor);
            int lw = label.getMeasuredWidth(), lh = label.getMeasuredHeight();
            int x = tile.getLeft() + (tileW - lw) / 2;
            int y = tile.getBottom() + Math.round(tileH * (Tile.FOCUS_SCALE - 1f) / 2f) + lh / 3;
            label.layout(x, y, x + lw, y + lh);
        }
    }
}
