package org.scrcpy.android;

import android.content.pm.ActivityInfo;
import java.util.regex.*;

/** Rotation reported by the controlled Android display service, not local sensors. */
final class RemoteOrientation {
    static final class Info {
        final int rotation, width, height;
        Info(int rotation, int width, int height) { this.rotation = rotation; this.width = width; this.height = height; }
        int requestedOrientation() {
            boolean naturalPortrait = (rotation & 1) == 0 ? height >= width : width >= height;
            int[] portrait = {ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE};
            int[] landscape = {ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT};
            return (naturalPortrait ? portrait : landscape)[rotation];
        }
    }
    static Info parse(String dump) {
        return parse(dump, 0);
    }
    static Info parse(String dump, int displayId) {
        for (String line : dump.split("\n")) {
            if (!line.matches("\\s*Display id " + displayId + ":.*")) continue;
            Matcher size = Pattern.compile("\\breal (\\d+) x (\\d+)").matcher(line);
            Matcher rotation = Pattern.compile("\\brotation ([0-3])\\b").matcher(line);
            if (!size.find() || !rotation.find()) return null;
            try {
                int width = Integer.parseInt(size.group(1)), height = Integer.parseInt(size.group(2));
                if (width > 0 && height > 0) return new Info(Integer.parseInt(rotation.group(1)), width, height);
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }
    static int aspectOrientation(int width, int height) {
        if (width <= 0 || height <= 0 || width == height) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        return width > height ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }
}
