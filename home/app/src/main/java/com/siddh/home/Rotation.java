package com.siddh.home;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The streaming shelves' daily rotation as plain logic, with no Android in it, so it is
 * unit-tested off the TV. The shelves are picked afresh each calendar day: a title shown one day
 * rests the next and may come back the day after, and a nudge that changes daily reorders the
 * candidates so the same few don't win every time. The same day always gives the same nudge, so a
 * second refresh that day picks the same titles.
 */
final class Rotation {
    private Rotation() {}

    /** The calendar day of {@code millis} in {@code zone}, as yyyy-MM-dd. */
    static String day(long millis, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(zone);
        return f.format(millis);
    }

    /** The day before a yyyy-MM-dd day; empty if it isn't one. */
    static String yesterday(String day) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.setTime(f.parse(day));
            c.add(Calendar.DAY_OF_YEAR, -1);
            return f.format(c.getTime());
        } catch (ParseException e) {
            return "";
        }
    }

    /**
     * Titles resting {@code today}: those shown yesterday. The shelves remember two days, the last
     * one picked ({@code day}, {@code ids}) and the one before ({@code prevDay}, {@code prevIds}).
     * Picking again on the same day keeps resting what rested at the first pick.
     */
    static Set<String> resting(String today, String day, Set<String> ids, String prevDay, Set<String> prevIds) {
        String yesterday = yesterday(today);
        if (today.equals(day)) return yesterday.equals(prevDay) ? prevIds : Collections.<String>emptySet();
        return yesterday.equals(day) ? ids : Collections.<String>emptySet();
    }

    /** A number in [0, 1) fixed for the day and title, different from one day to the next. */
    static double nudge(String day, String key) {
        long h = (day + "|" + key).hashCode() * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }
}
