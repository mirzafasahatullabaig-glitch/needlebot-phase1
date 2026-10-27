# NeedleBot Phase 2 — Android App Design Plan

Status: proposal for review. No code yet.

## Why this exists

Phase 1 works, but has one hard limit we hit on 2026-10-28:

**`bleak` (the Python BLE library) does not support Android.** It raises
`BleakError: Unsupported platform: Android`. So the Python host
(`needle_host.py`) can run the BLE loop on a **PC**, but **not on the phone** —
even though the phone is where `needle` and the model live.

Everything else on the phone works: `needle` classifies, and all the intent
handlers (facts, math, search, memory, app-launch, timers) run correctly when
called directly. Only the BLE transport is missing on Android.

**Goal:** let the ESP32 talk to the phone directly, so the phone is the full
host (BLE + AI) with no PC in the loop.

## The key enabler

`needle --serve [--port N]` runs an HTTP server:
- `POST /complete {"input":"..."}` → returns the tool-call JSON
- `POST /reset` → new conversation

So the AI side does **not** need to be reimplemented. The phone runs
`needle --serve` once; anything that can make an HTTP call to `localhost` gets
classification. This removes the need to port needle or the model.

## What Python can and cannot do here

- **Cannot:** act as a BLE central or peripheral on Android (bleak unsupported;
  Termux's `termux-api` BLE support is partial/unreliable for GATT notify+write).
- **Can:** run `needle --serve` on the phone (it's just a local binary).

Therefore the BLE half must be **native Android** (Kotlin/Java), which has full
BLE support via `android.bluetooth.le`.

## Proposed architecture

```
  ESP32-S3  --BLE-->  Android app (Kotlin)  --HTTP-->  needle --serve (localhost:8080)
  (peripheral,        (BLE central +                   (the AI, unchanged)
   unchanged)          intent->response logic)
```

Three parts:

1. **ESP32-S3 firmware** — unchanged. Still advertises `NeedleBot-S3`, notifies
   user text, accepts response writes. Phase 1 `main.cpp` is reused as-is.

2. **Android app (new, Kotlin)** — the replacement for `needle_host.py`:
   - BLE central: scan for `NeedleBot-S3`, connect, subscribe to the notify
     characteristic, write to the write characteristic. (Same three UUIDs.)
   - On each notification: POST the text to `needle --serve`, parse the
     tool-call JSON, map the chosen tool to a response, write it back over BLE.
   - Port the intent→response logic from `needle_host.py`: the handler set
     (greeting/status/joke/math/time/date/robot-info/fact/open-app/device/
     remember/timer/search), the keyword fallback, the confidence gate, and
     the JSON files (`facts.json`, `apps.json`, `devices.json`, `tools.json`).
   - Device actions and app-launch become **native** on Android (Intents,
     CameraManager torch, AudioManager volume) — no more `am`/`termux-api`
     shelling, which removes the Termux:API dependency entirely.

3. **needle** — unchanged binary, run as `needle --serve` (the app can even
   launch it, or the user starts it in Termux).

## What carries over vs. gets rewritten

| Piece | Fate |
|---|---|
| ESP32 firmware (`main.cpp`) | **Reused unchanged** |
| needle binary + model | **Reused unchanged** (`--serve` mode) |
| `tools.json`, `facts.json`, `apps.json`, `devices.json` | **Reused** (bundled as app assets) |
| Intent logic, keyword fallback, confidence gate, handlers | **Reimplemented in Kotlin** (small, direct port) |
| `needle_host.py` BLE loop (bleak) | **Replaced** by Android BLE APIs |
| `test_host.py` | Stays as the reference spec for the Kotlin port's behavior |

## Open questions to settle before building

1. **Framework:** native Kotlin (most control, steepest), or Flutter/React
   Native (cross-platform, extra layer)? Recommendation: **native Kotlin** —
   BLE + local HTTP + Intents are all first-class, and it's one platform.
2. **Who starts `needle --serve`?** The app shells it via Termux, or the user
   runs it manually, or it's bundled? (Bundling a native binary in an APK is
   possible but fiddly.)
3. **Build environment:** is there an Android SDK / Android Studio set up? The
   current machine is Windows; an APK build needs the SDK + a JDK.
4. **Scope of v1 of the app:** start with just the BLE loop + needle HTTP +
   text responses (prove the transport), then add device actions? Or all at
   once?

## Risks / honesty

- This is a **new app in a new language**, not an edit to Phase 1. It is a
  multi-session build, not a one-shot.
- `needle --serve`'s exact request/response shape is only partly known (one
  `--help` line). Needs a real test: `curl localhost:8080/complete` on the
  phone before the app depends on it.
- BLE on Android has its own permission maze (location + nearby-devices
  runtime permissions) — standard but non-trivial.
- Bundling/launching a native ELF binary from an Android app is the least
  certain part; the fallback is "user runs `needle --serve` in Termux first".

## Suggested first step

Before any app code: **verify `needle --serve` on the phone.** Run
`needle --model needle3.cact --tools tools.json --serve` in Termux, then from
another Termux session `curl -s localhost:8080/complete -d '{"input":"tell me a joke"}'`
and capture the JSON. That confirms the HTTP contract the whole app depends on.
