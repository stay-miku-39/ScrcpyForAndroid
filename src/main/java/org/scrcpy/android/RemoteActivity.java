package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

public class RemoteActivity extends androidx.activity.ComponentActivity implements RemoteDisplay.Events {
    private RuntimeState runtime;
    DeviceConnection device;
    private boolean userLeaving;
    private int outputGeneration;
    ScrcpySession session;
    SurfaceView video;
    RemoteDisplay display;
    Button ball;
    QualityOverlay qualityOverlay;
    RemoteKeyActions powerKeys;
    private FrameLayout root;
    private TextView status;
    private PopupWindow menu;
    private ClipboardManager clipboard;
    private String lastClipboard = "";
    private boolean resumed, pendingFloating, awaitingPermission, awaitingNotifications, switchingWindow;
    private boolean syncClipboard = true, syncVolume = true;
    private boolean autoRotate = true;
    private RemoteRotationMonitor rotationMonitor;
    private RemoteOrientation.Info rotationInfo;
    private long lastRotationUpdate = -1;
    private int sizeGeneration, monitorGeneration;
    MicrophoneForwarder microphone;
    private boolean selectingMicrophoneTarget, awaitingMicrophonePermission;
    private String pendingMicrophoneTarget;
    private final Set<Integer> heldKeys = new HashSet<>();
    private float ballX = 1, ballY = .4f;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> sendClipboard(false);
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceListener = (prefs, key) -> runOnUiThread(this::applyAppearance);

    static Intent intent(Context context, String target, boolean audio) {
        return new Intent(context, RemoteActivity.class).putExtra("target", target).putExtra("audio", audio)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    private DeviceConnection requestedDevice(Intent intent) {
        String target = intent.getStringExtra("target"); return target == null ? runtime.latest() : runtime.connection(target);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        DeviceConnection next = requestedDevice(intent);
        if (next == null || !next.connected) { status("该 ADB 已断开"); return; }
        setIntent(intent);
        if (next == device) { display.resumeOutput(); return; }
        ++outputGeneration;
        dismissMenu(); stopMicrophone(); stopRotationMonitor(); powerKeys.release(); releaseKeys();
        display.dispose(); root.removeView(display);
        device = next; session = null; lastClipboard = ""; rotationInfo = null; lastRotationUpdate = -1; switchingWindow = false;
        display = new RemoteDisplay(this, device, intent.getBooleanExtra("audio", true), this); video = display.video;
        root.addView(display, 0, new FrameLayout.LayoutParams(-1, -1));
        status("正在连接：" + device.target);
        updateQuality();
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { runtime = RuntimeState.get(this); } catch (Exception e) { finish(); return; }
        device = requestedDevice(getIntent());
        if (device == null || !device.connected) { finish(); return; }
        runtime.fullscreen = this;
        try { FloatingWindowService.keep(this); } catch (RuntimeException e) { statusServiceFailure(e); finish(); return; }
        android.content.SharedPreferences prefs = getSharedPreferences("remote-ui", MODE_PRIVATE);
        syncClipboard = prefs.getBoolean("clipboard", true); syncVolume = prefs.getBoolean("volumeKeys", true);
        autoRotate = prefs.getBoolean("autoRotate", true);
        ballX = prefs.getFloat("ballX", 1); ballY = prefs.getFloat("ballY", .4f);
        pendingFloating = saved == null && getIntent().getBooleanExtra("floating", false);
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK); setContentView(root);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        display = new RemoteDisplay(this, device, getIntent().getBooleanExtra("audio", true), this); video = display.video;
        powerKeys = new RemoteKeyActions(() -> display.ownsSession() ? session : null);
        root.addView(display, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(14); status.setText("正在连接…");
        status.setBackgroundColor(0xB0182841); status.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        qualityOverlay = new QualityOverlay(this);
        FrameLayout.LayoutParams qualityPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        qualityPosition.setMargins(dp(8), dp(8), dp(8), dp(8)); root.addView(qualityOverlay, qualityPosition);
        ball = new Button(this); ball.setText("⋮"); ball.setTextSize(26); ball.setPadding(0, 0, 0, 0); ball.setMinWidth(0); ball.setMinimumWidth(0);
        ball.setTextColor(Color.WHITE); ball.setContentDescription("远程控制菜单，拖动可移动");
        ball.setBackground(MainUi.background(0xE62463E1, 0, 28, this));
        root.addView(ball, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.LEFT));
        applyAppearance(); UiPreferences.get(this).registerOnSharedPreferenceChangeListener(appearanceListener);
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
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { positionBall(); if (r-l != or-ol || b-t != ob-ot) { if (menu != null) DebugLog.write("menu dismissed on root resize " + (or-ol) + "x" + (ob-ot) + " -> " + (r-l) + "x" + (b-t)); dismissMenu(); } });
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
        if (qualityOverlay != null) {
            FrameLayout.LayoutParams position = (FrameLayout.LayoutParams)qualityOverlay.getLayoutParams();
            int qualityLeft = safe.left + dp(8), qualityTop = safe.top + dp(8);
            if (position.leftMargin != qualityLeft || position.topMargin != qualityTop) { position.leftMargin = qualityLeft; position.topMargin = qualityTop; qualityOverlay.setLayoutParams(position); }
            qualityOverlay.setMaxWidth(Math.max(dp(120), root.getWidth() - qualityLeft - safe.right - dp(8)));
        }
    }
    private void saveUi() { getSharedPreferences("remote-ui", MODE_PRIVATE).edit().putFloat("ballX", ballX).putFloat("ballY", ballY).putBoolean("clipboard", syncClipboard).putBoolean("volumeKeys", syncVolume).apply(); }
    private void toggleMenu() {
        if (menu != null && menu.isShowing()) { dismissMenu(); return; }
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(MainUi.background(Color.WHITE, 0, 18, this));
        TextView title = new TextView(this); title.setText("远程控制"); title.setTextSize(19); title.setTextColor(MainUi.INK); panel.addView(title);
        addRow(panel, new String[]{"返回", "主页", "最近"}, new Runnable[]{() -> key(KeyEvent.KEYCODE_BACK), () -> key(KeyEvent.KEYCODE_HOME), () -> key(KeyEvent.KEYCODE_APP_SWITCH)});
        addRow(panel, new String[]{"音量+", "音量−", "旋转"}, new Runnable[]{() -> key(KeyEvent.KEYCODE_VOLUME_UP), () -> key(KeyEvent.KEYCODE_VOLUME_DOWN), () -> { if (session != null) session.send(new byte[]{11}); }});
        addRow(panel, new String[]{"电源与唤醒"}, new Runnable[]{this::showPowerMenu});
        addRow(panel, new String[]{"质量参数"}, new Runnable[]{this::showQualityMenu});
        addRow(panel, new String[]{"通知栏", "快捷设置", "收起通知"}, new Runnable[]{() -> panelCommand(5), () -> panelCommand(6), () -> panelCommand(7)});
        CheckBox rotation = new CheckBox(this); rotation.setText("自动屏幕旋转"); rotation.setTextColor(MainUi.INK); rotation.setChecked(autoRotate);
        rotation.setButtonTintList(android.content.res.ColorStateList.valueOf(MainUi.BLUE));
        rotation.setOnCheckedChangeListener((view, enabled) -> setAutoRotate(enabled)); panel.addView(rotation);
        addRow(panel, new String[]{microphone == null ? "转发麦克风" : "停止麦克风"}, new Runnable[]{this::toggleMicrophone});
        addRow(panel, new String[]{"粘贴", "获取剪贴板"}, new Runnable[]{() -> sendClipboard(true), () -> { if (session != null) session.getClipboard(); }});
        CheckBox clip = new CheckBox(this); clip.setText("同步文本剪贴板"); clip.setTextColor(MainUi.INK); clip.setChecked(syncClipboard);
        clip.setButtonTintList(android.content.res.ColorStateList.valueOf(MainUi.BLUE));
        clip.setOnCheckedChangeListener((v,checked) -> { syncClipboard = checked; saveUi(); }); panel.addView(clip);
        CheckBox volume = new CheckBox(this); volume.setText("同步音量实体键"); volume.setTextColor(MainUi.INK); volume.setChecked(syncVolume);
        volume.setButtonTintList(android.content.res.ColorStateList.valueOf(MainUi.BLUE));
        volume.setOnCheckedChangeListener((v,checked) -> { releaseKeys(); syncVolume = checked; saveUi(); }); panel.addView(volume);
        addRow(panel, new String[]{"悬浮小窗", "退出控制"}, new Runnable[]{this::requestFloatingWindow, this::finish});
        showMenuPanel(panel, 400);
    }
    void showPowerMenu() {
        dismissMenu();
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(MainUi.background(Color.WHITE, 0, 18, this));
        TextView title = new TextView(this); title.setText("电源与唤醒"); title.setTextSize(19); title.setTextColor(MainUi.INK); panel.addView(title);
        TextView note = new TextView(this); note.setText("一键发送按键。长按为 0.8 秒，组合键同时按下后自动松开；具体行为由被控系统决定。"); note.setTextColor(MainUi.MUTED); note.setPadding(0, dp(8), 0, dp(8)); panel.addView(note);
        addRow(panel, new String[]{"唤醒屏幕", "点按电源"}, new Runnable[]{() -> powerKeys.pulse(80, KeyEvent.KEYCODE_WAKEUP), () -> powerKeys.pulse(80, KeyEvent.KEYCODE_POWER)});
        addRow(panel, new String[]{"长按电源"}, new Runnable[]{() -> powerKeys.pulse(800, KeyEvent.KEYCODE_POWER)});
        addRow(panel, new String[]{"电源 + 音量−", "电源 + 音量+"}, new Runnable[]{() -> powerKeys.pulse(400, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_DOWN), () -> powerKeys.pulse(400, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP)});
        showMenuPanel(panel, 320);
    }
    void showQualityMenu() {
        dismissMenu();
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(MainUi.background(Color.WHITE, 0, 18, this));
        TextView title = new TextView(this); title.setText("质量参数"); title.setTextSize(19); title.setTextColor(MainUi.INK); panel.addView(title);
        TextView note = new TextView(this); note.setText("仅全屏前台统计勾选项。此菜单立即生效；外观在主设置页保存。视频延迟为本地解码排队/处理时间，ADB 往返含 Shell 开销，均不是端到端延迟。"); note.setTextColor(MainUi.MUTED); panel.addView(note);
        SharedPreferences prefs = UiPreferences.get(this);
        CheckBox enabled = new CheckBox(this); enabled.setText("显示质量参数"); enabled.setTextColor(MainUi.INK); enabled.setChecked(prefs.getBoolean(UiPreferences.QUALITY_ENABLED, false)); enabled.setTag("qualityMaster"); panel.addView(enabled);
        enabled.setOnCheckedChangeListener((v, value) -> prefs.edit().putBoolean(UiPreferences.QUALITY_ENABLED, value).apply());
        for (int i = 0; i < QualityStats.ITEMS.length; i++) {
            int bit = QualityStats.ITEMS[i]; CheckBox item = new CheckBox(this); item.setText(QualityStats.LABELS[i]); item.setTextColor(MainUi.INK); item.setTag("qualityItem" + bit);
            item.setChecked((prefs.getInt(UiPreferences.QUALITY_ITEMS, QualityStats.FPS | QualityStats.VIDEO_RATE) & bit) != 0); panel.addView(item);
            item.setOnCheckedChangeListener((v, value) -> { int old = prefs.getInt(UiPreferences.QUALITY_ITEMS, QualityStats.FPS | QualityStats.VIDEO_RATE); prefs.edit().putInt(UiPreferences.QUALITY_ITEMS, value ? old | bit : old & ~bit).apply(); });
        }
        addRow(panel, new String[]{"返回远控菜单"}, new Runnable[]{this::toggleMenu}); showMenuPanel(panel, 470);
    }
    private void showMenuPanel(LinearLayout panel, int maxHeightDp) {
        ScrollView scroll = new ScrollView(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) { return forwardVolumeKey(event) || super.dispatchKeyEvent(event); }
        }; scroll.addView(panel); scroll.setAlpha(UiPreferences.opacity(this, UiPreferences.MENU));
        int width = Math.min(dp(324), root.getWidth() - dp(24));
        menu = new PopupWindow(scroll, width, Math.min(dp(maxHeightDp), Math.max(dp(180), root.getHeight() - dp(100))), true);
        menu.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); menu.setOutsideTouchable(true); menu.setElevation(dp(14));
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
    View menuContent() { return menu == null ? null : menu.getContentView(); }
    boolean menuShowing() { return menu != null && menu.isShowing(); }
    private void applyAppearance() {
        if (ball != null) ball.setAlpha(UiPreferences.opacity(this, UiPreferences.BALL));
        if (menu != null) menu.getContentView().setAlpha(UiPreferences.opacity(this, UiPreferences.MENU));
        updateQuality();
    }
    private void updateQuality() { if (qualityOverlay != null) qualityOverlay.bind(session, display, resumed && !switchingWindow && !pendingFloating && !isFinishing()); }
    private void key(int code) { if (session != null && display.ownsSession()) session.key(code); }
    private void panelCommand(int type) { if (session != null && display.ownsSession()) session.send(new byte[]{(byte)type}); }
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
        if (switchingWindow || session == null || session.isClosed() || device.session != session) return;
        dismissMenu(); powerKeys.release(); releaseKeys(); display.releaseTouches(); stopMicrophone(); stopRotationMonitor();
        if (!Settings.canDrawOverlays(this)) {
            awaitingPermission = true;
            Toast.makeText(this, "请允许在其他应用上层显示", Toast.LENGTH_LONG).show();
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName())), 20); return;
        }
        android.content.SharedPreferences permissions = getSharedPreferences("remote-ui", MODE_PRIVATE);
        if (resumed && !userLeaving && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !permissions.getBoolean("notificationsRequested", false)) {
            permissions.edit().putBoolean("notificationsRequested", true).apply(); awaitingNotifications = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 21); return;
        }
        switchingWindow = true;
        int generation = ++outputGeneration;
        display.suspendOutput();
        Intent intent = new Intent(this, FloatingWindowService.class).putExtra("target", device.target).putExtra("audio", getIntent().getBooleanExtra("audio", true));
        intent.putExtra("result", new ResultReceiver(new Handler(getMainLooper())) {
            @Override protected void onReceiveResult(int result, Bundle data) {
                if (generation != outputGeneration || isDestroyed()) return;
                switchingWindow = false;
                if (result == RESULT_OK) { finish(); } else { display.resumeOutput(); status(data == null ? "小窗启动失败" : data.getString("error")); updateRotationMonitor(); }
            }
        });
        try { startForegroundService(intent); }
        catch (RuntimeException e) { switchingWindow = false; display.resumeOutput(); status("小窗启动失败：" + e.getMessage()); updateRotationMonitor(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20) { awaitingPermission = false; pendingFloating = Settings.canDrawOverlays(this); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 22) {
            awaitingMicrophonePermission = false;
            if (results.length == 0 || results[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingMicrophoneTarget = null; status("未授予麦克风权限，转发未开启");
            } else resumePendingMicrophone();
        }
        if (request == 21) {
            awaitingNotifications = false;
            if (results.length == 0 || results[0] != android.content.pm.PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "通知未启用，可在设备与凭据页恢复全屏或关闭小窗", Toast.LENGTH_LONG).show();
            if (display.ownsSession() && session != null && !session.isClosed()) requestFloatingWindow(); else pendingFloating = true;
        }
    }
    @Override public void ready(ScrcpySession attached) {
        session = attached;
        if (attached.width > 0) { status("已连接：" + device.target); if (resumed && syncClipboard) attached.getClipboard(); }
        FloatingWindowService.removeTransferred(device.target);
        updateRotationMonitor();
        if (pendingFloating) { pendingFloating = false; requestFloatingWindow(); }
        updateQuality();
    }
    @Override public void status(String message) {
        if (microphone != null && microphone.isActive()) message += "\n麦克风正在转发至：" + microphone.targetPackage;
        status.setText(message); status.setVisibility(View.VISIBLE);
        if (message.startsWith("已连接")) status.postDelayed(() -> { if (microphone == null) status.setVisibility(View.GONE); }, 1800);
    }
    private void toggleMicrophone() {
        if (microphone != null) { stopMicrophone(); status("已连接：" + device.target + "（麦克风已停止）"); return; }
        if (selectingMicrophoneTarget || session == null || !display.ownsSession()) return;
        selectingMicrophoneTarget = true;
        DeviceConnection queriedDevice = device;
        new Thread(() -> {
            String target = "";
            try { target = MicrophoneForwarder.foregroundPackage(queriedDevice.shell("dumpsys activity activities")); }
            catch (Exception e) { DebugLog.write("microphone foreground query: " + e.getMessage()); }
            String current = target;
            runOnUiThread(() -> {
                selectingMicrophoneTarget = false;
                if (device != queriedDevice || !resumed || isFinishing() || session == null || session.isClosed() || !display.ownsSession()) return;
                LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(24), 0, dp(24), 0);
                TextView explanation = new TextView(this); explanation.setText("实验功能，需要被控端 Android 14 及以上。向指定应用提供主控麦克风；建议先开启，再开始录音或网络通话。离开全屏或切换小窗会停止。\n\n被控应用包名（已填入当前前台应用）："); body.addView(explanation);
                EditText app = new EditText(this); app.setSingleLine(true); app.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); app.setText(current); app.setHint("例如 com.example.app"); body.addView(app);
                new AlertDialog.Builder(this).setTitle("转发本机麦克风").setView(body).setNegativeButton("取消", null)
                        .setPositiveButton("开启", (dialog, which) -> requestMicrophone(app.getText().toString().trim())).show();
            });
        }, "microphone-target").start();
    }
    private void requestMicrophone(String target) {
        if (!MicrophoneForwarder.validPackage(target)) { status("请输入有效的被控应用包名"); return; }
        pendingMicrophoneTarget = target;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            awaitingMicrophonePermission = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 22);
        } else resumePendingMicrophone();
    }
    private void resumePendingMicrophone() {
        if (!resumed || awaitingMicrophonePermission || pendingMicrophoneTarget == null) return;
        String target = pendingMicrophoneTarget; pendingMicrophoneTarget = null;
        if (isFinishing() || session == null || session.isClosed() || !display.ownsSession()) return;
        MicrophoneForwarder forwarding = new MicrophoneForwarder(this, device, target, new MicrophoneForwarder.Listener() {
            public void started(MicrophoneForwarder current) { runOnUiThread(() -> { if (microphone == current && current.isActive()) status("已连接：" + device.target); }); }
            public void stopped(MicrophoneForwarder current, String error) { runOnUiThread(() -> {
                if (microphone != current) return;
                microphone = null;
                if (resumed && !isFinishing()) status(error == null ? "已连接：" + device.target : "麦克风转发失败：" + error);
            }); }
        });
        microphone = forwarding; status("正在开启麦克风转发…"); forwarding.start();
    }
    private void stopMicrophone() {
        pendingMicrophoneTarget = null;
        if (microphone != null) { MicrophoneForwarder current = microphone; microphone = null; current.close(); }
    }
    @Override public void size(int width, int height) {
        if (!autoRotate || pendingFloating || switchingWindow) return;
        updateRotationMonitor();
        if (rotationMonitor != null) rotationMonitor.refresh();
        if (rotationInfo != null && (rotationInfo.width > rotationInfo.height) == (width > height)) {
            applyOrientation(rotationInfo.requestedOrientation(), "display service"); return;
        }
        int generation = ++sizeGeneration; long requested = SystemClock.elapsedRealtime();
        status.postDelayed(() -> {
            if (generation == sizeGeneration && lastRotationUpdate < requested)
                applyOrientation(RemoteOrientation.aspectOrientation(width, height), "video aspect");
        }, 450);
    }
    void setAutoRotate(boolean enabled) {
        autoRotate = enabled; getSharedPreferences("remote-ui", MODE_PRIVATE).edit().putBoolean("autoRotate", enabled).apply();
        if (!enabled) { stopRotationMonitor(); setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); }
        else { updateRotationMonitor(); if (session != null) size(session.width, session.height); }
    }
    boolean autoRotateEnabled() { return autoRotate; }
    private void updateRotationMonitor() {
        if (!autoRotate || !resumed || pendingFloating || switchingWindow || isFinishing() || session == null || session.isClosed() || !display.ownsSession()) return;
        if (session.remoteDisplayId < 0) return;
        if (rotationMonitor != null) return;
        int generation = ++monitorGeneration;
        RemoteRotationMonitor monitor = new RemoteRotationMonitor(device, session.remoteDisplayId, info -> runOnUiThread(() -> {
            if (generation != monitorGeneration || rotationMonitor == null || !autoRotate || !resumed || !display.ownsSession() || isFinishing()) return;
            if (info != null) { rotationInfo = info; lastRotationUpdate = SystemClock.elapsedRealtime(); applyOrientation(info.requestedOrientation(), "display service rotation=" + info.rotation); }
            else if (rotationInfo == null && session != null) applyOrientation(RemoteOrientation.aspectOrientation(session.width, session.height), "video aspect");
        }));
        rotationMonitor = monitor; monitor.start();
    }
    private void stopRotationMonitor() { ++monitorGeneration; if (rotationMonitor != null) { rotationMonitor.close(); rotationMonitor = null; } ++sizeGeneration; }
    private void applyOrientation(int orientation, String source) {
        if (!autoRotate || !resumed || pendingFloating || switchingWindow || isFinishing() || session == null || !display.ownsSession()
                || session.width <= 0 || orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED || getRequestedOrientation() == orientation) return;
        dismissMenu(); powerKeys.release(); releaseKeys(); display.releaseTouches();
        DebugLog.write("fullscreen native orientation=" + orientation + " source=" + source);
        setRequestedOrientation(orientation);
    }
    private void immersive() {
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus && !isFinishing()) immersive(); }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); dismissMenu(); immersive(); if (root != null) root.post(this::positionBall);
    }
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
        super.onResume(); resumed = true; userLeaving = false;
        if (display != null && !switchingWindow) display.resumeOutput();
        autoRotate = getSharedPreferences("remote-ui", MODE_PRIVATE).getBoolean("autoRotate", true); updateRotationMonitor();
        resumePendingMicrophone();
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipListener);
        if (runtime != null && getIntent().getBooleanExtra("audio", true)) runtime.acquireAudioFocus(this);
        updateQuality();
    }
    private void statusServiceFailure(RuntimeException error) { DebugLog.error("foreground service", error); Toast.makeText(this, "后台服务启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); }
    @Override protected void onUserLeaveHint() { userLeaving = true; super.onUserLeaveHint(); }
    @Override protected void onPause() {
        resumed = false; stopRotationMonitor(); if (powerKeys != null) powerKeys.release();
        if (qualityOverlay != null) qualityOverlay.stop();
        if (!awaitingMicrophonePermission) stopMicrophone(); releaseKeys();
        if (display != null) display.releaseTouches();
        if (clipboard != null) clipboard.removePrimaryClipChangedListener(clipListener);
        boolean waiting = awaitingPermission || awaitingNotifications || awaitingMicrophonePermission;
        if (display != null && !isFinishing() && !switchingWindow && (waiting || UiPreferences.get(this).getBoolean("backgroundKeepAlive", true))) {
            display.suspendOutput();
            if (!waiting && userLeaving && UiPreferences.get(this).getBoolean("backgroundFloating", false) && Settings.canDrawOverlays(this)) requestFloatingWindow();
        }
        super.onPause();
    }
    @Override protected void onStop() {
        if (!awaitingPermission && !awaitingNotifications && !awaitingMicrophonePermission && !switchingWindow
                && !UiPreferences.get(this).getBoolean("backgroundKeepAlive", true)) {
            if (display != null) display.dispose(); if (!isFinishing()) finish();
        }
        super.onStop();
    }
    @Override protected void onDestroy() {
        UiPreferences.get(this).unregisterOnSharedPreferenceChangeListener(appearanceListener);
        stopRotationMonitor(); stopMicrophone(); dismissMenu(); releaseKeys();
        if (qualityOverlay != null) qualityOverlay.stop();
        if (powerKeys != null) powerKeys.release();
        if (display != null) display.dispose();
        if (runtime != null) { if (runtime.fullscreen == this) runtime.fullscreen = null; runtime.releaseAudioFocusIfIdle(this); }
        super.onDestroy();
    }
}
