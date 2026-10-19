package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

/** Interactive overlay with explicit chrome controls; hardware keys remain local. */
public class FloatingWindowService extends Service implements RemoteDisplay.Events {
    private static final String CHANNEL = "remote-window";
    private static final int NOTIFICATION = 42;
    private static final int BAR_HEIGHT_DP = 40, BORDER_DP = 2;
    static final String CLOSE = "org.scrcpy.android.CLOSE_WINDOW";
    private static FloatingWindowService instance;
    private RuntimeState runtime;
    private WindowManager windows;
    private WindowManager.LayoutParams position;
    private LinearLayout window;
    RemoteDisplay display;
    MaterialButton powerButton, fullscreenButton;
    RemoteKeyActions powerKeys;
    ImageView leftResizeGrip, rightResizeGrip;
    TextView deviceLabel;
    LinearLayout header, footer;
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
        Context appearance = new ContextThemeWrapper(this, R.style.Theme_Scrcpy_Window);
        window = new LinearLayout(appearance); window.setOrientation(LinearLayout.VERTICAL); window.setElevation(dp(12));
        MaterialShapeDrawable frame = new MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(dp(12)).build());
        frame.setFillColor(ColorStateList.valueOf(Color.BLACK)); frame.setStroke(dp(1), Color.rgb(95, 119, 151));
        window.setBackground(frame); window.setClipToOutline(true); window.setClipChildren(true);
        window.setPadding(dp(BORDER_DP), dp(BORDER_DP), dp(BORDER_DP), dp(BORDER_DP));
        deviceLabel = new TextView(appearance); deviceLabel.setText("Android"); deviceLabel.setTextSize(12); deviceLabel.setGravity(Gravity.CENTER_VERTICAL);
        deviceLabel.setSingleLine(true); deviceLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        deviceLabel.setMinWidth(0); deviceLabel.setPadding(dp(10), 0, dp(4), 0); deviceLabel.setTextColor(Color.WHITE);
        deviceLabel.setContentDescription("Android，拖动移动小窗");
        header = new LinearLayout(appearance); header.setGravity(Gravity.CENTER_VERTICAL); header.setBackgroundColor(0xFF182841);
        // Weight 1 + zero width leaves exactly the space not occupied by the
        // power icon. TextView recomputes end ellipsis on every window resize.
        header.addView(deviceLabel, new LinearLayout.LayoutParams(0, dp(BAR_HEIGHT_DP), 1));
        powerButton = iconButton(appearance, R.drawable.ic_window_power, "电源：点按切换屏幕，长按发送长按电源");
        header.addView(powerButton, new LinearLayout.LayoutParams(dp(40), dp(BAR_HEIGHT_DP)));
        window.addView(header, new LinearLayout.LayoutParams(-1, dp(BAR_HEIGHT_DP)));
        display = new RemoteDisplay(this, runtime, audio, this); window.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
        powerKeys = new RemoteKeyActions(() -> display.ownsSession() ? runtime.session : null);
        powerButton.setOnClickListener(v -> powerKeys.pulse(80, KeyEvent.KEYCODE_POWER));
        powerButton.setOnLongClickListener(v -> { powerKeys.pulse(800, KeyEvent.KEYCODE_POWER); Toast.makeText(this, "已发送长按电源", Toast.LENGTH_SHORT).show(); return true; });
        footer = new LinearLayout(appearance); footer.setGravity(Gravity.CENTER_VERTICAL); footer.setBackgroundColor(0xFF182841);
        leftResizeGrip = resizeGrip(appearance, true); rightResizeGrip = resizeGrip(appearance, false);
        fullscreenButton = iconButton(appearance, R.drawable.ic_window_fullscreen, "恢复全屏远程控制");
        footer.addView(leftResizeGrip, new LinearLayout.LayoutParams(dp(40), dp(BAR_HEIGHT_DP)));
        footer.addView(fullscreenButton, new LinearLayout.LayoutParams(0, dp(BAR_HEIGHT_DP), 1));
        footer.addView(rightResizeGrip, new LinearLayout.LayoutParams(dp(40), dp(BAR_HEIGHT_DP)));
        window.addView(footer, new LinearLayout.LayoutParams(-1, dp(BAR_HEIGHT_DP)));
        fullscreenButton.setOnClickListener(v -> restoreFullscreen());
        installResize(leftResizeGrip, true); installResize(rightResizeGrip, false);
        position = new WindowManager.LayoutParams(dp(220), dp(420), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.alpha = UiPreferences.opacity(this, UiPreferences.WINDOW);
        position.setTitle("Scrcpy remote window");
        android.content.SharedPreferences prefs = getSharedPreferences("remote-ui", MODE_PRIVATE);
        position.width = prefs.getInt("windowWidth", Math.min(dp(220), bounds().width()/2)); position.x = prefs.getInt("windowX", dp(12)); position.y = prefs.getInt("windowY", dp(90));
        fitWindow();
        deviceLabel.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int startX, startY;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { x = e.getRawX(); y = e.getRawY(); startX = position.x; startY = position.y; display.releaseTouches(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) { position.x = startX + (int)(e.getRawX()-x); position.y = startY + (int)(e.getRawY()-y); clampPosition(); update(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_UP) { save(); v.performClick(); }
                return true;
            }
        });
        windows.addView(window, position); loadDeviceName(); DebugLog.write("floating window added");
    }
    private MaterialButton iconButton(Context context, int drawable, String description) {
        MaterialButton button = new MaterialButton(context); button.setText(""); button.setIconResource(drawable);
        button.setIconSize(dp(22)); button.setIconPadding(0); button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconTint(ColorStateList.valueOf(Color.WHITE)); button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setRippleColor(ColorStateList.valueOf(0x33FFFFFF)); button.setCornerRadius(dp(8)); button.setStrokeWidth(0);
        button.setInsetTop(0); button.setInsetBottom(0); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setPadding(0, 0, 0, 0); button.setStateListAnimator(null);
        button.setContentDescription(description); button.setTooltipText(description); return button;
    }
    private ImageView resizeGrip(Context context, boolean left) {
        ImageView grip = new ImageView(context); grip.setImageResource(R.drawable.ic_window_resize); grip.setPadding(dp(8), dp(8), dp(6), dp(6));
        grip.setScaleType(ImageView.ScaleType.FIT_END); if (left) grip.setScaleX(-1);
        grip.setContentDescription(left ? "拖动左下角等比缩放" : "拖动右下角等比缩放");
        grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); return grip;
    }
    private void loadDeviceName() {
        String target = runtime.target;
        // A name query never blocks Surface handoff, the UI or the shared worker.
        new Thread(() -> {
            String name = "Android";
            try { name = RemoteDeviceName.parse(runtime.shell("settings get global device_name 2>/dev/null; getprop ro.product.model")); }
            catch (Exception error) { DebugLog.write("floating device name unavailable: " + error.getMessage()); }
            String label = name;
            new Handler(getMainLooper()).post(() -> {
                if (instance != this || window == null || !target.equals(runtime.target)) return;
                deviceLabel.setText(label); deviceLabel.setContentDescription(label + "，拖动移动小窗");
                DebugLog.write("floating device name loaded chars=" + label.length());
            });
        }, "floating-device-name").start();
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
    void restoreFullscreen() {
        if (expanding) return; expanding = true; powerKeys.release(); display.releaseTouches();
        try { startActivity(new Intent(this, RemoteActivity.class).putExtra("audio", audio).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
        catch (RuntimeException error) { expanding = false; DebugLog.error("floating restore", error); Toast.makeText(this, "恢复全屏失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    Rect windowBounds() { return new Rect(position.x, position.y, position.x + position.width, position.y + position.height); }
    float windowOpacity() { return position.alpha; }
    View windowView() { return window; }
    private void fitWindow() {
        Rect available = bounds(); int maxWidth = Math.max(dp(120), available.width() - dp(16)), maxHeight = Math.max(dp(160), available.height() - dp(80));
        int[] size = WindowGeometry.fit(position.width, remoteWidth, remoteHeight, dp(160), maxWidth, maxHeight,
                dp(BORDER_DP * 2), dp(BAR_HEIGHT_DP * 2 + BORDER_DP * 2));
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
