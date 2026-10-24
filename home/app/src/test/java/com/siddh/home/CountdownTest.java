package com.siddh.home;

import static com.siddh.home.Countdown.MINUTE;
import static com.siddh.home.Countdown.SECOND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class CountdownTest {
    private static final long T = 1_800_000_000_000L;    // qualifying starts here
    private final List<Countdown.Session> weekend = Arrays.asList(
            new Countdown.Session("Qualifying", T, Countdown.length("Qualifying")),
            new Countdown.Session("Race", T + 24 * 60 * MINUTE, Countdown.length("Race")));

    private Countdown.State at(long now) {
        return Countdown.at(weekend, now, false);
    }

    @Test
    public void hiddenBeforeTheWindow() {
        Countdown.State s = at(T - 31 * MINUTE);
        assertEquals(Countdown.Kind.HIDDEN, s.kind);
        assertNull(s.session);
        assertEquals(MINUTE, s.next);
    }

    @Test
    public void wakesExactlyWhenTheWindowOpens() {
        assertEquals(20 * SECOND, at(T - 30 * MINUTE - 20 * SECOND).next);
    }

    @Test
    public void popsAtThirtyMinutes() {
        Countdown.State s = at(T - 30 * MINUTE);
        assertEquals(Countdown.Kind.POP, s.kind);
        assertEquals("Qualifying in 30 min", s.text);
        assertEquals(Countdown.POP_MS, s.next);
    }

    @Test
    public void popEndsAfterAFewSecondsUntilTheNextPop() {
        Countdown.State s = at(T - 30 * MINUTE + Countdown.POP_MS);
        assertEquals(Countdown.Kind.HIDDEN, s.kind);
        assertEquals(5 * MINUTE - Countdown.POP_MS, s.next);
    }

    @Test
    public void everyFiveMinutesUntilTen() {
        for (int m : new int[] {30, 25, 20, 15}) {
            assertEquals("at " + m, Countdown.Kind.POP, at(T - m * MINUTE + SECOND).kind);
        }
        assertEquals("Qualifying in 25 min", at(T - 25 * MINUTE + SECOND).text);
        Countdown.State gap = at(T - 12 * MINUTE);
        assertEquals(Countdown.Kind.HIDDEN, gap.kind);
        assertEquals(2 * MINUTE, gap.next);     // wakes for the steady countdown
    }

    @Test
    public void steadyFromTenMinutes() {
        Countdown.State s = at(T - 10 * MINUTE);
        assertEquals(Countdown.Kind.STEADY, s.kind);
        assertEquals("Qualifying in 10:00", s.text);
        Countdown.State later = at(T - (7 * 60 + 42) * SECOND + 300);
        assertEquals("Qualifying in 7:42", later.text);
        assertEquals(700, later.next);      // 461.7 s left shows as 7:42 until 461 s
        assertEquals("Qualifying in 0:01", at(T - 1).text);
    }

    @Test
    public void underwayForTheSessionLength() {
        assertEquals("Qualifying underway", at(T).text);
        assertEquals(Countdown.Kind.UNDERWAY, at(T + 59 * MINUTE).kind);
        assertEquals(Countdown.Kind.HIDDEN, at(T + 60 * MINUTE).kind);
    }

    @Test
    public void raceRunsLonger() {
        long race = T + 24 * 60 * MINUTE;
        assertEquals("Race underway", at(race + 2 * 60 * MINUTE).text);
        assertEquals(Countdown.Kind.HIDDEN, at(race + 135 * MINUTE).kind);
    }

    @Test
    public void neverOverFanCodeButKeepsTime() {
        Countdown.State s = Countdown.at(weekend, T - 5 * MINUTE, true);
        assertEquals(Countdown.Kind.HIDDEN, s.kind);
        assertTrue(s.next <= SECOND);
        assertEquals(Countdown.Kind.HIDDEN, Countdown.at(weekend, T + MINUTE, true).kind);
        assertEquals(Countdown.Kind.UNDERWAY, Countdown.at(weekend, T + MINUTE, false).kind);
    }

    @Test
    public void nothingScheduled() {
        Countdown.State s = Countdown.at(Arrays.asList(), T, false);
        assertEquals(Countdown.Kind.HIDDEN, s.kind);
        assertEquals(MINUTE, s.next);
    }

    @Test
    public void labelsAndLengths() {
        assertEquals("Sprint qualifying", Countdown.label("SprintQualifying"));
        assertEquals("Practice 2", Countdown.label("SecondPractice"));
        assertEquals(45 * MINUTE, Countdown.length("SprintQualifying"));
        assertEquals(40 * MINUTE, Countdown.length("Sprint"));
        assertEquals(60 * MINUTE, Countdown.length("FirstPractice"));
    }
}
