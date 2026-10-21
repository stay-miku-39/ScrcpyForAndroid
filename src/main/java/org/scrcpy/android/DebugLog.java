package org.scrcpy.android;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in diagnostics with a bounded background file writer. */
final class DebugLog {
    static final String TAG = "ScrcpyAndroid";
    private static volatile File file;
    private static volatile boolean enabled;
    private static volatile long generation;
    private static volatile String environment = "";
    private static ThreadPoolExecutor writer;
    static void init(Context context) {
        environment = "app=" + BuildConfig.VERSION_NAME + " Android=" + Build.VERSION.RELEASE + " API=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL + " server=5.0.1";
        file = file(context); setEnabled(UiPreferences.get(context).getBoolean(UiPreferences.DEBUG_LOG, false));
        write("app=" + BuildConfig.VERSION_NAME + " Android=" + Build.VERSION.RELEASE + " API=" + Build.VERSION.SDK_INT
                + " model=" + Build.MODEL + " server=5.0.1");
    }
    static boolean isEnabled() { return enabled; }
    static synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value; generation++;
        if (writer != null) writer.getQueue().clear();
        if (value) write("debug logging enabled " + environment);
    }
    static void write(String message) {
        if (!enabled) return;
        synchronized (DebugLog.class) {
            if (!enabled) return;
            if (writer == null) {
                writer = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
                        task -> new Thread(task, "debug-log-writer"), new ThreadPoolExecutor.DiscardOldestPolicy());
                writer.allowCoreThreadTimeOut(true);
            }
            long ticket = generation;
            writer.execute(() -> {
                if (!enabled || generation != ticket) return;
                Log.i(TAG, message);
                File destination = file; if (destination == null || !enabled || generation != ticket) return;
                if (destination.length() > 2 * 1024 * 1024) {
                    File previous = new File(destination.getParentFile(), "debug.previous.log");
                    if (previous.exists() && !previous.delete()) return;
                    if (!destination.renameTo(previous)) return;
                }
                try (FileWriter out = new FileWriter(destination, true)) {
                    if (enabled && generation == ticket) out.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + message + "\n");
                } catch (IOException error) { if (enabled && generation == ticket) Log.e(TAG, "Log write failed", error); }
            });
        }
    }
    static void error(String stage, Throwable e) {
        if (enabled) write(stage + ": " + Log.getStackTraceString(e));
    }
    static File file(Context context) { return new File(context.getFilesDir(), "debug.log"); }
}
