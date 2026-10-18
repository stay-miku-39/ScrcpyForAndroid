// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the USB transport ({@link UsbChannel}) against {@link FakeUsbAdbd}: the legacy RSA authentication
 * flow (token signature and public-key fallback), transfer framing in both directions including packet-aligned
 * payload chunking, and the STLS refusal. The framing rules themselves are asserted inside the fake.
 */
public class UsbChannelTest {
    private static final int API_ANDROID_11 = 30;

    private static KeyPair sKeyPair;

    private FakeUsbAdbd mDevice;
    private AdbConnection mConnection;

    private static synchronized KeyPair getKeyPair() throws Exception {
        if (sKeyPair == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            java.security.KeyPair keyPair = generator.generateKeyPair();
            PublicKey publicKey = keyPair.getPublic();
            // The USB fake verifies AUTH signatures with the private key directly; no certificate is involved
            Certificate certificate = new Certificate("X.509") {
                @Override
                public byte[] getEncoded() {
                    return new byte[0];
                }

                @Override
                public void verify(PublicKey key) {
                }

                @Override
                public void verify(PublicKey key, String sigProvider) {
                }

                @Override
                public String toString() {
                    return "test certificate";
                }

                @Override
                public PublicKey getPublicKey() {
                    return publicKey;
                }
            };
            sKeyPair = new KeyPair(keyPair.getPrivate(), certificate);
        }
        return sKeyPair;
    }

    @After
    public void tearDown() throws Exception {
        if (mConnection != null) {
            mConnection.close();
        }
        if (mDevice != null) {
            mDevice.close();
        }
    }

    private boolean connect(FakeUsbAdbd device) throws Exception {
        mDevice = device;
        mConnection = new AdbConnection.Builder()
                .setChannel(new UsbChannel(device))
                .setApi(API_ANDROID_11)
                .setKeyPair(getKeyPair())
                .setDeviceName("test-host")
                .build();
        return mConnection.connect(10, TimeUnit.SECONDS, false);
    }

    private static byte[] patternedBytes(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; ++i) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    @Test(timeout = 30_000)
    public void connectAuthenticatesWithRsaTokenSignature() throws Exception {
        FakeUsbAdbd device = new FakeUsbAdbd(AdbProtocol.A_VERSION_SKIP_CHECKSUM, 64 * 1024, getKeyPair().getPrivateKey());
        assertTrue(connect(device));
        assertTrue(mConnection.isConnectionEstablished());
        assertEquals("The known-key path needs exactly one signature", 1, device.getSignatureAttempts());
        assertNull("No public key may be sent when the signature is accepted", device.getReceivedPublicKey());
        assertEquals("maxdata must be the device's advertisement", 64 * 1024, mConnection.getMaxData());
    }

    @Test(timeout = 30_000)
    public void unknownKeyFallsBackToPublicKey() throws Exception {
        FakeUsbAdbd device = new FakeUsbAdbd(AdbProtocol.A_VERSION_SKIP_CHECKSUM, 64 * 1024, getKeyPair().getPrivateKey());
        device.setRejectFirstSignature(true);
        assertTrue(connect(device));
        assertTrue(mConnection.isConnectionEstablished());
        assertNotNull("A rejected signature must be followed by the public key (the Allow-USB-debugging path)",
                device.getReceivedPublicKey());
    }

    @Test(timeout = 30_000)
    public void shellRoundTripWithChunkedPayloads() throws Exception {
        FakeUsbAdbd device = new FakeUsbAdbd(AdbProtocol.A_VERSION_SKIP_CHECKSUM, 64 * 1024, getKeyPair().getPrivateKey());
        device.setEcho(true);
        assertTrue(connect(device));

        AdbStream stream = mConnection.open("shell:");
        // Larger than the 16 KiB bulk chunk: the payload crosses several transfers in both directions,
        // and the fake asserts every non-final chunk is packet-aligned
        byte[] expected = patternedBytes(40_000);
        stream.write(expected, 0, expected.length);

        InputStream in = stream.openInputStream();
        byte[] actual = new byte[expected.length];
        int off = 0;
        while (off < actual.length) {
            int read = in.read(actual, off, actual.length - off);
            if (read < 0) {
                fail("Unexpected end of stream after " + off + " bytes");
            }
            off += read;
        }
        assertArrayEquals(expected, actual);
    }

    @Test(timeout = 30_000)
    public void stlsIsRefusedOverUsb() throws Exception {
        FakeUsbAdbd device = new FakeUsbAdbd(AdbProtocol.A_VERSION_SKIP_CHECKSUM, 64 * 1024, getKeyPair().getPrivateKey());
        device.setSendStlsOnConnect(true);
        try {
            connect(device);
            fail("STLS over USB must fail the connection");
        } catch (IOException expected) {
            // The channel cannot be upgraded; the connection has to die instead of hanging
        }
    }

    @Test(timeout = 30_000)
    public void connectFailsAfterChannelClose() throws Exception {
        FakeUsbAdbd device = new FakeUsbAdbd(AdbProtocol.A_VERSION_SKIP_CHECKSUM, 64 * 1024, getKeyPair().getPrivateKey());
        assertTrue(connect(device));
        mConnection.close();
        try {
            mConnection.open("shell:");
            fail("open must fail on a closed connection");
        } catch (IOException | InterruptedException | IllegalStateException expected) {
            // Closing tears down the channel and the connection thread
        }
    }
}
