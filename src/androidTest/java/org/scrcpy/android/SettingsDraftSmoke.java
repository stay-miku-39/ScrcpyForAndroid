package org.scrcpy.android;

import android.content.*;
import android.view.View;
import android.widget.*;
import java.util.*;

/** No target connection or probe: exercises the real navigation and save button. */
final class SettingsDraftSmoke {
    static String run(SmokeInstrumentation test, Context context) {
        Map<String, ?> before = StreamPreferences.get(context).getAll();
        Map<String, ?> uiBefore = UiPreferences.get(context).getAll();
        MainActivity main = null;
        try {
            main = (MainActivity)test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity activity = main;
            test.runOnMainSync(() -> {
                StreamPreferences.save(context, StreamSettings.DEFAULTS);
                activity.ui.select("settings");
                View root = activity.getWindow().getDecorView();
                ((Spinner)root.findViewWithTag("stream_resolution")).setSelection(1);
                ((EditText)root.findViewWithTag("stream_customWidth")).setText("888");
                ((SeekBar)root.findViewWithTag(UiPreferences.BALL)).setProgress(37);
                CheckBox background = (CheckBox)UiControlsSmoke.find(root, "后台保留远程控制连接"); background.setChecked(!background.isChecked());
                if (!"original".equals(StreamPreferences.get(context).getString("resolution", ""))) throw new AssertionError("Draft persisted without Save");
                if (!uiBefore.equals(UiPreferences.get(context).getAll())) throw new AssertionError("UI settings auto-saved before Save");
                activity.ui.select("connect"); activity.ui.select("settings");
                if (((Spinner)root.findViewWithTag("stream_resolution")).getSelectedItemPosition() != 0) throw new AssertionError("Navigation retained draft resolution");
                if (!"1080".equals(((EditText)root.findViewWithTag("stream_customWidth")).getText().toString())) throw new AssertionError("Navigation retained draft width");
                ((Spinner)root.findViewWithTag("stream_resolution")).setSelection(1);
                ((SeekBar)root.findViewWithTag(UiPreferences.BALL)).setProgress(37);
                root.findViewWithTag("saveStreamSettings").performClick();
                if (UiPreferences.percent(UiPreferences.get(context), UiPreferences.BALL) != 37) throw new AssertionError("Save did not persist entire page");
                activity.ui.select("tools"); activity.ui.select("settings");
                if (((Spinner)root.findViewWithTag("stream_resolution")).getSelectedItemPosition() != 1) throw new AssertionError("Saved value not reloaded");
                StreamPreferences.get(context).edit().putString("resolution", "original").commit();
                activity.ui.select("connect"); activity.ui.select("settings");
                if (((Spinner)root.findViewWithTag("stream_resolution")).getSelectedItemPosition() != 0) throw new AssertionError("Reentry did not read preferences");
                ((Spinner)root.findViewWithTag("stream_resolution")).setSelection(1);
            });
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_HOME); test.waitForIdleSync();
            test.runOnMainSync(() -> {
                if (((Spinner)activity.getWindow().getDecorView().findViewWithTag("stream_resolution")).getSelectedItemPosition() != 0) throw new AssertionError("Leaving activity retained draft");
            });
            return "PASS settings draft discard and explicit save";
        } finally {
            StreamSettingsSmoke.restore(StreamPreferences.get(context), before); StreamSettingsSmoke.restore(UiPreferences.get(context), uiBefore);
            if (main != null) { MainActivity activity = main; test.runOnMainSync(activity::finish); }
        }
    }
}
