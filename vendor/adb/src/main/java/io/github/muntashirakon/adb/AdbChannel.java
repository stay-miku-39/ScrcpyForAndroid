// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A duplex byte channel carrying ADB protocol messages between {@link AdbConnection} and an ADB daemon.
 * <p>
 * The TCP transport is implemented by {@link TcpChannel}; ADB over USB (host/OTG mode) is implemented by
 * {@link UsbChannel} on top of a {@link UsbBulkIo}.
 */
public interface AdbChannel extends Closeable {
    /**
     * The stream ADB messages are read from.
     */
    @NonNull
    InputStream getInputStream();

    /**
     * The stream ADB messages are written to.
     */
    @NonNull
    OutputStream getOutputStream();

    /**
     * Whether the underlying transport is open.
     */
    boolean isConnected();

    /**
     * Whether the channel can be upgraded to TLS in response to A_STLS. Only network transports support this;
     * ADB over USB always uses the legacy RSA token authentication.
     */
    boolean supportsTls();

    /**
     * Upgrade the channel to TLS. May only be called when {@link #supportsTls()} returns {@code true}, after the
     * STLS exchange. Streams returned afterwards carry the TLS session.
     */
    void upgradeToTls() throws IOException;
}
