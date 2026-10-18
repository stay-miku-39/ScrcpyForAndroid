# ADB over USB (host / OTG)

This branch adds an ADB-over-USB transport so a device running this library can talk to a
**second** Android device attached over a USB OTG cable, in addition to the existing
network (TCP/TLS) transports.

## Design

`AdbConnection` no longer owns a socket directly; it drives an `AdbChannel`:

- `AdbChannel` — duplex byte channel + TLS-upgrade hook.
- `TcpChannel` — the original socket transport (plain + STLS upgrade). Unchanged behaviour.
- `UsbChannel` — reframes the ADB message byte stream onto packet-oriented USB bulk transfers
  (header and payload as separate transfers, packet-aligned payload chunking). USB uses the
  legacy RSA token authentication, so `supportsTls()` is `false` — no pairing, no TLS.
- `UsbBulkIo` — the platform bulk-endpoint seam. `android/AdbUsb.java` implements it with
  `UsbDeviceConnection` (host mode); the test suite implements it with libusb.

Entry point: `AbsAdbConnectionManager.connectUsb(context, usbDevice)`. The caller must hold USB
permission for the device; on first connect the target shows its "Allow USB debugging?" dialog
(legacy RSA auth), so use a generous `setTimeout`.

## Verification status

Verified:
- **Unit tests (JVM):** `UsbChannelTest` (framing in both directions, legacy RSA auth incl. the
  unknown-key public-key fallback, STLS refusal) and `AdbStreamTest` (the `TcpChannel` refactor,
  over real loopback sockets) — all green.
- **Real hardware, via the libusb test path:** connect + auth + `getprop` and a 1 GiB streamed
  transfer against a Pixel 8a, byte-exact, no timeout (`UsbDeviceIntegrationTest`,
  `UsbThroughputTest`; both self-skip without a device — see their headers to run).
- **Throughput:** on par with platform `adb` on the streaming path; the `sync:` file-transfer
  path (what acquisitions use) reaches the device's USB-2.0 ceiling.

Not yet verified (needs a device):
- The **Android** `UsbBulkIo` (`AdbUsb.DeviceConnectionBulkIo`, real `UsbDeviceConnection`) has
  only been exercised through the desktop libusb path so far, not on-device.
- OEM/host-controller matrix, cable-yank / power / OTG-host quirks.
- `libadb`'s own `sync:` pull throughput over USB (expected ~USB-2.0 ceiling; unconfirmed).

## Known robustness note

An interrupted transfer (or handing the device back and forth between the system `adb` server and
this transport) can leave data queued on the IN endpoint and desync the next connection. On connect
the transport now drains the IN endpoint (`AdbUsb.drainStaleInput`) as a best-effort recovery; a
physical replug always clears it.
