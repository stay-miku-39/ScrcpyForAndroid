# Validation history

## 0.6.1-dev settings, diagnostics, quality and touch continuity — 2026-10-21

The six requested changes were implemented and tested one at a time, followed
by the final focused regression below. The whole settings page now requires
**Save all settings**; leaving discards unsaved drafts. Startup phases are
separate in the UI, detailed diagnostics are opt-in, fullscreen quality metrics
collect only selected items, local codec capabilities annotate formats and
initialize fresh-install defaults, and stale touch MOVE no longer cancels other
held fingers. The pinned server and vendored ADB sources are unchanged.

- `:assembleDebug`, `:assembleDebugAndroidTest`: PASS, versionCode 9,
  versionName `0.6.1-dev`, compile/target API 36, min API 30.
- `:testDebugUnitTest`: 62 PASS. The 18 additional tests cover startup timing,
  disabled/selective metric paths, local codec policy, and touch queue/order
  behavior. Fifteen control/input tests include a five-pointer slow-consumer
  fixture with 250,000 MOVE operations across 500 gestures.
- Before modifying touch production code, three deterministic tests reproduced
  the old failures: stale MOVE cancelled held fingers, an unsent stale DOWN
  cancelled another hold, and a delayed UP released other pointers. All three
  pass with the new policy. Critical-edge overflow still safely resets input;
  ordinary key expiry keeps its existing safety behavior.
- `:adb-library:testDebugUnitTest`: 29 PASS / 2 physical USB checks SKIP.
- `:lintDebug`: PASS, 0 errors / 32 warnings; repeated after final test-fixture
  corrections. Fullscreen intentionally keeps the platform TextView/theme;
  Material/AppCompat remains scoped to floating controls.
- APK signature verification PASS. Certificate SHA-256 matches the preserved
  previous APK; replacement installs succeeded without clearing data, identity
  or pairing records. The production manifest excludes instrumentation and probe
  activities. Deliverable: `dist/ScrcpyForAndroid-0.6.1-dev-debug.apk`, with its
  SHA-256 recorded in `dist/SHA256SUMS.txt`; older artifacts are retained.

Final integration used two explicitly addressed Android 16 / API 36 x86_64
emulators, with the existing target logical-size override restored exactly.
No physical user device was operated for this development regression.

| Final check | Actual result |
| --- | --- |
| `featureCase draft` | PASS: stream/background/appearance drafts do not write before Save; navigation and Home discard them; reentry reads saved preferences; explicit Save persists the whole page |
| `featureCase logging` | PASS: unsaved logging switch has no effect; Save enables actual background append; Save off suppresses ordinary/error diagnostics; preference reload preserves the switch |
| `featureCase codecDiscovery` | PASS: actual local MediaCodec directory and hardware labels; fresh-install initialization against isolated preference files; upgrades/existing/manual choices retained; capability refresh preserves an unsaved selection |
| `mediaCase stages` | PASS: current-run upload through first-frame phases each have successful duration records and safe startup parameters; real video plays; identity and target size retained/restored |
| `mediaCase quality` | PASS: off means no optional statistics; video-rate-only excludes other counters; selected Opus/audio/video/local-delay/RTT samples appear; transparent top-left text passes touches to the remote app; font color/alpha require Save; background/floating stop collection; fullscreen restoration reuses the same session; disabling removes the frame-callback worker |
| `uiStage settings` | PASS: actual overflow/back navigation, 20% minimum, manual Save, ball/main/power menu alpha, active floating alpha only after Save, default reset and same-session fullscreen restore; identity/history unchanged |
| `mediaCase main` | PASS: saving dimensions leaves the active session unchanged, reopening applies them; proportional fullscreen/floating fit, touch and non-silent PCM; close restores target size and retains ADB |
| `mediaCase virtual` | PASS: INFO-level server output still supplies the virtual display ID with debug off; independent dimensions, audio/touch and same-session handoff; original size obeys the preferred route; virtual display removed on close |
| `mediaCase fallback` | PASS: unavailable virtual app falls back to the main route; unsupported named codec selection falls back to playable H.264/PCM |
| `defaultMedia true`, `windowUi true` | PASS: persistent identity, Shell, file SHA-256 roundtrip, scan, video/non-silent audio, two-pointer input, bidirectional clipboard, one rotation, HOME, physical volume down/up/repeat, floating touch and same-session roundtrip; closing remote retains ADB |
| `mediaCase touchHold` with a test-only delayed-ACK proxy | PASS: exactly one control-stream OKAY delayed by 700 ms after the second finger DOWN; both fingers stay held, then move/release independently, and the session remains live; the proxy stopped afterwards |

Every passing instrumentation row returned `result=PASS` and final
`INSTRUMENTATION_CODE: -1`. Settings and fullscreen quality screenshots were
inspected. Two final-fixture failures were corrected and rerun: the touch-through
point originally mapped to the target status bar; a saved large floating window
covered the injected settings-menu tap. These failures are not counted as PASS.
Temporary preferences, target dimensions and test components were restored or
stopped, and the task's temporary port forward was removed.

Metric boundaries are explicit: Surface callbacks are not optical display FPS;
media packets/rates exclude ADB/IP overhead; video delay includes local decoder
queueing, audio processing includes decoder/output waiting, control rates count
actual writes, and ADB round-trip includes Shell startup. The server does not
report encoder duration, so that value is unavailable. Compressed-audio input
and output PTS are not assumed identical for latency matching.

Fresh-install recommendation is verified using the production initializer with
isolated preferences rather than uninstalling/resetting the existing controller.
Actual OEM codec/hardware variation, game touch feel, sustained weak Wi-Fi,
long-duration power/thermal behavior and end-to-end optical/audio latency remain
unverified. Full codec enumeration/playback combinations, four-way rotation,
new PIN pairing, physical OTG, microphone and unrelated power/window suites were
not rerun. The prior four-way rotation limitation below is not declared fixed.

## 0.6.0-dev resolution and media settings — 2026-10-21

The settings page now saves a global startup snapshot: logical display size,
preferred main-screen/independent virtual-display route, encoded-video maximum
edge, video/audio formats, bit rates, frame-rate limit and optional named codec
components. Existing sessions and Surface handoffs keep their original snapshot.
Main-screen overrides retain their prior override/reset value for restoration;
startup fallback is bounded and does not become automatic reconnect.

- `:assembleDebug`, `:assembleDebugAndroidTest`: PASS, versionCode 8,
  versionName `0.6.0-dev`, compile/target API 36.
- `:testDebugUnitTest`: 44 PASS. Ten new tests cover immutable settings,
  independent logical/encoded dimensions, local-aspect presets, parameter and
  shell-fragment rejection, prior override preservation, virtual-display rotation
  selection, pinned codec IDs/component matching and bounded route failure policy.
- `:lintDebug`: PASS, 0 errors / 28 warnings.
- Debug APK signature verification PASS; the signing-certificate SHA-256 matches
  the preserved 0.5.1 APK. The deliverable is `dist/ScrcpyForAndroid-0.6.0-dev-debug.apk`
  with its SHA-256 appended to `dist/SHA256SUMS.txt`; previous APKs are retained.
- The scrcpy submodule remains pinned and unmodified at 5.0.1. No vendored ADB
  transport changes were made for this feature; its separate tests were not rerun.

Integration used two explicitly addressed Android 16 / API 36 x86_64 emulators
as controller and target. The target already had a reduced logical-size override;
tests restore that exact prior value rather than resetting to physical pixels.
Test installations use replacement with application data and RSA identity kept.

| Check | Actual result |
| --- | --- |
| `mediaCase main` | PASS: real settings page saves custom size; live session and target size remain unchanged; reopening applies saved dimensions; fullscreen/floating fit is maximal and proportional; actual touch, non-silent PCM and same-session handoff work; close restores prior target size and preserves ADB |
| `mediaCase virtual` | PASS: independent display ID and requested dimensions, target main-screen size unchanged, actual touch and non-silent audio, fullscreen/floating same-session handoff, display removal on close; original-size selection still obeys virtual preference |
| `mediaCase fallback` | PASS: unavailable virtual-start application falls back to main-screen route; nonexistent encoder/decoder selections fall back to H.264/PCM with playable non-silent audio |
| `mediaCase presets` | PASS: original dimensions with unlimited video, local controller dimensions with a lower encoded edge cap, and controller-aspect preset on a virtual display without a video edge cap; actual dimensions and proportional fullscreen fit asserted |
| `mediaCase codecs` | PASS: H.264 baseline plus actual H.265, AV1, VP8 and VP9 video decoding; AAC, Opus and FLAC decode to non-silent PCM; explicit Android software AVC/AAC encoder/decoder component names work |
| `defaultMedia true`, `windowUi true` | PASS: saved RSA identity, Shell, file SHA-256 roundtrip, scan, video/non-silent audio, two-pointer input, bidirectional text clipboard, one rotation, HOME, physical volume down/up/repeat, floating touch and same-session roundtrip; closing remote retains ADB; temporary default media configuration is restored |

All PASS integration rows returned both `INSTRUMENTATION_RESULT: result=PASS ...`
and final `INSTRUMENTATION_CODE: -1`. Actual screenshots of settings, fullscreen
and floating main/virtual output were inspected. Early compile/test-harness
failures were repaired and rerun; they are not counted as passing runs.
Codec and default-basic integration were repeated after the final bounded audio
decoder/output-loop change, and both passed. The production manifest was checked
to exclude the instrumentation runner and probe activities.

The opposite display-failure direction and the both-unsupported-to-native path
are covered by deterministic unit tests of the production fallback policy;
these emulators do not provide an actual OEM rejection of main-screen resizing
or virtual-display creation. Physical devices, hardware codec variation,
long-duration power/thermal behavior, subjective codec quality/latency and
disconnection/force-stop recovery of a persisted size lease remain unverified.
The existing four-way rotation issue below is not declared fixed. Pairing/PIN,
physical OTG, microphone routing, power-menu and unrelated UI suites were not
rerun for this feature. There is still no independent audio-clock drift correction.

## 0.5.1 bounded control freshness and decoded-frame catch-up — 2026-10-20

Investigation confirmed a 256-entry FIFO with no expiry: a failed offer ended
the entire session. The writer also removed a packet before waiting for ADB's
previous WRTE acknowledgement. Video output rendered every decoded buffer.

The replacement control queue has a 64-entry / 512 KiB bound and a 250 ms
pre-send deadline, coalesces pending MOVE per pointer without crossing DOWN/UP,
and evicts oldest work instead of ending the session. Selection/expiry occurs
after WRTE credit is available. Dropped input schedules release of transmitted
touches and keys; orphan MOVE/repeat/UP is suppressed until a fresh DOWN. A
cancelled hold requires lifting/repressing. Close discards queued actions and
briefly attempts only releases. Video retains the H.264 reference chain, drops
older ready decoded outputs, and presents the latest available output within a
bounded pass. The pinned scrcpy submodule is unchanged.

- `:testDebugUnitTest`: 34 PASS, including 9 queue/input-state and 4 video-drain
  tests. The five-pointer slow-consumer fixture submits 250,000 MOVE operations
  across 500 gestures, simulates ACK outages with a deterministic clock, checks
  the queue bound and independently verifies no orphan/stuck transmitted touches.
  Expired UP and mixed touch/chord releases, reverse key release order, repeat
  filtering, clipboard byte limits, pointer reuse and close discard are covered.
- `:adb-library:testDebugUnitTest`: 29 PASS; 2 physical USB checks SKIP.
  Four new real loopback TCP tests cover non-consuming WRTE readiness, no-credit
  timeout with no writes, delayed OKAY, peer close and interruption. Existing
  chunking, checksum, flow control, EOF, authentication and simulated USB tests
  were also run. Loopback readiness tests do not establish physical OTG behavior.
- `:assembleDebug`, `:assembleDebugAndroidTest`: PASS, compile/target API 36.
- `:lintDebug`: PASS, 0 errors / 27 warnings.
- Debug APK signing-certificate SHA-256 matches the preserved 0.5.0 APK;
  signature verification PASS. No installation/data reset was performed.

These are JVM/loopback and build checks. No Android device instrumentation,
actual multi-finger weak-Wi-Fi use, Surface hardware decoder/frame-drop behavior,
fullscreen/window/background handoff, power-menu behavior or latency benchmark
was run for this patch. The unresolved four-way video reconfiguration failure
recorded for 0.5.0 below remains unresolved and is not counted as retested.

The 250 ms limit applies to queued work before starting a write, not remote
execution. Already-started TCP/USB writes and partially transmitted large
clipboard messages cannot safely be abandoned. TCP retransmission and encoded
video/network backlog can still add delay; decoded-output dropping alone does
not promise low latency when sustained bandwidth is below the configured rate.
Releases also require a writable control channel. Pairing/identity formats,
media protocol, audio and device/output ownership are unchanged by this patch.

## 0.5.0 multiple ADB connections and background sessions — 2026-10-20

The controller now keeps one transport/session per endpoint, multiple independent
floating windows, and a single fullscreen Activity. Background keep-alive is on
by default; automatic fullscreen-to-window is an independent opt-in. Remote input,
name/rotation queries and microphone cleanup retain their original device binding.
The scrcpy submodule and vendored ADB sources are unchanged.

- `:assembleDebug`, `:assembleDebugAndroidTest`: PASS, compile/target API 36.
- `:testDebugUnitTest`: 21 PASS.
- `:adb-library:testDebugUnitTest`: 25 PASS; 2 physical USB checks SKIP.
- `:lintDebug`: PASS, 0 errors / 28 warnings.

Integration used three dedicated Android 16 / API 36 user-build emulators:
one controller and two explicitly addressed TCP ADB targets. The simultaneous
sessions used temporary 360×800 target displays to reduce software encoding load;
legacy window and microphone regression used the target's original 1080×2400
display (scrcpy max edge 1600). These are functional checks, not a throughput or
latency benchmark. Production video options were not reduced.

| Check | Actual result |
| --- | --- |
| `multiDevice true` | PASS: shared persistent identity, two connected ADBs, two live windows, target-isolated touch, duplicate/failed connection isolation, latest shortcut independent of tool selection, latest-disconnect fallback, one fullscreen instance, same-session background decode/restore and automatic window, live per-window close with ADB retained, per-device disconnect, keep-alive opt-out, disconnect-all exits fullscreen |
| `windowUi true` | PASS: identity, Shell, SHA-256 file roundtrip, scan, video/non-silent audio, two-pointer input, bidirectional text clipboard, single rotation, volume down/up/repeat and release, floating touch and same-session fullscreen/window handoff, close retains ADB |
| `microphoneOnly true` | PASS: deterministic PCM over the bound device's ADB, cross-UID MIC/VOICE_COMMUNICATION recording, route restoration, stalled-source stop and concurrent video. Live controller recording permission/capture was not retested |
| `uiStage rotation` | FAIL: native controller orientation follows, but video aspect sometimes does not update during the four-way sequence within the test bound |
| Unmodified 0.4.1 APK + HEAD baseline test comparison | FAIL at floating video rotation; fullscreen four-way checks reached the floating stage. Failure stages differ, so this does not establish a common root cause or exclude a 0.5.0 regression |

All rows marked PASS returned `INSTRUMENTATION_RESULT: result=PASS ...` and final
`INSTRUMENTATION_CODE: -1`. The focused four-way stage is explicitly not counted
as PASS, and its video reconfiguration issue remains unresolved. The earlier
single-rotation assertion timed out on the loaded emulator; the final tests allow
45 seconds for real media reconfiguration while retaining actual dimensions,
frames, native rotation and session identity checks. Clipboard direction changes
wait for the get/autosync deliveries to settle and for foreground window focus.

A raw controller screenshot of two simultaneous windows was inspected: both
videos, bars, rounded corners, icons and separate titles are present. Capture is
independent of the functional multi-device instrumentation; Home transitions use
the real system launcher Intent. Initial setup/fixture failures and incomplete
runs are not counted as successful validation.

Physical Android 16-to-15, mixed USB/TCP/TLS concurrency, OEM battery/overlay
policies, prolonged screen-off/Doze, hardware decoder capacity and subjective
multi-source audio quality require separate device validation. Foreground service
and wake lock do not promise survival of force-stop, process kill or network loss;
there is no automatic reconnect. Background/window microphone capture remains off.

## 0.4.1 focused floating-window refinement

Only the changed floating UI was exercised on dedicated controller and target
emulators, both Android 16. The existing ADB/control/audio/video
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

Updated requirements: [docs/requirements-and-regression.md](docs/requirements-and-regression.md).
Component/icon sources and license: [docs/ui-assets.md](docs/ui-assets.md).

## 0.4.0 power, window footer, native rotation and appearance

Implemented in order, with each focused suite passing before the next feature:
power → window footer → native orientation/remote panels → appearance settings.
All four were then exercised together with the existing regression suites.
The scrcpy submodule remains unchanged. Target stays Android 16 / API 36.

Validation used two dedicated Android 16 / API 36 user-build emulators in
controller and target roles. TCP and wireless-debugging TLS connections were
both exercised; addresses and ports must be obtained from the current devices.

| Check | Result / evidence |
| --- | --- |
| Root debug and instrumentation builds | PASS, targetSdk 36 |
| Application unit tests | 21 passed: protocol, microphone target validation, window geometry and four-way orientation parsing |
| Vendored ADB tests | 25 passed; 2 physical USB integration/throughput tests skipped because no physical USB device is attached |
| Android Lint | 0 errors, 26 warnings; no blanket suppression/baseline added |
| `uiStage power` | PASS, fullscreen short power/wake/long power/chord screenshot, floating short/long power, bounded release and actual target menu closure |
| `uiStage window` | PASS, click shrink/enlarge, both corner drags, bounds/aspect, no target touch leakage, same-session fullscreen restore |
| `uiStage rotation` | PASS, actual controller display rotation 0/90/180/270, persisted opt-out/re-enable, same session, remote notifications/quick settings/collapse, floating mode does not force controller direction |
| `uiStage settings` | PASS, real overflow-menu navigation/back, minimum and persistence, full ball/main/power menu opacity, live overlay alpha, reset, unchanged identity/history/session |
| `windowUi true` | PASS, identity, shell, SHA-256 file roundtrip, scan, video, non-silent audio, two-pointer input, bidirectional text clipboard, rotation, HOME, volume down/up/repeat, floating touch/roundtrip/close with ADB retained |
| `mainUiOnly true` | PASS, main UI connect, nested navigation, fingerprint and saved IP/port/history |
| Fresh wireless PIN + UI + TLS | PASS, obtained a new system pairing code, paired through the actual application page and connected to the advertised TLS debugging port |
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

## 0.3.0 experimental microphone forwarding

Only the new microphone checks and concurrent basic scrcpy video were exercised.
The scrcpy submodule is unchanged. Build target remains Android 16 / API 36.

- Root `:assembleDebug` and `:assembleDebugAndroidTest`: PASS.
- `:testDebugUnitTest --tests org.scrcpy.android.MicrophoneTargetTest`: 5 PASS
  (package validation, exact UID matching, system-UID exclusion, activity dump parsing).
- `microphoneOnly` instrumentation on dedicated controller and target
  emulators, both Android 16 `user` builds: PASS.
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

## Repository layout migration — basic smoke only

The Android host is now the root project (`src/`, `build.gradle`); `scrcpy/`
is a clean Git submodule pinned to `a60891aea193d92e7e5c3942700eca63f9d19a5f`
(5.0.1). The server build adapter lives in `gradle/scrcpy-server.gradle`.
All 66 application and vendored ADB source files match their pre-move SHA-256
hashes. No runtime behavior or UI was changed during this reorganization.

- Root `:assembleDebug`: PASS; 85 tasks executed.
  The application still targets Android 16 / API 36.
- Installed the resulting `build/outputs/apk/debug/ScrcpyForAndroid-debug.apk`
  over the existing controller application.
- Connected to the target using the existing saved wireless ADB identity,
  including the normal device-property query: PASS, no new pairing.
- Opened full-screen remote control using the bottom-right shortcut: PASS;
  the server starts, H.264 decoder reports its first rendered frame, and the
  animated target probe is visible.
- Tapped the remote video; the target probe reports actual single-pointer
  ACTION_DOWN and ACTION_UP: PASS.
- Stopped the remote session and target probe after testing.

Only these basic checks were repeated. Pairing, USB/OTG, audio, multi-touch,
clipboard, scanning, files and floating-window suites were not rerun.

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

Validation used two dedicated Android 16 / API 36 x86_64 emulators as controller
and target. The application uses **targetSdk 36**.

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

Historical results describe the recorded releases, not a new run against the
current build configuration. Raw logs and screenshots are task artifacts in the
ignored `test-results-local/` directory; they are not required in a fresh checkout.
