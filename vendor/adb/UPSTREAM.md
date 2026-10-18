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
