package com.siddh.home;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Sports view (ROADMAP section 7), after the owner's sports-screen.html design: near-black, one
 * accent per sport, Barlow Condensed for labels and numbers. Two tabs (Fixtures with live
 * matches first, Results) over three columns, one per sport. Reads only {@link Sports}'
 * cache, so it opens at once and works offline.
 *
 * Remote: on the tab bar, left and right change tab and down enters the columns; in the columns,
 * up and down move between cards (the column scrolls), left and right between sports, and select
 * opens a match. Back closes a match, then the view.
 *
 * Sizes are the design's pixels at 1920 wide, scaled to the screen by {@link #px}.
 */
public final class Scoreboard extends Activity {
    private static final String[] TABS = {"Fixtures", "Results"};
    private static final int FIXTURES = 0;
    private static final String[] SPORTS = {Sports.CRICKET, Sports.F1, Sports.TENNIS};
    private static final String[] NAMES = {"Cricket", "Formula 1", "WTA Tennis"};
    private static final int[] ICONS = {R.drawable.sport_cricket, R.drawable.ic_f1_flag, R.drawable.sport_tennis};
    /** One accent per sport, and the same at about 16% over the panel. */
    private static final int[] ACCENT = {0xFF4C9BFF, 0xFFFF4D3D, 0xFFC8F24A};
    private static final int[] SOFT_ACCENT = {0xFF1B2638, 0xFF3A1C1C, 0xFF2C3320};
    private static final int BG = 0xFF0A0B0D, PANEL = 0xFF121418, PANEL_LINE = 0xFF1E2126, CARD = 0xFF191C21,
            CHIP = 0xFF262A31, TEXT = 0xFFF4F5F7, MUTED = 0xFFA8AFB9, DIM = 0xFF8E95A0, SOFT = 0xFFC9CED6,
            WIN_CELL = 0xFF2E333B, TAB_BAR = 0xFF15171B, DASH = 0xFF2A2E35, LIVE = 0xFFFF453A, NO_RESULT = 0xFF6B7280;
    /** The focus ring: 3 px of white, 3 px clear of the card. Every focusable card carries room for it. */
    private static final int RING = 6;
    /** Cricket fixtures shown as full cards; the rest are listed as one-line rows under LATER. */
    private static final int FULL_CARDS = 4;
    /** Cricket and tennis results go back this far. */
    private static final long RECENT_MS = 7 * 86400_000L;
    /** F1 sessions change state by the clock, so the view redraws now and then while open. */
    private static final long REDRAW_MS = 30_000;
    private static final Pattern OVERS = Pattern.compile("\\(([\\d.]+)"), BALLS = Pattern.compile("\\s*\\((\\d+)b rem\\)");
    private static final Locale EN = Locale.ENGLISH;

    /** One item in a column: a card or row that takes focus, or a label or empty note that does not. */
    private static final class Entry {
        final View view;
        final boolean focusable;
        final Sports.Fixture fixture;
        final int fill, border, top;
        final float radius;

        Entry(View view, boolean focusable, Sports.Fixture fixture, int fill, int border, float radius, int top) {
            this.view = view;
            this.focusable = focusable;
            this.fixture = fixture;
            this.fill = fill;
            this.border = border;
            this.radius = radius;
            this.top = top;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable redraw = this::redraw;
    private float s;
    private int W, H, listW, listH;
    private FrameLayout root;
    private LinearLayout tabBar;
    private final List<TextView> tabs = new ArrayList<>();
    private final LinearLayout[] lists = new LinearLayout[SPORTS.length];
    private final TextView[] metas = new TextView[SPORTS.length];
    private final int[] first = new int[SPORTS.length];
    private final List<List<Entry>> entries = new ArrayList<>();
    /** Per column, which entries are fully on screen (the others are scrolled off or under the fade). */
    private final List<boolean[]> shown = new ArrayList<>();
    private View detail, scrim;
    private int tab, col, row = -1;
    private boolean inTabs = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = Math.max(dm.widthPixels, dm.heightPixels);
        H = Math.min(dm.widthPixels, dm.heightPixels);
        s = W / 1920f;
        setContentView(build());
        fill();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Sports.onChange = () -> ui.post(this::fill);
        // Guard normally keeps the cache fresh; fetch here too in case it is not running.
        if (Sports.stale(Sports.load(this), System.currentTimeMillis())) worker.execute(() -> Sports.daily(this));
        ui.postDelayed(redraw, REDRAW_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Sports.onChange = null;
        ui.removeCallbacks(redraw);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void redraw() {
        fill();
        ui.postDelayed(redraw, REDRAW_MS);
    }

    // ---- type and shapes ----

    private int px(float v) {
        return Math.round(v * s);
    }

    /** Barlow Condensed: labels, tags and numbers. {@code style} is Medium, SemiBold, Bold or BoldItalic. */
    private TextView cond(String words, float size, String style, int colour, float tracking) {
        TextView t = new TextView(this);
        t.setTypeface(Fonts.file(this, "BarlowCondensed-" + style));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(colour);
        t.setLetterSpacing(tracking);
        t.setIncludeFontPadding(false);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(words);
        return t;
    }

    /** Barlow: names and sentences. */
    private TextView body(String words, float size, boolean semibold, int colour) {
        TextView t = new TextView(this);
        t.setTypeface(Fonts.file(this, semibold ? "Barlow-SemiBold" : "Barlow-Medium"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(colour);
        t.setIncludeFontPadding(false);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(words);
        return t;
    }

    private static GradientDrawable round(int fill, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(fill);
        return d;
    }

    /** A card's fill and border, with the focus ring around it when {@code on}. */
    private Drawable ring(int fill, int border, float radius, boolean on) {
        int o = px(RING);
        GradientDrawable outer = round(0, radius + o);
        if (on) outer.setStroke(px(3), TEXT);
        GradientDrawable inner = round(fill, radius);
        if (border != 0) inner.setStroke(px(2), border);
        LayerDrawable l = new LayerDrawable(new Drawable[] {outer, inner});
        l.setLayerInset(1, o, o, o, o);
        return l;
    }

    /** A panel with the sport's colour along its top edge. */
    private Drawable panel(int accent, float radius) {
        GradientDrawable edge = round(accent, px(radius));
        GradientDrawable face = round(PANEL, px(radius - 2));
        face.setStroke(Math.max(1, px(1)), PANEL_LINE);
        LayerDrawable bg = new LayerDrawable(new Drawable[] {edge, face});
        bg.setLayerInset(1, 0, px(6), 0, 0);
        return bg;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** Takes the rest of a row, with margins. */
    private LinearLayout.LayoutParams grow(int l, int r) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(l, 0, r, 0);
        return p;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private ImageView icon(int drawable, int colour, float size) {
        ImageView v = new ImageView(this);
        v.setImageResource(drawable);
        v.setColorFilter(colour);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(size), px(size)));
        return v;
    }

    // ---- layout ----

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        int side = px(80), top = px(56), header = px(72);

        // Brand: three slanted stripes in the sports' colours, then SPORTS.
        LinearLayout brand = row();
        brand.addView(new Stripes(), new LinearLayout.LayoutParams(px(54), px(38)));
        brand.addView(cond("SPORTS", 54, "BoldItalic", TEXT, 0.02f), lp(-2, -2, px(18), 0, 0, 0));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, header, Gravity.TOP | Gravity.START);
        bl.leftMargin = side;
        bl.topMargin = top;
        root.addView(brand, bl);

        tabBar = row();
        tabBar.setPadding(px(6), px(6), px(6), px(6));
        for (String name : TABS) {
            TextView t = cond(name.toUpperCase(EN), 24, "SemiBold", MUTED, 0.08f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(36), 0, px(36), 0);
            tabBar.addView(t, lp(-2, px(56), tabs.isEmpty() ? 0 : px(4), 0, 0, 0));
            tabs.add(t);
        }
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = top + (header - px(68)) / 2;
        root.addView(tabBar, tl);

        LinearLayout date = column();
        date.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        date.addView(cond(new SimpleDateFormat("EEE d MMM", EN).format(new Date()), 34, "Bold", TEXT, 0), lp(-2, -2, 0, 0, 0, 0));
        date.addView(body("All times " + zone(), 18, false, MUTED), lp(-2, -2, 0, px(4), 0, 0));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, header, Gravity.TOP | Gravity.END);
        dl.rightMargin = side;
        dl.topMargin = top;
        root.addView(date, dl);

        // Three columns. Each clips to its rounded panel; its list runs on past the foot, where a
        // plain fade in the panel colour covers it.
        int gap = px(32), gridTop = top + header + px(40);
        int colW = (W - 2 * side - 2 * gap) / 3, colH = H - gridTop - px(56);
        listW = colW - 2 * px(20 - RING);
        for (int i = 0; i < SPORTS.length; i++) {
            FrameLayout panel = new FrameLayout(this);
            panel.setBackground(panel(ACCENT[i], 20));
            final float radius = px(20);
            panel.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            panel.setClipToOutline(true);

            LinearLayout inner = column();
            View head = header(i);
            inner.addView(head, lp(-1, -2, 0, px(6), 0, 0));
            head.measure(View.MeasureSpec.makeMeasureSpec(colW, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            listH = colH - px(6) - head.getMeasuredHeight();
            FrameLayout window = new FrameLayout(this);
            LinearLayout list = column();
            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(listW, 3 * listH);
            ll.leftMargin = px(20 - RING);
            ll.topMargin = px(4);
            window.addView(list, ll);
            View fade = new View(this);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {PANEL & 0x00FFFFFF, 0xE6000000 | (PANEL & 0x00FFFFFF), PANEL}));
            window.addView(fade, new FrameLayout.LayoutParams(-1, px(112), Gravity.BOTTOM));
            inner.addView(window, new LinearLayout.LayoutParams(-1, listH));
            panel.addView(inner, new FrameLayout.LayoutParams(-1, -1));
            lists[i] = list;
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(colW, colH);
            cl.leftMargin = side + i * (colW + gap);
            cl.topMargin = gridTop;
            root.addView(panel, cl);
        }
        return root;
    }

    /** The three slanted stripes before SPORTS. */
    private final class Stripes extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Stripes() {
            super(Scoreboard.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = px(10), gap = px(6), h = getHeight();
            float slant = h * (float) Math.tan(Math.toRadians(14));
            for (int i = 0; i < 3; i++) {
                float x = i * (w + gap);
                path.reset();
                path.moveTo(x + slant, 0);
                path.lineTo(x + slant + w, 0);
                path.lineTo(x + w, h);
                path.lineTo(x, h);
                path.close();
                paint.setColor(ACCENT[i]);
                c.drawPath(path, paint);
            }
        }
    }

    /** "IST": the stick's time zone data names India's zone only as "GMT+05:30". */
    private static String zone() {
        TimeZone tz = TimeZone.getDefault();
        if (tz.getID().equals("Asia/Kolkata") || tz.getID().equals("Asia/Calcutta")) return "IST";
        return tz.getDisplayName(false, TimeZone.SHORT, EN);
    }

    /** Icon tile, the sport's name, and a count or note on the right. */
    private View header(int i) {
        LinearLayout h = row();
        h.setPadding(px(28), px(22), px(28), px(22));
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(round(SOFT_ACCENT[i], px(12)));
        ImageView ic = icon(ICONS[i], ACCENT[i], 26);
        tile.addView(ic, new FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER));
        h.addView(tile, new LinearLayout.LayoutParams(px(44), px(44)));
        h.addView(cond(NAMES[i].toUpperCase(EN), 32, "Bold", TEXT, 0.06f), grow(px(14), px(14)));
        metas[i] = cond("", 18, "SemiBold", MUTED, 0.1f);
        h.addView(metas[i]);
        return h;
    }

    // ---- content ----

    /** Rebuilds every column for the current tab from the cache, keeping focus where it was. */
    private void fill() {
        if (isFinishing()) return;
        long now = System.currentTimeMillis();
        Sports.Data d = Sports.load(this);
        entries.clear();
        shown.clear();
        for (int i = 0; i < SPORTS.length; i++) {
            List<Entry> list = new ArrayList<>();
            metas[i].setText(tab == FIXTURES ? fixtures(list, d, i, now) : results(list, d, i, now));
            entries.add(list);
            shown.add(new boolean[list.size()]);
        }
        for (int i = 0; i < SPORTS.length; i++) {
            first[i] = Math.min(first[i], Math.max(0, entries.get(i).size() - 1));
            render(i);
        }
        if (!inTabs) {
            row = nearest(col, row);
            if (row < 0) inTabs = true;
        }
        paint();
    }

    /** Lays out column {@code i} from entry {@link #first}[i]; those that end above the fade count as shown. */
    private void render(int i) {
        LinearLayout list = lists[i];
        list.removeAllViews();
        List<Entry> all = entries.get(i);
        boolean[] on = new boolean[all.size()];
        int width = View.MeasureSpec.makeMeasureSpec(listW, View.MeasureSpec.EXACTLY);
        int fit = listH - px(96), used = 0;
        for (int k = first[i]; k < all.size() && used < listH; k++) {
            Entry e = all.get(k);
            int below = e.focusable ? px(16 - 2 * RING) : 0;
            list.addView(e.view, lp(-1, -2, 0, e.top, 0, below));
            e.view.measure(width, View.MeasureSpec.UNSPECIFIED);
            used += e.view.getMeasuredHeight() + e.top + below;
            on[k] = used <= fit || k == first[i];
        }
        shown.set(i, on);
    }

    private Entry card(View v, Sports.Fixture f, int fill, int border, float radius) {
        return new Entry(v, true, f, fill, border, px(radius), 0);
    }

    private Entry label(String words) {
        TextView t = cond(words, 16, "SemiBold", MUTED, 0.14f);
        t.setPadding(px(RING), 0, 0, px(10));
        return new Entry(t, false, null, 0, 0, 0, px(14));
    }

    private Entry empty(String words) {
        LinearLayout v = row();
        v.setGravity(Gravity.CENTER);
        v.setPadding(px(28), px(28), px(28), px(28));
        GradientDrawable box = round(0, px(16));
        box.setStroke(Math.max(1, px(1)), DASH, px(6), px(5));
        v.setBackground(box);
        v.addView(icon(R.drawable.ic_calendar, MUTED, 20));
        v.addView(body(words, 18, false, MUTED), lp(-2, -2, px(10), 0, 0, 0));
        FrameLayout pad = new FrameLayout(this);
        pad.setPadding(px(RING), px(RING), px(RING), px(RING));
        pad.addView(v);
        return new Entry(pad, false, null, 0, 0, 0, 0);
    }

    /** A focusable card's box: room for the ring, then the design's padding; children are spaced by {@code gap}. */
    private LinearLayout box(float padV, float padH, float gap) {
        LinearLayout v = column();
        int o = px(RING);
        v.setPadding(o + px(padH), o + px(padV), o + px(padH), o + px(padV));
        v.setTag(px(gap));
        return v;
    }

    private void put(LinearLayout card, View child) {
        int gap = card.getChildCount() == 0 ? 0 : (Integer) card.getTag();
        card.addView(child, lp(-1, -2, 0, gap, 0, 0));
    }

    /** A row with one view on the left taking the room, and one on the right. */
    private LinearLayout spread(View left, View right) {
        LinearLayout r = row();
        r.addView(left, grow(0, 0));
        if (right != null) r.addView(right, lp(-2, -2, px(12), 0, 0, 0));
        return r;
    }

    private TextView tag(String words, int i, boolean plain) {
        TextView t = cond(words.toUpperCase(EN), 16, "Bold", plain ? SOFT : ACCENT[i], 0.1f);
        t.setPadding(px(10), px(4), px(10), px(4));
        t.setBackground(round(plain ? CHIP : SOFT_ACCENT[i], px(6)));
        return t;
    }

    /** A team code in a chip, or a player's initials in a circle. */
    private TextView code(String words, boolean avatar, int colour) {
        TextView t = cond(words, avatar ? 18 : 20, "Bold", colour, 0.04f);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(CHIP, avatar ? px(22) : px(8)));
        t.setLayoutParams(avatar ? new LinearLayout.LayoutParams(px(44), px(44)) : new LinearLayout.LayoutParams(px(56), px(36)));
        return t;
    }

    /** "12:30" with a small "PM" after it. */
    private static CharSequence clock(long t) {
        String hm = new SimpleDateFormat("h:mm", EN).format(new Date(t));
        String ap = new SimpleDateFormat("a", EN).format(new Date(t)).toUpperCase(EN);
        SpannableString out = new SpannableString(hm + " " + ap);
        out.setSpan(new RelativeSizeSpan(0.46f), hm.length(), out.length(), 0);
        return out;
    }

    /** A day over a time, right-aligned. */
    private View when(String day, long t, int dayColour, float size) {
        LinearLayout v = column();
        v.setGravity(Gravity.END);
        v.addView(cond(day.toUpperCase(EN), 17, "SemiBold", dayColour, 0.12f));
        TextView time = cond("", size, "Bold", TEXT, 0);
        time.setText(clock(t));
        v.addView(time, lp(-2, -2, 0, px(4), 0, 0));
        return v;
    }

    /** "Today", "Tomorrow", "Yesterday", the weekday within a week (if {@code shortDay}), else "Tue 27 Oct". */
    private static String day(long t, long now, boolean shortDay) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(t);
        int days = (int) Math.round((startOfDay(b) - startOfDay(a)) / 86400_000.0);
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        if (days == -1) return "Yesterday";
        if (shortDay && days > 1 && days < 7) return new SimpleDateFormat("EEE", EN).format(new Date(t));
        return new SimpleDateFormat("EEE d MMM", EN).format(new Date(t));
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** A hairline, then the venue after a pin, or after the day for a result. */
    private View foot(String day, String where) {
        LinearLayout v = column();
        View line = new View(this);
        line.setBackgroundColor(CHIP);
        v.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, px(1))));
        LinearLayout r = row();
        if (day != null) {
            r.addView(body(day, 18, false, day.startsWith("●") ? LIVE : SOFT));
            if (!where.isEmpty()) r.addView(body("·", 18, false, MUTED), lp(-2, -2, px(8), 0, px(8), 0));
        } else if (!where.isEmpty()) {
            r.addView(icon(R.drawable.ic_pin, MUTED, 18), lp(px(18), px(18), 0, 0, px(8), 0));
        }
        r.addView(body(where, 18, false, MUTED), grow(0, 0));
        v.addView(r, lp(-1, -2, 0, px(14), 0, 0));
        return v;
    }

    private View pinned(String where) {
        LinearLayout r = row();
        r.addView(icon(R.drawable.ic_pin, MUTED, 18), lp(px(18), px(18), 0, 0, px(8), 0));
        r.addView(body(where, 18, false, MUTED), grow(0, 0));
        return r;
    }

    /** "India tour of New Zealand 2026/27" without the season. */
    private static String tournament(Sports.Fixture f) {
        return f.event.replaceAll("\\s+\\d{4}(/\\d{2,4})?$", "");
    }

    /** "United States Grand Prix" to "United States GP". */
    private static String gp(String race) {
        return race.replace("Grand Prix", "GP");
    }

    /** The first {@code n} parts of "Circuit of the Americas, Austin, USA". */
    private static String place(String venue, int n) {
        String[] parts = venue.split(", ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(n, parts.length); i++) out.append(i > 0 ? ", " : "").append(parts[i]);
        return out.toString();
    }

    private static boolean tbd(String name) {
        return name.isEmpty() || "TBD".equalsIgnoreCase(name);
    }

    private static String initials(String name) {
        if (tbd(name)) return "?";
        StringBuilder out = new StringBuilder();
        for (String w : name.split(" ")) if (!w.isEmpty() && out.length() < 2) out.append(Character.toUpperCase(w.charAt(0)));
        return out.length() == 0 ? "?" : out.toString();
    }

    /** ESPN's abbreviation ("IND-W" as "IND"), or the name's first letters. */
    private static String shortCode(String abbr, String name) {
        if (abbr.isEmpty()) return name.length() > 3 ? name.substring(0, 3).toUpperCase(EN) : name.toUpperCase(EN);
        return abbr.replace("-W", "");
    }

    /** ESPN's "India won by 4 wkts (2b rem)" in words: "India won by 4 wickets, 2 balls left". */
    private static String spell(String note) {
        String s = BALLS.matcher(note).replaceAll(", $1 balls left").replace(", 1 balls", ", 1 ball");
        return s.replace(" wkts", " wickets").replace(" wkt", " wicket").trim();
    }

    // ---- fixtures ----

    /** Fills a column's Fixtures; returns the note for its header. */
    private String fixtures(List<Entry> out, Sports.Data d, int i, long now) {
        String sport = SPORTS[i];
        if (Sports.F1.equals(sport)) return f1Fixtures(out, d, i, now);
        List<Sports.Fixture> live = new ArrayList<>(), next = new ArrayList<>();
        for (Sports.Fixture f : d.fixtures) {
            if (!f.sport.equals(sport)) continue;
            if (f.live()) live.add(f);
            else if ("pre".equals(f.state)) next.add(f);
        }
        for (Sports.Fixture f : live) out.add(card(scoreCard(f, i, now), f, CARD, 0, 16));
        boolean tennis = Sports.TENNIS.equals(sport);
        for (int k = 0; k < next.size(); k++) {
            Sports.Fixture f = next.get(k);
            if (!tennis && k == FULL_CARDS) out.add(label("LATER"));
            if (!tennis && k >= FULL_CARDS) {
                out.add(card(laterRow(f, i), f, CARD, 0, 12));
                continue;
            }
            boolean soonest = k == 0 && live.isEmpty();
            out.add(card(fixtureCard(f, i, soonest, now), f, CARD, soonest ? ACCENT[i] : 0, 16));
        }
        if (out.isEmpty()) {
            out.add(empty(tennis ? "Matches show once a draw is out" : "Nothing scheduled"));
            return "";
        }
        return live.isEmpty() ? next.size() + " UPCOMING" : live.size() + " LIVE";
    }

    /** Tournament and stage; the sides beside the day and time; the venue. */
    private View fixtureCard(Sports.Fixture f, int i, boolean soonest, long now) {
        boolean tennis = Sports.TENNIS.equals(f.sport);
        LinearLayout c = box(20, 22, 16);
        put(c, spread(body(tournament(f), 18, false, MUTED), f.stage.isEmpty() ? null : tag(f.stage, i, false)));
        LinearLayout teams = column();
        teams.addView(side(f, true, tennis));
        teams.addView(side(f, false, tennis), lp(-1, -2, 0, px(12), 0, 0));
        LinearLayout middle = row();
        middle.addView(teams, grow(0, 0));
        middle.addView(when(day(f.start, now, false), f.start, soonest ? ACCENT[i] : MUTED, 48), lp(-2, -2, px(12), 0, 0, 0));
        put(c, middle);
        put(c, foot(null, tennis ? place(f.venue, 2) : f.venue));
        return c;
    }

    /** A code or avatar and the name, for one side of a match to come. */
    private View side(Sports.Fixture f, boolean a, boolean tennis) {
        LinearLayout r = row();
        String name = a ? f.a : f.b;
        int colour = tbd(name) ? MUTED : TEXT;
        r.addView(code(tennis ? initials(name) : shortCode(a ? f.aShort : f.bShort, name), tennis, colour));
        r.addView(body(tennis ? Sports.surname(name) : name, 26, true, colour), grow(px(14), 0));
        return r;
    }

    /** One line under LATER: tournament, "IND v SA", stage. */
    private View laterRow(Sports.Fixture f, int i) {
        LinearLayout r = row();
        int o = px(RING);
        r.setPadding(o + px(18), o + px(14), o + px(18), o + px(14));
        r.addView(body(tournament(f), 18, false, MUTED), grow(0, 0));
        r.addView(cond(shortCode(f.aShort, f.a) + " v " + shortCode(f.bShort, f.b), 22, "Bold", TEXT, 0), lp(-2, -2, px(12), 0, 0, 0));
        if (!f.stage.isEmpty()) r.addView(tag(f.stage, i, true), lp(-2, -2, px(12), 0, 0, 0));
        return r;
    }

    /** F1: the nearest weekend in full, then the rounds after it. */
    private String f1Fixtures(List<Entry> out, Sports.Data d, int i, long now) {
        String round = null;
        for (Sports.Fixture f : d.fixtures) {
            if (Sports.F1.equals(f.sport) && !f.session.isEmpty() && !f.done()) {
                round = f.round;
                break;
            }
        }
        if (round == null) {
            out.add(empty("No races scheduled"));
            return "";
        }
        List<Sports.Fixture> weekend = new ArrayList<>(), later = new ArrayList<>();
        for (Sports.Fixture f : d.fixtures) {
            if (!Sports.F1.equals(f.sport) || f.session.isEmpty()) continue;
            if (round.equals(f.round)) weekend.add(f);
            else if (!f.done() && "Race".equals(f.session)) later.add(f);
        }
        Sports.Fixture race = weekend.get(weekend.size() - 1);
        boolean thisWeek = race.start - now < 7 * 86400_000L;
        out.add(card(weekendCard(weekend, i, thisWeek ? "THIS WEEKEND" : "ROUND " + round, now), race, CARD, 0, 16));
        if (!later.isEmpty()) out.add(label("NEXT ROUNDS"));
        for (Sports.Fixture f : later) out.add(card(roundCard(f, i), f, CARD, 0, 16));
        return thisWeek ? "RACE WEEKEND" : "NEXT ROUND";
    }

    private View weekendCard(List<Sports.Fixture> sessions, int i, String kicker, long now) {
        Sports.Fixture race = sessions.get(sessions.size() - 1);
        LinearLayout c = box(20, 22, 16);
        LinearLayout title = column();
        title.addView(cond(kicker, 16, "Bold", ACCENT[i], 0.14f));
        title.addView(cond(gp(race.event).toUpperCase(EN), 40, "Bold", TEXT, 0), lp(-2, -2, 0, px(6), 0, 0));
        put(c, title);
        put(c, pinned(place(race.venue, 2)));
        LinearLayout list = column();
        boolean marked = false;
        for (Sports.Fixture f : sessions) {
            boolean isNext = !marked && !f.done();
            if (isNext) marked = true;
            list.addView(sessionRow(f, i, isNext, now), lp(-1, -2, 0, list.getChildCount() == 0 ? px(4) : px(6), 0, 0));
        }
        put(c, list);
        return c;
    }

    /** Day over time, the session, and NEXT, LIVE or the chequered flag. Done sessions fade. */
    private View sessionRow(Sports.Fixture f, int i, boolean isNext, long now) {
        boolean race = "Race".equals(f.session);
        LinearLayout r = row();
        r.setPadding(px(14), px(14), px(14), px(14));
        if (race) r.setBackground(round(SOFT_ACCENT[i], px(12)));
        r.setAlpha(f.done() ? 0.45f : 1f);
        LinearLayout d = column();
        d.addView(cond(day(f.start, now, true).toUpperCase(EN), 16, "SemiBold", isNext || race ? ACCENT[i] : MUTED, 0.12f));
        TextView t = cond("", 30, "Bold", TEXT, 0);
        t.setText(clock(f.start));
        d.addView(t, lp(-2, -2, 0, px(2), 0, 0));
        r.addView(d, new LinearLayout.LayoutParams(px(152), -2));
        r.addView(body(f.stage, race ? 28 : 24, true, TEXT), grow(px(12), px(12)));
        if (f.live() || isNext) {
            TextView pill = cond(f.live() ? "LIVE" : "NEXT", 16, "Bold", f.live() ? TEXT : BG, 0.12f);
            pill.setPadding(px(12), px(5), px(12), px(5));
            pill.setBackground(round(f.live() ? LIVE : ACCENT[i], px(16)));
            r.addView(pill);
        } else if (race) {
            ImageView flag = new ImageView(this);
            flag.setImageResource(R.drawable.ic_checkered);
            r.addView(flag, new LinearLayout.LayoutParams(px(34), px(24)));
        }
        return r;
    }

    /** A later round: its number and dates, the Grand Prix, the circuit and the race time. */
    private View roundCard(Sports.Fixture f, int i) {
        LinearLayout c = box(20, 22, 10);
        SimpleDateFormat dm = new SimpleDateFormat("d MMM", EN);
        // Weekends run from the Friday to the race; in IST a Sunday race can fall on Monday.
        String dates = dm.format(new Date(f.start - 3 * 86400_000L)) + " – " + dm.format(new Date(f.start));
        put(c, spread(cond("ROUND " + f.round, 16, "Bold", ACCENT[i], 0.14f), tag(dates, i, false)));
        put(c, cond(gp(f.event).toUpperCase(EN), 32, "Bold", TEXT, 0));
        put(c, spread(body(place(f.venue, 1), 18, false, MUTED),
                when("Race · " + new SimpleDateFormat("EEE", EN).format(new Date(f.start)), f.start, MUTED, 30)));
        return c;
    }

    // ---- results ----

    private String results(List<Entry> out, Sports.Data d, int i, long now) {
        String sport = SPORTS[i];
        List<Sports.Fixture> done = new ArrayList<>();
        for (Sports.Fixture f : d.fixtures) {
            if (!f.sport.equals(sport) || !f.done() || !f.session.isEmpty()) continue;
            if (!Sports.F1.equals(sport) && now - f.start > RECENT_MS) continue;
            done.add(f);
        }
        Collections.reverse(done);      // newest first
        if (done.isEmpty()) {
            out.add(empty("No recent results"));
            return "";
        }
        if (Sports.F1.equals(sport)) {
            out.add(card(raceCard(done.get(0), i, now), done.get(0), CARD, 0, 16));
            if (done.size() > 1) out.add(label("EARLIER"));
            for (int k = 1; k < done.size(); k++) out.add(card(earlierRace(done.get(k), i, now), done.get(k), CARD, 0, 16));
            return "LAST RACE";
        }
        for (Sports.Fixture f : done) out.add(card(scoreCard(f, i, now), f, CARD, 0, 16));
        return Sports.TENNIS.equals(sport) ? tournament(done.get(0)).toUpperCase(EN) : "LAST 7 DAYS";
    }

    /** A played or live match: tournament and stage, each side with its score, how it ended, then day and venue. */
    private View scoreCard(Sports.Fixture f, int i, long now) {
        boolean tennis = Sports.TENNIS.equals(f.sport);
        boolean retired = f.note.toLowerCase(EN).contains("retired");
        LinearLayout c = box(20, 22, 16);
        put(c, spread(body(tournament(f), 18, false, MUTED), f.stage.isEmpty() ? null : tag(f.stage, i, false)));
        LinearLayout teams = column();
        teams.addView(scoreRow(f, true, tennis, retired, i));
        teams.addView(scoreRow(f, false, tennis, retired, i), lp(-1, -2, 0, px(12), 0, 0));
        put(c, teams);
        if (!tennis && !f.note.isEmpty()) {
            LinearLayout res = row();
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            boolean decided = f.live() || !f.won.isEmpty();
            g.setColor(f.live() ? LIVE : decided ? ACCENT[i] : NO_RESULT);
            dot.setBackground(g);
            res.addView(dot, new LinearLayout.LayoutParams(px(10), px(10)));
            res.addView(body(spell(f.note), 21, true, decided ? TEXT : SOFT), grow(px(10), 0));
            put(c, res);
        }
        String when = f.live() ? "●  Live" : day(f.start, now, false);
        String where = tennis && retired ? Sports.surname("a".equals(f.won) ? f.b : f.a) + " retired"
                : tennis ? place(f.venue, 2) : f.venue;
        put(c, foot(when, where));
        return c;
    }

    /** One side: code or avatar, name, the winner's mark (tennis), and runs and overs or games per set. */
    private View scoreRow(Sports.Fixture f, boolean a, boolean tennis, boolean retired, int i) {
        boolean won = f.done() && "a".equals(f.won) == a && !f.won.isEmpty();
        boolean lost = f.done() && !f.won.isEmpty() && !won;
        int colour = lost ? DIM : TEXT;
        String name = a ? f.a : f.b, mine = a ? f.scoreA : f.scoreB, theirs = a ? f.scoreB : f.scoreA;
        LinearLayout r = row();
        r.addView(code(tennis ? initials(name) : shortCode(a ? f.aShort : f.bShort, name), tennis, colour));
        TextView n = body(tennis ? Sports.surname(name) : name, 26, true, colour);
        if (!tennis) {
            r.addView(n, grow(px(14), px(12)));
            if (!mine.isEmpty()) {
                int cut = mine.indexOf(" (");
                r.addView(cond(cut < 0 ? mine : mine.substring(0, cut), 44, "Bold", colour, 0));
                Matcher m = OVERS.matcher(mine);
                TextView ov = cond(m.find() ? m.group(1) : "", 18, "Medium", MUTED, 0);
                ov.setGravity(Gravity.END);
                r.addView(ov, new LinearLayout.LayoutParams(px(56), -2));
            }
            return r;
        }
        r.addView(n, lp(-2, -2, px(14), 0, 0, 0));
        if (won) r.addView(icon(R.drawable.ic_win, ACCENT[i], 16), lp(px(16), px(16), px(10), 0, 0, 0));
        if (lost && retired) r.addView(tag("RET", i, true), lp(-2, -2, px(10), 0, 0, 0));
        // A spacer, zero high: a plain View with wrap-content height would take all the height offered.
        r.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
        String[] g = mine.trim().split(" "), o = theirs.trim().split(" ");
        for (int k = 0; k < g.length && !g[k].isEmpty(); k++) {
            boolean set = k < o.length && num(g[k]) > num(o[k]);
            TextView cell = cond(g[k], 30, "Bold", set ? TEXT : DIM, 0);
            cell.setGravity(Gravity.CENTER);
            if (set) cell.setBackground(round(WIN_CELL, px(8)));
            r.addView(cell, lp(px(44), px(46), px(4), 0, 0, 0));
        }
        return r;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The last race: the winner large, the rest of the podium with gaps, then the circuit. */
    private View raceCard(Sports.Fixture f, int i, long now) {
        LinearLayout c = box(20, 22, 16);
        LinearLayout title = column();
        title.addView(spread(cond("RACE RESULT", 16, "Bold", ACCENT[i], 0.14f), body(day(f.start, now, false), 18, false, SOFT)));
        title.addView(cond(gp(f.event).toUpperCase(EN), 40, "Bold", TEXT, 0), lp(-2, -2, 0, px(6), 0, 0));
        put(c, title);
        LinearLayout p1 = row();
        p1.setPadding(px(18), px(18), px(18), px(18));
        p1.setBackground(round(SOFT_ACCENT[i], px(14)));
        p1.addView(cond("P1", 64, "BoldItalic", ACCENT[i], 0));
        LinearLayout who = column();
        who.addView(cond("WINNER", 18, "SemiBold", SOFT, 0.12f));
        who.addView(body(f.a, 30, true, TEXT), lp(-2, -2, 0, px(2), 0, 0));
        p1.addView(who, grow(px(18), px(12)));
        p1.addView(driver(f.aShort));
        put(c, p1);
        if (!f.podium.isEmpty()) {
            LinearLayout podium = column();
            String[] places = f.podium.split(";");
            for (int k = 0; k < places.length; k++) {
                String[] p = places[k].split("\\|", -1);
                LinearLayout r = row();
                r.setPadding(px(18), px(14), px(18), px(14));
                r.addView(cond("P" + (k + 2), 34, "BoldItalic", SOFT, 0), new LinearLayout.LayoutParams(px(54), -2));
                r.addView(body(p[0], 24, true, MUTED), grow(0, 0));
                r.addView(cond(p.length > 1 ? p[1] : "", 20, "SemiBold", MUTED, 0));
                podium.addView(r);
                View line = new View(this);
                line.setBackgroundColor(CHIP);
                podium.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, px(1))));
            }
            put(c, podium);
        }
        put(c, pinned(f.venue));
        return c;
    }

    private View earlierRace(Sports.Fixture f, int i, long now) {
        LinearLayout c = box(20, 22, 10);
        put(c, spread(cond("RACE RESULT", 16, "Bold", ACCENT[i], 0.14f), body(day(f.start, now, false), 18, false, SOFT)));
        put(c, cond(gp(f.event).toUpperCase(EN), 32, "Bold", TEXT, 0));
        LinearLayout r = row();
        r.addView(cond("P1", 40, "BoldItalic", ACCENT[i], 0));
        r.addView(body(f.a, 26, true, TEXT), grow(px(14), px(12)));
        r.addView(driver(f.aShort));
        put(c, r);
        return c;
    }

    private TextView driver(String code) {
        TextView t = cond(code, 22, "Bold", TEXT, 0);
        t.setPadding(px(12), px(6), px(12), px(6));
        t.setBackground(round(BG, px(8)));
        return t;
    }

    // ---- focus ----

    /** Draws focus: a ring on the tab bar while in it, the selected tab, and the focused card's ring. */
    private void paint() {
        GradientDrawable bar = round(TAB_BAR, px(40));
        if (inTabs) bar.setStroke(px(3), TEXT);
        tabBar.setBackground(bar);
        for (int k = 0; k < tabs.size(); k++) {
            TextView t = tabs.get(k);
            boolean on = k == tab;
            t.setBackground(on ? round(TEXT, px(28)) : null);
            t.setTextColor(on ? BG : MUTED);
            t.setTypeface(Fonts.file(this, on ? "BarlowCondensed-Bold" : "BarlowCondensed-SemiBold"));
        }
        for (int c = 0; c < entries.size(); c++) {
            List<Entry> list = entries.get(c);
            for (int k = 0; k < list.size(); k++) {
                Entry e = list.get(k);
                if (e.focusable) e.view.setBackground(ring(e.fill, e.border, e.radius, !inTabs && c == col && k == row));
            }
        }
    }

    /** The focusable entry in column {@code c} nearest {@code r} that is on screen, or -1. */
    private int nearest(int c, int r) {
        List<Entry> list = entries.get(c);
        boolean[] on = shown.get(c);
        int best = -1;
        for (int k = 0; k < list.size(); k++) {
            if (!list.get(k).focusable || !on[k]) continue;
            if (best < 0 || Math.abs(k - r) < Math.abs(best - r)) best = k;
        }
        return best;
    }

    /** The next focusable entry after ({@code dir} 1) or before (-1) {@code r}, or -1. */
    private int step(int c, int r, int dir) {
        List<Entry> list = entries.get(c);
        for (int k = r + dir; k >= 0 && k < list.size(); k += dir) if (list.get(k).focusable) return k;
        return -1;
    }

    /** Scrolls a column so entry {@code r} is fully on screen, with the label just above it if there is one. */
    private void scrollTo(int c, int r) {
        if (r < 0 || shown.get(c)[r]) return;
        if (r < first[c]) {
            first[c] = r > 0 && !entries.get(c).get(r - 1).focusable ? r - 1 : r;
            render(c);
            return;
        }
        while (first[c] < r && !shown.get(c)[r]) {
            first[c]++;
            render(c);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean nav = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) key(code);
        return true;
    }

    private void key(int code) {
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (detail != null) {
            if (select) closeDetail();
            return;
        }
        if (inTabs) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && tab > 0) setTab(tab - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && tab < TABS.length - 1) setTab(tab + 1);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN || select) {
                int r = nearest(col, first[col]);
                for (int c = 0; r < 0 && c < SPORTS.length; c++) {
                    r = nearest(c, first[c]);
                    if (r >= 0) col = c;
                }
                if (r < 0) return;      // nothing to focus anywhere
                row = r;
                inTabs = false;
                paint();
            }
            return;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                int r = step(col, row, -1);
                if (r < 0) {
                    inTabs = true;
                    if (first[col] > 0) {
                        first[col] = 0;
                        render(col);
                    }
                } else {
                    row = r;
                }
                break;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int r = step(col, row, 1);
                if (r >= 0) row = r;
                break;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, c = col + dir;
                while (c >= 0 && c < SPORTS.length && nearest(c, row) < 0) c += dir;
                if (c < 0 || c >= SPORTS.length) return;
                row = nearest(c, row);
                col = c;
                break;
            }
            default: {
                Entry en = entries.get(col).get(row);
                if (en.fixture != null) openDetail(en.fixture);
                return;
            }
        }
        if (!inTabs) scrollTo(col, row);
        paint();
    }

    private void setTab(int t) {
        tab = t;
        Arrays.fill(first, 0);
        fill();
    }

    @Override
    public void onBackPressed() {
        if (detail != null) closeDetail();
        else finish();
    }

    // ---- match detail ----

    /** A panel over the columns with everything known about one match, session or race. */
    private void openDetail(Sports.Fixture f) {
        long now = System.currentTimeMillis();
        int i = Arrays.asList(SPORTS).indexOf(f.sport);
        LinearLayout v = column();
        v.setPadding(px(48), px(50), px(48), px(44));
        v.setBackground(panel(ACCENT[i], 24));
        v.setElevation(px(24));

        boolean session = Sports.F1.equals(f.sport) && !f.session.isEmpty();
        String kicker = session ? "ROUND " + f.round : f.stage;
        if (!kicker.isEmpty()) v.addView(cond(kicker.toUpperCase(EN), 18, "Bold", ACCENT[i], 0.14f));
        v.addView(cond((Sports.F1.equals(f.sport) ? gp(f.event) : tournament(f)).toUpperCase(EN), 48, "Bold", TEXT, 0),
                lp(-2, -2, 0, px(8), 0, 0));

        List<String[]> facts = new ArrayList<>();
        if (session) {
            for (Sports.Fixture x : Sports.load(this).fixtures) {
                if (!x.session.isEmpty() && x.round.equals(f.round)) {
                    facts.add(new String[] {x.stage, x.live() ? "Underway" : x.done() ? "Done"
                            : day(x.start, now, true) + "  " + clock(x.start)});
                }
            }
            facts.add(new String[] {"Circuit", f.venue});
        } else if (Sports.F1.equals(f.sport)) {
            facts.add(new String[] {"Raced", day(f.start, now, false)});
            facts.add(new String[] {"P1", f.a});
            String[] places = f.podium.isEmpty() ? new String[0] : f.podium.split(";");
            for (int k = 0; k < places.length; k++) {
                String[] x = places[k].split("\\|", -1);
                facts.add(new String[] {"P" + (k + 2), x[0] + "   " + (x.length > 1 ? x[1] : "")});
            }
            facts.add(new String[] {"Circuit", f.venue});
        } else {
            facts.add(new String[] {f.done() ? "Played" : "Date", day(f.start, now, false) + "  " + clock(f.start) + "  " + zone()});
            facts.add(new String[] {"Match", f.a + "  v  " + f.b});
            if (!f.scoreA.isEmpty() || !f.scoreB.isEmpty()) {
                facts.add(new String[] {"Score", Sports.TENNIS.equals(f.sport) ? f.sets()
                        : f.aShort + " " + f.scoreA + (f.scoreB.isEmpty() ? "" : "     " + f.bShort + " " + f.scoreB)});
            }
            if (!f.venue.isEmpty()) facts.add(new String[] {"Venue", f.venue});
            if (!f.note.isEmpty()) facts.add(new String[] {"Status", spell(f.note)});
        }
        for (int k = 0; k < facts.size(); k++) {
            LinearLayout r = row();
            r.addView(cond(facts.get(k)[0].toUpperCase(EN), 18, "SemiBold", MUTED, 0.14f), new LinearLayout.LayoutParams(px(240), -2));
            r.addView(body(facts.get(k)[1], 26, true, TEXT), grow(0, 0));
            v.addView(r, lp(-1, -2, 0, px(k == 0 ? 32 : 16), 0, 0));
        }

        scrim = new View(this);
        scrim.setBackgroundColor(0xB3000000);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        detail = v;
        v.setAlpha(0f);
        root.addView(v, new FrameLayout.LayoutParams(px(1120), -2, Gravity.CENTER));
        v.animate().alpha(1f).setDuration(180).start();
    }

    private void closeDetail() {
        root.removeView(scrim);
        root.removeView(detail);
        detail = null;
        scrim = null;
    }
}
