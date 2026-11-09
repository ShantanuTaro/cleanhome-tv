package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App folders on Home: a fixed set of names, each with the packages in it, kept in SharedPreferences. */
final class Folders {
    static final String[] NAMES = {"TCL", "Utilities", "Media", "Kids"};
    static final int[] TITLES = {R.string.folder_tcl, R.string.folder_utilities, R.string.folder_media, R.string.folder_kids};
    private static boolean changed;

    private Folders() {}

    private static SharedPreferences prefs(Context c) {
        SharedPreferences p = c.getSharedPreferences("folders", Context.MODE_PRIVATE);
        if (!p.getBoolean("seeded", false)) {
            p.edit().putBoolean("seeded", true)
                    .putString("TCL", "com.tcl.partnercustomizer,com.tcl.gallery,com.tcl.MultiScreenInteraction_TV,"
                            + "com.tcl.ui_mediaCenter,com.tcl.suspension,com.tcl.guard,com.tcl.t_solo,com.tcl.dashboard")
                    .putString("Utilities", "com.neoyantra.airplaymirror.airplaymirrorappdemo,com.jio.web.androidtv")
                    .commit();
        }
        return p;
    }

    static Map<String, List<String>> load(Context c) {
        SharedPreferences p = prefs(c);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : NAMES) {
            String value = p.getString(name, "");
            out.put(name, value.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(value.split(","))));
        }
        return out;
    }

    /** Puts pkg in the named folder, out of any other; a null folder puts it back on Home. */
    static void move(Context c, String pkg, String folder) {
        SharedPreferences.Editor edit = prefs(c).edit();
        for (Map.Entry<String, List<String>> e : load(c).entrySet()) {
            e.getValue().remove(pkg);
            if (e.getKey().equals(folder)) e.getValue().add(pkg);
            edit.putString(e.getKey(), String.join(",", e.getValue()));
        }
        edit.apply();
        changed = true;
    }

    /** True once after a folder changed, so Home rebuilds its tiles. */
    static boolean takeChanged() {
        boolean was = changed;
        changed = false;
        return was;
    }
}
