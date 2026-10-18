package org.scrcpy.android;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import java.util.Arrays;
import java.util.function.Supplier;

/** Bounded key holds shared by the fullscreen menu and overlay header. Main thread only. */
final class RemoteKeyActions {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Supplier<ScrcpySession> target;
    private ScrcpySession heldSession;
    private int[] held = new int[0];
    private final Runnable release = this::release;

    RemoteKeyActions(Supplier<ScrcpySession> target) { this.target = target; }
    void pulse(long milliseconds, int... codes) {
        release(); ScrcpySession session = target.get();
        if (session == null || session.isClosed() || codes.length == 0) return;
        heldSession = session; held = codes.clone();
        session.send(ControlProtocol.keyGroup(KeyEvent.ACTION_DOWN, held));
        long duration = Math.max(50, Math.min(1200, milliseconds));
        DebugLog.write("remote key hold=" + Arrays.toString(held) + " duration=" + duration);
        handler.postDelayed(release, duration);
    }
    boolean isHolding() { return held.length != 0; }
    void release() {
        handler.removeCallbacks(release);
        if (heldSession != null && !heldSession.isClosed() && held.length != 0)
            heldSession.send(ControlProtocol.keyGroup(KeyEvent.ACTION_UP, held));
        heldSession = null; held = new int[0];
    }
}
