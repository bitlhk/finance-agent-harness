"""Trusted Host task controls, independent of business capability names."""
import hashlib
import json

TASK_ACTION_FIELDS = {
    "tasks": frozenset(),
    "task-challenge": frozenset({"handle"}),
    "task-confirm": frozenset({"handle", "nonce"}),
    "task-decline": frozenset({"handle"}),
    "task-resume": frozenset({"handle"}),
    "step-up": frozenset({"verificationCode"}),
}


def validate_task_body(action, body):
    fields = TASK_ACTION_FIELDS[action]
    if not isinstance(body, dict) or set(body) != fields:
        raise ValueError("INVALID_TASK_REQUEST")
    if any(not isinstance(value, str) or not value.strip()
           or len(value) > (64 if field == "verificationCode" else 160)
           for field, value in body.items()):
        raise ValueError("INVALID_TASK_REQUEST")


def task_runtime_required(candidate, manifest):
    expected = manifest.get("files", {}).get("component-plan.json")
    if expected is None: return False  # Old immutable artifacts do not gain TASK support.
    path = candidate / "component-plan.json"
    if path.is_symlink() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError("BUILD_LOCK_MISMATCH")
    operations = {c["operation"] for c in json.loads(path.read_bytes())["capabilities"]}
    if not operations <= {"QUERY", "TASK"}: raise ValueError("HOST_OPERATION_UNSUPPORTED")
    tasks = "TASK" in operations
    if tasks and manifest["runtime"]["language"] != "java": raise ValueError("HOST_OPERATION_UNSUPPORTED")
    return tasks
