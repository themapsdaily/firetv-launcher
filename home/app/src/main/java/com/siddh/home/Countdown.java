package com.siddh.home;

import java.util.List;

/**
 * The F1 session pill's rules (ROADMAP section 6) as plain logic, with no Android in it, so they
 * are unit-tested off the TV. Given the weekend's sessions and the time, it says what the pill
 * shows and how soon that can change.
 *
 * From 30 to 10 minutes before a session the pill pops up briefly every 5 minutes; from 10 minutes
 * it stays, counting down in minutes and seconds; then it says the session is underway until the
 * session's fixed length has passed. Nothing shows while FanCode is in front.
 */
final class Countdown {
    static final long SECOND = 1000, MINUTE = 60 * SECOND;
    /** Minutes before a session at which the pill pops up for {@link #POP_MS}. */
    private static final int[] POPS = {30, 25, 20, 15};
    static final long POP_MS = 8 * SECOND;
    static final long STEADY_MS = 10 * MINUTE;
    private static final long WINDOW_MS = 30 * MINUTE;

    enum Kind { HIDDEN, POP, STEADY, UNDERWAY }

    static final class Session {
        final String name;
        final long start, length;

        Session(String name, long start, long length) {
            this.name = name;
            this.start = start;
            this.length = length;
        }

        long end() {
            return start + length;
        }
    }

    static final class State {
        final Kind kind;
        final String text;
        /** The session shown; null when hidden. */
        final Session session;
        /** Milliseconds until the state or its text may change. */
        final long next;

        State(Kind kind, String text, Session session, long next) {
            this.kind = kind;
            this.text = text;
            this.session = session;
            this.next = Math.max(1, next);
        }
    }

    private Countdown() {}

    /** Jolpica's session key to the name on the pill. */
    static String label(String key) {
        switch (key) {
            case "FirstPractice": return "Practice 1";
            case "SecondPractice": return "Practice 2";
            case "ThirdPractice": return "Practice 3";
            case "SprintQualifying": return "Sprint qualifying";
            case "Sprint": return "Sprint";
            case "Qualifying": return "Qualifying";
            default: return "Race";
        }
    }

    /**
     * How long a session runs. No free feed says when one ends, so these are fixed; a red-flagged
     * session that overruns loses its pill early.
     */
    static long length(String key) {
        switch (key) {
            case "SprintQualifying": return 45 * MINUTE;
            case "Sprint": return 40 * MINUTE;
            case "Race": return 135 * MINUTE;
            default: return 60 * MINUTE;    // practice, qualifying
        }
    }

    /** {@code sessions} in start order. */
    static State at(List<Session> sessions, long now, boolean fancode) {
        Session s = null;
        long nextWindow = Long.MAX_VALUE;
        for (Session x : sessions) {
            if (now >= x.start - WINDOW_MS && now < x.end()) {
                s = x;
                break;
            }
            if (x.start - WINDOW_MS > now) nextWindow = Math.min(nextWindow, x.start - WINDOW_MS - now);
        }
        if (s == null) return new State(Kind.HIDDEN, "", null, Math.min(MINUTE, nextWindow));
        State st = during(s, now);
        // FanCode shows the session itself; keep the timing, so leaving FanCode brings the pill back at once.
        return fancode ? new State(Kind.HIDDEN, "", null, st.next) : st;
    }

    private static State during(Session s, long now) {
        long until = s.start - now;
        if (until <= 0) return new State(Kind.UNDERWAY, s.name + " underway", s, Math.min(MINUTE, s.end() - now));
        if (until <= STEADY_MS) {
            long secs = (until + SECOND - 1) / SECOND;
            String text = s.name + " in " + secs / 60 + ":" + (secs % 60 < 10 ? "0" : "") + secs % 60;
            long tick = until % SECOND;
            return new State(Kind.STEADY, text, s, tick == 0 ? SECOND : tick);
        }
        long nextChange = until - STEADY_MS;
        for (int m : POPS) {
            long at = s.start - m * MINUTE;
            if (now >= at && now < at + POP_MS) {
                long mins = (until + MINUTE - 1) / MINUTE;
                return new State(Kind.POP, s.name + " in " + mins + " min", s, at + POP_MS - now);
            }
            if (at > now) nextChange = Math.min(nextChange, at - now);
        }
        return new State(Kind.HIDDEN, "", null, nextChange);
    }
}
