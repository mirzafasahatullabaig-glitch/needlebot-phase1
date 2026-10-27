"""NeedleBot Phase 1 host: bridge ESP32-S3 (BLE) <-> Needle 3 intent router.

Listens for text notifications from the ESP32, classifies each with Needle 3
(used strictly as an intent classifier, not for free-form synthesis), and writes
the matching template response back over BLE.
"""

import asyncio
import ast
import json
import operator
import os
import random
import re
import subprocess
from datetime import datetime
from difflib import SequenceMatcher

from bleak import BleakClient, BleakScanner

DEVICE_NAME = "NeedleBot-S3"
SERVICE_UUID = "4fa86970-43ac-11ed-b878-0242ac120002"
NOTIFY_CHAR_UUID = "4fa86c40-43ac-11ed-b878-0242ac120002"  # ESP32 -> us (notify)
WRITE_CHAR_UUID = "4fa86dae-43ac-11ed-b878-0242ac120002"   # us -> ESP32 (write)

def _load_facts() -> dict:
    """Load the curated facts file (question -> answer). Missing/broken file
    just disables the feature; it never stops the bot."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "facts.json")
    try:
        with open(path, encoding="utf-8") as f:
            return {k.lower().strip(): v for k, v in json.load(f).items()}
    except (FileNotFoundError, json.JSONDecodeError, OSError) as e:
        print(f"[facts] not loaded ({e}); fact questions will fall back")
        return {}


FACTS = _load_facts()


def handle_facts(text: str) -> str:
    # match if a known fact key appears in the question (e.g. "what is the
    # capital of france" contains "capital of france")
    t = text.lower()
    for key, answer in FACTS.items():
        if key in t:
            return answer
    return FALLBACK


ROBOT_NAME = "NeedleBot"
ROBOT_VERSION = "1.9.0"

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


def _pick(options: list[str]) -> str:
    """Rotate through phrasings so repeated intents don't sound robotic."""
    return random.choice(options)


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
    return f"That's {result}."


def handle_time(_text: str) -> str:
    return f"It's {datetime.now().strftime('%-H:%M') if os.name != 'nt' else datetime.now().strftime('%#H:%M')}."


def handle_date(_text: str) -> str:
    return f"It's {datetime.now().strftime('%A, %B %d')}."


def handle_robot_info(text: str) -> str:
    t = text.lower()
    if "name" in t:
        return f"I'm {ROBOT_NAME}."
    if "version" in t:
        return f"{ROBOT_NAME}, firmware {ROBOT_VERSION}."
    if "can you do" in t or "help" in t:
        return "I can chat, tell jokes, do quick math, and give you the time or date."
    if "how old" in t or "age" in t:
        return "Brand new - I was just built!"
    if "are you" in t:  # "are you smart/real/a robot/okay..."
        return _pick([
            "Smart enough to tell a joke and do your math!",
            "I'm a small robot doing my best.",
            "I try to be helpful!",
        ])
    return f"I'm {ROBOT_NAME}, a little companion robot."


def handle_greeting(_text: str) -> str:
    return _pick([
        "Hey there!",
        "Hi! What's up?",
        "Hello! How can I help?",
        "Hey! Good to see you.",
        "Hi there, ready when you are.",
    ])


def handle_status(_text: str) -> str:
    return _pick([
        "All good here.",
        "Doing great, thanks!",
        "Running smoothly.",
        "Feeling sharp today.",
        "Happy to be helping.",
    ])


def handle_joke(_text: str) -> str:
    return _pick([
        "Why did the robot cross the road? It was programmed by a chicken.",
        "I'd tell you a UDP joke, but you might not get it.",
        "Why was the robot tired? It had a hard drive.",
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I told my computer I needed a break, and it said 'no problem, I'll go to sleep.'",
        "Why was the math book sad? It had too many problems.",
        "There are 10 kinds of people: those who read binary and those who don't.",
    ])


# A response is a fixed string (template) or a handler(user_text) -> str.
INTENT_TEMPLATES = {
    "INTENT_GREETING": handle_greeting,
    "INTENT_STATUS": handle_status,
    "INTENT_JOKE": handle_joke,
    "INTENT_MATH": handle_math,
    "INTENT_TIME": handle_time,
    "INTENT_DATE": handle_date,
    "INTENT_ROBOT_INFO": handle_robot_info,
    "INTENT_FACT": handle_facts,
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


# Last intent routed this session, for resolving bare follow-ups like
# "another one" / "again". Single-connection bot, so module state is fine.
_last_intent = ""

# Follow-up phrases that mean "do the previous thing again", carrying no intent
# of their own.
_FOLLOWUP_RE = re.compile(
    r"^\s*(another( one)?|again|one more|more|and( that| it)?\??|keep going|do it again)\s*[.!?]*\s*$",
    re.IGNORECASE,
)


def _is_followup(text: str) -> bool:
    return bool(_FOLLOWUP_RE.match(text))


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
    if "status" in t or "how are you" in t:  # before "are you" below
        return "INTENT_STATUS"
    if any(w in t for w in ("your name", "version", "can you do", "who are you",
                            "what are you", "are you", "how old", "age")):
        return "INTENT_ROBOT_INFO"
    if any(w in t for w in ("hi", "hello", "hey", "greet")):
        return "INTENT_GREETING"
    if any(key in t for key in FACTS):  # curated world-knowledge lookup
        return "INTENT_FACT"
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
    global _last_intent
    # Follow-up ("another one", "again") reuses the previous intent, so the bot
    # keeps context across turns instead of classifying each message blind.
    if _is_followup(user_prompt) and _last_intent:
        return _respond(_last_intent, user_prompt)

    # Instruct single-code output so the model emits only the label, not prose.
    # This targets the benchmark's prompt-echo at the source.
    prompt = (
        "You are an intent classifier. Reply with exactly one of these codes "
        f"and nothing else: {VALID_INTENTS}. If none fit, reply NONE.\n"
        f"Message: {user_prompt}\nIntent:"
    )
    # NEEDLE3_EXTRA_ARGS: set to cap output tokens IF the runtime supports it,
    # e.g. "--max-tokens 6". Unverified from PC, so off by default to avoid
    # passing a flag needle3 might reject. Confirm on-device before enabling.
    extra = os.environ.get("NEEDLE3_EXTRA_ARGS", "").split()
    try:
        result = subprocess.run(
            ["needle3", "--model", "needle3.cact", "--prompt", prompt,
             "--forced", *extra],
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

    if intent:
        _last_intent = intent  # remember for follow-ups
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
