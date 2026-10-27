"""Host-logic tests: classification (B) and validation guards (C).

No hardware or real needle3 needed. The needle3 subprocess is stubbed to
reproduce the benchmark's failure modes (prompt echo, low confidence) so the
echo filter and confidence gate are exercised the way they will be on-device.
Run: python -m pytest test_host.py -q
"""

import subprocess

import needle_host as h

T = h.INTENT_TEMPLATES


def _stub(stdout):
    """Patch subprocess.run so needle 'emits' the given stdout."""
    class R:
        pass
    def run(args, **kw):
        r = R(); r.stdout = stdout; r.returncode = 0
        return r
    return run


def _needle_json(name=None, confidence=1.0):
    """Build a line of real-needle-shaped JSON output."""
    import json as _j
    calls = [{"name": name, "arguments": {}}] if name else []
    return _j.dumps({"type": "call", "success": True, "function_calls": calls,
                     "confidence": confidence})


# --- B: classification via the keyword fallback (no needle3 binary on PC) ---

def _routes_to(text, intent):
    """Response is non-fallback and the input classifies to the given intent.
    Robust to varied phrasings (responses rotate)."""
    return h.run_needle3_intent(text) != h.FALLBACK and h._keyword_intent(text) == intent

def test_b1_greeting():
    assert _routes_to("hi", "INTENT_GREETING")
    assert _routes_to("hello there", "INTENT_GREETING")

def test_b2_status():
    assert _routes_to("how are you", "INTENT_STATUS")
    assert _routes_to("status report", "INTENT_STATUS")

def test_b3_joke():
    assert _routes_to("tell me a joke", "INTENT_JOKE")

def test_b4_truly_unknown():
    # a question with no intent, no handler, and no curated fact still falls back
    assert h.run_needle3_intent("what is the meaning of life") == h.FALLBACK

def test_b5_gibberish():
    assert h.run_needle3_intent("asdf qwer") == h.FALLBACK


# --- new data-answering intents ---

def test_b6_math():
    assert "4" in h.run_needle3_intent("what is 2 plus 2")
    assert "50" in h.run_needle3_intent("10 * 5")
    assert "4" in h.run_needle3_intent("calculate 7 minus 3")

def test_b7_time():
    assert ":" in h.run_needle3_intent("what time is it")  # HH:MM present

def test_b8_date():
    assert _routes_to("what is the date today", "INTENT_DATE")

def test_b9_robot_info():
    assert h.ROBOT_NAME in h.run_needle3_intent("what is your name")
    assert h.ROBOT_VERSION in h.run_needle3_intent("what version are you")

def test_facts_lookup():
    # a curated fact is answered; an unknown one still falls back
    assert "Paris" in h.run_needle3_intent("what is the capital of France")
    assert h.run_needle3_intent("what is the capital of Narnia") == h.FALLBACK

def test_facts_missing_file_safe(monkeypatch):
    # if facts dict is empty, fact questions just fall back, no crash
    monkeypatch.setattr(h, "FACTS", {})
    assert h.run_needle3_intent("what is the capital of France") == h.FALLBACK

def test_age_question():
    assert _routes_to("how old are you", "INTENT_ROBOT_INFO")
    assert _routes_to("what is your age", "INTENT_ROBOT_INFO")
    assert h.run_needle3_intent("how old are you").isascii()  # no mangled chars

def test_are_you_questions():
    # "are you X" is robot-info, but "how are you" must stay status
    assert _routes_to("are you smart", "INTENT_ROBOT_INFO")
    assert _routes_to("are you a robot", "INTENT_ROBOT_INFO")
    assert h._keyword_intent("how are you") == "INTENT_STATUS"

def test_followup_reuses_last_intent():
    h._last_intent = ""  # reset session state
    assert _routes_to("tell me a joke", "INTENT_JOKE")   # sets last intent
    # a bare follow-up reuses the previous intent instead of falling back
    assert h.run_needle3_intent("another one") != h.FALLBACK
    assert h.run_needle3_intent("again") != h.FALLBACK

def test_followup_without_history_falls_back():
    h._last_intent = ""  # no prior turn
    assert h.run_needle3_intent("another one") == h.FALLBACK

def _app_run_recorder(calls):
    """subprocess.run stub: needle calls (have --prompt) return empty-call JSON
    so routing falls to keywords; app-launch calls (argv list) are recorded."""
    class R: stdout = _needle_json(None)
    def run(cmd, **kw):
        if "--prompt" in cmd:
            return R()
        calls.append((cmd, kw))
        return R()
    return run

def test_open_app_whitelist(monkeypatch):
    calls = []
    monkeypatch.setattr(subprocess, "run", _app_run_recorder(calls))
    monkeypatch.setattr(h, "APPS", {"camera": ["am", "start", "-camera"]})
    assert h.run_needle3_intent("open the camera") == "Opening camera."
    assert calls[0][0] == ["am", "start", "-camera"]

def test_open_app_unknown_not_launched(monkeypatch):
    calls = []
    monkeypatch.setattr(subprocess, "run", _app_run_recorder(calls))
    monkeypatch.setattr(h, "APPS", {"camera": ["am", "start", "-camera"]})
    assert h.run_needle3_intent("open spotify") != "Opening spotify."
    assert calls == []  # nothing launched

def test_open_app_no_shell_injection(monkeypatch):
    # user text never becomes a command: only the fixed argv list runs, and
    # the call is a list (no shell=True), so metacharacters can't inject
    calls = []
    monkeypatch.setattr(subprocess, "run", _app_run_recorder(calls))
    monkeypatch.setattr(h, "APPS", {"camera": ["am", "start", "-camera"]})
    h.run_needle3_intent("open camera; rm -rf ~")
    assert calls[0][0] == ["am", "start", "-camera"]       # injected text absent
    assert calls[0][1].get("shell") is not True

def test_device_action(monkeypatch):
    calls = []
    monkeypatch.setattr(subprocess, "run", _app_run_recorder(calls))
    monkeypatch.setattr(h, "DEVICES", {"flashlight on": {"cmd": ["termux-torch", "on"], "reply": "Flashlight on."}})
    assert h.run_needle3_intent("turn the flashlight on") == "Flashlight on."
    assert calls[0][0] == ["termux-torch", "on"]
    # a device phrase not whitelisted isn't run
    calls.clear()
    assert h.run_needle3_intent("flashlight off") != "Flashlight off."

def test_device_longest_phrase_wins(monkeypatch):
    monkeypatch.setattr(subprocess, "run", _app_run_recorder([]))
    monkeypatch.setattr(h, "DEVICES", {
        "volume": {"cmd": ["x"], "reply": "vol"},
        "volume up": {"cmd": ["y"], "reply": "up"}})
    assert h.handle_device("volume up please") == "up"  # longest match

def test_remember_and_recall():
    h._MEMORY.clear()
    assert "Sam" in h.run_needle3_intent("my name is Sam")
    assert "Sam" in h.run_needle3_intent("what is my name")
    assert h.run_needle3_intent("what is my favourite") == "I don't know your favourite yet." \
        or "don't know" in h.run_needle3_intent("what is my favourite")

def test_duration_parse():
    assert h._parse_duration("for 5 minutes") == 300
    assert h._parse_duration("30 seconds") == 30
    assert h._parse_duration("2 min") == 120
    assert h._parse_duration("1 hour") == 3600
    assert h._parse_duration("no number here") == 0

def test_timer_routes_and_needs_duration():
    assert h._keyword_intent("set a timer for 5 minutes") == "INTENT_TIMER"
    assert h._keyword_intent("remind me in 30 seconds") == "INTENT_TIMER"
    # timer with no duration asks for one
    assert "how long" in h.handle_timer("set a timer").lower()

def test_timer_fires(monkeypatch):
    import asyncio
    real_sleep = asyncio.sleep
    # make the timer's sleep instant so it fires immediately
    monkeypatch.setattr(h.asyncio, "sleep", lambda s: real_sleep(0))
    async def go():
        h._announce = asyncio.Queue()
        reply = h.handle_timer("set a timer for 1 second")
        assert "Timer set" in reply
        got = await asyncio.wait_for(h._announce.get(), timeout=1.0)
        assert got == "Time's up!"
    asyncio.run(go())

def test_math_rejects_code():
    # safe eval: names/calls must not execute, must fall back cleanly
    assert h.run_needle3_intent("calculate __import__('os')") == h.FALLBACK
    assert h.handle_math("5 / 0") == h.FALLBACK


# --- C: tool-call JSON parsing against real-needle-shaped stdout ---

def test_c1_empty_call_then_keyword(monkeypatch):
    # needle declined -> keyword fallback catches "joke"
    monkeypatch.setattr(subprocess, "run", _stub(_needle_json(None)))
    assert h.run_needle3_intent("tell me a joke") != h.FALLBACK
    # but a prompt with no keyword match still falls back
    assert h.run_needle3_intent("zxcv qwerty") == h.FALLBACK

def test_c2_low_confidence_then_keyword(monkeypatch):
    # low-confidence needle call -> keyword fallback catches "joke"
    monkeypatch.setattr(subprocess, "run", _stub(_needle_json("intent_joke", 0.028)))
    assert h.run_needle3_intent("tell me a joke") != h.FALLBACK
    # no keyword match -> still fallback despite the rejected low-conf call
    assert h.run_needle3_intent("zxcv qwerty") == h.FALLBACK

def test_c3_high_conf_tool_call_passes(monkeypatch):
    monkeypatch.setattr(subprocess, "run", _stub(_needle_json("intent_joke", 0.81)))
    assert h.run_needle3_intent("tell me a joke") != h.FALLBACK

def test_c3_all_intents_from_tool_calls(monkeypatch):
    for tool in ["intent_greeting", "intent_status", "intent_joke", "intent_date"]:
        monkeypatch.setattr(subprocess, "run", _stub(_needle_json(tool, 0.80)))
        assert h.run_needle3_intent("x") != h.FALLBACK

def test_unknown_tool_name_falls_back(monkeypatch):
    # a tool name with no matching intent code -> fallback
    monkeypatch.setattr(subprocess, "run", _stub(_needle_json("intent_bogus", 0.9)))
    assert h.run_needle3_intent("x") == h.FALLBACK


# --- JSON parser robustness ---

def test_intent_from_needle():
    assert h._intent_from_needle(_needle_json("intent_joke", 0.9)) == "INTENT_JOKE"
    assert h._intent_from_needle(_needle_json(None)) == ""          # empty call
    assert h._intent_from_needle("not json at all") == ""           # junk
    assert h._intent_from_needle(_needle_json("intent_joke", 0.1)) == ""  # low conf
