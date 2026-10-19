package org.scrcpy.android;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.Button;

/** Explicit stage-by-stage checks; only runs against the supplied test target. */
final class UiControlsSmoke {
    private static final String WINDOW_NAME = "我的远程平板 Android 16 — 一段用于验证不同窗口宽度下标题省略行为的很长设备名称";
    interface Check { boolean done() throws Exception; }
    static void until(Check condition, int seconds, String failure) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (SystemClock.elapsedRealtime() < deadline) { if (condition.done()) return; Thread.sleep(120); }
        throw new AssertionError(failure);
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void capture(Instrumentation test, Context context, String name) throws Exception {
        test.waitForIdleSync(); Thread.sleep(350); // Include the completed popup/window animation.
        android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot();
        check(image != null, "UI screenshot unavailable");
        java.io.File folder = context.getExternalFilesDir("ui-smoke");
        try (java.io.OutputStream out = new java.io.FileOutputStream(new java.io.File(folder, name + ".png"))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
    }
    static View find(View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView)view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) { View match = find(group.getChildAt(i), text); if (match != null) return match; }
        }
        return null;
    }
    static void powerItem(Instrumentation test, RemoteActivity activity, String text) throws Exception {
        test.runOnMainSync(() -> {
            activity.showPowerMenu();
            // PopupWindow content belongs to a separate Window; use the real menu view.
            View item = find(activity.menuContent(), text); check(item != null, "Missing power action: " + text); item.performClick();
        });
    }
    static boolean awake(RuntimeState state) throws Exception { return state.shell("dumpsys power").contains("mWakefulness=Awake"); }
    private static void putDeviceName(RuntimeState state, String name) throws Exception {
        // Keep the test-only OPEN service string ASCII; the device decodes the
        // UTF-8 fixture without depending on arbitrary shell-command input paths.
        String encoded = android.util.Base64.encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        state.shell("settings put global device_name \"$(echo " + encoded + " | base64 -d)\"");
    }
    private static boolean powerMenuVisible(RuntimeState state) throws Exception {
        java.util.regex.Matcher windows = java.util.regex.Pattern.compile("(?ms)^  Window #\\d+ Window\\{[^\\n]*GlobalActions[^\\n]*\\}:\\r?\\n(.*?)(?=^  Window #|\\z)")
                .matcher(state.shell("dumpsys window windows"));
        while (windows.find()) if (windows.group(1).contains("isVisible=true")) return true;
        return false;
    }
    static void prepareTarget(RuntimeState state) throws Exception {
        state.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n org.scrcpy.android.test/org.scrcpy.android.TargetProbeActivity");
    }
    static String run(Instrumentation test, Context context, RuntimeState state, String host, int port, String stage) throws Exception {
        check(state.adb.connect(host, port), "ADB connect failed"); state.target = host + ":" + port;
        prepareTarget(state);
        if ("settings".equals(stage)) return settings(test, context, state);
        SharedPreferences appearance = UiPreferences.get(context);
        String[] windowKeys = {"windowX", "windowY", "windowWidth", UiPreferences.WINDOW};
        int[] windowValues = new int[windowKeys.length]; boolean[] windowHad = new boolean[windowKeys.length];
        for (int i = 0; i < windowKeys.length; i++) { windowHad[i] = appearance.contains(windowKeys[i]); windowValues[i] = appearance.getInt(windowKeys[i], 0); }
        String oldName = null;
        if ("window".equals(stage)) {
            appearance.edit().putInt("windowWidth", MainUi.dp(context, 220)).putInt(UiPreferences.WINDOW, 100).apply();
            oldName = state.shell("settings get global device_name").trim();
            putDeviceName(state, WINDOW_NAME);
        }
        RemoteActivity remote = (RemoteActivity)test.startActivitySync(new Intent(context, RemoteActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("audio", false));
        String longPress = null;
        try {
            until(() -> remote.session != null && remote.session.renderedFrames() >= 3, 30, "Video did not start");
            if ("window".equals(stage)) return window(test, context, state, remote);
            if ("rotation".equals(stage)) return rotation(test, context, state, remote);
            if (!"power".equals(stage)) throw new IllegalArgumentException("Unknown UI stage: " + stage);
            longPress = state.shell("settings get global power_button_long_press").trim();
            state.shell("settings put global power_button_long_press 1");
            powerItem(test, remote, "点按电源"); until(() -> !awake(state), 12, "Remote power tap did not put screen to sleep");
            powerItem(test, remote, "唤醒屏幕"); until(() -> awake(state), 12, "Remote wake action failed");
            state.shell("wm dismiss-keyguard");
            powerItem(test, remote, "长按电源");
            until(() -> powerMenuVisible(state) && !remote.powerKeys.isHolding(), 12, "Long power did not open target system power menu");
            Thread.sleep(400); // System dialog entry animation and queued POWER up must finish.
            test.runOnMainSync(() -> remote.session.key(KeyEvent.KEYCODE_BACK));
            until(() -> !powerMenuVisible(state), 5, "Full power menu did not close");
            String screenshots = state.shell("ls /sdcard/Pictures/Screenshots");
            powerItem(test, remote, "电源 + 音量−");
            until(() -> !screenshots.equals(state.shell("ls /sdcard/Pictures/Screenshots")), 15, "Power/volume chord did not create screenshot");
            until(() -> !remote.powerKeys.isHolding(), 5, "Chord keys not released");
            // Screenshot preview/GlobalActions are system UI. Finish that
            // interaction before testing the overlay's independent power gesture.
            Thread.sleep(800); state.shell("input keyevent KEYCODE_BACK");
            until(() -> !powerMenuVisible(state), 5, "Screenshot system UI did not settle");
            DebugLog.write("TEST fullscreen power tap/wake/long/chord PASS");
            ScrcpySession original = remote.session;
            test.runOnMainSync(remote::requestFloatingWindow);
            until(() -> FloatingWindowService.isShowing() && remote.isFinishing(), 15, "Floating power test did not attach");
            FloatingWindowService floating = FloatingWindowService.current();
            check(state.session == original, "Window handoff restarted session");
            state.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard");
            Thread.sleep(650); // AWAKE precedes completion of the screen-on transition.
            test.runOnMainSync(() -> floating.powerButton.performClick());
            until(() -> !awake(state), 12, "Floating power tap did not sleep");
            test.runOnMainSync(() -> floating.powerButton.performClick());
            until(() -> awake(state), 12, "Floating power tap did not wake");
            state.shell("wm dismiss-keyguard");
            // Separate wake and long-hold gestures: an immediate second POWER
            // down can intentionally trigger Android's double-press camera action.
            Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 350L);
            test.runOnMainSync(() -> floating.powerButton.performLongClick());
            until(() -> powerMenuVisible(state), 12, "Floating long power failed");
            until(() -> !floating.powerKeys.isHolding(), 5, "Power keys not automatically released");
            Thread.sleep(400);
            test.runOnMainSync(() -> original.key(KeyEvent.KEYCODE_BACK));
            until(() -> !powerMenuVisible(state), 5, "Floating power menu did not close");
            DebugLog.write("TEST floating power tap/wake/long PASS");
            return "PASS fullscreen power tap/wake/long/chord, floating power tap/wake/long, bounded key release";
        } finally {
            test.runOnMainSync(() -> { context.stopService(new Intent(context, FloatingWindowService.class)); if (!remote.isFinishing()) remote.finish(); });
            if (longPress != null) state.shell("settings " + ("null".equals(longPress) ? "delete global power_button_long_press" : "put global power_button_long_press " + RuntimeState.quote(longPress)));
            SharedPreferences.Editor edit = appearance.edit();
            for (int i = 0; i < windowKeys.length; i++) { if (windowHad[i]) edit.putInt(windowKeys[i], windowValues[i]); else edit.remove(windowKeys[i]); } edit.apply();
            if (oldName != null) { if ("null".equals(oldName)) state.shell("settings delete global device_name"); else putDeviceName(state, oldName); }
            state.shell("input keyevent KEYCODE_WAKEUP; input keyevent KEYCODE_BACK; am force-stop org.scrcpy.android.test");
        }
    }
    private static View description(View view, String text) {
        if (view.getContentDescription() != null && text.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View match = description(((ViewGroup)view).getChildAt(i), text); if (match != null) return match;
        }
        return null;
    }
    private static void openSettings(Instrumentation test, MainActivity main) throws Exception {
        until(main::hasWindowFocus, 8, "Main window has no input focus");
        test.waitForIdleSync(); Thread.sleep(500); // Finish the previous Activity's native rotation/entry animation.
        test.runOnMainSync(() -> description(main.getWindow().getDecorView(), "管理与更多选项").performClick());
        test.waitForIdleSync(); Thread.sleep(250);
        until(() -> {
            android.view.accessibility.AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("设置")) {
                if (node.getText() != null && "设置".contentEquals(node.getText())) {
                    Rect bounds = new Rect(); node.getBoundsInScreen(bounds); long now = SystemClock.uptimeMillis();
                    for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                        MotionEvent tap = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0);
                        tap.setSource(InputDevice.SOURCE_TOUCHSCREEN); test.getUiAutomation().injectInputEvent(tap, true); tap.recycle();
                        if (action == MotionEvent.ACTION_DOWN) Thread.sleep(80);
                    }
                    return true;
                }
            }
            return false;
        }, 8, "Settings not present in main overflow menu");
        until(() -> "settings".equals(main.ui.page()), 5, "Settings menu did not navigate");
    }
    private static String settings(Instrumentation test, Context context, RuntimeState state) throws Exception {
        SharedPreferences prefs = UiPreferences.get(context); String[] keys = {UiPreferences.BALL, UiPreferences.MENU, UiPreferences.WINDOW};
        int[] old = new int[3]; boolean[] had = new boolean[3];
        for (int i = 0; i < keys.length; i++) { had[i] = prefs.contains(keys[i]); old[i] = prefs.getInt(keys[i], 100); }
        MainActivity main = null; RemoteActivity remote = null, restored = null; Instrumentation.ActivityMonitor monitor = null;
        String identity = state.adb.fingerprint(); String history = context.getSharedPreferences("devices", Context.MODE_PRIVATE).getString("history", "");
        try {
            main = (MainActivity)test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity first = main; openSettings(test, first);
            test.runOnMainSync(() -> {
                android.widget.SeekBar ball = first.getWindow().getDecorView().findViewWithTag(UiPreferences.BALL);
                ball.setProgress(0); check(prefs.getInt(UiPreferences.BALL, 0) == 20, "Opacity can hide controls completely");
                ball.setProgress(35);
                ((android.widget.SeekBar)first.getWindow().getDecorView().findViewWithTag(UiPreferences.MENU)).setProgress(55);
                ((android.widget.SeekBar)first.getWindow().getDecorView().findViewWithTag(UiPreferences.WINDOW)).setProgress(65);
            });
            capture(test, context, "settings");
            test.runOnMainSync(() -> { first.getOnBackPressedDispatcher().onBackPressed(); check("connect".equals(first.ui.page()), "Settings back navigation failed"); first.finish(); });
            remote = (RemoteActivity)test.startActivitySync(new Intent(context, RemoteActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("audio", false));
            RemoteActivity full = remote;
            until(() -> full.session != null && full.session.renderedFrames() >= 3, 30, "Settings test video did not start");
            Thread.sleep(900); test.waitForIdleSync(); // Native orientation can finish after the first decoded frames.
            ScrcpySession original = full.session;
            test.runOnMainSync(() -> { check(Math.abs(full.ball.getAlpha() - .35f) < .001f, "Ball opacity not loaded"); full.ball.performClick();
                check(Math.abs(full.menuContent().getAlpha() - .55f) < .001f, "Menu opacity not loaded"); full.ball.performClick();
                full.showPowerMenu(); check(Math.abs(full.menuContent().getAlpha() - .55f) < .001f, "Power menu opacity differs"); });
            capture(test, context, "power-menu");
            test.runOnMainSync(() -> check(full.menuShowing(), "Power popup disappeared before user could operate it"));
            powerItem(test, full, "唤醒屏幕");
            test.runOnMainSync(full::requestFloatingWindow);
            until(() -> FloatingWindowService.isShowing() && full.isFinishing(), 15, "Opacity test window failed");
            FloatingWindowService floating = FloatingWindowService.current();
            check(Math.abs(floating.windowOpacity() - .65f) < .001f, "Window opacity not loaded");
            main = (MainActivity)test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity second = main; openSettings(test, second);
            test.runOnMainSync(() -> {
                check(((android.widget.SeekBar)second.getWindow().getDecorView().findViewWithTag(UiPreferences.BALL)).getProgress() == 35, "Saved slider value lost");
                ((android.widget.SeekBar)second.getWindow().getDecorView().findViewWithTag(UiPreferences.WINDOW)).setProgress(40);
            });
            until(() -> Math.abs(floating.windowOpacity() - .40f) < .001f, 5, "Active window opacity did not update");
            capture(test, context, "floating-opacity");
            test.runOnMainSync(() -> find(second.getWindow().getDecorView(), "恢复默认透明度").performClick());
            until(() -> floating.windowOpacity() == 1f, 5, "Reset did not update active window");
            for (String key : keys) check(UiPreferences.percent(prefs, key) == 100, "Reset left custom opacity");
            monitor = test.addMonitor(RemoteActivity.class.getName(), null, false);
            test.runOnMainSync(() -> floating.fullscreenButton.performClick());
            restored = (RemoteActivity)test.waitForMonitorWithTimeout(monitor, 15000);
            check(restored != null, "Settings test fullscreen restore failed"); RemoteActivity finalFull = restored;
            until(() -> finalFull.session == original && !FloatingWindowService.isShowing(), 15, "Settings changed live session");
            check(finalFull.ball.getAlpha() == 1f, "Default ball opacity not restored");
            check(identity.equals(state.adb.fingerprint()) && history.equals(context.getSharedPreferences("devices", Context.MODE_PRIVATE).getString("history", "")), "Appearance modified identity/history");
            DebugLog.write("TEST settings overflow/back/persistence/minimum, full ball/menu/power opacity, live window alpha, reset/same session PASS");
            return "PASS settings menu/back, persisted opacity and minimum, full ball/menu/power, live overlay opacity, reset, same session/identity/history";
        } finally {
            if (monitor != null) test.removeMonitor(monitor);
            MainActivity endMain = main; RemoteActivity endRemote = remote, endRestored = restored;
            test.runOnMainSync(() -> { context.stopService(new Intent(context, FloatingWindowService.class)); if (endMain != null) endMain.finish(); if (endRemote != null) endRemote.finish(); if (endRestored != null) endRestored.finish(); });
            SharedPreferences.Editor edit = prefs.edit(); for (int i = 0; i < keys.length; i++) { if (had[i]) edit.putInt(keys[i], old[i]); else edit.remove(keys[i]); } edit.apply();
            state.shell("am force-stop org.scrcpy.android.test");
        }
    }
    private static void menuItem(Instrumentation test, RemoteActivity remote, String text) {
        test.runOnMainSync(() -> {
            if (remote.menuContent() == null) remote.ball.performClick();
            View item = find(remote.menuContent(), text); check(item != null, "Missing menu item: " + text); item.performClick();
        });
    }
    private static String rotation(Instrumentation test, Context context, RuntimeState state, RemoteActivity remote) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("remote-ui", Context.MODE_PRIVATE);
        boolean oldAuto = prefs.getBoolean("autoRotate", true), hadAuto = prefs.contains("autoRotate");
        String auto = state.shell("settings get system accelerometer_rotation").trim();
        String rotation = state.shell("settings get system user_rotation").trim();
        ScrcpySession original = remote.session;
        try {
            check(remote.autoRotateEnabled() == oldAuto, "Rotation preference not loaded");
            test.runOnMainSync(() -> remote.setAutoRotate(true));
            state.shell("settings put system accelerometer_rotation 0");
            int[] expected = {1, 0, 9, 8};
            for (int i = 0; i < 4; i++) {
                final int value = i;
                state.shell("wm user-rotation lock " + i);
                until(() -> remote.getRequestedOrientation() == expected[value] && remote.getDisplay().getRotation() == value,
                        20, "Native UI did not follow remote rotation=" + i + " requested=" + remote.getRequestedOrientation());
                until(() -> (original.width > original.height) == ((value & 1) == 1), 12, "Video aspect did not follow rotation");
                check(remote.session == original && state.session == original && !original.isClosed() && !remote.isFinishing(), "Rotation recreated session/activity");
            }
            menuItem(test, remote, "自动屏幕旋转");
            check(!prefs.getBoolean("autoRotate", true) && !remote.autoRotateEnabled(), "Rotation toggle not saved");
            state.shell("wm user-rotation lock 0"); Thread.sleep(2200);
            check(remote.getRequestedOrientation() == -1, "Disabled rotation still forced UI orientation");
            menuItem(test, remote, "自动屏幕旋转");
            until(() -> remote.getRequestedOrientation() == 1 && remote.getDisplay().getRotation() == 0, 20, "Reenabled rotation did not follow");
            menuItem(test, remote, "通知栏");
            until(() -> state.shell("dumpsys activity service com.android.systemui/.SystemUIService").contains("isPanelExpanded()=true"), 10, "Remote notification shade did not open");
            check(remote.hasWindowFocus(), "Notification command opened local shade");
            menuItem(test, remote, "快捷设置");
            until(() -> state.shell("dumpsys activity service com.android.systemui/.SystemUIService").contains("mQsExpanded=true"), 10, "Remote quick settings did not expand");
            menuItem(test, remote, "收起通知");
            until(() -> state.shell("dumpsys activity service com.android.systemui/.SystemUIService").contains("isPanelExpanded()=false"), 10, "Remote notification shade did not collapse");
            long frames = original.renderedFrames();
            test.runOnMainSync(remote::requestFloatingWindow);
            until(() -> FloatingWindowService.isShowing() && remote.isFinishing(), 15, "Rotation window handoff failed");
            int localRotation = context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0).getRotation();
            state.shell("wm user-rotation lock 1");
            until(() -> original.width > original.height && original.renderedFrames() > frames + 2, 15, "Floating video did not rotate");
            check(context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0).getRotation() == localRotation, "Floating mode forced controller orientation");
            check(state.session == original, "Floating rotation replaced session");
            DebugLog.write("TEST native rotation 0/90/180/270, opt-out/persistence, remote panels, unchanged floating rotation PASS");
            return "PASS native UI rotation 0/90/180/270, same session, persisted toggle, remote notifications/quick settings/collapse, floating rotation unchanged";
        } finally {
            state.shell("settings " + ("null".equals(rotation) ? "delete system user_rotation" : "put system user_rotation " + RuntimeState.quote(rotation)));
            state.shell("settings " + ("null".equals(auto) ? "delete system accelerometer_rotation" : "put system accelerometer_rotation " + RuntimeState.quote(auto)));
            SharedPreferences.Editor edit = prefs.edit(); if (hadAuto) edit.putBoolean("autoRotate", oldAuto); else edit.remove("autoRotate"); edit.apply();
        }
    }
    private static void checkAspect(FloatingWindowService floating, ScrcpySession session) throws Exception {
        until(() -> floating.display.video.getWidth() > 0 && floating.display.video.getHeight() > 0, 5, "Floating video has no size");
        double rendered = floating.display.video.getWidth() / (double)floating.display.video.getHeight();
        check(Math.abs(rendered - session.width / (double)session.height) < .008, "Window resize stretched the remote image");
    }
    private static void drag(View view, float dx, float dy) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, 12 + (action == MotionEvent.ACTION_DOWN ? 0 : dx), 20 + (action == MotionEvent.ACTION_DOWN ? 0 : dy), 0);
            view.dispatchTouchEvent(event); event.recycle();
        }
    }
    private static String window(Instrumentation test, Context context, RuntimeState state, RemoteActivity remote) throws Exception {
        ScrcpySession original = remote.session; long before = original.renderedFrames();
        String touches = state.shell("logcat -d -s ScrcpyProbe:I '*:S'");
        test.runOnMainSync(remote::requestFloatingWindow);
        until(() -> FloatingWindowService.isShowing() && remote.isFinishing(), 15, "Window did not attach");
        FloatingWindowService floating = FloatingWindowService.current();
        // Let the controller finish returning from remote-oriented fullscreen
        // to its normal UI before measuring an overlay resize.
        test.waitForIdleSync(); Thread.sleep(800);
        until(() -> WINDOW_NAME.contentEquals(floating.deviceLabel.getText()), 8, "ADB device name did not reach title");
        test.runOnMainSync(() -> {
            check(floating.header.getHeight() == floating.footer.getHeight(), "Window bars have different heights");
            check(floating.powerButton.getIcon() != null && floating.powerButton.getText().length() == 0, "Power is not an icon button");
            check(floating.fullscreenButton.getIcon() != null && floating.fullscreenButton.getText().length() == 0, "Fullscreen is not an icon button");
            android.graphics.Outline outline = new android.graphics.Outline();
            View frame = floating.windowView(); frame.getOutlineProvider().getOutline(frame, outline);
            check(frame.getClipToOutline() && outline.canClip() && outline.getRadius() > 0, "Rounded frame does not clip its bars");
            check(!floating.leftResizeGrip.isClickable() && !floating.rightResizeGrip.isClickable(), "Resize corners are still click buttons");
        });
        Rect unchanged = floating.windowBounds();
        test.runOnMainSync(() -> { drag(floating.leftResizeGrip, 0, 0); drag(floating.rightResizeGrip, 0, 0); });
        check(unchanged.equals(floating.windowBounds()), "A corner tap still resized the window");
        test.runOnMainSync(() -> drag(floating.rightResizeGrip, -50000, -50000));
        until(() -> floating.deviceLabel.getLayout() != null && floating.deviceLabel.getLayout().getEllipsisCount(0) > 0, 5, "Narrow title does not ellipsize");
        capture(test, context, "window-compact");
        int narrowEnd = floating.deviceLabel.getLayout().getEllipsisStart(0);
        int initial = floating.windowBounds().width();
        test.runOnMainSync(() -> drag(floating.rightResizeGrip, 160, 200));
        until(() -> floating.windowBounds().width() > initial, 5, "Right corner did not resize"); checkAspect(floating, original);
        until(() -> floating.deviceLabel.getLayout().getEllipsisStart(0) > narrowEnd, 5, "Title did not use extra width after resize");
        int larger = floating.windowBounds().width();
        test.runOnMainSync(() -> drag(floating.leftResizeGrip, 70, -80));
        until(() -> floating.windowBounds().width() < larger, 5, "Left corner did not resize"); checkAspect(floating, original);
        int leftSmall = floating.windowBounds().width();
        test.runOnMainSync(() -> drag(floating.leftResizeGrip, -110, 120));
        until(() -> floating.windowBounds().width() > leftSmall, 5, "Left corner did not enlarge"); checkAspect(floating, original);
        Rect beforeMove = floating.windowBounds(); test.runOnMainSync(() -> drag(floating.deviceLabel, 45, 40));
        check(!beforeMove.equals(floating.windowBounds()), "Device-name header no longer moves the window");
        capture(test, context, "window-expanded");
        test.runOnMainSync(() -> drag(floating.rightResizeGrip, 50000, 50000));
        Rect fit = floating.windowBounds(); Rect screen = context.getSystemService(android.view.WindowManager.class).getCurrentWindowMetrics().getBounds();
        check(fit.left >= 0 && fit.top >= 0 && fit.right <= screen.width() && fit.bottom <= screen.height(), "Resized window left screen bounds"); checkAspect(floating, original);
        check(touches.equals(state.shell("logcat -d -s ScrcpyProbe:I '*:S'")), "Footer resize leaked touch to target");
        test.runOnMainSync(() -> floating.powerButton.performClick());
        until(() -> !awake(state), 12, "Power icon did not sleep target");
        Thread.sleep(650); test.runOnMainSync(() -> floating.powerButton.performClick());
        until(() -> awake(state), 12, "Power icon did not wake target");
        android.app.Instrumentation.ActivityMonitor monitor = test.addMonitor(RemoteActivity.class.getName(), null, false);
        RemoteActivity restored = null;
        try {
            test.runOnMainSync(() -> floating.fullscreenButton.performClick());
            restored = (RemoteActivity)test.waitForMonitorWithTimeout(monitor, 15000);
            check(restored != null, "Footer fullscreen action did not open activity"); RemoteActivity full = restored;
            until(() -> full.session == original && !FloatingWindowService.isShowing() && original.renderedFrames() > before + 5, 15, "Footer fullscreen restart lost session");
            DebugLog.write("TEST window equal bars/rounded clip/icon controls/nonclick resize grips/ADB name/ellipsis/drag/power/fullscreen same-session PASS");
            return "PASS equal bars, rounded clipping, icon power/fullscreen, drag-only corners/aspect/bounds, ADB name/width ellipsis, header move, isolated input, same session";
        } finally { test.removeMonitor(monitor); if (restored != null) { RemoteActivity full = restored; test.runOnMainSync(full::finish); } }
    }
}
