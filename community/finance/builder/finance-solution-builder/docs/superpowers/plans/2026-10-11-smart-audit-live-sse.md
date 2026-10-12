# Smart Audit Live SSE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Relay the candidate Agent's authenticated AG-UI SSE stream so the smart-audit conversation and Subagent rail update as events actually occur.

**Architecture:** `Experience.stream` will validate the existing ticket/session, create a server-to-server AG-UI request to the active candidate, and forward a strict allow-list of SSE events immediately. The browser already renders `TEXT_MESSAGE_CONTENT` and `finance.subagents`; the work is to remove the buffered `/chat` bridge while retaining authorization and evidence boundaries.

**Tech Stack:** Python 3.12 standard-library HTTP/SSE proxy, Java AG-UI candidate transport, browser AG-UI client, pytest, Playwright CLI.

---

### Task 1: Specify and test progressive event forwarding

**Files:**
- Modify: `tests/test_experience_stream.py`
- Modify: `src/finance_solution_builder/experience.py:249-286`

- [ ] **Step 1: Write the failing test**

```python
def test_stream_forwards_candidate_agui_events_immediately(monkeypatch):
    sent = []
    candidate_events = [
        {"type": "CUSTOM", "name": "finance.subagents", "value": {"members": []}},
        {"type": "TEXT_MESSAGE_START", "messageId": "m-1", "role": "assistant"},
        {"type": "TEXT_MESSAGE_CONTENT", "messageId": "m-1", "delta": "第一段"},
        {"type": "TEXT_MESSAGE_CONTENT", "messageId": "m-1", "delta": "第二段"},
        {"type": "RUN_FINISHED", "threadId": "session-a", "runId": "run-a"},
    ]
    experience._stream_candidate_agui = lambda *args: iter(candidate_events)
    experience.stream("agent-a", "ticket-a", {"query": "材料包", "runId": "run-a"}, sent.append)
    assert [event["type"] for event in decode(sent)] == [event["type"] for event in candidate_events]
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `PYTHONPATH=.:src uv run --python 3.12 --extra test pytest -q tests/test_experience_stream.py`

Expected: FAIL because `_stream_candidate_agui` does not exist and `stream` buffers `/chat` output.

- [ ] **Step 3: Implement the minimal proxy**

```python
def _stream_candidate_agui(self, build_id, entry, body):
    # POST the validated AG-UI envelope to the active instance and yield one
    # decoded JSON event per SSE data line. Reject unknown event types/names.
    ...

def stream(self, build_id, token, body, send):
    entry = self.current(build_id, token)
    for event in self._stream_candidate_agui(build_id, entry, body):
        send(encode(event))
```

- [ ] **Step 4: Run the focused test**

Run: `PYTHONPATH=.:src uv run --python 3.12 --extra test pytest -q tests/test_experience_stream.py`

Expected: PASS.

### Task 2: Preserve candidate AG-UI ordering and cancellation safety

**Files:**
- Modify: `src/finance_solution_builder/experience.py:249-286`
- Modify: `tests/test_experience_stream.py`

- [ ] **Step 1: Write failing safety tests**

```python
def test_stream_rejects_unknown_candidate_event():
    experience._stream_candidate_agui = lambda *args: iter([{"type": "TOOL_CALL", "secret": "x"}])
    assert_run_error(experience)

def test_stream_rejects_finished_event_for_another_run():
    experience._stream_candidate_agui = lambda *args: iter([{"type": "RUN_FINISHED", "threadId": "session-a", "runId": "other"}])
    assert_run_error(experience)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `PYTHONPATH=.:src uv run --python 3.12 --extra test pytest -q tests/test_experience_stream.py`

Expected: FAIL because no candidate-event validation exists.

- [ ] **Step 3: Add a strict event validator**

```python
ALLOWED_TYPES = {"RUN_STARTED", "RUN_FINISHED", "RUN_ERROR", "STEP_STARTED", "STEP_FINISHED",
                 "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "CUSTOM"}
ALLOWED_CUSTOM_NAMES = {"finance.subagents", "finance.reviewTeam", "finance.display", "finance.evidence"}

def _validated_agui_event(event, session, run_id):
    # Require object shape; bind RUN_* events to current session/run; restrict CUSTOM names.
    ...
```

- [ ] **Step 4: Run the focused tests**

Run: `PYTHONPATH=.:src uv run --python 3.12 --extra test pytest -q tests/test_experience_stream.py`

Expected: PASS.

### Task 3: Build and visually verify actual streaming

**Files:**
- Generated: `examples/agent-experience/app.js` via `npm run build:ui` when browser sources change
- Evidence: `output/playwright/`

- [ ] **Step 1: Run static and unit checks**

Run: `npm run build:ui && node engineering/test_smart_audit_experience.mjs && PYTHONPATH=.:src uv run --python 3.12 --extra test pytest -q tests/test_experience_stream.py`

Expected: UI bundle builds; static fixture and proxy tests pass.

- [ ] **Step 2: Build a desktop personal-loan candidate through Studio**

Use Playwright to select `信贷授信` → `个人贷款审核辅助` → `电脑端` → `开始构建` → `启动 Agent`.

- [ ] **Step 3: Capture the event sequence through the web UI**

Use Playwright to start the session, upload `/Users/apple/Documents/工作资料/代码助手/灵感/personal-loan-demo.json`, then click `发起智能审核`. Capture a screenshot while the orchestrator is running and another after the four specialists complete. Confirm `TEXT_MESSAGE_CONTENT` arrives in multiple browser updates and the right rail changes without client-side timers.

- [ ] **Step 4: Run final hygiene checks**

Run: `git diff --check -- src/finance_solution_builder/experience.py tests/test_experience_stream.py templates/local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local/AgUiTransport.java browser/experience.js`

Expected: no whitespace errors.
