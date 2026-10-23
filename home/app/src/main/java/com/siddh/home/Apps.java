package com.siddh.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Which apps are on the home screen, in what order, and the art each one gets. */
final class Apps {
    /** Tile art size. RGB_565 halves the memory; tiles clip their own corners. */
    static final int ART_W = 480, ART_H = 270;

    /** Never shown: this launcher, the one it replaced, and Fire OS plumbing. */
    private static final List<String> SKIP = Arrays.asList(
            "tv.dumbtv.home", Guard.AMAZON, "com.amazon.firetv.troubleshooting");

    /** First run order; anything else follows alphabetically. */
    private static final List<String> FIRST = Arrays.asList(
            "com.amazon.firetv.youtube", "com.netflix.ninja", "com.amazon.firebat",
            "com.fancode.tv", "in.startv.hotstar", "com.spotify.tv.android",
            "com.onemainstream.sonyliv.android");

    static final class App {
        final String pkg;
        final CharSequence label;
        final Intent intent;
        final ActivityInfo info;
        Bitmap art;        // ART_W x ART_H, the tile
        Bitmap haze;       // a few pixels wide; stretched, it is the shelf's blurred backdrop
        int color = 0xFF2A2D38;

        App(String pkg, CharSequence label, Intent intent, ActivityInfo info) {
            this.pkg = pkg;
            this.label = label;
            this.intent = intent;
            this.info = info;
        }
    }

    private Apps() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** Every TV app (LEANBACK_LAUNCHER), minus hidden ones, in the saved order. */
    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> found = new LinkedHashMap<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(c.getPackageName()) || SKIP.contains(ai.packageName)
                    || found.containsKey(ai.packageName)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(ai.packageName, ai.name);
            found.put(ai.packageName, new App(ai.packageName, ri.loadLabel(pm), launch, ai));
        }
        List<String> hidden = list(c, "hidden");
        List<App> out = new ArrayList<>();
        for (App a : found.values()) if (!hidden.contains(a.pkg)) out.add(a);
        List<String> order = list(c, "order");
        Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> {
            int ra = rank(order, a.pkg), rb = rank(order, b.pkg);
            if (ra != rb) return Integer.compare(ra, rb);
            return collator.compare(a.label.toString(), b.label.toString());
        });
        return out;
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Apps hidden from the home screen that are still installed, for the "Show" menu. */
    static List<String> hidden(Context c) {
        return list(c, "hidden");
    }

    private static int rank(List<String> order, String pkg) {
        int i = order.indexOf(pkg);
        if (i >= 0) return i;
        int f = FIRST.indexOf(pkg);
        return f >= 0 ? 1000 + f : Integer.MAX_VALUE;
    }

    static void saveOrder(Context c, List<App> apps) {
        List<String> ids = new ArrayList<>();
        for (App a : apps) ids.add(a.pkg);
        prefs(c).edit().putString("order", TextUtils.join(",", ids)).apply();
    }

    static void setHidden(Context c, String pkg, boolean hide) {
        List<String> h = list(c, "hidden");
        h.remove(pkg);
        if (hide) h.add(pkg);
        prefs(c).edit().putString("hidden", TextUtils.join(",", h)).apply();
    }

    private static List<String> list(Context c, String key) {
        String s = prefs(c).getString(key, "");
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Renders the banner (or the icon on its own colour) into a tile bitmap. Slow: off the UI thread. */
    static void art(PackageManager pm, App a) {
        Drawable banner = a.info.loadBanner(pm);
        Bitmap bmp = Bitmap.createBitmap(ART_W, ART_H, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        if (banner != null) {
            c.drawColor(Color.BLACK);
            banner.setBounds(0, 0, ART_W, ART_H);
            banner.draw(c);
        } else {
            Drawable icon = a.info.loadIcon(pm);
            Bitmap probe = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 24, 24);
            icon.draw(new Canvas(probe));
            c.drawColor(darken(average(probe), 0.55f));
            probe.recycle();
            int s = ART_H / 2;
            icon.setBounds((ART_W - s) / 2, (ART_H - s) / 2, (ART_W + s) / 2, (ART_H + s) / 2);
            icon.draw(c);
        }
        a.art = bmp;
        a.color = average(bmp);
        a.haze = haze(bmp);
    }

    /** Two filtered steps down to a few pixels; stretched back up it reads as a heavy blur. */
    static Bitmap haze(Bitmap src) {
        int w = 12, h = Math.max(2, Math.round(12f * src.getHeight() / src.getWidth()));
        Bitmap mid = Bitmap.createScaledBitmap(src, w * 4, h * 4, true);
        Bitmap out = Bitmap.createScaledBitmap(mid, w, h, true);
        if (mid != src) mid.recycle();
        return out;
    }

    /** Average colour weighted towards saturated pixels, so a white logo on red reads as red. */
    static int average(Bitmap src) {
        Bitmap s = Bitmap.createScaledBitmap(src, 16, 9, true);
        float r = 0, g = 0, b = 0, w = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int p = s.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                float weight = 0.15f + hsv[1] * hsv[2];
                r += Color.red(p) * weight;
                g += Color.green(p) * weight;
                b += Color.blue(p) * weight;
                w += weight;
            }
        }
        if (s != src) s.recycle();
        if (w == 0) return 0xFF2A2D38;
        return Color.rgb((int) (r / w), (int) (g / w), (int) (b / w));
    }

    /** The colour as a dark, slightly desaturated background tint (never pure black). */
    static int tint(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], 0.55f);
        hsv[2] = 0.20f;
        return Color.HSVToColor(hsv);
    }

    static int darken(int color, float f) {
        return Color.rgb((int) (Color.red(color) * f), (int) (Color.green(color) * f),
                (int) (Color.blue(color) * f));
    }
}
