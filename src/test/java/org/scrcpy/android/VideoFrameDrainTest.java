package org.scrcpy.android;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class VideoFrameDrainTest {
    @Test public void burstDropsEarlierDecodedOutputsAndRendersNewest() {
        AtomicInteger next = new AtomicInteger(1); List<String> releases = new ArrayList<>();
        int dropped = VideoFrameDrain.renderLatest(0, () -> next.get() < 5 ? next.getAndIncrement() : -1,
                (index, render) -> releases.add(index + ":" + render));
        assertEquals(4, dropped);
        assertEquals(Arrays.asList("0:false", "1:false", "2:false", "3:false", "4:true"), releases);
    }
    @Test public void isolatedFinalFrameRendersImmediatelyWithoutWaitingForAnother() {
        List<String> releases = new ArrayList<>();
        assertEquals(0, VideoFrameDrain.renderLatest(7, () -> -1, (index, render) -> releases.add(index + ":" + render)));
        assertEquals(Arrays.asList("7:true"), releases);
    }
    @Test public void continuousOutputStillPresentsWithinBoundedPass() {
        AtomicInteger next = new AtomicInteger(1); List<Integer> rendered = new ArrayList<>();
        assertEquals(32, VideoFrameDrain.renderLatest(0, next::getAndIncrement, (index, render) -> { if (render) rendered.add(index); }));
        assertEquals(Arrays.asList(32), rendered);
    }
    @Test public void formatChangeDoesNotLeakPendingOutput() {
        List<Boolean> releases = new ArrayList<>();
        assertEquals(0, VideoFrameDrain.renderLatest(3, () -> -2, (index, render) -> releases.add(render)));
        assertEquals(Arrays.asList(true), releases);
    }
}
