package org.scrcpy.android;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.*;
import android.view.Surface;
import java.util.concurrent.*;

/** Drained EGL sink: retains the decoder while an Activity has no visible Surface. */
final class BackgroundOutput implements ScrcpySession.Listener, AutoCloseable {
    private final HandlerThread thread = new HandlerThread("remote-background-output");
    private final Handler handler;
    Surface surface;
    private SurfaceTexture texture;
    private EGLDisplay display;
    private EGLContext context;
    private EGLSurface pbuffer;
    BackgroundOutput() throws Exception {
        thread.start(); handler = new Handler(thread.getLooper());
        FutureTask<Void> init = new FutureTask<>(() -> {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (!EGL14.eglInitialize(display, new int[2], 0, new int[2], 0)) throw new IllegalStateException("EGL initialize failed");
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) throw new IllegalStateException("EGL config unavailable");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) throw new IllegalStateException("EGL context unavailable");
            int[] names = new int[1]; GLES20.glGenTextures(1, names, 0);
            texture = new SurfaceTexture(names[0]);
            texture.setOnFrameAvailableListener(value -> { try { value.updateTexImage(); } catch (RuntimeException e) { DebugLog.error("background frame", e); } }, handler);
            surface = new Surface(texture); return null;
        });
        handler.post(init);
        try { init.get(10, TimeUnit.SECONDS); } catch (Exception e) { close(); throw e; }
    }
    public void status(String message) { DebugLog.write("background " + message); }
    public void size(int width, int height) { }
    public void clipboard(String text) { }
    public void ended(String reason) { DebugLog.write("background ended: " + reason); }
    public void close() {
        handler.post(() -> {
            if (surface != null) surface.release();
            if (texture != null) texture.release();
            if (display != null) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (pbuffer != null) EGL14.eglDestroySurface(display, pbuffer);
                if (context != null) EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
            }
            thread.quitSafely();
        });
    }
}
