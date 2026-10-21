package org.scrcpy.android;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionTraceTest {
    @Test public void stageDurationAndTotalUseMonotonicClock() throws Exception {
        AtomicLong now = new AtomicLong(); List<String> logs = new ArrayList<>(), statuses = new ArrayList<>();
        ConnectionTrace trace = new ConnectionTrace(statuses::add, logs::add, now::get);
        now.set(5000000);
        String result = trace.call("upload", "uploading", "bytes=100", () -> { now.set(17000000); return "done"; });
        assertEquals("done", result); assertEquals(Collections.singletonList("uploading"), statuses);
        assertTrue(logs.get(1).contains("durationMs=12 elapsedMs=17")); assertTrue(logs.get(1).contains("result=ok"));
    }
    @Test public void failedStageRetainsFailureAndIsNotLoggedAsSuccess() {
        List<String> logs = new ArrayList<>(); ConnectionTrace trace = new ConnectionTrace(s -> {}, logs::add, () -> 0);
        try { trace.call("handshake", "connecting", "", () -> { throw new IOException("peer closed"); }); fail(); }
        catch (Exception expected) { assertEquals("peer closed", expected.getMessage()); }
        assertTrue(logs.get(1).contains("result=failed-or-cancelled"));
    }
    @Test public void asynchronousCompletionAndCleanupLogOnce() {
        List<String> logs = new ArrayList<>(); ConnectionTrace trace = new ConnectionTrace(s -> {}, logs::add, () -> 0);
        ConnectionTrace.Phase phase = trace.begin("first-frame", "waiting", ""); phase.success(); phase.close(); phase.success();
        assertEquals(2, logs.size()); assertTrue(logs.get(1).contains("result=ok"));
    }
}
