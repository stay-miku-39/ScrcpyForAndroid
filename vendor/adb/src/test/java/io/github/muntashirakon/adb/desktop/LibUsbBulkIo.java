// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.desktop;

import java.io.IOException;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import io.github.muntashirakon.adb.UsbBulkIo;

/**
 * A {@link UsbBulkIo} backed by the host's libusb through JNA — the desktop counterpart of the Android
 * UsbDeviceConnection implementation. Test-only (see {@link io.github.muntashirakon.adb.UsbDeviceIntegrationTest}).
 */
public final class LibUsbBulkIo implements UsbBulkIo {
    private static final int MAX_BULK_SIZE = 16 * 1024;
    private static final int READ_TIMEOUT_MS = 0;     // block indefinitely (the auth dialog can take a while)
    private static final int WRITE_TIMEOUT_MS = 5_000;

    private final LibUsb mLib = LibUsb.INSTANCE;
    private final Pointer mHandle;
    private final byte mInEndpoint;
    private final byte mOutEndpoint;
    private final int mInterfaceNumber;
    private volatile boolean mClosed = false;

    public LibUsbBulkIo(Pointer handle, byte inEndpoint, byte outEndpoint, int interfaceNumber) {
        mHandle = handle;
        mInEndpoint = inEndpoint;
        mOutEndpoint = outEndpoint;
        mInterfaceNumber = interfaceNumber;
    }

    @Override
    public int bulkRead(byte[] buffer, int offset, int length) throws IOException {
        int toRead = Math.min(length, MAX_BULK_SIZE);
        try (Memory buf = new Memory(toRead)) {
            IntByReference transferred = new IntByReference();
            int r = mLib.libusb_bulk_transfer(mHandle, mInEndpoint, buf, toRead, transferred, READ_TIMEOUT_MS);
            if (r != LibUsb.LIBUSB_SUCCESS) {
                throw new IOException(mClosed ? "USB channel closed" : "bulk read failed: " + mLib.libusb_error_name(r));
            }
            int n = transferred.getValue();
            buf.read(0, buffer, offset, n);
            return n;
        }
    }

    @Override
    public void bulkWrite(byte[] buffer, int offset, int length) throws IOException {
        try (Memory buf = new Memory(Math.max(1, length))) {
            buf.write(0, buffer, offset, length);
            IntByReference transferred = new IntByReference();
            int r = mLib.libusb_bulk_transfer(mHandle, mOutEndpoint, buf, length, transferred, WRITE_TIMEOUT_MS);
            if (r != LibUsb.LIBUSB_SUCCESS) {
                throw new IOException(mClosed ? "USB channel closed" : "bulk write failed: " + mLib.libusb_error_name(r));
            }
            if (transferred.getValue() != length) {
                throw new IOException("short bulk write (" + transferred.getValue() + "/" + length + ")");
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
        try {
            mLib.libusb_release_interface(mHandle, mInterfaceNumber);
        } finally {
            mLib.libusb_close(mHandle);
        }
    }
}
