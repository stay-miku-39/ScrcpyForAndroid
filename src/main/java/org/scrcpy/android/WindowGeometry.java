package org.scrcpy.android;

/** Pure window sizing. RemoteDisplay always fits video without stretching. */
final class WindowGeometry {
    static boolean dismiss(int x, int y, int width, int height, int screenWidth, int screenHeight,
            float vx, float vy, float minVelocity, int edge) {
        float cx = x + width / 2f, cy = y + height / 2f;
        if (cx < 0 || cx > screenWidth || cy < 0 || cy > screenHeight) return true;
        // Fling only when already crossing that edge, never from the middle of the screen.
        return (x < -edge && vx < -minVelocity && cx + vx * .18f < 0)
                || (x + width > screenWidth + edge && vx > minVelocity && cx + vx * .18f > screenWidth)
                || (y < -edge && vy < -minVelocity && cy + vy * .18f < 0)
                || (y + height > screenHeight + edge && vy > minVelocity && cy + vy * .18f > screenHeight);
    }
    static int[] fit(int requested, int videoWidth, int videoHeight, int minWidth, int maxWidth, int maxHeight, int border, int chrome) {
        maxWidth = Math.max(border + 1, maxWidth); maxHeight = Math.max(1, maxHeight);
        minWidth = Math.min(Math.max(border + 1, minWidth), maxWidth);
        int width = Math.max(minWidth, Math.min(requested, maxWidth));
        double ratio = Math.max(1, videoHeight) / (double)Math.max(1, videoWidth);
        int ideal = (int)Math.round((width - border) * ratio) + chrome;
        if (ideal > maxHeight) {
            int fromHeight = (int)Math.floor(Math.max(1, maxHeight - chrome) / ratio) + border;
            width = Math.max(minWidth, Math.min(width, fromHeight));
        }
        int height = Math.min(maxHeight, (int)Math.round((width - border) * ratio) + chrome);
        return new int[]{width, Math.max(1, height)};
    }
    static int cornerDelta(float dx, float dy, double videoRatio, boolean left) {
        float horizontal = left ? -dx : dx;
        float vertical = (float)(dy / Math.max(.01, videoRatio));
        return Math.round(Math.abs(horizontal) >= Math.abs(vertical) ? horizontal : vertical);
    }
}
