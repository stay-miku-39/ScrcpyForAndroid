package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import static org.scrcpy.android.UiControlsSmoke.check;

final class ColorPickerSmoke {
    private static View description(View view,String text) {
        if(text.contentEquals(view.getContentDescription()==null ? "" : view.getContentDescription())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=description(((ViewGroup)view).getChildAt(i),text); if(found!=null) return found;
        }
        return null;
    }
    static String run(Instrumentation test,Context context) throws Exception {
        SharedPreferences prefs=UiPreferences.get(context); Map<String,?> saved=prefs.getAll(),stream=StreamPreferences.get(context).getAll();
        MainActivity main=null;
        try {
            main=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); MainActivity activity=main;
            test.runOnMainSync(() -> { activity.ui.select("settings"); UiControlsSmoke.find(activity.getWindow().getDecorView(),"打开字体调色盘").performClick(); });
            AlertDialog first=activity.ui.colorPicker; check(first!=null,"Color palette did not open");
            test.waitForIdleSync();
            View palette=first.getWindow().getDecorView().findViewWithTag("qualityPalette");
            UiControlsSmoke.screenDrag(test,palette,palette.getWidth()*.25f,palette.getHeight()*.3f,350,false);
            final int[] preview={0};
            test.runOnMainSync(() -> {
                ((SeekBar)first.getWindow().getDecorView().findViewWithTag("colorBrightness")).setProgress(25);
                preview[0]=((TextView)first.getWindow().getDecorView().findViewWithTag("colorPreview")).getCurrentTextColor();
            });
            float[] hsv=new float[3]; Color.colorToHSV(preview[0],hsv); check(Math.abs(hsv[2]-.25)<.01,"Brightness did not update preview");
            Bitmap image=test.getUiAutomation().takeScreenshot();
            try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir("color-smoke"),"palette.png"))) { image.compress(Bitmap.CompressFormat.PNG,100,out); } finally { image.recycle(); }
            test.runOnMainSync(() -> first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()); test.waitForIdleSync();
            check(saved.equals(prefs.getAll()),"Cancelled palette wrote preferences");
            chooseCyan(test,activity);
            check("#40C4FF".contentEquals(((EditText)activity.getWindow().getDecorView().findViewWithTag(UiPreferences.QUALITY_COLOR)).getText()),"Swatch not copied to draft");
            check(saved.equals(prefs.getAll()),"Choosing palette color bypassed Save");
            test.runOnMainSync(() -> { activity.ui.select("connect"); activity.ui.select("settings"); });
            check(prefs.getString(UiPreferences.QUALITY_COLOR,"#FFFFFF").contentEquals(((EditText)activity.getWindow().getDecorView().findViewWithTag(UiPreferences.QUALITY_COLOR)).getText()),"Leaving retained palette draft");
            chooseCyan(test,activity);
            test.runOnMainSync(() -> activity.getWindow().getDecorView().findViewWithTag("saveStreamSettings").performClick());
            check(UiPreferences.qualityColor(prefs)==0xFF40C4FF,"Saved palette color not persisted");
            test.runOnMainSync(() -> { activity.ui.select("connect"); activity.ui.select("settings"); });
            check("#40C4FF".contentEquals(((EditText)activity.getWindow().getDecorView().findViewWithTag(UiPreferences.QUALITY_COLOR)).getText()),"Saved palette color not reloaded");
            return "PASS real HSV drag, brightness/preview, swatches, cancel, explicit Save, draft discard and reload";
        } finally {
            if(main!=null) { MainActivity activity=main; test.runOnMainSync(activity::finish); }
            StreamSettingsSmoke.restore(prefs,saved); StreamSettingsSmoke.restore(StreamPreferences.get(context),stream);
        }
    }
    private static void chooseCyan(Instrumentation test,MainActivity activity) {
        test.runOnMainSync(() -> UiControlsSmoke.find(activity.getWindow().getDecorView(),"打开字体调色盘").performClick());
        test.waitForIdleSync();
        test.runOnMainSync(() -> {
            AlertDialog picker=activity.ui.colorPicker; description(picker.getWindow().getDecorView(),"选择颜色 #40C4FF").performClick();
            picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        test.waitForIdleSync();
    }
}
