# Validation — 2026-10-19

## 0.4.1 focused floating-window refinement

Only the changed floating UI was exercised on controller `emulator-5554` and
target `emulator-5556`, both Android 16. The existing ADB/control/audio/video
implementation and scrcpy submodule were not modified. No broader pairing,
file, scan, microphone or full regression suite is claimed for this patch.

- `:assembleDebug`, `:assembleDebugAndroidTest`, `:lintDebug`: PASS.
  Lint reports 0 errors and 28 warnings (including dependency/style suggestions).
- Updated `-e uiStage window`: PASS.
- Header and footer both measure 40 dp; native rounded outline clips child bars.
- Power and fullscreen use actual vector icons on Material Components buttons;
  text labels and the stepwise + / − buttons are absent.
- Both corner hatch handles resize proportionally when dragged. Tapping either
  handle leaves geometry unchanged. Bounds and target-input isolation pass.
- The target's global device name was temporarily replaced with a long Chinese /
  English name. The overlay reads the name through ADB; the TextView keeps the
  complete value while visible text is end-ellipsized. Enlarging the actual
  window reveals more characters. Top-title dragging still moves the window.
- Actual icon power clicks sleep/wake the target; the fullscreen icon restores
  the existing session, with subsequent decoded frames and no reconnect.
- Compact/expanded screenshots were visually inspected for four clipped corners,
  equal bars, mirrored resize grips, icon placement and adaptive title truncation.
- Target device name, saved geometry and window opacity were restored. Probe and
  floating service are stopped after the test.

The Unicode fixture is encoded into an ASCII-only test setup command before
being decoded on the target. Direct non-ASCII service strings exposed an existing
vendored `AdbProtocol.generateOpen()` allocation issue during test setup; the
display-only name query itself is ASCII and reads UTF-8 output normally. This
patch does not expand scope into modifying the ADB transport. The first title
comparison also read a pre-resize layout; it now waits for the native window
layout before comparing visible character counts.

```text
INSTRUMENTATION_RESULT: result=PASS equal bars, rounded clipping, icon power/fullscreen, drag-only corners/aspect/bounds, ADB name/width ellipsis, header move, isolated input, same session
INSTRUMENTATION_CODE: -1
```

Logs/screenshots: ignored `test-results-local/window-0.4.1/`.
Updated requirements: [docs/requirements-and-regression.md](docs/requirements-and-regression.md).
Component/icon sources and license: [docs/ui-assets.md](docs/ui-assets.md).

## 0.4.0 power, window footer, native rotation and appearance

Implemented in order, with each focused suite passing before the next feature:
power → window footer → native orientation/remote panels → appearance settings.
All four were then exercised together with the existing regression suites.
The scrcpy submodule remains unchanged. Target stays Android 16 / API 36.

Current AVD roles are controller `emulator-5554` (Medium_Phone) and target
`emulator-5556` (Medium_Phone_1), both Android 16 user builds. These are the same
AVDs previously recorded below as 5556 and 5558. Current direct test endpoint is
`10.0.2.17:5555`; saved wireless-debugging TLS endpoint is `10.0.2.17:41929`.

| Check | Result / evidence |
| --- | --- |
| Root debug and instrumentation builds | PASS, API 37 compile override with targetSdk 36 |
| Application unit tests | 21 passed: protocol, microphone target validation, window geometry and four-way orientation parsing |
| Vendored ADB tests | 25 passed; 2 physical USB integration/throughput tests skipped because no physical USB device is attached |
| Android Lint | 0 errors, 26 warnings; no blanket suppression/baseline added |
| `uiStage power` | PASS, fullscreen short power/wake/long power/chord screenshot, floating short/long power, bounded release and actual target menu closure |
| `uiStage window` | PASS, click shrink/enlarge, both corner drags, bounds/aspect, no target touch leakage, same-session fullscreen restore |
| `uiStage rotation` | PASS, actual controller display rotation 0/90/180/270, persisted opt-out/re-enable, same session, remote notifications/quick settings/collapse, floating mode does not force controller direction |
| `uiStage settings` | PASS, real overflow-menu navigation/back, minimum and persistence, full ball/main/power menu opacity, live overlay alpha, reset, unchanged identity/history/session |
| `windowUi true` | PASS, identity, shell, SHA-256 file roundtrip, scan, video, non-silent audio, two-pointer input, bidirectional text clipboard, rotation, HOME, volume down/up/repeat, floating touch/roundtrip/close with ADB retained |
| `mainUiOnly true` | PASS, main UI connect, nested navigation, fingerprint and saved IP/port/history |
| Fresh wireless PIN + UI + TLS | PASS, obtained a new system pairing code, paired through the actual application page and connected to TLS port 41929 |
| New-process saved TLS reconnect | PASS, identity, shell, file roundtrip and scan; no new PIN needed |
| `microphoneOnly true` | PASS, deterministic PCM over independent ADB stream, cross-UID MIC/VOICE_COMMUNICATION input, physical-route restore, stalled-capture stop, concurrent video |

Integration results use `INSTRUMENTATION_RESULT: result=PASS ...` and final
`INSTRUMENTATION_CODE: -1`. Fullscreen and overlay screenshots were inspected.
The final overlay uses a text power button because the emulator font did not
contain the initially considered power glyph. Alpha applies to the entire overlay,
including the decoded video, while touch hit areas remain in place.

The rotation runs exposed an existing fast-shell race in vendored ADB:
an accepted OKAY followed immediately by CLSE could be mistaken for rejection.
That is corrected along with atomic concurrent stream-ID allocation. Loopback
tests cover 64 concurrent short commands, EOF, real rejection and existing flow
control. Lint also identified a missing local microphone permission check;
capture now checks permission again immediately before AudioRecord creation.
This iteration's microphone integration uses a deterministic source; live
controller permission-denial/grant/stop checks below remain the 0.3.0 evidence.

The footer test explicitly leaves the legal minimum width before asserting a
shrink, and restores saved geometry. Native rotation and popup animations settle
before real menu taps and screenshots. Power tests check actual menu visibility
and release before dismissing the target system menu; they temporarily set and
then restore `power_button_long_press`. Temporary rotation settings are restored.
Screenshot preview and screen-on/dialog animations finish before the next
independent power action; AWAKE alone is earlier than a completed display-on.
Test activities and streams are stopped after checks.

Remaining physical checks: Android 16 controller with Android 15 target, OEM
power/volume+ chord behavior, lock-screen credentials, prolonged natural sleep,
tablet/foldable orientation policy, USB/OTG hardware/permissions/power, and actual
duplex communication audio. Ordinary immersive mode still permits system edge
gestures to reveal controller bars; remote panel buttons reduce the conflict.
Media clipboard and universal camera input replacement remain unimplemented.

The 26 Lint warnings include existing dependency/target-version hints, vendored
TLS-library checks, accessibility/RTL/style suggestions and programmatic view
construction. They have not been represented as zero-warning validation.
Detailed acceptance requirements, test commands, manual cases and refactoring
invariants are in [docs/requirements-and-regression.md](docs/requirements-and-regression.md).
Local build/test logs and UI images are in ignored
`test-results-local/ui-2026-10-19/`.

## 0.3.0 experimental microphone forwarding

Only the new microphone checks and concurrent basic scrcpy video were exercised.
The scrcpy submodule is unchanged. Build target remains Android 16 / API 36.

- Root `:assembleDebug` and `:assembleDebugAndroidTest`: PASS.
- `:testDebugUnitTest --tests org.scrcpy.android.MicrophoneTargetTest`: 5 PASS
  (package validation, exact UID matching, system-UID exclusion, activity dump parsing).
- `microphoneOnly` instrumentation on controller `emulator-5556` and target
  `emulator-5558`, both Android 16 `user` builds: PASS.
- A deterministic 1 kHz PCM source used the production ADB forwarding channel.
  A separate-UID target app recorded it through normal MIC and
  VOICE_COMMUNICATION AudioRecords, both routed to device type 25.
- The initial split header/payload transport caused underruns. Combining each
  frame into one ADB WRTE fixed that avoidable extra round trip. Startup and
  encoder reconfiguration can still briefly underrun on the emulators; the
  probe allows up to three one-second samples to settle while retaining the
  tone detection threshold. This is not a call-quality or latency guarantee.
- Explicit close restored target built-in-microphone routing (type 15) and
  removed the injected test tone.
- A deliberately stalled PCM source was closed by the five-second watchdog.
- Actual full-screen UI: target package defaults to the foreground app;
  denial of the Android microphone permission keeps forwarding off and leaves
  video connected. Granting it starts the real controller AudioRecord and
  displays an ongoing target indicator.
- The menu stop action ends the real recording. After switching to Home and
  allowing the Activity pause transition to complete, RECORD_AUDIO AppOps no
  longer reports a running recording; logs confirm forwarding stopped.

Final instrumentation result:

```text
INSTRUMENTATION_RESULT: result=PASS microphone PCM over ADB, cross-UID MIC/VOICE_COMMUNICATION, route restore, capture stall stop, concurrent scrcpy video
INSTRUMENTATION_CODE: -1
```

Research, permissions, architecture and physical-device limitations are in
INPUT_SHARING.md. Android 14/15 physical devices, actual communication apps,
duplex echo/quality, background/floating capture and camera injection were not
claimed as verified. Audio is not persisted; the deterministic-source test does
not capture the real controller microphone. UI capture tests use the emulator.
Logs and UI snapshots are in the ignored `test-results-local/input-sharing/`.

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
