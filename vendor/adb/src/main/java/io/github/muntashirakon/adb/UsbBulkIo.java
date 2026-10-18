// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;

/**
 * Bulk-endpoint I/O of a USB device exposing an ADB interface. {@link UsbChannel} maps ADB messages onto it;
 * implementations only move raw chunks. The Android implementation lives in
 * {@link io.github.muntashirakon.adb.android.AdbUsb}.
 */
public interface UsbBulkIo extends Closeable {
    /**
     * Read one chunk from the bulk-in endpoint into {@code buffer[offset..offset+length)}. Blocks until data
     * arrives or the device is closed/detached.
     *
     * @return The number of bytes read; a chunk may be shorter than requested but never longer.
     * @throws IOException If the transfer fails or the device is gone.
     */
    int bulkRead(@NonNull byte[] buffer, int offset, int length) throws IOException;

    /**
     * Write one chunk of exactly {@code length} bytes to the bulk-out endpoint.
     *
     * @throws IOException If the transfer fails or the device is gone.
     */
    void bulkWrite(@NonNull byte[] buffer, int offset, int length) throws IOException;

    /**
     * The largest chunk a single {@link #bulkRead} or {@link #bulkWrite} may move. Must be a multiple of 512
     * (the USB high-speed bulk packet size), so that a payload split into several chunks produces no short
     * packet before its final chunk.
     */
    int getMaxBulkSize();

    /**
     * Whether the device is still open.
     */
    boolean isConnected();
}
