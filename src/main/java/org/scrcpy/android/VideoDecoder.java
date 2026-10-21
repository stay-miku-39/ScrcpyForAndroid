package org.scrcpy.android;

import android.media.*;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

final class VideoDecoder implements AutoCloseable {
    private final MediaCodec codec;
    private final QualityStats quality;
    private final Object trackingLock = new Object();
    private android.os.HandlerThread frameCallbacks;
    volatile String description;
    private volatile boolean running = true;
    private volatile Throwable failure;
    private final Thread drain;
    final AtomicLong frames = new AtomicLong();
    private long droppedFrames;

    VideoDecoder(Surface surface, int width, int height, String mime, String name, QualityStats quality) throws IOException {
        this.quality = quality;
        codec = createCodec(mime, name);
        description = codec.getName();
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1);
            codec.configure(format, surface, null, 0); codec.start();
        } catch (RuntimeException e) { codec.release(); throw e; }
        updateQuality();
        drain = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (running) {
                    int index = codec.dequeueOutputBuffer(info, 10000);
                    if (index >= 0) {
                        quality.videoOutput(info.presentationTimeUs);
                        droppedFrames += VideoFrameDrain.renderLatest(index, () -> {
                            int next = codec.dequeueOutputBuffer(info, 0);
                            if (next >= 0) quality.videoOutput(info.presentationTimeUs);
                            if (next == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) DebugLog.write("video decoder format=" + codec.getOutputFormat());
                            return next;
                        }, (buffer, render) -> codec.releaseOutputBuffer(buffer, render));
                        if (frames.incrementAndGet() == 1) DebugLog.write("video first frame rendered");
                    }
                    else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) DebugLog.write("video decoder format=" + codec.getOutputFormat());
                }
            } catch (RuntimeException e) { if (running) { failure = e; DebugLog.error("video output", e); } }
        }, "scrcpy-video-output"); drain.start();
    }
    void updateQuality() {
        synchronized (trackingLock) {
            if (!running) return;
            try {
                if (quality.enabled(QualityStats.CODECS)) description = codec.getName() + (codec.getCodecInfo().isHardwareAccelerated() ? "（硬件）" : "（软件）");
                if (quality.enabled(QualityStats.FPS) && frameCallbacks == null) {
                    frameCallbacks = new android.os.HandlerThread("quality-frame-callback"); frameCallbacks.start();
                    codec.setOnFrameRenderedListener((c, pts, time) -> quality.framePresented(), new android.os.Handler(frameCallbacks.getLooper()));
                } else if (!quality.enabled(QualityStats.FPS) && frameCallbacks != null) {
                    codec.setOnFrameRenderedListener(null, null); frameCallbacks.quitSafely(); frameCallbacks = null;
                }
            } catch (IllegalStateException ignored) { /* Concurrent decoder teardown. */ }
        }
    }
    static boolean supports(String mime, String name) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder() || (!name.isEmpty() && !name.equals(info.getName()))) continue;
            for (String type : info.getSupportedTypes()) if (mime.equalsIgnoreCase(type)) return true;
        }
        return false;
    }
    private static MediaCodec createCodec(String mime, String name) throws IOException {
        MediaCodec preferred = name.isEmpty() ? MediaCodec.createDecoderByType(mime) : MediaCodec.createByCodecName(name);
        // The emulator's virtual decoder can accept setOutputSurface(), then
        // keep queueing to the abandoned old producer (-32), or release itself.
        // A real software component handles SurfaceView/texture handoffs reliably.
        if (name.isEmpty() && preferred.getName().startsWith("c2.goldfish.")) {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || !info.isSoftwareOnly() || info.getName().startsWith("c2.goldfish.")) continue;
                for (String type : info.getSupportedTypes()) if (mime.equalsIgnoreCase(type)) {
                    try {
                        MediaCodec software = MediaCodec.createByCodecName(info.getName());
                        preferred.release(); DebugLog.write("video decoder=" + info.getName() + " goldfish Surface handoff fallback"); return software;
                    } catch (IOException error) { DebugLog.error("software decoder unavailable", error); }
                }
            }
        }
        DebugLog.write("video decoder=" + preferred.getName()); return preferred;
    }
    void feed(MediaPacket packet) throws IOException {
        long waiting = quality.enabled(QualityStats.INPUT_WAIT) ? System.nanoTime() : 0;
        while (running) {
            if (failure != null) throw new IOException("Video decoder failed", failure);
            int index = codec.dequeueInputBuffer(10000);
            if (index < 0) continue;
            if (waiting != 0 && quality.enabled(QualityStats.INPUT_WAIT)) quality.inputWait(System.nanoTime() - waiting);
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < packet.data.length) throw new IOException("Encoded video frame too large");
            buffer.clear(); buffer.put(packet.data);
            if (!packet.config) quality.videoQueued(packet.pts);
            codec.queueInputBuffer(index, 0, packet.data.length, packet.pts, packet.config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0); return;
        }
    }
    void setOutputSurface(Surface surface) { codec.setOutputSurface(surface); }
    @Override public void close() {
        running = false;
        try { drain.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized (trackingLock) {
            if (frameCallbacks != null) { frameCallbacks.quitSafely(); frameCallbacks = null; }
            try { codec.stop(); } finally { codec.release(); }
        }
        DebugLog.write("video decoder closed frames=" + frames.get() + " dropped=" + droppedFrames);
    }
}
