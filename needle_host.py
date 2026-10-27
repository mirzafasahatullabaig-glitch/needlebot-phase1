"""NeedleBot Phase 1 host: bridge ESP32-S3 (BLE) <-> Needle 3 intent router.

Listens for text notifications from the ESP32, classifies each with Needle 3
(used strictly as an intent classifier, not for free-form synthesis), and writes
the matching template response back over BLE.
"""

import asyncio
import json
import subprocess

from bleak import BleakClient, BleakScanner

DEVICE_NAME = "NeedleBot-S3"
SERVICE_UUID = "4fa86970-43ac-11ed-b878-0242ac120002"
NOTIFY_CHAR_UUID = "4fa86c40-43ac-11ed-b878-0242ac120002"  # ESP32 -> us (notify)
WRITE_CHAR_UUID = "4fa86dae-43ac-11ed-b878-0242ac120002"   # us -> ESP32 (write)

INTENT_TEMPLATES = {
    "INTENT_GREETING": "Hello! NeedleBot online and ready.",
    "INTENT_STATUS": "All systems nominal. Running Phase 1 BLE text loop.",
    "INTENT_JOKE": "Why did the robot cross the road? It was programmed by a chicken.",
}
FALLBACK = "Sorry, I didn't catch that."

VALID_INTENTS = ",".join(INTENT_TEMPLATES)


def _keyword_intent(text: str) -> str:
    """Offline fallback classifier for hosts without needle3 (e.g. PC testing)."""
    t = text.lower()
    if "joke" in t:
        return "INTENT_JOKE"
    if "status" in t or "how are you" in t:
        return "INTENT_STATUS"
    if any(w in t for w in ("hi", "hello", "hey", "greet")):
        return "INTENT_GREETING"
    return ""


def _match_intent(out: str) -> str:
    """Pick the intent code from needle3 stdout, scanning last line first so an
    echoed prompt (which lists every intent name) can't win over the real answer."""
    for line in reversed(out.splitlines()):
        for intent in INTENT_TEMPLATES:
            if intent in line:
                return intent
    return ""


def run_needle3_intent(user_prompt: str) -> str:
    """Return a response string. Uses needle3 when present; otherwise falls back
    to a built-in keyword classifier so the BLE loop is testable without it."""
    prompt = f"Classify intent ({VALID_INTENTS}): {user_prompt}"
    try:
        result = subprocess.run(
            ["needle3", "--model", "needle3.cact", "--prompt", prompt, "--forced"],
            capture_output=True,
            text=True,
            timeout=30,
        )
        intent = _match_intent(result.stdout.strip())
        if not intent:
            print(f"[needle3] no intent matched in: {result.stdout.strip()!r}")
    except FileNotFoundError:
        print("[needle3] binary not found, using keyword fallback (PC test mode)")
        intent = _keyword_intent(user_prompt)
    except subprocess.TimeoutExpired:
        print("[needle3] timed out")
        intent = ""

    return INTENT_TEMPLATES.get(intent, FALLBACK)


async def main() -> None:
    print(f"Scanning for {DEVICE_NAME}...")
    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=20.0)
    if device is None:
        print(f"Device {DEVICE_NAME} not found.")
        return

    async with BleakClient(device) as client:
        print(f"Connected to {device.address}")

        async def on_notify(_char, data: bytearray) -> None:
            try:
                payload = json.loads(data.decode("utf-8"))
                text = payload.get("text", "")
            except (UnicodeDecodeError, json.JSONDecodeError):
                print(f"[RX] non-JSON payload: {data!r}")
                return
            print(f"[RX text]: {text}")
            response = run_needle3_intent(text)
            print(f"[TX response]: {response}")
            await client.write_gatt_char(
                WRITE_CHAR_UUID, response.encode("utf-8"), response=False
            )

        await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
        print("Listening. Type in the ESP32 Serial Monitor. Ctrl-C to quit.")
        try:
            while client.is_connected:
                await asyncio.sleep(1)
        finally:
            await client.stop_notify(NOTIFY_CHAR_UUID)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nBye.")
