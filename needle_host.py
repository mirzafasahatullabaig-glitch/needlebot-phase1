"""NeedleBot Phase 1 host: bridge ESP32-S3 (BLE) <-> Needle 3 intent router.

Listens for text notifications from the ESP32, classifies each with Needle 3
(used strictly as an intent classifier, not for free-form synthesis), and writes
the matching template response back over BLE.
"""

import asyncio
import ast
import json
import operator
import re
import subprocess
from datetime import datetime
from difflib import SequenceMatcher

from bleak import BleakClient, BleakScanner

DEVICE_NAME = "NeedleBot-S3"
SERVICE_UUID = "4fa86970-43ac-11ed-b878-0242ac120002"
NOTIFY_CHAR_UUID = "4fa86c40-43ac-11ed-b878-0242ac120002"  # ESP32 -> us (notify)
WRITE_CHAR_UUID = "4fa86dae-43ac-11ed-b878-0242ac120002"   # us -> ESP32 (write)

ROBOT_NAME = "NeedleBot"
ROBOT_VERSION = "1.3.0"

# Safe arithmetic: evaluate a math AST, no builtins/names -> no code execution.
_MATH_OPS = {
    ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul,
    ast.Div: operator.truediv, ast.Pow: operator.pow, ast.Mod: operator.mod,
    ast.USub: operator.neg, ast.UAdd: operator.pos,
}


def _eval_math(node):
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return node.value
    if isinstance(node, ast.BinOp) and type(node.op) in _MATH_OPS:
        return _MATH_OPS[type(node.op)](_eval_math(node.left), _eval_math(node.right))
    if isinstance(node, ast.UnaryOp) and type(node.op) in _MATH_OPS:
        return _MATH_OPS[type(node.op)](_eval_math(node.operand))
    raise ValueError("unsupported expression")


def handle_math(text: str) -> str:
    # pull the arithmetic part; spell out common words so "2 plus 2" works
    expr = text.lower()
    for word, sym in (("plus", "+"), ("minus", "-"), ("times", "*"),
                      ("multiplied by", "*"), ("divided by", "/"), ("x", "*")):
        expr = expr.replace(word, sym)
    expr = re.sub(r"[^0-9+\-*/.()%\s]", "", expr).strip()
    if not expr:
        return FALLBACK
    try:
        result = _eval_math(ast.parse(expr, mode="eval").body)
    except (ValueError, SyntaxError, ZeroDivisionError, TypeError):
        return FALLBACK
    if isinstance(result, float) and result.is_integer():
        result = int(result)
    return f"The answer is {result}."


def handle_time(_text: str) -> str:
    return f"The time is {datetime.now().strftime('%H:%M')}."


def handle_date(_text: str) -> str:
    return f"Today is {datetime.now().strftime('%A, %B %d, %Y')}."


def handle_robot_info(text: str) -> str:
    t = text.lower()
    if "name" in t:
        return f"My name is {ROBOT_NAME}."
    if "version" in t:
        return f"I'm {ROBOT_NAME} running firmware {ROBOT_VERSION}."
    if "can you do" in t or "help" in t:
        return "I can greet you, tell a joke, do math, and give the time or date."
    return f"I'm {ROBOT_NAME}, your companion robot."


# A response is a fixed string (template) or a handler(user_text) -> str.
INTENT_TEMPLATES = {
    "INTENT_GREETING": "Hello! NeedleBot online and ready.",
    "INTENT_STATUS": "All systems nominal. Running Phase 1 BLE text loop.",
    "INTENT_JOKE": "Why did the robot cross the road? It was programmed by a chicken.",
    "INTENT_MATH": handle_math,
    "INTENT_TIME": handle_time,
    "INTENT_DATE": handle_date,
    "INTENT_ROBOT_INFO": handle_robot_info,
}
FALLBACK = "Sorry, I didn't catch that."

VALID_INTENTS = ",".join(INTENT_TEMPLATES)


def _respond(intent: str, user_text: str) -> str:
    """Resolve an intent to its response string, calling handlers with the text."""
    r = INTENT_TEMPLATES.get(intent)
    if r is None:
        return FALLBACK
    return r(user_text) if callable(r) else r

# Needle 3 eval (needle3.cact, Android/Termux): reliable only as a JSON intent
# router. Free-form output shows prompt echoing and truncation, with confidence
# as low as 0.028. These two guards enforce the report's middleware directives.
ECHO_SIMILARITY_MAX = 0.70   # reject arg that just echoes the prompt
CONFIDENCE_MIN = 0.70        # below this, treat classification as unknown


def _is_prompt_echo(user_prompt: str, out: str) -> bool:
    """True if needle3's output is mostly the prompt repeated back (the report's
    similarity(prompt, arg) > 0.7 failure mode)."""
    return SequenceMatcher(None, user_prompt.lower(), out.lower()).ratio() > ECHO_SIMILARITY_MAX


def _parse_confidence(out: str) -> float | None:
    """Pull a confidence score (0..1) from needle3 stdout if present; tolerant of
    format since the real stdout layout is only known on-device. None = absent."""
    m = re.search(r"confidence[\"']?\s*[:=]\s*([01]?\.\d+)", out, re.IGNORECASE)
    return float(m.group(1)) if m else None


def _keyword_intent(text: str) -> str:
    """Offline fallback classifier for hosts without needle3 (e.g. PC testing).
    Specific intents are checked before generic ones (math before greeting)."""
    t = text.lower()
    if "joke" in t:
        return "INTENT_JOKE"
    # math: arithmetic words or any digit paired with an operator token
    if any(w in t for w in ("plus", "minus", "times", "multiplied", "divided")) or \
            re.search(r"\d\s*[-+*/x]\s*\d", t) or "calculate" in t:
        return "INTENT_MATH"
    if "time" in t or "clock" in t:
        return "INTENT_TIME"
    if "date" in t or "day is" in t or "today" in t:
        return "INTENT_DATE"
    if any(w in t for w in ("your name", "version", "can you do", "who are you", "what are you")):
        return "INTENT_ROBOT_INFO"
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
        out = result.stdout.strip()
        if _is_prompt_echo(user_prompt, out):
            print(f"[needle3] rejected prompt echo: {out!r}")
            return FALLBACK
        conf = _parse_confidence(out)
        if conf is not None and conf < CONFIDENCE_MIN:
            print(f"[needle3] low confidence {conf:.3f} < {CONFIDENCE_MIN}, fallback")
            return FALLBACK
        intent = _match_intent(out)
        if not intent:
            print(f"[needle3] no intent matched in: {out!r}")
    except FileNotFoundError:
        print("[needle3] binary not found, using keyword fallback (PC test mode)")
        intent = _keyword_intent(user_prompt)
    except subprocess.TimeoutExpired:
        print("[needle3] timed out")
        intent = ""

    return _respond(intent, user_prompt)


async def main() -> None:
    print(f"Scanning for {DEVICE_NAME}...")
    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=20.0)
    if device is None:
        print(f"Device {DEVICE_NAME} not found.")
        return

    async with BleakClient(device) as client:
        print(f"Connected to {device.address}")

        # Queue messages from the notify callback and process them one at a
        # time below. Doing the classify+write inside the callback blocked the
        # BLE handler, so a message arriving mid-write was dropped.
        queue: asyncio.Queue[str] = asyncio.Queue()

        def on_notify(_char, data: bytearray) -> None:
            try:
                text = json.loads(data.decode("utf-8")).get("text", "")
            except (UnicodeDecodeError, json.JSONDecodeError):
                print(f"[RX] non-JSON payload: {data!r}")
                return
            print(f"[RX text]: {text}")
            queue.put_nowait(text)

        await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
        print("Listening. Type in the ESP32 Serial Monitor. Ctrl-C to quit.")
        try:
            while client.is_connected:
                try:
                    text = await asyncio.wait_for(queue.get(), timeout=1.0)
                except asyncio.TimeoutError:
                    continue  # poll connection state, nothing queued
                response = run_needle3_intent(text)
                print(f"[TX response]: {response}")
                try:
                    await client.write_gatt_char(
                        WRITE_CHAR_UUID, response.encode("utf-8"), response=True
                    )
                except Exception as e:  # report instead of silently dropping
                    print(f"[TX ERROR] write failed: {e}")
        finally:
            await client.stop_notify(NOTIFY_CHAR_UUID)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nBye.")
