// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * The TCP transport: a plain socket that can be upgraded to TLS via the STLS exchange.
 */
class TcpChannel implements AdbChannel {
    @NonNull
    private final Socket mSocket;
    @NonNull
    private final String mHost;
    private final int mPort;
    @NonNull
    private final KeyPair mKeyPair;
    @NonNull
    private final InputStream mPlainInputStream;
    @NonNull
    private final OutputStream mPlainOutputStream;
    @Nullable
    private volatile InputStream mTlsInputStream;
    @Nullable
    private volatile OutputStream mTlsOutputStream;
    private volatile boolean mIsTls = false;

    TcpChannel(@NonNull String host, int port, @NonNull KeyPair keyPair) throws IOException {
        this.mHost = Objects.requireNonNull(host);
        this.mPort = port;
        this.mKeyPair = Objects.requireNonNull(keyPair);
        try {
            this.mSocket = new Socket();
            this.mSocket.connect(new InetSocketAddress(host, port), 8000);
        } catch (Throwable th) {
            //noinspection UnnecessaryInitCause
            throw (IOException) new IOException().initCause(th);
        }
        this.mPlainInputStream = mSocket.getInputStream();
        this.mPlainOutputStream = mSocket.getOutputStream();

        // Disable Nagle because we're sending tiny packets
        mSocket.setTcpNoDelay(true);
    }

    @NonNull
    @Override
    public InputStream getInputStream() {
        return mIsTls ? Objects.requireNonNull(mTlsInputStream) : mPlainInputStream;
    }

    @NonNull
    @Override
    public OutputStream getOutputStream() {
        return mIsTls ? Objects.requireNonNull(mTlsOutputStream) : mPlainOutputStream;
    }

    @Override
    public boolean isConnected() {
        return !mSocket.isClosed() && mSocket.isConnected();
    }

    @Override
    public boolean supportsTls() {
        return true;
    }

    @Override
    public void upgradeToTls() throws IOException {
        try {
            SSLContext sslContext = SslUtils.getSslContext(mKeyPair);
            SSLSocket tlsSocket = (SSLSocket) sslContext.getSocketFactory()
                    .createSocket(mSocket, mHost, mPort, true);
            tlsSocket.setSoTimeout(15000);
            tlsSocket.startHandshake();
            tlsSocket.setSoTimeout(0);
            mTlsInputStream = tlsSocket.getInputStream();
            mTlsOutputStream = tlsSocket.getOutputStream();
            mIsTls = true;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            //noinspection UnnecessaryInitCause
            throw (IOException) new IOException().initCause(e);
        }
    }

    @Override
    public void close() throws IOException {
        mSocket.close();
    }
}
