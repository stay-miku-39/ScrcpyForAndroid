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
    private final RuntimeState runtime;
    private final boolean audio;
    private final Events events;
    private final SparseArray<float[]> touches = new SparseArray<>();
    private ScrcpySession session;
    private int videoWidth, videoHeight;
    private boolean disposed;
    private int surfaceGeneration;

    RemoteDisplay(Context context, RuntimeState runtime, boolean audio, Events events) {
        super(context); this.runtime = runtime; this.audio = audio; this.events = events; setBackgroundColor(Color.BLACK);
        video = new SurfaceView(context); video.setContentDescription("远程设备画面，可触控");
        addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.getHolder().addCallback(this); video.setOnTouchListener((v,e) -> touch(e));
        addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> resize());
    }
    @Override public void surfaceCreated(SurfaceHolder holder) {
        int generation = ++surfaceGeneration;
        runtime.worker.execute(() -> {
            try {
                if (disposed || generation != surfaceGeneration) return;
                ScrcpySession current = runtime.session;
                if (current == null || current.isClosed()) {
                    if (!runtime.adb.isConnected()) throw new java.io.IOException("ADB 未连接");
                    current = new ScrcpySession(getContext(), runtime.adb, holder.getSurface(), audio, this);
                    runtime.session = current; session = current; current.start();
                } else { current.attachOutput(holder.getSurface(), this); session = current; size(current.width, current.height); }
                ScrcpySession attached = current;
                post(() -> {
                    if (disposed || generation != surfaceGeneration) { stopIfOwner(); return; }
                    events.ready(attached);
                });
            } catch (Exception e) { DebugLog.error("attach remote view", e); post(() -> events.ended(e.getMessage())); }
        });
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { ++surfaceGeneration; stopIfOwner(); }
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
        if (ownsSession()) { session.close(); if (runtime.session == session) runtime.session = null; }
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
