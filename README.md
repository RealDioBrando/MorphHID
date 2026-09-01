# MorphHID

Turn an Android phone into a **shape-shifting Bluetooth HID device**.
Import a JSON profile that declares which HID device(s) to emulate
(keyboard, mouse, consumer/media, gamepad — any combo), what the on-screen
control UI looks like, and what macros/sequences the controls run. Both
humans and agents drive the same audited control plane.

> Status: **Working vertical slice with signed releases.** Core engine (descriptor compiler,
> report codec, macro engine, control session, UI renderer) is implemented
> with JVM tests. Universal HID registration, on-device Bluetooth validation, and agent adapters
> (AppFunctions/AIDL/MCP) are next — see `docs/NOTES.md`.

## Building

Open the project in Android Studio, or build from the command line:

```bash
./gradlew :core:hid:test :core:control:test   # JVM unit tests
./gradlew :app:assembleDebug                  # debug APK
```

Requirements: JDK 17+, Android SDK (compileSdk 35, minSdk 28).

## Using

1. Import a profile (JSON) via **Import profile**, or start from the bundled
   samples (Deck Mini, Basic Keyboard, Presenter, Laptop 75, ThinkPad TrackPoint) — they're copied into the
   app on first launch.
2. **Activate** a profile: the app registers one universal HID device once;
   profiles then only swap the local UI and control mapping.
3. Pair the phone with the host computer (Android Bluetooth settings), then
   use **Connect host** to pick the bonded host and connect.
4. The runtime screen renders the profile's controls. Swipe between screens.

## Profiles

A profile declares:

- `device.hid.collections` — the HID device identity (compiled into a real
  HID report descriptor): `keyboard`, `pointer`, `consumer`, `gamepad`.
- `ui.screens[].widgets` — the control UI: `button`, `toggle`, `joystick`,
  `dpad`, `slider`, `pointerPad`, `trackPoint`, `keyGrid`, `led`, `label`.
- `macros` — sequences with timing and jitter (`type`, `hold`, `tap`,
  `delay`, `repeat`, `run`, `set`, `haptic`, `page`).
- `agent` — what agents may do (scope, sensitive controls, rate limit).

Example — press one button, type "apple" with human-like pacing:

```json
"macros": {
  "typeApple": {
    "steps": [ { "type": "type", "text": "apple", "keyDelayMs": 45, "jitterMs": 20 } ]
  }
}
```

Full examples live in `app/src/main/assets/samples/`.

## Modules

- `:core:profile` — profile schema (kotlinx-serialization)
- `:core:hid` — HID usage tables, descriptor compiler, report codec
- `:core:control` — ControlSession, MacroRuntime, access policy, audit, validator
- `:ui:renderer` — Compose widget toolkit
- `:app` — Bluetooth transport, foreground service, screens

Design docs: `docs/DESIGN.md` · Working notes & TODOs: `docs/NOTES.md`

## Known limitations (v1)

- Text typing uses the US keyboard layout only.
- Game consoles (Xbox/PlayStation) don't accept generic HID controllers.
- Custom profiles are limited to controls present in the universal HID descriptor (keyboard, pointer, and a fixed consumer set).
- Some OEM Bluetooth stacks don't implement the HID-device role correctly.

## License

GPL-3.0 (see LICENSE).
