// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import io.github.muntashirakon.adb.desktop.DesktopUsb;

/**
 * Runs the USB transport on the host JVM against a real phone attached over USB — the Android-targeted
 * library exercised on a non-Android platform. The AGP runs {@code src/test} on the desktop JVM (stub
 * android.jar), and libusb via JNA fills the {@link UsbBulkIo} seam that {@code UsbDeviceConnection} fills
 * on-device (see {@link DesktopUsb}).
 * <p>
 * Self-skips when libusb is unavailable or no ADB device is attached, so CI stays green. To run for real:
 * <pre>
 *   brew install libusb
 *   adb kill-server
 *   ./gradlew :libadb:testReleaseUnitTest --tests '*UsbDeviceIntegrationTest'
 * </pre>
 * Approve "Allow USB debugging?" on first connect (key persisted to ~/.libadb-usb-desktop/adbkey.pk8).
 */
public class UsbDeviceIntegrationTest {
    @BeforeClass
    public static void pointJnaAtLibusb() {
        if (System.getProperty("jna.library.path") == null
                && System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            System.setProperty("jna.library.path", "/opt/homebrew/lib:/usr/local/lib");
        }
    }

    @Test(timeout = 90_000)
    public void connectsAndReadsPropsOverUsb() throws Exception {
        DesktopUsb.Session opened = DesktopUsb.open();
        Assume.assumeTrue("No USB ADB connection (see console for why); skipping", opened != null);
        try (DesktopUsb.Session session = opened) {
            AdbConnection connection = session.connection;
            assertTrue(connection.isConnectionEstablished());
            String model = runShell(connection, "getprop ro.product.model").trim();
            System.out.println("Connected over USB. ro.product.model = " + model);
            assertFalse("getprop over USB must return a model", model.isEmpty());
        }
    }

    private static String runShell(AdbConnection connection, String command) throws Exception {
        try (AdbStream stream = connection.open("shell:" + command)) {
            InputStream in = stream.openInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }
}
