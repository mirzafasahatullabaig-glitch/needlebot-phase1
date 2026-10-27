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
    """Patch subprocess.run so needle3 'emits' the given stdout."""
    class R:
        pass
    def run(args, **kw):
        r = R(); r.stdout = stdout; r.returncode = 0
        return r
    return run


# --- B: classification via the keyword fallback (no needle3 binary on PC) ---

def test_b1_greeting():
    assert h.run_needle3_intent("hi") == T["INTENT_GREETING"]
    assert h.run_needle3_intent("hello there") == T["INTENT_GREETING"]

def test_b2_status():
    assert h.run_needle3_intent("how are you") == T["INTENT_STATUS"]
    assert h.run_needle3_intent("status report") == T["INTENT_STATUS"]

def test_b3_joke():
    assert h.run_needle3_intent("tell me a joke") == T["INTENT_JOKE"]

def test_b4_unknown_math():
    assert h.run_needle3_intent("what is 2 plus 2") == h.FALLBACK

def test_b5_gibberish():
    assert h.run_needle3_intent("asdf qwer") == h.FALLBACK


# --- C: validation guards firing against real-needle3-shaped stdout ---

def test_c1_prompt_echo_rejected(monkeypatch):
    # needle3 parrots the prompt back verbatim (benchmark T02/T05 mode)
    monkeypatch.setattr(subprocess, "run", _stub("say hello to me in a friendly way"))
    assert h.run_needle3_intent("say hello to me in a friendly way") == h.FALLBACK

def test_c2_low_confidence_rejected(monkeypatch):
    # valid code but confidence below threshold (benchmark saw 0.028)
    monkeypatch.setattr(subprocess, "run", _stub("INTENT_JOKE confidence=0.028"))
    assert h.run_needle3_intent("tell me a joke") == h.FALLBACK

def test_c3_clean_high_confidence_passes(monkeypatch):
    monkeypatch.setattr(subprocess, "run", _stub("INTENT_JOKE confidence=0.81"))
    assert h.run_needle3_intent("tell me a joke") == T["INTENT_JOKE"]

def test_c3_high_conf_all_intents(monkeypatch):
    for text, code in [("hi", "INTENT_GREETING"),
                       ("how are you", "INTENT_STATUS"),
                       ("joke please", "INTENT_JOKE")]:
        monkeypatch.setattr(subprocess, "run", _stub(f"{code} confidence=0.80"))
        assert h.run_needle3_intent(text) == T[code]


# --- confidence parser format tolerance (A2 prep) ---

def test_confidence_parse_formats():
    assert h._parse_confidence('{"confidence": 0.028}') == 0.028
    assert h._parse_confidence("confidence=0.8087") == 0.8087
    assert h._parse_confidence("CONFIDENCE : 0.5") == 0.5
    assert h._parse_confidence("INTENT_JOKE") is None  # absent -> gate skipped
