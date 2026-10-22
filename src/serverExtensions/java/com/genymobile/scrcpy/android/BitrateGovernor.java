package com.genymobile.scrcpy.android;

/** Encoder feedback only: never queues, delays or discards compressed frames.
 * Ten 100 ms buckets approximate a rolling second. A silent/static scene cannot
 * raise the requested target above its configured value. Timestamps are monotonic. */
public final class BitrateGovernor {
    private final int target, limit;
    private final long[] bytes = new long[10];
    private final long[] epochs = new long[10];
    private final long start;
    private long lastAdjustment, lowSince = -1;
    private int current;
    private long measured;

    public BitrateGovernor(int target, int limit, long now) {
        this.target = Math.min(target, limit); this.limit = limit; current = this.target;
        start = now; lastAdjustment = now;
        java.util.Arrays.fill(epochs, -1);
    }
    /** Returns a new encoder target, or zero when no update is needed. */
    public int frame(int size, long now) {
        long epoch = now / 100; int slot = (int)(epoch % 10);
        if (epochs[slot] != epoch) { epochs[slot] = epoch; bytes[slot] = 0; }
        bytes[slot] += size;
        long total = 0;
        for (int i=0;i<10;i++) if (epochs[i] > epoch-10 && epochs[i] <= epoch) total += bytes[i];
        measured = total * 8; // 900–1000 ms of encoded payload; includes key frames.
        if (now-start < 1000 || now-lastAdjustment < 1000) return 0;
        int next = current;
        if (measured > limit) {
            next = Math.max(100000, (int)(current * (limit * .85 / measured)));
            lowSince = -1;
        } else if (measured < limit * .7) {
            if (lowSince < 0) lowSince = now;
            if (now-lowSince >= 3000) next = Math.min(target, Math.max(current+10000, (int)(current*1.1)));
        } else lowSince = -1;
        if (next == current) return 0;
        current = next; lastAdjustment = now; return next;
    }
    public long measuredBitrate() { return measured; }
    public boolean cannotReduceFurther() { return current==100000 && measured>limit; }
}
