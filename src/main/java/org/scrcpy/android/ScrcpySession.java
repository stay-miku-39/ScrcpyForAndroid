package org.scrcpy.android;

import android.content.Context;
import android.media.*;
import android.view.Surface;
import io.github.muntashirakon.adb.AdbStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class ScrcpySession implements AutoCloseable {
    interface Listener {
        void status(String text);
        void size(int width, int height);
        void clipboard(String text);
        void ended(String reason);
    }
    private final Context context;
    private final AdbManager adb;
    private volatile Surface surface;
    private volatile Listener listener;
    private final Object renderLock = new Object();
    private final boolean audioEnabled;
    private final List<AdbStream> streams = new CopyOnWriteArrayList<>();
    private final ArrayBlockingQueue<byte[]> controls = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong(1);
    private volatile Thread controlWriter;
    private volatile AudioTrack track;
    private volatile VideoDecoder decoder;
    volatile int width, height;
    volatile long videoFrames, audioBytes, nonSilentAudioBytes;
    private String remoteJar;

    ScrcpySession(Context context, AdbManager adb, Surface surface, boolean audio, Listener listener) {
        this.context = context.getApplicationContext(); this.adb = adb; this.surface = surface; this.audioEnabled = audio; this.listener = listener;
    }
    private AdbStream open(String destination) throws Exception {
        if (closed.get()) throw new IOException("Session closed");
        AdbStream stream = adb.openStream(destination); streams.add(stream);
        if (closed.get()) { RuntimeState.close(stream); throw new IOException("Session closed"); }
        return stream;
    }
    void start() {
        new Thread(() -> {
            try { connect(); }
            catch (Exception e) { if (!closed.get()) fail("启动投屏失败", e); }
        }, "scrcpy-start").start();
    }
    private void connect() throws Exception {
        int id = new SecureRandom().nextInt() & 0x7fffffff;
        String suffix = String.format(Locale.ROOT, "%08x", id);
        remoteJar = "/data/local/tmp/scrcpy-android-" + suffix + ".jar";
        listener.status("正在上传 scrcpy 服务端…");
        try (InputStream server = context.getAssets().open("scrcpy-server.jar")) { AdbSync.push(adb, server, remoteJar); }
        if (closed.get()) { removeServerJar(); return; }
        String command = "CLASSPATH=" + remoteJar + " app_process / com.genymobile.scrcpy.Server 5.0.1"
                + " scid=" + suffix + " tunnel_forward=true video_codec=h264 max_size=1600 max_fps=60 video_bit_rate=6000000"
                + " audio=" + audioEnabled + " audio_codec=raw control=true cleanup=true clipboard_autosync=true log_level=debug";
        DebugLog.write("server launch scid=" + suffix + " audio=" + audioEnabled);
        AdbStream shell = open("shell:" + command);
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(shell.openInputStream(), StandardCharsets.UTF_8)); String line;
                while ((line = reader.readLine()) != null) DebugLog.write("server " + line);
            } catch (IOException e) { if (!closed.get()) DebugLog.error("server log", e); }
        }, "scrcpy-server-log").start();

        AdbStream video = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!closed.get() && System.nanoTime() < deadline) {
            try { video = open("localabstract:scrcpy_" + suffix); break; }
            catch (IOException e) { Thread.sleep(100); }
        }
        if (video == null) throw new IOException("服务端未打开投屏通道，请查看服务端日志");
        DataInputStream videoInput = new DataInputStream(video.openInputStream());
        AdbStream first = video;
        ScheduledFuture<?> handshakeTimeout = RuntimeState.timers.schedule(() -> RuntimeState.close(first), 15, TimeUnit.SECONDS);
        try {
            if (videoInput.readUnsignedByte() != 0) throw new IOException("Invalid scrcpy dummy byte");
            AdbStream audio = audioEnabled ? open("localabstract:scrcpy_" + suffix) : null;
            AdbStream control = open("localabstract:scrcpy_" + suffix);
            byte[] name = new byte[64]; videoInput.readFully(name);
            String device = new String(name, StandardCharsets.UTF_8).replace("\0", "").trim();
            if (videoInput.readInt() != 0x68323634) throw new IOException("Expected H.264 stream");
            startControl(control);
            if (audio != null) startAudio(audio);
            listener.status("已连接：" + device);
            DebugLog.write("scrcpy connected device=" + device);
        } finally { handshakeTimeout.cancel(false); }
        try {
            while (!closed.get()) {
                MediaPacket packet = MediaPacket.read(videoInput);
                if (packet.session) {
                    synchronized (renderLock) {
                        if (decoder != null) { videoFrames += decoder.frames.get(); decoder.close(); decoder = null; }
                        width = packet.width; height = packet.height;
                        DebugLog.write("video session " + width + "x" + height); listener.size(width, height);
                        decoder = new VideoDecoder(surface, width, height);
                    }
                } else {
                    VideoDecoder current = decoder;
                    if (current == null) throw new IOException("Video packet before session metadata");
                    current.feed(packet);
                }
            }
        } catch (Exception e) { if (!closed.get()) fail("投屏连接已断开", e); }
        finally {
            synchronized (renderLock) {
                VideoDecoder current = decoder; decoder = null;
                if (current != null) { videoFrames += current.frames.get(); current.close(); }
            }
            close();
        }
    }
    long renderedFrames() { VideoDecoder current = decoder; return videoFrames + (current == null ? 0 : current.frames.get()); }
    long currentRenderedFrames() { VideoDecoder current = decoder; return current == null ? 0 : current.frames.get(); }
    boolean isClosed() { return closed.get(); }
    boolean isOwnedBy(Listener owner) { return listener == owner; }
    void attachOutput(Surface replacement, Listener owner) throws IOException {
        synchronized (renderLock) {
            if (closed.get() || !replacement.isValid()) throw new IOException("投屏输出已失效");
            try { if (decoder != null) decoder.setOutputSurface(replacement); }
            catch (RuntimeException e) { throw new IOException("无法切换投屏窗口", e); }
            surface = replacement; listener = owner;
        }
        owner.size(width, height); DebugLog.write("video output transferred without reconnect");
    }
    private void startControl(AdbStream stream) {
        controlWriter = new Thread(() -> {
            Map<Long, byte[]> activePointers = new HashMap<>();
            try {
                OutputStream out = stream.openOutputStream();
                while (!closed.get() || !controls.isEmpty()) {
                    byte[] packet = controls.poll(100, TimeUnit.MILLISECONDS);
                    if (packet == null) continue;
                    out.write(packet); out.flush();
                    if (packet[0] == 2) {
                        long pointer = ByteBuffer.wrap(packet, 2, 8).getLong();
                        if (packet[1] == 1) activePointers.remove(pointer);
                        else {
                            byte[] up = packet.clone(); up[1] = 1; up[22] = 0; up[23] = 0; activePointers.put(pointer, up);
                        }
                    }
                }
                for (byte[] up : activePointers.values()) out.write(up);
                out.flush();
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception e) { if (!closed.get()) fail("控制通道断开", e); }
        }, "scrcpy-control-output"); controlWriter.start();
        new Thread(() -> {
            try {
                DataInputStream in = new DataInputStream(stream.openInputStream());
                while (!closed.get()) {
                    int type = in.readUnsignedByte();
                    if (type == 0) {
                        int length = in.readInt();
                        if (length < 0 || length > (1 << 18) - 5) throw new IOException("Invalid clipboard length");
                        byte[] bytes = new byte[length]; in.readFully(bytes);
                        listener.clipboard(new String(bytes, StandardCharsets.UTF_8));
                        DebugLog.write("clipboard received bytes=" + length);
                    } else if (type == 1) { DebugLog.write("clipboard acknowledged seq=" + in.readLong()); }
                    else if (type == 2) { in.readUnsignedShort(); int n = in.readUnsignedShort(); byte[] bytes = new byte[n]; in.readFully(bytes); }
                    else throw new IOException("Unknown device message=" + type);
                }
            } catch (Exception e) { if (!closed.get()) fail("控制通道断开", e); }
        }, "scrcpy-control-input").start();
    }
    private void startAudio(AdbStream stream) {
        new Thread(() -> {
            AudioTrack audio = null;
            try {
                DataInputStream in = new DataInputStream(stream.openInputStream()); int codec = in.readInt();
                if (codec == 0 || codec == 1) { listener.status("视频已连接；被控端未能采集声音"); DebugLog.write("audio disabled code=" + codec); return; }
                if (codec != 0x00726177) throw new IOException("Expected raw PCM audio");
                int minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                audio = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(Math.max(minimum, 19200)).setTransferMode(AudioTrack.MODE_STREAM).build();
                track = audio; audio.play(); DebugLog.write("audio playback PCM 48000 Hz stereo");
                while (!closed.get()) {
                    MediaPacket packet = MediaPacket.read(in);
                    if (packet.session || packet.config) continue;
                    for (byte value : packet.data) if (value != 0) nonSilentAudioBytes++;
                    int offset = 0;
                    while (offset < packet.data.length && !closed.get()) {
                        int n = audio.write(packet.data, offset, packet.data.length - offset);
                        if (n <= 0) throw new IOException("AudioTrack write=" + n);
                        offset += n; audioBytes += n;
                    }
                }
            } catch (Exception e) { if (!closed.get()) { DebugLog.error("audio", e); listener.status("视频继续；声音同步失败，请查看日志"); } }
            finally {
                track = null;
                if (audio != null) { try { audio.stop(); } catch (RuntimeException ignored) { } audio.release(); }
                RuntimeState.close(stream); DebugLog.write("audio closed bytes=" + audioBytes);
            }
        }, "scrcpy-audio").start();
    }
    void send(byte[] packet) {
        if (!closed.get() && !controls.offer(packet)) fail("控制队列阻塞，已停止会话以释放触点", new IOException("Control queue overflow"));
    }
    void key(int code) { send(ControlProtocol.key(0, code)); send(ControlProtocol.key(1, code)); }
    void clipboard(String text, boolean paste) { send(ControlProtocol.clipboard(sequence.getAndIncrement(), text, paste)); }
    void getClipboard() { send(new byte[]{8, 0}); }
    private void fail(String stage, Throwable e) { DebugLog.error(stage, e); if (!closed.get()) { close(); listener.ended(stage + "：" + e.getMessage()); } }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Close asynchronously: never block the UI on a broken USB/TCP write.
        new Thread(() -> {
            if (controlWriter != null) {
                try { controlWriter.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                controlWriter.interrupt();
            }
            for (AdbStream stream : streams) RuntimeState.close(stream);
            streams.clear();
            // Upstream cleanup normally deletes its jar. Also remove a jar uploaded before launch failed.
            removeServerJar();
        }, "scrcpy-close").start();
        DebugLog.write("session closing frames=" + renderedFrames() + " audioBytes=" + audioBytes);
    }
    private void removeServerJar() {
        if (remoteJar == null) return;
        try (AdbStream ignored = adb.openStream("shell:rm -f " + RuntimeState.quote(remoteJar))) { }
        catch (Exception ignored) { }
    }
}
