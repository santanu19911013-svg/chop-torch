package com.motofeatures.choptorch;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String FILE = "chop";

    static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("enabled", on).apply();
    }

    /** The click on torch on/off; the vibration always plays */
    static boolean sound(Context c) {
        return sp(c).getBoolean("sound", true);
    }

    static void setSound(Context c, boolean on) {
        sp(c).edit().putBoolean("sound", on).apply();
    }

    /** tools/upload_server.py on the PC, e.g. http://192.168.1.20:8765 */
    static String server(Context c) {
        return sp(c).getString("server", "");
    }

    static void setServer(Context c, String url) {
        sp(c).edit().putString("server", url.trim()).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private Prefs() {}
}
