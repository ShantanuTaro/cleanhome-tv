package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Dark tiles from sunset to sunrise; Control Center's toggle overrides that until the next sunrise or sunset. */
final class DarkMode {
    private DarkMode() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("dark_mode", Context.MODE_PRIVATE);
    }

    private static boolean scheduled(Context c) {
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), c.getString(R.string.latitude),
                c.getString(R.string.longitude), ZoneId.systemDefault());
        int minute = now.getHour() * 60 + now.getMinute();
        return minute < sun[0] || minute >= sun[1];
    }

    static boolean isDark(Context c) {
        boolean scheduled = scheduled(c);
        SharedPreferences p = prefs(c);
        if (!p.contains("value")) return scheduled;
        if (p.getBoolean("at", scheduled) == scheduled) return p.getBoolean("value", scheduled);
        p.edit().clear().apply();   // the sun has moved on: the schedule takes over again
        return scheduled;
    }

    static void toggle(Context c) {
        boolean next = !isDark(c);
        prefs(c).edit().putBoolean("value", next).putBoolean("at", scheduled(c)).apply();
    }
}
