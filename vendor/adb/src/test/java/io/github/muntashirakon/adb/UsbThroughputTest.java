// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import io.github.muntashirakon.adb.desktop.DesktopUsb;

/**
 * Throughput / stability benchmark for the USB transport against a real attached phone. Measures read
 * (device -> host, the acquisition-relevant direction) throughput, checks large transfers complete with an
 * exact byte count (no truncation, no timeout), and characterises small-command round-trip latency.
 * <p>
 * Opt-in and device-gated so it never runs in a normal suite. Run with:
 * <pre>
 *   adb kill-server
 *   ./gradlew :libadb:testReleaseUnitTest --tests '*UsbThroughputTest' -Dlibadb.usb.benchmark=true
 * </pre>
 */
public class UsbThroughputTest {
    private static final long MiB = 1024 * 1024;

    @BeforeClass
    public static void setUp() {
        if (System.getProperty("jna.library.path") == null
                && System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            System.setProperty("jna.library.path", "/opt/homebrew/lib:/usr/local/lib");
        }
        Assume.assumeTrue("set -Dlibadb.usb.benchmark=true to run the USB benchmark",
                Boolean.getBoolean("libadb.usb.benchmark"));
    }

    @Test(timeout = 300_000)
    public void benchmark() throws Exception {
        DesktopUsb.Session opened = DesktopUsb.open();
        Assume.assumeTrue("No USB ADB connection (see console for why); skipping", opened != null);
        try (DesktopUsb.Session session = opened) {
            AdbConnection connection = session.connection;

            // Prefer the raw exec pipe (no PTY throttling); fall back to a shell PTY if the device's
            // adbd doesn't serve exec: over this transport.
            boolean useExec;
            try {
                useExec = drain(connection, "exec:dd if=/dev/zero bs=1024 count=1 2>/dev/null") > 0;
            } catch (Exception e) {
                useExec = false;
            }
            System.out.println("transfer path: " + (useExec ? "exec: (raw pipe)" : "shell: (PTY)"));

            // Warm up (spin up dd, prime the pipe) — not measured.
            readZeros(connection, useExec, 50);

            System.out.println("\n=== USB read throughput (device -> host) ===");
            double best = 0;
            for (long sizeMiB : new long[]{100, 200, 400}) {
                long start = System.nanoTime();
                long bytes = readZeros(connection, useExec, sizeMiB);
                double seconds = (System.nanoTime() - start) / 1e9;
                double mbps = (bytes / (double) MiB) / seconds;
                best = Math.max(best, mbps);
                System.out.printf("  %4d MiB in %6.2f s = %6.1f MiB/s%n", sizeMiB, seconds, mbps);
                assertEquals("transfer must be complete (no truncation/timeout)", sizeMiB * MiB, bytes);
            }

            System.out.println("\n=== Large sustained transfer (no-timeout check) ===");
            long big = 1024; // 1 GiB
            long start = System.nanoTime();
            long bytes = readZeros(connection, useExec, big);
            double seconds = (System.nanoTime() - start) / 1e9;
            System.out.printf("  %4d MiB in %6.2f s = %6.1f MiB/s%n", big, seconds,
                    (bytes / (double) MiB) / seconds);
            assertEquals("1 GiB transfer must complete exactly", big * MiB, bytes);

            System.out.println("\n=== File pull via sync: protocol (the real acquisition path) ===");
            String path = "/data/local/tmp/libadb_bench.bin";
            drain(connection, "shell:dd if=/dev/zero of=" + path + " bs=1m count=400 2>/dev/null");
            for (int i = 1; i <= 2; i++) {
                long ps = System.nanoTime();
                long pulled = syncRecv(connection, path);
                double psec = (System.nanoTime() - ps) / 1e9;
                System.out.printf("  sync pull #%d: %.0f MiB in %6.2f s = %6.1f MiB/s%n",
                        i, pulled / (double) MiB, psec, (pulled / (double) MiB) / psec);
                assertEquals("sync pull must be complete", 400 * MiB, pulled);
            }
            drain(connection, "shell:rm -f " + path);

            System.out.println("\n=== Small-command round-trip latency ===");
            int iterations = 20;
            long rtStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                drain(connection, "shell:getprop ro.product.model");
            }
            double avgMs = (System.nanoTime() - rtStart) / 1e6 / iterations;
            System.out.printf("  %d x getprop round-trip: %.1f ms average%n", iterations, avgMs);

            System.out.println();
            assertTrue("throughput implausibly low (something is stalling)", best > 1.0);
        }
    }

    /** Streams {@code sizeMiB} of zeros from the device and returns the bytes read (stderr dropped so the
     *  count is exactly the payload). */
    private static long readZeros(AdbConnection connection, boolean useExec, long sizeMiB) throws Exception {
        String prefix = useExec ? "exec:" : "shell:";
        return drain(connection, prefix + "dd if=/dev/zero bs=1048576 count=" + sizeMiB + " 2>/dev/null");
    }

    /** Pull a file via the ADB sync protocol (RECV) — the same path {@code adb pull} and file acquisition use. */
    private static long syncRecv(AdbConnection connection, String path) throws Exception {
        try (AdbStream stream = connection.open("sync:")) {
            OutputStream out = stream.openOutputStream();
            InputStream in = stream.openInputStream();
            byte[] p = path.getBytes(StandardCharsets.UTF_8);
            ByteBuffer req = ByteBuffer.allocate(8 + p.length).order(ByteOrder.LITTLE_ENDIAN);
            req.put("RECV".getBytes(StandardCharsets.US_ASCII)).putInt(p.length).put(p);
            out.write(req.array());
            out.flush();

            long total = 0;
            byte[] header = new byte[8];
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                readFully(in, header, 8);
                String id = new String(header, 0, 4, StandardCharsets.US_ASCII);
                int value = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if ("DATA".equals(id)) {
                    int remaining = value;
                    while (remaining > 0) {
                        int n = in.read(buffer, 0, Math.min(remaining, buffer.length));
                        if (n < 0) throw new EOFException("sync stream ended mid-DATA");
                        remaining -= n;
                        total += n;
                    }
                } else if ("DONE".equals(id)) {
                    break;
                } else if ("FAIL".equals(id)) {
                    byte[] msg = new byte[value];
                    readFully(in, msg, value);
                    throw new java.io.IOException("sync FAIL: " + new String(msg, StandardCharsets.UTF_8));
                } else {
                    throw new java.io.IOException("unexpected sync id: " + id);
                }
            }
            return total;
        }
    }

    private static void readFully(InputStream in, byte[] buffer, int length) throws Exception {
        int off = 0;
        while (off < length) {
            int n = in.read(buffer, off, length - off);
            if (n < 0) throw new EOFException("stream ended after " + off + " of " + length + " bytes");
            off += n;
        }
    }

    private static long drain(AdbConnection connection, String destination) throws Exception {
        try (AdbStream stream = connection.open(destination)) {
            InputStream in = stream.openInputStream();
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
            }
            return total;
        }
    }
}
