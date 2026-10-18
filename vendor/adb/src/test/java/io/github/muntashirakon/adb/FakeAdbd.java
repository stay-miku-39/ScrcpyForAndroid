// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * A minimal in-process, plain-text (no AUTH/TLS) ADB daemon used to exercise {@link AdbConnection} and
 * {@link AdbStream} over a real loopback socket in local unit tests.
 */
class FakeAdbd implements Closeable {
    private final ServerSocket mServerSocket;
    private final int mVersion;
    private final int mMaxData;

    private Socket mSocket;
    private InputStream mIn;
    private OutputStream mOut;

    FakeAdbd(int version, int maxData) throws IOException {
        mVersion = version;
        mMaxData = maxData;
        mServerSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    int getPort() {
        return mServerSocket.getLocalPort();
    }

    /**
     * Accepts the client connection, consumes its CNXN packet and replies with our own,
     * establishing the connection.
     */
    void acceptAndConnect() throws IOException {
        mSocket = mServerSocket.accept();
        mSocket.setTcpNoDelay(true);
        mIn = mSocket.getInputStream();
        mOut = mSocket.getOutputStream();
        expect(AdbProtocol.A_CNXN);
        send(AdbProtocol.generateMessage(AdbProtocol.A_CNXN, mVersion, mMaxData,
                StringCompat.getBytes("device::\0", "UTF-8")));
    }

    void setSoTimeout(int millis) throws SocketException {
        mSocket.setSoTimeout(millis);
    }

    AdbProtocol.Message readMessage() throws IOException {
        return AdbProtocol.Message.parse(mIn, mVersion, mMaxData);
    }

    AdbProtocol.Message expect(int command) throws IOException {
        AdbProtocol.Message message = readMessage();
        if (message.command != command) {
            throw new IOException("Expected command 0x" + Integer.toHexString(command) + " but got " + message);
        }
        return message;
    }

    void send(byte[] packet) throws IOException {
        mOut.write(packet);
        mOut.flush();
    }

    void sendOkay(int localId, int remoteId) throws IOException {
        send(AdbProtocol.generateReady(localId, remoteId));
    }

    void sendWrte(int localId, int remoteId, byte[] data) throws IOException {
        send(AdbProtocol.generateWrite(localId, remoteId, data, 0, data.length));
    }

    void sendClse(int localId, int remoteId) throws IOException {
        send(AdbProtocol.generateClose(localId, remoteId));
    }

    @Override
    public void close() throws IOException {
        if (mSocket != null) {
            mSocket.close();
        }
        mServerSocket.close();
    }
}
