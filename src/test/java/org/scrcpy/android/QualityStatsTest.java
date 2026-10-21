package org.scrcpy.android;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class QualityStatsTest {
    @Test public void disabledStatisticsDoNotReadClockOrAccumulate() {
        QualityStats stats = new QualityStats(() -> { throw new AssertionError("Disabled hot path sampled time"); });
        stats.videoPacket(100, true); stats.audioPacket(100); stats.videoOutput(1); stats.framePresented();
        stats.videoQueued(1); stats.audioProcessing(20); stats.inputWait(20); stats.controlSent(32, 20, 30); stats.roundTrip(10);
        assertEquals(0, stats.mask);
    }
    @Test public void onlySelectedRatesAccumulateAndDeselectionClearsHistory() {
        AtomicLong now = new AtomicLong(); QualityStats stats = new QualityStats(now::get); stats.select(QualityStats.VIDEO_RATE);
        stats.videoPacket(125000, true); stats.videoOutput(1); stats.framePresented(); stats.audioPacket(1000); stats.controlSent(32, 1, 1);
        now.set(1000000000); QualityStats.Sample sample=stats.sample();
        assertEquals(1, sample.videoMbps, .001); assertEquals(1, sample.videoPps, .001);
        assertEquals(0, sample.decodedFps, 0); assertEquals(0, sample.audioKbps, 0); assertEquals(0, sample.controlPps, 0);
        stats.select(0); stats.videoPacket(100, true); stats.select(QualityStats.VIDEO_RATE); now.addAndGet(1000000000);
        assertEquals(0, stats.sample().videoMbps, 0);
    }
    @Test public void latencyPairsPresentationTimestampsAndIgnoresUnknownOutputs() {
        AtomicLong now = new AtomicLong(); QualityStats stats = new QualityStats(now::get); stats.select(QualityStats.VIDEO_DELAY);
        stats.videoQueued(40); now.set(12000000); stats.videoOutput(40); stats.videoOutput(999);
        assertEquals(12, stats.sample().videoDelayMs, .001);
        stats.select(0); stats.videoOutput(40); stats.select(QualityStats.VIDEO_DELAY); assertEquals(-1, stats.sample().videoDelayMs, 0);
    }
    @Test public void ratesUseElapsedTimeAndControlWaitIsSeparateFromWriteTime() {
        AtomicLong now = new AtomicLong(); QualityStats stats = new QualityStats(now::get); stats.select(QualityStats.FPS | QualityStats.CONTROL);
        stats.videoPacket(32, false); stats.videoPacket(32, true); stats.videoOutput(1); stats.framePresented();
        stats.controlSent(32, 2000000, 5000000); now.set(2000000000); QualityStats.Sample sample=stats.sample();
        assertEquals(.5, sample.receivedFps, .001); assertEquals(.5, sample.presentedFps, .001); assertEquals(.5, sample.controlPps, .001);
        assertEquals(2, sample.controlQueueMs, .001); assertEquals(5, sample.controlWriteMs, .001);
    }
}
