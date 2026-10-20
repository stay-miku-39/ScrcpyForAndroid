package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;
import java.util.*;

/** One foreground owner for all transports, background mirrors and overlay windows. */
public class FloatingWindowService extends Service {
    static final String CLOSE = "org.scrcpy.android.CLOSE_WINDOW", KEEP = "org.scrcpy.android.KEEP_CONNECTIONS";
    static final String CLOSE_REMOTE = "org.scrcpy.android.CLOSE_REMOTE", DISCONNECT = "org.scrcpy.android.DISCONNECT_ALL";
    private static FloatingWindowService instance;
    private final Map<String, FloatingWindow> windows = new LinkedHashMap<>();
    private RuntimeState runtime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private String notificationState = "";
    static boolean isShowing() { return instance != null && !instance.windows.isEmpty(); }
    static boolean isShowing(String target) { return instance != null && instance.windows.containsKey(target); }
    static FloatingWindow current() { return instance == null || instance.windows.isEmpty() ? null : new ArrayList<>(instance.windows.values()).get(instance.windows.size() - 1); }
    static void removeTransferred(String target) { if (instance != null) instance.closeWindow(target); }
    static void closeRemote(Context context, String target) {
        if (instance != null) instance.closeWindow(target);
        try {
            RuntimeState state = RuntimeState.get(context); DeviceConnection device = state.connection(target);
            if (device != null) {
                if (state.fullscreen != null && state.fullscreen.device == device) state.fullscreen.finish();
                device.closeMirror();
            }
        }
        catch (Exception e) { DebugLog.error("close remote", e); }
    }
    static void keep(Context context) { context.startForegroundService(new Intent(context, FloatingWindowService.class).setAction(KEEP)); }
    FloatingWindow window(String target) { return windows.get(target); }
    int windowCount() { return windows.size(); }
    void closeWindow(String target) { FloatingWindow window = windows.remove(target); if (window != null) window.close(); }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        try { runtime = RuntimeState.get(this); } catch (Exception error) { DebugLog.error("service init", error); }
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("remote-window", "ADB 与远程控制", NotificationManager.IMPORTANCE_LOW));
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "org.scrcpy.android:remote");
        handler.postDelayed(tick, 1000);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(42, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        if (runtime == null || intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction(), target = intent.getStringExtra("target");
        if (DISCONNECT.equals(action)) {
            runtime.cancelPendingConnect();
            if (runtime.fullscreen != null) runtime.fullscreen.finish();
            for (String key : new ArrayList<>(windows.keySet())) closeWindow(key);
            runtime.worker.execute(() -> { for (DeviceConnection device : runtime.connections()) try { runtime.disconnect(device); } catch (Exception error) { DebugLog.error("disconnect", error); } });
        } else if (CLOSE.equals(action)) {
            if (target == null) for (String key : new ArrayList<>(windows.keySet())) closeWindow(key); else closeWindow(target);
        } else if (CLOSE_REMOTE.equals(action)) {
            for (DeviceConnection device : runtime.connections()) closeRemote(this, device.target);
        } else if (!KEEP.equals(action)) {
            ResultReceiver result = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("result", ResultReceiver.class) : intent.getParcelableExtra("result");
            try {
                DeviceConnection device = target == null ? runtime.current() : runtime.connection(target);
                if (device == null || !device.connected) throw new IllegalStateException("ADB 未连接");
                if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("请先授予悬浮窗权限");
                if (windows.containsKey(device.target)) { if (result != null) result.send(Activity.RESULT_OK, null); }
                else windows.put(device.target, new FloatingWindow(this, device, intent.getBooleanExtra("audio", true), result));
                if (intent.getBooleanExtra("audio", true)) runtime.acquireAudioFocus(this);
            } catch (Exception error) {
                DebugLog.error("floating start", error);
                if (result != null) { Bundle data = new Bundle(); data.putString("error", error.getMessage()); result.send(Activity.RESULT_CANCELED, data); }
            }
        }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        int count = runtime == null ? 0 : runtime.connections().size();
        DeviceConnection latest = runtime == null ? null : runtime.latest();
        Intent open = latest == null ? new Intent(this, MainActivity.class) : RemoteActivity.intent(this, latest.target, getSharedPreferences("devices", MODE_PRIVATE).getBoolean("audio", true));
        PendingIntent resume = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent manage = PendingIntent.getActivity(this, 3, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent close = PendingIntent.getService(this, 2, new Intent(this, FloatingWindowService.class).setAction(CLOSE_REMOTE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent disconnect = PendingIntent.getService(this, 4, new Intent(this, FloatingWindowService.class).setAction(DISCONNECT), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "remote-window").setSmallIcon(R.drawable.ic_launcher).setContentTitle("ADB 与远程控制运行中")
                .setContentText(count + " 个 ADB · " + windows.size() + " 个小窗；点击恢复最新设备").setOngoing(true).setContentIntent(resume)
                .addAction(new Notification.Action.Builder(null, "管理设备", manage).build())
                .addAction(new Notification.Action.Builder(null, "停止全部远控", close).build())
                .addAction(new Notification.Action.Builder(null, "断开全部 ADB", disconnect).build()).build();
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (runtime == null) return;
            boolean active = false;
            for (DeviceConnection device : runtime.connections()) {
                if (device.hasMirror()) active = true;
                else if (device.session != null) { closeWindow(device.target); device.closeMirror(); }
            }
            if (active && !wake.isHeld()) wake.acquire(); else if (!active && wake.isHeld()) wake.release();
            runtime.releaseAudioFocusIfIdle(FloatingWindowService.this);
            if (runtime.connections().isEmpty() && !runtime.operationRunning) { stopSelf(); return; }
            DeviceConnection latest = runtime.latest();
            String state = runtime.connections().size() + "/" + windows.size() + "/" + (latest == null ? "" : latest.target);
            if (!state.equals(notificationState)) {
                notificationState = state;
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(42, notification());
            }
            if (!runtime.operationRunning) runtime.worker.execute(() -> {
                for (DeviceConnection device : runtime.connections()) if (!device.adb.isConnected()) {
                    handler.post(() -> closeWindow(device.target));
                    try { runtime.disconnect(device); } catch (Exception e) { DebugLog.error("transport ended", e); }
                }
            });
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); for (FloatingWindow window : windows.values()) window.configurationChanged(); }
    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        for (String target : new ArrayList<>(windows.keySet())) closeWindow(target);
        if (runtime != null) for (DeviceConnection device : runtime.connections()) device.closeMirror();
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE); if (instance == this) instance = null; super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
