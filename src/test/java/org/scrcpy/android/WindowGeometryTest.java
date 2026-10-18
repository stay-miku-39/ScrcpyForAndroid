package org.scrcpy.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowGeometryTest {
    @Test public void portraitAndLandscapeFitWithoutChangingVideoAspect() {
        for (int[] video : new int[][]{{720, 1600}, {1600, 720}}) {
            int[] fit = WindowGeometry.fit(600, video[0], video[1], 160, 1000, 2200, 4, 92);
            assertEquals((fit[0] - 4) * video[1] / (double)video[0], fit[1] - 92, 1);
        }
    }
    @Test public void sizeIsBoundedAndChromeRemainsUsableOnExtremeAspectRatios() {
        for (int request : new int[]{-100, 0, 150, 900, Integer.MAX_VALUE}) {
            int[] fit = WindowGeometry.fit(request, 720, 1600, 160, 600, 700, 4, 92);
            assertTrue(fit[0] >= 160 && fit[0] <= 600); assertTrue(fit[1] > 92 && fit[1] <= 700);
        }
        int[] tall = WindowGeometry.fit(500, 10, 1000, 160, 600, 700, 4, 92);
        assertEquals(160, tall[0]); assertEquals(700, tall[1]);
    }
    @Test public void cornerDragWorksOnBothAxesAndBothCorners() {
        assertEquals(100, WindowGeometry.cornerDelta(100, 0, 2, false));
        assertEquals(100, WindowGeometry.cornerDelta(-100, 0, 2, true));
        assertEquals(100, WindowGeometry.cornerDelta(0, 200, 2, true));
        assertEquals(-100, WindowGeometry.cornerDelta(-100, -200, 2, false));
    }
}
