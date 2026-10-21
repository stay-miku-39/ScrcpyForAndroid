package org.scrcpy.android;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Startup-only spans, including asynchronous first-frame waiting and failed attempts. */
final class ConnectionTrace {
    interface Work<T> { T run() throws Exception; }
    private final Consumer<String> status, log;
    private final LongSupplier clock;
    private final long started;
    ConnectionTrace(Consumer<String> status, Consumer<String> log) { this(status, log, System::nanoTime); }
    ConnectionTrace(Consumer<String> status, Consumer<String> log, LongSupplier clock) {
        this.status = status; this.log = log; this.clock = clock; started = clock.getAsLong();
    }
    Phase begin(String name, String title, String parameters) {
        status.accept(title); return new Phase(name, parameters);
    }
    <T> T call(String name, String title, String parameters, Work<T> work) throws Exception {
        try (Phase phase = begin(name, title, parameters)) { T value = work.run(); phase.success(); return value; }
    }
    final class Phase implements AutoCloseable {
        private final String name;
        private final long begin = clock.getAsLong();
        private final AtomicBoolean finished = new AtomicBoolean();
        Phase(String name, String parameters) {
            this.name = name; log.accept("connect stage=" + name + " begin elapsedMs=" + millis(begin - started) + " trace=" + Long.toHexString(started) + " " + parameters);
        }
        void success() { finish("ok"); }
        private void finish(String result) {
            if (finished.compareAndSet(false, true)) {
                long now = clock.getAsLong();
                log.accept("connect stage=" + name + " result=" + result + " durationMs=" + millis(now - begin) + " elapsedMs=" + millis(now - started) + " trace=" + Long.toHexString(started));
            }
        }
        @Override public void close() { finish("failed-or-cancelled"); }
    }
    private static long millis(long nanos) { return Math.max(0, nanos / 1000000); }
}
