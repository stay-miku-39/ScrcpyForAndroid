package org.scrcpy.android;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class DisplayFallbackTest {
    @Test public void mainFailureUsesVirtualAndStopsAfterSuccess() throws Exception {
        List<String> attempts = new ArrayList<>();
        DisplayFallback.run("main", route -> { attempts.add(route); if ("main".equals(route)) throw new IOException("permission denied"); }, () -> true, (route, error) -> assertEquals("main",route));
        assertEquals(Arrays.asList("main","virtual"), attempts);
    }
    @Test public void virtualFailureUsesMainAndBothFailuresUseUnmodifiedNative() throws Exception {
        List<String> attempts = new ArrayList<>();
        DisplayFallback.run("virtual", route -> { attempts.add(route); if (!"native".equals(route)) throw new IOException("unsupported"); }, () -> true, (route,error) -> {});
        assertEquals(Arrays.asList("virtual","main","native"), attempts);
    }
    @Test public void realDisconnectAfterStartupDoesNotRetry() throws Exception {
        List<String> attempts = new ArrayList<>();
        try { DisplayFallback.run("main", route -> { attempts.add(route); throw new IOException("disconnected"); }, () -> false, (route,error) -> fail()); fail(); }
        catch (IOException expected) { assertEquals("disconnected", expected.getMessage()); }
        assertEquals(Collections.singletonList("main"), attempts);
    }
    @Test public void failingAllRoutesIsBoundedAndRetainsCause() throws Exception {
        List<String> attempts = new ArrayList<>();
        try { DisplayFallback.run("main", route -> { attempts.add(route); throw new IOException(route); }, () -> true, (route,error) -> {}); fail(); }
        catch (IOException expected) { assertEquals("native", expected.getCause().getMessage()); }
        assertEquals(3, attempts.size());
    }
}
