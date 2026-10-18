package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Interactive overlay with explicit chrome controls; hardware keys remain local. */
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
    Button powerButton;
    RemoteKeyActions powerKeys;
    Button shrinkButton, expandButton, fullscreenButton;
    private LinearLayout header, footer;
    private boolean expanding;
    private ResultReceiver result;
    private int remoteWidth = 720, remoteHeight = 1600;
    private boolean audio;
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceListener = (prefs, key) -> {
        if (UiPreferences.WINDOW.equals(key)) new Handler(getMainLooper()).post(() -> {
            if (position != null) { position.alpha = UiPreferences.opacity(this, UiPreferences.WINDOW); update(); }
        });
    };
    static boolean isShowing() { return instance != null && instance.window != null; }
    static FloatingWindowService current() { return instance; }
    private int dp(float value) { return MainUi.dp(this, value); }

    @Override public void onCreate() {
        super.onCreate(); instance = this;
        try { runtime = RuntimeState.get(this); }
        catch (Exception e) { DebugLog.error("floating init", e); stopSelf(); }
        windows = (WindowManager)getSystemService(WINDOW_SERVICE);
        UiPreferences.get(this).registerOnSharedPreferenceChangeListener(appearanceListener);
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
        handle.setSingleLine(true); handle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        handle.setTextColor(Color.WHITE); handle.setBackgroundColor(0xFF182841); handle.setContentDescription("拖动悬浮窗");
        header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setBackgroundColor(0xFF182841);
        header.addView(handle, new LinearLayout.LayoutParams(0, dp(40), 1));
        powerButton = new Button(this); powerButton.setText("电源"); powerButton.setTextSize(12); powerButton.setTextColor(Color.WHITE);
        powerButton.setMinWidth(0); powerButton.setPadding(0, 0, 0, 0); powerButton.setBackgroundColor(Color.TRANSPARENT);
        powerButton.setContentDescription("电源：点按切换屏幕，长按发送长按电源");
        header.addView(powerButton, new LinearLayout.LayoutParams(dp(44), dp(40))); window.addView(header);
        display = new RemoteDisplay(this, runtime, audio, this); window.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
        powerKeys = new RemoteKeyActions(() -> display.ownsSession() ? runtime.session : null);
        powerButton.setOnClickListener(v -> powerKeys.pulse(80, KeyEvent.KEYCODE_POWER));
        powerButton.setOnLongClickListener(v -> { powerKeys.pulse(800, KeyEvent.KEYCODE_POWER); Toast.makeText(this, "已发送长按电源", Toast.LENGTH_SHORT).show(); return true; });
        footer = new LinearLayout(this); footer.setGravity(Gravity.CENTER_VERTICAL); footer.setBackgroundColor(0xFF182841);
        shrinkButton = chromeButton("−", "缩小；拖动左下角等比缩放");
        expandButton = chromeButton("+", "放大；拖动右下角等比缩放");
        fullscreenButton = chromeButton("全屏", "恢复全屏远程控制"); fullscreenButton.setTextSize(13);
        footer.addView(shrinkButton, new LinearLayout.LayoutParams(dp(44), dp(48)));
        footer.addView(fullscreenButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        footer.addView(expandButton, new LinearLayout.LayoutParams(dp(44), dp(48)));
        window.addView(footer, new LinearLayout.LayoutParams(-1, dp(48)));
        shrinkButton.setOnClickListener(v -> scaleWindow(1 / 1.12f)); expandButton.setOnClickListener(v -> scaleWindow(1.12f));
        fullscreenButton.setOnClickListener(v -> restoreFullscreen());
        installResize(shrinkButton, true); installResize(expandButton, false);
        position = new WindowManager.LayoutParams(dp(220), dp(420), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.alpha = UiPreferences.opacity(this, UiPreferences.WINDOW);
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
        windows.addView(window, position); DebugLog.write("floating window added");
    }
    private Button chromeButton(String text, String description) {
        Button button = new Button(this); button.setText(text); button.setTextSize(21); button.setAllCaps(false);
        button.setTextColor(Color.WHITE); button.setMinWidth(0); button.setPadding(0, 0, 0, 0);
        button.setBackgroundColor(Color.TRANSPARENT); button.setContentDescription(description); return button;
    }
    private void installResize(View corner, boolean left) {
        corner.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int width, right, top; boolean dragging;
            public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    x = event.getRawX(); y = event.getRawY(); width = position.width; right = position.x + position.width; top = position.y;
                    dragging = false; display.releaseTouches(); return true;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - x, dy = event.getRawY() - y;
                    if (Math.abs(dx) + Math.abs(dy) > dp(6)) dragging = true;
                    if (dragging) {
                        position.width = width + WindowGeometry.cornerDelta(dx, dy, remoteHeight / (double)remoteWidth, left);
                        fitWindow(); position.y = top; if (left) position.x = right - position.width;
                        clampPosition(); update();
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP) { if (dragging) save(); else view.performClick(); return true; }
                if (action == MotionEvent.ACTION_CANCEL) { if (dragging) save(); return true; }
                return true;
            }
        });
    }
    private void scaleWindow(float factor) {
        display.releaseTouches(); int centerX = position.x + position.width / 2, centerY = position.y + position.height / 2;
        position.width = Math.round(position.width * factor); fitWindow();
        position.x = centerX - position.width / 2; position.y = centerY - position.height / 2; clampPosition(); update(); save();
    }
    void restoreFullscreen() {
        if (expanding) return; expanding = true; powerKeys.release(); display.releaseTouches();
        try { startActivity(new Intent(this, RemoteActivity.class).putExtra("audio", audio).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
        catch (RuntimeException error) { expanding = false; DebugLog.error("floating restore", error); Toast.makeText(this, "恢复全屏失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    Rect windowBounds() { return new Rect(position.x, position.y, position.x + position.width, position.y + position.height); }
    float windowOpacity() { return position.alpha; }
    private void fitWindow() {
        Rect available = bounds(); int maxWidth = Math.max(dp(120), available.width() - dp(16)), maxHeight = Math.max(dp(160), available.height() - dp(80));
        int[] size = WindowGeometry.fit(position.width, remoteWidth, remoteHeight, dp(160), maxWidth, maxHeight, dp(4), dp(92));
        position.width = size[0]; position.height = size[1];
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
        UiPreferences.get(this).unregisterOnSharedPreferenceChangeListener(appearanceListener);
        if (powerKeys != null) powerKeys.release();
        deliver(Activity.RESULT_CANCELED, "小窗已关闭");
        if (display != null) display.dispose();
        if (window != null) { try { windows.removeViewImmediate(window); } catch (IllegalArgumentException ignored) { } window = null; }
        if (runtime != null) runtime.releaseAudioFocusIfIdle(this);
        stopForeground(STOP_FOREGROUND_REMOVE); if (instance == this) instance = null;
        DebugLog.write("floating window removed"); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
