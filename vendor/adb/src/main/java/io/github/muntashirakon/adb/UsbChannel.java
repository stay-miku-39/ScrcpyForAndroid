// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StreamCorruptedException;
import java.util.Objects;

/**
 * ADB over USB (host/OTG mode). Unlike TCP, the USB transport is packet-oriented: each 24-byte message header
 * and each payload is its own bulk transfer, and both sides issue reads of exactly the expected size (a read
 * larger than the pending transfer either hangs or swallows the next header). This class reframes
 * {@link AdbConnection}'s byte-stream reads and writes into correctly bounded transfers on a {@link UsbBulkIo},
 * so the connection logic stays transport-agnostic.
 * <p>
 * TLS is not supported: ADB over USB always authenticates with the legacy RSA token exchange.
 */
public class UsbChannel implements AdbChannel {
    @NonNull
    private final UsbBulkIo mIo;
    @NonNull
    private final UsbInputStream mInputStream;
    @NonNull
    private final UsbOutputStream mOutputStream;
    private volatile boolean mClosed = false;

    public UsbChannel(@NonNull UsbBulkIo io) {
        mIo = Objects.requireNonNull(io);
        mInputStream = new UsbInputStream();
        mOutputStream = new UsbOutputStream();
    }

    @NonNull
    @Override
    public InputStream getInputStream() {
        return mInputStream;
    }

    @NonNull
    @Override
    public OutputStream getOutputStream() {
        return mOutputStream;
    }

    @Override
    public boolean isConnected() {
        return !mClosed && mIo.isConnected();
    }

    @Override
    public boolean supportsTls() {
        return false;
    }

    @Override
    public void upgradeToTls() {
        throw new UnsupportedOperationException("TLS is not supported over USB");
    }

    @Override
    public void close() throws IOException {
        mClosed = true;
        mIo.close();
    }

    private static int readLeInt(@NonNull byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }

    private static void checkHeader(@NonNull byte[] header) throws IOException {
        int command = readLeInt(header, 0);
        int magic = readLeInt(header, 20);
        if (command != ~magic) {
            throw new StreamCorruptedException(String.format("Invalid header: Invalid magic 0x%x.", magic));
        }
        int dataLength = readLeInt(header, 12);
        if (dataLength < 0 || dataLength > AdbProtocol.MAX_PAYLOAD_V3) {
            throw new StreamCorruptedException(String.format("Invalid header: Invalid data length %d.", dataLength));
        }
    }

    /**
     * Serves the framed byte stream: fetches one header transfer (exactly 24 bytes), learns the payload length
     * from it, then fetches the payload with exactly-sized reads, chunked at {@link UsbBulkIo#getMaxBulkSize()}.
     */
    private final class UsbInputStream extends InputStream {
        private final byte[] mOne = new byte[1];
        private byte[] mChunk;
        private int mPos = 0;
        private int mEnd = 0;
        private int mPayloadRemaining = 0;

        @Override
        public int read() throws IOException {
            int n = read(mOne, 0, 1);
            return n < 0 ? -1 : (mOne[0] & 0xFF);
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (mPos >= mEnd) {
                fill();
            }
            int n = Math.min(len, mEnd - mPos);
            System.arraycopy(mChunk, mPos, b, off, n);
            mPos += n;
            return n;
        }

        private void fill() throws IOException {
            byte[] chunk;
            if (mPayloadRemaining == 0) {
                chunk = new byte[AdbProtocol.ADB_HEADER_LENGTH];
                readExact(chunk);
                checkHeader(chunk);
                mPayloadRemaining = readLeInt(chunk, 12);
            } else {
                chunk = new byte[Math.min(mPayloadRemaining, mIo.getMaxBulkSize())];
                readExact(chunk);
                mPayloadRemaining -= chunk.length;
            }
            mChunk = chunk;
            mPos = 0;
            mEnd = chunk.length;
        }

        private void readExact(@NonNull byte[] buffer) throws IOException {
            int offset = 0;
            while (offset < buffer.length) {
                int n = mIo.bulkRead(buffer, offset, buffer.length - offset);
                if (n < 0) {
                    throw new IOException("USB bulk read failed");
                }
                offset += n;
            }
        }
    }

    /**
     * Reframes arbitrary stream writes into bounded transfers: buffers until a 24-byte header is complete,
     * sends it as one transfer, then buffers its payload and sends it in packet-aligned chunks.
     */
    private final class UsbOutputStream extends OutputStream {
        private final byte[] mOne = new byte[1];
        private final byte[] mHeader = new byte[AdbProtocol.ADB_HEADER_LENGTH];
        private int mHeaderFilled = 0;
        private byte[] mPayload;
        private int mPayloadFilled = 0;

        @Override
        public void write(int b) throws IOException {
            mOne[0] = (byte) b;
            write(mOne, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int taken;
                if (mPayload == null) {
                    taken = Math.min(len, mHeader.length - mHeaderFilled);
                    System.arraycopy(b, off, mHeader, mHeaderFilled, taken);
                    mHeaderFilled += taken;
                    if (mHeaderFilled == mHeader.length) {
                        checkHeader(mHeader);
                        mIo.bulkWrite(mHeader, 0, mHeader.length);
                        mHeaderFilled = 0;
                        int dataLength = readLeInt(mHeader, 12);
                        if (dataLength > 0) {
                            mPayload = new byte[dataLength];
                            mPayloadFilled = 0;
                        }
                    }
                } else {
                    taken = Math.min(len, mPayload.length - mPayloadFilled);
                    System.arraycopy(b, off, mPayload, mPayloadFilled, taken);
                    mPayloadFilled += taken;
                    if (mPayloadFilled == mPayload.length) {
                        writeChunked(mPayload);
                        mPayload = null;
                    }
                }
                off += taken;
                len -= taken;
            }
        }

        private void writeChunked(@NonNull byte[] payload) throws IOException {
            // All chunks but the last must be packet-aligned so the device sees no premature short packet.
            int cap = Math.max(512, (mIo.getMaxBulkSize() / 512) * 512);
            int offset = 0;
            while (offset < payload.length) {
                int n = Math.min(cap, payload.length - offset);
                mIo.bulkWrite(payload, offset, n);
                offset += n;
            }
        }

        @Override
        public void flush() {
            // Transfers are emitted at message boundaries; there is nothing to flush.
        }
    }
}
