"""Public observed history only; no labels, generation or scoring imports."""
import json
import hashlib
from pathlib import Path
from ..contracts import BusinessError, digest

def with_history(context, history):
    """接入可见权益历史；无法验证的未来/异源记录不进入模型。"""
    from datetime import datetime

    rows = []
    for raw in history:
        when = datetime.fromisoformat(raw["event_at"])
        known = datetime.fromisoformat(raw["known_at"])
        if not when.tzinfo or not known.tzinfo:
            raise BusinessError("invalid-history-time")
        if raw.get("source_id") != "benefits" or when > context.observed_at or known > context.observed_at:
            continue
        rows.append({key: raw[key] for key in ("record_id", "benefit_id", "source_id", "event_at", "known_at", "value")})
    payload = context.model_dump(mode="json")
    payload["history"] = list(context.history) + rows[-100:]
    payload.pop("comparison_fingerprint")
    return context.model_copy(update={"history": tuple(payload["history"]),
        "comparison_fingerprint": digest({"input": payload, "eligibility": "benefits-v1", "protocol": "rec-v1"})})

def read_public(root):
    root = Path(root)
    if any((root/name).is_symlink() or not (root/name).is_file() for name in ('manifest.json','samples.jsonl')):
        raise BusinessError('preference-source-unavailable')
    manifest = json.loads((root / "manifest.json").read_bytes())
    data = (root / "samples.jsonl").read_bytes()
    if hashlib.sha256(data).hexdigest() != manifest["samples_sha256"]:
        raise BusinessError("dataset-integrity-mismatch")
    return manifest, [json.loads(row) for row in data.splitlines() if row]

def observed_history(root, context):
    _, samples = read_public(root)
    for sample in samples:
        stored = sample["context"]
        if stored["customer_id"] == context.customer_id and stored['case_id'] == context.case_id:
            if (stored["dataset_version"] != context.dataset_version
                    or stored['snapshot_digest'] != context.snapshot_digest):
                raise BusinessError("preference-dataset-mismatch")
            return with_history(context, [r for r in stored["history"] if r.get("source_id") == "benefits"])
    return context
