package com.siddh.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import org.junit.Test;

public class RotationTest {
    private static final Set<String> A = new HashSet<>(Arrays.asList("tv:1", "movie:2"));
    private static final Set<String> B = new HashSet<>(Arrays.asList("tv:3"));

    @Test
    public void dayIsLocal() {
        long t = 1_792_956_600_000L;    // 2026-10-25 19:30 UTC
        assertEquals("2026-10-25", Rotation.day(t, TimeZone.getTimeZone("UTC")));
        assertEquals("2026-10-26", Rotation.day(t, TimeZone.getTimeZone("Asia/Kolkata")));
    }

    @Test
    public void yesterdayAcrossMonthsAndYears() {
        assertEquals("2026-10-24", Rotation.yesterday("2026-10-25"));
        assertEquals("2026-02-28", Rotation.yesterday("2026-03-01"));
        assertEquals("2025-12-31", Rotation.yesterday("2026-01-01"));
        assertEquals("", Rotation.yesterday(""));
    }

    @Test
    public void yesterdaysPicksRestToday() {
        assertEquals(A, Rotation.resting("2026-10-25", "2026-10-24", A, "2026-10-23", B));
    }

    @Test
    public void backTheDayAfter() {
        assertTrue(Rotation.resting("2026-10-25", "2026-10-23", A, "2026-10-22", B).isEmpty());
    }

    @Test
    public void secondPickTheSameDayRestsTheSame() {
        assertEquals(B, Rotation.resting("2026-10-25", "2026-10-25", A, "2026-10-24", B));
        assertTrue(Rotation.resting("2026-10-25", "2026-10-25", A, "2026-10-22", B).isEmpty());
    }

    @Test
    public void firstEverPick() {
        assertTrue(Rotation.resting("2026-10-25", "", new HashSet<String>(), "", new HashSet<String>()).isEmpty());
    }

    @Test
    public void nudgeIsFixedForTheDayAndChangesAfter() {
        double today = Rotation.nudge("2026-10-25", "movie:155");
        assertEquals(today, Rotation.nudge("2026-10-25", "movie:155"), 0);
        assertNotEquals(today, Rotation.nudge("2026-10-26", "movie:155"), 0);
        assertNotEquals(today, Rotation.nudge("2026-10-25", "movie:156"), 0);
    }

    @Test
    public void nudgeSpreadsOverTheRange() {
        double sum = 0, lo = 1, hi = 0;
        for (int i = 0; i < 1000; i++) {
            double v = Rotation.nudge("2026-10-25", "tv:" + i);
            assertTrue(v >= 0 && v < 1);
            sum += v;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        assertEquals(0.5, sum / 1000, 0.05);
        assertTrue(lo < 0.05 && hi > 0.95);
    }
}
