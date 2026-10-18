package org.scrcpy.android;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class MicrophoneTargetTest {
    @Test public void targetRejectsShellSyntax() {
        assertTrue(MicrophoneForwarder.validPackage("org.scrcpy.android.test"));
        assertFalse(MicrophoneForwarder.validPackage("com.app;id"));
        assertFalse(MicrophoneForwarder.validPackage("com.app\nother"));
        assertFalse(MicrophoneForwarder.validPackage("$(id)"));
        assertFalse(MicrophoneForwarder.validPackage(""));
    }
    @Test public void uidSelectionRequiresExactPackage() throws Exception {
        assertEquals(10234, MicrophoneForwarder.packageUid("package:com.app.extra uid:10345\npackage:com.app uid:10234\n", "com.app"));
    }
    @Test(expected = IOException.class) public void partialPackageIsNotSelected() throws Exception {
        MicrophoneForwarder.packageUid("package:com.app.extra uid:10345\n", "com.app");
    }
    @Test(expected = IOException.class) public void systemUidIsNotRedirected() throws Exception {
        MicrophoneForwarder.packageUid("package:com.app uid:1000\n", "com.app");
    }
    @Test public void currentAppFromVersionedActivityDumps() {
        assertEquals("org.scrcpy.android.test", MicrophoneForwarder.foregroundPackage(" topResumedActivity=ActivityRecord{a u0 org.scrcpy.android.test/.Probe t1}\n"));
        assertEquals("com.app", MicrophoneForwarder.foregroundPackage(" mResumedActivity: ActivityRecord{b u0 com.app/.Main t2}\n"));
        assertEquals("", MicrophoneForwarder.foregroundPackage("mLastPausedActivity: com.other/.Main"));
    }
}
