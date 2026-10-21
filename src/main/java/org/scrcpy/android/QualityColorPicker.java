package org.scrcpy.android;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Native HSV palette. The caller owns the settings draft and explicit Save. */
final class QualityColorPicker {
    static AlertDialog show(Context context, int initial, IntConsumer selected) {
        LinearLayout panel=new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL);
        int padding=MainUi.dp(context,16); panel.setPadding(padding,padding,padding,padding);
        TextView preview=new TextView(context); preview.setTextSize(16); preview.setPadding(padding,padding,padding,padding);
        preview.setTag("colorPreview"); preview.setBackgroundColor(0xFF182841); panel.addView(preview);
        float[] hsv=new float[3]; Color.colorToHSV(initial,hsv);
        final Palette[] palette={null}; final SeekBar[] brightness={null};
        Runnable update=() -> {
            int color=Color.HSVToColor(hsv); preview.setTextColor(color);
            preview.setText(String.format(Locale.ROOT,"质量参数示例 · #%06X",color & 0xFFFFFF));
            if(palette[0]!=null) palette[0].invalidate();
        };
        TextView hint=new TextView(context); hint.setText("在调色盘中选择色相与饱和度，再调整明度。"); hint.setTextColor(MainUi.INK); panel.addView(hint);
        palette[0]=new Palette(context,hsv,update); palette[0].setTag("qualityPalette");
        panel.addView(palette[0],new LinearLayout.LayoutParams(-1,MainUi.dp(context,180)));
        TextView label=new TextView(context); label.setText("明度"); label.setTextColor(MainUi.INK); panel.addView(label);
        brightness[0]=new SeekBar(context); brightness[0].setTag("colorBrightness"); brightness[0].setContentDescription("字体颜色明度");
        brightness[0].setMax(100); brightness[0].setProgress(Math.round(hsv[2]*100)); panel.addView(brightness[0]);
        brightness[0].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
            public void onProgressChanged(SeekBar bar,int progress,boolean user) { hsv[2]=progress/100f; update.run(); }
        });
        LinearLayout swatches=new LinearLayout(context); panel.addView(swatches);
        for(int color:new int[]{Color.WHITE,Color.BLACK,0xFFFF5252,0xFFFFD740,0xFF69F0AE,0xFF40C4FF,0xFFB388FF}) {
            Button swatch=new Button(context); swatch.setText("●"); swatch.setTextSize(22); swatch.setTextColor(color); swatch.setMinWidth(0); swatch.setMinimumWidth(0); swatch.setPadding(0,0,0,0);
            swatch.setContentDescription(String.format(Locale.ROOT,"选择颜色 #%06X",color & 0xFFFFFF));
            swatches.addView(swatch,new LinearLayout.LayoutParams(0,MainUi.dp(context,48),1));
            swatch.setOnClickListener(v -> { Color.colorToHSV(color,hsv); brightness[0].setProgress(Math.round(hsv[2]*100)); update.run(); });
        }
        update.run(); ScrollView scroll=new ScrollView(context); scroll.addView(panel);
        AlertDialog dialog=new AlertDialog.Builder(context).setTitle("质量参数字体颜色").setView(scroll)
                .setNegativeButton("取消",null).setPositiveButton("使用此颜色",(d,w) -> selected.accept(Color.HSVToColor(hsv))).create();
        dialog.show(); return dialog;
    }
    @android.annotation.SuppressLint("AppCompatCustomView")
    private static final class Palette extends View {
        private final float[] hsv;
        private final Runnable changed;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader hues,white;
        Palette(Context context,float[] hsv,Runnable changed) {
            super(context); this.hsv=hsv; this.changed=changed; setContentDescription("颜色调色盘，横向色相，纵向饱和度");
            setFocusable(true); setClickable(true);
        }
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
            hues=new LinearGradient(0,0,Math.max(1,w),0,new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED},null,Shader.TileMode.CLAMP);
            white=new LinearGradient(0,0,0,Math.max(1,h),Color.WHITE,Color.TRANSPARENT,Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas canvas) {
            if(hues==null) return;
            paint.setStyle(Paint.Style.FILL); paint.setAlpha(255); paint.setShader(hues); canvas.drawRect(0,0,getWidth(),getHeight(),paint);
            paint.setShader(white); canvas.drawRect(0,0,getWidth(),getHeight(),paint); paint.setShader(null);
            paint.setColor(Color.BLACK); paint.setAlpha(Math.round((1-hsv[2])*255)); canvas.drawRect(0,0,getWidth(),getHeight(),paint); paint.setAlpha(255);
            float x=Math.max(8,Math.min(getWidth()-8,hsv[0]/360f*getWidth())),y=Math.max(8,Math.min(getHeight()-8,hsv[1]*getHeight()));
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5); paint.setColor(Color.BLACK); canvas.drawCircle(x,y,MainUi.dp(getContext(),8),paint);
            paint.setStrokeWidth(2); paint.setColor(Color.WHITE); canvas.drawCircle(x,y,MainUi.dp(getContext(),8),paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN || event.getActionMasked()==MotionEvent.ACTION_MOVE || event.getActionMasked()==MotionEvent.ACTION_UP) {
                getParent().requestDisallowInterceptTouchEvent(true);
                hsv[0]=Math.max(0,Math.min(359.999f,event.getX()/Math.max(1,getWidth())*360));
                hsv[1]=Math.max(0,Math.min(1,event.getY()/Math.max(1,getHeight()))); changed.run();
                if(event.getActionMasked()==MotionEvent.ACTION_UP) { performClick(); getParent().requestDisallowInterceptTouchEvent(false); }
                return true;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL) getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onKeyDown(int key,KeyEvent event) {
            if(key==KeyEvent.KEYCODE_DPAD_LEFT) hsv[0]=(hsv[0]+355)%360;
            else if(key==KeyEvent.KEYCODE_DPAD_RIGHT) hsv[0]=(hsv[0]+5)%360;
            else if(key==KeyEvent.KEYCODE_DPAD_UP) hsv[1]=Math.max(0,hsv[1]-.05f);
            else if(key==KeyEvent.KEYCODE_DPAD_DOWN) hsv[1]=Math.min(1,hsv[1]+.05f);
            else return super.onKeyDown(key,event);
            changed.run(); return true;
        }
    }
}
