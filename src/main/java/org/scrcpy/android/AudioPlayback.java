package org.scrcpy.android;

import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** scrcpy config packets are converted back to Android CSD before decoding. */
final class AudioPlayback implements AutoCloseable {
    interface Sink { void pcm(byte[] bytes) throws IOException; }
    private final String format, decoderName;
    private final Sink sink;
    private final BooleanSupplier running;
    private MediaCodec decoder;
    private final QualityStats quality;
    volatile String actualDecoder = "";
    AudioPlayback(String format, String decoderName, BooleanSupplier running, Sink sink, QualityStats quality) { this.format = format; this.decoderName = decoderName; this.running = running; this.sink = sink; this.quality = quality; }
    void feed(MediaPacket packet) throws IOException {
        long started = quality.enabled(QualityStats.AUDIO_DELAY) && !packet.config ? System.nanoTime() : 0;
        try { feedPacket(packet); }
        finally { if (started != 0 && quality.enabled(QualityStats.AUDIO_DELAY)) quality.audioProcessing(System.nanoTime() - started); }
    }
    private void feedPacket(MediaPacket packet) throws IOException {
        if (quality.enabled(QualityStats.CODECS) && actualDecoder.isEmpty() && decoder != null) actualDecoder = decoder.getName();
        if ("raw".equals(format)) { if (!packet.config) sink.pcm(packet.data); return; }
        if (packet.config) { configure(packet.data); return; }
        if (decoder == null) throw new IOException("Audio data before codec configuration");
        int input;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((input = decoder.dequeueInputBuffer(10000)) < 0) {
            if (!running.getAsBoolean() || System.nanoTime() >= deadline) throw new IOException("Audio decoder input timed out");
            drain();
        }
        ByteBuffer buffer = decoder.getInputBuffer(input);
        if (buffer == null || buffer.capacity() < packet.data.length) throw new IOException("Audio packet too large");
        buffer.clear(); buffer.put(packet.data); decoder.queueInputBuffer(input, 0, packet.data.length, packet.pts, 0); drain();
    }
    private void configure(byte[] config) throws IOException {
        close(); MediaFormat media = MediaFormat.createAudioFormat(StreamSettings.audioMime(format), 48000, 2);
        media.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        media.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
        media.setByteBuffer("csd-0", ByteBuffer.wrap(config));
        if ("flac".equals(format)) {
            if (config.length != 34) throw new IOException("Invalid FLAC STREAMINFO");
            ByteBuffer header = ByteBuffer.allocate(42); header.put(new byte[]{'f','L','a','C',(byte)0x80,0,0,34}); header.put(config); header.flip();
            media.setByteBuffer("csd-0", header);
        }
        if ("opus".equals(format)) {
            if (config.length < 19 || config[0] != 'O') throw new IOException("Invalid Opus config");
            int skip = (config[10] & 255) | ((config[11] & 255) << 8);
            media.setByteBuffer("csd-1", nativeLong(skip * 1000000000L / 48000));
            media.setByteBuffer("csd-2", nativeLong(80000000L));
        }
        decoder = decoderName.isEmpty() ? MediaCodec.createDecoderByType(StreamSettings.audioMime(format)) : MediaCodec.createByCodecName(decoderName);
        if (quality.enabled(QualityStats.CODECS)) actualDecoder = decoder.getName();
        try { decoder.configure(media, null, null, 0); decoder.start(); if (DebugLog.isEnabled()) DebugLog.write("audio decoder=" + decoder.getName()); }
        catch (RuntimeException error) { decoder.release(); decoder = null; throw error; }
    }
    private static ByteBuffer nativeLong(long value) { ByteBuffer data = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()); data.putLong(value); data.flip(); return data; }
    private void drain() throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running.getAsBoolean()) {
            int index = decoder.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat output = decoder.getOutputFormat();
                if (output.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 48000 || output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 2
                        || (output.containsKey(MediaFormat.KEY_PCM_ENCODING) && output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)) throw new IOException("Unexpected decoded PCM format");
            } else if (index >= 0) {
                try {
                    ByteBuffer buffer = decoder.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) { buffer.position(info.offset); buffer.limit(info.offset + info.size); byte[] pcm = new byte[info.size]; buffer.get(pcm); sink.pcm(pcm); }
                } finally { decoder.releaseOutputBuffer(index, false); }
            } else if (index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return;
        }
    }
    @Override public void close() {
        if (decoder != null) { try { decoder.stop(); } finally { decoder.release(); decoder = null; } }
    }
}
