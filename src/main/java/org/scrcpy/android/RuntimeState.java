package org.scrcpy.android;

import android.content.Context;
import java.util.concurrent.*;
import java.io.*;

final class RuntimeState {
    private static RuntimeState instance;
    final AdbManager adb; // Identity/pairing only. Active transports belong to DeviceConnection.
    private final Identity identity;
    private final java.util.LinkedHashMap<String, DeviceConnection> connections = new java.util.LinkedHashMap<>();
    private volatile DeviceConnection selected, latest;
    private final java.util.concurrent.atomic.AtomicInteger connectGeneration = new java.util.concurrent.atomic.AtomicInteger();
    volatile RemoteActivity fullscreen; // Main-thread owner; singleTask Activity supplies one fullscreen slot.
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    static final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    volatile boolean operationRunning;
    volatile String lastMessage = "";
    private android.media.AudioFocusRequest audioFocus;

    private RuntimeState(Context context) throws Exception { DebugLog.init(context); identity = new Identity(context); adb = new AdbManager(identity); }
    static synchronized RuntimeState get(Context context) throws Exception {
        if (instance == null) instance = new RuntimeState(context.getApplicationContext());
        return instance;
    }
    synchronized void acquireAudioFocus(Context context) {
        android.media.AudioManager manager = (android.media.AudioManager)context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioFocus == null) audioFocus = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener(change -> DebugLog.write("audio focus change=" + change), new android.os.Handler(android.os.Looper.getMainLooper())).build();
        DebugLog.write("audio focus result=" + manager.requestAudioFocus(audioFocus));
    }
    synchronized void releaseAudioFocusIfIdle(Context context) {
        if (audioFocus != null && connections().stream().noneMatch(DeviceConnection::hasMirror)) {
            ((android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocusRequest(audioFocus); audioFocus = null;
        }
    }
    synchronized DeviceConnection connection(String target) { return connections.get(target); }
    synchronized java.util.List<DeviceConnection> connections() { return new java.util.ArrayList<>(connections.values()); }
    DeviceConnection current() { return selected; }
    DeviceConnection latest() { return latest; }
    synchronized void select(DeviceConnection device) { if (device != null && connections.get(device.target) == device) selected = device; }
    DeviceConnection connect(String host, int port) throws Exception {
        String target = host + ":" + port;
        DeviceConnection existing = connection(target);
        if (existing != null && existing.adb.isConnected()) { select(existing); return existing; }
        int generation = connectGeneration.get();
        AdbManager manager = new AdbManager(identity);
        try {
            if (!manager.connect(host, port)) throw new IOException("连接超时，请检查端口、配对状态或被控端授权弹窗");
            synchronized (this) {
                if (generation != connectGeneration.get()) throw new IOException("连接已取消");
                return register(target, manager);
            }
        } catch (Exception error) { manager.disconnect(); throw error; }
    }
    synchronized void cancelPendingConnect() { connectGeneration.incrementAndGet(); }
    synchronized DeviceConnection register(String target, AdbManager manager) {
        DeviceConnection old = connections.remove(target);
        if (old != null) { old.connected = false; old.closeMirror(); old.worker.shutdown(); new Thread(() -> { try { old.awaitMirrorCleanup(); old.adb.disconnect(); } catch (Exception e) { DebugLog.error("replace transport", e); } }).start(); }
        DeviceConnection device = new DeviceConnection(target, manager); device.connected = true;
        connections.put(target, device); selected = latest = device; return device;
    }
    void disconnect(DeviceConnection device) throws Exception {
        if (device == null) return;
        synchronized (this) {
            if (connections.get(device.target) != device) return;
            connections.remove(device.target); device.connected = false;
            DeviceConnection last = null; for (DeviceConnection remaining : connections.values()) last = remaining;
            if (latest == device) latest = last;
            if (selected == device) selected = latest;
        }
        device.closeMirror(); device.awaitMirrorCleanup(); device.adb.disconnect(); device.worker.shutdown();
    }
    String shell(String command) throws Exception {
        DeviceConnection device = current(); if (device == null) throw new IOException("请先连接 ADB");
        return device.shell(command);
    }
    static void close(Closeable object) { if (object != null) try { object.close(); } catch (Exception ignored) { } }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
