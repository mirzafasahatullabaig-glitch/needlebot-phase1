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


def _load_apps() -> dict:
    """Load the app launch whitelist (name -> argv list). Keys starting with
    '_' are comments. Missing/broken file disables launching, never crashes."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apps.json")
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        # only keep name -> list-of-str entries; drop comments and anything odd
        return {k.lower().strip(): v for k, v in data.items()
                if not k.startswith("_") and isinstance(v, list)
                and all(isinstance(a, str) for a in v)}
    except (FileNotFoundError, json.JSONDecodeError, OSError) as e:
        print(f"[apps] not loaded ({e}); app launching disabled")
        return {}


APPS = _load_apps()


def handle_open_app(text: str) -> str:
    """Launch a whitelisted app. ONLY runs a fixed command from apps.json;
    the user's text selects which, it is never passed to a shell."""
    t = text.lower()
    for name, cmd in APPS.items():
        if name in t:
            try:
                subprocess.run(cmd, timeout=10)  # list form, no shell=True
            except (FileNotFoundError, subprocess.TimeoutExpired, OSError) as e:
                print(f"[apps] launch failed for {name}: {e}")
                return f"I couldn't open {name}."
            return f"Opening {name}."
    return "I can't open that one."


def _load_devices() -> dict:
    """Load the device-action whitelist: phrase -> {cmd: argv, reply: str}.
    Same strict pattern as apps; missing/broken file just disables it."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "devices.json")
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        out = {}
        for k, v in data.items():
            if k.startswith("_") or not isinstance(v, dict):
                continue
            cmd = v.get("cmd")
            if isinstance(cmd, list) and all(isinstance(a, str) for a in cmd):
                out[k.lower().strip()] = {"cmd": cmd, "reply": v.get("reply", "Done.")}
        return out
    except (FileNotFoundError, json.JSONDecodeError, OSError) as e:
        print(f"[devices] not loaded ({e}); device actions disabled")
        return {}


DEVICES = _load_devices()


def handle_device(text: str) -> str:
    """Run a whitelisted device action (flashlight, volume, battery...).
    Fixed argv from devices.json only; user text never reaches a shell.
    Longest phrase wins so 'volume up' beats 'volume'."""
    t = text.lower()
    match = max((p for p in DEVICES if p in t), key=len, default=None)
    if match is None:
        return "I can't do that one."
    entry = DEVICES[match]
    try:
        r = subprocess.run(entry["cmd"], timeout=10, capture_output=True, text=True)
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError) as e:
        print(f"[devices] action failed for {match}: {e}")
        return f"I couldn't do {match}."
    reply = entry["reply"]
    # {out} in the reply is filled with the command's stdout (e.g. battery %)
    return reply.format(out=r.stdout.strip()) if "{out}" in reply else reply


# Cactus Needle runtime. On the Android/Termux host the binary is ~/needle-android/needle
# (NOT "needle3") with the model beside it. Override via env for other setups.
# On the PC neither exists, so the call raises FileNotFoundError and the host
# uses the keyword fallback.
_NEEDLE_DIR = os.path.expanduser("~/needle-android")
NEEDLE_BIN = os.environ.get("NEEDLE_BIN", os.path.join(_NEEDLE_DIR, "needle"))
NEEDLE_MODEL = os.environ.get("NEEDLE_MODEL", os.path.join(_NEEDLE_DIR, "needle3.cact"))
# tools.json ships beside this script; each tool is an intent.
TOOLS_PATH = os.environ.get(
    "NEEDLE_TOOLS",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "tools.json"),
)
# tool name "intent_joke" <-> intent code "INTENT_JOKE"
CONFIDENCE_MIN = 0.70  # needle's reported confidence below this -> fallback

ROBOT_NAME = "NeedleBot"
ROBOT_VERSION = "2.4.0"

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


# In-session memory: things the user tells the bot to remember. Lost on
# restart (single session), which is fine for a companion bot.
_MEMORY: dict[str, str] = {}

# Timers push their "time's up" message here when they fire; the main BLE loop
# drains this and sends it to the board. Set in main() to the running loop.
_announce: "asyncio.Queue[str] | None" = None


def _parse_duration(text: str) -> int:
    """Seconds from 'for 5 minutes', '30 seconds', '2 min'. 0 if none found."""
    m = re.search(r"(\d+)\s*(h|hour|hr|m|min|minute|s|sec|second)", text, re.IGNORECASE)
    if not m:
        return 0
    n, unit = int(m.group(1)), m.group(2).lower()
    if unit.startswith("h"):
        return n * 3600
    if unit.startswith("m"):
        return n * 60
    return n  # seconds


def handle_timer(text: str) -> str:
    secs = _parse_duration(text)
    if secs <= 0:
        return "How long should the timer be?"
    if _announce is None:
        return "Timers aren't available right now."

    async def _fire():
        await asyncio.sleep(secs)
        await _announce.put("Time's up!")

    asyncio.ensure_future(_fire())
    mins = secs // 60
    human = f"{mins} minute{'s' if mins != 1 else ''}" if mins else f"{secs} seconds"
    return f"Timer set for {human}."


def handle_remember(text: str) -> str:
    """Store a fact the user states, or recall it.
    'my name is Sam' -> stores name=Sam; 'what is my name' -> recalls it.
    'remember X' stores a free note under 'note'."""
    t = text.lower().strip()
    # "my X is Y" -> remember X = Y. Match on lowercase but keep the original
    # case of the value (so "Sam" stays "Sam"); key is normalised lowercase.
    m = re.search(r"my (\w+) is (.+)", text, re.IGNORECASE)
    if m:
        key, val = m.group(1).lower(), m.group(2).strip(" .!?")
        _MEMORY[key] = val
        return f"Got it, your {key} is {val}."
    # "what is my X" / "what's my X" -> recall
    m = re.search(r"my (\w+)", t)
    if m and ("what" in t or "recall" in t):
        key = m.group(1)
        return f"Your {key} is {_MEMORY[key]}." if key in _MEMORY else f"I don't know your {key} yet."
    # "remember <note>" -> store a note (keep original case)
    m = re.search(r"remember (?:that )?(.+)", text, re.IGNORECASE)
    if m:
        _MEMORY["note"] = m.group(1).strip(" .!?")
        return "I'll remember that."
    if "note" in _MEMORY and ("what" in t):
        return f"You asked me to remember: {_MEMORY['note']}."
    return FALLBACK


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
    "INTENT_OPEN_APP": handle_open_app,
    "INTENT_DEVICE": handle_device,
    "INTENT_REMEMBER": handle_remember,
    "INTENT_TIMER": handle_timer,
}
FALLBACK = "Sorry, I didn't catch that."

VALID_INTENTS = ",".join(INTENT_TEMPLATES)


def _respond(intent: str, user_text: str) -> str:
    """Resolve an intent to its response string, calling handlers with the text."""
    r = INTENT_TEMPLATES.get(intent)
    if r is None:
        return FALLBACK
    return r(user_text) if callable(r) else r

def _intent_from_needle(stdout: str) -> str:
    """Parse needle's JSON tool-call output into an intent code.
    Picks function_calls[0].name ('intent_joke' -> 'INTENT_JOKE'), honouring
    the reported confidence. Empty call or low confidence -> '' (fallback)."""
    try:
        data = json.loads(stdout.strip().splitlines()[-1])  # last line is the JSON
    except (json.JSONDecodeError, IndexError):
        print(f"[needle] unparseable output: {stdout.strip()!r}")
        return ""
    calls = data.get("function_calls") or []
    if not calls:
        return ""  # model declined to dispatch -> no intent
    conf = data.get("confidence")
    if isinstance(conf, (int, float)) and conf < CONFIDENCE_MIN:
        print(f"[needle] low confidence {conf:.3f} < {CONFIDENCE_MIN}, fallback")
        return ""
    name = calls[0].get("name", "")           # e.g. "intent_joke"
    intent = name.upper()                      # -> "INTENT_JOKE"
    return intent if intent in INTENT_TEMPLATES else ""


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
    # device action: a whitelisted phrase present (e.g. "flashlight on")
    if any(p in t for p in DEVICES):
        return "INTENT_DEVICE"
    # app launch: an explicit action verb AND a whitelisted app name, so
    # "open clock" launches but "what time is it" stays INTENT_TIME.
    if any(v in t for v in ("open", "launch", "start")) and any(n in t for n in APPS):
        return "INTENT_OPEN_APP"
    # timer: "set a timer", "timer for N minutes", "remind me in N"
    if "timer" in t or (("remind" in t or "alarm" in t) and _parse_duration(t) > 0):
        return "INTENT_TIMER"
    # memory: "my X is Y", "remember ...", or recall "what is my X"
    # (checked after timer so "remind me" doesn't get caught by "remember")
    if re.search(r"\bmy \w+ is ", t) or "remember" in t or re.search(r"what('?s| is) my \w+", t):
        return "INTENT_REMEMBER"
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


def run_needle3_intent(user_prompt: str) -> str:
    """Return a response string. Uses needle3 when present; otherwise falls back
    to a built-in keyword classifier so the BLE loop is testable without it."""
    global _last_intent
    # Follow-up ("another one", "again") reuses the previous intent, so the bot
    # keeps context across turns instead of classifying each message blind.
    if _is_followup(user_prompt) and _last_intent:
        return _respond(_last_intent, user_prompt)

    # Needle 3 is a tool-calling model: it picks a tool from tools.json and
    # returns JSON with function_calls[].name. The chosen tool name is the
    # intent. --forced makes it always dispatch a call.
    try:
        result = subprocess.run(
            [NEEDLE_BIN, "--model", NEEDLE_MODEL, "--tools", TOOLS_PATH,
             "--prompt", user_prompt, "--forced"],
            capture_output=True,
            text=True,
            timeout=30,
        )
        intent = _intent_from_needle(result.stdout)
    except FileNotFoundError:
        print("[needle] binary not found, using keyword fallback (PC test mode)")
        intent = ""
    except subprocess.TimeoutExpired:
        print("[needle] timed out")
        intent = ""

    # Needle on this tiny model is often low-confidence on short inputs and
    # gets rejected. Fall back to the keyword classifier rather than giving up,
    # so we keep needle's reach but never lose an intent the keywords can catch.
    if not intent:
        kw = _keyword_intent(user_prompt)
        if kw:
            print(f"[needle] falling back to keyword intent: {kw}")
            intent = kw

    if intent:
        _last_intent = intent  # remember for follow-ups
    return _respond(intent, user_prompt)


async def main() -> None:
    global _announce
    print(f"Scanning for {DEVICE_NAME}...")
    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=20.0)
    if device is None:
        print(f"Device {DEVICE_NAME} not found.")
        return

    _announce = asyncio.Queue()  # timers push "time's up" here

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
        async def send(text: str) -> None:
            print(f"[TX response]: {text}")
            try:
                await client.write_gatt_char(
                    WRITE_CHAR_UUID, text.encode("utf-8"), response=True
                )
            except Exception as e:  # report instead of silently dropping
                print(f"[TX ERROR] write failed: {e}")

        try:
            while client.is_connected:
                # a fired timer takes priority, then incoming messages
                if not _announce.empty():
                    await send(_announce.get_nowait())
                    continue
                try:
                    text = await asyncio.wait_for(queue.get(), timeout=1.0)
                except asyncio.TimeoutError:
                    continue  # poll connection state + announce queue
                await send(run_needle3_intent(text))
        finally:
            await client.stop_notify(NOTIFY_CHAR_UUID)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nBye.")
