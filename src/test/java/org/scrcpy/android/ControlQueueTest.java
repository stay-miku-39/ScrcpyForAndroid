package org.scrcpy.android;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ControlQueueTest {
    private final AtomicLong clock = new AtomicLong();
    private ControlQueue queue(int capacity) { return new ControlQueue(capacity, 512 * 1024, 250, clock::get); }
    private static byte[] touch(int action, long pointer, int x) { return ControlProtocol.touch(action, pointer, x, 10, 100, 200, action == 1 ? 0 : 1); }

    @Test public void fullQueueEvictsOldestAndKeepsLatestWithoutFailing() {
        ControlQueue queue = queue(3);
        for (int i = 5; i <= 8; i++) queue.offer(new byte[]{(byte)i});
        assertEquals(3, queue.size());
        for (int i = 6; i <= 8; i++) assertArrayEquals(new byte[]{(byte)i}, queue.poll());
        assertNull(queue.poll());
        assertTrue(queue.stats().contains("overflow=1"));
    }
    @Test public void expiresEvenBelowCapacityWhenWriteCreditFinallyArrives() {
        ControlQueue queue = queue(64);
        queue.offer(new byte[]{5}); clock.set(249); assertEquals(1, queue.size());
        clock.set(250); assertNull(queue.poll());
        assertTrue(queue.stats().contains("expired=1"));
        queue.offer(new byte[]{6}); assertArrayEquals(new byte[]{6}, queue.poll());
    }
    @Test public void movesCoalesceIndependentlyPerFingerAndPreserveDownUpBoundaries() {
        ControlQueue queue = queue(64);
        queue.offer(touch(0, 1, 0)); queue.offer(touch(0, 2, 0));
        for (int x = 0; x < 99; x++) { queue.offer(touch(2, 1, x)); queue.offer(touch(2, 2, x)); }
        assertEquals(4, queue.size());
        queue.poll(); queue.poll();
        assertArrayEquals(touch(2, 1, 98), queue.poll()); assertArrayEquals(touch(2, 2, 98), queue.poll());
        queue.offer(touch(2, 1, 20)); queue.offer(touch(1, 1, 20));
        queue.offer(touch(0, 1, 30)); queue.offer(touch(2, 1, 40));
        assertEquals(4, queue.size());
    }
    @Test public void byteLimitAlsoEvictsOldestClipboard() {
        ControlQueue queue = new ControlQueue(64, 30, 250, clock::get);
        byte[] first = ControlProtocol.clipboard(1, "one", false), second = ControlProtocol.clipboard(2, "two", false);
        queue.offer(first); queue.offer(second);
        assertEquals(1, queue.size()); assertArrayEquals(second, queue.poll());
    }
    @Test public void evictedDownDoesNotCreateOrphanMoveOrUp() {
        ControlQueue queue = queue(2); ControlInputState state = new ControlInputState();
        queue.offer(touch(0, 42, 10)); queue.offer(touch(2, 42, 20)); queue.offer(touch(1, 42, 20));
        assertSame(ControlQueue.RESET, queue.poll());
        assertEquals(0, state.prepare(queue.poll()).length); assertEquals(0, state.prepare(queue.poll()).length);
        byte[] down = touch(0, 42, 30); assertArrayEquals(down, state.prepare(down));
    }
    @Test public void expiredUpReleasesPreviouslyTransmittedFingerAndHeldChord() {
        ControlQueue queue = queue(64); ControlInputState state = new ControlInputState();
        state.sent(touch(0, 3, 5)); state.sent(ControlProtocol.keyGroup(0, 26, 25));
        queue.offer(touch(1, 3, 5)); clock.set(251);
        assertSame(ControlQueue.RESET, queue.poll());
        byte[] release = state.releases();
        assertEquals(60, release.length);
        assertEquals(1, release[1]); assertEquals(0, release[22]); assertEquals(0, release[23]);
        assertEquals(25, ByteBuffer.wrap(release, 34, 4).getInt());
        assertEquals(26, ByteBuffer.wrap(release, 48, 4).getInt());
        state.released(); assertEquals(0, state.releases().length);
        assertEquals(0, state.prepare(touch(2, 3, 50)).length);
    }
    @Test public void staleKeyDownDropsItsRepeatAndUpButFreshChordRemainsAtomic() {
        ControlInputState state = new ControlInputState();
        assertEquals(0, state.prepare(ControlProtocol.key(0, 24, 5, 1)).length);
        assertEquals(0, state.prepare(ControlProtocol.key(1, 24)).length);
        byte[] chord = ControlProtocol.keyGroup(0, 26, 25);
        assertArrayEquals(chord, state.prepare(chord)); state.sent(chord);
        byte[] repeat = ControlProtocol.key(0, 25, 2, 1);
        assertArrayEquals(repeat, state.prepare(repeat)); state.sent(repeat);
        byte[] up = ControlProtocol.keyGroup(1, 26, 25);
        assertArrayEquals(up, state.prepare(up)); state.sent(up);
        assertEquals(0, state.releases().length);
    }
    @Test public void closingDropsUnsentActionsAndRejectsNewWork() throws Exception {
        ControlQueue queue = queue(3);
        queue.offer(ControlProtocol.key(0, 26)); queue.close(); queue.offer(new byte[]{5});
        assertEquals(0, queue.size()); assertNull(queue.poll()); assertFalse(queue.awaitPending(100));
    }
    @Test public void slowConsumerStressKeepsBoundAndRecoversWithoutStuckInputs() {
        ControlQueue queue = queue(8); ControlInputState state = new ControlInputState();
        List<byte[]> transmitted = new ArrayList<>();
        for (int gesture = 0; gesture < 500; gesture++) {
            for (int finger = 0; finger < 5; finger++) queue.offer(touch(0, finger, 0));
            if (gesture % 3 == 0) for (int finger = 0; finger < 5; finger++) transmit(queue, state, transmitted);
            for (int move = 0; move < 100; move++) {
                clock.incrementAndGet();
                for (int finger = 0; finger < 5; finger++) queue.offer(touch(2, finger, move));
                assertTrue(queue.size() <= 8);
                if (move % 37 == 0) transmit(queue, state, transmitted);
            }
            for (int finger = 0; finger < 5; finger++) queue.offer(touch(1, finger, 99));
            clock.addAndGet(300); // Simulate an ACK outage longer than the operation deadline.
            while (queue.size() > 0) transmit(queue, state, transmitted);
            transmit(queue, state, transmitted);
            assertEquals(0, state.releases().length);
        }
        queue.offer(touch(0, 9, 1)); transmit(queue, state, transmitted);
        queue.offer(touch(1, 9, 2)); transmit(queue, state, transmitted);
        assertEquals(0, state.releases().length);
        assertTrue(transmitted.size() > 0);
        java.util.Set<Long> remote = new java.util.HashSet<>();
        for (byte[] packet : transmitted) {
            for (int offset = 0; offset < packet.length; offset += 32) {
                long pointer = ByteBuffer.wrap(packet, offset + 2, 8).getLong();
                int action = packet[offset + 1];
                if (action == 0) assertTrue("No duplicate DOWN", remote.add(pointer));
                else if (action == 1) assertTrue("No orphan UP", remote.remove(pointer));
                else assertTrue("No orphan MOVE", remote.contains(pointer));
            }
        }
        assertTrue("All transmitted fingers must be released", remote.isEmpty());
    }
    private static void transmit(ControlQueue queue, ControlInputState state, List<byte[]> transmitted) {
        byte[] packet = queue.poll(); if (packet == null) return;
        byte[] ready = packet == ControlQueue.RESET ? state.releases() : state.prepare(packet);
        if (ready.length == 0) return;
        transmitted.add(ready);
        if (packet == ControlQueue.RESET) state.released(); else state.sent(ready);
    }
}
