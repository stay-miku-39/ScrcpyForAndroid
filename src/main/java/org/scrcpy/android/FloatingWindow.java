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
final class FloatingWindow extends ContextWrapper implements RemoteDisplay.Events {
    private static final int BAR_HEIGHT_DP = 40, BORDER_DP = 2;
    final DeviceConnection runtime;
    private final FloatingWindowService service;
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
    private int dp(float value) { return MainUi.dp(this, value); }
    FloatingWindow(FloatingWindowService service, DeviceConnection runtime, boolean audio, ResultReceiver result) {
        super(service); this.service = service; this.runtime = runtime; this.audio = audio; this.result = result;
        windows = (WindowManager)getSystemService(WINDOW_SERVICE);
        UiPreferences.get(this).registerOnSharedPreferenceChangeListener(appearanceListener);
        createWindow();
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
        deviceLabel.setContentDescription("Android，拖动移动小窗，向屏幕边缘甩出关闭");
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
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.alpha = UiPreferences.opacity(this, UiPreferences.WINDOW);
        position.setTitle("Scrcpy remote window " + runtime.target);
        android.content.SharedPreferences prefs = getSharedPreferences("remote-ui", MODE_PRIVATE);
        position.width = prefs.getInt("windowWidth", Math.min(dp(220), bounds().width()/2)); position.x = prefs.getInt("windowX", dp(12)); position.y = prefs.getInt("windowY", dp(90)) + dp(36) * service.windowCount();
        position.x += dp(28) * service.windowCount();
        fitWindow();
        deviceLabel.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int startX, startY; boolean dragging; VelocityTracker velocity;
            public boolean onTouch(View v, MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    x = e.getRawX(); y = e.getRawY(); startX = position.x; startY = position.y; dragging = false;
                    if (velocity != null) velocity.recycle(); velocity = VelocityTracker.obtain(); display.releaseTouches(); powerKeys.release();
                }
                // Window-relative coordinates move with the window. Track screen coordinates instead.
                if (velocity != null) {
                    MotionEvent screen = MotionEvent.obtain(e); screen.offsetLocation(e.getRawX() - e.getX(), e.getRawY() - e.getY());
                    velocity.addMovement(screen); screen.recycle();
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    if (Math.hypot(e.getRawX()-x, e.getRawY()-y) > ViewConfiguration.get(FloatingWindow.this).getScaledTouchSlop()) dragging = true;
                    if (dragging) { position.x = startX + Math.round(e.getRawX()-x); position.y = startY + Math.round(e.getRawY()-y); update(); }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (velocity != null) velocity.computeCurrentVelocity(1000, dp(8000));
                    Rect area = bounds();
                    boolean dismiss = action == MotionEvent.ACTION_UP && dragging && velocity != null
                            && WindowGeometry.dismiss(position.x, position.y, position.width, position.height, area.width(), area.height(),
                            velocity.getXVelocity(), velocity.getYVelocity(), dp(1000), dp(48));
                    if (velocity != null) { velocity.recycle(); velocity = null; }
                    if (dismiss) { service.closeWindow(runtime.target); return true; }
                    clampPosition(); update(); save(); if (!dragging && action == MotionEvent.ACTION_UP) v.performClick();
                }
                return true;
            }
        });
        deviceLabel.setTooltipText("拖动移动，向屏幕边缘甩出可关闭小窗");
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
                if (window == null || !runtime.connected || service.window(runtime.target) != this) return;
                deviceLabel.setText(label); deviceLabel.setContentDescription(label + "，拖动移动小窗，向屏幕边缘甩出关闭");
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
        try { startActivity(RemoteActivity.intent(this, runtime.target, audio)); }
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
    @Override public void ended(String reason) { Toast.makeText(this, reason, Toast.LENGTH_LONG).show(); deliver(Activity.RESULT_CANCELED, reason); service.closeWindow(runtime.target); }
    void configurationChanged() { if (position != null) { fitWindow(); update(); } }
    void close() {
        UiPreferences.get(this).unregisterOnSharedPreferenceChangeListener(appearanceListener);
        if (powerKeys != null) powerKeys.release();
        deliver(Activity.RESULT_CANCELED, "小窗已关闭");
        if (display != null) display.dispose();
        if (window != null) { try { windows.removeViewImmediate(window); } catch (IllegalArgumentException ignored) { } window = null; }
        
        DebugLog.write("floating window removed target=" + runtime.target);
    }

}
