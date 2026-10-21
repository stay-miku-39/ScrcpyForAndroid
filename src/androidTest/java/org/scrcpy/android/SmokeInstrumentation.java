package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in, explicitly addressed two-emulator integration test; no production test backdoor. */
public class SmokeInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { this.args = args; start(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    interface Condition { boolean done() throws Exception; }
    private static void until(Condition condition, int seconds, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) { if (condition.done()) return; Thread.sleep(100); }
        throw new AssertionError(message);
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); RuntimeState runtime = null; RemoteActivity remote = null; String autoRotate = null;
        Map<String, ?> streamBefore = null;
        try {
            Context context = getTargetContext(); runtime = RuntimeState.get(context);
            if ("colorPicker".equals(args.getString("featureCase"))) { result.putString("result", ColorPickerSmoke.run(this,context)); return; }
            if ("draft".equals(args.getString("featureCase"))) {
                result.putString("result", SettingsDraftSmoke.run(this, context)); return;
            }
            if ("logging".equals(args.getString("featureCase"))) { result.putString("result", LoggingSmoke.run(this, context)); return; }
            if ("codecDiscovery".equals(args.getString("featureCase"))) { result.putString("result", CodecDiscoverySmoke.run(this, context)); return; }
            if ("true".equals(args.getString("defaultMedia"))) {
                streamBefore = StreamPreferences.get(context).getAll(); StreamPreferences.save(context, StreamSettings.DEFAULTS);
            }
            Identity again = new Identity(context);
            check(Arrays.equals(again.certificate.getPublicKey().getEncoded(), new Identity(context).certificate.getPublicKey().getEncoded()), "Identity must survive reload");
            String host = args.getString("host", "10.0.2.2"); int port = Integer.parseInt(args.getString("port", "15558"));
            if (args.containsKey("mediaCase")) {
                result.putString("result", StreamSettingsSmoke.run(this, context, runtime, host, port, args.getString("mediaCase"))); return;
            }
            if ("true".equals(args.getString("multiDevice"))) {
                if (!args.containsKey("secondHost") || !args.containsKey("secondPort")) throw new IllegalArgumentException("secondHost/secondPort required");
                result.putString("result", MultiDeviceSmoke.run(this, context, runtime, host, port, args.getString("secondHost"), Integer.parseInt(args.getString("secondPort")))); return;
            }
            if (args.containsKey("uiStage")) {
                result.putString("result", UiControlsSmoke.run(this, context, runtime, host, port, args.getString("uiStage"))); return;
            }
            if ("true".equals(args.getString("microphoneOnly"))) {
                result.putString("result", MicrophoneSmoke.run(this, context, runtime, host, port)); return;
            }
            if ("true".equals(args.getString("mainUiOnly"))) {
                MainActivity main = (MainActivity)startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                if (args.containsKey("pairPort")) {
                    runOnMainSync(() -> {
                        main.ui.select("pair");
                        main.host.setText(host); main.pairPort.setText(args.getString("pairPort")); main.code.setText(args.getString("pin"));
                        findButton(main.getWindow().getDecorView(), "使用配对码配对").performClick();
                    });
                    until(() -> {
                        final String[] text = {""}; runOnMainSync(() -> text[0] = main.status.getText().toString());
                        if (text[0].startsWith("操作失败")) throw new AssertionError(text[0]);
                        return text[0].startsWith("配对成功");
                    }, 30, "Main UI did not pair");
                }
                runOnMainSync(() -> {
                    main.ui.select("connect");
                    main.host.setText(host); main.port.setText(String.valueOf(port));
                    View button = findButton(main.getWindow().getDecorView(), "连接 / 重连");
                    check(button != null, "Connect button absent"); button.performClick();
                });
                until(() -> {
                    final String[] text = {""}; runOnMainSync(() -> text[0] = main.status.getText().toString());
                    if (text[0].startsWith("操作失败")) throw new AssertionError(text[0]);
                    return text[0].startsWith("ADB 已连接");
                }, 40, "Main UI did not connect");
                check(context.getSharedPreferences("devices", Context.MODE_PRIVATE).getString("history", "").contains(host), "Device history not saved");
                check(host.equals(context.getSharedPreferences("devices", Context.MODE_PRIVATE).getString("host", "")), "Last host not saved");
                check(String.valueOf(port).equals(context.getSharedPreferences("devices", Context.MODE_PRIVATE).getString("port", "")), "Last port not saved");
                String credential = runtime.adb.fingerprint();
                runOnMainSync(() -> {
                    main.ui.select("devices"); check("devices".equals(main.ui.page()), "Device page missing");
                    findButton(main.getWindow().getDecorView(), "查看本机凭据与指纹").performClick();
                    check("credential".equals(main.ui.page()), "Credential detail missing");
                    main.getOnBackPressedDispatcher().onBackPressed(); check("devices".equals(main.ui.page()), "Credential back navigation failed");
                    main.ui.select("tools"); findButton(main.getWindow().getDecorView(), "打开端口扫描").performClick();
                    check("scan".equals(main.ui.page()), "Scan subpage missing");
                    main.getOnBackPressedDispatcher().onBackPressed(); check("tools".equals(main.ui.page()), "Subpage back navigation failed");
                    main.ui.select("connect");
                });
                check(credential.equals(runtime.adb.fingerprint()), "UI navigation changed identity");
                DebugLog.write("TEST main UI connection and saved history PASS");
                result.putString("result", "PASS main UI connection and saved history"); return;
            }
            if (args.containsKey("pairPort")) {
                if ("true".equals(args.getString("expectPairFailure"))) {
                    boolean rejected = false;
                    try { rejected = !runtime.adb.pair(host, Integer.parseInt(args.getString("pairPort")), args.getString("pin")); }
                    catch (Exception expected) { rejected = true; }
                    check(rejected, "Incorrect pairing code was accepted");
                    DebugLog.write("TEST incorrect pairing code rejected PASS"); result.putString("result", "PASS incorrect pairing code rejected"); return;
                }
                check(runtime.adb.pair(host, Integer.parseInt(args.getString("pairPort")), args.getString("pin")), "Pairing failed");
                DebugLog.write("TEST pairing PASS");
            }
            check(runtime.connect(host, port) != null, "ADB connect timed out");
            runtime.shell("logcat -c");
            check(runtime.shell("echo scrcpy-android-smoke").contains("scrcpy-android-smoke"), "Shell mismatch");
            DebugLog.write("TEST shell and persistent identity PASS");
            byte[] bytes = new byte[262151]; new Random(12345).nextBytes(bytes);
            AdbSync.push(runtime.current().adb, new ByteArrayInputStream(bytes), "/sdcard/Download/scrcpy-smoke.bin");
            ByteArrayOutputStream pulled = new ByteArrayOutputStream(); AdbSync.pull(runtime.current().adb, "/sdcard/Download/scrcpy-smoke.bin", pulled);
            check(Arrays.equals(MessageDigest.getInstance("SHA-256").digest(bytes), MessageDigest.getInstance("SHA-256").digest(pulled.toByteArray())), "File SHA-256 mismatch");
            runtime.shell("rm /sdcard/Download/scrcpy-smoke.bin"); DebugLog.write("TEST bidirectional file SHA-256 PASS");
            CountDownLatch scanned = new CountDownLatch(1); List<Integer> ports = new ArrayList<>();
            PortScanner scanner = new PortScanner(); scanner.start(host, port, port, new PortScanner.Listener() {
                public void progress(int done, int total) { }
                public void finished(List<Integer> found, boolean cancelled) { ports.addAll(found); scanned.countDown(); }
            });
            check(scanned.await(10, TimeUnit.SECONDS) && ports.contains(port), "Scanner missed ADB port"); DebugLog.write("TEST scan PASS");
            if ("true".equals(args.getString("connectOnly"))) { result.putString("result", "PASS connect, identity, shell, files, scan"); return; }
            autoRotate = runtime.shell("settings get system accelerometer_rotation").trim();
            runtime.shell("settings put system accelerometer_rotation 0; wm user-rotation lock 0");
            runtime.shell("am force-stop org.scrcpy.android.test; am start -n org.scrcpy.android.test/org.scrcpy.android.TargetProbeActivity");
            Intent intent = new Intent(context, RemoteActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("audio", true);
            remote = (RemoteActivity)startActivitySync(intent);
            RemoteActivity activity = remote;
            until(() -> activity.session != null && activity.session.renderedFrames() >= 10, 30, "No rendered video frames");
            until(() -> activity.session.audioBytes > 8192, 15, "No audio playback bytes");
            until(() -> activity.session.nonSilentAudioBytes > 4096, 15, "Audio is entirely silent");
            DebugLog.write("TEST video and audio PASS frames=" + activity.session.renderedFrames() + " audioBytes=" + activity.session.audioBytes);
            if ("true".equals(args.getString("windowUi"))) {
                RuntimeState state = runtime;
                android.media.AudioManager audio = (android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                int hostVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                until(activity::hasWindowFocus, 10, "Fullscreen window never received input focus");
                DebugLog.write("TEST key input focus=" + activity.hasWindowFocus());
                long keyTime = SystemClock.uptimeMillis();
                sendKeySync(new KeyEvent(keyTime, keyTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0));
                sendKeySync(new KeyEvent(keyTime, keyTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 1));
                sendKeySync(new KeyEvent(keyTime, keyTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0));
                sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN));
                sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN));
                runOnMainSync(() -> activity.ball.performClick());
                until(() -> {
                    String log = state.shell("logcat -d -s ScrcpyProbe:I '*:S'");
                    return log.contains("key action=0 code=24 repeat=1") && log.contains("key action=1 code=24") && log.contains("key action=0 code=25");
                }, 10, "Volume down/up/repeat not delivered");
                check(hostVolume == audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC), "Host volume was changed by fullscreen forwarding");
                // Close the menu through the same ball action before continuing normal touch tests.
                runOnMainSync(() -> activity.ball.performClick());
                DebugLog.write("TEST physical volume down/up/repeat PASS");
            }
            // Dispatch actual Android multi-pointer events into the mirror view.
            runOnMainSync(() -> {
                long now = SystemClock.uptimeMillis(); float x = activity.video.getWidth() / 2f, y = activity.video.getHeight() / 2f;
                MotionEvent.PointerProperties first = new MotionEvent.PointerProperties(); first.id = 3; first.toolType = MotionEvent.TOOL_TYPE_FINGER;
                MotionEvent.PointerProperties second = new MotionEvent.PointerProperties(); second.id = 7; second.toolType = MotionEvent.TOOL_TYPE_FINGER;
                MotionEvent.PointerCoords a = new MotionEvent.PointerCoords(); a.x = x - 40; a.y = y; a.pressure = 1;
                MotionEvent.PointerCoords b = new MotionEvent.PointerCoords(); b.x = x + 40; b.y = y; b.pressure = 1;
                dispatch(activity, now, MotionEvent.ACTION_DOWN, new MotionEvent.PointerProperties[]{first}, new MotionEvent.PointerCoords[]{a});
                dispatch(activity, now, MotionEvent.ACTION_POINTER_DOWN | (1 << 8), new MotionEvent.PointerProperties[]{first, second}, new MotionEvent.PointerCoords[]{a,b});
                a.x -= 80; b.x += 80;
                dispatch(activity, now, MotionEvent.ACTION_MOVE, new MotionEvent.PointerProperties[]{first, second}, new MotionEvent.PointerCoords[]{a,b});
                dispatch(activity, now, MotionEvent.ACTION_POINTER_UP | (1 << 8), new MotionEvent.PointerProperties[]{first, second}, new MotionEvent.PointerCoords[]{a,b});
                dispatch(activity, now, MotionEvent.ACTION_UP, new MotionEvent.PointerProperties[]{first}, new MotionEvent.PointerCoords[]{a});
            });
            RuntimeState state = runtime;
            until(() -> state.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("touch action=5 pointers=2"), 10, "Target did not receive two pointers");
            DebugLog.write("TEST actual two-pointer injection PASS");
            until(() -> {
                final boolean[] match = {false}; runOnMainSync(() -> {
                    ClipboardManager manager = (ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = manager.getPrimaryClip(); match[0] = clip != null && "remote-clipboard-安卓".contentEquals(clip.getItemAt(0).getText());
                }); return match[0];
            }, 10, "Remote clipboard did not sync to host");
            // Let the explicit get plus autosync deliveries finish before changing direction.
            // Both emulators can also mirror the host clipboard asynchronously.
            Thread.sleep(2000);
            until(activity::hasWindowFocus, 10, "Clipboard source activity lost foreground focus");
            runOnMainSync(() -> ((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("smoke", "host-clipboard-安卓")));
            until(() -> state.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("clipboard=host-clipboard-安卓"), 10, "Host clipboard did not sync to target");
            DebugLog.write("TEST bidirectional text clipboard PASS");
            int oldWidth = activity.session.width;
            activity.session.send(new byte[]{11});
            until(() -> activity.session.width != oldWidth && activity.session.currentRenderedFrames() >= 5, 45, "Rotation did not render new frames");
            DebugLog.write("TEST rotation PASS");
            if ("true".equals(args.getString("windowUi"))) {
                check(android.provider.Settings.canDrawOverlays(context), "Grant overlay permission to this test emulator first");
                ScrcpySession original = activity.session; long before = original.renderedFrames(), beforeAudio = original.audioBytes;
                runtime.shell("logcat -c");
                sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));
                runOnMainSync(activity::requestFloatingWindow);
                until(() -> FloatingWindowService.isShowing() && activity.isFinishing() && original.renderedFrames() > before + 5 && original.audioBytes > beforeAudio + 8192, 20, "Floating output did not continue");
                check(runtime.current().session == original && !original.isClosed(), "Switching window restarted session");
                until(() -> state.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("key action=1 code=24"), 10, "Held volume key not released during handoff");
                FloatingWindow floating = FloatingWindowService.current();
                runtime.shell("logcat -c");
                int localVolume = ((android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)); sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN));
                check(!runtime.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("key action=0 code=25"), "Floating mode forwarded a local hardware key");
                ((android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(android.media.AudioManager.STREAM_MUSIC, localVolume, 0);
                runOnMainSync(() -> {
                    View view = floating.display.video; long now = SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, view.getWidth()/2f, view.getHeight()/2f, 0);
                    view.dispatchTouchEvent(down); down.recycle();
                    MotionEvent up = MotionEvent.obtain(now, now+50, MotionEvent.ACTION_UP, view.getWidth()/2f, view.getHeight()/2f, 0);
                    view.dispatchTouchEvent(up); up.recycle();
                });
                until(() -> state.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("touch action=1 pointers=1"), 10, "Floating touch not received by target");
                long floatingFrames = original.renderedFrames(), floatingAudio = original.audioBytes;
                RemoteActivity restored = (RemoteActivity)startActivitySync(new Intent(context, RemoteActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("audio", true));
                remote = restored;
                until(() -> restored.session == original && !FloatingWindowService.isShowing() && original.renderedFrames() > floatingFrames + 5 && original.audioBytes > floatingAudio + 8192, 20, "Fullscreen restore lost session");
                check(original.audioBytes > 8192, "Audio lost after window transfer");
                DebugLog.write("TEST floating touch and roundtrip without reconnect PASS");
            }
            activity.session.key(KeyEvent.KEYCODE_HOME);
            until(() -> !state.shell("dumpsys activity activities | grep mResumedActivity").contains("TargetProbeActivity"), 10, "Home key not injected");
            DebugLog.write("TEST remote HOME key PASS");
            if ("true".equals(args.getString("windowUi"))) {
                RemoteActivity restored = remote;
                runOnMainSync(restored::requestFloatingWindow);
                until(() -> FloatingWindowService.isShowing() && restored.isFinishing(), 15, "Second window did not open");
                runOnMainSync(() -> context.stopService(new Intent(context, FloatingWindowService.class)));
                until(() -> !FloatingWindowService.isShowing() && state.current().session == null, 15, "Closing window left a live mirror");
                check(runtime.current().adb.isConnected(), "Closing window disconnected ADB");
                DebugLog.write("TEST floating close preserves ADB PASS");
            }
            result.putString("result", "PASS identity, shell, files, scan, video, audio, multi-touch, clipboard, rotation, HOME");
        } catch (Throwable e) {
            DebugLog.error("TEST FAIL", e); result.putString("result", "FAIL " + e);
        } finally {
            runOnMainSync(() -> getTargetContext().stopService(new Intent(getTargetContext(), FloatingWindowService.class)));
            if (remote != null) { RemoteActivity activity = remote; runOnMainSync(activity::finish); }
            if (runtime != null) {
                if (runtime.current() != null && runtime.current().session != null) runtime.current().session.close();
                try { if (autoRotate != null) runtime.shell("wm user-rotation lock 0; settings put system accelerometer_rotation " + ("1".equals(autoRotate) ? "1" : "0")); runtime.disconnect(runtime.current()); }
                catch (Exception ignored) { }
            }
            if (streamBefore != null) StreamSettingsSmoke.restore(StreamPreferences.get(getTargetContext()), streamBefore);
            finish(result.getString("result", "FAIL").startsWith("PASS") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }
    private static void dispatch(RemoteActivity activity, long downTime, int action, MotionEvent.PointerProperties[] properties, MotionEvent.PointerCoords[] coords) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, properties.length, properties, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        activity.video.dispatchTouchEvent(event); event.recycle();
        android.os.SystemClock.sleep(40); // Distinct event times, as on a real touch digitizer.
    }
    private static View findButton(View view, String text) {
        if (view instanceof android.widget.Button && text.contentEquals(((android.widget.Button)view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) { View found = findButton(group.getChildAt(i), text); if (found != null) return found; }
        }
        return null;
    }
}
