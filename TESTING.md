# Validation — 2026-10-18

## Repository layout migration — basic smoke only

The Android host is now the root project (`src/`, `build.gradle`); `scrcpy/`
is a clean Git submodule pinned to `a60891aea193d92e7e5c3942700eca63f9d19a5f`
(5.0.1). The server build adapter lives in `gradle/scrcpy-server.gradle`.
All 66 application and vendored ADB source files match their pre-move SHA-256
hashes. No runtime behavior or UI was changed during this reorganization.

- Root `:assembleDebug` with `-PandroidCompileSdk=37`: PASS; 85 tasks executed.
  The application still targets Android 16 / API 36.
- Installed the resulting `build/outputs/apk/debug/ScrcpyForAndroid-debug.apk`
  over the existing controller on `emulator-5556`.
- Connected to `emulator-5558` using the existing saved wireless ADB identity,
  including the normal device-property query: PASS, no new pairing.
- Opened full-screen remote control using the bottom-right shortcut: PASS;
  the server starts, H.264 decoder reports its first rendered frame, and the
  animated target probe is visible.
- Tapped the remote video; the target probe reports actual single-pointer
  ACTION_DOWN and ACTION_UP: PASS.
- Stopped the remote session and target probe after testing.

Only these basic checks were repeated. Pairing, USB/OTG, audio, multi-touch,
clipboard, scanning, files and floating-window suites were not rerun.
Build logs, XML snapshots, screenshot and target touch logs are retained locally
in the ignored `test-results-local/reorganization/` directory.

The older results below describe the previous `:android-client` module layout.
Current task names and paths are documented in the root README.md.

## 0.2.0 UI / floating-window regression

The previous 0.1.0 encrypted identity and saved device records were retained
through APK upgrades. On the same two Android 16 emulators:

- Three primary sections and pairing/scanning subpages navigate correctly; back
  returns to the parent. The separate credential page displays the same public
  identity, and navigation does not replace credentials.
- Main UI connect and saved IP/port/paired history pass.
- Full original shell, SHA-256 file roundtrip, scanning, video, non-silent audio,
  two-finger touch, bidirectional text clipboard, rotation and remote HOME pass.
- Native injected volume down/up/hold-repeat reach the target. Fullscreen does
  not alter controller volume. Held keys are released when entering the window.
- Fullscreen → floating → fullscreen preserves the **same ScrcpySession**;
  decoded frames and audio byte counts keep growing across both handoffs.
- Floating-window touch reaches the target. Local hardware volume is not
  forwarded in floating mode. Closing the window leaves ADB connected.
- The final overlay was moved and resized through actual input swipes; its
  displayed video remained live and correctly fitted to the resized window.
- Visual checks use both 1080×2400 and a temporary 720×1280 controller display.
  The original display size is restored after testing.
- First-use notification permission was exercised through the actual Android
  permission dialog. Denial still permits the foreground floating window;
  the device page supplies fullscreen/close actions without a notification.
- 10 client unit tests pass (including hardware-key repeat/meta serialization);
  the unchanged vendored ADB suite previously passed 23 tests, with 2 physical
  device/benchmark checks skipped. Android lint has no errors.

Dedicated-emulator setup grants overlay and notification permission and marks
Android's first immersive-mode instruction as confirmed. That system instruction
otherwise owns input focus and prevents native key injection from reaching the app.
The target probe consumes volume events after logging them so the system volume
panel cannot steal focus during touch assertions. Gesture events have distinct
timestamps, reflecting actual touch input.

The UI change does not modify the ADB transport, pairing implementation, saved
identity format, sync protocol or server options. Rendering changes are limited
to changing the existing MediaCodec output Surface and transferring its owner.
OTG and OEM-specific window/permission policies still need physical-device checks.

## Original 0.1.0 baseline

Environment: Windows, Android Studio bundled JBR 25, Gradle 9.3.1 / AGP 9.1,
compileSdk 37 override, **targetSdk 36**. Two Android 16 / API 36 x86_64 emulators:
controller `emulator-5556`, target `emulator-5558`.

## Executed

| Check | Result |
| --- | --- |
| `:android-client:assembleDebug` | PASS |
| `:android-client:assembleDebugAndroidTest` | PASS |
| `:android-client:lintDebug` | PASS, no errors; remaining warnings include Android 16 target requested by the user, remote `/sdcard` path, dependency notices and localization/accessibility suggestions |
| Client protocol / parsing / scanning validation | 9 tests passed |
| Vendored ADB transport / flow control / RSA / SPAKE2 / USB simulation | 23 tests passed; 2 physical USB/benchmark tests skipped |
| `git diff --check` | PASS |
| Six-digit pairing against Android's actual pairing server | PASS |
| Deliberately incorrect pairing code | Rejected |
| Reconnect after failed pairing, with saved identity | PASS |
| TLS connection after app process restart / APK replacement | PASS, no second pairing |
| Reconnect to changed random TLS port | PASS, same saved identity |
| Traditional `adb tcpip 5555` RSA connection | PASS |
| ADB shell | Expected output verified |
| ADB sync upload + download, 262151 bytes | SHA-256 identical, test file removed |
| TCP port scan of specified target | Open ADB port found |
| H.264 MediaCodec display | At least 10 frames rendered |
| PCM audio | Playback bytes received and nonzero samples verified |
| Actual two-finger events dispatched into the host SurfaceView | Target test Activity reported ACTION_POINTER_DOWN/UP with 2 pointers |
| Text clipboard | Both target-to-controller and controller-to-target verified, including Chinese text |
| Target rotation | 720×1600 to 1600×720, at least 5 newly decoded frames after reconfiguration |
| Remote HOME key | Target resumed Activity changed |
| Main screen pairing and connection | Real UI buttons paired and connected; host/port/paired history committed atomically and verified after process exit |

Full instrumentation result:

```text
INSTRUMENTATION_RESULT: result=PASS identity, shell, files, scan, video, audio, multi-touch, clipboard, rotation, HOME
INSTRUMENTATION_CODE: -1
```

Traditional TCP and changed-port reconnect result:

```text
INSTRUMENTATION_RESULT: result=PASS connect, identity, shell, files, scan
INSTRUMENTATION_CODE: -1
```

## Remaining physical-device checks

- Android 16 ARM controller + Android 15 target.
- USB Host permission dialog, physical cable/OTG direction, unplug/replug and OEM authorization.
- Real audio quality and extended-session audio/video drift, hardware decoder performance, Wi-Fi packet loss.
- Document-provider picker interactions and large file transfer cancellation on the user's ROM.
- Media clipboard MIME synchronization is not implemented; media files can be transferred through ADB sync.

The simulated USB tests validate transport framing and authentication. They do
not validate the physical Android USB stack. Audio checks validate non-silent
data playback, not subjective listening quality or precise A/V synchronization.

See README.md for build, device setup and test commands. The test APK is
for dedicated test devices; its probe Activity and instrumentation are absent
from the deliverable controller APK.
