package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import static org.scrcpy.android.UiControlsSmoke.check;
import static org.scrcpy.android.UiControlsSmoke.until;

/** Only the instrumentation APK exposes these checks. Restores target and saved settings. */
final class StreamSettingsSmoke {
    private final Instrumentation test;
    private final Context context;
    private final RuntimeState state;
    private final DeviceConnection device;
    private RemoteActivity activity;
    private StreamSettingsSmoke(Instrumentation test, Context context, RuntimeState state, DeviceConnection device) { this.test = test; this.context = context; this.state = state; this.device = device; }
    static String run(Instrumentation test, Context context, RuntimeState state, String host, int port, String stage) throws Exception {
        DeviceConnection device = state.connect(host, port); check(device != null, "ADB connection failed");
        Map<String, ?> saved = StreamPreferences.get(context).getAll(), ui = UiPreferences.get(context).getAll();
        String original = device.shell("wm size"), identity = device.adb.fingerprint();
        StreamSettingsSmoke smoke = new StreamSettingsSmoke(test, context, state, device);
        try {
            UiPreferences.get(context).edit().putBoolean("backgroundKeepAlive", true).putBoolean("backgroundFloating", false).apply();
            if ("main".equals(stage)) smoke.main();
            else if ("virtual".equals(stage)) smoke.virtual();
            else if ("codecs".equals(stage)) smoke.codecs();
            else if ("fallback".equals(stage)) smoke.fallback();
            else if ("presets".equals(stage)) smoke.presets();
            else throw new IllegalArgumentException("Unknown mediaCase: " + stage);
            check(identity.equals(device.adb.fingerprint()), "Media settings changed ADB identity");
            check(original.equals(device.shell("wm size")), "Target size was not restored");
            return "PASS media " + stage + ", persistent identity, target size restored";
        } finally {
            smoke.close(); restore(StreamPreferences.get(context), saved); restore(UiPreferences.get(context), ui);
            device.shell("am force-stop org.scrcpy.android.test");
        }
    }
    static void restore(SharedPreferences prefs, Map<String, ?> saved) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) edit.putString(key, (String)value); else if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) edit.putInt(key, (Integer)value); else if (value instanceof Float) edit.putFloat(key, (Float)value); else if (value instanceof Long) edit.putLong(key, (Long)value);
        }
        edit.commit();
    }
    private void configure(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>(StreamSettings.DEFAULTS);
        values.put("resolution", "custom"); values.put("customWidth", "360"); values.put("customHeight", "640"); values.put("maxSize", "640"); values.put("fps", "30");
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i+1]); StreamPreferences.save(context, values);
    }
    private ScrcpySession start(boolean audio) throws Exception {
        device.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n org.scrcpy.android.test/org.scrcpy.android.TargetProbeActivity");
        activity = (RemoteActivity)test.startActivitySync(RemoteActivity.intent(context, device.target, audio));
        until(() -> activity.session != null && activity.session.isStarted() && activity.session.currentRenderedFrames() >= 3, 65, "Session did not start");
        return activity.session;
    }
    private void close() throws Exception {
        ScrcpySession current = device.session;
        RemoteActivity closing = activity; activity = null;
        test.runOnMainSync(() -> { FloatingWindowService.closeRemote(context, device.target); if (closing != null && !closing.isFinishing()) closing.finish(); });
        if (current != null) { current.close(); current.awaitCleanup(); }
        test.waitForIdleSync();
    }
    private void fit(RemoteDisplay display, ScrcpySession session) throws Exception {
        until(() -> {
            final boolean[] ok = {false}; test.runOnMainSync(() -> {
                float scale = Math.min(display.getWidth() / (float)session.width, display.getHeight() / (float)session.height);
                ok[0] = Math.abs(display.video.getWidth() - Math.round(session.width * scale)) <= 2
                        && Math.abs(display.video.getHeight() - Math.round(session.height * scale)) <= 2 && scale > 0;
            }); return ok[0];
        }, 10, "Video not fitted maximally without cropping/stretching");
    }
    private void touch(RemoteDisplay display) throws Exception {
        device.shell("logcat -c");
        test.runOnMainSync(() -> {
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(now, now + action * 60, action, display.video.getWidth() / 2f, display.video.getHeight() / 2f, 0);
                display.video.dispatchTouchEvent(event); event.recycle();
            }
        });
        until(() -> device.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("touch action=1"), 10, "Touch did not reach selected display");
    }
    private void capture(String name) throws Exception {
        test.waitForIdleSync(); Thread.sleep(2800); Bitmap bitmap = test.getUiAutomation().takeScreenshot(); check(bitmap != null, "Screenshot unavailable");
        File directory = context.getExternalFilesDir("media-settings-20261020");
        try (OutputStream out = new FileOutputStream(new File(directory, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
    }
    private void main() throws Exception {
        configure(); ScrcpySession original = start(true); check("main".equals(original.activeBackend), "Main backend not selected");
        check(device.shell("wm size").contains("Override size: 360x640"), "Custom size not applied");
        until(() -> original.nonSilentAudioBytes > 8192, 15, "PCM audio missing"); fit(activity.display, original); touch(activity.display); capture("main-fullscreen");
        MainActivity main = (MainActivity)test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.runOnMainSync(() -> {
            main.ui.select("settings"); View root = main.getWindow().getDecorView();
            ((EditText)root.findViewWithTag("stream_customWidth")).setText("400"); ((EditText)root.findViewWithTag("stream_customHeight")).setText("800");
            root.findViewWithTag("saveStreamSettings").performClick();
            check("400".equals(StreamPreferences.get(context).getString("customWidth", "")), "Settings UI did not save");
        }); capture("settings");
        test.runOnMainSync(() -> {
            View root = main.getWindow().getDecorView(); View save = root.findViewWithTag("saveStreamSettings");
            save.requestRectangleOnScreen(new android.graphics.Rect(0,0,save.getWidth(),save.getHeight()), true);
        }); capture("settings-media");
        check(original == device.session && original.settings.values.get("customWidth").equals("360"), "Live settings snapshot changed");
        check(device.shell("wm size").contains("Override size: 360x640"), "Saving settings changed live remote size");
        test.runOnMainSync(() -> context.startActivity(RemoteActivity.intent(context, device.target, true)));
        until(() -> activity.hasWindowFocus() && activity.display.ownsSession() && activity.session == original, 10, "Settings navigation lost session");
        test.runOnMainSync(activity::requestFloatingWindow); until(() -> FloatingWindowService.isShowing(device.target), 15, "Floating window missing");
        FloatingWindow window = FloatingWindowService.current(); fit(window.display, original); touch(window.display); capture("main-floating");
        activity = (RemoteActivity)test.startActivitySync(RemoteActivity.intent(context, device.target, true));
        until(() -> activity.session == original && activity.display.ownsSession(), 15, "Window handoff restarted session");
        close();
        ScrcpySession reopened = start(true); check(reopened != original, "Reopen reused closed session");
        check(device.shell("wm size").contains("Override size: 400x800"), "Saved settings not applied on reopen"); fit(activity.display, reopened); close();
        DebugLog.write("TEST media main size/UI snapshot/fullscreen/window/reopen PASS");
    }
    private void virtual() throws Exception {
        String initial = device.shell("wm size");
        configure("backend", "virtual", "virtualApp", "org.scrcpy.android.test");
        ScrcpySession original = start(true);
        check("virtual".equals(original.activeBackend) && original.remoteDisplayId > 0, "Independent virtual display not selected");
        check(initial.equals(device.shell("wm size")), "Virtual display changed physical screen dimensions");
        check(original.width == 360 && original.height == 640, "Virtual size mismatch");
        until(() -> original.nonSilentAudioBytes > 8192, 15, "Virtual audio missing"); fit(activity.display, original); touch(activity.display); capture("virtual-fullscreen");
        test.runOnMainSync(activity::requestFloatingWindow); until(() -> FloatingWindowService.isShowing(device.target), 15, "Virtual floating window missing");
        FloatingWindow window = FloatingWindowService.current(); fit(window.display, original); touch(window.display); capture("virtual-floating");
        activity = (RemoteActivity)test.startActivitySync(RemoteActivity.intent(context, device.target, true));
        until(() -> activity.session == original && activity.display.ownsSession(), 15, "Virtual handoff restarted server"); close();
        until(() -> !device.shell("cmd display get-displays").contains("Display id " + original.remoteDisplayId + ":"), 10, "Virtual display leaked");
        configure("resolution", "original", "backend", "virtual", "virtualApp", "org.scrcpy.android.test");
        ScrcpySession nativeSize = start(false); DisplaySizeState size = DisplaySizeState.parse(initial);
        check("virtual".equals(nativeSize.activeBackend) && nativeSize.displayWidth == size.width && nativeSize.displayHeight == size.height, "Original size ignored preferred virtual backend"); close();
        DebugLog.write("TEST media virtual display/audio/touch/handoff/original preference PASS");
    }
    private void codecs() throws Exception {
        for (String audio : new String[]{"aac", "opus", "flac"}) {
            configure("audioCodec", audio); ScrcpySession session = start(true);
            check(audio.equals(session.effectiveAudio), "Audio unexpectedly fell back: " + audio);
            until(() -> session.nonSilentAudioBytes > 8192, 15, "Decoded audio silent: " + audio); close();
            DebugLog.write("TEST audio codec=" + audio + " PASS");
        }
        for (String video : new String[]{"h265", "av1", "vp8", "vp9"}) {
            configure("videoCodec", video); ScrcpySession session = start(false);
            DebugLog.write("TEST video requested=" + video + " effective=" + session.effectiveVideo + " frames=" + session.renderedFrames());
            check(!session.isClosed(), "Video codec session closed"); close();
        }
        configure("videoEncoder", "c2.android.avc.encoder", "videoDecoder", "c2.android.avc.decoder", "audioCodec", "aac", "audioEncoder", "c2.android.aac.encoder", "audioDecoder", "c2.android.aac.decoder");
        ScrcpySession named = start(true); check("h264".equals(named.effectiveVideo) && "aac".equals(named.effectiveAudio), "Named components failed"); close();
    }
    private void fallback() throws Exception {
        configure("backend", "virtual", "virtualApp", "org.scrcpy.missing.application");
        ScrcpySession route = start(false); check("main".equals(route.activeBackend), "Virtual startup failure did not fall back to main"); close();
        configure("videoEncoder", "not.a.real.encoder", "audioCodec", "opus", "audioDecoder", "not.a.real.decoder");
        ScrcpySession media = start(true); check("h264".equals(media.effectiveVideo) && "raw".equals(media.effectiveAudio), "Unavailable codecs did not use safe defaults");
        until(() -> media.nonSilentAudioBytes > 8192, 15, "Fallback PCM silent"); close();
        DebugLog.write("TEST media fallback unavailable display app/components PASS");
    }
    private void presets() throws Exception {
        for (String[] preset : new String[][]{{"original","main","0"},{"local","main","640"},{"local720","virtual","0"}}) {
            configure("resolution",preset[0],"backend",preset[1],"maxSize",preset[2],"virtualApp","org.scrcpy.android.test","fps","15");
            String initial = device.shell("wm size"); ScrcpySession session = start(false);
            DisplaySizeState before = DisplaySizeState.parse(initial); int[] expected = session.settings.displaySize(before.width,before.height);
            check(session.displayWidth == expected[0] && session.displayHeight == expected[1], "Preset logical dimensions mismatch");
            check(preset[1].equals(session.activeBackend), "Preset unexpectedly fell back");
            if ("0".equals(preset[2])) check(Math.abs(session.width - expected[0]) <= 2 && Math.abs(session.height - expected[1]) <= 2, "Unlimited encoding unexpectedly scaled");
            else check(Math.max(session.width, session.height) <= 640, "Video cap ignored");
            fit(activity.display,session); DebugLog.write("TEST preset=" + preset[0] + " logical=" + session.displayWidth + "x" + session.displayHeight + " video=" + session.width + "x" + session.height + " PASS"); close();
        }
    }
}
