# NeedleBot Phase 3 — App Automation (drive apps, not just open them)

Status: proposal for review. No code yet. (Phase 2 shipped: v2.12.0, call-a-contact verified on hardware.)

## What you asked for

> "use any application to do work"

Today NeedleBot can **open** any app and fire a handful of built-in actions
(call, flashlight, search, timer). It cannot **operate** an app — tap a button,
type into a field, scroll, send. Phase 3 is about that: "send a WhatsApp to
mom saying I'm late", "play lo-fi on YouTube", "reply to this email".

## The one hard truth up front

There is no clean API to drive arbitrary third-party apps on Android. The only
general mechanism is the **AccessibilityService** — the same API screen readers
use. It can read the on-screen view tree and inject taps/text into *other* apps.
Consequences, stated honestly so we decide with eyes open:

- It needs a **special permission** the user grants manually in Settings →
  Accessibility, behind a scary "this can observe everything you do" warning.
  There is no runtime-prompt shortcut.
- **Google Play bans** AccessibilityService used for automation that isn't an
  accessibility aid. This repo is private / sideloaded, so that's fine for us —
  but it's a hard ceiling if you ever publish.
- It is **inherently brittle**: you match on-screen text and buttons, and every
  app changes those with every update. A flow is never "done", it rots.

So Phase 3 is not "make it do any task in any app." That's a mirage. Phase 3 is:
**build the automation substrate, then support a small, growing set of concrete
flows that we test and maintain.**

## The real bottleneck is the brain, not the hands

The tapping is the easy 20%. The hard 80% is: **where does the step-by-step plan
come from?** The current pipeline is:

```
BLE text  →  needle (/complete)  →  ONE intent code  →  Intents.kt handler
```

`needle` is a **classifier**. It maps an utterance to one of a fixed set of
intents (see `tools.json`). It does **not** and cannot emit "open WhatsApp, tap
search, type mom, tap the chat, type the message, tap send." Multi-step app
automation needs a **planner**, which needle is not.

That gives us three honest options for the brain, in rising order of power and cost:

| Option | What it is | Cost / risk |
|---|---|---|
| **A. Scripted flows** | Each supported task is a hand-written Kotlin flow (a list of find-text/tap/type steps). needle (or a keyword) picks *which* flow; the flow itself is fixed code. | Lowest. Reliable per-flow. Doesn't generalize — every new task is new code. This is how Tasker/Google Assistant routines actually work. |
| **B. Deep links first, automation second** | Many "tasks" don't need UI driving at all — they have documented intents/URLs (WhatsApp `wa.me`, YouTube search intent, Maps nav, email `mailto`). Use those where they exist; fall back to scripted accessibility flows only where they don't. | Low–medium. Most robust where it applies, because it skips the UI entirely. Should be tried **before** A for every task. |
| **C. LLM planner** | A model turns the view tree + goal into the next action, step by step (the "computer use" pattern). Generalizes to unseen apps. | Highest. needle can't do this (it's a classifier). Would need a bigger local model or a paid API — and your standing rule is local-first, no paid inference. Slow on-device, and still brittle. Not for Phase 3. |

**Recommendation: B then A.** For each task, use a deep link / documented intent
if one exists; only drop to a scripted AccessibilityService flow when there's no
other way. Defer C entirely — it's a research project, not a phase.

## Proposed architecture

```
BLE text ─► needle /complete ─► intent  ─► Intents.kt
                                   │
                                   └─ new INTENT_TASK ─► TaskRouter
                                                           ├─ deep-link flow (preferred)   ── Intent/URL, no accessibility
                                                           └─ scripted flow (fallback)      ── NeedleAccessibilityService
```

New pieces (small, additive — nothing in Phase 2 changes):

1. **`tools.json` gets task intents** so needle can route them. Likely one
   `intent_task` with an argument (e.g. `app`, `action`, `text`), or a few
   coarse ones (`intent_message`, `intent_play`, `intent_navigate`). Needs a
   real `curl` test on the phone to see what needle actually emits — same
   discipline as Phase 2's first step.
2. **`TaskRouter`** (new Kotlin): given the parsed task, try a deep-link flow;
   if none, run a scripted accessibility flow; else say "I can't do that yet."
3. **`NeedleAccessibilityService`** (new): the service class + its config XML +
   a manifest entry. Provides primitives: `findByText`, `tap`, `setText`,
   `scroll`, `back`, `waitForWindow(pkg)`. Flows are written against these.
4. **One real flow, end to end**, to prove the whole chain before generalizing.

## Scope of Phase 3 v1 (deliberately small)

Prove the substrate with **exactly two flows**, one of each kind:

- **Deep-link flow:** "message <contact> <text>" on WhatsApp via
  `https://wa.me/<number>?text=<msg>` (no accessibility needed — proves the
  preferred path).
- **Scripted accessibility flow:** one genuinely-UI task that has no deep link,
  e.g. "turn on Wi-Fi" via the Settings UI, or "like the current post". This
  proves the AccessibilityService path end to end.

If both work on hardware, the substrate is real and adding flow #3, #4 … is
incremental. If we can't get even one scripted flow stable, we've learned that
cheaply instead of after building ten.

## What carries over vs. new

| Piece | Fate |
|---|---|
| Phase 2 app, all `INTENT_*` handlers, call feature | **Unchanged** |
| `Intents.kt` dispatch | **One new arm** (`INTENT_TASK` → TaskRouter) |
| needle binary + model | **Unchanged** (classifier only) |
| `tools.json` | **Extended** with task intent(s), re-tested via curl |
| AccessibilityService, TaskRouter, flows | **New** |

## Open questions to settle before building

1. **How granular are task intents in `tools.json`?** One `intent_task` with
   arguments, or several coarse intents? → settle with a `curl` probe on the
   phone first (what does needle reliably emit?).
2. **How does the command carry its payload?** Needle returns an intent + maybe
   arguments; but the message *text* ("tell mom I'm late") still comes over BLE.
   TaskRouter likely parses the raw text for the payload, like the call handler
   already does. Confirm needle's `arguments` are usable or if we parse text.
3. **Which 2 flows for v1?** Pick one deep-link task and one true-UI task you
   actually want. (Suggestion above: WhatsApp message + Wi-Fi toggle.)
4. **Accessibility permission UX:** first run sends the user to Settings to
   enable the service (like the overlay-permission flow already in
   `MainActivity`). Acceptable? It's unavoidable for the scripted path.
5. **Safety gate:** a mis-parsed automation can do real things in real apps.
   Do destructive/sending flows need a confirmation step (spoken "yes") before
   executing? Recommend: yes, for anything that sends or deletes.

## Risks / honesty

- **AccessibilityService flows rot.** Every target-app update can break a
  scripted flow. This is maintenance forever, not build-once. Budget for it.
- **Play Store is closed** to this approach — fine while private/sideloaded,
  a wall if you ever publish. Decide now if that matters.
- **needle is a classifier, not a planner** — it caps how "smart" routing can
  be. True open-ended "do any task" needs option C (an LLM planner), which your
  local-first / no-paid-inference rule rules out for now. Phase 3 is honestly
  "a maintained library of flows", not "an agent that operates any app".
- **This is bigger than Phase 2's call feature** — multi-session. v1 is the
  substrate + 2 flows, not "everything".

## Suggested first step

Before any app code, two cheap probes:

1. On the phone, `curl` needle with task-shaped utterances
   (`'{"input":"message mom I am late"}'`, `'{"input":"turn on wifi"}'`) and
   capture what `function_calls` / `arguments` it returns. That tells us how
   much routing needle can do vs. how much we parse from text — exactly the
   Phase 2 discipline (prove the contract first).
2. Pick the two v1 flows (question 3). Then I build the AccessibilityService
   skeleton + the WhatsApp deep-link flow first (no accessibility risk), prove
   it, then the one scripted flow.
