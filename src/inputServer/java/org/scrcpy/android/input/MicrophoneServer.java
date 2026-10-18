package org.scrcpy.android.input;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Looper;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/** Opt-in, single-UID PCM injection; launched through ADB, never installed. */
public final class MicrophoneServer {
    private static final int MAGIC = 0x53434d31; // SCM1: 48 kHz, mono, little-endian PCM16.
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[0].matches("scrcpy_mic_[0-9a-f]{16}")) throw new IllegalArgumentException("Invalid arguments");
            int uid = Integer.parseInt(args[1]);
            if (uid < 10000) throw new IllegalArgumentException("Select an installed application");
            Looper.prepareMainLooper();
            // Use the matching, unmodified scrcpy server's shell context workarounds.
            Class.forName("com.genymobile.scrcpy.Workarounds").getMethod("apply").invoke(null);
            Context context = (Context)Class.forName("com.genymobile.scrcpy.FakeContext").getMethod("get").invoke(null);
            java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean();
            Thread watchdog = new Thread(() -> { try { Thread.sleep(15000); } catch (InterruptedException ignored) { }
                if (!accepted.get()) System.exit(0); }, "input-accept-timeout");
            watchdog.setDaemon(true); watchdog.start();
            try (LocalServerSocket server = new LocalServerSocket(args[0]); LocalSocket socket = server.accept()) {
                accepted.set(true); socket.setSoTimeout(5000); // A stalled host must release the policy.
                receive(context, uid, socket);
            }
        } catch (Throwable e) {
            System.err.println("microphone receiver failed: " + e); e.printStackTrace(System.err);
        }
        System.exit(0);
    }
    private static void receive(Context context, int uid, LocalSocket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        if (input.readInt() != MAGIC) throw new IllegalArgumentException("Protocol mismatch");
        AudioManager manager = context.getSystemService(AudioManager.class);
        Object policy = null; Class<?> policyType = null; AudioTrack track = null;
        boolean acknowledged = false, registered = false;
        try {
            if (Build.VERSION.SDK_INT < 34) throw new UnsupportedOperationException("需要被控端 Android 14 或以上");
            if (context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("被控系统未向 ADB shell 提供音频路由权限");
            Class<?> ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Object builder = Class.forName(ruleType.getName() + "$Builder").getConstructor().newInstance();
            invoke(builder, "setTargetMixRole", new Class<?>[]{int.class}, 1); // MIX_ROLE_INJECTOR
            invoke(builder, "addMixRule", new Class<?>[]{int.class, Object.class}, 4, uid); // RULE_MATCH_UID
            Object rule = invoke(builder, "build", new Class<?>[0]);
            Class<?> mixType = Class.forName("android.media.audiopolicy.AudioMix");
            builder = Class.forName(mixType.getName() + "$Builder").getConstructor(ruleType).newInstance(rule);
            AudioFormat format = new AudioFormat.Builder().setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            invoke(builder, "setFormat", new Class<?>[]{AudioFormat.class}, format);
            invoke(builder, "setRouteFlags", new Class<?>[]{int.class}, 2); // ROUTE_FLAG_LOOP_BACK
            Object mix = invoke(builder, "build", new Class<?>[0]);
            policyType = Class.forName("android.media.audiopolicy.AudioPolicy");
            builder = Class.forName(policyType.getName() + "$Builder").getConstructor(Context.class).newInstance(context);
            invoke(builder, "addMix", new Class<?>[]{mixType}, mix);
            policy = invoke(builder, "build", new Class<?>[0]);
            int result = (Integer)invoke(manager, "registerAudioPolicy", new Class<?>[]{policyType}, policy);
            if (result != 0) throw new IllegalStateException("被控系统拒绝注册音频路由：" + result);
            registered = true;
            track = (AudioTrack)invoke(policy, "createAudioTrackSource", new Class<?>[]{mixType}, mix);
            if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("无法建立录音输入通道");
            track.play(); output.writeInt(MAGIC); output.writeInt(0); output.writeUTF("ready"); output.flush(); acknowledged = true;
            System.out.println("microphone route ready uid=" + uid);
            byte[] pcm = new byte[4096]; long bytes = 0;
            while (true) {
                int size = input.readInt();
                if (size == 0) break;
                if (size < 2 || size > pcm.length || (size & 1) != 0) throw new IllegalArgumentException("Invalid PCM frame size");
                input.readFully(pcm, 0, size);
                int position = 0;
                while (position < size) {
                    int written = track.write(pcm, position, size - position);
                    if (written <= 0) throw new IllegalStateException("Input track write failed: " + written);
                    position += written;
                }
                bytes += size;
            }
            System.out.println("microphone received PCM bytes=" + bytes);
        } catch (Throwable e) {
            if (!acknowledged) {
                Throwable cause = e; while (cause.getCause() != null) cause = cause.getCause();
                String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                output.writeInt(MAGIC); output.writeInt(1); output.writeUTF(message.substring(0, Math.min(512, message.length()))); output.flush();
            }
            throw e;
        } finally {
            if (track != null) { try { track.stop(); } catch (RuntimeException ignored) { } track.release(); }
            if (registered) {
                invoke(manager, "unregisterAudioPolicy", new Class<?>[]{policyType}, policy);
                System.out.println("microphone route released uid=" + uid);
            }
        }
    }
}
