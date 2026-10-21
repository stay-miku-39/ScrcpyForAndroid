package org.scrcpy.android;

import java.util.*;
import java.util.function.LongSupplier;

/** Optional counters only. No polling, maps, frame timestamps or accumulation when disabled. */
final class QualityStats {
    static final int FPS = 1, VIDEO_RATE = 2, VIDEO_DELAY = 4, AUDIO_RATE = 8, AUDIO_DELAY = 16, CONTROL = 32, RTT = 64, CODECS = 128, INPUT_WAIT = 256;
    static final int ALL = 511;
    static final int[] ITEMS = {FPS, VIDEO_RATE, VIDEO_DELAY, INPUT_WAIT, AUDIO_RATE, AUDIO_DELAY, CONTROL, RTT, CODECS};
    static final String[] LABELS = {"视频帧率：接收 / 解码 / Surface 呈现", "视频码率与媒体包速率", "本地视频解码延迟", "视频输入缓冲等待", "音频码率与媒体包速率", "音频处理耗时与播放缓冲", "控制写入速率、队列与等待", "ADB 往返（包含 Shell 开销）", "实际编解码器与视频尺寸"};
    volatile int mask;
    private final LongSupplier clock;
    private long since;
    private long received, decoded, presented, videoBytes, videoPackets, audioBytes, audioPackets, controlWrites, controlBytes;
    private long videoDelaySum, videoDelayCount, audioDelaySum, audioDelayCount, waitSum, waitCount, controlWaitSum, controlWriteSum;
    private double rttMs = -1;
    private LinkedHashMap<Long, Long> videoPending;
    QualityStats() { this(System::nanoTime); }
    QualityStats(LongSupplier clock) { this.clock = clock; }
    boolean enabled(int item) { return (mask & item) != 0; }
    synchronized void select(int selected) {
        selected &= ALL; if (mask == selected) return;
        mask = selected; reset(); since = clock.getAsLong(); rttMs = -1;
        videoPending = enabled(VIDEO_DELAY) ? new LinkedHashMap<>() : null;
    }
    void videoPacket(int bytes, boolean frame) {
        if ((mask & (FPS | VIDEO_RATE)) == 0) return;
        synchronized (this) { if (enabled(FPS) && frame) received++; if (enabled(VIDEO_RATE)) { videoBytes += bytes; videoPackets++; } }
    }
    void audioPacket(int bytes) {
        if (!enabled(AUDIO_RATE)) return;
        synchronized (this) { if (enabled(AUDIO_RATE)) { audioBytes += bytes; audioPackets++; } }
    }
    void videoOutput(long pts) {
        if ((mask & (FPS | VIDEO_DELAY)) == 0) return;
        synchronized (this) { if (enabled(FPS)) decoded++; completeVideo(pts); }
    }
    void framePresented() { if (enabled(FPS)) synchronized (this) { if (enabled(FPS)) presented++; } }
    void videoQueued(long pts) {
        if (!enabled(VIDEO_DELAY)) return;
        synchronized (this) {
            Map<Long, Long> pending = videoPending; if (pending == null) return;
            if (pending.size() >= 128) pending.remove(pending.keySet().iterator().next());
            pending.put(pts, clock.getAsLong());
        }
    }
    void audioProcessing(long nanos) { if (enabled(AUDIO_DELAY)) synchronized (this) { if (enabled(AUDIO_DELAY)) { audioDelaySum += nanos; audioDelayCount++; } } }
    private void completeVideo(long pts) {
        Map<Long, Long> pending = videoPending; if (pending == null) return;
        Long begin = pending.remove(pts); if (begin == null) return;
        long duration = Math.max(0, clock.getAsLong() - begin);
        videoDelaySum += duration; videoDelayCount++;
    }
    void inputWait(long nanos) { if (enabled(INPUT_WAIT)) synchronized (this) { if (enabled(INPUT_WAIT)) { waitSum += nanos; waitCount++; } } }
    void controlSent(int bytes, long queuedNanos, long writeNanos) {
        if (!enabled(CONTROL)) return;
        synchronized (this) { if (enabled(CONTROL)) { controlWrites++; controlBytes += bytes; controlWaitSum += queuedNanos; controlWriteSum += writeNanos; } }
    }
    void roundTrip(double milliseconds) { if (enabled(RTT)) synchronized (this) { if (enabled(RTT)) rttMs = milliseconds; } }
    synchronized Sample sample() {
        long now = clock.getAsLong(); double seconds = Math.max(0.001, (now - since) / 1e9); since = now;
        Sample sample = new Sample(mask, received / seconds, decoded / seconds, presented / seconds,
                videoBytes * 8 / seconds / 1e6, videoPackets / seconds, audioBytes * 8 / seconds / 1000, audioPackets / seconds,
                average(videoDelaySum, videoDelayCount), average(audioDelaySum, audioDelayCount), average(waitSum, waitCount),
                controlWrites / seconds, controlBytes / seconds, average(controlWaitSum, controlWrites), average(controlWriteSum, controlWrites), rttMs);
        reset(); return sample;
    }
    private static double average(long nanos, long count) { return count == 0 ? -1 : nanos / (double)count / 1e6; }
    private void reset() {
        received = decoded = presented = videoBytes = videoPackets = audioBytes = audioPackets = controlWrites = controlBytes = 0;
        videoDelaySum = videoDelayCount = audioDelaySum = audioDelayCount = waitSum = waitCount = controlWaitSum = controlWriteSum = 0;
    }
    static final class Sample {
        final int mask;
        final double receivedFps, decodedFps, presentedFps, videoMbps, videoPps, audioKbps, audioPps, videoDelayMs, audioProcessingMs, inputWaitMs, controlPps, controlBps, controlQueueMs, controlWriteMs, rttMs;
        Sample(int mask, double... v) {
            this.mask = mask; receivedFps=v[0]; decodedFps=v[1]; presentedFps=v[2]; videoMbps=v[3]; videoPps=v[4]; audioKbps=v[5]; audioPps=v[6]; videoDelayMs=v[7]; audioProcessingMs=v[8]; inputWaitMs=v[9]; controlPps=v[10]; controlBps=v[11]; controlQueueMs=v[12]; controlWriteMs=v[13]; rttMs=v[14];
        }
    }
}
