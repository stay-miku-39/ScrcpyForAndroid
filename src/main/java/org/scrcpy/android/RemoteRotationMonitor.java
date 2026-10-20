package org.scrcpy.android;

import io.github.muntashirakon.adb.AdbStream;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finite, cancellable reads of DisplayManager's shell interface. Fullscreen only. */
final class RemoteRotationMonitor implements AutoCloseable {
    interface Listener { void rotation(RemoteOrientation.Info info); }
    private final DeviceConnection state;
    private final int displayId;
    private final Listener listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed = new AtomicBoolean(), refreshPending = new AtomicBoolean();
    private volatile AdbStream current;
    private boolean unavailableLogged;
    RemoteRotationMonitor(DeviceConnection state, int displayId, Listener listener) { this.state = state; this.displayId = displayId; this.listener = listener; }
    void start() { worker.scheduleWithFixedDelay(this::poll, 0, 1200, TimeUnit.MILLISECONDS); }
    void refresh() {
        if (closed.get() || !refreshPending.compareAndSet(false, true)) return;
        try { worker.execute(() -> { refreshPending.set(false); poll(); }); }
        catch (RejectedExecutionException ignored) { refreshPending.set(false); }
    }
    private void poll() {
        if (closed.get()) return;
        RemoteOrientation.Info info = null;
        try (AdbStream stream = state.adb.openStream("shell:cmd display get-displays")) {
            current = stream; if (closed.get()) return;
            ScheduledFuture<?> timeout = RuntimeState.timers.schedule(() -> RuntimeState.close(stream), 2, TimeUnit.SECONDS);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                InputStream input = stream.openInputStream();
                while ((n = input.read(buffer)) != -1) {
                    if (output.size() + n > 256 * 1024) throw new IOException("Display response too large");
                    output.write(buffer, 0, n);
                }
                info = RemoteOrientation.parse(output.toString("UTF-8"), displayId);
            } finally { timeout.cancel(false); }
        } catch (Exception error) {
            if (!closed.get() && !unavailableLogged) DebugLog.write("remote rotation query unavailable: " + error.getMessage());
        } finally { current = null; }
        if (!closed.get()) {
            if (info == null && !unavailableLogged) DebugLog.write("remote rotation uses video aspect fallback");
            unavailableLogged = info == null; listener.rotation(info);
        }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdownNow(); AdbStream stream = current;
        if (stream != null) new Thread(() -> RuntimeState.close(stream), "rotation-query-close").start();
    }
}
