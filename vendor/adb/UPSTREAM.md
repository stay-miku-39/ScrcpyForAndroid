# Vendored LibADB

Source: https://github.com/osservatorionessuno/libadb-android-bc
Tag: 3.4.0
Commit: dcd931071b5e4b9ebc0ac598201755d5a8814b71

The original `src` tree and COPYING are retained. This project uses the Apache-2.0
option of the upstream dual license. Individual source headers and third-party
notices (including the BSD-licensed ADB and SPAKE2 code) remain in the source.

Local changes: simplified Gradle integration; bounded TCP connect/pairing/TLS
timeouts; close pairing sockets even when the TLS handshake fails; omit peer
info from pairing logs; corrected InputStream single-byte and interrupted-read
semantics. Upstream already provides a bounded stream-open wait.

Additional transport fixes retained by this Android host: guarded stream-open
notification and timeout, bulk reads and bounded queue backpressure, graceful
peer EOF distinct from a lost connection, USB transport adaptation and its tests.
As of 0.4.0, concurrent opens allocate atomic local IDs; an establishing OKAY
followed immediately by CLSE is accepted as an empty completed stream instead
of a rejected service. CLSE without OKAY still rejects the open. Loopback tests
exercise concurrent short commands, real rejection and the existing stream paths.

As of 0.5.1, `AdbStream.awaitWriteReady(timeout, unit)` lets a stream's sole writer
wait for WRTE credit without consuming it or choosing a payload in advance. The
controller uses this to expire queued operations after a delayed OKAY. Existing
write/chunking/USB behavior is retained. Loopback tests cover no-credit timeout,
non-consuming readiness, delayed OKAY, peer close and interruption. Readiness is
not a deadline for the underlying socket/USB write or a multi-chunk payload that
has already started; abandoning a partially written scrcpy message is unsafe.
