package org.scrcpy.android;

import android.content.*;
import android.content.pm.PackageInfo;
import android.media.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide discovery runs once off the UI thread; no continuous capability polling. */
final class CodecCatalog {
    private static final AtomicBoolean loading = new AtomicBoolean();
    private static final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
    static volatile CodecCatalog cached;
    final List<CodecPolicy.Component> components;
    final boolean successful;
    private CodecCatalog(List<CodecPolicy.Component> components, boolean successful) { this.components=Collections.unmodifiableList(new ArrayList<>(components)); this.successful=successful; }
    static void start(Context context, Runnable ready) {
        if (cached != null) { ready.run(); return; }
        callbacks.add(ready);
        if (!loading.compareAndSet(false, true)) { if (cached != null && callbacks.remove(ready)) ready.run(); return; }
        Context app = context.getApplicationContext();
        new Thread(() -> {
            CodecCatalog result = discover();
            if (result.successful) {
                try {
                    PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(),0);
                    boolean fresh = info.firstInstallTime == info.lastUpdateTime
                            && app.getSharedPreferences("devices",Context.MODE_PRIVATE).getAll().isEmpty() && UiPreferences.get(app).getAll().isEmpty();
                    initialize(StreamPreferences.get(app), app.getSharedPreferences("codec-discovery",Context.MODE_PRIVATE), result, fresh);
                } catch (Exception error) { DebugLog.error("codec defaults",error); }
            }
            cached=result;
            for (Runnable callback : callbacks) { if (callbacks.remove(callback)) callback.run(); }
        },"codec-discovery").start();
    }
    static CodecCatalog discover() {
        List<CodecPolicy.Component> values = new ArrayList<>();
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isAlias()) continue;
                for (String mime : info.getSupportedTypes()) {
                    for (String codec : StreamSettings.VIDEOS) if (StreamSettings.videoMime(codec).equalsIgnoreCase(mime)) values.add(new CodecPolicy.Component(info.getName(),codec,info.isEncoder(),info.isHardwareAccelerated()));
                    for (String codec : StreamSettings.AUDIOS) if (!"raw".equals(codec) && StreamSettings.audioMime(codec).equalsIgnoreCase(mime)) values.add(new CodecPolicy.Component(info.getName(),codec,info.isEncoder(),info.isHardwareAccelerated()));
                }
            }
            return new CodecCatalog(values,true);
        } catch (RuntimeException error) { DebugLog.error("local codec discovery",error); return new CodecCatalog(values,false); }
    }
    static boolean initialize(SharedPreferences stream, SharedPreferences state, CodecCatalog catalog, boolean freshInstall) {
        synchronized (StreamPreferences.class) {
            if (!catalog.successful || state.getBoolean("initialized",false)) return false;
            boolean apply = freshInstall && stream.getAll().isEmpty();
            if (apply) {
                Map<String,String> values = CodecPolicy.recommend(catalog.components); StreamSettings.validate(values);
                SharedPreferences.Editor editor = stream.edit(); for (Map.Entry<String,String> e : values.entrySet()) editor.putString(e.getKey(),e.getValue()); editor.apply();
            }
            state.edit().putBoolean("initialized",true).apply(); return apply;
        }
    }
    boolean hardwareDecode(String format) { return components.stream().anyMatch(c -> !c.encoder && c.hardware && c.format.equals(format)); }
    String summary() {
        List<String> dec = new ArrayList<>(), enc = new ArrayList<>();
        for (String format : new String[]{"h264","h265","av1","vp8","vp9","aac","opus","flac"}) {
            if (components.stream().anyMatch(c -> !c.encoder && c.hardware && c.format.equals(format))) dec.add(format);
            if (components.stream().anyMatch(c -> c.encoder && c.hardware && c.format.equals(format))) enc.add(format);
        }
        return "本机硬件解码：" + (dec.isEmpty()?"未报告":String.join(" / ",dec)) + "\n本机硬件编码：" + (enc.isEmpty()?"未报告":String.join(" / ",enc))
                + "\n硬件标记来自系统声明；当前投屏的编码运行在被控端，连接后单独检查。模拟器或组件不兼容时可能使用软件回退，实际组件见质量参数。";
    }
}
