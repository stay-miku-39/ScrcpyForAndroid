package org.scrcpy.android;

import android.content.Context;
import android.content.SharedPreferences;

/** UI-only preferences, independent of device history and ADB identity. */
final class UiPreferences {
    static final String BALL = "ballOpacity", MENU = "menuOpacity", WINDOW = "windowOpacity";
    static final int MIN_OPACITY = 20;
    static SharedPreferences get(Context context) { return context.getSharedPreferences("remote-ui", Context.MODE_PRIVATE); }
    static float opacity(Context context, String key) { return percent(get(context), key) / 100f; }
    static int percent(SharedPreferences prefs, String key) { return Math.max(MIN_OPACITY, Math.min(100, prefs.getInt(key, 100))); }
}
