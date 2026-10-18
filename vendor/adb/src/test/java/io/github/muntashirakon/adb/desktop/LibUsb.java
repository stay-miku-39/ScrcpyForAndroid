// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.desktop;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Minimal JNA binding to the system libusb, used only by {@link io.github.muntashirakon.adb.UsbDeviceIntegrationTest}
 * to reach a phone attached to the host. Test-only: it lets the (Android-targeted) USB transport run on the
 * desktop JVM, without pulling any USB dependency into the shipped library. usb4java is avoided because its
 * 1.3.0 jar has no darwin-aarch64 native.
 */
public interface LibUsb extends Library {
    LibUsb INSTANCE = Native.load("usb-1.0", LibUsb.class);

    int LIBUSB_SUCCESS = 0;
    int LIBUSB_TRANSFER_TYPE_MASK = 0x03;
    int LIBUSB_TRANSFER_TYPE_BULK = 0x02;
    int LIBUSB_ENDPOINT_DIR_MASK = 0x80;
    int LIBUSB_ENDPOINT_IN = 0x80;

    int libusb_init(PointerByReference ctx);
    void libusb_exit(Pointer ctx);
    String libusb_error_name(int errcode);

    long libusb_get_device_list(Pointer ctx, PointerByReference list);
    void libusb_free_device_list(Pointer list, int unref_devices);

    int libusb_get_device_descriptor(Pointer device, DeviceDescriptor desc);
    int libusb_get_active_config_descriptor(Pointer device, PointerByReference config);
    void libusb_free_config_descriptor(Pointer config);

    int libusb_open(Pointer device, PointerByReference handle);
    void libusb_close(Pointer handle);
    int libusb_claim_interface(Pointer handle, int interfaceNumber);
    int libusb_release_interface(Pointer handle, int interfaceNumber);
    int libusb_set_auto_detach_kernel_driver(Pointer handle, int enable);
    int libusb_clear_halt(Pointer handle, byte endpoint);

    int LIBUSB_ERROR_TIMEOUT = -7;

    int libusb_bulk_transfer(Pointer handle, byte endpoint, Pointer data, int length,
                             IntByReference transferred, int timeout);

    // --- struct mappings (field order matches the libusb 1.0 ABI) ---

    class DeviceDescriptor extends Structure {
        public byte bLength;
        public byte bDescriptorType;
        public short bcdUSB;
        public byte bDeviceClass;
        public byte bDeviceSubClass;
        public byte bDeviceProtocol;
        public byte bMaxPacketSize0;
        public short idVendor;
        public short idProduct;
        public short bcdDevice;
        public byte iManufacturer;
        public byte iProduct;
        public byte iSerialNumber;
        public byte bNumConfigurations;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("bLength", "bDescriptorType", "bcdUSB", "bDeviceClass", "bDeviceSubClass",
                    "bDeviceProtocol", "bMaxPacketSize0", "idVendor", "idProduct", "bcdDevice", "iManufacturer",
                    "iProduct", "iSerialNumber", "bNumConfigurations");
        }
    }

    class EndpointDescriptor extends Structure {
        public byte bLength;
        public byte bDescriptorType;
        public byte bEndpointAddress;
        public byte bmAttributes;
        public short wMaxPacketSize;
        public byte bInterval;
        public byte bRefresh;
        public byte bSynchAddress;
        public Pointer extra;
        public int extra_length;

        public EndpointDescriptor() { }
        public EndpointDescriptor(Pointer p) { super(p); read(); }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("bLength", "bDescriptorType", "bEndpointAddress", "bmAttributes", "wMaxPacketSize",
                    "bInterval", "bRefresh", "bSynchAddress", "extra", "extra_length");
        }
    }

    class InterfaceDescriptor extends Structure {
        public byte bLength;
        public byte bDescriptorType;
        public byte bInterfaceNumber;
        public byte bAlternateSetting;
        public byte bNumEndpoints;
        public byte bInterfaceClass;
        public byte bInterfaceSubClass;
        public byte bInterfaceProtocol;
        public byte iInterface;
        public Pointer endpoint;       // libusb_endpoint_descriptor[]
        public Pointer extra;
        public int extra_length;

        public InterfaceDescriptor() { }
        public InterfaceDescriptor(Pointer p) { super(p); read(); }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("bLength", "bDescriptorType", "bInterfaceNumber", "bAlternateSetting",
                    "bNumEndpoints", "bInterfaceClass", "bInterfaceSubClass", "bInterfaceProtocol", "iInterface",
                    "endpoint", "extra", "extra_length");
        }

        public EndpointDescriptor[] endpoints() {
            if (bNumEndpoints == 0 || endpoint == null) return new EndpointDescriptor[0];
            EndpointDescriptor[] arr = new EndpointDescriptor[bNumEndpoints & 0xFF];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = new EndpointDescriptor(endpoint.share((long) i * new EndpointDescriptor().size()));
            }
            return arr;
        }
    }

    class Interface extends Structure {
        public Pointer altsetting;     // libusb_interface_descriptor[]
        public int num_altsetting;

        public Interface() { }
        public Interface(Pointer p) { super(p); read(); }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("altsetting", "num_altsetting");
        }

        public InterfaceDescriptor[] altsettings() {
            if (num_altsetting == 0 || altsetting == null) return new InterfaceDescriptor[0];
            InterfaceDescriptor[] arr = new InterfaceDescriptor[num_altsetting];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = new InterfaceDescriptor(altsetting.share((long) i * new InterfaceDescriptor().size()));
            }
            return arr;
        }
    }

    class ConfigDescriptor extends Structure {
        public byte bLength;
        public byte bDescriptorType;
        public short wTotalLength;
        public byte bNumInterfaces;
        public byte bConfigurationValue;
        public byte iConfiguration;
        public byte bmAttributes;
        public byte bMaxPower;
        public Pointer iface;          // libusb_interface[]
        public Pointer extra;
        public int extra_length;

        public ConfigDescriptor() { }
        public ConfigDescriptor(Pointer p) { super(p); read(); }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("bLength", "bDescriptorType", "wTotalLength", "bNumInterfaces",
                    "bConfigurationValue", "iConfiguration", "bmAttributes", "bMaxPower", "iface", "extra",
                    "extra_length");
        }

        public Interface[] interfaces() {
            if (bNumInterfaces == 0 || iface == null) return new Interface[0];
            Interface[] arr = new Interface[bNumInterfaces & 0xFF];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = new Interface(iface.share((long) i * new Interface().size()));
            }
            return arr;
        }
    }
}
