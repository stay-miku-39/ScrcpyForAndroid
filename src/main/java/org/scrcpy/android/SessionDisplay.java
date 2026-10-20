package org.scrcpy.android;

import android.content.*;
import java.io.IOException;
import java.util.concurrent.*;

/** Device-bound size lease. Durable recovery is retained when the transport is lost. */
final class SessionDisplay {
    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, SessionDisplay> OWNERS = new ConcurrentHashMap<>();
    private final DeviceConnection device;
    private final SharedPreferences recovery;
    private final String key;
    private final Object lock;
    private String restore;
    SessionDisplay(Context context, DeviceConnection device) {
        this.device = device; recovery = context.getSharedPreferences("display-recovery", Context.MODE_PRIVATE); key = device.target;
        lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
    }
    DisplaySizeState acquire() throws Exception {
        synchronized (lock) {
            SessionDisplay previous = OWNERS.get(key);
            if (previous != null && previous != this) previous.restore();
            OWNERS.put(key, this);
            String pending = recovery.getString(key, "");
            if (!pending.isEmpty()) { restore = pending; restore(); if (restore != null) throw new IOException("上次显示尺寸尚未恢复"); }
            try { return DisplaySizeState.parse(device.shell("wm size")); }
            catch (Exception error) {
                RemoteOrientation.Info info = RemoteOrientation.parse(device.shell("cmd display get-displays"));
                if (info == null) throw new IOException("无法读取被控显示尺寸", error);
                int width = info.width, height = info.height;
                if ((info.rotation & 1) != 0) { int swap = width; width = height; height = swap; }
                return DisplaySizeState.withoutOverrideAccess(width, height);
            }
        }
    }
    void apply(DisplaySizeState original, int width, int height) throws Exception {
        synchronized (lock) {
            if (OWNERS.get(key) != this) throw new IOException("显示尺寸所有权已转移");
            if (!DisplaySizeState.validRestore(original.restore)) throw new IOException("无法保存原始覆盖值，改用其他显示方式");
            restore = original.restore;
            if (!recovery.edit().putString(key, restore).commit()) throw new IOException("无法保存原始尺寸，已取消覆盖");
            checked("wm size " + width + "x" + height);
            DisplaySizeState actual = DisplaySizeState.parse(device.shell("wm size"));
            if (actual.width != width || actual.height != height) throw new IOException("系统未接受请求的显示尺寸");
        }
    }
    void requireRestored() throws IOException { if (restore != null) throw new IOException("原始尺寸恢复失败，保留恢复记录"); }
    void restore() {
        synchronized (lock) {
            if (restore == null) return;
            if (OWNERS.get(key) != this) return;
            try {
                if (!DisplaySizeState.validRestore(restore)) throw new IOException("无效的尺寸恢复记录");
                checked("wm size " + restore);
                DisplaySizeState actual = DisplaySizeState.parse(device.shell("wm size"));
                if (!restore.equals(actual.restore)) throw new IOException("恢复尺寸未被系统接受");
                recovery.edit().remove(key).commit(); restore = null;
                DebugLog.write("remote display size restored");
            } catch (Exception error) { DebugLog.error("restore remote display; retained for next session", error); }
        }
    }
    void release() { synchronized (lock) { restore(); OWNERS.remove(key, this); } }
    private void checked(String command) throws Exception {
        String result = device.shell(command + "; echo SFA_SIZE_EXIT=$?");
        if (!result.contains("SFA_SIZE_EXIT=0")) throw new IOException("被控系统拒绝调整显示尺寸");
    }
}
