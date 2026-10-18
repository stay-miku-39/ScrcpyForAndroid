package org.scrcpy.android;

import android.content.Context;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import io.github.muntashirakon.adb.AdbStream;
import java.io.*;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** A separate opt-in channel; it never modifies scrcpy's audio/video protocol. */
final class MicrophoneForwarder implements AutoCloseable {
    static final int MAGIC = 0x53434d31, SAMPLE_RATE = 48000;
    interface Listener { void started(MicrophoneForwarder forwarding); void stopped(MicrophoneForwarder forwarding, String error); }
    interface PcmSource extends AutoCloseable {
        void start() throws Exception;
        int read(byte[] buffer) throws Exception;
        void close();
    }
    private final Context context;
    private final RuntimeState runtime;
    final String targetPackage;
    private final Listener listener;
    private final PcmSource source;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CopyOnWriteArrayList<AdbStream> streams = new CopyOnWriteArrayList<>();
    private volatile boolean active;
    private volatile long lastWrite;
    private volatile String forcedFailure;
    volatile long sentBytes;
    private volatile String inputJar, scrcpyJar;
    MicrophoneForwarder(Context context, RuntimeState runtime, String targetPackage, Listener listener) {
        this(context, runtime, targetPackage, listener, new LocalMicrophone());
    }
    MicrophoneForwarder(Context context, RuntimeState runtime, String targetPackage, Listener listener, PcmSource source) {
        if (!validPackage(targetPackage)) throw new IllegalArgumentException("请输入有效的被控应用包名");
        this.context = context.getApplicationContext(); this.runtime = runtime; this.targetPackage = targetPackage;
        this.listener = listener; this.source = source;
    }
    static boolean validPackage(String name) { return name != null && name.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"); }
    static String foregroundPackage(String activities) {
        for (String line : activities.split("\n")) {
            if (!line.contains("topResumedActivity") && !line.contains("mResumedActivity")) continue;
            Matcher component = Pattern.compile("\\s([A-Za-z][A-Za-z0-9_.]*)/").matcher(line);
            if (component.find() && validPackage(component.group(1))) return component.group(1);
        }
        return "";
    }
    static int packageUid(String packageList, String target) throws IOException {
        Matcher entry = Pattern.compile("(?m)^package:" + Pattern.quote(target) + "\\s+uid:(\\d+)\\s*$").matcher(packageList);
        if (!entry.find()) throw new IOException("未找到被控应用：" + target);
        int uid = Integer.parseInt(entry.group(1));
        if (uid < 10000) throw new IOException("请选择普通被控应用");
        return uid;
    }
    boolean isActive() { return active && !closed.get(); }
    boolean isClosed() { return closed.get(); }
    void start() {
        new Thread(() -> {
            String failure = null;
            try { forward(); }
            catch (Exception e) { if (!closed.get()) { failure = e.getMessage() == null ? e.toString() : e.getMessage(); DebugLog.error("microphone forwarding", e); } }
            finally { close(); listener.stopped(this, failure == null ? forcedFailure : failure); cleanupFiles(); }
        }, "microphone-forward").start();
    }
    private void checkOpen() throws IOException { if (closed.get()) throw new IOException("Microphone forwarding stopped"); }
    private AdbStream open(String name) throws Exception {
        checkOpen(); AdbStream stream = runtime.adb.openStream(name); streams.add(stream);
        if (closed.get()) { RuntimeState.close(stream); throw new IOException("Microphone forwarding stopped"); }
        return stream;
    }
    private void forward() throws Exception {
        int uid = packageUid(runtime.shell("cmd package list packages -U " + RuntimeState.quote(targetPackage)), targetPackage);
        String suffix = String.format(Locale.ROOT, "%016x", new SecureRandom().nextLong());
        String socketName = "scrcpy_mic_" + suffix;
        inputJar = "/data/local/tmp/scrcpy-input-" + suffix + ".jar";
        scrcpyJar = "/data/local/tmp/scrcpy-input-base-" + suffix + ".jar";
        upload("scrcpy-input-server.jar", inputJar); upload("scrcpy-server.jar", scrcpyJar); checkOpen();
        String command = "CLASSPATH=" + RuntimeState.quote(inputJar + ":" + scrcpyJar)
                + " app_process / org.scrcpy.android.input.MicrophoneServer " + socketName + " " + uid;
        AdbStream process = open("shell:" + command);
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.openInputStream(), "UTF-8")); String line;
                while ((line = reader.readLine()) != null) DebugLog.write("input receiver " + line);
            } catch (IOException e) { if (!closed.get()) DebugLog.error("input receiver log", e); }
        }, "microphone-receiver-log").start();
        AdbStream data = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!closed.get() && System.nanoTime() < deadline) {
            try { data = open("localabstract:" + socketName); break; }
            catch (IOException e) { Thread.sleep(100); }
        }
        if (data == null) throw new IOException("被控端未打开麦克风接收通道，请查看日志");
        AdbStream transport = data;
        ScheduledFuture<?> timeout = RuntimeState.timers.schedule(() -> RuntimeState.close(transport), 6, TimeUnit.SECONDS);
        // One ADB WRTE per frame: sending the length separately causes an extra
        // stop-and-wait round trip and audible gaps on wireless connections.
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(data.openOutputStream(), 8192));
        try {
            output.writeInt(MAGIC); output.flush(); DataInputStream input = new DataInputStream(data.openInputStream());
            if (input.readInt() != MAGIC) throw new IOException("麦克风通道协议不匹配");
            int result = input.readInt(); String message = input.readUTF();
            if (result != 0) throw new IOException(message);
        } finally { timeout.cancel(false); }
        checkOpen(); source.start(); checkOpen(); active = true; lastWrite = System.nanoTime();
        DebugLog.write("microphone forwarding active package=" + targetPackage + " uid=" + uid); listener.started(this);
        ScheduledFuture<?> watchdog = RuntimeState.timers.scheduleAtFixedRate(() -> {
            if (!closed.get() && System.nanoTime() - lastWrite > TimeUnit.SECONDS.toNanos(5)) {
                forcedFailure = "麦克风传输已超时，转发已停止"; close();
            }
        }, 1, 1, TimeUnit.SECONDS);
        try {
            byte[] buffer = new byte[3840];
            while (!closed.get()) {
                int n = source.read(buffer); checkOpen();
                if (n <= 0 || n > buffer.length || (n & 1) != 0) throw new IOException("主控麦克风读取失败：" + n);
                output.writeInt(n); output.write(buffer, 0, n); output.flush(); sentBytes += n; lastWrite = System.nanoTime();
            }
        } finally { watchdog.cancel(false); }
    }
    private void upload(String asset, String destination) throws Exception {
        checkOpen();
        try (InputStream input = context.getAssets().open(asset)) { AdbSync.push(runtime.adb, input, destination); }
        checkOpen();
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active = false;
        // Stop AudioRecord immediately; closing the ADB socket releases the target AudioPolicy.
        source.close();
        new Thread(() -> {
            // Close the data socket before the process log stream, allowing target finally blocks.
            for (int i = streams.size() - 1; i >= 0; i--) RuntimeState.close(streams.get(i));
            DebugLog.write("microphone forwarding stopped bytes=" + sentBytes);
        }, "microphone-cleanup").start();
    }
    private void cleanupFiles() {
        if (inputJar == null || scrcpyJar == null) return;
        // adbd may briefly reject a new shell while the receiver's shell is closing.
        for (int attempt = 0; attempt < 3; attempt++) {
            try { runtime.shell("rm -f " + RuntimeState.quote(inputJar) + " " + RuntimeState.quote(scrcpyJar)); return; }
            catch (Exception e) {
                if (attempt == 2) { DebugLog.write("microphone temp cleanup: " + e.getMessage()); return; }
                try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
    private static final class LocalMicrophone implements PcmSource {
        private AudioRecord record;
        private AcousticEchoCanceler echo;
        private NoiseSuppressor noise;
        private boolean closed;
        @Override public synchronized void start() throws IOException {
            if (closed) throw new IOException("Microphone closed");
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("主控不支持 48 kHz 单声道录音");
            record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(7680, minimum * 2)).build();
            try {
                if (AcousticEchoCanceler.isAvailable()) { echo = AcousticEchoCanceler.create(record.getAudioSessionId()); if (echo != null) echo.setEnabled(true); }
                if (NoiseSuppressor.isAvailable()) { noise = NoiseSuppressor.create(record.getAudioSessionId()); if (noise != null) noise.setEnabled(true); }
            } catch (RuntimeException e) { DebugLog.write("microphone optional effects unavailable: " + e.getMessage()); }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("无法开始录音，请检查麦克风权限和系统隐私开关");
        }
        @Override public int read(byte[] buffer) throws IOException {
            AudioRecord current; synchronized (this) { current = record; }
            if (current == null) throw new IOException("Microphone closed");
            return current.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
        }
        @Override public synchronized void close() {
            closed = true;
            if (record != null) { try { record.stop(); } catch (RuntimeException ignored) { } record.release(); record = null; }
            if (echo != null) { echo.release(); echo = null; } if (noise != null) { noise.release(); noise = null; }
        }
    }
}
