package org.scrcpy.android;

import android.content.*;
import android.view.View;
import android.widget.*;
import java.util.*;
import static org.scrcpy.android.UiControlsSmoke.check;

/** Real settings widgets, upgrade interpretation, explicit save and invalid drafts. */
final class BitratePoliciesSmoke {
    static String run(SmokeInstrumentation test,Context context) {
        Map<String,?> before=StreamPreferences.get(context).getAll(), uiBefore=UiPreferences.get(context).getAll();
        MainActivity main=null;
        try {
            StreamPreferences.save(context,StreamSettings.DEFAULTS);
            StreamPreferences.get(context).edit().remove("videoTargetMode").remove("videoBitRateLimit").putString("videoBitRate","100000000").commit();
            check(StreamPreferences.snapshot(context).videoBitRate==8000000,"Legacy high target remained active");
            check("100000000".equals(StreamPreferences.snapshot(context).values.get("videoBitRate")),"Legacy custom value was lost");
            main=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); MainActivity activity=main;
            onMain(test,() -> {
                activity.ui.select("settings"); View root=activity.getWindow().getDecorView();
                Spinner mode=root.findViewWithTag("stream_videoTargetMode"); EditText limit=root.findViewWithTag("stream_videoBitRateLimit"), target=root.findViewWithTag("stream_videoBitRate");
                check(!target.isEnabled(),"Default target was editable");
                root.findViewWithTag("stream_advanced").performClick();
                limit.setText("100000000"); root.findViewWithTag("saveStreamSettings").performClick();
                check(StreamPreferences.snapshot(context).videoBitRate==8000000,"Large cap increased default target");
                mode.setSelection(1); limit.setText("1000000"); root.findViewWithTag("saveStreamSettings").performClick();
                check(StreamPreferences.snapshot(context).videoBitRate==800000,"Automatic ratio not saved");
                mode.setSelection(2); target.setText("4000000"); root.findViewWithTag("saveStreamSettings").performClick();
                check("auto".equals(StreamPreferences.snapshot(context).videoTargetMode),"Invalid custom target was saved");
                limit.setText("8000000"); root.findViewWithTag("saveStreamSettings").performClick();
                check(StreamPreferences.snapshot(context).videoBitRate==4000000,"Custom target not saved");
                target.setText("7000000"); activity.ui.select("connect"); activity.ui.select("settings");
                check("4000000".equals(((EditText)root.findViewWithTag("stream_videoBitRate")).getText().toString()),"Unsaved target survived navigation");
            });
            return "PASS bitrate policy widgets, legacy high-target migration, validation and explicit save";
        } finally {
            StreamSettingsSmoke.restore(StreamPreferences.get(context),before); StreamSettingsSmoke.restore(UiPreferences.get(context),uiBefore);
            if(main!=null) { MainActivity activity=main; test.runOnMainSync(activity::finish); }
        }
    }
    private static void onMain(SmokeInstrumentation test,Runnable action) {
        Throwable[] failure={null};
        test.runOnMainSync(() -> { try { action.run(); } catch(Throwable error) { failure[0]=error; } });
        if(failure[0]!=null) throw new AssertionError("Settings check failed",failure[0]);
    }
}
