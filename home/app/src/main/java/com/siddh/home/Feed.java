package com.siddh.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures and weather, fetched in the background and cached under files/, so the home screen and
 * the screensaver only ever read local files.
 *
 * - Streaming apps: titles picked for the owner's taste ({@link Taste}), trending first (TMDB
 *   backdrop and title logo).
 * - FanCode: the next Formula 1 races with session times (Jolpica schedule, TheSportsDB and
 *   Wikipedia pictures).
 * - Spotify: this week's top albums and podcasts in India (Apple's public charts).
 * - Every app, and the screensaver: Commons featured photos picked for the owner's taste, as a
 *   shared pool (Bing's photos of the day if Commons is down).
 * - Weather for the saved city, from Open-Meteo.
 *
 * On disk: files/gallery/<package>/<n>.jpg, optional <n>.png (title logo) and <n>.txt
 * (title, overline, caption, flags), replaced as a whole on each refresh.
 */
final class Feed {
    private static final String TAG = "HomeFeed";
    private static final long IMAGES_EVERY_MS = 12 * 3600_000L;
    private static final long WEATHER_EVERY_MS = 30 * 60_000L;
    private static final int PER_APP = 5;
    private static final String TMDB = "https://api.themoviedb.org/3";
    private static final String TMDB_IMG = "https://image.tmdb.org/t/p/";
    private static final String POOL = "_pool";
    private static final String FANCODE = "com.fancode.tv", SPOTIFY = "com.spotify.tv.android";

    /** Package, a key to match TMDB's provider name against. */
    private static final String[][] SERVICES = {
            {"com.netflix.ninja", "netflix"},
            {"com.amazon.firebat", "amazonprimevideo"},
            {"in.startv.hotstar", "hotstar"},
            {"com.onemainstream.sonyliv.android", "sonyliv"},
            {"com.apple.atve.amazon.appletv", "appletv"},
    };

    /** Flags on an item: show the overline, show the caption, show the picture as a card. */
    static final String OVERLINE = "overline", CAPTION = "caption", CARD = "card";
    /** Line break inside a caption (the .txt files are one field per line). */
    static final String LINE = "  |  ";

    static final class Item {
        final File image, logo;
        /** A looping clip that plays instead of the picture (the picture is its still). */
        File video;
        final String title, overline, caption, flags;

        Item(File image, File logo, String title, String overline, String caption, String flags) {
            this.image = image;
            this.logo = logo;
            this.title = title;
            this.overline = overline;
            this.caption = caption;
            this.flags = flags;
        }

        boolean is(String flag) {
            return flags.contains(flag);
        }
    }

    static final class Weather {
        final double temp, high, low;
        final int code;
        final boolean day;
        final String city;

        Weather(double temp, double high, double low, int code, boolean day, String city) {
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.code = code;
            this.day = day;
            this.city = city;
        }

        String describe() {
            return Feed.describe(code);
        }
    }

    private static final ExecutorService net = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;

    private Feed() {}

    private static File dir(Context c, String name) {
        return new File(new File(c.getFilesDir(), "gallery"), name);
    }

    // ---- reading (any thread, cheap) ----

    /** The slides for one app: what it last played, its own pictures, then pool photos. */
    static List<Item> items(Context c, String pkg, CharSequence label) {
        List<Item> out = new ArrayList<>();
        File wdir = Watcher.dir(c);
        if (wdir != null) {
            String[] t = lines(new File(wdir, pkg + ".txt"));
            File img = new File(wdir, pkg + ".jpg");
            if (t != null && img.isFile()) {
                boolean music = pkg.contains("spotify") || pkg.contains("music");
                out.add(new Item(img, null, t[0], music ? "Listen again" : "Continue watching", t[1],
                        OVERLINE + "," + CAPTION + (music ? "," + CARD : "")));
            }
        }
        File pushed = new File(c.getExternalFilesDir("shelf"), pkg + ".jpg");
        if (pushed.isFile()) out.add(new Item(pushed, null, "", "", "", ""));
        out.addAll(read(dir(c, pkg)));
        if (out.size() < 3) {
            List<Item> pool = read(dir(c, POOL));
            // Each app starts at a different photo, so neighbours do not look alike.
            Collections.rotate(pool, Math.abs(pkg.hashCode()) % Math.max(1, pool.size()));
            for (Item p : pool) {
                if (out.size() >= PER_APP) break;
                out.add(new Item(p.image, null, "", "", "", ""));
            }
        }
        return out;
    }

    /** The photo pool, for the screensaver. */
    static List<Item> pool(Context c) {
        return read(dir(c, POOL));
    }

    private static List<Item> read(File d) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            File img = new File(d, i + ".jpg");
            if (!img.isFile()) continue;
            File logo = new File(d, i + ".png");
            String[] t = lines(new File(d, i + ".txt"));
            if (t == null) t = new String[] {"", "", "", ""};
            Item it = new Item(img, logo.isFile() ? logo : null, t[0], t[1], t[2], t[3]);
            File clip = new File(d, i + ".mp4");
            if (clip.isFile()) it.video = clip;
            out.add(it);
        }
        return out;
    }

    private static String[] lines(File f) {
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
            int n = in.read(buf);
            String[] l = new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8).split("\n", -1);
            return new String[] {at(l, 0), at(l, 1), at(l, 2), at(l, 3)};
        } catch (IOException e) {
            return null;
        }
    }

    private static String at(String[] a, int i) {
        return i < a.length ? a[i] : "";
    }

    static Weather weather(Context c) {
        String raw = Apps.prefs(c).getString("weather.json", null);
        if (raw == null) return null;
        try {
            JSONObject j = new JSONObject(raw);
            JSONObject cur = j.getJSONObject("current");
            JSONObject daily = j.getJSONObject("daily");
            return new Weather(cur.getDouble("temperature_2m"),
                    daily.getJSONArray("temperature_2m_max").getDouble(0),
                    daily.getJSONArray("temperature_2m_min").getDouble(0),
                    cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1, city(c));
        } catch (JSONException e) {
            return null;
        }
    }

    static String city(Context c) {
        return Apps.prefs(c).getString("city", "Pune");
    }

    static boolean has(String s) {
        return s != null && !s.isEmpty();
    }

    // ---- refreshing (background) ----

    /** Refreshes whatever is stale; runs {@code done} on the background thread if anything changed. */
    static void refresh(Context context, Runnable done) {
        final Context c = context.getApplicationContext();
        if (busy) return;
        busy = true;
        net.execute(() -> {
            try {
                SharedPreferences p = Apps.prefs(c);
                long now = System.currentTimeMillis();
                boolean changed = false;
                if (now - p.getLong("weather.at", 0) > WEATHER_EVERY_MS) {
                    changed |= fetchWeather(c);
                }
                // Also on a new day, so the streaming shelves rotate each morning (Rotation).
                TimeZone zone = TimeZone.getDefault();
                if (now - p.getLong("images.at", 0) > IMAGES_EVERY_MS
                        || !Rotation.day(now, zone).equals(Rotation.day(p.getLong("images.at", 0), zone))) {
                    boolean ok = fetchPool(c);
                    ok &= fetchServices(c);
                    if (Apps.installed(c, FANCODE)) fetchF1(c);
                    if (Apps.installed(c, SPOTIFY)) fetchCharts(c);
                    if (ok) p.edit().putLong("images.at", now).apply();
                    changed = true;
                }
                if (changed && done != null) done.run();
            } finally {
                busy = false;
            }
        });
    }

    /** Forgets the refresh times, so the next refresh fetches everything (new key, new city). */
    static void invalidate(Context c) {
        Apps.prefs(c).edit().remove("images.at").remove("weather.at").apply();
    }

    private static boolean fetchWeather(Context c) {
        SharedPreferences p = Apps.prefs(c);
        String lat = p.getString("lat", "18.5204"), lon = p.getString("lon", "73.8567");
        try {
            String json = Net.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1", null);
            new JSONObject(json);   // reject garbage before caching it
            p.edit().putString("weather.json", json).putLong("weather.at", System.currentTimeMillis()).apply();
            return true;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "weather: " + e.getMessage());
            return false;
        }
    }

    /** Looks a city up with Open-Meteo's geocoder and saves its coordinates. Blocking. */
    static boolean setCity(Context c, String city) {
        try {
            String json = Net.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + URLEncoder.encode(city, "UTF-8"), null);
            JSONObject r = new JSONObject(json).getJSONArray("results").getJSONObject(0);
            Apps.prefs(c).edit().putString("city", r.getString("name"))
                    .putString("lat", String.valueOf(r.getDouble("latitude")))
                    .putString("lon", String.valueOf(r.getDouble("longitude")))
                    .remove("weather.at").apply();
            return true;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "city: " + e.getMessage());
            return false;
        }
    }

    /**
     * Where the photo pool comes from: Wikimedia Commons searches over its Featured Pictures
     * (curated, freely licensed, no text), with how many pictures each gives per refresh. Tuned
     * from the owner's picks over 60 candidates (2026-10-23): loved were towns and cities at night
     * or blue hour (warm lamps under a deep blue sky), a village road, a river gorge, a cliff coast
     * in evening light; rejected were flat daylight blues (lakes, beaches, reefs), aurora and neon
     * glare, misty or snowy whites, wildlife, and wide panoramas. So night towns lead, and lakes,
     * beaches, fog and birds are gone; Iceland and New Zealand stay at one each for variety.
     */
    private static final Object[][] POOL_SOURCES = {
            {"incategory:\"Featured_pictures_of_cityscapes\" night", 4},
            {"incategory:\"Featured_pictures_of_cityscapes\" \"blue hour\" OR dusk OR twilight", 3},
            {"deepcat:\"Featured pictures of Germany\" \"blue hour\" OR night OR evening", 2},
            {"incategory:\"Featured_pictures_of_roads\"", 2},
            {"incategory:\"Featured_pictures_of_watercourses\"", 2},
            {"incategory:\"Featured_pictures_of_mountains\"", 1},
            {"incategory:\"Featured_pictures_of_waterfalls\"", 1},
            {"deepcat:\"Featured pictures of Iceland\"", 1},
            {"deepcat:\"Featured pictures of New Zealand\"", 1},
            {"incategory:\"Featured_pictures_of_coasts\"", 1},
            {"incategory:\"Featured_pictures_of_architecture\"", 1},
            {"deepcat:\"Featured pictures of Felidae\"", 1},
    };
    /** How many of the owner's loved pictures ({@link Taste#PHOTOS_LOVED}) join each refresh. */
    private static final int LOVED_PER_REFRESH = 3;
    /** Files never wanted in the pool: insects, and old (pre-1960) photographs. */
    private static final java.util.regex.Pattern UNWANTED = java.util.regex.Pattern.compile(
            "(?i)insect|\\bbees?\\b|bumblebee|butterfl|\\bmoths?\\b|dragonfl|damselfl|beetle|\\bwasps?\\b|hornet|"
                    + "\\bants?\\b|spider|grasshopper|locust|cicada|mantis|\\bfly\\b|\\bflies\\b|mosquito|"
                    + "\\b1[5-8]\\d\\d\\b|\\b19[0-5]\\d\\b");
    private static final int POOL_MAX = 20;

    /**
     * The shared photo pool: a few random wide pictures from each source above, plus a few of the
     * owner's loved ones, at 1920 px (all a 1080p stick can show), passing {@link #suits}. Falls
     * back to Bing's photos of the day if Commons is unreachable.
     */
    private static boolean fetchPool(Context c) {
        File tmp = fresh(dir(c, POOL + ".new"));
        List<String> urls = new ArrayList<>();
        java.util.Random random = new java.util.Random();
        for (Object[] s : POOL_SOURCES) {
            String query = (String) s[0];
            for (int attempt = 0; attempt < 2; attempt++) {
                long pause = 2000;      // Commons rate-limits quick bursts
                try {
                    List<String> wide = commonsWide(query, random.nextInt(60));
                    if (wide.isEmpty()) wide = commonsWide(query, 0);
                    Collections.shuffle(wide);
                    urls.addAll(wide.subList(0, Math.min((Integer) s[1], wide.size())));
                    attempt = 2;
                } catch (IOException | JSONException e) {
                    Log.w(TAG, "commons " + query + ": " + e.getMessage());
                    if (String.valueOf(e.getMessage()).contains("429")) pause = 30_000;
                    else attempt = 2;
                }
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        if (urls.size() < 4) urls.addAll(bing());
        Collections.shuffle(urls);
        try {   // loved pictures go in at random places early enough to survive the POOL_MAX cut
            for (String u : commonsFiles(Taste.PHOTOS_LOVED, LOVED_PER_REFRESH)) {
                urls.add(random.nextInt(Math.min(urls.size(), POOL_MAX / 2) + 1), u);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "commons loved: " + e.getMessage());
        }
        int n = 0;
        for (String u : urls) {
            if (n >= POOL_MAX) break;
            File f = new File(tmp, n + ".jpg");
            if (!Net.download(u, f)) continue;
            if (!suits(f)) {
                f.delete();
                continue;
            }
            try {
                write(tmp, n, "", "", "", "");
            } catch (IOException e) {
                continue;
            }
            n++;
        }
        if (n == 0) return false;
        swap(tmp, dir(c, POOL));
        return true;
    }

    /** 1920 px URLs of {@code count} random files from a list of Commons titles ("File:..."). */
    private static List<String> commonsFiles(String[] titles, int count) throws IOException, JSONException {
        List<String> pick = new ArrayList<>(java.util.Arrays.asList(titles));
        Collections.shuffle(pick);
        List<String> out = new ArrayList<>();
        if (pick.isEmpty() || count <= 0) return out;
        JSONObject q = new JSONObject(Net.get("https://commons.wikimedia.org/w/api.php?action=query&format=json"
                + "&titles=" + URLEncoder.encode(android.text.TextUtils.join("|", pick.subList(0, Math.min(count, pick.size()))), "UTF-8")
                + "&prop=imageinfo&iiprop=url&iiurlwidth=1920", null)).optJSONObject("query");
        JSONObject pages = q == null ? null : q.optJSONObject("pages");
        if (pages == null) return out;
        for (java.util.Iterator<String> it = pages.keys(); it.hasNext(); ) {
            JSONArray ii = pages.getJSONObject(it.next()).optJSONArray("imageinfo");
            String url = ii == null ? "" : ii.getJSONObject(0).optString("thumburl");
            if (!url.isEmpty()) out.add(url);
        }
        return out;
    }

    /**
     * 1920 px URLs of landscape-shaped photos matching a Commons file search, from a result offset.
     * No wider than 1.9:1: panoramas lose most of themselves when cropped to the 16:9 screen, and
     * the owner turned down 14 of the 17 wider ones offered.
     */
    private static List<String> commonsWide(String search, int offset) throws IOException, JSONException {
        List<String> out = new ArrayList<>();
        JSONObject q = new JSONObject(Net.get("https://commons.wikimedia.org/w/api.php?action=query&format=json"
                + "&generator=search&gsrnamespace=6&gsrlimit=30&gsroffset=" + offset
                + "&gsrsearch=" + URLEncoder.encode(search, "UTF-8")
                + "&prop=imageinfo&iiprop=url|size&iiurlwidth=1920", null)).optJSONObject("query");
        JSONObject pages = q == null ? null : q.optJSONObject("pages");
        if (pages == null) return out;
        for (java.util.Iterator<String> it = pages.keys(); it.hasNext(); ) {
            JSONObject page = pages.getJSONObject(it.next());
            JSONArray ii = page.optJSONArray("imageinfo");
            if (ii == null || UNWANTED.matcher(page.optString("title")).find()) continue;
            JSONObject info = ii.getJSONObject(0);
            int w = info.optInt("width"), h = info.optInt("height");
            String url = info.optString("thumburl");
            if (w >= 1920 && w >= h * 1.45 && w <= h * 1.9 && info.optInt("thumbwidth") <= 1920 && !url.isEmpty()) {
                out.add(url);
            }
        }
        return out;
    }

    /**
     * Whether a picture suits the screen, from a downsampled copy: real colour (the average gap
     * between strongest and weakest channel at least 16, so not black-and-white or sepia), but not
     * garish (at most 100: neon, aurora and tropical-reef blues were all rejected), and not washed
     * out (average brightness at most 150 of 255: snowfields and hazy white skies were rejected).
     * The limits come from the owner's picks: every loved picture falls between them.
     */
    private static boolean suits(File f) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = 32;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return false;
        long chroma = 0, luma = 0;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        if (px.length == 0) return false;
        for (int p : px) {
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
            chroma += Math.max(r, Math.max(g, bl)) - Math.min(r, Math.min(g, bl));
            luma += (299 * r + 587 * g + 114 * bl) / 1000;
        }
        chroma /= px.length;
        return chroma >= 16 && chroma <= 100 && luma / px.length <= 150;
    }

    private static List<String> bing() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray imgs = new JSONObject(Net.get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-IN", null))
                    .getJSONArray("images");
            for (int i = 0; i < imgs.length(); i++) {
                String base = imgs.getJSONObject(i).optString("urlbase");
                if (!base.isEmpty()) out.add("https://www.bing.com" + base + "_1920x1080.jpg");
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "bing: " + e.getMessage());
        }
        return out;
    }

    // ---- streaming services (TMDB) ----

    private static boolean fetchServices(Context c) {
        String key = Apps.prefs(c).getString("tmdb", "");
        if (key.isEmpty()) return true;     // nothing to do without a key; the pool covers every app
        try {
            JSONArray providers = new JSONObject(tmdb(key, "/watch/providers/tv?watch_region=IN&language=en-US"))
                    .getJSONArray("results");
            java.util.Map<String, Double> trend = trending(key);
            java.util.Map<Integer, List<String>> wanted = watchlist(key);
            java.util.Set<String> taken = new java.util.HashSet<>();    // each title on one shelf only
            // Yesterday's picks rest today: counted as taken, so no shelf shows them.
            SharedPreferences p = Apps.prefs(c);
            String today = Rotation.day(System.currentTimeMillis(), TimeZone.getDefault());
            String day = p.getString("shelf.day", "");
            java.util.Set<String> ids = new java.util.HashSet<>(p.getStringSet("shelf.ids", Collections.<String>emptySet()));
            java.util.Set<String> resting = Rotation.resting(today, day, ids, p.getString("shelf.prev.day", ""),
                    new java.util.HashSet<>(p.getStringSet("shelf.prev.ids", Collections.<String>emptySet())));
            taken.addAll(resting);
            for (String[] s : SERVICES) {
                if (!Apps.installed(c, s[0])) continue;
                for (int id : providerIds(providers, s[1])) {
                    if (fetchService(c, key, s[0], id, trend, wanted, taken, today)) break;
                }
            }
            java.util.Set<String> shown = new java.util.HashSet<>(taken);
            shown.removeAll(resting);
            SharedPreferences.Editor e = p.edit();
            if (!today.equals(day)) e.putString("shelf.prev.day", day).putStringSet("shelf.prev.ids", ids);
            e.putString("shelf.day", today).putStringSet("shelf.ids", shown).apply();
            return true;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "tmdb: " + e.getMessage());
            return false;
        }
    }

    /** TMDB provider ids whose name contains {@code want}, shortest name first. */
    private static List<Integer> providerIds(JSONArray all, String want) throws JSONException {
        List<JSONObject> hits = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.getJSONObject(i);
            if (norm(p.getString("provider_name")).contains(want)) hits.add(p);
        }
        Collections.sort(hits, (a, b) -> Integer.compare(a.optString("provider_name").length(),
                b.optString("provider_name").length()));
        List<Integer> ids = new ArrayList<>();
        for (JSONObject p : hits) ids.add(p.getInt("provider_id"));
        return ids;
    }

    /**
     * A shelf picked for the owner's taste ({@link Taste}), ranked so what's trending comes first.
     *
     * Candidates, all streaming on this service in India, each with a fit for the owner:
     * watchlist titles 2; titles with one of the owner's keywords and good ratings 1; well-rated
     * documentaries 0.7; recent well-rated Indian titles in liked genres 0.6; recent well-rated
     * titles in liked genres 0.5. Anything watched, already on another shelf, anime or horror is
     * out, and so is anything shown yesterday (it is in {@code taken}). Each is scored on fit, this
     * week's TMDB trending rank, popularity, rating, freshness, being Indian and the day's nudge
     * ({@link Rotation#nudge}); the best five go up, never three series or three films in a row.
     */
    private static boolean fetchService(Context c, String key, String pkg, int provider,
            java.util.Map<String, Double> trend, java.util.Map<Integer, List<String>> wanted,
            java.util.Set<String> taken, String today) throws IOException, JSONException {
        String since = daysAgo(730);
        String base = "?watch_region=IN&with_watch_providers=" + provider
                + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&language=en-US&page=1";
        java.util.Map<String, JSONObject> cand = new java.util.LinkedHashMap<>();
        for (String kind : new String[] {"tv", "movie"}) {
            boolean tv = "tv".equals(kind);
            String q = base + "&without_genres=" + (tv ? Taste.NO_TV : Taste.NO_MOVIE)
                    + "&without_keywords=" + Taste.NO_KEYWORDS;
            String date = tv ? "first_air_date" : "primary_release_date";
            String like = tv ? Taste.LIKE_TV : Taste.LIKE_MOVIE;
            candidates(cand, key, kind, 1.0, q + "&with_keywords=" + Taste.KEYWORDS
                    + "&vote_average.gte=7&vote_count.gte=" + (tv ? 100 : 300), taken);
            candidates(cand, key, kind, 0.7, q + "&with_genres=99&vote_average.gte=7&vote_count.gte=30", taken);
            candidates(cand, key, kind, 0.6, q + "&with_genres=" + like + "&with_origin_country=IN&" + date
                    + ".gte=" + since + "&vote_average.gte=6.8&vote_count.gte=" + (tv ? 5 : 30), taken);
            candidates(cand, key, kind, 0.5, q + "&with_genres=" + like + "&" + date + ".gte=" + since
                    + "&vote_average.gte=7&vote_count.gte=150", taken);
        }
        List<String> want = wanted.get(provider);
        if (want != null) {
            for (String k : want) {
                if (taken.contains(k)) continue;
                String[] kv = k.split(":");
                JSONObject m = new JSONObject(tmdb(key, "/" + kv[0] + "/" + kv[1] + "?language=en-US"));
                if (m.optString("backdrop_path").isEmpty()) continue;
                cand.put(k, m.put("_kind", kv[0]).put("_fit", 2.0));
            }
        }

        List<JSONObject> ranked = new ArrayList<>(cand.values());
        for (JSONObject m : ranked) m.put("_score", score(m, trend, today));
        Collections.sort(ranked, (a, b) -> Double.compare(b.optDouble("_score"), a.optDouble("_score")));
        List<JSONObject> picks = new ArrayList<>();
        for (JSONObject m : ranked) {
            if (picks.size() == PER_APP) break;
            int n = picks.size();
            String kind = m.getString("_kind");
            if (n >= 2 && kind.equals(picks.get(n - 1).getString("_kind"))
                    && kind.equals(picks.get(n - 2).getString("_kind"))) continue;
            picks.add(m);
        }
        if (picks.size() < 3) return false;

        File tmp = fresh(dir(c, pkg + ".new"));
        int n = 0;
        for (JSONObject m : picks) {
            if (!Net.download(TMDB_IMG + "w1280" + m.getString("backdrop_path"), new File(tmp, n + ".jpg"))) continue;
            String kind = m.getString("_kind");
            String logo = logoPath(key, kind, m.getInt("id"));
            if (logo != null) Net.download(TMDB_IMG + "w500" + logo, new File(tmp, n + ".png"));
            write(tmp, n, m.optString("tv".equals(kind) ? "name" : "title"), "", "", "");
            taken.add(kind + ":" + m.getInt("id"));
            n++;
        }
        if (n == 0) return false;
        swap(tmp, dir(c, pkg));
        return true;
    }

    /** Adds one discover page's titles to {@code cand} with the given fit, keeping each title's best fit. */
    private static void candidates(java.util.Map<String, JSONObject> cand, String key, String kind, double fit,
            String query, java.util.Set<String> taken) throws IOException, JSONException {
        JSONArray results = new JSONObject(tmdb(key, "/discover/" + kind + query)).getJSONArray("results");
        for (int i = 0; i < results.length(); i++) {
            JSONObject m = results.getJSONObject(i);
            String k = kind + ":" + m.getInt("id");
            if (taken.contains(k) || seen(kind, m.getInt("id"))) continue;
            if (m.optString("backdrop_path").isEmpty() || "null".equals(m.optString("backdrop_path"))) continue;
            JSONArray g = m.optJSONArray("genre_ids");
            if (has(g, 16) && (!has(g, 35) || "ja".equals(m.optString("original_language")))) continue;  // anime
            JSONObject old = cand.get(k);
            if (old == null || old.optDouble("_fit") < fit) cand.put(k, m.put("_kind", kind).put("_fit", fit));
        }
    }

    private static double score(JSONObject m, java.util.Map<String, Double> trend, String today) {
        String k = m.optString("_kind") + ":" + m.optInt("id");
        String date = m.optString("first_air_date", m.optString("release_date", ""));
        double age = yearsSince(date);
        boolean indian = false;
        JSONArray origin = m.optJSONArray("origin_country");
        if (origin != null) for (int i = 0; i < origin.length(); i++) indian |= "IN".equals(origin.optString(i));
        Double t = trend.get(k);
        return 1.6 * m.optDouble("_fit")                                          // taste
                + 1.2 * (t == null ? 0 : t)                                         // trending this week
                + 0.5 * Math.min(1, Math.log10(1 + m.optDouble("popularity", 0)) / 3)  // steady popularity
                + 0.6 * Math.max(0, m.optDouble("vote_average", 0) - 6.5) / 2.5     // rating
                + 0.5 * Math.exp(-age / 2)                                          // freshness
                + (indian ? 0.3 : 0)
                + 1.0 * Rotation.nudge(today, k);                                   // today's shuffle
    }

    /** TMDB's weekly trending series and films, top 60 of each, as "tv:id"/"movie:id" to 1 (top) .. 0. */
    private static java.util.Map<String, Double> trending(String key) {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        for (String kind : new String[] {"tv", "movie"}) {
            for (int page = 1; page <= 3; page++) {
                try {
                    JSONArray r = new JSONObject(tmdb(key, "/trending/" + kind + "/week?language=en-US&page=" + page))
                            .getJSONArray("results");
                    for (int i = 0; i < r.length(); i++) {
                        out.put(kind + ":" + r.getJSONObject(i).getInt("id"), 1 - ((page - 1) * 20 + i) / 60.0);
                    }
                } catch (IOException | JSONException e) {
                    Log.w(TAG, "trending: " + e.getMessage());
                }
            }
        }
        return out;
    }

    /** Which services stream each watchlist title in India: provider id to "movie:id"s. */
    private static java.util.Map<Integer, List<String>> watchlist(String key) {
        java.util.Map<Integer, List<String>> out = new java.util.HashMap<>();
        for (int id : Taste.WANT_MOVIE) {
            try {
                JSONObject in = new JSONObject(tmdb(key, "/movie/" + id + "/watch/providers"))
                        .getJSONObject("results").optJSONObject("IN");
                JSONArray flat = in == null ? null : in.optJSONArray("flatrate");
                if (flat == null) continue;
                for (int i = 0; i < flat.length(); i++) {
                    int p = flat.getJSONObject(i).getInt("provider_id");
                    if (!out.containsKey(p)) out.put(p, new ArrayList<>());
                    out.get(p).add("movie:" + id);
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "watchlist " + id + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static boolean seen(String kind, int id) {
        for (int s : "tv".equals(kind) ? Taste.SEEN_TV : Taste.SEEN_MOVIE) if (s == id) return true;
        return false;
    }

    private static boolean has(JSONArray a, int v) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (a.optInt(i) == v) return true;
        return false;
    }

    /** Years since a yyyy-MM-dd date; 50 if there is none. */
    private static double yearsSince(String date) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = f.parse(date);
            return Math.max(0, (System.currentTimeMillis() - d.getTime()) / (365.25 * 86400_000L));
        } catch (ParseException | RuntimeException e) {
            return 50;
        }
    }

    /** The English, Hindi or language-neutral title logo, if TMDB has a PNG one. */
    private static String logoPath(String key, String kind, int id) {
        try {
            JSONArray logos = new JSONObject(tmdb(key, "/" + kind + "/" + id + "/images?include_image_language=en,hi,null"))
                    .getJSONArray("logos");
            for (int i = 0; i < logos.length(); i++) {
                String path = logos.getJSONObject(i).optString("file_path");
                if (path.endsWith(".png")) return path;
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "logo: " + e.getMessage());
        }
        return null;
    }

    /** v3 keys go in the query string; v4 read tokens (long JWTs) as a bearer header. */
    private static String tmdb(String key, String path) throws IOException {
        if (key.length() > 60) return Net.get(TMDB + path, key);
        return Net.get(TMDB + path + (path.contains("?") ? "&" : "?") + "api_key=" + key, null);
    }

    // ---- FanCode: Formula 1 ----

    /**
     * Formula 1 for FanCode: the owner's clip (home/art/com.fancode.tv/*.mp4, synced by install.sh)
     * with the next Grand Prix and its qualifying and race times in the TV's time zone. No clip, no
     * F1 slide: FanCode then shows the photo pool like any other app.
     */
    private static void fetchF1(Context c) {
        File clip = ownClip(c, FANCODE);
        if (clip == null) {
            delete(dir(c, FANCODE));
            return;
        }
        try {
            JSONArray races = new JSONObject(Net.get("https://api.jolpi.ca/ergast/f1/current.json?limit=40", null))
                    .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
            long now = System.currentTimeMillis();
            for (int i = 0; i < races.length(); i++) {
                JSONObject r = races.getJSONObject(i);
                Date race = utc(r.optString("date"), r.optString("time", "12:00:00Z"));
                if (race == null || race.getTime() + 3 * 3600_000L < now) continue;
                String when = (r.has("Qualifying") ? "Qualifying   " + local(r.getJSONObject("Qualifying")) + LINE : "")
                        + "Race   " + fmt(race);
                File tmp = fresh(dir(c, FANCODE + ".new"));
                if (copy(clip, new File(tmp, "0.mp4")) && still(clip, new File(tmp, "0.jpg"))) {
                    write(tmp, 0, r.getString("raceName"), "", when, CAPTION);
                    swap(tmp, dir(c, FANCODE));
                }
                return;
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "f1: " + e.getMessage());
        }
    }

    /** The first .mp4 the owner pushed for an app, or null. */
    private static File ownClip(Context c, String pkg) {
        File[] clips = new File(c.getExternalFilesDir("shelf"), pkg).listFiles((d, name) -> name.endsWith(".mp4"));
        if (clips == null || clips.length == 0) return null;
        java.util.Arrays.sort(clips);
        return clips[0];
    }

    /** A frame two seconds into the clip, as a JPEG: the slide's picture and the glass's frost. */
    private static boolean still(File clip, File to) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(clip.getPath());
            android.graphics.Bitmap b = r.getFrameAtTime(2_000_000);
            if (b == null) return false;
            try (FileOutputStream out = new FileOutputStream(to)) {
                return b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
            }
        } catch (RuntimeException | IOException e) {
            return false;
        } finally {
            try {
                r.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static boolean copy(File from, File to) {
        try (java.io.InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String local(JSONObject session) {
        Date d = utc(session.optString("date"), session.optString("time", "12:00:00Z"));
        return d == null ? "" : fmt(d);
    }

    private static Date utc(String date, String time) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return f.parse(date + "T" + time);
        } catch (ParseException e) {
            return null;
        }
    }

    private static String fmt(Date d) {
        return new SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(d);
    }

    // ---- Spotify: the owner's top artists and albums, then India's charts ----

    /** The owner's picks ({@link Taste#MUSIC}), then top albums and podcasts in India this week (Apple's public charts, no key). */
    private static void fetchCharts(Context c) {
        try {
            File tmp = fresh(dir(c, SPOTIFY + ".new"));
            int n = 0;
            for (String[] m : Taste.MUSIC) {
                if (!Net.download(m[2], new File(tmp, n + ".jpg"))) continue;
                write(tmp, n++, m[0], "", m[1], CAPTION + "," + CARD);
            }
            n = chart(tmp, n, "https://rss.marketingtools.apple.com/api/v2/in/music/most-played/10/albums.json", 2);
            n = chart(tmp, n, "https://rss.marketingtools.apple.com/api/v2/in/podcasts/top/10/podcasts.json", 2);
            if (n > 0) swap(tmp, dir(c, SPOTIFY));
        } catch (IOException e) {
            Log.w(TAG, "charts: " + e.getMessage());
        }
    }

    private static int chart(File tmp, int n, String url, int count) throws IOException {
        try {
            JSONArray rs = new JSONObject(Net.get(url, null)).getJSONObject("feed").getJSONArray("results");
            for (int i = 0, added = 0; i < rs.length() && added < count; i++) {
                JSONObject r = rs.getJSONObject(i);
                String art = r.optString("artworkUrl100").replace("100x100bb", "1000x1000bb");
                if (art.isEmpty() || !Net.download(art, new File(tmp, n + ".jpg"))) continue;
                String name = r.optString("name").replaceAll("\\s*\\((Original Motion Picture Soundtrack|Original Soundtrack)\\)", "");
                write(tmp, n, name, "", shortArtists(r.optString("artistName")), CAPTION + "," + CARD);
                n++;
                added++;
            }
        } catch (JSONException e) {
            Log.w(TAG, "chart: " + e.getMessage());
        }
        return n;
    }

    /** "A, B, C & D" to "A & B": soundtracks credit a dozen people. */
    private static String shortArtists(String all) {
        String[] names = all.split("\\s*(,|&)\\s*");
        if (names.length <= 2) return all;
        return names[0] + " & " + names[1];
    }

    // ---- files ----

    private static String daysAgo(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -days);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    private static String norm(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static File fresh(File d) {
        delete(d);
        d.mkdirs();
        return d;
    }

    private static void swap(File from, File to) {
        delete(to);
        if (!from.renameTo(to)) Log.w(TAG, "could not move " + from + " to " + to);
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    private static void write(File dir, int n, String title, String overline, String caption, String flags)
            throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, n + ".txt"))) {
            String s = title + "\n" + overline + "\n" + caption + "\n" + flags;
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** WMO weather code to words. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }
}
