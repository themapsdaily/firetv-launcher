package com.siddh.home;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/**
 * Outfit (SIL OFL, see FONT-LICENSE-OFL.txt) is one variable font file. Typeface.create(family,
 * weight) does not pick a weight out of a variable file, so each weight is built with its own
 * 'wght' axis setting, once.
 */
final class Fonts {
    private static final SparseArray<Typeface> cache = new SparseArray<>();

    private Fonts() {}

    static Typeface outfit(Context c, int weight) {
        Typeface t = cache.get(weight);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(c.getAssets(), "fonts/outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null) t = Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        cache.put(weight, t);
        return t;
    }
}
