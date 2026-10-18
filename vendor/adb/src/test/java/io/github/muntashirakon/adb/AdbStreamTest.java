// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the {@link AdbStream} read/write paths against {@link FakeAdbd} over a real loopback socket:
 * flow control (bounded read queue), bulk reads, checksum negotiation and end-of-stream handling.
 */
public class AdbStreamTest {
    private static final int API_ANDROID_11 = 30;
    private static final int SERVER_ID = 0x1234;

    private static KeyPair sKeyPair;

    private ExecutorService mExecutor;
    private FakeAdbd mServer;
    private AdbConnection mConnection;
    private int mClientId;

    private static synchronized KeyPair getKeyPair() throws Exception {
        if (sKeyPair == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            java.security.KeyPair keyPair = generator.generateKeyPair();
            PublicKey publicKey = keyPair.getPublic();
            // The fake server performs neither AUTH nor TLS, so the certificate contents are irrelevant
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

    @Before
    public void setUp() {
        mExecutor = Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() throws Exception {
        if (mConnection != null) {
            mConnection.close();
        }
        if (mServer != null) {
            mServer.close();
        }
        mExecutor.shutdownNow();
    }

    private AdbStream connectAndOpen(int serverVersion, int serverMaxData) throws Exception {
        mServer = new FakeAdbd(serverVersion, serverMaxData);
        mConnection = AdbConnection.create("127.0.0.1", mServer.getPort(), getKeyPair(), API_ANDROID_11);
        Future<Boolean> connected = mExecutor.submit(() ->
                mConnection.connect(10, TimeUnit.SECONDS, false));
        mServer.acceptAndConnect();
        assertTrue(connected.get(10, TimeUnit.SECONDS));

        Future<AdbStream> opened = mExecutor.submit(() -> mConnection.open("shell:"));
        AdbProtocol.Message open = mServer.expect(AdbProtocol.A_OPEN);
        mClientId = open.arg0;
        mServer.sendOkay(SERVER_ID, mClientId);
        return opened.get(10, TimeUnit.SECONDS);
    }

    private static byte[] patternedBytes(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; ++i) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    private static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int off = offset;
        int remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, off, remaining);
            if (read < 0) {
                throw new IOException("Unexpected end of stream, " + remaining + " bytes remaining");
            }
            off += read;
            remaining -= read;
        }
    }

    @Test(timeout = 30_000)
    public void openTimesOutWhenPeerNeverAcksTheOpen() throws Exception {
        mServer = new FakeAdbd(AdbProtocol.A_VERSION_SKIP_CHECKSUM, 64 * 1024);
        mConnection = AdbConnection.create("127.0.0.1", mServer.getPort(), getKeyPair(), API_ANDROID_11);
        Future<Boolean> connected = mExecutor.submit(() -> mConnection.connect(10, TimeUnit.SECONDS, false));
        mServer.acceptAndConnect();
        assertTrue(connected.get(10, TimeUnit.SECONDS));

        // The server sees the OPEN but deliberately never sends OKAY; a bare wait() would hang forever.
        Future<AdbStream> opened = mExecutor.submit(() -> mConnection.open("shell:", 500));
        mServer.expect(AdbProtocol.A_OPEN);
        try {
            opened.get(10, TimeUnit.SECONDS);
            fail("open should have timed out without an establishing OKAY");
        } catch (ExecutionException e) {
            assertTrue("expected IOException, got " + e.getCause(), e.getCause() instanceof IOException);
        }
    }

    @Test(timeout = 30_000)
    public void readsBulkDataAcrossPayloadBoundaries() throws Exception {
        int maxData = 64 * 1024;
        AdbStream stream = connectAndOpen(AdbProtocol.A_VERSION_SKIP_CHECKSUM, maxData);
        byte[] expected = patternedBytes(200_000);

        // Read with an odd buffer size so reads regularly cross WRTE payload boundaries
        Future<byte[]> readFuture = mExecutor.submit(() -> {
            byte[] actual = new byte[expected.length];
            InputStream in = stream.openInputStream();
            int off = 0;
            while (off < actual.length) {
                int read = in.read(actual, off, Math.min(999, actual.length - off));
                if (read < 0) {
                    break;
                }
                off += read;
            }
            return actual;
        });

        mServer.setSoTimeout(10_000);
        int sent = 0;
        while (sent < expected.length) {
            int chunk = Math.min(maxData, expected.length - sent);
            mServer.sendWrte(SERVER_ID, mClientId, Arrays.copyOfRange(expected, sent, sent + chunk));
            // The queue never fills up here, so every WRTE has to be acknowledged right away
            mServer.expect(AdbProtocol.A_OKAY);
            sent += chunk;
        }

        assertArrayEquals(expected, readFuture.get(10, TimeUnit.SECONDS));
    }

    @Test(timeout = 30_000)
    public void boundedReadQueueWithholdsOkayUntilDrained() throws Exception {
        int maxData = 4096;
        // The read queue limit is 8 * maxData, so the 8th unread payload has to leave the OKAY deferred
        AdbStream stream = connectAndOpen(AdbProtocol.A_VERSION_SKIP_CHECKSUM, maxData);
        byte[] chunk = patternedBytes(maxData);

        mServer.setSoTimeout(10_000);
        for (int i = 0; i < 7; ++i) {
            mServer.sendWrte(SERVER_ID, mClientId, chunk);
            mServer.expect(AdbProtocol.A_OKAY);
        }
        mServer.sendWrte(SERVER_ID, mClientId, chunk);
        mServer.setSoTimeout(1_000);
        try {
            mServer.expect(AdbProtocol.A_OKAY);
            fail("OKAY must be withheld while the read queue is full");
        } catch (SocketTimeoutException expected) {
            // The queue is full: no OKAY until the application reads
        }

        // Drain a single payload; the deferred OKAY has to be sent now
        InputStream in = stream.openInputStream();
        byte[] buffer = new byte[maxData];
        readFully(in, buffer, 0, maxData);
        assertArrayEquals(chunk, buffer);
        mServer.setSoTimeout(10_000);
        mServer.expect(AdbProtocol.A_OKAY);

        // Drain the remaining queued payloads
        for (int i = 0; i < 7; ++i) {
            readFully(in, buffer, 0, maxData);
            assertArrayEquals(chunk, buffer);
        }

        // The peer may continue transmitting, and the data is intact
        mServer.sendWrte(SERVER_ID, mClientId, chunk);
        mServer.expect(AdbProtocol.A_OKAY);
        readFully(in, buffer, 0, maxData);
        assertArrayEquals(chunk, buffer);
    }

    @Test(timeout = 30_000)
    public void checksumSkippedAfterNegotiation() throws Exception {
        AdbStream stream = connectAndOpen(AdbProtocol.A_VERSION_SKIP_CHECKSUM, 4096);
        byte[] payload = patternedBytes(1024);

        stream.write(payload, 0, payload.length);

        AdbProtocol.Message wrte = mServer.expect(AdbProtocol.A_WRTE);
        assertEquals("Checksum must be skipped when both sides speak 0x01000001", 0, wrte.dataCheck);
        assertArrayEquals(payload, wrte.payload);
    }

    @Test(timeout = 30_000)
    public void checksumComputedForLegacyPeer() throws Exception {
        AdbStream stream = connectAndOpen(AdbProtocol.A_VERSION_MIN, 4096);
        byte[] payload = patternedBytes(1024);

        stream.write(payload, 0, payload.length);

        // FakeAdbd parses with A_VERSION_MIN and would throw StreamCorruptedException on a checksum mismatch
        AdbProtocol.Message wrte = mServer.expect(AdbProtocol.A_WRTE);
        assertNotEquals("Checksum must be present for a peer that predates 0x01000001", 0, wrte.dataCheck);
        assertArrayEquals(payload, wrte.payload);
    }

    @Test(timeout = 30_000)
    public void multiChunkWriteHonorsFlowControl() throws Exception {
        int maxData = 4096;
        AdbStream stream = connectAndOpen(AdbProtocol.A_VERSION_SKIP_CHECKSUM, maxData);
        byte[] big = patternedBytes(10_000);

        Future<?> writeFuture = mExecutor.submit(() -> {
            stream.write(big, 0, big.length);
            return null;
        });

        mServer.setSoTimeout(10_000);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        int[] expectedLengths = {maxData, maxData, big.length - 2 * maxData};
        for (int expectedLength : expectedLengths) {
            AdbProtocol.Message wrte = mServer.expect(AdbProtocol.A_WRTE);
            assertEquals(expectedLength, wrte.dataLength);
            received.write(wrte.payload);
            // A new WRTE may only follow once we acknowledge this one
            mServer.sendOkay(SERVER_ID, mClientId);
        }
        writeFuture.get(10, TimeUnit.SECONDS);

        assertArrayEquals(big, received.toByteArray());
    }

    @Test(timeout = 30_000)
    public void eofAfterPeerClosesWithPendingData() throws Exception {
        AdbStream stream = connectAndOpen(AdbProtocol.A_VERSION_SKIP_CHECKSUM, 4096);
        byte[] data = patternedBytes(4096);

        mServer.setSoTimeout(10_000);
        mServer.sendWrte(SERVER_ID, mClientId, data);
        mServer.expect(AdbProtocol.A_OKAY);
        mServer.sendClse(SERVER_ID, mClientId);

        // All the data queued before the CLSE has to be readable, followed by a regular end of stream
        InputStream in = stream.openInputStream();
        byte[] actual = new byte[data.length + 1];
        int total = 0;
        int read;
        while ((read = in.read(actual, total, actual.length - total)) > 0) {
            total += read;
        }
        assertEquals(-1, read);
        assertEquals(data.length, total);
        assertArrayEquals(data, Arrays.copyOf(actual, data.length));
        assertTrue(stream.isClosed());
        // End of stream has to be stable
        assertEquals(-1, in.read(actual, 0, actual.length));
    }

    @Test(timeout = 30_000)
    public void bufferedTailReadableAfterPeerClose() throws Exception {
        AdbStream stream = connectAndOpen(AdbProtocol.A_VERSION_SKIP_CHECKSUM, 4096);
        byte[] data = patternedBytes(4096);

        mServer.setSoTimeout(10_000);
        mServer.sendWrte(SERVER_ID, mClientId, data);
        mServer.expect(AdbProtocol.A_OKAY);

        // Consume part of the payload so the rest sits in the read buffer, then let the peer close
        InputStream in = stream.openInputStream();
        byte[] head = new byte[100];
        readFully(in, head, 0, head.length);
        assertArrayEquals(Arrays.copyOf(data, head.length), head);

        mServer.sendClse(SERVER_ID, mClientId);
        // Give the connection thread a moment to process the CLSE; the stream must stay readable
        // (not closed) because the read buffer still holds unread data
        Thread.sleep(300);

        // The buffered tail must not be lost
        byte[] tail = new byte[data.length - head.length];
        readFully(in, tail, 0, tail.length);
        assertArrayEquals(Arrays.copyOfRange(data, head.length, data.length), tail);
        assertEquals(-1, in.read(tail, 0, tail.length));
        assertTrue(stream.isClosed());
    }
}
