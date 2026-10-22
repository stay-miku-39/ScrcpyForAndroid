package org.scrcpy.android;

import android.content.Context;
import android.media.*;
import android.view.Surface;
import io.github.muntashirakon.adb.AdbStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
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
    private final DeviceConnection device;
    final StreamSettings settings;
    private final SessionDisplay displayConfig;
    private final ConnectionTrace connectionTrace;
    final QualityStats quality = new QualityStats();
    private final AtomicBoolean probing = new AtomicBoolean();
    private volatile AdbStream qualityProbe;
    private volatile Thread qualityProbeThread;
    private volatile long trackStartAudioBytes;
    volatile String actualAudioDecoder = "", actualVideoEncoder = "", actualAudioEncoder = "";
    volatile String activeBackend = "", effectiveVideo = "h264", effectiveAudio = "raw", effectiveRateMode = "auto";
    volatile boolean bitrateLimitUnavailable;
    volatile int currentVideoBitRate, bitrateAdjustments;
    volatile int displayWidth, displayHeight;
    volatile int remoteDisplayId = -1;
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private volatile Surface surface;
    private volatile Listener listener;
    private final Object renderLock = new Object();
    private final boolean audioEnabled;
    private final List<AdbStream> streams = new CopyOnWriteArrayList<>();
    private final ControlQueue controls = new ControlQueue();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong(1);
    private volatile Thread controlWriter;
    private volatile AudioTrack track;
    private volatile VideoDecoder decoder;
    volatile int width, height;
    volatile long videoFrames, audioBytes, nonSilentAudioBytes;
    private String remoteJar;
    private final CountDownLatch videoStopped = new CountDownLatch(1);
    private final CountDownLatch cleanupStopped = new CountDownLatch(1);
    private volatile Attempt attempt;
    private boolean safeVideo, safeAudio;
    private final List<String> remoteFiles = new CopyOnWriteArrayList<>();

    private static final class Attempt {
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile boolean serverEnded, ready, audioReady;
        volatile Exception audioFailure;
        volatile String videoCodec, audioCodec, videoDecoder, audioDecoder;
        volatile AdbStream video;
        volatile ScheduledFuture<?> readiness;
        Thread audioThread;
        ConnectionTrace.Phase firstFrame;
    }

    ScrcpySession(Context context, DeviceConnection device, Surface surface, boolean audio, Listener listener) {
        this.context = context.getApplicationContext(); this.device = device; this.adb = device.adb; this.surface = surface; this.audioEnabled = audio; this.listener = listener;
        settings = StreamPreferences.snapshot(context); currentVideoBitRate = settings.videoBitRate; displayConfig = new SessionDisplay(context, device);
        connectionTrace = new ConnectionTrace(text -> this.listener.status(text), DebugLog::write);
    }
    private AdbStream open(String destination) throws Exception {
        if (closed.get()) throw new IOException("Session closed");
        AdbStream stream = adb.openStream(destination); streams.add(stream);
        if (closed.get()) { RuntimeState.close(stream); throw new IOException("Session closed"); }
        return stream;
    }
    void start() {
        if (closed.get()) { videoStopped.countDown(); return; }
        new Thread(() -> {
            try { connect(); }
            catch (Exception e) { if (!closed.get()) fail("启动投屏失败", e); }
            finally { videoStopped.countDown(); close(); }
        }, "scrcpy-start").start();
    }
    private void connect() throws Exception {
        int id = new SecureRandom().nextInt() & 0x7fffffff;
        String suffix = String.format(Locale.ROOT, "%08x", id);
        remoteJar = "/data/local/tmp/scrcpy-android-" + suffix + ".jar";
        remoteFiles.add(remoteJar);
        DebugLog.write("connect settings target=" + device.target + " resolution=" + settings.resolution + " backend=" + settings.backend
                + " local=" + settings.localWidth + "x" + settings.localHeight + " custom=" + settings.values.get("customWidth") + "x" + settings.values.get("customHeight")
                + " maxSize=" + settings.maxSize + " fps=" + settings.fps + " video=" + settings.videoCodec + " videoBitRate=" + settings.videoBitRate + " targetMode=" + settings.videoTargetMode + " videoBitRateLimit=" + settings.videoBitRateLimit + " rateMode=" + settings.videoBitRateMode
                + " audioEnabled=" + audioEnabled + " audio=" + settings.audioCodec + " audioBitRate=" + settings.audioBitRate
                + " videoEncoder=" + settings.videoEncoder + " videoDecoder=" + settings.videoDecoder
                + " audioEncoder=" + settings.audioEncoder + " audioDecoder=" + settings.audioDecoder);
        connectionTrace.call("upload", "正在上传 scrcpy 服务端…", "server=5.0.1", () -> {
            try (InputStream server = context.getAssets().open("scrcpy-server.jar")) { return AdbSync.push(adb, server, remoteJar); }
        });
        if (closed.get()) { removeServerJar(); return; }
        String encoderList = "";
        try { encoderList = connectionTrace.call("capabilities", "正在检查远端编码器和本地解码器…", "", () -> device.shell(serverCommand() + " list_encoders=true cleanup=false")); }
        catch (Exception error) { DebugLog.error("encoder capability query", error); }
        safeVideo = !VideoDecoder.supports(StreamSettings.videoMime(settings.videoCodec), settings.videoDecoder)
                || (!encoderList.isEmpty() && !encoderSupported(encoderList, "video", settings.videoCodec, settings.videoEncoder));
        safeAudio = audioEnabled && !"raw".equals(settings.audioCodec) && (!VideoDecoder.supports(StreamSettings.audioMime(settings.audioCodec), settings.audioDecoder)
                || (!encoderList.isEmpty() && !encoderSupported(encoderList, "audio", settings.audioCodec, settings.audioEncoder)));
        if (safeVideo) notice("所选视频编解码器不可用，回退 H.264 自动组件");
        if (safeAudio) notice("所选音频编解码器不可用，回退 PCM");
        if (closed.get()) return;
        DisplaySizeState original = connectionTrace.call("display-query", "正在读取被控端显示尺寸…", "", displayConfig::acquire);
        int[] requested = settings.displaySize(original.width, original.height); displayWidth = requested[0]; displayHeight = requested[1];
        DisplayFallback.run(settings.backend, route -> {
            if (closed.get()) return;
            displayConfig.restore();
            displayConfig.requireRestored();
                if ("main".equals(route) && !"original".equals(settings.resolution)) connectionTrace.call("display-apply", "正在应用被控主屏分辨率…", "requested=" + requested[0] + "x" + requested[1], () -> { displayConfig.apply(original, requested[0], requested[1]); return null; });
                if ("native".equals(route)) { displayWidth = original.width; displayHeight = original.height; notice("两种显示方式未能应用，使用原始主屏尺寸"); }
                while (!closed.get()) {
                    bitrateLimitUnavailable = false; currentVideoBitRate = settings.videoBitRate; bitrateAdjustments = 0;
                    Attempt current = new Attempt(); attempt = current;
                    current.videoCodec = safeVideo ? "h264" : settings.videoCodec; current.videoDecoder = safeVideo ? "" : settings.videoDecoder;
                    current.audioCodec = safeAudio ? "raw" : settings.audioCodec; current.audioDecoder = safeAudio ? "" : settings.audioDecoder;
                    try { runAttempt(route, requested, current, safeVideo, safeAudio); return; }
                    catch (Exception error) {
                        if (current.ready || closed.get()) throw error;
                        if (!safeVideo || !safeAudio) {
                            if ((safeVideo || ("h264".equals(settings.videoCodec) && settings.videoEncoder.isEmpty() && settings.videoDecoder.isEmpty()))
                                    && (safeAudio || ("raw".equals(settings.audioCodec))) && !"cbr".equals(settings.videoBitRateMode)) throw error;
                            safeVideo = safeAudio = true; notice("音视频启动失败，使用 H.264 / PCM 和自动码率模式重试：" + error.getMessage());
                        } else throw error;
                    } finally { stopAttempt(current); }
                }
        }, () -> { Attempt current = attempt; return !closed.get() && (current == null || !current.ready); },
                (route, error) -> notice(("virtual".equals(route) ? "虚拟显示" : "主屏尺寸") + "未能启用，尝试下一种方式：" + error.getMessage()));
    }
    private String serverCommand() { return "CLASSPATH=" + remoteJar + " app_process / com.genymobile.scrcpy.android.AndroidServer " + settings.videoBitRateLimit + " 5.0.1"; }
    static boolean encoderSupported(String list, String kind, String codec, String component) {
        for (String line : list.split("\n")) if (line.contains("--" + kind + "-codec=" + codec + " ")
                && (component.isEmpty() || line.matches(".*--" + kind + "-encoder=" + java.util.regex.Pattern.quote(component) + "(?:\\s.*)?"))) return true;
        return false;
    }
    private void notice(String text) {
        notices.add(text); DebugLog.write("stream fallback: " + text); listener.status(text);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show());
    }
    private void runAttempt(String route, int[] requested, Attempt current, boolean safeVideo, boolean safeAudio) throws Exception {
        int id = new SecureRandom().nextInt() & 0x7fffffff; String suffix = String.format(Locale.ROOT, "%08x", id);
        String launchJar = "/data/local/tmp/scrcpy-android-run-" + suffix + ".jar"; remoteFiles.add(launchJar);
        String copied = connectionTrace.call("server-prepare", "正在准备独立会话启动文件…", "route=" + route, () -> device.shell("cp " + RuntimeState.quote(remoteJar) + " " + RuntimeState.quote(launchJar) + "; echo SFA_COPY_EXIT=$?"));
        if (!copied.contains("SFA_COPY_EXIT=0")) throw new IOException("无法准备服务端启动文件");
        String command = serverCommand().replace(remoteJar, launchJar) + " scid=" + suffix + " tunnel_forward=true video_codec=" + current.videoCodec
                + " max_size=" + settings.maxSize + " max_fps=" + settings.fps + " video_bit_rate=" + settings.videoBitRate
                + " audio=" + audioEnabled + " audio_codec=" + current.audioCodec + " audio_bit_rate=" + settings.audioBitRate
                + " control=true cleanup=true clipboard_autosync=true log_level=" + (DebugLog.isEnabled() ? "debug" : "info");
        command += settings.videoRateOptions(safeVideo);
        if (!safeVideo && !settings.videoEncoder.isEmpty()) command += " video_encoder=" + settings.videoEncoder;
        if (!safeAudio && !"raw".equals(current.audioCodec) && !settings.audioEncoder.isEmpty()) command += " audio_encoder=" + settings.audioEncoder;
        if ("virtual".equals(route)) command += " new_display=" + requested[0] + "x" + requested[1] + " vd_destroy_content=false";
        remoteDisplayId = "virtual".equals(route) ? -1 : 0;
        DebugLog.write("server launch route=" + route + " requested=" + requested[0] + "x" + requested[1] + " video=" + current.videoCodec + " audio=" + current.audioCodec + " maxSize=" + settings.maxSize);
        String launchCommand = command;
        AdbStream shell = connectionTrace.call("server-launch", "正在启动 scrcpy 服务端…", "route=" + route + " scid=" + suffix, () -> open("shell:" + launchCommand));
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(shell.openInputStream(), StandardCharsets.UTF_8)); String line;
                while ((line = reader.readLine()) != null) {
                    DebugLog.write("server " + line);
                    java.util.regex.Matcher bitrate = java.util.regex.Pattern.compile("Video bitrate adjusted: target=(\\d+)").matcher(line);
                    if (!current.stopped.get() && bitrate.find()) { currentVideoBitRate = Integer.parseInt(bitrate.group(1)); bitrateAdjustments++; }
                    if (line.contains("Video bitrate limit unavailable:") && !current.stopped.get()) {
                        bitrateLimitUnavailable = true;
                        notice("远端编码器未能执行码率软上限，请降低高级目标码率。");
                    }
                    {
                        java.util.regex.Matcher encoder = java.util.regex.Pattern.compile("Using (video|audio) encoder: '([^']+)'").matcher(line);
                        if (encoder.find()) { if ("video".equals(encoder.group(1))) actualVideoEncoder = encoder.group(2); else actualAudioEncoder = encoder.group(2); }
                    }
                    java.util.regex.Matcher display = java.util.regex.Pattern.compile("New display: .*\\(id=(\\d+)\\)").matcher(line);
                    if (!current.stopped.get() && display.find()) remoteDisplayId = Integer.parseInt(display.group(1));
                }
            } catch (IOException e) { if (!closed.get() && !current.stopped.get()) DebugLog.error("server log", e); }
            finally { current.serverEnded = true; }
        }, "scrcpy-server-log").start();

        AdbStream video = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        ConnectionTrace.Phase channelPhase = connectionTrace.begin("video-channel", "正在等待远端视频通道…", "route=" + route);
        try {
        while (!closed.get() && !current.serverEnded && System.nanoTime() < deadline) {
            try { video = open("localabstract:scrcpy_" + suffix); break; }
            catch (IOException e) { Thread.sleep(100); }
        }
        if (video == null) throw new IOException("服务端未打开投屏通道，请查看服务端日志");
        channelPhase.success();
        } finally { channelPhase.close(); }
        current.video = video;
        DataInputStream videoInput = new DataInputStream(video.openInputStream());
        AdbStream first = video;
        ScheduledFuture<?> handshakeTimeout = RuntimeState.timers.schedule(() -> RuntimeState.close(first), 15, TimeUnit.SECONDS);
        try {
            AdbStream control;
            String deviceName;
            ConnectionTrace.Phase handshakePhase = connectionTrace.begin("handshake", "正在建立音视频和控制通道…", "audio=" + audioEnabled);
            try {
            if (videoInput.readUnsignedByte() != 0) throw new IOException("Invalid scrcpy dummy byte");
            AdbStream audio = audioEnabled ? open("localabstract:scrcpy_" + suffix) : null;
            control = open("localabstract:scrcpy_" + suffix);
            byte[] name = new byte[64]; videoInput.readFully(name);
            deviceName = new String(name, StandardCharsets.UTF_8).replace("\0", "").trim();
            if (videoInput.readInt() != StreamSettings.videoId(current.videoCodec)) throw new IOException("Unexpected video codec");
            // Virtual displays may be empty until an app is started. This setup message
            // bypasses the expiring input queue and is written before its writer starts.
            if ("virtual".equals(route)) connectionTrace.call("virtual-app", "正在虚拟显示中启动应用…", "requested=" + requested[0] + "x" + requested[1], () -> { byte[] start = ControlProtocol.startApp(virtualApp()); control.write(start, 0, start.length); return null; });
            if (audio != null) startAudio(audio, current); else current.audioReady = true;
            activeBackend = route; effectiveVideo = current.videoCodec; effectiveAudio = current.audioCodec;
            effectiveRateMode = safeVideo ? "auto" : settings.videoBitRateMode;
            DebugLog.write("scrcpy connected device=" + deviceName);
            handshakePhase.success();
            } finally { handshakePhase.close(); }
            handshakeTimeout.cancel(false);
            current.firstFrame = connectionTrace.begin("first-frame", "正在等待首帧与声音就绪…", "route=" + route);
            AdbStream watchedVideo = video;
            ScheduledFuture<?> firstFrameTimeout = RuntimeState.timers.schedule(() -> { if (!current.ready) RuntimeState.close(watchedVideo); }, 20, TimeUnit.SECONDS);
            current.readiness = RuntimeState.timers.scheduleWithFixedDelay(() -> {
                if (closed.get() || current.stopped.get()) { current.readiness.cancel(false); return; }
                if (currentRenderedFrames() == 0 || !current.audioReady) return;
                current.ready = true; current.readiness.cancel(false); firstFrameTimeout.cancel(false);
                current.firstFrame.success();
                startControl(control, current); listener.size(width, height);
                listener.status("已连接：" + deviceName + " · " + activeBackend + " · " + effectiveVideo + "/" + effectiveAudio
                        + (notices.isEmpty() ? "" : "（已回退，详见日志）"));
            }, 50, 50, TimeUnit.MILLISECONDS);
        try {
            while (!closed.get()) {
                MediaPacket packet = MediaPacket.read(videoInput);
                if (packet.session) {
                    synchronized (renderLock) {
                        if (decoder != null) { videoFrames += decoder.frames.get(); decoder.close(); decoder = null; }
                        width = packet.width; height = packet.height;
                        DebugLog.write("video session " + width + "x" + height); listener.size(width, height);
                        if (!current.ready) decoder = connectionTrace.call("decoder-init", "正在初始化视频解码器，等待画面…", "encoded=" + width + "x" + height + " codec=" + current.videoCodec + " decoder=" + current.videoDecoder,
                                () -> new VideoDecoder(surface, width, height, StreamSettings.videoMime(current.videoCodec), current.videoDecoder, quality));
                        else decoder = new VideoDecoder(surface, width, height, StreamSettings.videoMime(current.videoCodec), current.videoDecoder, quality);
                        decoder.updateQuality();
                    }
                } else {
                    quality.videoPacket(packet.data.length, !packet.config);
                    VideoDecoder active = decoder;
                    if (active == null) throw new IOException("Video packet before session metadata");
                    active.feed(packet);
                }
            }
        } catch (Exception e) { if (current.audioFailure != null) throw current.audioFailure; throw e; }
        finally {
            firstFrameTimeout.cancel(false);
            current.readiness.cancel(false);
            synchronized (renderLock) {
                VideoDecoder active = decoder; decoder = null;
                if (active != null) { videoFrames += active.frames.get(); active.close(); }
            }
        }
        } finally { handshakeTimeout.cancel(false); }
    }
    private String virtualApp() throws Exception {
        if (!settings.virtualApp.isEmpty()) {
            if (!device.shell("pm path " + settings.virtualApp).contains("package:")) throw new IOException("虚拟显示启动应用未安装");
            return settings.virtualApp;
        }
        try {
            String resolved = device.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
            for (String line : resolved.split("\n")) { String pkg = line.trim().split("/")[0]; if (line.contains("/") && pkg.matches("[A-Za-z0-9_.]+")) return pkg; }
        } catch (Exception error) { DebugLog.error("resolve virtual launcher", error); }
        return "com.android.settings";
    }
    private void stopAttempt(Attempt current) {
        current.stopped.set(true);
        if (current.firstFrame != null) current.firstFrame.close();
        for (AdbStream stream : streams) RuntimeState.close(stream); streams.clear();
        if (current.audioThread != null) try { current.audioThread.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    void afterVideoStopped(Runnable action) { new Thread(() -> { try { videoStopped.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { action.run(); } }, "remote-output-cleanup").start(); }
    long renderedFrames() { VideoDecoder current = decoder; return videoFrames + (current == null ? 0 : current.frames.get()); }
    long currentRenderedFrames() { VideoDecoder current = decoder; return current == null ? 0 : current.frames.get(); }
    boolean isClosed() { return closed.get(); }
    boolean isStarted() { Attempt current = attempt; return current != null && current.ready && !closed.get(); }
    boolean isOwnedBy(Listener owner) { return listener == owner; }
    void attachOutput(Surface replacement, Listener owner) throws IOException {
        quality.select(0); controls.measureWait = false; stopQualityProbe();
        synchronized (renderLock) {
            if (closed.get() || !replacement.isValid()) throw new IOException("投屏输出已失效");
            try { if (decoder != null) decoder.setOutputSurface(replacement); }
            catch (RuntimeException e) { throw new IOException("无法切换投屏窗口", e); }
            surface = replacement; listener = owner;
            if (decoder != null) decoder.updateQuality();
        }
        owner.size(width, height); DebugLog.write("video output transferred without reconnect");
    }
    private void startControl(AdbStream stream, Attempt attempt) {
        controlWriter = new Thread(() -> {
            ControlInputState inputs = new ControlInputState();
            try {
                while (!closed.get() && !attempt.stopped.get()) {
                    if (!controls.awaitPending(100)) continue;
                    // Leave entries in the queue while waiting for the previous ADB WRTE's OKAY.
                    // Otherwise a dequeued, expired DOWN/MOVE could still execute after recovery.
                    if (!stream.awaitWriteReady(100, TimeUnit.MILLISECONDS) || closed.get()) continue;
                    byte[] packet = controls.pollForWrite();
                    if (packet == null) continue;
                    byte[] ready = packet == ControlQueue.RESET ? inputs.releases() : inputs.prepare(packet);
                    if (ready.length == 0) continue;
                    long writeStarted = quality.enabled(QualityStats.CONTROL) ? System.nanoTime() : 0;
                    stream.write(ready, 0, ready.length);
                    if (writeStarted != 0 && quality.enabled(QualityStats.CONTROL)) quality.controlSent(ready.length, packet == ControlQueue.RESET ? 0 : controls.lastWaitNanos, System.nanoTime() - writeStarted);
                    if (packet == ControlQueue.RESET) inputs.released(); else inputs.sent(ready);
                }
                byte[] releases = inputs.releases();
                if (releases.length > 0) stream.write(releases, 0, releases.length);
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception e) { if (!closed.get() && !attempt.stopped.get()) fail("控制通道断开", e); }
            finally { DebugLog.write("control queue closed " + controls.stats()); }
        }, "scrcpy-control-output"); controlWriter.start();
        new Thread(() -> {
            try {
                DataInputStream in = new DataInputStream(stream.openInputStream());
                while (!closed.get() && !attempt.stopped.get()) {
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
            } catch (Exception e) { if (!closed.get() && !attempt.stopped.get()) fail("控制通道断开", e); }
        }, "scrcpy-control-input").start();
    }
    private void startAudio(AdbStream stream, Attempt attempt) {
        attempt.audioThread = new Thread(() -> {
            AudioTrack audio = null;
            AudioPlayback playback = null;
            try {
                DataInputStream in = new DataInputStream(stream.openInputStream()); int codec = in.readInt();
                if (codec == 0 || codec == 1) {
                    if (!"raw".equals(attempt.audioCodec)) throw new IOException("所选音频编码未能启动");
                    attempt.audioReady = true; listener.status("视频已连接；被控端未能采集声音"); DebugLog.write("audio disabled code=" + codec); return;
                }
                if (codec != StreamSettings.audioId(attempt.audioCodec)) throw new IOException("Unexpected audio codec");
                int minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                audio = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(Math.max(minimum, 19200)).setTransferMode(AudioTrack.MODE_STREAM).build();
                trackStartAudioBytes = audioBytes; track = audio; audio.play(); DebugLog.write("audio playback PCM 48000 Hz stereo");
                AudioTrack output = audio;
                playback = new AudioPlayback(attempt.audioCodec, attempt.audioDecoder, () -> !closed.get() && !attempt.stopped.get(), pcm -> {
                    for (byte value : pcm) if (value != 0) nonSilentAudioBytes++;
                    int offset = 0;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (offset < pcm.length && !closed.get() && !attempt.stopped.get()) {
                        int n = output.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                        if (n < 0) throw new IOException("AudioTrack write=" + n);
                        if (n == 0) {
                            if (System.nanoTime() >= deadline) throw new IOException("Audio output stalled");
                            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Audio stopped",e); }
                            continue;
                        }
                        offset += n; audioBytes += n; attempt.audioReady = true;
                    }
                }, quality);
                while (!closed.get() && !attempt.stopped.get()) {
                    MediaPacket packet = MediaPacket.read(in);
                    if (!packet.session) { quality.audioPacket(packet.data.length); playback.feed(packet); if (quality.enabled(QualityStats.CODECS)) actualAudioDecoder = playback.actualDecoder; }
                }
            } catch (Exception e) { if (!closed.get() && !attempt.stopped.get()) {
                DebugLog.error("audio", e);
                if (!attempt.ready && !"raw".equals(attempt.audioCodec)) { attempt.audioFailure = e; RuntimeState.close(attempt.video); }
                else { attempt.audioReady = true; listener.status("视频继续；声音同步失败，请查看日志"); }
            } }
            finally {
                if (playback != null) try { playback.close(); } catch (RuntimeException error) { DebugLog.error("audio decoder close", error); }
                track = null;
                if (audio != null) { try { audio.stop(); } catch (RuntimeException ignored) { } audio.release(); }
                RuntimeState.close(stream); DebugLog.write("audio closed bytes=" + audioBytes);
            }
        }, "scrcpy-audio"); attempt.audioThread.start();
    }
    void send(byte[] packet) {
        if (!closed.get()) controls.offer(packet);
    }
    void configureQuality(Listener owner, int mask) {
        if (!isOwnedBy(owner) || closed.get()) return;
        quality.select(mask); controls.measureWait = quality.enabled(QualityStats.CONTROL);
        if (!quality.enabled(QualityStats.RTT)) stopQualityProbe();
        try { device.worker.execute(() -> { VideoDecoder current = decoder; if (current != null) current.updateQuality(); }); }
        catch (RejectedExecutionException ignored) { }
    }
    String qualityCodecDescription() {
        VideoDecoder current = decoder;
        return width + "×" + height + " · " + activeBackend + "\n视频 " + effectiveVideo + " · 编码 " + (actualVideoEncoder.isEmpty() ? "自动（未上报）" : actualVideoEncoder)
                + "\n解码 " + (current == null ? "等待" : current.description) + "\n初始目标 " + settings.videoBitRate/1e6 + " Mbps · 当前请求 " + currentVideoBitRate/1e6 + " Mbps · 软上限 " + settings.videoBitRateLimit/1e6 + " Mbps" + (bitrateLimitUnavailable ? "（未生效）" : "") + " · " + settings.fps + " fps 上限 · " + effectiveRateMode
                + "\n音频 " + (audioEnabled ? effectiveAudio + " · " + (actualAudioDecoder.isEmpty() ? "PCM 直通/等待" : actualAudioDecoder) : "关闭");
    }
    int qualityControlDepth() { return controls.size(); }
    String qualityAudioBuffer() {
        AudioTrack current = track; if (current == null) return "未播放";
        try {
            long written = (audioBytes - trackStartAudioBytes) / 4;
            long queued = (written - (current.getPlaybackHeadPosition() & 0xffffffffL)) & 0xffffffffL;
            return String.format(java.util.Locale.ROOT, "缓冲 %.1f ms · 欠载 %d", queued / 48d, current.getUnderrunCount());
        } catch (IllegalStateException ignored) { return "切换中"; }
    }
    void requestQualityProbe() {
        if (!quality.enabled(QualityStats.RTT) || closed.get() || !probing.compareAndSet(false, true)) return;
        qualityProbeThread = new Thread(() -> {
            ScheduledFuture<?> timeout = null;
            long begin = System.nanoTime();
            try (AdbStream stream = adb.openStream("shell:echo")) {
                qualityProbe = stream;
                if (!quality.enabled(QualityStats.RTT) || closed.get()) return;
                timeout = RuntimeState.timers.schedule(() -> RuntimeState.close(stream), 3, TimeUnit.SECONDS);
                byte[] buffer = new byte[128]; InputStream input = stream.openInputStream(); while (input.read(buffer) >= 0) { }
                quality.roundTrip((System.nanoTime() - begin) / 1e6);
            } catch (Exception ignored) { quality.roundTrip(-1); }
            finally { if (timeout != null) timeout.cancel(false); qualityProbe = null; probing.set(false); }
        }, "quality-adb-probe"); qualityProbeThread.start();
    }
    private void stopQualityProbe() {
        AdbStream current = qualityProbe; qualityProbe = null;
        // Closing a transport stream can write to ADB; keep it off the UI thread.
        if (current != null) new Thread(() -> RuntimeState.close(current), "quality-probe-close").start();
        Thread thread = qualityProbeThread; if (thread != null) thread.interrupt();
    }
    void key(int code) { send(ControlProtocol.key(0, code)); send(ControlProtocol.key(1, code)); }
    void clipboard(String text, boolean paste) { send(ControlProtocol.clipboard(sequence.getAndIncrement(), text, paste)); }
    void getClipboard() { send(new byte[]{8, 0}); }
    private void fail(String stage, Throwable e) { DebugLog.error(stage, e); if (!closed.get()) { close(); listener.ended(stage + "：" + e.getMessage()); } }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        quality.select(0); controls.measureWait = false; stopQualityProbe();
        controls.close();
        // Close asynchronously: never block the UI on a broken USB/TCP write.
        new Thread(() -> {
            if (controlWriter != null) {
                try { controlWriter.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                controlWriter.interrupt();
            }
            for (AdbStream stream : streams) RuntimeState.close(stream);
            streams.clear();
            try { videoStopped.await(); displayConfig.release(); removeServerJar(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { cleanupStopped.countDown(); }
        }, "scrcpy-close").start();
        DebugLog.write("session closing frames=" + renderedFrames() + " audioBytes=" + audioBytes);
    }
    void awaitCleanup() { try { cleanupStopped.await(25, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private void removeServerJar() {
        if (remoteJar == null) return;
        StringBuilder command = new StringBuilder("shell:rm -f"); for (String file : remoteFiles) command.append(' ').append(RuntimeState.quote(file));
        try (AdbStream ignored = adb.openStream(command.toString())) { }
        catch (Exception ignored) { }
    }
}
