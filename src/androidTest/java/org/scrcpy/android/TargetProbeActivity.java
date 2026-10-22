package org.scrcpy.android;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.util.Log;
import android.view.*;

/** Test APK only: animated target, PCM tone, observable multi-touch and clipboard. */
public class TargetProbeActivity extends Activity {
    private volatile boolean running;
    private Thread tone;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean bitrateLoad=getIntent().getBooleanExtra("bitrateLoad",false);
        setContentView(new View(this) {
            final Paint paint = new Paint(3);
            final Bitmap texture = bitrateLoad ? texture() : null;
            private Bitmap texture() {
                int[] pixels=new int[512*512]; java.util.Random random=new java.util.Random(731);
                for(int i=0;i<pixels.length;i++) { int shade=random.nextInt(128)+64; pixels[i]=Color.rgb(shade,shade,shade); }
                return Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888);
            }
            @Override protected void onDraw(Canvas canvas) {
                if(texture!=null) {
                    int offset=(int)(SystemClock.uptimeMillis()/4%512);
                    for(int y=-512;y<getHeight();y+=512) for(int x=0;x<getWidth();x+=512) canvas.drawBitmap(texture,x,y+offset,paint);
                    postInvalidateDelayed(16); return;
                }
                canvas.drawColor(Color.rgb(16, 34, 64)); paint.setColor(Color.CYAN); paint.setTextSize(44);
                canvas.drawText("Scrcpy target probe", 60, 180, paint);
                canvas.drawText("Android " + Build.VERSION.RELEASE, 60, 250, paint);
                float x = 80 + (SystemClock.uptimeMillis() % 3000) * (getWidth() - 160) / 3000f;
                canvas.drawCircle(x, getHeight() / 2f, 60, paint); postInvalidateDelayed(33);
            }
            @Override protected void onDetachedFromWindow() { if(texture!=null) texture.recycle(); super.onDetachedFromWindow(); }
            @Override public boolean onTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action != MotionEvent.ACTION_MOVE) Log.i("ScrcpyProbe", "touch action=" + action + " pointers=" + event.getPointerCount());
                if (action == MotionEvent.ACTION_UP) {
                    ClipboardManager manager = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    manager.setPrimaryClip(ClipData.newPlainText("probe", "remote-clipboard-安卓"));
                }
                return true;
            }
        });
        ClipboardManager clips = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clips.addPrimaryClipChangedListener(() -> {
            ClipData clip = clips.getPrimaryClip();
            if (clip != null) {
                String text = String.valueOf(clip.getItemAt(0).getText());
                if ("host-clipboard-安卓".equals(text) || "remote-clipboard-安卓".equals(text)) Log.i("ScrcpyProbe", "clipboard=" + text);
            }
        });
        running = true;
        tone = new Thread(() -> {
            AudioTrack track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(19200).setTransferMode(AudioTrack.MODE_STREAM).build();
            try {
                track.play(); short[] samples = new short[2048]; long position = 0;
                while (running) {
                    for (int i = 0; i < samples.length; i += 2) { short s = (short)(Math.sin(2 * Math.PI * 440 * position++ / 48000) * 3000); samples[i] = s; samples[i+1] = s; }
                    track.write(samples, 0, samples.length);
                }
            } finally { track.stop(); track.release(); }
        }, "probe-tone"); tone.start();
    }
    @Override protected void onDestroy() { running = false; super.onDestroy(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            Log.i("ScrcpyProbe", "key action=" + event.getAction() + " code=" + event.getKeyCode() + " repeat=" + event.getRepeatCount());
            return true; // Keep the system volume panel from taking focus during event assertions.
        }
        return super.dispatchKeyEvent(event);
    }
}
