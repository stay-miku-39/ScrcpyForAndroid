package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

public class RemoteActivity extends androidx.activity.ComponentActivity implements RemoteDisplay.Events {
    private RuntimeState runtime;
    ScrcpySession session;
    SurfaceView video;
    RemoteDisplay display;
    Button ball;
    private FrameLayout root;
    private TextView status;
    private PopupWindow menu;
    private ClipboardManager clipboard;
    private String lastClipboard = "";
    private boolean resumed, pendingFloating, awaitingPermission, awaitingNotifications, switchingWindow;
    private boolean syncClipboard = true, syncVolume = true;
    private final Set<Integer> heldKeys = new HashSet<>();
    private float ballX = 1, ballY = .4f;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> sendClipboard(false);

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { runtime = RuntimeState.get(this); } catch (Exception e) { finish(); return; }
        android.content.SharedPreferences prefs = getSharedPreferences("remote-ui", MODE_PRIVATE);
        syncClipboard = prefs.getBoolean("clipboard", true); syncVolume = prefs.getBoolean("volumeKeys", true);
        ballX = prefs.getFloat("ballX", 1); ballY = prefs.getFloat("ballY", .4f);
        pendingFloating = saved == null && getIntent().getBooleanExtra("floating", false);
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK); setContentView(root);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        display = new RemoteDisplay(this, runtime, getIntent().getBooleanExtra("audio", true), this); video = display.video;
        root.addView(display, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(14); status.setText("正在连接…");
        status.setBackgroundColor(0xB0182841); status.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        ball = new Button(this); ball.setText("⋮"); ball.setTextSize(26); ball.setPadding(0, 0, 0, 0); ball.setMinWidth(0); ball.setMinimumWidth(0);
        ball.setTextColor(Color.WHITE); ball.setContentDescription("远程控制菜单，拖动可移动");
        ball.setBackground(MainUi.background(0xE62463E1, 0, 28, this));
        root.addView(ball, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.LEFT));
        ball.setOnClickListener(v -> toggleMenu());
        ball.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY; boolean moved;
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); startX = ball.getX(); startY = ball.getY(); moved = false; return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true;
                    if (moved) {
                        dismissMenu(); ballX = clamp((startX + dx) / Math.max(1, root.getWidth() - dp(48)));
                        ballY = clamp((startY + dy) / Math.max(1, root.getHeight() - dp(48))); positionBall();
                    } return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) { if (!moved) ball.performClick(); saveUi(); return true; }
                return true;
            }
        });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { positionBall(); if (r-l != or-ol || b-t != ob-ot) dismissMenu(); });
        root.setOnApplyWindowInsetsListener((v, insets) -> { positionBall(); return insets; });
        clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { remoteBack(); }
        });
    }
    private int dp(float n) { return MainUi.dp(this, n); }
    private static float clamp(float n) { return Math.max(0, Math.min(1, n)); }
    private void positionBall() {
        if (ball == null || root.getWidth() == 0) return;
        android.graphics.Insets safe = root.getRootWindowInsets() == null ? android.graphics.Insets.NONE
                : root.getRootWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int left = Math.max(dp(8), safe.left), top = Math.max(dp(8), safe.top);
        ball.setX(left + ballX * Math.max(0, root.getWidth() - left - Math.max(dp(8), safe.right) - dp(48)));
        ball.setY(top + ballY * Math.max(0, root.getHeight() - top - Math.max(dp(8), safe.bottom) - dp(48)));
    }
    private void saveUi() { getSharedPreferences("remote-ui", MODE_PRIVATE).edit().putFloat("ballX", ballX).putFloat("ballY", ballY).putBoolean("clipboard", syncClipboard).putBoolean("volumeKeys", syncVolume).apply(); }
    private void toggleMenu() {
        if (menu != null && menu.isShowing()) { dismissMenu(); return; }
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(MainUi.background(Color.WHITE, 0, 18, this));
        TextView title = new TextView(this); title.setText("远程控制"); title.setTextSize(19); title.setTextColor(MainUi.INK); panel.addView(title);
        addRow(panel, new String[]{"返回", "主页", "最近"}, new Runnable[]{() -> key(KeyEvent.KEYCODE_BACK), () -> key(KeyEvent.KEYCODE_HOME), () -> key(KeyEvent.KEYCODE_APP_SWITCH)});
        addRow(panel, new String[]{"音量+", "音量−", "旋转"}, new Runnable[]{() -> key(KeyEvent.KEYCODE_VOLUME_UP), () -> key(KeyEvent.KEYCODE_VOLUME_DOWN), () -> { if (session != null) session.send(new byte[]{11}); }});
        addRow(panel, new String[]{"粘贴", "获取剪贴板"}, new Runnable[]{() -> sendClipboard(true), () -> { if (session != null) session.getClipboard(); }});
        CheckBox clip = new CheckBox(this); clip.setText("同步文本剪贴板"); clip.setTextColor(MainUi.INK); clip.setChecked(syncClipboard);
        clip.setButtonTintList(android.content.res.ColorStateList.valueOf(MainUi.BLUE));
        clip.setOnCheckedChangeListener((v,checked) -> { syncClipboard = checked; saveUi(); }); panel.addView(clip);
        CheckBox volume = new CheckBox(this); volume.setText("同步音量实体键"); volume.setTextColor(MainUi.INK); volume.setChecked(syncVolume);
        volume.setButtonTintList(android.content.res.ColorStateList.valueOf(MainUi.BLUE));
        volume.setOnCheckedChangeListener((v,checked) -> { releaseKeys(); syncVolume = checked; saveUi(); }); panel.addView(volume);
        addRow(panel, new String[]{"悬浮小窗", "退出控制"}, new Runnable[]{this::requestFloatingWindow, this::finish});
        ScrollView scroll = new ScrollView(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) { return forwardVolumeKey(event) || super.dispatchKeyEvent(event); }
        }; scroll.addView(panel);
        int width = Math.min(dp(324), root.getWidth() - dp(24));
        menu = new PopupWindow(scroll, width, Math.min(dp(400), Math.max(dp(180), root.getHeight() - dp(100))), true);
        menu.setBackgroundDrawable(MainUi.background(Color.WHITE, 0, 18, this)); menu.setOutsideTouchable(true); menu.setElevation(dp(14));
        int x = Math.max(dp(12), Math.min((int)ball.getX() - width + dp(48), root.getWidth() - width - dp(12)));
        int y = Math.max(dp(32), Math.min((int)ball.getY(), root.getHeight() - menu.getHeight() - dp(24)));
        menu.showAtLocation(root, Gravity.TOP | Gravity.LEFT, x, y);
    }
    private void addRow(LinearLayout panel, String[] labels, Runnable[] actions) {
        LinearLayout row = new LinearLayout(this); panel.addView(row);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this); b.setText(labels[i]); b.setTextSize(13); b.setMinWidth(0); b.setPadding(dp(3), 0, dp(3), 0);
            b.setTextColor(MainUi.BLUE); b.setAllCaps(false); Runnable action = actions[i];
            b.setBackground(MainUi.background(0xFFE8EFFC, 0, 8, this)); b.setStateListAnimator(null);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1); params.setMargins(dp(2), dp(3), dp(2), dp(3));
            b.setOnClickListener(v -> { dismissMenu(); action.run(); }); row.addView(b, params);
        }
    }
    private void dismissMenu() { if (menu != null) { menu.dismiss(); menu = null; } }
    private void key(int code) { if (session != null && display.ownsSession()) session.key(code); }
    // Local predictive back is registered with OnBackPressedDispatcher above.
    // This KEYCODE_BACK is serialized for the remote device, not intercepted locally.
    @android.annotation.SuppressLint("GestureBackNavigation")
    private void remoteBack() { if (menu != null && menu.isShowing()) dismissMenu(); else key(KeyEvent.KEYCODE_BACK); }
    // Public Activity/Window callback; AndroidX core's inherited bridge carries
    // an overly broad RestrictTo annotation. Intercept before focused views.
    @android.annotation.SuppressLint("RestrictedApi")
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return forwardVolumeKey(event) || super.dispatchKeyEvent(event); }
    private boolean forwardVolumeKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (syncVolume && resumed && display != null && display.ownsSession() && session != null
                && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
                && (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) heldKeys.add(code); else heldKeys.remove(code);
            DebugLog.write("volume key action=" + event.getAction() + " code=" + code + " repeat=" + event.getRepeatCount());
            session.send(ControlProtocol.key(event.getAction(), code, event.getRepeatCount(), event.getMetaState())); return true;
        }
        return false;
    }
    private void releaseKeys() { if (session != null) for (int code : heldKeys) session.send(ControlProtocol.key(KeyEvent.ACTION_UP, code)); heldKeys.clear(); }
    void requestFloatingWindow() {
        if (switchingWindow || session == null || !display.ownsSession()) return;
        dismissMenu(); releaseKeys(); display.releaseTouches();
        if (!Settings.canDrawOverlays(this)) {
            awaitingPermission = true;
            Toast.makeText(this, "请允许在其他应用上层显示", Toast.LENGTH_LONG).show();
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName())), 20); return;
        }
        android.content.SharedPreferences permissions = getSharedPreferences("remote-ui", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !permissions.getBoolean("notificationsRequested", false)) {
            permissions.edit().putBoolean("notificationsRequested", true).apply(); awaitingNotifications = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 21); return;
        }
        switchingWindow = true;
        Intent intent = new Intent(this, FloatingWindowService.class).putExtra("audio", getIntent().getBooleanExtra("audio", true));
        intent.putExtra("result", new ResultReceiver(new Handler(getMainLooper())) {
            @Override protected void onReceiveResult(int result, Bundle data) {
                switchingWindow = false;
                if (result == RESULT_OK) finish(); else status(data == null ? "小窗启动失败" : data.getString("error"));
            }
        });
        try { startForegroundService(intent); }
        catch (RuntimeException e) { switchingWindow = false; status("小窗启动失败：" + e.getMessage()); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20) { awaitingPermission = false; pendingFloating = Settings.canDrawOverlays(this); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 21) {
            awaitingNotifications = false;
            if (results.length == 0 || results[0] != android.content.pm.PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "通知未启用，可在设备与凭据页恢复全屏或关闭小窗", Toast.LENGTH_LONG).show();
            if (display.ownsSession() && session != null && !session.isClosed()) requestFloatingWindow(); else pendingFloating = true;
        }
    }
    @Override public void ready(ScrcpySession attached) {
        session = attached;
        if (attached.width > 0) { status("已连接：" + runtime.target); if (syncClipboard) attached.getClipboard(); }
        if (FloatingWindowService.isShowing()) stopService(new Intent(this, FloatingWindowService.class));
        if (pendingFloating) { pendingFloating = false; requestFloatingWindow(); }
    }
    @Override public void status(String message) {
        status.setText(message); status.setVisibility(View.VISIBLE);
        if (message.startsWith("已连接")) status.postDelayed(() -> status.setVisibility(View.GONE), 1800);
    }
    @Override public void size(int width, int height) { }
    private void sendClipboard(boolean paste) {
        if (!resumed || session == null || !display.ownsSession() || (!paste && !syncClipboard) || !clipboard.hasPrimaryClip()) return;
        ClipData data = clipboard.getPrimaryClip(); if (data == null || data.getItemCount() == 0) return;
        CharSequence text = data.getItemAt(0).getText(); if (text == null) return;
        String value = text.toString(); if (!paste && value.equals(lastClipboard)) return;
        try { session.clipboard(value, paste); lastClipboard = value; DebugLog.write("clipboard sent chars=" + value.length()); }
        catch (IllegalArgumentException e) { status(e.getMessage()); }
    }
    @Override public void clipboard(String text) {
        if (!resumed || isFinishing() || !syncClipboard || text.equals(lastClipboard)) return;
        lastClipboard = text; ClipData current = clipboard.getPrimaryClip();
        if (current != null && current.getItemCount() > 0 && text.contentEquals(current.getItemAt(0).getText())) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("远程设备", text));
    }
    @Override public void ended(String reason) { if (!isFinishing()) new AlertDialog.Builder(this).setMessage(reason).setPositiveButton("返回", (d,w) -> finish()).show(); }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipListener);
        if (runtime != null && getIntent().getBooleanExtra("audio", true)) runtime.acquireAudioFocus(this);
    }
    @Override protected void onPause() { resumed = false; releaseKeys(); if (display != null) display.releaseTouches(); if (clipboard != null) clipboard.removePrimaryClipChangedListener(clipListener); super.onPause(); }
    @Override protected void onStop() { if (!awaitingPermission && !awaitingNotifications) { if (display != null) display.dispose(); if (!isFinishing()) finish(); } super.onStop(); }
    @Override protected void onDestroy() { dismissMenu(); releaseKeys(); if (display != null) display.dispose(); if (runtime != null) runtime.releaseAudioFocusIfIdle(this); super.onDestroy(); }
}
