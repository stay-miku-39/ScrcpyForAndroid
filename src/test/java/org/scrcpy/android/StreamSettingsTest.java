package org.scrcpy.android;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StreamSettingsTest {
    @Test public void targetPoliciesKeepLargeLimitFromDrivingEncoder() {
        assertEquals(8000000, options().videoBitRate);
        assertEquals(20000000, options().videoBitRateLimit);
        assertEquals(8000000, options("videoBitRate","100000000","videoBitRateLimit","100000000").videoBitRate);
        assertEquals(8000000, options("videoTargetMode","auto","videoBitRateLimit","100000000").videoBitRate);
        assertEquals(800000, options("videoTargetMode","auto","videoBitRateLimit","1000000").videoBitRate);
        assertEquals(100000, options("videoTargetMode","auto","videoBitRateLimit","100000").videoBitRate);
        assertEquals(1000000, options("videoBitRateLimit","1000000").videoBitRate);
        assertEquals(4000000, options("videoTargetMode","custom","videoBitRate","4000000").videoBitRate);
        for(String[] invalid:new String[][]{{"videoTargetMode","invalid"},{"videoBitRateLimit","0"},{"videoTargetMode","custom","videoBitRate","100000000"}}) {
            try { options(invalid); fail(); } catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void rateControlUsesPinnedServerOptionAndSafeRetryRemovesIt() {
        assertEquals("", options().videoRateOptions(false));
        assertEquals(" video_codec_options=bitrate-mode=2",options("videoBitRateMode","cbr").videoRateOptions(false));
        assertEquals("",options("videoBitRateMode","cbr").videoRateOptions(true));
        try { options("videoBitRateMode","bitrate-mode=0;reboot"); fail(); } catch (IllegalArgumentException expected) { }
    }
    private StreamSettings options(String... pairs) {
        Map<String, String> values = new HashMap<>(); for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return new StreamSettings(values, 1080, 2400);
    }
    @Test public void logicalResolutionIsIndependentFromVideoLimitAndSnapshotCannotMutate() {
        Map<String, String> values = new HashMap<>(); values.put("resolution", "custom"); values.put("customWidth", "1080"); values.put("customHeight", "2400");
        StreamSettings saved = new StreamSettings(values, 720, 1280); values.put("customWidth", "720");
        assertArrayEquals(new int[]{1080, 2400}, saved.displaySize(360, 800)); assertEquals(1600, saved.maxSize);
        assertEquals("h264", saved.videoCodec); assertEquals("raw", saved.audioCodec);
        try { saved.values.put("fps", "5"); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void nativeLocalAndAspectPresetsUseExpectedPixels() {
        assertArrayEquals(new int[]{360,800}, options().displaySize(360,800));
        assertArrayEquals(new int[]{1080,2400}, options("resolution","local").displaySize(360,800));
        assertArrayEquals(new int[]{720,1600}, options("resolution","local720").displaySize(360,800));
        assertArrayEquals(new int[]{1440,3200}, options("resolution","local1440").displaySize(360,800));
        assertArrayEquals(new int[]{1080,1920}, options("resolution","1080x1920").displaySize(360,800));
        assertEquals(0, options("maxSize","0").maxSize);
    }
    @Test public void rejectsInvalidDimensionsRatesAndShellFragments() {
        for (String[] pair : new String[][]{{"customWidth","0"},{"customHeight","99999999"},{"fps","0"},{"maxSize","10"},{"videoBitRate","2147483648"},{"audioBitRate","0"},{"videoEncoder","c2.avc;reboot"},{"audioDecoder","$(id)"},{"virtualApp","x y"},{"videoCodec","made-up"}}) {
            try { options(pair); fail(Arrays.toString(pair)); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void originalOverrideIsPreservedRatherThanResetToPhysicalSize() {
        DisplaySizeState original = DisplaySizeState.parse("Physical size: 1080x2400\nOverride size: 360x800\n");
        assertEquals("360x800", original.restore); assertEquals(360, original.width);
        assertEquals("reset", DisplaySizeState.parse("Physical size: 1080x2400").restore);
        assertFalse(DisplaySizeState.validRestore("reset; reboot")); assertTrue(DisplaySizeState.validRestore("360x800"));
    }
    @Test public void selectedVirtualDisplayDoesNotReadPhysicalRotation() {
        String dump = "Display id 0: DisplayInfo{real 1080 x 2400, rotation 0}\nDisplay id 4: DisplayInfo{real 640 x 360, rotation 1}";
        assertEquals(1, RemoteOrientation.parse(dump, 4).rotation); assertEquals(640, RemoteOrientation.parse(dump,4).width);
        assertNull(RemoteOrientation.parse(dump, 2));
    }
    @Test public void remoteComponentMatchesExactlyAndCodecIdsFollowPinnedProtocol() {
        String list = "--video-codec=h264 --video-encoder=c2.android.avc.encoder (sw)\n--audio-codec=aac --audio-encoder=c2.android.aac.encoder (sw)";
        assertTrue(ScrcpySession.encoderSupported(list,"video","h264","c2.android.avc.encoder"));
        assertFalse(ScrcpySession.encoderSupported(list,"video","h264","c2.android.avc"));
        assertFalse(ScrcpySession.encoderSupported(list,"audio","opus",""));
        assertEquals(0x68323635, StreamSettings.videoId("h265")); assertEquals(0x6f707573, StreamSettings.audioId("opus"));
    }
}
