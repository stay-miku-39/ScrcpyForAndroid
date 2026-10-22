package com.genymobile.scrcpy.android;

import android.media.MediaCodec;
import android.os.Bundle;
import android.os.SystemClock;
import com.genymobile.scrcpy.util.Ln;

/** Runs on the existing video encoder thread; reset with each encoder instance. */
public final class VideoRateLimiter {
    private final BitrateGovernor governor;
    private boolean available = true;
    private int floorWindows;
    private long lastFloorCheck;

    public VideoRateLimiter(int target) {
        int limit = Integer.parseInt(System.getProperty("org.scrcpy.android.videoBitRateLimit", "0"));
        governor = limit == 0 ? null : new BitrateGovernor(target, limit, SystemClock.elapsedRealtime());
        if (governor != null) Ln.i("Video bitrate soft limit active: " + limit);
    }
    public void onFrame(MediaCodec codec, int size) {
        if (governor == null || !available) return;
        long now = SystemClock.elapsedRealtime();
        int next = governor.frame(size, now);
        if (next != 0) {
            try {
                Bundle params = new Bundle(); params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next);
                codec.setParameters(params);
                Ln.i("Video bitrate adjusted: target=" + next + " measured=" + governor.measuredBitrate());
            } catch (IllegalArgumentException | IllegalStateException error) {
                unavailable("encoder rejected dynamic bitrate");
            }
        }
        // setParameters may return successfully while a vendor encoder ignores it.
        if (now-lastFloorCheck >= 1000) {
            lastFloorCheck = now;
            if (!governor.cannotReduceFurther()) floorWindows = 0;
            else if (++floorWindows >= 5) unavailable("encoder output remains above limit at minimum target");
        }
    }
    private void unavailable(String reason) {
        available = false; Ln.w("Video bitrate limit unavailable: " + reason);
    }
}
