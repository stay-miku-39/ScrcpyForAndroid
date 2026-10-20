package org.scrcpy.android;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Freshness is checked after ADB grants write credit, not before a blocking write. */
final class ControlQueue {
    static final int MAX_ENTRIES = 64;
    static final int MAX_BYTES = 512 * 1024;
    static final long MAX_AGE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    // An empty entry asks the writer to release transmitted inputs before continuing.
    static final byte[] RESET = new byte[0];

    private static final class Entry {
        final byte[] packet;
        final long created;
        Entry(byte[] packet, long created) { this.packet = packet; this.created = created; }
    }

    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final int capacity, byteLimit;
    private final long maxAge;
    private final LongSupplier clock;
    private int bytes;
    private long expired, overflow, coalesced;
    private boolean reset, closed;

    ControlQueue() { this(MAX_ENTRIES, MAX_BYTES, MAX_AGE_NANOS, System::nanoTime); }
    ControlQueue(int capacity, int byteLimit, long maxAge, LongSupplier clock) {
        this.capacity = capacity; this.byteLimit = byteLimit; this.maxAge = maxAge; this.clock = clock;
    }
    synchronized void offer(byte[] packet) {
        if (closed) return;
        long now = clock.getAsLong();
        expire(now);
        if (isMove(packet)) {
            long pointer = pointer(packet);
            Iterator<Entry> iterator = queue.descendingIterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (isTouch(entry.packet) && pointer(entry.packet) == pointer) {
                    if (isMove(entry.packet)) {
                        iterator.remove(); bytes -= entry.packet.length; coalesced++;
                    }
                    break; // Never coalesce across DOWN/UP for a reused pointer ID.
                }
            }
        }
        while (!queue.isEmpty() && (queue.size() >= capacity || bytes + packet.length > byteLimit)) {
            discard(queue.removeFirst()); overflow++;
        }
        if (packet.length > byteLimit) { if (isInput(packet)) reset = true; overflow++; return; }
        queue.addLast(new Entry(packet, now)); bytes += packet.length; notifyAll();
    }
    synchronized boolean awaitPending(long milliseconds) throws InterruptedException {
        if (!closed && !reset && queue.isEmpty()) wait(milliseconds);
        return reset || !queue.isEmpty();
    }
    synchronized byte[] poll() {
        expire(clock.getAsLong());
        if (reset) { reset = false; return RESET; }
        Entry entry = queue.pollFirst();
        if (entry == null) return null;
        bytes -= entry.packet.length;
        return entry.packet;
    }
    private void expire(long now) {
        while (!queue.isEmpty() && now - queue.peekFirst().created >= maxAge) {
            discard(queue.removeFirst()); expired++;
        }
    }
    private void discard(Entry entry) {
        bytes -= entry.packet.length;
        if (isInput(entry.packet)) reset = true;
    }
    synchronized void close() { closed = true; queue.clear(); bytes = 0; reset = false; notifyAll(); }
    synchronized int size() { return queue.size(); }
    synchronized String stats() { return "queued=" + queue.size() + " bytes=" + bytes + " expired=" + expired + " overflow=" + overflow + " coalesced=" + coalesced; }
    static boolean isTouch(byte[] packet) { return packet.length == 32 && packet[0] == 2; }
    static boolean isMove(byte[] packet) { return isTouch(packet) && packet[1] == 2; }
    static boolean isInput(byte[] packet) { return packet.length > 0 && (packet[0] == 0 || packet[0] == 2); }
    static long pointer(byte[] packet) { return ByteBuffer.wrap(packet, 2, 8).getLong(); }
}
