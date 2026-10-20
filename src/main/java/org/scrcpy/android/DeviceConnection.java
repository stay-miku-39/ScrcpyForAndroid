package org.scrcpy.android;

import io.github.muntashirakon.adb.AdbStream;
import java.io.*;
import java.util.concurrent.*;

/** One transport and one mirror per endpoint. Views retain this object across selection changes. */
final class DeviceConnection {
    final String target;
    final AdbManager adb;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    volatile ScrcpySession session;
    private volatile ScrcpySession closingSession;
    volatile boolean connected;
    volatile int mirrorGeneration;
    private volatile BackgroundOutput background;
    private volatile ScrcpySession.Listener parkedOwner;
    DeviceConnection(String target, AdbManager adb) { this.target = target; this.adb = adb; }
    String shell(String command) throws Exception {
        try (AdbStream stream = adb.openStream("shell:" + command)) {
            ScheduledFuture<?> timeout = RuntimeState.timers.schedule(() -> RuntimeState.close(stream), 20, TimeUnit.SECONDS);
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
    boolean hasMirror() { ScrcpySession current = session; return current != null && !current.isClosed(); }
    void park(ScrcpySession current, ScrcpySession.Listener owner) throws Exception {
        if (current == null || current.isClosed() || !current.isOwnedBy(owner)) return;
        if (background == null) background = new BackgroundOutput();
        parkedOwner = owner;
        current.attachOutput(background.surface, background);
    }
    synchronized void closeOwned(ScrcpySession expected, ScrcpySession.Listener owner) {
        if (expected == null) return;
        BackgroundOutput sink = background;
        boolean owned = expected.isOwnedBy(owner) || (sink != null && parkedOwner == owner && expected.isOwnedBy(sink));
        if (!owned) return;
        if (session == expected) closeMirror(); else expected.close();
    }
    synchronized void closeMirror() {
        ++mirrorGeneration; parkedOwner = null;
        ScrcpySession current = session; session = null;
        if (current != null) { closingSession = current; current.close(); }
        try { worker.execute(() -> {
            BackgroundOutput output = background; background = null;
            if (output != null) { if (current != null) current.afterVideoStopped(output::close); else output.close(); }
        }); } catch (RejectedExecutionException ignored) { }

    }
    void awaitMirrorCleanup() { ScrcpySession current = closingSession; if (current != null) current.awaitCleanup(); }
}
