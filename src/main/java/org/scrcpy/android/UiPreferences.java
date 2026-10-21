package org.scrcpy.android;

import android.content.Context;
import android.content.SharedPreferences;

/** UI-only preferences, independent of device history and ADB identity. */
final class UiPreferences {
    static final String BALL = "ballOpacity", MENU = "menuOpacity", WINDOW = "windowOpacity";
    static final String QUALITY_ENABLED = "qualityEnabled", QUALITY_ITEMS = "qualityItems", QUALITY_COLOR = "qualityTextColor", QUALITY_OPACITY = "qualityTextOpacity";
    static final String DEBUG_LOG = "debugLogging";
    static final int MIN_OPACITY = 20;
    static SharedPreferences get(Context context) { return context.getSharedPreferences("remote-ui", Context.MODE_PRIVATE); }
    static float opacity(Context context, String key) { return percent(get(context), key) / 100f; }
    static int percent(SharedPreferences prefs, String key) { return Math.max(QUALITY_OPACITY.equals(key) ? 0 : MIN_OPACITY, Math.min(100, prefs.getInt(key, 100))); }
    static int qualityMask(SharedPreferences prefs) { return prefs.getBoolean(QUALITY_ENABLED, false) ? prefs.getInt(QUALITY_ITEMS, QualityStats.FPS | QualityStats.VIDEO_RATE) & QualityStats.ALL : 0; }
    static int parseQualityColor(String text) {
        if (!text.matches("#?[0-9a-fA-F]{6}")) throw new IllegalArgumentException("质量参数字体颜色须为 6 位十六进制颜色，例如 #FFFFFF");
        return android.graphics.Color.parseColor(text.startsWith("#") ? text : "#" + text);
    }
    static int qualityColor(SharedPreferences prefs) {
        try { return parseQualityColor(prefs.getString(QUALITY_COLOR, "#FFFFFF")); }
        catch (IllegalArgumentException ignored) { return android.graphics.Color.WHITE; }
    }
}
