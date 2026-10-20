package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import io.github.muntashirakon.adb.android.AdbUsb;
import io.github.muntashirakon.adb.AdbStream;
import org.json.*;
import java.io.*;
import java.util.*;

public class MainActivity extends androidx.activity.ComponentActivity {
    RuntimeState runtime;
    EditText host, port, pairPort, code, scanFrom, scanTo, command, remotePath;
    TextView status, output;
    CheckBox audio;
    final List<View> operations = new ArrayList<>();
    private boolean busy;
    MainUi ui;
    private boolean awaitingOverlay;
    private String overlayTarget, pendingDeviceTarget;
    private PortScanner scanner;
    private String pendingPath;
    private SharedPreferences preferences;
    private static final String USB_PERMISSION = "org.scrcpy.android.USB_PERMISSION";
    private final Runnable operationWatcher = new Runnable() {
        @Override public void run() {
            if (isDestroyed() || ui == null) return;
            if (runtime.operationRunning) { status.postDelayed(this, 250); return; }
            ui.refresh(); if (!runtime.lastMessage.isEmpty()) show(runtime.lastMessage);
        }
    };
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                        : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connectUsb(device);
                else show("USB 权限未授予");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && runtime != null) {
                UsbDevice detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (detached == null) return;
                DeviceConnection device = runtime.connection("USB:" + detached.getDeviceName());
                if (device == null) return;
                FloatingWindowService.closeRemote(MainActivity.this, device.target);
                runtime.worker.execute(() -> { try { runtime.disconnect(device); } catch (Exception e) { DebugLog.error("USB detach", e); } });
                show("USB 设备已断开");
            }
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("devices", MODE_PRIVATE);
        try { runtime = RuntimeState.get(this); }
        catch (Exception e) { new AlertDialog.Builder(this).setMessage("无法读取 ADB 身份：" + e).setPositiveButton("关闭", (d,w) -> finish()).show(); return; }
        ui = new MainUi(this, preferences);
        ui.build(saved);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (!ui.back()) finish(); }
        });
        if (saved != null) { pendingPath = saved.getString("pendingPath"); awaitingOverlay = saved.getBoolean("awaitingOverlay"); overlayTarget = saved.getString("overlayTarget"); pendingDeviceTarget = saved.getString("pendingDeviceTarget"); }
        IntentFilter filter = new IntentFilter(USB_PERMISSION); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    void show(String text) { runtime.lastMessage = text; runOnUiThread(() -> { if (!isDestroyed()) { status.setText(text); output.setText(text); if (ui != null) ui.message(text); } }); }
    interface Job { String run() throws Exception; }
    void task(Job job) {
        if (isBusy()) return;
        busy = true; runtime.operationRunning = true; for (View v : operations) v.setEnabled(false); if (ui != null) ui.refresh(); show("处理中…");
        runtime.worker.execute(() -> {
            try { show(job.run()); }
            catch (Exception e) { DebugLog.error("operation", e); show("操作失败：" + e.getMessage()); }
            finally { runtime.operationRunning = false; runOnUiThread(() -> { busy = false; for (View v : operations) v.setEnabled(true); if (ui != null) ui.refresh(); }); }
        });
    }
    static int port(EditText field) {
        int n;
        try { n = Integer.parseInt(field.getText().toString().trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("请输入有效端口"); }
        if (n < 1 || n > 65535) throw new IllegalArgumentException("端口必须为 1–65535"); return n;
    }
    private String host() { String value = host.getText().toString().trim(); if (value.isEmpty()) throw new IllegalArgumentException("请输入目标 IP"); return value; }
    void requireConnected() throws IOException { requireConnected(runtime.current()); }
    static void requireConnected(DeviceConnection device) throws IOException { if (device == null || !device.connected) throw new IOException("请先连接 ADB"); }
    private void keepConnections() {
        FloatingWindowService.keep(this);
        SharedPreferences permissions = UiPreferences.get(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !permissions.getBoolean("notificationsRequested", false)) {
            permissions.edit().putBoolean("notificationsRequested", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 21);
        }
    }
    void connectNetwork() {
        try {
            String address = host(); int number = port(port);
            keepConnections();
            task(() -> {
                DebugLog.write("adb connect host=" + address + " port=" + number);
                DeviceConnection device = runtime.connect(address, number); remember(address, number, false);
                return "ADB 已连接：" + device.target + "\n" + device.shell("getprop ro.product.model; getprop ro.build.version.release");
            });
        } catch (Exception e) { show(e.getMessage()); }
    }
    void pair() {
        try {
            String address = host(), pin = code.getText().toString().trim(); int number = port(pairPort);
            if (!pin.matches("[0-9]{6}")) throw new IllegalArgumentException("请输入 6 位配对码");
            code.setText("");
            task(() -> {
                DebugLog.write("adb pairing start host=" + address + " port=" + number);
                if (!runtime.adb.pair(address, number, pin)) throw new IOException("配对失败");
                remember(address, 0, true); DebugLog.write("adb pairing succeeded");
                return "配对成功，身份已保存。请输入无线调试首页的连接端口，再点击连接。";
            });
        } catch (Exception e) { show(e.getMessage()); }
    }
    private synchronized void remember(String address, int number, boolean paired) throws JSONException {
        JSONArray existing = new JSONArray(preferences.getString("history", "[]")), updated = new JSONArray();
        JSONObject item = new JSONObject().put("host", address).put("port", number).put("paired", paired).put("lastUsed", System.currentTimeMillis());
        for (int i = 0; i < existing.length(); i++) {
            JSONObject old = existing.getJSONObject(i);
            if (address.equals(old.optString("host"))) {
                if (number == 0) item.put("port", old.optInt("port", 0));
                item.put("paired", paired || old.optBoolean("paired"));
                if (old.has("alias")) item.put("alias", old.optString("alias"));
            } else if (updated.length() < 19) updated.put(old);
        }
        JSONArray result = new JSONArray().put(item); for (int i = 0; i < updated.length(); i++) result.put(updated.get(i));
        SharedPreferences.Editor editor = preferences.edit().putString("history", result.toString());
        if (number > 0) editor.putString("host", address).putString("port", String.valueOf(number));
        if (!editor.commit()) throw new IllegalStateException("保存设备记录失败");
    }
    void usbDevices() {
        UsbManager usb = (UsbManager)getSystemService(USB_SERVICE); List<UsbDevice> devices = new ArrayList<>();
        for (UsbDevice device : usb.getDeviceList().values()) if (AdbUsb.isAdbDevice(device)) devices.add(device);
        if (devices.isEmpty()) { show("未发现 USB ADB 设备，请检查 OTG、数据线和被控端 USB 调试"); return; }
        String[] labels = new String[devices.size()]; for (int i = 0; i < labels.length; i++) labels[i] = devices.get(i).getDeviceName() + " " + devices.get(i).getProductName();
        new AlertDialog.Builder(this).setTitle("USB ADB 设备").setItems(labels, (d,i) -> {
            UsbDevice device = devices.get(i);
            if (usb.hasPermission(device)) connectUsb(device);
            else usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, new Intent(USB_PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }).show();
    }
    private void connectUsb(UsbDevice usbDevice) {
        keepConnections();
        task(() -> {
            String target = "USB:" + usbDevice.getDeviceName(); DeviceConnection device = runtime.connection(target);
            if (device == null || !device.adb.isConnected()) {
                AdbManager manager = new AdbManager(new Identity(this));
                try {
                    if (!manager.connectUsb(this, usbDevice)) throw new IOException("USB 连接超时，请在被控端允许 USB 调试");
                    device = runtime.register(target, manager);
                } catch (Exception e) { manager.disconnect(); throw e; }
            } else runtime.select(device);
            return "USB ADB 已连接：" + target + "\n" + device.shell("getprop ro.product.model; ip -f inet addr show wlan0");
        });
    }
    void scan() {
        try {
            if (scanner != null) scanner.close();
            String address = host(); int from = port(scanFrom), to = port(scanTo);
            PortScanner current = new PortScanner(); scanner = current; show("扫描中…");
            current.start(address, from, to, new PortScanner.Listener() {
                public void progress(int done, int total) { if (scanner == current) show("已扫描 " + done + "/" + total); }
                public void finished(List<Integer> found, boolean cancelled) { runOnUiThread(() -> {
                    if (isDestroyed() || scanner != current) return;
                    scanner = null; show((cancelled ? "扫描已取消，" : "扫描完成，") + "发现 " + found.size() + " 个开放端口");
                    String[] labels = new String[found.size()]; for (int i = 0; i < labels.length; i++) labels[i] = address + ":" + found.get(i);
                    new AlertDialog.Builder(MainActivity.this).setTitle("选择开放端口（需确认是否为 ADB）").setItems(labels, (d,i) -> {
                        new AlertDialog.Builder(MainActivity.this).setMessage(labels[i]).setPositiveButton("填入连接端口", (a,b) -> { host.setText(address); port.setText(String.valueOf(found.get(i))); ui.select("connect"); })
                                .setNeutralButton("填入配对端口", (a,b) -> { host.setText(address); pairPort.setText(String.valueOf(found.get(i))); ui.select("pair"); }).setNegativeButton("取消", null).show();
                    }).setNegativeButton("关闭", null).show();
                    DebugLog.write("scan complete total=" + (to - from + 1) + " open=" + found.size() + " cancelled=" + cancelled);
                }); }
            });
        } catch (Exception e) { if (scanner != null) scanner.close(); scanner = null; show(e.getMessage()); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20) {
            awaitingOverlay = false;
            if (android.provider.Settings.canDrawOverlays(this)) startRemote(overlayTarget, true);
            else show("未授予悬浮窗权限，仍可使用全屏控制");
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); String path = pendingPath;
        if (request == 10) task(() -> {
            requireConnected(); try (InputStream in = getContentResolver().openInputStream(uri)) {
                long count = AdbSync.push(fileDevice().adb, in, path); return "上传完成：" + count + " 字节";
            }
        });
        else if (request == 11) task(() -> {
            requireConnected(); try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                long count = AdbSync.pull(fileDevice().adb, path, out); return "下载完成：" + count + " 字节";
            }
        });
    }
    @Override protected void onResume() {
        super.onResume();
        if (ui != null) { ui.refresh(); if (!runtime.lastMessage.isEmpty()) show(runtime.lastMessage); if (runtime.operationRunning) status.post(operationWatcher); }
    }
    @Override protected void onPause() { if (status != null) status.removeCallbacks(operationWatcher); super.onPause(); }
    boolean isBusy() { return busy || runtime.operationRunning; }
    void disconnect() { disconnect(runtime.current()); }
    void disconnect(DeviceConnection device) {
        if (device == null) return;
        FloatingWindowService.closeRemote(this, device.target);
        task(() -> { runtime.disconnect(device); return "已断开 ADB：" + device.target; });
    }
    void cancelOperation() {
        if (scanner != null) scanner.close();
        runtime.cancelPendingConnect();
        DeviceConnection device = runtime.current(); if (device == null) { show("正在取消连接…"); return; }
        FloatingWindowService.closeRemote(this, device.target);
        new Thread(() -> {
            try { runtime.disconnect(device); show("连接已断开：" + device.target); }
            catch (Exception e) { DebugLog.error("cancel", e); }
        }, "adb-cancel").start();
    }
    void tcpip() { DeviceConnection device = runtime.current(); task(() -> {
        requireConnected(device);
        try (AdbStream stream = device.adb.openStream("tcpip:5555")) {
            byte[] b = new byte[1024]; int n = stream.openInputStream().read(b);
            return "已请求开启 TCP 5555；请填写被控端 Wi-Fi IP 后重连。" + (n > 0 ? "\n" + new String(b, 0, n, "UTF-8") : "");
        }
    }); }
    void exportLogs() {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://org.scrcpy.android.logs/debug.log"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(share, "导出调试日志"));
    }
    void chooseFile(boolean upload) {
        try {
            DeviceConnection device = runtime.current(); requireConnected(device);
            pendingDeviceTarget = device.target; pendingPath = remotePath.getText().toString();
            if (upload) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 10);
            else {
                String name = pendingPath.substring(pendingPath.lastIndexOf('/') + 1);
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE, name.isEmpty() ? "download.bin" : name), 11);
            }
        } catch (Exception e) { show(e.getMessage()); }
    }
    void cancelScan() { if (scanner != null) scanner.close(); }
    private DeviceConnection fileDevice() throws IOException {
        DeviceConnection device = runtime.connection(pendingDeviceTarget);
        if (device == null || !device.connected) throw new IOException("文件传输目标已断开"); return device;
    }
    void startRemote(boolean floating) { DeviceConnection device = runtime.current(); startRemote(device == null ? null : device.target, floating); }
    void startLatestRemote() { DeviceConnection device = runtime.latest(); startRemote(device == null ? null : device.target, false); }
    void startRemote(String target, boolean floating) {
        try {
            DeviceConnection device = runtime.connection(target);
            if (device == null || !device.connected) throw new IOException("请先连接 ADB");
            if (floating && FloatingWindowService.isShowing(device.target)) { show("该设备小窗已在运行"); return; }
            if (floating && !android.provider.Settings.canDrawOverlays(this)) {
                awaitingOverlay = true; overlayTarget = device.target;
                Toast.makeText(this, "请允许在其他应用上层显示，以便使用可触控小窗", Toast.LENGTH_LONG).show();
                startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), 20); return;
            }
            preferences.edit().putBoolean("audio", audio.isChecked()).apply();
            if (floating) {
                if (runtime.fullscreen != null && runtime.fullscreen.device == device) { runtime.fullscreen.requestFloatingWindow(); return; }
                startForegroundService(new Intent(this, FloatingWindowService.class).putExtra("target", device.target).putExtra("audio", audio.isChecked()));
            } else startActivity(RemoteActivity.intent(this, device.target, audio.isChecked()));
        } catch (Exception e) { show(e.getMessage()); }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("pendingPath", pendingPath); out.putBoolean("awaitingOverlay", awaitingOverlay); out.putString("overlayTarget", overlayTarget); out.putString("pendingDeviceTarget", pendingDeviceTarget);
        if (ui != null) ui.save(out);
    }
    @Override protected void onDestroy() { if (scanner != null) scanner.close(); if (runtime != null) try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { } super.onDestroy(); }
}
