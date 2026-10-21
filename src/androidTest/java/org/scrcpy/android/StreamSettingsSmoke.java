package org.scrcpy.android;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import static org.scrcpy.android.UiControlsSmoke.check;
import static org.scrcpy.android.UiControlsSmoke.until;

/** Only the instrumentation APK exposes these checks. Restores target and saved settings. */
final class StreamSettingsSmoke {
    private final Instrumentation test;
    private final Context context;
    private final RuntimeState state;
    private final DeviceConnection device;
    private RemoteActivity activity;
    private StreamSettingsSmoke(Instrumentation test, Context context, RuntimeState state, DeviceConnection device) { this.test = test; this.context = context; this.state = state; this.device = device; }
    static String run(Instrumentation test, Context context, RuntimeState state, String host, int port, String stage) throws Exception {
        DeviceConnection device = state.connect(host, port); check(device != null, "ADB connection failed");
        Map<String, ?> saved = StreamPreferences.get(context).getAll(), ui = UiPreferences.get(context).getAll();
        String original = device.shell("wm size"), identity = device.adb.fingerprint();
        StreamSettingsSmoke smoke = new StreamSettingsSmoke(test, context, state, device);
        try {
            UiPreferences.get(context).edit().putBoolean("backgroundKeepAlive", true).putBoolean("backgroundFloating", false).apply();
            if ("main".equals(stage)) smoke.main();
            else if ("virtual".equals(stage)) smoke.virtual();
            else if ("codecs".equals(stage)) smoke.codecs();
            else if ("fallback".equals(stage)) smoke.fallback();
            else if ("presets".equals(stage)) smoke.presets();
            else if ("stages".equals(stage)) smoke.stages();
            else if ("quality".equals(stage)) smoke.quality();
            else if ("position".equals(stage)) smoke.position();
            else if ("performance".equals(stage)) smoke.performance();
            else if ("rateControl".equals(stage)) smoke.rateControl();
            else if ("touchHold".equals(stage)) smoke.touchHold();
            else throw new IllegalArgumentException("Unknown mediaCase: " + stage);
            check(identity.equals(device.adb.fingerprint()), "Media settings changed ADB identity");
            check(original.equals(device.shell("wm size")), "Target size was not restored");
            return "PASS media " + stage + ", persistent identity, target size restored";
        } finally {
            smoke.close(); restore(StreamPreferences.get(context), saved); restore(UiPreferences.get(context), ui); DebugLog.setEnabled(UiPreferences.get(context).getBoolean(UiPreferences.DEBUG_LOG, false));
            device.shell("am force-stop org.scrcpy.android.test");
        }
    }
    static void restore(SharedPreferences prefs, Map<String, ?> saved) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) edit.putString(key, (String)value); else if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) edit.putInt(key, (Integer)value); else if (value instanceof Float) edit.putFloat(key, (Float)value); else if (value instanceof Long) edit.putLong(key, (Long)value);
        }
        edit.commit();
    }
    private void configure(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>(StreamSettings.DEFAULTS);
        values.put("resolution", "custom"); values.put("customWidth", "360"); values.put("customHeight", "640"); values.put("maxSize", "640"); values.put("fps", "30");
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i+1]); StreamPreferences.save(context, values);
    }
    private void stages() throws Exception {
        UiPreferences.get(context).edit().putBoolean(UiPreferences.DEBUG_LOG, true).apply(); DebugLog.setEnabled(true);
        String marker = "TEST phase-run-" + System.nanoTime(); DebugLog.write(marker);
        configure();
        ScrcpySession current = start(true);
        until(() -> {
            String text=new String(java.nio.file.Files.readAllBytes(DebugLog.file(context).toPath()),java.nio.charset.StandardCharsets.UTF_8);
            return text.contains(marker) && text.substring(text.lastIndexOf(marker)).contains("connect stage=first-frame result=ok");
        }, 5, "Phase log not flushed");
        String log = new String(java.nio.file.Files.readAllBytes(DebugLog.file(context).toPath()), java.nio.charset.StandardCharsets.UTF_8);
        log=log.substring(log.lastIndexOf(marker));
        for (String stage : new String[]{"upload", "capabilities", "display-query", "display-apply", "server-prepare", "server-launch", "video-channel", "handshake", "decoder-init", "first-frame"})
            check(log.contains("connect stage=" + stage + " result=ok durationMs="), "Missing successful phase: " + stage);
        check(log.contains("connect settings target=" + device.target) && log.contains("requested=360x640"), "Missing effective startup parameters");
        check(current.currentRenderedFrames() > 0, "Phase reporting prevented playback");
        close();
    }
    private void quality() throws Exception {
        configure("audioCodec", "opus");
        UiPreferences.get(context).edit().remove(UiPreferences.QUALITY_X).remove(UiPreferences.QUALITY_Y).apply();
        UiPreferences.get(context).edit().putBoolean(UiPreferences.QUALITY_ENABLED, false).putInt(UiPreferences.QUALITY_ITEMS, QualityStats.VIDEO_RATE).apply();
        ScrcpySession current = start(true);
        check(current.quality.mask == 0 && activity.qualityOverlay.getVisibility() == View.GONE, "Disabled panel still collects statistics");
        test.runOnMainSync(() -> { activity.showQualityMenu(); ((CheckBox)activity.menuContent().findViewWithTag("qualityMaster")).setChecked(true); });
        until(() -> current.quality.mask == QualityStats.VIDEO_RATE && activity.qualityOverlay.getText().toString().contains("Mbps"), 8, "Selected rate did not appear");
        QualityStats.Sample onlyVideo = current.quality.sample();
        check(onlyVideo.decodedFps == 0 && onlyVideo.audioKbps == 0 && onlyVideo.controlPps == 0 && onlyVideo.videoDelayMs < 0, "Unselected statistics accumulated");
        test.runOnMainSync(() -> {
            activity.showQualityMenu();
            for (int bit : QualityStats.ITEMS) { CheckBox item = activity.menuContent().findViewWithTag("qualityItem" + bit); item.setChecked(true); }
            activity.ball.performClick();
        });
        until(() -> current.quality.mask == QualityStats.ALL, 5, "Quality selections not applied");
        touch(activity.display);
        until(() -> activity.qualityOverlay.getText().toString().contains("本地视频解码") && activity.qualityOverlay.getText().toString().contains("ADB 往返"), 8, "Quality details missing");
        until(() -> {
            QualityStats.Sample all = activity.qualityOverlay.lastSample;
            return all != null && all.mask == QualityStats.ALL && all.decodedFps > 0 && all.audioKbps > 0 && all.videoDelayMs >= 0 && all.audioProcessingMs >= 0;
        }, 8, "Selected live metrics have no samples: " + activity.qualityOverlay.getText());
        until(() -> activity.qualityOverlay.lastSample != null && activity.qualityOverlay.lastSample.rttMs >= 0, 8, "Selected ADB round-trip probe produced no sample");
        device.shell("logcat -c");
        test.runOnMainSync(() -> {
            View root=(View)activity.qualityOverlay.getParent();
            // Stay below the target's status bar while touching through the
            // overlay. The video's very top maps to system chrome, not the probe.
            float x=activity.display.getX()+activity.video.getX()+activity.video.getWidth()*.2f;
            float y=activity.display.getY()+activity.video.getY()+activity.video.getHeight()*.15f;
            check(x>=activity.qualityOverlay.getX() && x<activity.qualityOverlay.getRight()
                    && y>=activity.qualityOverlay.getY() && y<activity.qualityOverlay.getBottom(), "Touch fixture missed quality text bounds");
            long down=SystemClock.uptimeMillis();
            for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0); root.dispatchTouchEvent(event); event.recycle();
            }
        });
        until(() -> device.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("touch action=1 pointers=1"),5,"Quality text intercepted remote touch");
        test.runOnMainSync(() -> {
            check(activity.qualityOverlay.getBackground() instanceof android.graphics.drawable.ColorDrawable && ((android.graphics.drawable.ColorDrawable)activity.qualityOverlay.getBackground()).getColor() == android.graphics.Color.TRANSPARENT, "Panel background is opaque");
            check(activity.qualityOverlay.getX() <= MainUi.dp(context, 40) && activity.qualityOverlay.getY() <= MainUi.dp(context, 60), "Panel is not top-left");
        });
        android.graphics.Bitmap capture = test.getUiAutomation().takeScreenshot(); check(capture != null, "Quality screenshot missing");
        java.io.File folder = context.getExternalFilesDir("quality-smoke");
        try (java.io.OutputStream out = new java.io.FileOutputStream(new java.io.File(folder, "fullscreen-quality.png"))) { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { capture.recycle(); }
        MainActivity main = (MainActivity)test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            until(() -> current.quality.mask == 0, 5, "Background output retained statistics");
            test.runOnMainSync(() -> {
                main.ui.select("settings"); View root = main.getWindow().getDecorView();
                ((EditText)root.findViewWithTag(UiPreferences.QUALITY_COLOR)).setText("#00FF88");
                ((SeekBar)root.findViewWithTag(UiPreferences.QUALITY_OPACITY)).setProgress(62);
                check(UiPreferences.qualityColor(UiPreferences.get(context)) != android.graphics.Color.rgb(0,255,136), "Unsaved font color applied");
                root.findViewWithTag("saveStreamSettings").performClick();
            });
        } finally { test.runOnMainSync(main::finish); }
        until(() -> activity.hasWindowFocus() && activity.display.ownsSession() && current.quality.mask == QualityStats.ALL, 12, "Foreground did not restart selected metrics");
        test.runOnMainSync(() -> check(activity.qualityOverlay.getCurrentTextColor() == android.graphics.Color.rgb(0,255,136) && Math.abs(activity.qualityOverlay.getAlpha()-.62f)<.001f, "Saved font appearance not applied"));
        test.runOnMainSync(activity::requestFloatingWindow);
        until(() -> FloatingWindowService.isShowing() && activity.isFinishing() && FloatingWindowService.current().display.ownsSession() && current.quality.mask == 0, 12, "Floating output did not complete ownership handoff or retained statistics");
        FloatingWindow floating = FloatingWindowService.current();
        check(floating.windowView().findViewWithTag("qualityOverlay") == null, "Floating output contains quality panel");
        android.app.Instrumentation.ActivityMonitor monitor = test.addMonitor(RemoteActivity.class.getName(), null, false);
        try {
            test.runOnMainSync(() -> floating.fullscreenButton.performClick());
            activity = (RemoteActivity)test.waitForMonitorWithTimeout(monitor, 15000); check(activity != null, "Fullscreen restore failed");
            until(() -> activity.session == current && current.quality.mask == QualityStats.ALL, 12, "Handoff replaced session or lost selections");
            test.runOnMainSync(() -> { activity.showQualityMenu(); ((CheckBox)activity.menuContent().findViewWithTag("qualityMaster")).setChecked(false); });
            until(() -> current.quality.mask == 0 && activity.qualityOverlay.getVisibility() == View.GONE, 5, "Panel did not stop statistics");
            until(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && "quality-frame-callback".equals(t.getName())), 5, "Disabled panel retained frame callback worker");
        } finally { test.removeMonitor(monitor); }
        close();
    }
    private void position() throws Exception {
        configure(); SharedPreferences prefs=UiPreferences.get(context);
        prefs.edit().putBoolean(UiPreferences.QUALITY_ENABLED,true).putInt(UiPreferences.QUALITY_ITEMS,QualityStats.VIDEO_RATE)
                .remove(UiPreferences.QUALITY_X).remove(UiPreferences.QUALITY_Y).apply();
        ScrcpySession current=start(false); Thread.sleep(1000);
        until(() -> activity.qualityOverlay.getText().toString().contains("Mbps"),5,"Quality panel not ready");
        device.shell("logcat -c");
        test.runOnMainSync(() -> {
            activity.showQualityMenu(); View item=UiControlsSmoke.find(activity.menuContent(),"自定义参数位置");
            check(item!=null,"Position menu entry missing"); item.performClick();
        });
        float x=activity.qualityOverlay.getX(), y=activity.qualityOverlay.getY();
        UiControlsSmoke.screenDrag(test,activity.qualityOverlay,MainUi.dp(context,40),MainUi.dp(context,240),400,false);
        check(activity.qualityOverlay.getY()>y+MainUi.dp(context,60),"Quality panel did not follow real drag");
        check(!prefs.contains(UiPreferences.QUALITY_Y),"Unsaved position persisted");
        test.runOnMainSync(() -> UiControlsSmoke.find(activity.getWindow().getDecorView(),"完成定位").performClick());
        check(prefs.getFloat(UiPreferences.QUALITY_Y,0)>.1f && !activity.qualityOverlay.isClickable(),"Position not saved or still intercepting");
        float savedX=activity.qualityOverlay.getX(),savedY=activity.qualityOverlay.getY();
        test.runOnMainSync(activity::beginQualityPosition);
        UiControlsSmoke.screenDrag(test,activity.qualityOverlay,-MainUi.dp(context,30),-MainUi.dp(context,80),400,false);
        test.runOnMainSync(() -> UiControlsSmoke.find(activity.getWindow().getDecorView(),"取消定位").performClick());
        check(Math.abs(activity.qualityOverlay.getY()-savedY)<2,"Cancel did not restore saved location");
        check(!device.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("touch action="),"Position drag leaked remote touch");
        MainActivity main=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.runOnMainSync(main::finish);
        until(() -> activity.hasWindowFocus() && activity.display.ownsSession(),12,"Position foreground restore failed");
        until(() -> activity.session==current && activity.qualityOverlay.getText().toString().contains("Mbps")
                && Math.abs(activity.qualityOverlay.getY()-savedY)<2,8,"Saved location or session lost on resume");
        test.runOnMainSync(() -> {
            View root=(View)activity.qualityOverlay.getParent();
            float tx=activity.qualityOverlay.getX()+20,ty=activity.qualityOverlay.getY()+20; long down=SystemClock.uptimeMillis();
            for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,tx,ty,0); root.dispatchTouchEvent(event); event.recycle();
            }
        });
        until(() -> device.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("touch action=1"),5,"Positioned overlay did not pass remote touches");
        test.runOnMainSync(() -> { activity.setAutoRotate(false); activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); });
        until(() -> activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight(),10,"Local landscape did not settle");
        test.waitForIdleSync(); Thread.sleep(300);
        check(activity.qualityOverlay.getX()>=0 && activity.qualityOverlay.getY()>=0
                && activity.qualityOverlay.getX()+activity.qualityOverlay.getWidth()<=((View)activity.qualityOverlay.getParent()).getWidth()+1
                && activity.qualityOverlay.getY()+activity.qualityOverlay.getHeight()<=((View)activity.qualityOverlay.getParent()).getHeight()+1,"Rotated quality position left screen");
        check(activity.session==current,"Position rotation replaced session");
        capture("quality-position-landscape");
        test.runOnMainSync(() -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        until(() -> activity.getWindow().getDecorView().getWidth()<activity.getWindow().getDecorView().getHeight(),10,"Local portrait did not settle");
        test.runOnMainSync(() -> {
            prefs.edit().putBoolean(UiPreferences.QUALITY_ENABLED,false).putInt(UiPreferences.QUALITY_OPACITY,0).apply();
            activity.beginQualityPosition();
            check(activity.qualityOverlay.getVisibility()==View.VISIBLE && activity.qualityOverlay.getAlpha()==1,"Hidden panel cannot be positioned");
            UiControlsSmoke.find(activity.getWindow().getDecorView(),"取消定位").performClick();
        });
        check(current.quality.mask==0 && activity.qualityOverlay.getVisibility()==View.GONE,"Position preview enabled disabled stats");
        close();
    }
    private void performance() throws Exception {
        SharedPreferences prefs=UiPreferences.get(context);
        prefs.edit().putBoolean(UiPreferences.QUALITY_ENABLED,true).putInt(UiPreferences.QUALITY_ITEMS,
                QualityStats.FPS|QualityStats.VIDEO_RATE|QualityStats.VIDEO_DELAY|QualityStats.INPUT_WAIT|QualityStats.CODECS)
                .putBoolean(UiPreferences.DEBUG_LOG,true).apply(); DebugLog.setEnabled(true);
        File directory=context.getExternalFilesDir("performance-smoke");
        try (PrintWriter out=new PrintWriter(new File(directory,"android.csv"))) {
            out.println("target_bps,sample,received_fps,decoded_fps,video_mbps,packets_s,decode_queue_ms,input_wait_ms");
            for (String rate:new String[]{"8000000","100000000"}) {
                configure("customWidth","1080","customHeight","2400","maxSize","0","fps","120","videoBitRate",rate);
                ScrcpySession session=start(false);
                check(session.width==1080 && session.height==2400,"Performance case unexpectedly scaled");
                device.shell("input keyevent KEYCODE_HOME"); Thread.sleep(800);
                Throwable[] failure={null};
                Thread gestures=new Thread(() -> {
                    try { device.shell("for i in 1 2 3 4 5 6 7 8; do input swipe 540 2100 540 400 250; input swipe 540 500 540 2100 250; cmd statusbar expand-notifications; sleep 0.2; cmd statusbar collapse; done"); }
                    catch(Throwable error) { failure[0]=error; }
                },"performance-test-gestures"); gestures.start();
                int active=0;
                for(int i=0;i<16;i++) {
                    Thread.sleep(1100); QualityStats.Sample sample=activity.qualityOverlay.lastSample;
                    check(sample!=null,"Performance sample absent");
                    out.printf(Locale.ROOT,"%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",rate,i,sample.receivedFps,sample.decodedFps,sample.videoMbps,sample.videoPps,sample.videoDelayMs,sample.inputWaitMs); out.flush();
                    if (sample.receivedFps>0) active++;
                }
                gestures.join(30000); check(!gestures.isAlive() && failure[0]==null,"Performance gestures failed");
                check(active>=10 && !session.isClosed(),"Performance session stalled"); close();
            }
        }
    }
    private void rateControl() throws Exception {
        UiPreferences.get(context).edit().putBoolean(UiPreferences.DEBUG_LOG,true).putBoolean(UiPreferences.QUALITY_ENABLED,true)
                .putInt(UiPreferences.QUALITY_ITEMS,QualityStats.VIDEO_RATE|QualityStats.CODECS).apply(); DebugLog.setEnabled(true);
        configure("videoBitRateMode","cbr");
        ScrcpySession cbr=start(false);
        check("cbr".equals(cbr.effectiveRateMode),"Supported CBR unexpectedly fell back");
        until(() -> activity.qualityOverlay.getText().toString().contains("目标 6.0 Mbps · cbr"),5,"Target rate/mode missing from panel");
        String log=new String(java.nio.file.Files.readAllBytes(DebugLog.file(context).toPath()),java.nio.charset.StandardCharsets.UTF_8);
        check(log.contains("Video codec option set: bitrate-mode (Integer) = 2"),"Pinned server did not receive CBR option");
        check(cbr.qualityCodecDescription().contains("未声明低延迟能力") || cbr.qualityCodecDescription().contains("支持低延迟"),"Decoder low-latency capability not described");
        close();
        configure("videoBitRateMode","cbr","videoEncoder","not.a.real.encoder");
        ScrcpySession fallback=start(false);
        check("auto".equals(fallback.effectiveRateMode),"Safe media retry retained CBR");
        close();
    }
    private void touchHold() throws Exception {
        configure(); ScrcpySession current = start(false); device.shell("logcat -c");
        android.view.MotionEvent.PointerProperties[] properties=new android.view.MotionEvent.PointerProperties[2];
        android.view.MotionEvent.PointerCoords[] coordinates=new android.view.MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++){properties[i]=new android.view.MotionEvent.PointerProperties();properties[i].id=i==0?7:42;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coordinates[i]=new android.view.MotionEvent.PointerCoords();coordinates[i].x=activity.video.getWidth()*(i+1)/3f;coordinates[i].y=activity.video.getHeight()/2f;coordinates[i].pressure=1;coordinates[i].size=1;}
        long down=SystemClock.uptimeMillis();
        test.runOnMainSync(() -> {
            dispatchTouch(activity,down,MotionEvent.ACTION_DOWN,1,properties,coordinates);
            dispatchTouch(activity,down,MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coordinates);
            coordinates[1].x+=12; dispatchTouch(activity,down,MotionEvent.ACTION_MOVE,2,properties,coordinates);
        });
        Thread.sleep(1100); // Host test proxy withholds this stream's ACK for 700 ms.
        String held=device.shell("logcat -d -s ScrcpyProbe:I '*:S'");
        check(held.contains("touch action=0 pointers=1") && held.contains("touch action=5 pointers=2"),"Two fingers were not received");
        check(!held.contains("touch action=6") && !held.contains("touch action=1"),"An expired MOVE cancelled a held finger");
        for(int i=0;i<40;i++){
            test.runOnMainSync(() -> { coordinates[1].x+=1;dispatchTouch(activity,down,MotionEvent.ACTION_MOVE,2,properties,coordinates); }); Thread.sleep(12);
        }
        test.runOnMainSync(() -> dispatchTouch(activity,down,MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coordinates));
        until(() -> device.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("touch action=6 pointers=2"),5,"Second finger did not release");
        String one=device.shell("logcat -d -s ScrcpyProbe:I '*:S'"); check(!one.contains("touch action=1 pointers=1"),"Releasing second finger cancelled first finger");
        test.runOnMainSync(() -> {coordinates[0].x+=15;dispatchTouch(activity,down,MotionEvent.ACTION_MOVE,1,properties,coordinates);dispatchTouch(activity,down,MotionEvent.ACTION_UP,1,properties,coordinates);});
        until(() -> device.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("touch action=1 pointers=1"),5,"Final finger did not release");
        check(!current.isClosed(),"Input congestion ended the session"); close();
    }
    private static void dispatchTouch(RemoteActivity activity,long down,int action,int count,MotionEvent.PointerProperties[] properties,MotionEvent.PointerCoords[] coordinates){
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        activity.video.dispatchTouchEvent(event);event.recycle();
    }
    private ScrcpySession start(boolean audio) throws Exception {
        device.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n org.scrcpy.android.test/org.scrcpy.android.TargetProbeActivity");
        activity = (RemoteActivity)test.startActivitySync(RemoteActivity.intent(context, device.target, audio));
        until(() -> activity.session != null && activity.session.isStarted() && activity.session.currentRenderedFrames() >= 3, 65, "Session did not start");
        return activity.session;
    }
    private void close() throws Exception {
        ScrcpySession current = device.session;
        RemoteActivity closing = activity; activity = null;
        test.runOnMainSync(() -> { FloatingWindowService.closeRemote(context, device.target); if (closing != null && !closing.isFinishing()) closing.finish(); });
        if (current != null) { current.close(); current.awaitCleanup(); }
        test.waitForIdleSync();
    }
    private void fit(RemoteDisplay display, ScrcpySession session) throws Exception {
        until(() -> {
            final boolean[] ok = {false}; test.runOnMainSync(() -> {
                float scale = Math.min(display.getWidth() / (float)session.width, display.getHeight() / (float)session.height);
                ok[0] = Math.abs(display.video.getWidth() - Math.round(session.width * scale)) <= 2
                        && Math.abs(display.video.getHeight() - Math.round(session.height * scale)) <= 2 && scale > 0;
            }); return ok[0];
        }, 10, "Video not fitted maximally without cropping/stretching");
    }
    private void touch(RemoteDisplay display) throws Exception {
        device.shell("logcat -c");
        test.runOnMainSync(() -> {
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(now, now + action * 60, action, display.video.getWidth() / 2f, display.video.getHeight() / 2f, 0);
                display.video.dispatchTouchEvent(event); event.recycle();
            }
        });
        until(() -> device.shell("logcat -d -s ScrcpyProbe:I '*:S'").contains("touch action=1"), 10, "Touch did not reach selected display");
    }
    private void capture(String name) throws Exception {
        test.waitForIdleSync(); Thread.sleep(2800); Bitmap bitmap = test.getUiAutomation().takeScreenshot(); check(bitmap != null, "Screenshot unavailable");
        File directory = context.getExternalFilesDir("media-settings-0.7.0");
        try (OutputStream out = new FileOutputStream(new File(directory, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
    }
    private void main() throws Exception {
        configure(); ScrcpySession original = start(true); check("main".equals(original.activeBackend), "Main backend not selected");
        check(device.shell("wm size").contains("Override size: 360x640"), "Custom size not applied");
        until(() -> original.nonSilentAudioBytes > 8192, 15, "PCM audio missing"); fit(activity.display, original); touch(activity.display); capture("main-fullscreen");
        MainActivity main = (MainActivity)test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.runOnMainSync(() -> {
            main.ui.select("settings"); View root = main.getWindow().getDecorView();
            ((EditText)root.findViewWithTag("stream_customWidth")).setText("400"); ((EditText)root.findViewWithTag("stream_customHeight")).setText("800");
            root.findViewWithTag("saveStreamSettings").performClick();
            check("400".equals(StreamPreferences.get(context).getString("customWidth", "")), "Settings UI did not save");
        }); capture("settings");
        test.runOnMainSync(() -> {
            View root = main.getWindow().getDecorView(); View save = root.findViewWithTag("saveStreamSettings");
            save.requestRectangleOnScreen(new android.graphics.Rect(0,0,save.getWidth(),save.getHeight()), true);
        }); capture("settings-media");
        check(original == device.session && original.settings.values.get("customWidth").equals("360"), "Live settings snapshot changed");
        check(device.shell("wm size").contains("Override size: 360x640"), "Saving settings changed live remote size");
        test.runOnMainSync(() -> context.startActivity(RemoteActivity.intent(context, device.target, true)));
        until(() -> activity.hasWindowFocus() && activity.display.ownsSession() && activity.session == original, 10, "Settings navigation lost session");
        test.runOnMainSync(activity::requestFloatingWindow); until(() -> FloatingWindowService.isShowing(device.target), 15, "Floating window missing");
        FloatingWindow window = FloatingWindowService.current(); fit(window.display, original); touch(window.display); capture("main-floating");
        activity = (RemoteActivity)test.startActivitySync(RemoteActivity.intent(context, device.target, true));
        until(() -> activity.session == original && activity.display.ownsSession(), 15, "Window handoff restarted session");
        close();
        ScrcpySession reopened = start(true); check(reopened != original, "Reopen reused closed session");
        check(device.shell("wm size").contains("Override size: 400x800"), "Saved settings not applied on reopen"); fit(activity.display, reopened); close();
        DebugLog.write("TEST media main size/UI snapshot/fullscreen/window/reopen PASS");
    }
    private void virtual() throws Exception {
        String initial = device.shell("wm size");
        configure("backend", "virtual", "virtualApp", "org.scrcpy.android.test");
        ScrcpySession original = start(true);
        check("virtual".equals(original.activeBackend) && original.remoteDisplayId > 0, "Independent virtual display not selected");
        check(initial.equals(device.shell("wm size")), "Virtual display changed physical screen dimensions");
        check(original.width == 360 && original.height == 640, "Virtual size mismatch");
        until(() -> original.nonSilentAudioBytes > 8192, 15, "Virtual audio missing"); fit(activity.display, original); touch(activity.display); capture("virtual-fullscreen");
        test.runOnMainSync(activity::requestFloatingWindow); until(() -> FloatingWindowService.isShowing(device.target), 15, "Virtual floating window missing");
        FloatingWindow window = FloatingWindowService.current(); fit(window.display, original); touch(window.display); capture("virtual-floating");
        activity = (RemoteActivity)test.startActivitySync(RemoteActivity.intent(context, device.target, true));
        until(() -> activity.session == original && activity.display.ownsSession(), 15, "Virtual handoff restarted server"); close();
        until(() -> !device.shell("cmd display get-displays").contains("Display id " + original.remoteDisplayId + ":"), 10, "Virtual display leaked");
        configure("resolution", "original", "backend", "virtual", "virtualApp", "org.scrcpy.android.test");
        ScrcpySession nativeSize = start(false); DisplaySizeState size = DisplaySizeState.parse(initial);
        check("virtual".equals(nativeSize.activeBackend) && nativeSize.displayWidth == size.width && nativeSize.displayHeight == size.height, "Original size ignored preferred virtual backend"); close();
        DebugLog.write("TEST media virtual display/audio/touch/handoff/original preference PASS");
    }
    private void codecs() throws Exception {
        for (String audio : new String[]{"aac", "opus", "flac"}) {
            configure("audioCodec", audio); ScrcpySession session = start(true);
            check(audio.equals(session.effectiveAudio), "Audio unexpectedly fell back: " + audio);
            until(() -> session.nonSilentAudioBytes > 8192, 15, "Decoded audio silent: " + audio); close();
            DebugLog.write("TEST audio codec=" + audio + " PASS");
        }
        for (String video : new String[]{"h265", "av1", "vp8", "vp9"}) {
            configure("videoCodec", video); ScrcpySession session = start(false);
            DebugLog.write("TEST video requested=" + video + " effective=" + session.effectiveVideo + " frames=" + session.renderedFrames());
            check(!session.isClosed(), "Video codec session closed"); close();
        }
        configure("videoEncoder", "c2.android.avc.encoder", "videoDecoder", "c2.android.avc.decoder", "audioCodec", "aac", "audioEncoder", "c2.android.aac.encoder", "audioDecoder", "c2.android.aac.decoder");
        ScrcpySession named = start(true); check("h264".equals(named.effectiveVideo) && "aac".equals(named.effectiveAudio), "Named components failed"); close();
    }
    private void fallback() throws Exception {
        configure("backend", "virtual", "virtualApp", "org.scrcpy.missing.application");
        ScrcpySession route = start(false); check("main".equals(route.activeBackend), "Virtual startup failure did not fall back to main"); close();
        configure("videoEncoder", "not.a.real.encoder", "audioCodec", "opus", "audioDecoder", "not.a.real.decoder");
        ScrcpySession media = start(true); check("h264".equals(media.effectiveVideo) && "raw".equals(media.effectiveAudio), "Unavailable codecs did not use safe defaults");
        until(() -> media.nonSilentAudioBytes > 8192, 15, "Fallback PCM silent"); close();
        DebugLog.write("TEST media fallback unavailable display app/components PASS");
    }
    private void presets() throws Exception {
        for (String[] preset : new String[][]{{"original","main","0"},{"local","main","640"},{"local720","virtual","0"}}) {
            configure("resolution",preset[0],"backend",preset[1],"maxSize",preset[2],"virtualApp","org.scrcpy.android.test","fps","15");
            String initial = device.shell("wm size"); ScrcpySession session = start(false);
            DisplaySizeState before = DisplaySizeState.parse(initial); int[] expected = session.settings.displaySize(before.width,before.height);
            check(session.displayWidth == expected[0] && session.displayHeight == expected[1], "Preset logical dimensions mismatch");
            check(preset[1].equals(session.activeBackend), "Preset unexpectedly fell back");
            if ("0".equals(preset[2])) check(Math.abs(session.width - expected[0]) <= 2 && Math.abs(session.height - expected[1]) <= 2, "Unlimited encoding unexpectedly scaled");
            else check(Math.max(session.width, session.height) <= 640, "Video cap ignored");
            fit(activity.display,session); DebugLog.write("TEST preset=" + preset[0] + " logical=" + session.displayWidth + "x" + session.displayHeight + " video=" + session.width + "x" + session.height + " PASS"); close();
        }
    }
}
