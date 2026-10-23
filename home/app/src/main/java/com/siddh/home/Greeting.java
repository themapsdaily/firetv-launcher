package com.siddh.home;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/** "Hi Siddharth!" and a question underneath that changes with the hour, the day and the apps. */
final class Greeting {
    private final Random random = new Random();
    private String lastLine = "";

    String headline(String name, Calendar now) {
        int h = now.get(Calendar.HOUR_OF_DAY);
        List<String> pick = new ArrayList<>();
        pick.add("Hi " + name + "!");
        pick.add("Hey " + name + "!");
        if (h >= 5 && h < 12) pick.add("Good morning, " + name);
        else if (h >= 12 && h < 17) pick.add("Good afternoon, " + name);
        else if (h >= 17 && h < 23) pick.add("Good evening, " + name);
        else pick.add("Still up, " + name + "?");
        pick.add("Welcome back, " + name);
        return pick.get(random.nextInt(pick.size()));
    }

    /** A new line each call, never the same one twice in a row. */
    String line(Calendar now, List<Apps.App> apps) {
        List<String> pick = lines(now, apps);
        String s;
        do {
            s = pick.get(random.nextInt(pick.size()));
        } while (s.equals(lastLine) && pick.size() > 1);
        lastLine = s;
        return s;
    }

    private List<String> lines(Calendar now, List<Apps.App> apps) {
        int h = now.get(Calendar.HOUR_OF_DAY);
        int day = now.get(Calendar.DAY_OF_WEEK);
        List<String> out = new ArrayList<>();
        out.add("What are we watching today?");
        out.add("Pick up where you left off?");
        out.add("Something new, or an old favourite?");
        out.add("Let's find something good.");
        out.add("Popcorn ready?");

        if (h >= 5 && h < 12) {
            out.add("Coffee and a quick episode?");
            out.add("Some music to start the day?");
        } else if (h >= 12 && h < 17) {
            out.add("Taking a break?");
            out.add("A little afternoon watching?");
        } else if (h >= 17 && h < 23) {
            out.add("Settle in. What's it tonight?");
            out.add("Movie night?");
            out.add("Dinner and a show?");
        } else {
            out.add("One more episode?");
            out.add("Something light before bed?");
        }

        boolean evening = h >= 17;
        if (day == Calendar.FRIDAY && evening) out.add("It's Friday. Movie night?");
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) {
            out.add("Weekend binge?");
            out.add("No plans? Perfect.");
        }
        if (day == Calendar.SUNDAY && evening) out.add("Sunday evening. Something calm?");
        if (day == Calendar.MONDAY && h < 12) out.add("Monday. Ease into it.");

        if (has(apps, "com.fancode.tv")) out.add("Any match on today?");
        if (has(apps, "com.spotify.tv.android")) out.add("Or just put on some music?");
        String a = label(apps, 0), b = label(apps, 1);
        if (a != null && b != null) out.add(a + " or " + b + " tonight?");
        return out;
    }

    private static boolean has(List<Apps.App> apps, String pkg) {
        for (Apps.App a : apps) if (a.pkg.equals(pkg)) return true;
        return false;
    }

    private static String label(List<Apps.App> apps, int i) {
        return i < apps.size() ? apps.get(i).label.toString() : null;
    }
}
