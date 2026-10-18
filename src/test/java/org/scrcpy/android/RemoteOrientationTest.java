package org.scrcpy.android;

import org.junit.Test;
import static org.junit.Assert.*;
import android.content.pm.ActivityInfo;

public class RemoteOrientationTest {
    @Test public void displayServiceSelectsPhysicalDisplayAndAllFourRotations() {
        int[] expected = {1, 0, 9, 8};
        for (int rotation = 0; rotation < 4; rotation++) {
            int width = (rotation & 1) == 0 ? 1080 : 2400, height = (rotation & 1) == 0 ? 2400 : 1080;
            RemoteOrientation.Info info = RemoteOrientation.parse("Displays:\nDisplay id 7: DisplayInfo{real 10 x 20, rotation 3}\nDisplay id 0: DisplayInfo{displayId 0, real " + width + " x " + height + ", rotation " + rotation + ", state ON}");
            assertNotNull(info); assertEquals(expected[rotation], info.requestedOrientation());
        }
    }
    @Test public void landscapeNaturalDevicesAndVideoFallback() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, new RemoteOrientation.Info(0, 1920, 1200).requestedOrientation());
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, new RemoteOrientation.Info(3, 1200, 1920).requestedOrientation());
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, RemoteOrientation.aspectOrientation(720, 1600));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, RemoteOrientation.aspectOrientation(720, 720));
        assertNull(RemoteOrientation.parse("cmd: command not found"));
    }
}
