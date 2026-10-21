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
    volatile boolean measureWait;
    volatile long lastWaitNanos;

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
                if (!isMove(entry.packet)) break; // Any edge/command is an ordering boundary.
                if (isTouch(entry.packet) && pointer(entry.packet) == pointer) {
                    if (isMove(entry.packet)) {
                        iterator.remove(); bytes -= entry.packet.length; coalesced++;
                    }
                    break;
                }
            }
        }
        while (!queue.isEmpty() && (queue.size() >= capacity || bytes + packet.length > byteLimit)) {
            Entry movement = null;
            for (Entry entry : queue) if (isMove(entry.packet)) { movement = entry; break; }
            if (movement != null) { queue.remove(movement); discard(movement, false); overflow++; }
            else if (isMove(packet)) { overflow++; return; } // Preserve pending gesture edges.
            else { discard(queue.removeFirst(), true); overflow++; }
        }
        if (packet.length > byteLimit) { if (isInput(packet) && !isMove(packet)) reset = true; overflow++; return; }
        queue.addLast(new Entry(packet, now)); bytes += packet.length; notifyAll();
    }
    synchronized boolean awaitPending(long milliseconds) throws InterruptedException {
        if (!closed && !reset && queue.isEmpty()) wait(milliseconds);
        return reset || !queue.isEmpty();
    }
    synchronized byte[] poll() {
        long now = clock.getAsLong(); expire(now);
        if (reset) { reset = false; return RESET; }
        Entry entry = queue.pollFirst();
        if (entry == null) return null;
        bytes -= entry.packet.length;
        if (measureWait) lastWaitNanos = Math.max(0, now - entry.created);
        return entry.packet;
    }
    private void expire(long now) {
        Iterator<Entry> entries = queue.iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next();
            // A delayed UP still has to release the transmitted finger. A stale
            // unsent DOWN or MOVE must not cancel a different, already held finger.
            if (isTouch(entry.packet) && entry.packet[1] == 1) continue;
            if (now - entry.created >= maxAge) {
                entries.remove(); discard(entry, !isTouch(entry.packet)); expired++;
            }
        }
    }
    private void discard(Entry entry, boolean resetInput) {
        bytes -= entry.packet.length;
        if (resetInput && isInput(entry.packet)) reset = true;
    }
    /** One WRTE for consecutive pointer updates; never crosses a gesture edge. */
    synchronized byte[] pollForWrite() {
        byte[] first = poll(); if (first == null || !isMove(first)) return first;
        int count = 1;
        for (Entry entry : queue) { if (!isMove(entry.packet) || count == 16) break; count++; }
        if (count == 1) return first;
        byte[] batch = new byte[count * 32]; System.arraycopy(first,0,batch,0,32);
        for (int i=1;i<count;i++) { Entry next=queue.removeFirst(); bytes-=next.packet.length; System.arraycopy(next.packet,0,batch,i*32,32); }
        return batch;
    }
    static boolean isMoveBatch(byte[] packet) {
        if (packet.length < 64 || packet.length % 32 != 0) return false;
        for (int offset=0;offset<packet.length;offset+=32) if (packet[offset]!=2 || packet[offset+1]!=2) return false;
        return true;
    }
    synchronized void close() { closed = true; queue.clear(); bytes = 0; reset = false; notifyAll(); }
    synchronized int size() { return queue.size(); }
    synchronized String stats() { return "queued=" + queue.size() + " bytes=" + bytes + " expired=" + expired + " overflow=" + overflow + " coalesced=" + coalesced; }
    static boolean isTouch(byte[] packet) { return packet.length == 32 && packet[0] == 2; }
    static boolean isMove(byte[] packet) { return isTouch(packet) && packet[1] == 2; }
    static boolean isInput(byte[] packet) { return packet.length > 0 && (packet[0] == 0 || packet[0] == 2); }
    static long pointer(byte[] packet) { return ByteBuffer.wrap(packet, 2, 8).getLong(); }
}
