package org.scrcpy.android;

import android.content.*;
import android.widget.CheckBox;
import java.util.*;
import java.io.*;
import static org.scrcpy.android.UiControlsSmoke.*;

final class LoggingSmoke {
    static String run(SmokeInstrumentation test, Context context) throws Exception {
        Map<String, ?> before = UiPreferences.get(context).getAll();
        Map<String, ?> streamBefore = StreamPreferences.get(context).getAll();
        MainActivity main = null;
        String marker = "logging-test-" + System.nanoTime(); File file = DebugLog.file(context);
        try {
            UiPreferences.get(context).edit().putBoolean(UiPreferences.DEBUG_LOG, false).commit(); DebugLog.setEnabled(false);
            long size = file.length(); DebugLog.write(marker + "-off"); DebugLog.error(marker + "-off-error", new IOException("test exception"));
            Thread.sleep(100); check(size == file.length(), "Disabled logger wrote to file");
            main = (MainActivity)test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); MainActivity activity = main;
            test.runOnMainSync(() -> {
                activity.ui.select("settings"); ((CheckBox)activity.getWindow().getDecorView().findViewWithTag(UiPreferences.DEBUG_LOG)).setChecked(true);
                check(!DebugLog.isEnabled(), "Unsaved debug switch took effect"); activity.getWindow().getDecorView().findViewWithTag("saveStreamSettings").performClick();
            });
            check(DebugLog.isEnabled(), "Saved debug switch not applied"); DebugLog.write(marker + "-on");
            until(() -> new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains(marker + "-on"), 5, "Enabled logger did not append");
            test.runOnMainSync(() -> {
                ((CheckBox)activity.getWindow().getDecorView().findViewWithTag(UiPreferences.DEBUG_LOG)).setChecked(false);
                check(DebugLog.isEnabled(), "Unsaved disable took effect"); activity.getWindow().getDecorView().findViewWithTag("saveStreamSettings").performClick();
            });
            check(!DebugLog.isEnabled(), "Saved disable not applied");
            long disabledSize = file.length(); DebugLog.write(marker + "-after"); DebugLog.error(marker + "-after-error", new IOException("test exception"));
            Thread.sleep(150); check(disabledSize == file.length(), "Disabled logger appended messages");
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            check(!text.contains(marker + "-off") && !text.contains(marker + "-after"), "Disabled diagnostics leaked");
            DebugLog.init(context); check(!DebugLog.isEnabled(), "Saved disabled state not loaded");
            return "PASS debug opt-in, manual save, background file append, disabled messages suppressed";
        } finally {
            StreamSettingsSmoke.restore(UiPreferences.get(context), before); StreamSettingsSmoke.restore(StreamPreferences.get(context), streamBefore); DebugLog.init(context);
            if (main != null) { MainActivity activity = main; test.runOnMainSync(activity::finish); }
        }
    }
}
