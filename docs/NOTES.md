# MorphHID — Working Notes

Living document so nothing gets lost between sessions. Ordered by topic.

## Status (2026-08-30, end of session 1)

Done:
- Gradle multi-module project scaffold (app, core:profile, core:hid,
  core:control, ui:renderer).
- Profile schema (kotlinx-serialization, sealed types via "type" discriminator).
- HID descriptor compiler: keyboard / pointer / consumer / gamepad
  collections, auto report-id assignment, subclass byte, fingerprint.
- Report codec: key array + rollover, modifiers, bitmaps, axes, relative
  accumulators, hat, LED output decode, releaseAll.
- ControlSession: single-actor control plane, actuate/axis/pointer/macro/
  emergency-stop, agent policy (scope + sensitive + rate limit), audit log.
- MacroRuntime: press/release/hold/tap/type/delay/repeat/run/set/haptic/page,
  jitter, shift management, NonCancellable stuck-key release.
- ProfileValidator: refs, usage names, typability, cycles, sizes.
- Compose renderer: button/toggle/joystick/dpad/slider/pointerPad/keyGrid/
  led/label, pager + grid, theme colors.
- App: BluetoothHidDevice transport, FGS with disconnect action, import
  (SAF), pairing (bonded hosts), runtime screen, sample profiles.
- JVM tests: descriptor golden, codec, macro timing, control session,
  validator.

Session 4 (on-device): Windows shows phone as 'PC' (CoD is OS-level, cosmetic —
see notes) and Android host ignored pointer/clicks — fixed by restructuring
the pointer descriptor (physical collection only when buttons present;
samples now declare 3 buttons + wheel for full mouse compatibility).

Session 3 (on-device): keyboard works to Android host. Pointer pad typed
garbage -> gamepad hat encoder OR-vs-mask bug + macro interleaving race,
both fixed. Windows pairing flow clarified (host-initiated add-device;
transport auto-accepts bonded hosts). New keyboard+mouse sample profile.

Session 2 (on-device bug fixes): back navigation exited the app -> added
BackHandler; 'keyboard.shift' was unknown -> added shift/ctrl/alt/gui/win
aliases mapping to the left-hand modifiers; connect failed after profile
switch -> transport lifecycle rework (proxy kept alive, settle phases,
target-matched callbacks, same-fingerprint no-op, auto-reconnect).

Build verified: :core:hid:test :core:control:test all green (34 tests), :app:assembleDebug produces pp/build/outputs/apk/debug/app-debug.apk (~23 MB).

Not done yet (next up):
- AppFunctions adapter (Android 16+) — see "Agent adapters" below.
- AIDL in-app API for other Android apps.
- MCP server (localhost TCP + adb reverse) for desktop agents.
- Room/DataStore persistence for profiles, grants, audit.
- Real-device Bluetooth validation (the big unknown — see below).
- Gamepad rumble (host output report → haptics).

## Critical knowledge / gotchas

### BluetoothHidDevice
- **Windows classing the phone as a "PC": Windows reads the Bluetooth
  Class-of-Device advertised by the phone (set by the OS, apps cannot change
  it) and shows a generic device icon — the SDP HID record our
  registerApp() creates is only visible to hosts that enumerate HID
  services. The visual category is cosmetic; the test is whether Device
  Manager gains HID keyboard/mouse entries after connecting. If Windows
  offers no keyboard/mouse services, the likely cause is that registration
  was not active at discovery time — activate the profile BEFORE "Add
  device", and wait for the status card to say "Registered".** If the phone
  was previously paired with a different profile, remove it from Windows
  first (cached SDP/descriptor).
- **Pointer descriptor fix: a buttonless pointer previously still wrapped
  its fields in a Pointer PHYSICAL collection with no parent usage — some
  hosts reject that. The compiler now emits the physical collection only
  when buttons exist (canonical mouse form), otherwise a flat
  Application collection.**
- **Windows pairing flow (learned on-device): Windows does NOT accept
  phone-initiated `hidDevice.connect()`. The working flow is the reverse:
  activate a profile in MorphHID first, then on Windows go to
  Bluetooth → "Add device" — Windows discovers the phone and connects TO it.
  The transport now auto-accepts CONNECTED events from any bonded host.
  Phone-initiated connect() is still used for Android hosts.** "Displays as a
  phone" in Windows settings is normal — check Device Manager for the HID
  keyboard/mouse entries.
- **Report encoder bug found on-device: gamepad hat encoding used OR instead
  of masking the nibble, which corrupted neighboring absolute-axis bytes.
  This is why the pointer pad "typed characters" — the host parsed corrupted
  gamepad reports. Fixed with `(b and 0xF0) or hat`.**
- **UI macros now serialize (semaphore) so a "type text" binding can never
  interleave with another binding's key events.**
- PointerPad tap-click rewritten with a single awaitEachGesture handler
  (drag accumulates; tap only when total movement < touch slop). The old
  chained detectDragGestures + detectTapGestures did not produce taps.
- **Profile-swap lifecycle (learned from on-device testing, Android 12 & 16):
  closing the profile proxy and re-requesting it during a profile switch is
  RACY — the service re-bind can silently fail, after which neither
  registerApp nor connect work. The transport now obtains the proxy ONCE and
  keeps it for the app's lifetime. Swapping = disconnect -> unregisterApp
  (wait for callback) -> settle 500ms -> registerApp -> wait for callback.**
- Stale onConnectionStateChanged(DISCONNECTED) events from a previous host
  can fail a pending connect — callbacks are now matched against the
  connect/disconnect target address.
- Re-activating the same profile (same fingerprint) is a no-op that keeps
  the connection alive. Activating a different profile auto-reconnects to
  the last host after ~800ms (best effort).
- If a host rejects the phone after a descriptor change (cached layout),
  the user must remove the phone from the host's Bluetooth devices and
  re-pair. The pairing screen now says this explicitly.
- Public since API 28. Constants: SUBCLASS1_KEYBOARD=0x40,
  SUBCLASS2_MOUSE=0x80, SUBCLASS3_COMBO=0xC0. We encode these directly.
- `registerApp(sdp, inQos, outQos, executor, callback)` is async; success
  arrives via `onAppStatusChanged(pluggedDevice, registered=true)`.
  We bridge with CompletableDeferreds (proxy connect and registration are
  two separate waits — don't merge them).
- `sendReport(host, id, data)`: data EXCLUDES the report id. There is also
  `sendReport(host, data)` where data[0] is the id. Some OEM stacks behave
  differently; if reports don't arrive on a specific phone, try the 2-arg
  form with the id prefixed. Track in device matrix.
- Only ONE app can be registered as HID device at a time system-wide.
  Handle "registration failed" gracefully (another keyboard app installed?).
- Host output reports (keyboard LEDs) should arrive via
  `onInterruptData(device, reportId, data)` — VERIFY on real hardware.
  If LEDs don't work, check whether SET_REPORT over the control channel is
  surfaced differently (onGetReport/onSetReport callbacks may be needed).
- Pairing flow that works: bond phone↔host in Android BT settings first,
  then `hidDevice.connect(bondedDevice)`.
- Some OEM Bluetooth stacks are broken for HID-device role. Ship a
  capability probe + device compatibility matrix.

### Host-side caching
- Hosts cache the HID descriptor per bonded device. Changing profiles =
  changing the descriptor → the host may still show the OLD device until
  re-paired. The profile fingerprint exists to detect this and tell the
  user "re-pair on the host to refresh the device layout".

### HID report details
- Boot keyboard report: [modifiers, reserved, k1..k6]. More than 6 keys =
  ErrorRollOver (0x01) in all slots.
- Modifier usages are 0xE0..0xE7 (keyboard page); item encoding uses 2-byte
  values for usages > 127 (little-endian) — hosts parse this fine.
- Consumer usage bitmap spans usageMin..usageMax, so sparse usage sets
  waste bits (accepted; keeps descriptors simple and host-compatible).
- Hat switch: logical 0..7 (0=N clockwise), 4 bits + 4 padding bits;
  neutral = 8 (null state flag 0x40 in the Input item).
- Report payload cap: 47 bytes (HID interrupt L2CAP frame) enforced by the
  compiler.

### Text typing
- v1 is US layout only; `TextLayout` maps chars → (key, shift).
- CJK/emoji cannot be typed as raw HID keys. v2 options: Win+. emoji panel
  macros, Alt codes, clipboard injection (needs host-side helper).

### Macro semantics
- Default tap hold = 15 ms (shorter risks host auto-repeat firing).
- Type gap default = 45 ms; jitter is uniform [0, jitterMs] per event.
- UI bindings are compiled to ad-hoc macro steps and run through the same
  engine (ControlSession.runAdhoc) so audit/stuck-key/rate-limit logic is
  shared.
- Macro `Run` steps are flattened at invocation with cycle detection and a
  10k-step cap.

## Agent adapters (Phase 3)

AppFunctions (Android 16+, `androidx.appfunctions`):
- Profiles are dynamic but AppFunctions are compile-time annotated → expose
  STABLE generic functions: list_profiles, activate_profile, get_status,
  list_controls, set_control, press_key, run_macro, release_all,
  emergency_stop. The active profile's control catalog is returned by
  describe_controls at call time.
- Requires compileSdk 36 and KSP; currently we're at 35 — bump when adding.
- As of mid-2026 Gemini↔AppFunctions is private preview; library is usable.

MCP server (desktop agents like Claude):
- Plan: tiny JSON-RPC over TCP on 127.0.0.1 (port 8765), reachable via
  `adb reverse tcp:8765 tcp:8765`. Later: official MCP SDK (Streamable HTTP).
- Auth: 6-digit code shown in app; localhost-only by default; LAN opt-in.
- Actor kind "mcp"; same AccessPolicy + audit as everything else.

AIDL (other Android apps):
- IControlPlane.aidl with custom dangerous permission
  `dev.morphhid.permission.CONTROL_HID` + per-app user allowlist.

## Safety decisions made
- Agents default INVOKE_ONLY (can run macros, not raw controls).
- Sensitive controls (e.g. winKey) are denied to agents outright in v1;
  interactive confirmation flow is future work.
- Emergency stop is always allowed for any actor, cancels macros, releases
  all controls, and disconnects.
- Continuous streams (axes, pointer) bypass the rate meter but still pass
  scope checks.
- Macro keystroke content is NOT audited (privacy); macro id + result is.

## TODO backlog (unordered)
- Device matrix page in README (phones × hosts).
- Auto-reconnect with exponential backoff when transport drops.
- Quick Settings tile to toggle/disconnect.
- Boot-protocol handling (`onSetProtocol`) for BIOS/pre-boot keyboards.
- Gamepad rumble output report → phone vibration.
- Config schema docs page generated from the Kotlin types.
- Config signing (ed25519) + "trusted pack" concept.
- Room persistence + DataStore settings; audit export.
- Visual profile editor on-device.
- BLE HOGP transport for hosts without BR/EDR.
- Free-form layout (percentage coordinates) for widgets.
- Multi-touch joystick + simultaneous buttons (verify Compose input).
- Landscape/one-handed layouts per screen.
- i18n: profile strings with locale maps; app UI strings.
- CI: GitHub Actions with JVM tests first, emulator later.
- `tools:cli` module for `morphhid validate|compile` (pure JVM, exists as
  core:control validator + core:hid compiler; just needs a CLI wrapper).

## Build environment (this machine)
- JDK 21 at `C:\Program Files\Android\Android Studio\jbr`.
- Android SDK at `C:\Users\shime\AppData\Local\Android\Sdk`.
- No system Gradle; wrapper distribution downloaded to
  `%TEMP%\morphhid-nettest\gradle.zip` during setup (see BUILDING.md for
  the offline bootstrap used here).