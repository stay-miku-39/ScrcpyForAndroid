package org.scrcpy.android;

import java.util.regex.*;

final class DisplaySizeState {
    final int width, height;
    final String restore;
    private DisplaySizeState(int width, int height, String restore) { this.width = width; this.height = height; this.restore = restore; }
    static DisplaySizeState withoutOverrideAccess(int width, int height) { return new DisplaySizeState(width, height, "unknown"); }
    static DisplaySizeState parse(String text) {
        Matcher physical = Pattern.compile("Physical size: (\\d+)x(\\d+)").matcher(text);
        if (!physical.find()) throw new IllegalArgumentException("无法读取被控显示尺寸");
        String width = physical.group(1), height = physical.group(2), restore = "reset";
        Matcher override = Pattern.compile("Override size: (\\d+)x(\\d+)").matcher(text);
        if (override.find()) { width = override.group(1); height = override.group(2); restore = width + "x" + height; }
        return new DisplaySizeState(Integer.parseInt(width), Integer.parseInt(height), restore);
    }
    static boolean validRestore(String text) { return "reset".equals(text) || text.matches("[0-9]{2,5}x[0-9]{2,5}"); }
}
