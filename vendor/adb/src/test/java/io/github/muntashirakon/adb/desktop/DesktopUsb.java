// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.desktop;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.UsbChannel;

/**
 * Test-only helper: opens an {@link AdbConnection} to a phone attached to the host over USB, using libusb
 * (via JNA) behind the transport-agnostic {@link io.github.muntashirakon.adb.UsbBulkIo} seam. Lets the
 * Android-targeted USB transport be exercised from a desktop JVM. Shared by the USB device tests.
 */
public final class DesktopUsb {
    private static final int ADB_CLASS = 0xFF;
    private static final int ADB_SUBCLASS = 0x42;
    private static final int ADB_PROTOCOL = 1;
    private static final File KEY_DIR = new File(System.getProperty("user.home"), ".libadb-usb-desktop");

    private DesktopUsb() { }

    /** A live USB ADB connection; closing it tears down the connection, the libusb handle and libusb itself. */
    public static final class Session implements Closeable {
        public final AdbConnection connection;
        private final LibUsb lib;

        private Session(AdbConnection connection, LibUsb lib) {
            this.connection = connection;
            this.lib = lib;
        }

        @Override
        public void close() throws IOException {
            try {
                connection.close();   // closes UsbChannel -> releases interface + closes the libusb handle
            } finally {
                lib.libusb_exit(null);
            }
        }
    }

    private static final class AdbIface {
        int number;
        byte inEndpoint;
        byte outEndpoint;
    }

    /**
     * Opens a USB ADB connection, or returns {@code null} if libusb is unavailable, no ADB device is attached,
     * the interface can't be claimed (run {@code adb kill-server}), or the connection fails. The reason is
     * printed so a skipped test explains itself.
     */
    public static Session open() throws Exception {
        LibUsb lib;
        try {
            lib = LibUsb.INSTANCE;
        } catch (Throwable t) {
            System.out.println("libusb unavailable: " + t.getMessage());
            return null;
        }
        if (lib.libusb_init(null) != LibUsb.LIBUSB_SUCCESS) {
            System.out.println("libusb_init failed");
            return null;
        }
        boolean ok = false;
        try {
            PointerByReference listRef = new PointerByReference();
            long count = lib.libusb_get_device_list(null, listRef);
            if (count < 0) {
                System.out.println("libusb_get_device_list failed");
                return null;
            }
            Pointer listHead = listRef.getValue();
            Pointer[] devices = count == 0 ? new Pointer[0] : listHead.getPointerArray(0, (int) count);

            Pointer adbDevice = null;
            AdbIface adbIface = null;
            for (Pointer device : devices) {
                AdbIface found = findAdbInterface(lib, device);
                if (found != null) {
                    adbDevice = device;
                    adbIface = found;
                    break;
                }
            }
            if (adbDevice == null) {
                lib.libusb_free_device_list(listHead, 1);
                System.out.println("no ADB USB device attached");
                return null;
            }
            if (adbIface.inEndpoint == 0 || adbIface.outEndpoint == 0) {
                lib.libusb_free_device_list(listHead, 1);
                System.out.println("ADB interface has no bulk endpoints");
                return null;
            }

            PointerByReference handleRef = new PointerByReference();
            if (lib.libusb_open(adbDevice, handleRef) != LibUsb.LIBUSB_SUCCESS) {
                lib.libusb_free_device_list(listHead, 1);
                System.out.println("libusb_open failed (permission?)");
                return null;
            }
            Pointer handle = handleRef.getValue();
            // The handle keeps the device alive; the list can be released now.
            lib.libusb_free_device_list(listHead, 1);

            lib.libusb_set_auto_detach_kernel_driver(handle, 1);
            if (lib.libusb_claim_interface(handle, adbIface.number) != LibUsb.LIBUSB_SUCCESS) {
                lib.libusb_close(handle);
                System.out.println("claim_interface failed (run `adb kill-server`)");
                return null;
            }

            // Recover from a desynced pipe (e.g. a previously interrupted transfer left data queued):
            // reset the endpoints and drain any stale IN data with 512-aligned, short-timeout reads,
            // so the first header read starts clean instead of hitting LIBUSB_ERROR_OVERFLOW.
            lib.libusb_clear_halt(handle, adbIface.inEndpoint);
            lib.libusb_clear_halt(handle, adbIface.outEndpoint);
            drainStaleInput(lib, handle, adbIface.inEndpoint);

            LibUsbBulkIo io = new LibUsbBulkIo(handle, adbIface.inEndpoint, adbIface.outEndpoint, adbIface.number);
            PrivateKey privateKey = loadOrCreateKey();
            Certificate certificate = certForPublicKey(derivePublicKey(privateKey));
            AdbConnection connection = new AdbConnection.Builder()
                    .setChannel(new UsbChannel(io))
                    .setApi(34)
                    .setPrivateKey(privateKey)
                    .setCertificate(certificate)
                    .setDeviceName("libadb-desktop-test")
                    .build();
            if (!connection.connect(60, TimeUnit.SECONDS, false)) {
                connection.close();
                System.out.println("USB connect failed / timed out");
                return null;
            }
            ok = true;
            return new Session(connection, lib);
        } finally {
            if (!ok) lib.libusb_exit(null);
        }
    }

    /**
     * Read and discard any data already queued on the IN endpoint, using 512-aligned buffers and a short
     * timeout so a full IN packet can never overflow the request. Bounded so a large backlog (e.g. a killed
     * multi-hundred-MB transfer) can't stall startup indefinitely.
     */
    private static void drainStaleInput(LibUsb lib, Pointer handle, byte inEndpoint) {
        Memory scratch = new Memory(16 * 1024);   // multiple of the 512-byte bulk max packet size
        IntByReference transferred = new IntByReference();
        long deadline = System.nanoTime() + 3_000_000_000L;   // cap the drain at ~3s
        while (System.nanoTime() < deadline) {
            int r = lib.libusb_bulk_transfer(handle, inEndpoint, scratch, (int) scratch.size(), transferred, 150);
            if (r == LibUsb.LIBUSB_ERROR_TIMEOUT) return;   // pipe is empty
            if (r != LibUsb.LIBUSB_SUCCESS) return;         // nothing more we can usefully do
            if (transferred.getValue() == 0) return;
        }
    }

    private static AdbIface findAdbInterface(LibUsb lib, Pointer device) {
        PointerByReference configRef = new PointerByReference();
        if (lib.libusb_get_active_config_descriptor(device, configRef) != LibUsb.LIBUSB_SUCCESS) return null;
        Pointer configPtr = configRef.getValue();
        try {
            LibUsb.ConfigDescriptor config = new LibUsb.ConfigDescriptor(configPtr);
            for (LibUsb.Interface iface : config.interfaces()) {
                for (LibUsb.InterfaceDescriptor d : iface.altsettings()) {
                    if ((d.bInterfaceClass & 0xFF) == ADB_CLASS
                            && (d.bInterfaceSubClass & 0xFF) == ADB_SUBCLASS
                            && (d.bInterfaceProtocol & 0xFF) == ADB_PROTOCOL) {
                        // Read everything now, before the config descriptor is freed below.
                        AdbIface result = new AdbIface();
                        result.number = d.bInterfaceNumber & 0xFF;
                        for (LibUsb.EndpointDescriptor ep : d.endpoints()) {
                            if ((ep.bmAttributes & LibUsb.LIBUSB_TRANSFER_TYPE_MASK) != LibUsb.LIBUSB_TRANSFER_TYPE_BULK) {
                                continue;
                            }
                            if ((ep.bEndpointAddress & LibUsb.LIBUSB_ENDPOINT_DIR_MASK) == LibUsb.LIBUSB_ENDPOINT_IN) {
                                result.inEndpoint = ep.bEndpointAddress;
                            } else {
                                result.outEndpoint = ep.bEndpointAddress;
                            }
                        }
                        return result;
                    }
                }
            }
        } finally {
            lib.libusb_free_config_descriptor(configPtr);
        }
        return null;
    }

    private static PrivateKey loadOrCreateKey() throws Exception {
        File keyFile = new File(KEY_DIR, "adbkey.pk8");
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        if (keyFile.isFile()) {
            return rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        KEY_DIR.mkdirs();
        Files.write(keyFile.toPath(), pair.getPrivate().getEncoded());
        return pair.getPrivate();
    }

    private static PublicKey derivePublicKey(PrivateKey privateKey) throws Exception {
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
        return KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }

    private static Certificate certForPublicKey(PublicKey publicKey) {
        return new Certificate("X.509") {
            @Override public byte[] getEncoded() { return new byte[0]; }
            @Override public void verify(PublicKey key) { }
            @Override public void verify(PublicKey key, String sigProvider) { }
            @Override public String toString() { return "desktop test certificate"; }
            @Override public PublicKey getPublicKey() { return publicKey; }
        };
    }
}
