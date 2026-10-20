package org.scrcpy.android;

import android.content.*;
import android.graphics.Rect;
import android.view.*;
import java.util.*;

final class StreamPreferences {
    static SharedPreferences get(Context context) { return context.getSharedPreferences("stream-settings", Context.MODE_PRIVATE); }
    static StreamSettings snapshot(Context context) {
        Rect bounds = context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        int width = bounds.width(), height = bounds.height();
        Display display = context.getSystemService(WindowManager.class).getDefaultDisplay();
        if ((display.getRotation() & 1) != 0) { int swap = width; width = height; height = swap; }
        Map<String, String> v = new LinkedHashMap<>(); SharedPreferences prefs = get(context);
        for (Map.Entry<String, String> e : StreamSettings.DEFAULTS.entrySet()) v.put(e.getKey(), prefs.getString(e.getKey(), e.getValue()));
        return new StreamSettings(v, width, height);
    }
    static void save(Context context, Map<String, String> values) {
        StreamSettings.validate(values); SharedPreferences.Editor edit = get(context).edit();
        for (Map.Entry<String, String> e : values.entrySet()) edit.putString(e.getKey(), e.getValue()); edit.apply();
    }
}
