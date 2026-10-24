package com.siddh.home;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The sports the owner follows ({@link Taste#CRICKET}, {@link Taste#TENNIS}, and F1):
 * fixtures, live scores and results, for the score pill ({@link Scores}) and the Sports
 * view ({@link Scoreboard}). One fetcher per sport, all returning the same {@link Fixture}, so a
 * source can be swapped without touching the screens. No keys needed:
 *
 * - Cricket: ESPN's cricket scorepanel. Unofficial and community-documented, no published limits.
 * - WTA tennis: ESPN's WTA scoreboard. Same caveats.
 * - F1: Jolpica (calendar, recent results). Past rounds never change, so it is asked once a day.
 *
 * Fixtures and results are fetched once a day; live scores only while a followed match is on
 * ({@link #pollable}). Everything is cached in files/sports.json, so screens only read local data,
 * and finished matches stay for {@link #KEEP_RESULTS_MS} at no extra cost.
 */
final class Sports {
    private static final String TAG = "HomeSports";
    static final String CRICKET = "cricket", F1 = "f1", TENNIS = "tennis";
    private static final String ESPN = "https://site.api.espn.com/apis/site/v2/sports/";
    private static final String JOLPICA = "https://api.jolpi.ca/ergast/f1/";
    private static final long HOUR = 3600_000L, DAY = 24 * HOUR;
    private static final long KEEP_RESULTS_MS = 14 * DAY;
    /** Past F1 races listed under Results. */
    private static final int F1_RESULTS = 4;
    /** F1 weekends closer than this list every session; later ones list the race alone. */
    private static final long F1_SESSIONS_MS = 10 * DAY;
    /**
     * Cricket fixtures are looked for this many days ahead, one day at a time: ESPN's scorepanel
     * for a date holds about 25 matches from that day backwards, so on a busy day a followed match
     * can fall off a panel asked for the day after.
     */
    private static final int CRICKET_AHEAD_DAYS = 21, CRICKET_STEP_DAYS = 1;
    /** A failed daily fetch is tried again after this long, not every minute. */
    private static final long RETRY_MS = HOUR / 2;
    /** Live polling starts this long before a followed match... */
    private static final long LEAD_MS = 10 * Countdown.MINUTE;
    /** ...and ends this long after its start, unless the feed still says it is on. */
    private static final long CRICKET_DAY_MS = 10 * HOUR, TENNIS_MATCH_MS = 5 * HOUR;
    /** Jolpica's session keys, in weekend order; the race is the race itself. */
    private static final String[] SESSIONS = {"FirstPractice", "SecondPractice", "ThirdPractice",
            "SprintQualifying", "Sprint", "Qualifying", "Race"};

    /** One match or F1 session, the record every fetcher returns. Side a is always the followed one. */
    static final class Fixture {
        String id = "", sport = "", event = "", stage = "", a = "", b = "", aShort = "", bShort = "";
        /** Ground, court or circuit, with its city where the feed gives one. */
        String venue = "";
        /** F1: the round number. */
        String round = "";
        /** F1 race results: the rest of the podium, "Leclerc|+7.335;Antonelli|+24.573". */
        String podium = "";
        /** Cricket: the innings line ("168/4 (17.2/20 ov)"). Tennis: games per set ("6 3"). F1 result: "P2". */
        String scoreA = "", scoreB = "";
        /** The feed's status line ("India need 66 runs", "Final"), or F1's result line. */
        String note = "";
        /** "pre", "in" or "post", as ESPN has them. F1 sessions get theirs from the clock. */
        String state = "pre";
        /** For F1 sessions, Jolpica's key (Qualifying, Race...); empty for everything else. */
        String session = "";
        /** "a", "b" or empty: who won, where the feed says. */
        String won = "";
        long start;

        boolean live() {
            return "in".equals(state);
        }

        boolean done() {
            return "post".equals(state);
        }

        /** What the pill shows; when it differs between polls the pill comes back to full strength. */
        String change() {
            return scoreA + "|" + scoreB + "|" + state + "|" + note;
        }

        /** Tennis set scores from a's side, "6-4 3-2". */
        String sets() {
            String[] x = scoreA.trim().split(" "), y = scoreB.trim().split(" ");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < x.length && !x[i].isEmpty(); i++) {
                if (out.length() > 0) out.append(' ');
                out.append(x[i]).append('-').append(i < y.length ? y[i] : "0");
            }
            return out.toString();
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("sport", sport).put("event", event).put("stage", stage).put("venue", venue).put("round", round).put("podium", podium)
                    .put("a", a).put("b", b).put("aShort", aShort).put("bShort", bShort)
                    .put("scoreA", scoreA).put("scoreB", scoreB).put("note", note).put("state", state)
                    .put("session", session).put("won", won).put("start", start);
        }

        static Fixture of(JSONObject j) {
            Fixture f = new Fixture();
            f.id = j.optString("id");
            f.sport = j.optString("sport");
            f.event = j.optString("event");
            f.stage = j.optString("stage");
            f.venue = j.optString("venue");
            f.round = j.optString("round");
            f.podium = j.optString("podium");
            f.a = j.optString("a");
            f.b = j.optString("b");
            f.aShort = j.optString("aShort");
            f.bShort = j.optString("bShort");
            f.scoreA = j.optString("scoreA");
            f.scoreB = j.optString("scoreB");
            f.note = j.optString("note");
            f.state = j.optString("state", "pre");
            f.session = j.optString("session");
            f.won = j.optString("won");
            f.start = j.optLong("start");
            return f;
        }
    }

    static final class Data {
        /** In start order. */
        final List<Fixture> fixtures;
        /** When the daily fetch last succeeded, or is next due minus a day after a failure. */
        final long at;

        Data(List<Fixture> fixtures, long at) {
            this.fixtures = fixtures;
            this.at = at;
        }
    }

    /** Runs on a background thread after any change; the Sports view sets it while open. */
    static volatile Runnable onChange;

    private static Data cached;
    private static volatile boolean dailyBusy;

    private Sports() {}

    // ---- reading ----

    static synchronized Data load(Context c) {
        if (cached == null) cached = read(file(c));
        // F1 sessions have no live feed: their state follows the clock.
        long now = System.currentTimeMillis();
        for (Fixture f : cached.fixtures) {
            if (!f.session.isEmpty()) {
                f.state = now < f.start ? "pre" : now < f.start + Countdown.length(f.session) ? "in" : "post";
            }
        }
        return cached;
    }

    /** Makes the next tick fetch everything again (Home's {@code --ez refresh true}). */
    static synchronized void invalidate(Context c) {
        Data d = load(c);
        save(c, new Data(d.fixtures, 0));
    }

    static boolean stale(Data d, long now) {
        return now - d.at > DAY;
    }

    /** Sports that need live polling now: a followed match is on, or starts within {@link #LEAD_MS}. */
    static List<String> pollable(Data d, long now) {
        List<String> out = new ArrayList<>();
        for (Fixture f : d.fixtures) {
            if (F1.equals(f.sport) || f.done() || out.contains(f.sport) || resting(f)) continue;
            long span = CRICKET.equals(f.sport) ? CRICKET_DAY_MS : TENNIS_MATCH_MS;
            boolean window = now >= f.start - LEAD_MS && now < f.start + span;
            // A Test runs five days: keep polling while the feed says it is on, but not for ever.
            if (window || (f.live() && now < f.start + 6 * DAY)) out.add(f.sport);
        }
        return out;
    }

    /** Stumps, or a stale "live" from before the TV slept: nothing to poll for. */
    private static boolean resting(Fixture f) {
        return f.note.contains("Stumps");
    }

    /** The live followed match for the pill: cricket first, the main follow, then tennis. */
    static Fixture headline(Data d, long now) {
        for (String sport : new String[] {CRICKET, TENNIS}) {
            for (Fixture f : d.fixtures) {
                if (sport.equals(f.sport) && f.live() && !resting(f) && now < f.start + 6 * DAY) return f;
            }
        }
        return null;
    }

    /** The pill's words for a live match: "NZ 123/5 (11.2) vs IND", batting side first; "Rybakina 6-3 2-6 vs Shnaider". */
    static String pillText(Fixture f) {
        if (TENNIS.equals(f.sport)) return surname(f.a) + " " + f.sets() + " vs " + surname(f.b);
        // The chasing side's line carries the target; before that, whoever has a score is batting.
        boolean bBats = f.scoreB.contains("target") || (f.scoreA.isEmpty() && !f.scoreB.isEmpty());
        String bat = bBats ? f.bShort : f.aShort, score = bBats ? f.scoreB : f.scoreA;
        String other = bBats ? f.aShort : f.bShort;
        return score.isEmpty() ? f.aShort + " vs " + f.bShort : bat + " " + shortScore(score) + " vs " + other;
    }

    /** "132/1 (20.5/50 ov, target 198)" to "132/1 (20.5)". */
    static String shortScore(String s) {
        return s.replaceAll("/\\d+ ov", "").replaceAll(",? ?target \\d+", "").replace(" ov)", ")").trim();
    }

    static String surname(String name) {
        int i = name.lastIndexOf(' ');
        return i < 0 ? name : name.substring(i + 1);
    }

    /** The F1 sessions the countdown covers, in start order: all, or those named in the "f1.sessions" setting. */
    static List<Countdown.Session> sessions(Context c, Data d) {
        String only = Apps.prefs(c).getString("f1.sessions", "").toLowerCase(Locale.US);
        List<Countdown.Session> out = new ArrayList<>();
        for (Fixture f : d.fixtures) {
            if (f.session.isEmpty()) continue;
            if (!only.isEmpty() && !only.contains(f.session.toLowerCase(Locale.US))) continue;
            out.add(new Countdown.Session(Countdown.label(f.session), f.start, Countdown.length(f.session)));
        }
        return out;
    }

    static int colour(String sport) {
        switch (sport) {
            case CRICKET: return 0xFF4C9BFF;
            case F1: return 0xFFFF4D3D;
            case TENNIS: return 0xFFC8F24A;
            default: return 0xFFE0A030;     // multi-sport Games
        }
    }

    // ---- fetching (blocking; background threads only) ----

    /** The daily fetch: fixtures for the next weeks, and recent results. Returns whether it ran. */
    static boolean daily(Context c) {
        if (dailyBusy) return false;
        dailyBusy = true;
        try {
            long now = System.currentTimeMillis();
            Map<String, List<Fixture>> fresh = new HashMap<>();
            fresh.put(CRICKET, cricket(cricketDays()));
            // A player's matches are known only once the draw is out, a day or two ahead.
            fresh.put(TENNIS, tennis(new int[] {-1, 0, 1, 2}));
            fresh.put(F1, f1(now));
            boolean ok = !fresh.containsValue(null);
            synchronized (Sports.class) {
                Data old = load(c);
                save(c, new Data(merge(old.fixtures, fresh, now), ok ? now : now - DAY + RETRY_MS));
            }
            changed();
            return true;
        } finally {
            dailyBusy = false;
        }
    }

    /** One sport's live scores. Returns whether anything changed. */
    static boolean live(Context c, String sport) {
        List<Fixture> got = CRICKET.equals(sport) ? cricket(null) : tennis(null);
        if (got == null) return false;
        boolean changed = false;
        synchronized (Sports.class) {
            Data d = load(c);
            Map<String, Fixture> byId = new LinkedHashMap<>();
            for (Fixture f : d.fixtures) byId.put(f.id, f);
            for (Fixture f : got) {
                Fixture old = byId.get(f.id);
                if (old == null || !old.change().equals(f.change())) changed = true;
                byId.put(f.id, f);
            }
            if (changed) save(c, new Data(sorted(byId.values()), d.at));
        }
        if (changed) changed();
        return changed;
    }

    /** Today's fetch for each sport, plus finished matches from before that are still recent. A failed sport keeps its old list. */
    private static List<Fixture> merge(List<Fixture> old, Map<String, List<Fixture>> fresh, long now) {
        Map<String, Fixture> byId = new LinkedHashMap<>();
        for (Map.Entry<String, List<Fixture>> e : fresh.entrySet()) {
            if (e.getValue() == null) {
                for (Fixture f : old) if (f.sport.equals(e.getKey())) byId.put(f.id, f);
            } else {
                for (Fixture f : e.getValue()) byId.put(f.id, f);
            }
        }
        for (Fixture f : old) {
            if (f.done() && now - f.start < KEEP_RESULTS_MS && !byId.containsKey(f.id)) byId.put(f.id, f);
        }
        return sorted(byId.values());
    }

    private static List<Fixture> sorted(java.util.Collection<Fixture> all) {
        List<Fixture> out = new ArrayList<>(all);
        Collections.sort(out, (x, y) -> Long.compare(x.start, y.start));
        return out;
    }

    /** From last week out to {@link #CRICKET_AHEAD_DAYS}, every {@link #CRICKET_STEP_DAYS} days. */
    private static int[] cricketDays() {
        int[] days = new int[(CRICKET_AHEAD_DAYS + 7) / CRICKET_STEP_DAYS + 1];
        for (int i = 0; i < days.length; i++) days[i] = -6 + i * CRICKET_STEP_DAYS;
        return days;
    }

    /**
     * Followed cricket from ESPN's scorepanel. {@code days} are offsets from today, each of which
     * returns a few days around it; null asks for the live panel only. Null on failure.
     */
    private static List<Fixture> cricket(int[] days) {
        Map<String, Fixture> out = new LinkedHashMap<>();
        try {
            for (int d : days == null ? new int[] {0} : days) {
                String url = ESPN + "cricket/scorepanel" + (days == null ? "" : "?dates=" + ymd(d));
                JSONArray scores = new JSONObject(Net.get(url, null)).getJSONArray("scores");
                for (int i = 0; i < scores.length(); i++) {
                    JSONObject sc = scores.getJSONObject(i);
                    JSONArray leagues = sc.optJSONArray("leagues");
                    String league = leagues != null && leagues.length() > 0 ? leagues.getJSONObject(0).optString("name") : "";
                    JSONArray events = sc.optJSONArray("events");
                    for (int k = 0; events != null && k < events.length(); k++) {
                        Fixture f = cricketMatch(events.getJSONObject(k), league);
                        if (f != null) out.put(f.id, f);
                    }
                }
            }
            return new ArrayList<>(out.values());
        } catch (IOException | JSONException e) {
            Log.w(TAG, "cricket: " + e.getMessage());
            return null;
        }
    }

    private static Fixture cricketMatch(JSONObject e, String league) throws JSONException {
        JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
        JSONArray cs = comp.getJSONArray("competitors");
        if (cs.length() != 2) return null;
        int mine = -1;
        for (int i = 0; i < 2; i++) {
            if (follows(Taste.CRICKET, cs.getJSONObject(i).getJSONObject("team").optString("displayName"))) mine = i;
        }
        if (mine < 0) return null;
        JSONObject a = cs.getJSONObject(mine), b = cs.getJSONObject(1 - mine);
        JSONObject status = e.getJSONObject("status");
        Fixture f = new Fixture();
        f.id = "c" + e.getString("id");
        f.sport = CRICKET;
        f.event = league;
        f.stage = comp.optString("description");
        f.venue = venue(comp.optJSONObject("venue"));
        f.a = a.getJSONObject("team").optString("displayName");
        f.aShort = a.getJSONObject("team").optString("abbreviation");
        f.b = b.getJSONObject("team").optString("displayName");
        f.bShort = b.getJSONObject("team").optString("abbreviation");
        f.scoreA = a.optString("score");
        f.scoreB = b.optString("score");
        f.state = status.getJSONObject("type").optString("state", "pre");
        f.note = status.optString("summary", status.getJSONObject("type").optString("detail"));
        f.won = "true".equals(a.optString("winner")) ? "a" : "true".equals(b.optString("winner")) ? "b" : "";
        f.start = utc(e.optString("date"));
        return f;
    }

    /** Followed WTA singles from ESPN's scoreboard, for days offset from today (null: today's live board). */
    private static List<Fixture> tennis(int[] days) {
        Map<String, Fixture> out = new LinkedHashMap<>();
        try {
            for (int d : days == null ? new int[] {0} : days) {
                String url = ESPN + "tennis/wta/scoreboard" + (days == null ? "" : "?dates=" + ymd(d));
                JSONArray events = new JSONObject(Net.get(url, null)).optJSONArray("events");
                for (int i = 0; events != null && i < events.length(); i++) {
                    JSONObject e = events.getJSONObject(i);
                    JSONArray groups = e.optJSONArray("groupings");
                    for (int g = 0; groups != null && g < groups.length(); g++) {
                        JSONObject group = groups.getJSONObject(g);
                        if (!group.optJSONObject("grouping").optString("displayName").contains("Singles")) continue;
                        JSONArray comps = group.getJSONArray("competitions");
                        for (int k = 0; k < comps.length(); k++) {
                            Fixture f = tennisMatch(comps.getJSONObject(k), e.optString("name"));
                            if (f != null && f.venue.isEmpty()) f.venue = venue(e.optJSONObject("venue"));
                            if (f != null) out.put(f.id, f);
                        }
                    }
                }
            }
            return new ArrayList<>(out.values());
        } catch (IOException | JSONException | NullPointerException e) {
            Log.w(TAG, "tennis: " + e.getMessage());
            return null;
        }
    }

    private static Fixture tennisMatch(JSONObject c, String tournament) throws JSONException {
        JSONArray cs = c.getJSONArray("competitors");
        if (cs.length() != 2) return null;
        int mine = -1;
        for (int i = 0; i < 2; i++) {
            JSONObject who = cs.getJSONObject(i).optJSONObject("athlete");
            if (who != null && follows(Taste.TENNIS, who.optString("displayName"))) mine = i;
        }
        if (mine < 0) return null;
        JSONObject a = cs.getJSONObject(mine), b = cs.getJSONObject(1 - mine);
        JSONObject type = c.getJSONObject("status").getJSONObject("type");
        Fixture f = new Fixture();
        f.id = "t" + c.getString("id");
        f.sport = TENNIS;
        f.event = tournament;
        JSONObject round = c.optJSONObject("round");
        f.stage = round == null ? "" : round.optString("displayName");
        f.venue = venue(c.optJSONObject("venue"));
        f.a = a.getJSONObject("athlete").optString("displayName");
        f.b = b.optJSONObject("athlete") == null ? "TBD" : b.getJSONObject("athlete").optString("displayName");
        f.aShort = surname(f.a);
        f.bShort = surname(f.b);
        f.scoreA = games(a);
        f.scoreB = games(b);
        f.state = type.optString("state", "pre");
        f.note = type.optString("description");
        f.won = a.optBoolean("winner") ? "a" : b.optBoolean("winner") ? "b" : "";
        f.start = utc(c.optString("date"));
        return f;
    }

    /** Games per set, "6 3". */
    private static String games(JSONObject competitor) {
        JSONArray sets = competitor.optJSONArray("linescores");
        StringBuilder out = new StringBuilder();
        for (int i = 0; sets != null && i < sets.length(); i++) {
            if (out.length() > 0) out.append(' ');
            out.append(Math.round(sets.optJSONObject(i).optDouble("value")));
        }
        return out.toString();
    }

    /**
     * The rest of this season's calendar, and next season's once Jolpica publishes it: every
     * session for weekends within {@link #F1_SESSIONS_MS}, the race alone after that. Then Max's
     * last race result. Null on failure.
     */
    private static List<Fixture> f1(long now) {
        List<Fixture> out = new ArrayList<>();
        try {
            JSONArray races = f1Season("current");
            if (races.length() > 0) {
                JSONArray next = f1Season(String.valueOf(Integer.parseInt(races.getJSONObject(0).optString("season")) + 1));
                for (int i = 0; i < next.length(); i++) races.put(next.getJSONObject(i));
            }
            for (int i = 0; i < races.length(); i++) {
                JSONObject r = races.getJSONObject(i);
                long race = utc(r.optString("date") + "T" + r.optString("time", "12:00:00Z"));
                if (race + Countdown.length("Race") < now) continue;
                JSONObject circuit = r.getJSONObject("Circuit");
                JSONObject where = circuit.getJSONObject("Location");
                String place = where.optString("locality");
                boolean near = race - now < F1_SESSIONS_MS;
                for (String key : SESSIONS) {
                    if (!near && !"Race".equals(key)) continue;
                    JSONObject s = "Race".equals(key) ? r : r.optJSONObject(key);
                    if (s == null || !s.has("date")) continue;
                    Fixture f = new Fixture();
                    f.id = "f" + r.optString("season") + "-" + r.optString("round") + "-" + key;
                    f.sport = F1;
                    f.event = r.optString("raceName");
                    f.stage = Countdown.label(key);
                    f.a = Countdown.label(key);
                    f.b = place;
                    f.venue = circuit.optString("circuitName") + ", " + place + ", " + where.optString("country");
                    f.session = key;
                    f.round = r.optString("round");
                    f.start = utc(s.optString("date") + "T" + s.optString("time", "12:00:00Z"));
                    out.add(f);
                }
            }
            out.addAll(f1Results());
            return out;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "f1: " + e.getMessage());
            return null;
        }
    }

    /** One season's races; empty if Jolpica has none yet. */
    private static JSONArray f1Season(String season) throws IOException, JSONException {
        return new JSONObject(Net.get(JOLPICA + season + ".json?limit=40", null))
                .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
    }

    /** The winners of this season's last {@link #F1_RESULTS} races, the latest with its podium. */
    private static List<Fixture> f1Results() throws IOException, JSONException {
        List<Fixture> out = new ArrayList<>();
        JSONArray races = new JSONObject(Net.get(JOLPICA + "current/results/1.json?limit=40", null))
                .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
        for (int i = Math.max(0, races.length() - F1_RESULTS); i < races.length(); i++) {
            JSONObject r = races.getJSONObject(i);
            JSONObject driver = r.getJSONArray("Results").getJSONObject(0).getJSONObject("Driver");
            Fixture f = new Fixture();
            f.id = "f" + r.optString("season") + "-" + r.optString("round") + "-result";
            f.sport = F1;
            f.event = r.optString("raceName");
            f.stage = "Race result";
            f.round = r.optString("round");
            f.a = driver.optString("givenName") + " " + driver.optString("familyName");
            f.aShort = driver.optString("code");
            f.venue = r.getJSONObject("Circuit").optString("circuitName");
            f.won = "a";
            f.state = "post";
            f.start = utc(r.optString("date") + "T" + r.optString("time", "12:00:00Z"));
            out.add(f);
        }
        if (out.isEmpty()) return out;
        JSONArray last = new JSONObject(Net.get(JOLPICA + "current/last/results.json?limit=3", null))
                .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
        if (last.length() > 0) {
            JSONArray top = last.getJSONObject(0).getJSONArray("Results");
            StringBuilder podium = new StringBuilder();
            for (int i = 1; i < top.length(); i++) {
                JSONObject x = top.getJSONObject(i);
                JSONObject time = x.optJSONObject("Time");
                if (podium.length() > 0) podium.append(';');
                podium.append(x.getJSONObject("Driver").optString("familyName")).append('|')
                        .append(time != null ? time.optString("time") : x.optString("status"));
            }
            out.get(out.size() - 1).podium = podium.toString();
        }
        return out;
    }

    /** "Bay Oval, Mount Maunganui": ESPN's venue name, with its city when the name lacks it. */
    private static String venue(JSONObject v) {
        if (v == null) return "";
        String name = v.optString("fullName");
        JSONObject address = v.optJSONObject("address");
        String city = address == null ? "" : address.optString("city");
        return city.isEmpty() || name.contains(city) ? name : name + ", " + city;
    }

    private static boolean follows(String[] names, String name) {
        for (String n : names) if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    // ---- files ----

    private static File file(Context c) {
        return new File(c.getFilesDir(), "sports.json");
    }

    private static Data read(File f) {
        List<Fixture> fixtures = new ArrayList<>();
        long at = 0;
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int n = 0;
                for (int k; n < buf.length && (k = in.read(buf, n, buf.length - n)) > 0; ) n += k;
                JSONObject j = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
                at = j.optLong("at");
                JSONArray fx = j.optJSONArray("fixtures");
                for (int i = 0; fx != null && i < fx.length(); i++) fixtures.add(Fixture.of(fx.getJSONObject(i)));
            } catch (IOException | JSONException e) {
                Log.w(TAG, "read: " + e.getMessage());
            }
        }
        return new Data(fixtures, at);
    }

    /** Writes to a temp file, then renames, so a crash never leaves half a file. */
    private static synchronized void save(Context c, Data d) {
        cached = d;
        try {
            JSONArray fx = new JSONArray();
            for (Fixture f : d.fixtures) fx.put(f.json());
            String json = new JSONObject().put("at", d.at).put("fixtures", fx).toString();
            File f = file(c), tmp = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) Log.w(TAG, "could not save " + f);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "save: " + e.getMessage());
        }
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    /** Today plus {@code days}, as ESPN's yyyyMMdd. */
    private static String ymd(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, days);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.getTime());
    }

    /** ESPN's "2026-10-23T08:30Z" or Jolpica's "2026-10-23T08:30:00Z"; 0 if neither. */
    private static long utc(String s) {
        for (String p : new String[] {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'"}) {
            SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return f.parse(s).getTime();
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }
}
