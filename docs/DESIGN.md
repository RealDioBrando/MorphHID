# MorphHID — Design

MorphHID turns an Android phone into a **shape-shifting Bluetooth HID device**.
It does not emulate one fixed device type; it compiles an imported **profile**
(JSON) into a real HID report descriptor, registers it with the OS Bluetooth
stack, and renders a matching control UI. Both humans and agents drive the
same control plane.

## Architecture

```
Profile (JSON)
   │  parse + validate + compile
   ▼
CompiledHid ──── descriptor bytes ──▶ BluetoothHidDevice.registerApp() ──▶ host
   │  ReportModel / ControlCatalog                    (public API, since Android 9)
   ▼
ControlSession  (single actor, mutex-serialized)
   │  ReportCodec (state → report bytes → sendReport)
   ├── MacroRuntime   (sequences, timing, jitter, stuck-key protection)
   ├── UI             (Compose renderer: widgets → onKey/onAxis/onBinding)
   └── Agents         (planned: AppFunctions, AIDL, MCP — same interface)
```

### Modules

| Module | Kind | Contents |
|---|---|---|
| `:core:profile` | JVM | kotlinx-serialization schema of profiles |
| `:core:hid` | JVM | usage tables, descriptor compiler, report codec |
| `:core:control` | JVM | ControlSession, MacroRuntime, AccessPolicy, audit, validator |
| `:ui:renderer` | Android | Compose widget toolkit, ProfileRenderer |
| `:app` | Android | Bluetooth transport, FGS, import/pairing/runtime screens |

### HID engine

- **Descriptor compiler**: profile collections → HID 1.11 item stream.
  Supported collections: `keyboard` (boot-compatible 6KRO + modifiers + LEDs),
  `pointer` (buttons + relative x/y/wheel), `consumer` (usage bitmap),
  `gamepad` (buttons + 4-bit hat + absolute axes).
- **Report codec**: maintains per-control state, serializes *dirty* reports
  only, accumulates relative deltas, handles key-array slots (with
  ErrorRollOver above the rollover limit), decodes host output reports
  (keyboard LEDs) back into control states.
- **Transport**: `android.bluetooth.BluetoothHidDevice` (API 28+). The SDP
  subclass byte + raw descriptor define what the host sees. Input payload
  excludes the report-id byte (`sendReport(host, id, data)`).

### Macro engine

- Steps: `press`, `release`, `hold`, `tap`, `type`, `delay`, `repeat`,
  `run` (nested, cycle-checked), `set`, `haptic`, `page`.
- Timing: each key event is a separate report; default tap hold 15 ms,
  type gap 45 ms, optional jitter (uniform, per event). Shift is held only
  while needed while typing uppercase/symbol runs.
- Policies: `RESTART`, `IGNORE`, `QUEUE`, `PARALLEL`.
- **Stuck-key protection**: every key a run pressed is released in a
  `NonCancellable` finally block, including on emergency stop.
- Text is US-layout only in v1 (see NOTES).

### Control plane

`ControlSession` is the single funnel for human UI, macros and (future)
agent adapters:

- `actuate(actor, controlId, pressed)` — keys/buttons/consumer bits
- `setAxisNormalized`, `movePointer`, `setHat` — continuous controls
- `runMacro`, `runAdhoc` — sequences (UI bindings become ad-hoc macros)
- `releaseAll`, `emergencyStop` — neutral state + disconnect
- every call is policy-checked (agent scope, sensitive controls, rate
  limit) and audited.

### Security model

- Humans: always allowed.
- Agents: gated by profile `agent.defaultScope`
  (`READ_ONLY` / `INVOKE_ONLY` / `FULL`), sensitive-control deny-list, and a
  token-bucket rate limit (discrete actions only; continuous axis/pointer
  streams are exempt).
- Emergency stop: cancels all macro runs, releases all controls, disconnects
  (notification action available from the foreground service).

## Profile format (summary)

```json
{
  "schemaVersion": 1,
  "device": {
    "name": "Deck Mini",
    "hid": {
      "subclass": "combo",
      "collections": [
        { "type": "keyboard", "reportId": 1 },
        { "type": "consumer", "reportId": 2, "usages": ["playPause", "mute"] },
        { "type": "pointer", "reportId": 3, "buttons": 2, "relativeAxes": ["x", "y"] }
      ]
    }
  },
  "ui": {
    "screens": [{
      "id": "deck", "layout": { "type": "grid", "columns": 3 },
      "widgets": [
        { "type": "button", "id": "apple", "label": "type apple",
          "onTap": { "type": "macro", "macro": "typeApple" } }
      ]
    }]
  },
  "macros": {
    "typeApple": { "steps": [ { "type": "type", "text": "apple", "keyDelayMs": 45, "jitterMs": 20 } ] }
  },
  "agent": { "defaultScope": "INVOKE_ONLY", "sensitiveControls": ["keyboard.winKey"] }
}
```

Widget types: `button`, `toggle`, `joystick`, `dpad`, `slider`,
`pointerPad`, `keyGrid`, `led`, `label`. Bindings: `key`, `combo`,
`macro`, `text`, `page`.

See `app/src/main/assets/samples/` for complete examples.