package org.scrcpy.android;

import android.content.Context;
import java.util.concurrent.*;
import io.github.muntashirakon.adb.AdbStream;
import java.io.*;

final class RuntimeState {
    private static RuntimeState instance;
    final AdbManager adb;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    static final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    volatile ScrcpySession session;
    volatile String target = "";
    volatile boolean operationRunning;
    volatile String lastMessage = "";
    private android.media.AudioFocusRequest audioFocus;

    private RuntimeState(Context context) throws Exception { DebugLog.init(context); adb = new AdbManager(new Identity(context)); }
    static synchronized RuntimeState get(Context context) throws Exception {
        if (instance == null) instance = new RuntimeState(context.getApplicationContext());
        return instance;
    }
    synchronized void acquireAudioFocus(Context context) {
        android.media.AudioManager manager = (android.media.AudioManager)context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioFocus == null) audioFocus = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener(change -> DebugLog.write("audio focus change=" + change), new android.os.Handler(android.os.Looper.getMainLooper())).build();
        DebugLog.write("audio focus result=" + manager.requestAudioFocus(audioFocus));
    }
    synchronized void releaseAudioFocusIfIdle(Context context) {
        if (audioFocus != null && (session == null || session.isClosed())) {
            ((android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocusRequest(audioFocus); audioFocus = null;
        }
    }
    String shell(String command) throws Exception {
        try (AdbStream stream = adb.openStream("shell:" + command)) {
            ScheduledFuture<?> timeout = timers.schedule(() -> close(stream), 20, TimeUnit.SECONDS);
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                InputStream input = stream.openInputStream();
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > 1024 * 1024) throw new IOException("Shell output exceeds 1 MiB; use file transfer");
                    bytes.write(buffer, 0, n);
                }
                return bytes.toString("UTF-8");
            } finally { timeout.cancel(false); }
        }
    }
    static void close(Closeable object) { if (object != null) try { object.close(); } catch (Exception ignored) { } }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
