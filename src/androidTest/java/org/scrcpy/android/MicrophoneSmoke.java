package org.scrcpy.android;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicReference;

final class MicrophoneSmoke {
    interface Check { boolean done() throws Exception; }
    static void until(Check check, int seconds, String failure) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (SystemClock.elapsedRealtime() < deadline) { if (check.done()) return; Thread.sleep(150); }
        throw new AssertionError(failure);
    }
    static String run(Instrumentation test, Context context, RuntimeState runtime, String host, int port) throws Exception {
        if (!runtime.adb.connect(host, port)) throw new AssertionError("ADB connection failed");
        runtime.target = host + ":" + port;
        RemoteActivity remote = (RemoteActivity)test.startActivitySync(new Intent(context, RemoteActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("audio", false));
        AtomicReference<String> failure = new AtomicReference<>();
        MicrophoneForwarder.PcmSource tone = new MicrophoneForwarder.PcmSource() {
            private long position; private volatile boolean closed;
            public void start() { }
            public int read(byte[] data) throws Exception {
                if (closed) return -1;
                for (int i = 0; i < data.length; i += 2) {
                    short value = (short)(Math.sin(2 * Math.PI * 1000 * position++ / 48000) * 10000);
                    data[i] = (byte)value; data[i + 1] = (byte)(value >> 8);
                }
                return data.length; // Receiver/ADB backpressure, as for a file-backed PCM source.
            }
            public void close() { closed = true; }
        };
        MicrophoneForwarder forwarding = new MicrophoneForwarder(context, runtime, "org.scrcpy.android.test", new MicrophoneForwarder.Listener() {
            public void started(MicrophoneForwarder current) { }
            public void stopped(MicrophoneForwarder current, String error) { if (error != null) failure.set(error); }
        }, tone);
        String phase = Long.toHexString(System.nanoTime());
        try {
            until(() -> remote.session != null && remote.session.renderedFrames() >= 5, 30, "Video did not start");
            forwarding.start();
            until(() -> { if (failure.get() != null) throw new AssertionError(failure.get()); return forwarding.isActive() && forwarding.sentBytes >= 3840; }, 25, "Input relay did not start");
            runtime.shell("am force-stop org.scrcpy.android.test; am start -n org.scrcpy.android.test/org.scrcpy.android.MicrophoneProbeActivity --ez expectTone true --es phase " + phase);
            until(() -> {
                String log = runtime.shell("logcat -d -s MicInputProbe:I '*:S'");
                return log.matches("(?s).*MIC_INPUT phase=" + phase + " source=1 route=25[^\\n]*tone=PASS.*")
                        && log.matches("(?s).*MIC_INPUT phase=" + phase + " source=7 route=25[^\\n]*tone=PASS.*");
            }, 20, "Other app did not record the forwarded 1 kHz tone with both input sources");
            DebugLog.write("TEST microphone cross-UID MIC/VOICE_COMMUNICATION PASS bytes=" + forwarding.sentBytes);
            forwarding.close(); Thread.sleep(1200);
            String stopped = phase + "stop";
            runtime.shell("am force-stop org.scrcpy.android.test; am start -n org.scrcpy.android.test/org.scrcpy.android.MicrophoneProbeActivity --es phase " + stopped);
            until(() -> {
                String log = runtime.shell("logcat -d -s MicInputProbe:I '*:S'");
                return log.matches("(?s).*MIC_INPUT phase=" + stopped + " source=1 route=(?!25\\b)\\d+[^\\n]*tone=NO_TONE.*");
            }, 15, "Stopping relay did not restore physical microphone routing");
            DebugLog.write("TEST microphone route release PASS");
            verifyStall(context, runtime);
            return "PASS microphone PCM over ADB, cross-UID MIC/VOICE_COMMUNICATION, route restore, capture stall stop, concurrent scrcpy video";
        } finally {
            forwarding.close(); test.runOnMainSync(remote::finish);
            try { runtime.shell("am force-stop org.scrcpy.android.test"); } catch (Exception ignored) { }
        }
    }
    private static void verifyStall(Context context, RuntimeState runtime) throws Exception {
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> stopped = new AtomicReference<>();
        MicrophoneForwarder stalled = new MicrophoneForwarder(context, runtime, "org.scrcpy.android.test", new MicrophoneForwarder.Listener() {
            public void started(MicrophoneForwarder current) { }
            public void stopped(MicrophoneForwarder current, String error) { stopped.set(error); }
        }, new MicrophoneForwarder.PcmSource() {
            public void start() { }
            public int read(byte[] buffer) throws Exception { closed.await(); return -1; }
            public void close() { closed.countDown(); }
        });
        try {
            stalled.start(); until(() -> stopped.get() != null, 20, "Stalled capture was not stopped");
            if (!stalled.isClosed() || closed.getCount() != 0 || !stopped.get().contains("超时")) throw new AssertionError("Capture stall did not release the microphone");
            DebugLog.write("TEST microphone stalled capture auto-stop PASS");
        } finally { stalled.close(); }
    }
}
