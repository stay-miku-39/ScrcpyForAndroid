package org.scrcpy.android;

import android.content.Context;
import android.graphics.Color;
import android.util.SparseArray;
import android.view.*;
import android.widget.FrameLayout;

/** Shared touch/coordinate mapping for fullscreen and floating views. */
final class RemoteDisplay extends FrameLayout implements SurfaceHolder.Callback, ScrcpySession.Listener {
    interface Events {
        void ready(ScrcpySession session);
        void status(String message);
        void size(int width, int height);
        void clipboard(String text);
        void ended(String reason);
    }
    final SurfaceView video;
    private final DeviceConnection runtime;
    private final boolean audio;
    private final Events events;
    private final SparseArray<float[]> touches = new SparseArray<>();
    private volatile ScrcpySession session;
    private int videoWidth, videoHeight;
    private volatile boolean disposed;
    private volatile boolean suspended;
    private volatile int surfaceGeneration;

    RemoteDisplay(Context context, DeviceConnection runtime, boolean audio, Events events) {
        super(context); this.runtime = runtime; this.audio = audio; this.events = events; setBackgroundColor(Color.BLACK);
        video = new SurfaceView(context); video.setContentDescription("远程设备画面，可触控");
        addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.getHolder().addCallback(this); video.setOnTouchListener((v,e) -> touch(e));
        addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> resize());
    }
    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (suspended || disposed) return;
        attach(holder);
    }
    private void attach(SurfaceHolder holder) {
        int generation = ++surfaceGeneration, mirrorGeneration = runtime.mirrorGeneration;
        try { runtime.worker.execute(() -> {
            try {
                ScrcpySession current; boolean created;
                synchronized (runtime) {
                    if (disposed || suspended || generation != surfaceGeneration || mirrorGeneration != runtime.mirrorGeneration || !runtime.connected) return;
                    current = runtime.session; created = current == null || current.isClosed();
                    if (created) {
                        current = new ScrcpySession(getContext(), runtime, holder.getSurface(), audio, this);
                        runtime.session = current;
                    }
                    session = current;
                }
                if (created) current.start();
                else { current.attachOutput(holder.getSurface(), this); size(current.width, current.height); }
                ScrcpySession attached = current;
                post(() -> {
                    if (disposed || generation != surfaceGeneration || mirrorGeneration != runtime.mirrorGeneration) { if (!suspended || disposed) stopIfOwner(); return; }
                    if (!suspended) events.ready(attached);
                });
            } catch (Exception e) { DebugLog.error("attach remote view", e); post(() -> { if (!disposed && mirrorGeneration == runtime.mirrorGeneration) events.ended(e.getMessage()); }); }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { events.ended("ADB 已断开"); }
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { ++surfaceGeneration; if (!suspended) stopIfOwner(); }
    void suspendOutput() {
        releaseTouches(); suspended = true;
        try { runtime.worker.execute(() -> {
            try { runtime.park(session, this); }
            catch (Exception error) { DebugLog.error("park remote", error); post(() -> events.ended("后台保活失败：" + error.getMessage())); stopIfOwner(); }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    void resumeOutput() {
        if (disposed || !suspended) return; suspended = false;
        if (video.getHolder().getSurface().isValid()) attach(video.getHolder());
    }

    boolean ownsSession() { return session != null && session.isOwnedBy(this); }
    void dispose() { disposed = true; stopIfOwner(); }
    void releaseTouches() {
        if (ownsSession()) for (int i = 0; i < touches.size(); i++) {
            float[] xy = touches.valueAt(i); session.send(ControlProtocol.touch(MotionEvent.ACTION_UP, touches.keyAt(i), (int)xy[0], (int)xy[1], videoWidth, videoHeight, 0));
        }
        touches.clear();
    }
    private void stopIfOwner() {
        releaseTouches();
        runtime.closeOwned(session, this);
    }
    private void resize() {
        if (videoWidth <= 0 || videoHeight <= 0 || getWidth() == 0 || getHeight() == 0) return;
        float scale = Math.min(getWidth() / (float)videoWidth, getHeight() / (float)videoHeight);
        int width = Math.max(1, Math.round(videoWidth * scale)), height = Math.max(1, Math.round(videoHeight * scale));
        ViewGroup.LayoutParams old = video.getLayoutParams();
        if (old.width != width || old.height != height) video.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
    }
    private boolean touch(MotionEvent event) {
        if (!ownsSession() || videoWidth <= 0 || videoHeight <= 0) return true;
        int action = event.getActionMasked(), changed = event.getActionIndex();
        if (BuildConfig.DEBUG && action != MotionEvent.ACTION_MOVE) DebugLog.write("touch action=" + action + " pointers=" + event.getPointerCount());
        if (action == MotionEvent.ACTION_CANCEL) { releaseTouches(); return true; }
        if (action == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) if (touches.indexOfKey(event.getPointerId(i)) >= 0) sendTouch(event, i, MotionEvent.ACTION_MOVE);
        } else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) sendTouch(event, changed, MotionEvent.ACTION_DOWN);
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            sendTouch(event, changed, MotionEvent.ACTION_UP); touches.remove(event.getPointerId(changed)); video.performClick();
        }
        return true;
    }
    private void sendTouch(MotionEvent event, int index, int action) {
        int id = event.getPointerId(index);
        float x = event.getX(index) * videoWidth / video.getWidth(), y = event.getY(index) * videoHeight / video.getHeight();
        touches.put(id, new float[]{x,y});
        session.send(ControlProtocol.touch(action, id, (int)x, (int)y, videoWidth, videoHeight, action == MotionEvent.ACTION_UP ? 0 : event.getPressure(index)));
    }
    @Override public void status(String message) { post(() -> { if (!disposed && ownsSession()) events.status(message); }); }
    @Override public void size(int width, int height) { post(() -> {
        if (disposed || !ownsSession()) return; releaseTouches(); videoWidth = width; videoHeight = height; resize(); events.size(width, height);
    }); }
    @Override public void clipboard(String text) { post(() -> { if (!disposed && ownsSession()) events.clipboard(text); }); }
    @Override public void ended(String reason) { post(() -> { if (!disposed && ownsSession()) events.ended(reason); }); }
}
