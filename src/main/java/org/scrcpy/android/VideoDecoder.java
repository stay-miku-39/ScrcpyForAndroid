package org.scrcpy.android;

import android.media.*;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

final class VideoDecoder implements AutoCloseable {
    private final MediaCodec codec;
    private volatile boolean running = true;
    private volatile Throwable failure;
    private final Thread drain;
    final AtomicLong frames = new AtomicLong();

    VideoDecoder(Surface surface, int width, int height) throws IOException {
        codec = MediaCodec.createDecoderByType("video/avc");
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            codec.configure(format, surface, null, 0); codec.start();
        } catch (RuntimeException e) { codec.release(); throw e; }
        drain = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (running) {
                    int index = codec.dequeueOutputBuffer(info, 10000);
                    if (index >= 0) { codec.releaseOutputBuffer(index, true); if (frames.incrementAndGet() == 1) DebugLog.write("video first frame rendered"); }
                    else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) DebugLog.write("video decoder format=" + codec.getOutputFormat());
                }
            } catch (RuntimeException e) { if (running) { failure = e; DebugLog.error("video output", e); } }
        }, "scrcpy-video-output"); drain.start();
    }
    void feed(MediaPacket packet) throws IOException {
        while (running) {
            if (failure != null) throw new IOException("Video decoder failed", failure);
            int index = codec.dequeueInputBuffer(10000);
            if (index < 0) continue;
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < packet.data.length) throw new IOException("Encoded video frame too large");
            buffer.clear(); buffer.put(packet.data);
            codec.queueInputBuffer(index, 0, packet.data.length, packet.pts, packet.config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0); return;
        }
    }
    void setOutputSurface(Surface surface) { codec.setOutputSurface(surface); }
    @Override public void close() {
        running = false;
        try { drain.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { codec.stop(); } finally { codec.release(); }
        DebugLog.write("video decoder closed frames=" + frames.get());
    }
}
