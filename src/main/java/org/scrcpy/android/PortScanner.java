package org.scrcpy.android;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class PortScanner implements AutoCloseable {
    interface Listener { void progress(int done, int total); void finished(List<Integer> ports, boolean cancelled); }
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ExecutorService pool = Executors.newFixedThreadPool(32);
    static InetAddress address(String value) {
        // Numeric IPv4 only: scanning exactly one user supplied host, never a subnet or DNS expansion.
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("扫描请输入 IPv4 地址，例如 192.168.1.10");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("无效 IPv4 地址");
            int n = Integer.parseInt(parts[i]); if (n > 255) throw new IllegalArgumentException("无效 IPv4 地址"); bytes[i] = (byte)n;
        }
        try { return InetAddress.getByAddress(bytes); } catch (UnknownHostException e) { throw new IllegalArgumentException(e); }
    }
    void start(String host, int from, int to, Listener listener) {
        InetAddress target = address(host);
        if (from < 1 || to > 65535 || from > to) throw new IllegalArgumentException("端口范围必须为 1–65535，起始不能大于结束");
        int total = to - from + 1;
        AtomicInteger next = new AtomicInteger(from), done = new AtomicInteger();
        List<Integer> found = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(32);
        for (int i = 0; i < 32; i++) pool.execute(() -> {
            try {
                int port;
                while (!cancelled.get() && (port = next.getAndIncrement()) <= to) {
                    try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(target, port), 400); found.add(port); }
                    catch (Exception ignored) { }
                    int n = done.incrementAndGet(); if (n % 128 == 0 || n == total) listener.progress(n, total);
                }
            } finally { latch.countDown(); }
        });
        new Thread(() -> {
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            pool.shutdown(); Collections.sort(found); listener.finished(new ArrayList<>(found), cancelled.get());
        }, "adb-scan-results").start();
    }
    @Override public void close() { cancelled.set(true); pool.shutdown(); }
}
