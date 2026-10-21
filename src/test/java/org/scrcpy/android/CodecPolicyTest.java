package org.scrcpy.android;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CodecPolicyTest {
    private static CodecPolicy.Component c(String name,String format,boolean encoder,boolean hw) { return new CodecPolicy.Component(name,format,encoder,hw); }
    @Test public void prefersCompatibleHardwareAvcAndCompressedOpusAudio() {
        Map<String,String> result=CodecPolicy.recommend(Arrays.asList(c("vendor.hevc","h265",false,true),c("vendor.avc","h264",false,true),c("software.opus","opus",false,false)));
        assertEquals("h264",result.get("videoCodec")); assertEquals("",result.get("videoDecoder")); assertEquals("opus",result.get("audioCodec"));
        assertEquals("",StreamSettings.DEFAULTS.get("videoDecoder"));
    }
    @Test public void encoderOnControllerDoesNotCertifyDecoderOrRemoteEncoder() {
        Map<String,String> result=CodecPolicy.recommend(Collections.singletonList(c("encoder.hevc","h265",true,true)));
        assertEquals("h264",result.get("videoCodec")); assertEquals("",result.get("videoEncoder")); assertEquals("",result.get("videoDecoder"));
    }
    @Test public void doesNotForceGoldfishComponentAndBypassSurfaceCompatibilityFallback() {
        Map<String,String> result=CodecPolicy.recommend(Collections.singletonList(c("c2.goldfish.avc.decoder","h264",false,true)));
        assertEquals("",result.get("videoDecoder"));
    }
    @Test public void selectsAvailableHardwareWhenAvcHasNoHardwareDecoder() {
        Map<String,String> result=CodecPolicy.recommend(Arrays.asList(c("sw.avc","h264",false,false),c("vendor.hevc","h265",false,true),c("vendor.aac","aac",false,true)));
        assertEquals("h265",result.get("videoCodec")); assertEquals("aac",result.get("audioCodec")); assertEquals("",result.get("audioDecoder"));
    }
    @Test public void emptyCapabilitiesPreserveSafeFallbackDefaults() { assertEquals(StreamSettings.DEFAULTS,CodecPolicy.recommend(Collections.emptyList())); }
}
