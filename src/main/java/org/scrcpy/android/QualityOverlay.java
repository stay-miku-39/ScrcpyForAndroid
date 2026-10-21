package org.scrcpy.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.widget.TextView;
import java.util.Locale;

/** Fullscreen foreground UI owns sampling. A hidden/floating/background output has no sampler. */
// Fullscreen deliberately uses the platform theme; AppCompat wraps only floating controls.
@android.annotation.SuppressLint("AppCompatCustomView")
final class QualityOverlay extends TextView {
    private ScrcpySession session;
    private RemoteDisplay owner;
    private int activeMask;
    private long lastProbe;
    volatile QualityStats.Sample lastSample;
    private final Runnable tick = new Runnable() { public void run() {
        if (activeMask == 0 || session == null || session.isClosed() || owner == null || !owner.ownsSession()) { stop(); return; }
        if (!session.isStarted()) { setText("质量参数：等待连接…"); postDelayed(this, 1000); return; }
        QualityStats.Sample sample = session.quality.sample(); lastSample = sample; StringBuilder text = new StringBuilder();
        if (has(QualityStats.FPS)) line(text, "FPS 收 %.1f / 解 %.1f / Surface %.1f", sample.receivedFps, sample.decodedFps, sample.presentedFps);
        if (has(QualityStats.VIDEO_RATE)) line(text, "视频 %.2f Mbps · %.1f 媒体包/s", sample.videoMbps, sample.videoPps);
        if (has(QualityStats.VIDEO_DELAY)) line(text, "本地视频解码 %s ms（含排队）\n远端编码延迟：未上报", number(sample.videoDelayMs));
        if (has(QualityStats.INPUT_WAIT)) line(text, "视频输入等待 %s ms", number(sample.inputWaitMs));
        if (has(QualityStats.AUDIO_RATE)) line(text, "音频 %.1f kbps · %.1f 媒体包/s", sample.audioKbps, sample.audioPps);
        if (has(QualityStats.AUDIO_DELAY)) line(text, "音频处理 %s ms（含解码/输出等待）\n%s", number(sample.audioProcessingMs), session.qualityAudioBuffer());
        if (has(QualityStats.CONTROL)) line(text, "控制 %.1f 写入/s · %.0f B/s · 队列 %d\n排队 %s ms · 写入 %s ms", sample.controlPps, sample.controlBps, session.qualityControlDepth(), number(sample.controlQueueMs), number(sample.controlWriteMs));
        if (has(QualityStats.RTT)) {
            line(text, "ADB 往返 %s ms（含 Shell 开销）", number(sample.rttMs));
            if (SystemClock.elapsedRealtime() - lastProbe >= 3000) { lastProbe = SystemClock.elapsedRealtime(); session.requestQualityProbe(); }
        }
        if (has(QualityStats.CODECS)) line(text, "%s", session.qualityCodecDescription());
        setText(text.toString()); postDelayed(this, 1000);
    } };
    QualityOverlay(Context context) {
        super(context); setTag("qualityOverlay"); setTextSize(12); setTypeface(Typeface.MONOSPACE);
        setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setShadowLayer(1, 0, 0, Color.BLACK);
        setClickable(false); setFocusable(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
    }
    void bind(ScrcpySession next, RemoteDisplay output, boolean foreground) {
        SharedPreferences prefs = UiPreferences.get(getContext());
        setTextColor(UiPreferences.qualityColor(prefs)); setAlpha(UiPreferences.percent(prefs, UiPreferences.QUALITY_OPACITY) / 100f);
        int mask = foreground && next != null && output != null && output.ownsSession() ? UiPreferences.qualityMask(prefs) : 0;
        if (session == next && owner == output && activeMask == mask) return;
        stop(); session = next; owner = output; activeMask = mask;
        if (mask != 0) { session.configureQuality(owner, mask); setVisibility(VISIBLE); setText("质量参数：采样中…"); postDelayed(tick, 1000); }
    }
    private boolean has(int bit) { return (activeMask & bit) != 0; }
    int activeMask() { return activeMask; }
    void stop() {
        removeCallbacks(tick);
        if (session != null && owner != null) session.configureQuality(owner, 0);
        activeMask = 0; setVisibility(GONE);
        lastSample = null;
    }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
    private static String number(double value) { return value < 0 ? "—" : String.format(Locale.ROOT, "%.1f", value); }
    private static void line(StringBuilder text, String format, Object... values) { if (text.length() > 0) text.append('\n'); text.append(String.format(Locale.ROOT, format, values)); }
}
