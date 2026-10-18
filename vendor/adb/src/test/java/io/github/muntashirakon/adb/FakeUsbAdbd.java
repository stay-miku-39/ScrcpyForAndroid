// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An in-process ADB daemon speaking the USB transport, used to exercise {@link UsbChannel} in local unit tests.
 * It plays the device side of the {@link UsbBulkIo} contract and strictly asserts the USB framing rules that a
 * real adbd relies on:
 * <ul>
 *     <li>every message header is its own 24-byte transfer (a coalesced header+payload write would overrun the
 *     device's fixed-size header read);</li>
 *     <li>a payload chunk never crosses into the next message;</li>
 *     <li>every payload chunk but the last is a multiple of 512, so the device sees no premature short packet;</li>
 *     <li>the host never reads past the pending transfer when the transfer ends packet-aligned (on hardware that
 *     read would hang or swallow the next header).</li>
 * </ul>
 * Unlike {@link FakeAdbd}, authentication is real: it challenges with a token and verifies the RSA signature,
 * optionally rejecting the first signature to force the public-key fallback (the "Allow USB debugging?" path).
 */
class FakeUsbAdbd implements UsbBulkIo, Closeable {
    static final int SERVER_ID = 0x5678;
    private static final int MAX_BULK_SIZE = 16 * 1024;

    private final int mVersion;
    private final int mMaxData;
    private final PrivateKey mExpectedKey;

    private boolean mAuthRequired = true;
    private boolean mRejectFirstSignature = false;
    private boolean mSendStlsOnConnect = false;
    private boolean mEcho = false;

    private byte[] mToken;
    private boolean mRejectedOnce = false;
    private byte[] mReceivedPublicKey;
    private int mSignatureAttempts = 0;

    // Host -> device: framing state of the device's message assembly
    private final byte[] mHeader = new byte[AdbProtocol.ADB_HEADER_LENGTH];
    private int mHeaderFilled = 0;
    private byte[] mPayload;
    private int mPayloadFilled = 0;

    // Device -> host: queued transfers, each entry one bulk transfer
    private final LinkedBlockingQueue<byte[]> mTransfers = new LinkedBlockingQueue<>();
    private byte[] mCurrentTransfer;
    private int mCurrentTransferPos = 0;

    private volatile boolean mClosed = false;

    FakeUsbAdbd(int version, int maxData, PrivateKey expectedKey) {
        mVersion = version;
        mMaxData = maxData;
        mExpectedKey = expectedKey;
    }

    void setAuthRequired(boolean authRequired) {
        mAuthRequired = authRequired;
    }

    void setRejectFirstSignature(boolean rejectFirstSignature) {
        mRejectFirstSignature = rejectFirstSignature;
    }

    void setSendStlsOnConnect(boolean sendStlsOnConnect) {
        mSendStlsOnConnect = sendStlsOnConnect;
    }

    void setEcho(boolean echo) {
        mEcho = echo;
    }

    byte[] getReceivedPublicKey() {
        return mReceivedPublicKey;
    }

    int getSignatureAttempts() {
        return mSignatureAttempts;
    }

    // --- UsbBulkIo: the host side of the cable ---

    @Override
    public int bulkRead(byte[] buffer, int offset, int length) throws IOException {
        try {
            while (mCurrentTransfer == null) {
                byte[] transfer = mTransfers.poll(50, TimeUnit.MILLISECONDS);
                if (transfer != null) {
                    mCurrentTransfer = transfer;
                    mCurrentTransferPos = 0;
                } else if (mClosed) {
                    throw new IOException("Fake USB device closed");
                }
            }
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while waiting for a transfer", e);
        }
        int remaining = mCurrentTransfer.length - mCurrentTransferPos;
        if (length > remaining && remaining % 512 == 0) {
            throw new AssertionError("Host read of " + length + " bytes would outlive the pending " + remaining
                    + "-byte packet-aligned transfer: on hardware this hangs or swallows the next header");
        }
        int n = Math.min(length, remaining);
        System.arraycopy(mCurrentTransfer, mCurrentTransferPos, buffer, offset, n);
        mCurrentTransferPos += n;
        if (mCurrentTransferPos == mCurrentTransfer.length) {
            mCurrentTransfer = null;
        }
        return n;
    }

    @Override
    public void bulkWrite(byte[] buffer, int offset, int length) throws IOException {
        if (mPayload == null) {
            if (length != AdbProtocol.ADB_HEADER_LENGTH) {
                throw new AssertionError("Message header must be its own 24-byte transfer, got " + length + " bytes");
            }
            System.arraycopy(buffer, offset, mHeader, 0, length);
            mHeaderFilled = length;
            int dataLength = readLeInt(mHeader, 12);
            if (dataLength > 0) {
                mPayload = new byte[dataLength];
                mPayloadFilled = 0;
            } else {
                dispatchMessage();
            }
        } else {
            int remaining = mPayload.length - mPayloadFilled;
            if (length > remaining) {
                throw new AssertionError("Payload chunk of " + length + " bytes crosses the message boundary ("
                        + remaining + " bytes remaining)");
            }
            if (length < remaining && length % 512 != 0) {
                throw new AssertionError("Non-final payload chunk of " + length
                        + " bytes is not packet-aligned: the device would see a premature short packet");
            }
            System.arraycopy(buffer, offset, mPayload, mPayloadFilled, length);
            mPayloadFilled += length;
            if (mPayloadFilled == mPayload.length) {
                dispatchMessage();
            }
        }
    }

    @Override
    public int getMaxBulkSize() {
        return MAX_BULK_SIZE;
    }

    @Override
    public boolean isConnected() {
        return !mClosed;
    }

    @Override
    public void close() {
        mClosed = true;
    }

    // --- The device's protocol state machine ---

    private void dispatchMessage() throws IOException {
        ByteArrayOutputStream messageBytes = new ByteArrayOutputStream();
        messageBytes.write(mHeader, 0, mHeaderFilled);
        if (mPayload != null) {
            messageBytes.write(mPayload, 0, mPayload.length);
        }
        mHeaderFilled = 0;
        mPayload = null;
        AdbProtocol.Message message = AdbProtocol.Message.parse(
                new ByteArrayInputStream(messageBytes.toByteArray()), AdbProtocol.A_VERSION_SKIP_CHECKSUM,
                AdbProtocol.MAX_PAYLOAD_V3);
        handleMessage(message);
    }

    private void handleMessage(AdbProtocol.Message message) throws IOException {
        switch (message.command) {
            case AdbProtocol.A_CNXN:
                if (mSendStlsOnConnect) {
                    enqueueMessage(AdbProtocol.generateStls());
                } else if (mAuthRequired) {
                    sendChallenge();
                } else {
                    sendConnected();
                }
                break;
            case AdbProtocol.A_AUTH:
                if (message.arg0 == AdbProtocol.ADB_AUTH_SIGNATURE) {
                    ++mSignatureAttempts;
                    byte[] expected;
                    try {
                        expected = AndroidPubkey.adbAuthSign(mExpectedKey, mToken);
                    } catch (Exception e) {
                        throw new IOException("Could not compute the expected signature", e);
                    }
                    if (!Arrays.equals(expected, message.payload)) {
                        throw new AssertionError("AUTH signature does not verify against the challenged token");
                    }
                    if (mRejectFirstSignature && !mRejectedOnce) {
                        // An unknown key: adbd would re-challenge, prompting the client to send its public key
                        mRejectedOnce = true;
                        sendChallenge();
                    } else {
                        sendConnected();
                    }
                } else if (message.arg0 == AdbProtocol.ADB_AUTH_RSAPUBLICKEY) {
                    // Simulates the user accepting the "Allow USB debugging?" dialog
                    mReceivedPublicKey = message.payload;
                    sendConnected();
                }
                break;
            case AdbProtocol.A_OPEN:
                enqueueMessage(AdbProtocol.generateReady(SERVER_ID, message.arg0));
                break;
            case AdbProtocol.A_WRTE:
                enqueueMessage(AdbProtocol.generateReady(SERVER_ID, message.arg0));
                if (mEcho) {
                    enqueueMessage(AdbProtocol.generateWrite(SERVER_ID, message.arg0, message.payload, 0,
                            message.payload.length));
                }
                break;
            case AdbProtocol.A_OKAY:
            case AdbProtocol.A_CLSE:
            default:
                // Nothing to do
                break;
        }
    }

    private void sendChallenge() {
        mToken = new byte[20];
        for (int i = 0; i < mToken.length; ++i) {
            mToken[i] = (byte) (i * 17 + 3);
        }
        enqueueMessage(AdbProtocol.generateAuth(AdbProtocol.ADB_AUTH_TOKEN, mToken));
    }

    private void sendConnected() {
        enqueueMessage(AdbProtocol.generateMessage(AdbProtocol.A_CNXN, mVersion, mMaxData,
                StringCompat.getBytes("device::\0", "UTF-8")));
    }

    /**
     * Split a full message into transfers the way adbd sends them: the header and the payload each as one.
     */
    private void enqueueMessage(byte[] message) {
        mTransfers.add(Arrays.copyOfRange(message, 0, AdbProtocol.ADB_HEADER_LENGTH));
        if (message.length > AdbProtocol.ADB_HEADER_LENGTH) {
            mTransfers.add(Arrays.copyOfRange(message, AdbProtocol.ADB_HEADER_LENGTH, message.length));
        }
    }

    private static int readLeInt(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }
}
