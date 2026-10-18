package org.scrcpy.android;

import io.github.muntashirakon.adb.AdbStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** ADB sync v1: streaming bounded chunks, no shell interpolation or full-file buffering. */
final class AdbSync {
    private static final class TransferDeadline implements AutoCloseable {
        private final ScheduledFuture<?> future;
        TransferDeadline(AdbStream stream) {
            future = RuntimeState.timers.schedule(() -> RuntimeState.close(stream), 10, TimeUnit.MINUTES);
        }
        @Override public void close() { future.cancel(false); }
    }
    private static void header(OutputStream out, String id, int value) throws IOException {
        out.write(id.getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[]{(byte)value, (byte)(value >> 8), (byte)(value >> 16), (byte)(value >> 24)});
    }
    private static int integer(DataInputStream in) throws IOException { return Integer.reverseBytes(in.readInt()); }
    private static String id(DataInputStream in) throws IOException { byte[] b = new byte[4]; in.readFully(b); return new String(b, StandardCharsets.US_ASCII); }
    private static void request(OutputStream out, String id, String path) throws IOException {
        if (!path.startsWith("/") || path.indexOf('\0') >= 0) throw new IOException("请输入绝对路径");
        byte[] b = path.getBytes(StandardCharsets.UTF_8);
        if (b.length > 1024) throw new IOException("路径过长");
        header(out, id, b.length); out.write(b); out.flush();
    }
    private static IOException failure(DataInputStream in, int size) throws IOException {
        if (size < 0 || size > 65536) throw new IOException("Invalid sync error length");
        byte[] b = new byte[size]; in.readFully(b); return new IOException(new String(b, StandardCharsets.UTF_8));
    }
    static long push(AdbManager adb, InputStream source, String path) throws Exception {
        try (AdbStream stream = adb.openStream("sync:"); TransferDeadline deadline = new TransferDeadline(stream)) {
            OutputStream out = stream.openOutputStream(); DataInputStream in = new DataInputStream(stream.openInputStream());
            request(out, "SEND", path + ",33206");
            byte[] b = new byte[65536]; long total = 0; int n;
            while ((n = source.read(b)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                header(out, "DATA", n); out.write(b, 0, n); total += n;
            }
            header(out, "DONE", (int)(System.currentTimeMillis() / 1000)); out.flush();
            String reply = id(in); int size = integer(in);
            if ("FAIL".equals(reply)) throw failure(in, size);
            if (!"OKAY".equals(reply) || size != 0) throw new IOException("Invalid sync SEND response");
            DebugLog.write("sync push bytes=" + total); return total;
        }
    }
    static long pull(AdbManager adb, String path, OutputStream destination) throws Exception {
        try (AdbStream stream = adb.openStream("sync:"); TransferDeadline deadline = new TransferDeadline(stream)) {
            OutputStream out = stream.openOutputStream(); DataInputStream in = new DataInputStream(stream.openInputStream());
            request(out, "RECV", path); long total = 0;
            while (true) {
                String reply = id(in); int size = integer(in);
                if ("FAIL".equals(reply)) throw failure(in, size);
                if ("DONE".equals(reply)) { destination.flush(); DebugLog.write("sync pull bytes=" + total); return total; }
                if (!"DATA".equals(reply) || size < 0 || size > 65536) throw new IOException("Invalid sync RECV response");
                byte[] b = new byte[size]; in.readFully(b); destination.write(b); total += size;
            }
        }
    }
}
