package org.scrcpy.android;

import java.util.function.IntSupplier;

/** Decode every reference frame, but present only the latest immediately available output. */
final class VideoFrameDrain {
    static final int MAX_DROPS_PER_PASS = 32;
    interface Releaser { void release(int index, boolean render); }

    static int renderLatest(int first, IntSupplier dequeue, Releaser release) {
        int pending = first, dropped = 0;
        while (dropped < MAX_DROPS_PER_PASS) {
            int next = dequeue.getAsInt();
            if (next < 0) break;
            release.release(pending, false);
            pending = next; dropped++;
        }
        release.release(pending, true);
        return dropped;
    }
}
