package org.scrcpy.android;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.*;
import java.util.*;

/** Opt-in one-controller/two-target integration, using the real connection and window paths. */
final class MultiDeviceSmoke {
    static void check(boolean value, String message) { UiControlsSmoke.check(value, message); }
    static void waitFor(UiControlsSmoke.Check check, String message) throws Exception { UiControlsSmoke.until(check, 75, message); }
    private static boolean fullscreenReady(RuntimeState state, DeviceConnection device, ScrcpySession session) {
        RemoteActivity activity = state.fullscreen;
        return activity != null && !activity.isFinishing() && activity.device == device && activity.display != null
                && activity.session == session && activity.display.ownsSession();
    }
    private static void connect(Instrumentation test, MainActivity main, String host, int port) throws Exception {
        test.runOnMainSync(() -> { main.host.setText(host); main.port.setText(String.valueOf(port)); main.connectNetwork(); });
        waitFor(() -> !main.runtime.operationRunning, "Connection task did not finish");
        check(main.runtime.connection(host + ":" + port) != null, "Connection failed: " + main.runtime.lastMessage);
    }
    private static void tap(Instrumentation test, View view) {
        test.runOnMainSync(() -> {
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, view.getWidth() / 2f, view.getHeight() / 2f, 0);
                view.dispatchTouchEvent(event); event.recycle();
            }
        });
    }
    private static int touches(DeviceConnection device) throws Exception {
        return Integer.parseInt(device.shell("logcat -d -s ScrcpyProbe:I '*:S' | grep -c 'touch action=1 pointers=1'").trim());
    }
    private static void home(Instrumentation test, Context context) {
        test.runOnMainSync(() -> context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }
    private static void restore(SharedPreferences preferences, Map<String, ?> saved, String... keys) {
        SharedPreferences.Editor edit = preferences.edit();
        for (String key : keys) {
            Object value = saved.get(key);
            if (value == null) edit.remove(key);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) edit.putInt(key, (Integer)value);
            else if (value instanceof String) edit.putString(key, (String)value);
        }
        edit.commit();
    }
    static String run(Instrumentation test, Context context, RuntimeState state, String host, int port, String secondHost, int secondPort) throws Exception {
        check(!Objects.equals(host + ":" + port, secondHost + ":" + secondPort), "Two different target endpoints are required");
        SharedPreferences preferences = UiPreferences.get(context), devices = context.getSharedPreferences("devices", Context.MODE_PRIVATE);
        Map<String, ?> saved = preferences.getAll(), savedDevices = devices.getAll();
        String identity = state.adb.fingerprint();
        MainActivity main = null;
        try {
            preferences.edit().putBoolean("backgroundKeepAlive", true).putBoolean("backgroundFloating", false)
                    .putBoolean("autoRotate", false).putInt("windowWidth", MainUi.dp(context, 180)).commit();
            main = (MainActivity)test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity ui = main;
            connect(test, ui, host, port); DeviceConnection first = state.current();
            first.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n org.scrcpy.android.test/org.scrcpy.android.TargetProbeActivity");
            test.runOnMainSync(() -> ui.startRemote(first.target, true));
            waitFor(() -> first.session != null && first.session.renderedFrames() > 10 && FloatingWindowService.isShowing(first.target), "First window has no frames");
            ScrcpySession one = first.session; FloatingWindow windowOne = FloatingWindowService.current();
            long oneFrames = one.renderedFrames();
            connect(test, ui, secondHost, secondPort); DeviceConnection second = state.current();
            check(first != second && state.connections().size() == 2 && first.session == one && !one.isClosed(), "Second ADB replaced the first");
            second.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n org.scrcpy.android.test/org.scrcpy.android.TargetProbeActivity");
            test.runOnMainSync(() -> ui.startRemote(second.target, true));
            waitFor(() -> second.session != null && second.session.renderedFrames() > 10 && one.renderedFrames() > oneFrames + 10, "Both windows did not continue decoding");
            FloatingWindow windowTwo = FloatingWindowService.current(); ScrcpySession two = second.session;
            check(windowOne != windowTwo && FloatingWindowService.isShowing(first.target) && FloatingWindowService.isShowing(second.target), "Two overlays not present");
            check(identity.equals(first.adb.fingerprint()) && identity.equals(second.adb.fingerprint()), "Connections did not reuse RSA identity");
            int firstTouches = touches(first), secondTouches = touches(second);
            tap(test, windowOne.display.video); waitFor(() -> touches(first) > firstTouches, "First touch did not reach first target");
            check(touches(second) == secondTouches, "First window sent input to second target");
            int afterFirst = touches(first);
            tap(test, windowTwo.display.video); waitFor(() -> touches(second) > secondTouches, "Second touch did not reach second target");
            check(touches(first) == afterFirst, "Second window sent input to first target");
            DebugLog.write("TEST multi two windows/isolated input PASS");

            check(state.connect(host, port) == first && state.latest() == second, "Reusing an old endpoint changed latest/transport");
            try { state.connect("127.0.0.1", 1); throw new AssertionError("Closed endpoint unexpectedly connected"); }
            catch (java.io.IOException expected) { check(state.latest() == second && first.session == one && second.session == two, "Failed connect changed live endpoints"); }
            // Tool selection must not change the latest-connection shortcut.
            state.select(first);
            test.runOnMainSync(ui::startLatestRemote);
            waitFor(() -> fullscreenReady(state, second, two), "Shortcut did not restore latest ADB");
            check(FloatingWindowService.isShowing(first.target) && !FloatingWindowService.isShowing(second.target), "Fullscreen removed another device's window");
            RemoteActivity full = state.fullscreen;
            long beforeBackground = two.renderedFrames();
            home(test, context);
            Thread.sleep(1800);
            waitFor(() -> two.renderedFrames() > beforeBackground + 15, "Background decoder stopped");
            check(second.session == two && !two.isClosed() && !full.isFinishing(), "Background transition lost session");
            check(second.shell("echo background-alive").contains("background-alive"), "Background ADB lost");
            test.runOnMainSync(() -> context.startActivity(RemoteActivity.intent(context, second.target, true)));
            waitFor(() -> full.display.ownsSession() && full.session == two, "Background restore replaced session");
            DebugLog.write("TEST multi background same-session restore PASS");
            preferences.edit().putBoolean("backgroundFloating", true).commit();
            Thread.sleep(500);
            home(test, context);
            waitFor(() -> FloatingWindowService.isShowing(first.target) && FloatingWindowService.isShowing(second.target) && full.isFinishing(), "Automatic background window failed");
            check(second.session == two && first.session == one, "Auto-window restarted a mirror");
            test.runOnMainSync(full.display::dispose); // Delayed/repeated old-view destruction must not close the transferred output.
            check(!two.isClosed() && second.session == two, "Obsolete fullscreen view closed transferred session");
            DebugLog.write("TEST multi automatic window PASS");

            // Restore A, then ask for B: the single fullscreen slot is reused.
            test.runOnMainSync(() -> context.startActivity(RemoteActivity.intent(context, first.target, true)));
            waitFor(() -> fullscreenReady(state, first, one), "First fullscreen restore failed");
            RemoteActivity single = state.fullscreen;
            test.runOnMainSync(() -> context.startActivity(RemoteActivity.intent(context, second.target, true)));
            waitFor(() -> state.fullscreen == single && fullscreenReady(state, second, two), "Second fullscreen instance was created");
            DebugLog.write("TEST multi single fullscreen PASS");
            // Closing one remote and disconnecting one transport must leave the peer live.
            test.runOnMainSync(single::requestFloatingWindow);
            waitFor(() -> FloatingWindowService.isShowing(second.target), "Second window restore failed");
            test.runOnMainSync(() -> ui.startRemote(first.target, true));
            waitFor(() -> first.session != null && first.session != one && !first.session.isClosed() && first.session.renderedFrames() > 5, "First remote restart failed");
            ScrcpySession restartedFirst = first.session;
            test.runOnMainSync(() -> FloatingWindowService.closeRemote(context, first.target));
            check(restartedFirst.isClosed() && !two.isClosed(), "Closing one live window affected peer");
            check(first.adb.isConnected(), "Closing remote disconnected ADB");
            first.shell("am force-stop org.scrcpy.android.test");
            state.disconnect(first);
            long before = two.renderedFrames();
            waitFor(() -> two.renderedFrames() > before + 10, "Peer stopped after endpoint disconnect");
            check(state.latest() == second && second.adb.isConnected(), "Disconnect changed peer/latest");
            test.runOnMainSync(() -> FloatingWindowService.closeRemote(context, second.target));
            test.runOnMainSync(() -> ui.startRemote(second.target, false));
            waitFor(() -> state.fullscreen != null && !state.fullscreen.isFinishing() && state.fullscreen.device == second && state.fullscreen.session != null && state.fullscreen.session != two && !state.fullscreen.session.isClosed() && state.fullscreen.session.renderedFrames() > 5, "Remote restart failed");
            ScrcpySession optOut = state.fullscreen.session;
            preferences.edit().putBoolean("backgroundKeepAlive", false).commit();
            home(test, context);
            waitFor(optOut::isClosed, "Disabling keep-alive did not stop background remote");
            check(second.adb.isConnected(), "Keep-alive opt-out disconnected ADB");
            DeviceConnection reconnectedFirst = state.connect(host, port);
            check(state.latest() == reconnectedFirst, "New connection did not become latest");
            state.disconnect(reconnectedFirst);
            check(state.latest() == second && second.adb.isConnected(), "Latest disconnect did not fall back to remaining connection");
            test.runOnMainSync(() -> ui.startRemote(second.target, false));
            waitFor(() -> state.fullscreen != null && !state.fullscreen.isFinishing() && state.fullscreen.session != null && !state.fullscreen.session.isClosed() && state.fullscreen.session.renderedFrames() > 5, "Disconnect-all fullscreen did not start");
            RemoteActivity disconnectFull = state.fullscreen;
            second.shell("am force-stop org.scrcpy.android.test");
            test.runOnMainSync(() -> context.startService(new Intent(context, FloatingWindowService.class).setAction(FloatingWindowService.DISCONNECT)));
            waitFor(() -> state.connections().isEmpty() && disconnectFull.isFinishing(), "Disconnect-all did not close fullscreen/transports");
            return "PASS two ADB identities, two live windows, isolated touch, latest shortcut, single fullscreen, background same-session decode/restore, automatic window, per-device close/disconnect, keep-alive opt-out";
        } finally {
            test.runOnMainSync(() -> { if (state.fullscreen != null) state.fullscreen.finish(); context.stopService(new Intent(context, FloatingWindowService.class)); });
            for (DeviceConnection device : state.connections()) {
                try { device.shell("am force-stop org.scrcpy.android.test"); } catch (Exception ignored) { }
                state.disconnect(device);
            }
            if (main != null) { MainActivity last = main; test.runOnMainSync(last::finish); }
            restore(preferences, saved, "backgroundKeepAlive", "backgroundFloating", "autoRotate", "windowWidth", "windowX", "windowY");
            restore(devices, savedDevices, "history", "host", "port", "audio");
            check(identity.equals(state.adb.fingerprint()), "Identity changed after cleanup");
        }
    }
}
