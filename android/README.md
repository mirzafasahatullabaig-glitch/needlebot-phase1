# NeedleBot Android app (Phase 2)

Native Android host that replaces the Python `needle_host.py` on the phone,
because **bleak has no Android backend**. See `../PHASE2_PLAN.md`.

```
ESP32-S3  --BLE-->  this app  --HTTP-->  needle --serve (localhost:8080)
```

## ⚠️ Status: UNTESTED SOURCE

This was written without an Android SDK available, so it has **never been
compiled or run**. Expect to fix build errors on first open in Android Studio.
It is a scaffold built on the verified `needle --serve` contract, not a
finished app.

## What it does

- BLE central: scans for `NeedleBot-S3`, connects, subscribes to notify,
  writes responses (same UUIDs as the ESP32 firmware).
- Each received message -> `POST localhost:8080/complete` -> parse
  `function_calls[0].name` -> intent (same logic as `needle_host.py`).
- Keyword fallback when needle is unsure (confidence < 0.70) or declines.
- Native actions: open apps (Intents), flashlight (CameraManager), web search,
  timers (Handler.postDelayed), in-session memory — no Termux:API needed.

## To build

1. Install **Android Studio** (bundles SDK + JDK + Gradle).
2. Open the `android/` folder as a project. Let Gradle sync (downloads deps).
3. Add a Gradle wrapper if missing: `gradle wrapper` (or Android Studio does it).
4. Build -> Build APK, or Run on a connected phone.

## To run (on the phone)

1. In Termux, start needle's HTTP server:
   ```
   ~/needle-android/needle --model ~/needle-android/needle3.cact \
     --tools ~/needlebot-phase1/tools.json --serve
   ```
2. Launch the NeedleBot app, grant Bluetooth permission.
3. Power the ESP32 (advertising `NeedleBot-S3`).
4. App connects; type in the ESP32 Serial Monitor; replies come back.

## Known gaps / TODO

- Facts are a hardcoded subset in `Intents.kt`; wire up the full `facts.json`
  as a bundled asset.
- No handling for `needle --serve` being down beyond keyword fallback.
- BLE reconnect-on-disconnect not implemented (Phase 1 firmware re-advertises).
- Not tested against a real device or real needle server.
