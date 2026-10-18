// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.android;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.IOException;

import io.github.muntashirakon.adb.AdbChannel;
import io.github.muntashirakon.adb.UsbBulkIo;
import io.github.muntashirakon.adb.UsbChannel;

/**
 * ADB over USB in host (OTG) mode: this device is the USB host and the device to talk to is attached over an
 * OTG cable with USB debugging enabled. The caller is responsible for USB permission
 * ({@link UsbManager#requestPermission}) before opening a channel, and for the target's "Allow USB debugging?"
 * confirmation, which shows up during the connect (legacy RSA) handshake.
 */
public final class AdbUsb {
    /**
     * The ADB interface: vendor-specific class, subclass 0x42, protocol 1.
     */
    public static final int ADB_INTERFACE_CLASS = 0xFF;
    public static final int ADB_INTERFACE_SUBCLASS = 0x42;
    public static final int ADB_INTERFACE_PROTOCOL = 1;

    private AdbUsb() {
    }

    /**
     * Find the ADB interface of a USB device, if it exposes one.
     */
    @Nullable
    public static UsbInterface findAdbInterface(@NonNull UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); ++i) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == ADB_INTERFACE_CLASS
                    && usbInterface.getInterfaceSubclass() == ADB_INTERFACE_SUBCLASS
                    && usbInterface.getInterfaceProtocol() == ADB_INTERFACE_PROTOCOL) {
                return usbInterface;
            }
        }
        return null;
    }

    /**
     * Whether a USB device exposes an ADB interface (i.e. it is an Android device with USB debugging enabled).
     */
    public static boolean isAdbDevice(@NonNull UsbDevice device) {
        return findAdbInterface(device) != null;
    }

    /**
     * Open an {@link AdbChannel} to the ADB interface of an attached USB device. USB permission for the device
     * must already be granted.
     *
     * @throws IOException If the device cannot be opened (missing permission or detached), has no ADB interface,
     *                     or its interface cannot be claimed.
     */
    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    @NonNull
    public static AdbChannel openChannel(@NonNull Context context, @NonNull UsbDevice device) throws IOException {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            throw new IOException("USB host service is unavailable");
        }
        UsbInterface usbInterface = findAdbInterface(device);
        if (usbInterface == null) {
            throw new IOException("Device has no ADB interface. Is USB debugging enabled on it?");
        }
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < usbInterface.getEndpointCount(); ++i) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                in = endpoint;
            } else {
                out = endpoint;
            }
        }
        if (in == null || out == null) {
            throw new IOException("ADB interface lacks bulk endpoints");
        }
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            throw new IOException("Could not open USB device. Has USB permission been granted?");
        }
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            throw new IOException("Could not claim the ADB interface");
        }
        // Discard anything already queued on the IN endpoint before the handshake. A cleanly reset
        // interface has nothing pending (this returns immediately), but if a previous session was torn
        // down mid-transfer the daemon may still have bytes buffered; reading them now keeps the first
        // header read from starting mid-stream. Best-effort and time-bounded.
        drainStaleInput(connection, in);
        return new UsbChannel(new DeviceConnectionBulkIo(connection, usbInterface, in, out));
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private static void drainStaleInput(@NonNull UsbDeviceConnection connection, @NonNull UsbEndpoint in) {
        // Packet-aligned buffer and a short timeout, so a full IN packet can never overflow the request.
        byte[] scratch = new byte[16 * 1024];
        long deadline = System.currentTimeMillis() + 250;
        while (System.currentTimeMillis() < deadline) {
            int n = connection.bulkTransfer(in, scratch, scratch.length, 50);
            if (n <= 0) {
                return;   // timed out (pipe empty) or errored: nothing more to drain
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private static final class DeviceConnectionBulkIo implements UsbBulkIo {
        // bulkTransfer is capped at 16 KiB before API 28; being packet-aligned it is a safe chunk everywhere.
        private static final int MAX_BULK_SIZE = 16 * 1024;

        @NonNull
        private final UsbDeviceConnection mConnection;
        @NonNull
        private final UsbInterface mInterface;
        @NonNull
        private final UsbEndpoint mIn;
        @NonNull
        private final UsbEndpoint mOut;
        private volatile boolean mClosed = false;

        private DeviceConnectionBulkIo(@NonNull UsbDeviceConnection connection, @NonNull UsbInterface usbInterface,
                                       @NonNull UsbEndpoint in, @NonNull UsbEndpoint out) {
            mConnection = connection;
            mInterface = usbInterface;
            mIn = in;
            mOut = out;
        }

        @Override
        public int bulkRead(@NonNull byte[] buffer, int offset, int length) throws IOException {
            int n = mConnection.bulkTransfer(mIn, buffer, offset, Math.min(length, MAX_BULK_SIZE), 0);
            if (n < 0) {
                throw new IOException(mClosed ? "USB channel closed" : "USB bulk read failed");
            }
            return n;
        }

        @Override
        public void bulkWrite(@NonNull byte[] buffer, int offset, int length) throws IOException {
            int n = mConnection.bulkTransfer(mOut, buffer, offset, length, 0);
            if (n != length) {
                throw new IOException(mClosed ? "USB channel closed" : "USB bulk write failed (" + n + "/" + length + ")");
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
            // Closing the connection fails any blocked bulkTransfer, unblocking the connection thread.
            mClosed = true;
            try {
                mConnection.releaseInterface(mInterface);
            } finally {
                mConnection.close();
            }
        }
    }
}
