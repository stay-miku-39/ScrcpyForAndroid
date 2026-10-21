package org.scrcpy.android;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Navigation and device metadata only; ADB operations remain in MainActivity. */
final class MainUi {
    static final int INK = Color.rgb(24, 40, 65), MUTED = Color.rgb(92, 110, 132), BLUE = Color.rgb(36, 99, 225);
    private final MainActivity a;
    private final SharedPreferences prefs;
    private final Map<String, View> pages = new LinkedHashMap<>();
    private final Map<String, Button> tabs = new LinkedHashMap<>();
    private ViewFlipper content;
    private LinearLayout devicePage, nav;
    private TextView heading, connection;
    private Button quickRemote;
    private EditText pairHost;
    private String page = "connect";
    private SettingsDraft settingsDraft;

    MainUi(MainActivity activity, SharedPreferences preferences) { a = activity; prefs = preferences; }
    static int dp(Context context, float n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    int dp(float n) { return dp(a, n); }
    static GradientDrawable background(int color, int stroke, float radius, Context context) {
        GradientDrawable b = new GradientDrawable(); b.setColor(color); b.setCornerRadius(dp(context, radius));
        if (stroke != 0) b.setStroke(dp(context, 1), stroke); return b;
    }
    void build(Bundle saved) {
        LinearLayout root = column(); root.setBackgroundColor(Color.rgb(243, 246, 251)); a.setContentView(root);
        a.getWindow().getInsetsController().setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, imeBottom));
            if (nav != null) nav.setVisibility(insets.isVisible(WindowInsets.Type.ime()) ? View.GONE : View.VISIBLE); return insets;
        });
        LinearLayout header = row(); header.setPadding(dp(20), dp(10), dp(12), dp(8));
        heading = text("Scrcpy Android", 23, INK); heading.setTypeface(null, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button more = new Button(a); more.setText("⋮"); more.setTextSize(24); more.setContentDescription("管理与更多选项");
        more.setBackgroundColor(Color.TRANSPARENT); header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        more.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(a, more);
            menu.getMenu().add("本机凭据").setOnMenuItemClickListener(i -> { select("credential"); return true; });
            menu.getMenu().add("设备与凭据").setOnMenuItemClickListener(i -> { select("devices"); return true; });
            menu.getMenu().add("设置").setOnMenuItemClickListener(i -> { select("settings"); return true; });
            menu.getMenu().add("导出调试日志").setOnMenuItemClickListener(i -> { a.exportLogs(); return true; });
            menu.getMenu().add("取消当前操作并断开连接").setOnMenuItemClickListener(i -> { a.cancelOperation(); return true; }); menu.show();
        }); root.addView(header);
        connection = text("", 13, MUTED); connection.setPadding(dp(20), 0, dp(20), dp(6)); root.addView(connection);
        a.status = text("就绪", 13, MUTED); a.status.setMaxLines(3); a.status.setEllipsize(TextUtils.TruncateAt.END);
        a.status.setPadding(dp(20), dp(4), dp(20), dp(8)); root.addView(a.status); a.status.setVisibility(View.GONE);
        FrameLayout pageArea = new FrameLayout(a); root.addView(pageArea, new LinearLayout.LayoutParams(-1, 0, 1));
        content = new ViewFlipper(a); pageArea.addView(content, new FrameLayout.LayoutParams(-1, -1));
        quickRemote = new Button(a); quickRemote.setText("远程控制"); quickRemote.setTextSize(14); quickRemote.setAllCaps(false);
        quickRemote.setContentDescription("一键启动最新 ADB 连接的远程控制"); quickRemote.setTextColor(Color.WHITE);
        quickRemote.setMinWidth(0); quickRemote.setPadding(dp(12), 0, dp(12), 0);
        quickRemote.setBackground(background(BLUE, 0, 24, a)); quickRemote.setElevation(dp(6)); quickRemote.setVisibility(View.GONE);
        quickRemote.setOnClickListener(v -> a.startLatestRemote()); a.operations.add(quickRemote);
        FrameLayout.LayoutParams shortcutPosition = new FrameLayout.LayoutParams(dp(112), dp(48), Gravity.BOTTOM | Gravity.END);
        shortcutPosition.setMargins(dp(16), dp(16), dp(16), dp(16)); pageArea.addView(quickRemote, shortcutPosition);
        buildConnect(); buildPair(); devicePage = screen("devices", "设备与凭据", null); buildCredential(); buildTools(); buildScan(); buildShell(); buildFiles(); buildSettings();
        nav = row(); nav.setPadding(dp(12), dp(8), dp(12), dp(8)); nav.setBackgroundColor(Color.WHITE);
        String[][] navigation = {{"connect", "连接"}, {"devices", "设备与凭据"}, {"tools", "工具"}};
        for (String[] item : navigation) {
            Button tab = new Button(a); tab.setText(item[1]); tab.setTextSize(14); tab.setAllCaps(false); tab.setMinWidth(0); tab.setPadding(0, 0, 0, 0);
            tab.setOnClickListener(v -> select(item[0])); nav.addView(tab, new LinearLayout.LayoutParams(0, dp(48), 1)); tabs.put(item[0], tab);
        } root.addView(nav);
        if (saved != null) {
            a.host.setText(saved.getString("host", a.host.getText().toString())); a.port.setText(saved.getString("port", a.port.getText().toString()));
            a.pairPort.setText(saved.getString("pairPort", "")); a.scanFrom.setText(saved.getString("scanFrom", "30000")); a.scanTo.setText(saved.getString("scanTo", "50000"));
            a.command.setText(saved.getString("command", "getprop ro.product.model")); a.remotePath.setText(saved.getString("remotePath", "/sdcard/Download/shared-file.bin"));
            a.audio.setChecked(saved.getBoolean("audio", a.audio.isChecked()));
        }
        select(saved == null ? "connect" : saved.getString("page", "connect")); refresh();
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(a); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(a); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String value, int size, int color) { TextView v = new TextView(a); v.setText(value); v.setTextColor(color); v.setTextSize(size); return v; }
    private void title(LinearLayout parent, String value) { TextView t = text(value, 18, INK); t.setTypeface(null, Typeface.BOLD); t.setPadding(0, 0, 0, dp(8)); parent.addView(t); }
    private void note(LinearLayout parent, String value) { TextView t = text(value, 14, MUTED); t.setPadding(0, dp(4), 0, dp(12)); parent.addView(t); }
    private LinearLayout screen(String id, String name, String parent) {
        ScrollView scroll = new ScrollView(a); scroll.setFillViewport(true);
        LinearLayout body = column(); body.setPadding(dp(16), dp(10), dp(16), dp(20)); scroll.addView(body);
        if (parent != null) { Button back = action(body, "‹  " + name, () -> select(parent), false); back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); }
        pages.put(id, scroll); content.addView(scroll); return body;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column(); card.setPadding(dp(16), dp(16), dp(16), dp(12));
        card.setBackground(background(Color.WHITE, Color.rgb(222, 229, 239), 16, a));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(14); parent.addView(card, p); return card;
    }
    private Button action(LinearLayout parent, String name, Runnable handler, boolean primary) {
        Button b = new Button(a); b.setText(name); b.setAllCaps(false); b.setTextSize(15); b.setMinHeight(dp(48)); b.setMinWidth(0);
        b.setTextColor(primary ? Color.WHITE : BLUE); b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setBackground(background(primary ? BLUE : Color.rgb(232, 239, 252), 0, 10, a)); b.setStateListAnimator(null);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.topMargin = dp(3); spacing.bottomMargin = dp(3);
        b.setOnClickListener(v -> handler.run()); parent.addView(b, spacing); return b;
    }
    private Button operation(LinearLayout parent, String name, Runnable handler, boolean primary) {
        Button button = action(parent, name, handler, primary); a.operations.add(button); return button;
    }
    private EditText field(LinearLayout parent, String name, String initial, boolean numeric) {
        TextView label = text(name, 13, MUTED); label.setPadding(0, dp(6), 0, dp(3)); parent.addView(label);
        EditText v = new EditText(a); v.setText(initial); v.setHint(name); v.setTextSize(16); v.setTextColor(INK); v.setSingleLine(true);
        v.setPadding(dp(12), dp(10), dp(12), dp(10)); v.setMinHeight(dp(48));
        v.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        v.setBackground(background(Color.rgb(248, 250, 253), Color.rgb(213, 223, 236), 9, a));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(10); parent.addView(v, p); return v;
    }
    private void buildConnect() {
        LinearLayout body = screen("connect", "连接", null), card = card(body);
        title(card, "连接到设备"); note(card, "使用无线调试地址，或连接 USB / OTG 设备。连接新设备会保留其他连接；在设备页分别控制。");
        a.host = field(card, "目标 IP", prefs.getString("host", ""), false);
        a.port = field(card, "连接端口", prefs.getString("port", "5555"), true);
        operation(card, "连接 / 重连", a::connectNetwork, true);
        action(card, "首次使用？无线配对", () -> select("pair"), false);
        LinearLayout usb = card(body); title(usb, "USB / OTG"); note(usb, "连接数据线并开启被控端 USB 调试。");
        operation(usb, "选择 USB 设备并连接", a::usbDevices, false);
        LinearLayout remote = card(body); title(remote, "远程控制");
        a.audio = new CheckBox(a); a.audio.setText("同步远程声音"); a.audio.setTextColor(INK); a.audio.setChecked(prefs.getBoolean("audio", true)); remote.addView(a.audio);
        operation(remote, "开始投屏和控制", () -> a.startRemote(false), true);
        operation(remote, "悬浮小窗", () -> a.startRemote(true), false);
    }
    private void buildPair() {
        LinearLayout body = screen("pair", "无线配对", "connect"), card = card(body);
        title(card, "使用配对码"); note(card, "被控端 → 开发者选项 → 无线调试 → 使用配对码配对设备。");
        pairHost = field(card, "目标 IP", a.host.getText().toString(), false);
        pairHost.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { a.host.setText(s); }
            public void afterTextChanged(Editable e) { }
        });
        a.pairPort = field(card, "配对端口", "", true); a.code = field(card, "6 位配对码", "", true);
        a.code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); a.code.setSaveEnabled(false);
        operation(card, "使用配对码配对", a::pair, true);
        note(card, "配对端口与连接端口不同。配对成功后回到连接页，填写无线调试首页的连接端口。配对码不会保存。");
        action(card, "返回连接", () -> select("connect"), false);
    }
    private void buildSettings() {
        SettingsDraft draft = new SettingsDraft(UiPreferences.get(a)); settingsDraft = draft;
        LinearLayout body = screen("settings", "设置", "connect"), card = card(body);
        StreamSettingsView stream = new StreamSettingsView(a, values -> {
            UiPreferences.parseQualityColor(draft.getString(UiPreferences.QUALITY_COLOR, "#FFFFFF"));
            StreamPreferences.save(a, values); draft.persist();
            DebugLog.setEnabled(draft.getBoolean(UiPreferences.DEBUG_LOG, false));
            Toast.makeText(a, "全部设置已保存", Toast.LENGTH_SHORT).show();
            if (draft.getBoolean("backgroundFloating", false) && !android.provider.Settings.canDrawOverlays(a))
                a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + a.getPackageName())));
        }); card.addView(stream);
        card = card(body);
        title(card, "后台运行");
        SharedPreferences background = draft;
        CheckBox keep = new CheckBox(a); keep.setText("后台保留远程控制连接"); keep.setChecked(background.getBoolean("backgroundKeepAlive", true)); card.addView(keep);
        CheckBox floating = new CheckBox(a); floating.setText("切换后台时，全屏自动转为小窗"); floating.setChecked(background.getBoolean("backgroundFloating", false)); floating.setEnabled(keep.isChecked()); card.addView(floating);
        keep.setOnCheckedChangeListener((view, checked) -> { background.edit().putBoolean("backgroundKeepAlive", checked).apply(); floating.setEnabled(checked); });
        floating.setOnCheckedChangeListener((view, checked) -> background.edit().putBoolean("backgroundFloating", checked).apply());
        note(card, "后台通过常驻通知保留 ADB 和远控。自动小窗需悬浮窗权限；未授权时保留后台连接。后台和小窗会停止本机麦克风、剪贴板同步及实体音量键转发。系统强制停止或断网仍会结束连接。");
        card = card(body);
        title(card, "界面外观");
        note(card, "数值越小越透明。控制组件最低 20% 不透明度。小窗设置作用于整个窗口，包括远程画面和操作栏。所有修改须点击底部保存后生效。");
        SharedPreferences appearance = draft;
        opacityControl(card, appearance, "全屏悬浮菜单球", UiPreferences.BALL);
        opacityControl(card, appearance, "全屏展开菜单", UiPreferences.MENU);
        opacityControl(card, appearance, "悬浮小窗", UiPreferences.WINDOW);
        opacityControl(card, appearance, "质量参数字体", UiPreferences.QUALITY_OPACITY);
        EditText qualityColor = field(card, "质量参数字体颜色（#RRGGBB）", appearance.getString(UiPreferences.QUALITY_COLOR, "#FFFFFF"), false);
        qualityColor.setTag(UiPreferences.QUALITY_COLOR);
        qualityColor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { appearance.edit().putString(UiPreferences.QUALITY_COLOR, s.toString().trim()).apply(); }
            public void afterTextChanged(Editable e) { }
        });
        action(card, "恢复默认外观", () -> {
            appearance.edit().remove(UiPreferences.BALL).remove(UiPreferences.MENU).remove(UiPreferences.WINDOW).remove(UiPreferences.QUALITY_OPACITY).remove(UiPreferences.QUALITY_COLOR).apply();
            ViewGroup controls = (ViewGroup)pages.get("settings");
            for (String key : new String[]{UiPreferences.BALL, UiPreferences.MENU, UiPreferences.WINDOW, UiPreferences.QUALITY_OPACITY}) {
                ((SeekBar)controls.findViewWithTag(key)).setProgress(100);
            }
            qualityColor.setText("#FFFFFF");
        }, false);
        LinearLayout diagnostics = card(body); title(diagnostics, "诊断");
        CheckBox logging = new CheckBox(a); logging.setText("启用调试日志"); logging.setTag(UiPreferences.DEBUG_LOG); logging.setChecked(draft.getBoolean(UiPreferences.DEBUG_LOG, false)); diagnostics.addView(logging);
        logging.setOnCheckedChangeListener((view, value) -> draft.edit().putBoolean(UiPreferences.DEBUG_LOG, value).apply());
        note(diagnostics, "默认关闭。保存开启后记录后续阶段、参数与错误；关闭后停止追加，历史日志仍可导出。服务端日志级别在下次投屏启动时应用。日志不包含配对码、密钥或剪贴板正文。");
        stream.moveSaveControls(body);
    }
    private void opacityControl(LinearLayout parent, SharedPreferences appearance, String name, String key) {
        TextView value = text(name + " · 不透明度 " + UiPreferences.percent(appearance, key) + "%", 15, INK); parent.addView(value);
        SeekBar slider = new SeekBar(a); slider.setTag(key); slider.setContentDescription(name + "不透明度");
        slider.setMin(UiPreferences.QUALITY_OPACITY.equals(key) ? 0 : UiPreferences.MIN_OPACITY); slider.setMax(100); slider.setProgress(UiPreferences.percent(appearance, key));
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                value.setText(name + " · 不透明度 " + progress + "%"); appearance.edit().putInt(key, progress).apply();
            }
        });
    }
    private void buildTools() {
        LinearLayout body = screen("tools", "工具", null);
        String[][] tools = {{"scan", "端口扫描", "在指定 IP 和端口范围中查找可连接端口"}, {"shell", "ADB Shell", "执行命令并查看返回结果"}, {"files", "文件传输", "通过系统文件选择器上传和下载"}};
        for (String[] item : tools) { LinearLayout tile = card(body); title(tile, item[1]); note(tile, item[2]); action(tile, "打开" + item[1], () -> select(item[0]), false); }
        LinearLayout diagnostics = card(body); title(diagnostics, "连接与诊断");
        operation(diagnostics, "通过当前 ADB 开启 TCP 5555", a::tcpip, false);
        action(diagnostics, "导出调试日志", a::exportLogs, false);
        action(diagnostics, "取消当前操作并断开连接", a::cancelOperation, false);
    }
    private void buildScan() {
        LinearLayout body = screen("scan", "端口扫描", "tools"), card = card(body);
        title(card, "扫描单个设备");
        EditText scanHost = field(card, "目标 IPv4", a.host.getText().toString(), false);
        scanHost.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { a.host.setText(s); }
            public void afterTextChanged(Editable e) { }
        });
        scanHost.setTag("scanHost"); a.scanFrom = field(card, "起始端口", "30000", true); a.scanTo = field(card, "结束端口", "50000", true);
        operation(card, "扫描目标 IP 的开放 TCP 端口", a::scan, true); action(card, "取消扫描", a::cancelScan, false);
        note(card, "开放 TCP 端口不一定是 ADB。扫描结束后可选填连接或配对端口。");
    }
    private void buildShell() {
        LinearLayout body = screen("shell", "ADB Shell", "tools"), card = card(body);
        title(card, "执行命令"); a.command = field(card, "Shell 命令", "getprop ro.product.model", false);
        operation(card, "执行 Shell", () -> { String command = a.command.getText().toString(); DeviceConnection device = a.runtime.current(); a.task(() -> { MainActivity.requireConnected(device); return device.shell(command); }); }, true);
        a.output = text("命令结果显示在这里", 14, INK); a.output.setTextIsSelectable(true); a.output.setPadding(0, dp(16), 0, dp(12)); card.addView(a.output);
    }
    private void buildFiles() {
        LinearLayout body = screen("files", "文件传输", "tools"), card = card(body);
        title(card, "与设备共享文件"); a.remotePath = field(card, "被控端完整文件路径", "/sdcard/Download/shared-file.bin", false);
        operation(card, "选择本地文件并上传", () -> a.chooseFile(true), true);
        operation(card, "下载远程文件到本地", () -> a.chooseFile(false), false);
        note(card, "请填写包含文件名的绝对路径。上传写入该目标文件；下载时选择本地保存位置。");
    }
    void select(String name) {
        if (!pages.containsKey(name)) name = "connect";
        if ("settings".equals(page) || "settings".equals(name)) rebuildSettings();
        page = name;
        if ("pair".equals(name)) pairHost.setText(a.host.getText());
        if ("scan".equals(name)) ((EditText)pages.get("scan").findViewWithTag("scanHost")).setText(a.host.getText());
        content.setDisplayedChild(new ArrayList<>(pages.keySet()).indexOf(name));
        String tab = Arrays.asList("connect", "pair", "settings").contains(name) ? "connect" : Arrays.asList("devices", "credential").contains(name) ? "devices" : "tools";
        for (Map.Entry<String, Button> item : tabs.entrySet()) {
            boolean selected = item.getKey().equals(tab); item.getValue().setTextColor(selected ? Color.WHITE : MUTED);
            item.getValue().setBackground(background(selected ? BLUE : Color.WHITE, 0, 10, a)); item.getValue().setStateListAnimator(null);
            item.getValue().setSelected(selected);
        }
        heading.setText("settings".equals(name) ? "设置" : "devices".equals(tab) ? "设备与凭据" : "tools".equals(tab) ? "ADB 工具" : "Scrcpy Android");
        if ("devices".equals(name)) devices();
        a.getWindow().getDecorView().clearFocus();
        ((android.view.inputmethod.InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(content.getWindowToken(), 0);
    }
    void discardSettingsDraft() {
        if ("settings".equals(page)) rebuildSettings();
    }
    private void rebuildSettings() {
        View previous = pages.remove("settings"); content.removeView(previous); buildSettings();
        content.setDisplayedChild(Math.max(0, new ArrayList<>(pages.keySet()).indexOf(page)));
    }
    void codecCatalogReady() { ((StreamSettingsView)pages.get("settings").findViewWithTag("streamSettings")).refreshCodecLabels(); }
    boolean back() { if (Arrays.asList("pair", "settings", "scan", "shell", "files", "credential").contains(page)) { select(Arrays.asList("pair", "settings").contains(page) ? "connect" : "credential".equals(page) ? "devices" : "tools"); return true; } return false; }
    String page() { return page; }
    void message(String message) {
        a.status.setVisibility(message.startsWith("ADB 已连接") ? View.GONE : View.VISIBLE);
        a.status.setText(message.split("\n", 2)[0]);
    }
    void refresh() {
        // connect()/pair() hold the ADB manager lock while waiting for the peer.
        // Never query that lock from navigation while an operation is in flight.
        DeviceConnection current = a.runtime.current();
        boolean connected = connected(current);
        connection.setText(connected ? "● " + a.runtime.connections().size() + " 个 ADB · 当前 " + current.target : "○ 未连接设备");
        connection.setTextColor(connected ? Color.rgb(24, 124, 88) : MUTED);
        quickRemote.setVisibility(connected ? View.VISIBLE : View.GONE);
        for (View operation : a.operations) operation.setEnabled(!a.isBusy());
        if ("devices".equals(page)) devices();
    }
    private boolean connected(DeviceConnection device) { return device != null && device.connected; }
    private void devices() {
        devicePage.removeAllViews();
        LinearLayout active = card(devicePage); title(active, "已连接设备（" + a.runtime.connections().size() + "）");
        if (a.runtime.connections().isEmpty()) note(active, "没有已连接设备。连接后可在这里分别控制或断开。");
        for (DeviceConnection device : a.runtime.connections()) {
            LinearLayout item = card(devicePage); title(item, device.target);
            boolean showing = FloatingWindowService.isShowing(device.target);
            note(item, (device == a.runtime.current() ? "当前工具目标 · " : "") + (device == a.runtime.latest() ? "最新 ADB 连接 · " : "")
                    + (showing ? "小窗运行中" : device.hasMirror() ? "远控连接中" : "可开始远程控制"));
            action(item, "设为当前工具目标", () -> { a.runtime.select(device); refresh(); }, false).setEnabled(!a.isBusy());
            action(item, "全屏控制", () -> a.startRemote(device.target, false), true).setEnabled(!a.isBusy());
            action(item, "悬浮小窗", () -> a.startRemote(device.target, true), false).setEnabled(!a.isBusy());
            if (device.hasMirror()) action(item, "关闭此设备远控", () -> { FloatingWindowService.closeRemote(a, device.target); a.status.postDelayed(this::refresh, 300); }, false);
            action(item, "断开此设备 ADB", () -> a.disconnect(device), false).setEnabled(!a.isBusy());
        }
        action(devicePage, "查看本机凭据与指纹", () -> select("credential"), false);
        title(devicePage, "已保存设备");
        try {
            JSONArray items = new JSONArray(prefs.getString("history", "[]"));
            if (items.length() == 0) note(devicePage, "成功配对或连接后，设备会出现在这里。");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i); LinearLayout saved = card(devicePage);
                title(saved, item.optString("alias").isEmpty() ? item.optString("host") : item.optString("alias"));
                note(saved, item.optString("host") + ":" + (item.optInt("port") > 0 ? item.optInt("port") : "待填写连接端口")
                        + (item.optBoolean("paired") ? "  ·  已配对" : "  ·  已保存"));
                action(saved, "使用此设备", () -> { a.host.setText(item.optString("host")); if (item.optInt("port") > 0) a.port.setText(String.valueOf(item.optInt("port"))); select("connect"); }, true);
                action(saved, "管理设备记录", () -> edit(item), false).setEnabled(!a.isBusy());
            }
        } catch (JSONException e) { note(devicePage, "设备记录读取失败：" + e.getMessage()); }
    }
    private void buildCredential() {
        LinearLayout body = screen("credential", "本机凭据", "devices"), credential = card(body); title(credential, "本机 ADB 凭据");
        note(credential, "RSA 身份 · Android Keystore 加密保存\n所有设备共用此主控身份，升级应用会保留配对。");
        TextView fingerprint = text(a.runtime.adb.fingerprint(), 12, MUTED); fingerprint.setTextIsSelectable(true); credential.addView(fingerprint);
        action(credential, "复制凭据指纹", () -> ((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ADB SHA-256", a.runtime.adb.fingerprint())), false);
        note(credential, "设备授权可在被控端的无线调试中撤销。本页管理本地设备记录，不更换已保存的密钥。");
    }
    private void edit(JSONObject item) {
        LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText alias = field(form, "设备名称", item.optString("alias", item.optString("host")), false);
        EditText endpoint = field(form, "连接端口", item.optInt("port") > 0 ? String.valueOf(item.optInt("port")) : "", true);
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle("管理 " + item.optString("host")).setView(form)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).setNeutralButton("删除本地记录", (d,w) -> {
                    new AlertDialog.Builder(a).setMessage("删除此设备记录？密钥会保留，被控端的授权不会撤销。")
                            .setPositiveButton("删除", (confirm,which) -> update(item, null)).setNegativeButton("取消", null).show();
                }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { JSONObject replacement = new JSONObject(item.toString()); replacement.put("alias", alias.getText().toString().trim());
                replacement.put("port", MainActivity.port(endpoint)); update(item, replacement); dialog.dismiss(); }
            catch (Exception e) { endpoint.setError(e.getMessage()); }
        })); dialog.show();
    }
    private void update(JSONObject original, JSONObject replacement) {
        try {
            JSONArray old = new JSONArray(prefs.getString("history", "[]")), result = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject entry = old.getJSONObject(i);
                if (entry.optString("host").equals(original.optString("host"))) { if (replacement != null) result.put(replacement); }
                else result.put(entry);
            }
            prefs.edit().putString("history", result.toString()).apply(); devices();
        } catch (JSONException e) { a.show(e.getMessage()); }
    }
    void save(Bundle out) {
        out.putString("page", page); out.putString("host", a.host.getText().toString()); out.putString("port", a.port.getText().toString());
        out.putString("pairPort", a.pairPort.getText().toString()); out.putString("scanFrom", a.scanFrom.getText().toString()); out.putString("scanTo", a.scanTo.getText().toString());
        out.putString("command", a.command.getText().toString()); out.putString("remotePath", a.remotePath.getText().toString()); out.putBoolean("audio", a.audio.isChecked());
    }
}
