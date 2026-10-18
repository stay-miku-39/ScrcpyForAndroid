package org.scrcpy.android;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DebugLog {
    static final String TAG = "ScrcpyAndroid";
    private static File file;
    static synchronized void init(Context context) {
        file = new File(context.getFilesDir(), "debug.log");
        write("app=" + BuildConfig.VERSION_NAME + " Android=" + Build.VERSION.RELEASE + " API=" + Build.VERSION.SDK_INT
                + " model=" + Build.MODEL + " server=5.0.1");
    }
    static synchronized void write(String message) {
        Log.i(TAG, message);
        if (file == null) return;
        if (file.length() > 2 * 1024 * 1024) {
            File previous = new File(file.getParentFile(), "debug.previous.log");
            if (previous.exists() && !previous.delete()) return;
            if (!file.renameTo(previous)) return;
        }
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + message + "\n");
        } catch (IOException e) { Log.e(TAG, "Log write failed", e); }
    }
    static void error(String stage, Throwable e) {
        write(stage + ": " + Log.getStackTraceString(e));
        Log.e(TAG, stage, e);
    }
    static File file(Context context) { return new File(context.getFilesDir(), "debug.log"); }
}
