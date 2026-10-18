package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Interactive overlay. Deliberately has no remote-operation menu or hardware-key interception. */
public class FloatingWindowService extends Service implements RemoteDisplay.Events {
    private static final String CHANNEL = "remote-window";
    private static final int NOTIFICATION = 42;
    static final String CLOSE = "org.scrcpy.android.CLOSE_WINDOW";
    private static FloatingWindowService instance;
    private RuntimeState runtime;
    private WindowManager windows;
    private WindowManager.LayoutParams position;
    private LinearLayout window;
    RemoteDisplay display;
    private ResultReceiver result;
    private int remoteWidth = 720, remoteHeight = 1600;
    private boolean audio;
    static boolean isShowing() { return instance != null && instance.window != null; }
    static FloatingWindowService current() { return instance; }
    private int dp(float value) { return MainUi.dp(this, value); }

    @Override public void onCreate() {
        super.onCreate(); instance = this;
        try { runtime = RuntimeState.get(this); }
        catch (Exception e) { DebugLog.error("floating init", e); stopSelf(); }
        windows = (WindowManager)getSystemService(WINDOW_SERVICE);
        NotificationManager notifications = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "远程悬浮小窗", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || CLOSE.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        result = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("result", ResultReceiver.class) : intent.getParcelableExtra("result");
        audio = intent.getBooleanExtra("audio", true);
        try {
            startForeground(NOTIFICATION, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            if (runtime == null || !runtime.adb.isConnected()) throw new IllegalStateException("ADB 未连接");
            if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("请先授予悬浮窗权限");
            if (window != null) { deliver(Activity.RESULT_OK, null); return START_NOT_STICKY; }
            createWindow(); if (audio) runtime.acquireAudioFocus(this);
        } catch (Exception e) { DebugLog.error("floating start", e); deliver(Activity.RESULT_CANCELED, e.getMessage()); stopSelf(); }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        PendingIntent expand = PendingIntent.getActivity(this, 1, new Intent(this, RemoteActivity.class).putExtra("audio", audio)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent close = PendingIntent.getService(this, 2, new Intent(this, FloatingWindowService.class).setAction(CLOSE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("远程小窗正在运行")
                .setContentText("点击恢复全屏，也可在设备与凭据中关闭小窗").setOngoing(true).setContentIntent(expand)
                .addAction(new Notification.Action.Builder(null, "恢复全屏", expand).build())
                .addAction(new Notification.Action.Builder(null, "关闭小窗", close).build()).build();
    }
    private Rect bounds() { return windows.getCurrentWindowMetrics().getBounds(); }
    private void createWindow() {
        window = new LinearLayout(this); window.setOrientation(LinearLayout.VERTICAL); window.setElevation(dp(12));
        window.setBackground(MainUi.background(Color.BLACK, Color.rgb(95, 119, 151), 12, this)); window.setPadding(dp(2), dp(2), dp(2), dp(2));
        TextView handle = new TextView(this); handle.setText("远程设备  ·  拖动移动"); handle.setTextSize(11); handle.setGravity(Gravity.CENTER);
        handle.setTextColor(Color.WHITE); handle.setBackgroundColor(0xFF182841); handle.setContentDescription("拖动悬浮窗");
        window.addView(handle, new LinearLayout.LayoutParams(-1, dp(28)));
        display = new RemoteDisplay(this, runtime, audio, this); window.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView resize = new TextView(this); resize.setText("—"); resize.setTextSize(12); resize.setTextColor(0xFF90A4BF); resize.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        resize.setContentDescription("拖动调整小窗大小"); window.addView(resize, new LinearLayout.LayoutParams(-1, dp(16)));
        position = new WindowManager.LayoutParams(dp(220), dp(420), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.setTitle("Scrcpy remote window");
        android.content.SharedPreferences prefs = getSharedPreferences("remote-ui", MODE_PRIVATE);
        position.width = prefs.getInt("windowWidth", Math.min(dp(220), bounds().width()/2)); position.x = prefs.getInt("windowX", dp(12)); position.y = prefs.getInt("windowY", dp(90));
        fitWindow();
        handle.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int startX, startY;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { x = e.getRawX(); y = e.getRawY(); startX = position.x; startY = position.y; display.releaseTouches(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) { position.x = startX + (int)(e.getRawX()-x); position.y = startY + (int)(e.getRawY()-y); clampPosition(); update(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_UP) { save(); v.performClick(); }
                return true;
            }
        });
        resize.setOnTouchListener(new View.OnTouchListener() {
            float start; int width;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { start = e.getRawX(); width = position.width; display.releaseTouches(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) { position.width = width + (int)(e.getRawX()-start); fitWindow(); update(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_UP) { save(); v.performClick(); }
                return true;
            }
        });
        windows.addView(window, position); DebugLog.write("floating window added");
    }
    private void fitWindow() {
        Rect available = bounds(); int maxWidth = Math.max(dp(120), available.width() - dp(16)), maxHeight = Math.max(dp(160), available.height() - dp(80));
        int minWidth = Math.min(dp(160), maxWidth);
        position.width = Math.max(minWidth, Math.min(maxWidth, position.width));
        int chrome = dp(48); position.height = Math.max(dp(130), Math.min(maxHeight, (int)((position.width-dp(4)) * remoteHeight / (float)remoteWidth) + chrome));
        clampPosition();
    }
    private void clampPosition() { Rect available = bounds(); position.x = Math.max(0, Math.min(position.x, Math.max(0, available.width()-position.width))); position.y = Math.max(dp(24), Math.min(position.y, Math.max(dp(24), available.height()-position.height-dp(24)))); }
    private void update() { if (window != null && window.isAttachedToWindow()) windows.updateViewLayout(window, position); }
    private void save() { getSharedPreferences("remote-ui", MODE_PRIVATE).edit().putInt("windowX", position.x).putInt("windowY", position.y).putInt("windowWidth", position.width).apply(); }
    private void deliver(int code, String error) {
        ResultReceiver receiver = result; result = null;
        if (receiver != null) { Bundle data = new Bundle(); data.putString("error", error); receiver.send(code, data); }
    }
    @Override public void ready(ScrcpySession session) { deliver(Activity.RESULT_OK, null); DebugLog.write("floating output attached"); }
    @Override public void status(String message) { DebugLog.write("floating " + message); }
    @Override public void size(int width, int height) { if (width > 0 && height > 0) { remoteWidth = width; remoteHeight = height; fitWindow(); update(); } }
    @Override public void clipboard(String text) { /* Small-window mode intentionally exposes only touch control. */ }
    @Override public void ended(String reason) { Toast.makeText(this, reason, Toast.LENGTH_LONG).show(); deliver(Activity.RESULT_CANCELED, reason); stopSelf(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); if (position != null) { fitWindow(); update(); } }
    @Override public void onDestroy() {
        deliver(Activity.RESULT_CANCELED, "小窗已关闭");
        if (display != null) display.dispose();
        if (window != null) { try { windows.removeViewImmediate(window); } catch (IllegalArgumentException ignored) { } window = null; }
        if (runtime != null) runtime.releaseAudioFocusIfIdle(this);
        stopForeground(STOP_FOREGROUND_REMOVE); if (instance == this) instance = null;
        DebugLog.write("floating window removed"); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
