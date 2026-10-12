"""Deployment-level readability checks for pinned runtime qualification inputs."""
from __future__ import annotations

import json
import hashlib
import os
from pathlib import Path


QUALIFICATION_FILES = (
    "FH_OBSERVATION_QUALIFICATION_FILE",
    "FH_AGENTSCOPE_QUALIFICATION_FILE",
)
QUALIFICATION_DIGESTS = {
    "FH_OBSERVATION_QUALIFICATION_FILE": ("FH_OBSERVATION_QUALIFICATION_SHA256", False),
    "FH_AGENTSCOPE_QUALIFICATION_FILE": ("FH_AGENTSCOPE_QUALIFICATION_SHA256", True),
}


def _read_regular(path: Path, limit: int) -> bytes:
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
            raise ValueError("RUNTIME_QUALIFICATION_UNREADABLE")
        return path.read_bytes()
    except OSError:
        raise ValueError("RUNTIME_QUALIFICATION_UNREADABLE") from None


def require_qualification_access(environment=None, runtime_id=None) -> None:
    """Prove that the service identity can read configured proofs before queueing.

    Java remains authoritative for proof semantics and digests. This deployment
    check catches copied evidence that exists but is unreadable by the service
    account, instead of failing after an otherwise successful compilation.
    """
    env = os.environ if environment is None else environment
    names = QUALIFICATION_FILES if runtime_id is None or str(runtime_id).startswith("agentscope-") else QUALIFICATION_FILES[:1]
    for name in names:
        configured = env.get(name)
        if not configured:
            continue
        path = Path(configured).absolute()
        raw = _read_regular(path, 65_536)
        digest_name, prefixed = QUALIFICATION_DIGESTS[name]
        expected = env.get(digest_name)
        if not expected:
            raise ValueError("RUNTIME_QUALIFICATION_DIGEST_MISSING")
        actual = hashlib.sha256(raw).hexdigest()
        if expected != (("sha256:" if prefixed else "") + actual):
            raise ValueError("RUNTIME_QUALIFICATION_DIGEST_MISMATCH")
        if name != "FH_OBSERVATION_QUALIFICATION_FILE":
            continue
        try:
            qualification = json.loads(raw)
            collectors = qualification.get("collectors", [])
        except (UnicodeError, json.JSONDecodeError, AttributeError):
            # Semantic validation belongs to the qualified Host implementation.
            continue
        for collector in collectors if isinstance(collectors, list) else []:
            for reference in collector.get("evidence", []) if isinstance(collector, dict) else []:
                relative = reference.get("file") if isinstance(reference, dict) else None
                if not isinstance(relative, str) or not relative:
                    continue
                evidence = (path.parent / relative).resolve()
                try:
                    evidence.relative_to(path.parent.resolve())
                except ValueError:
                    continue
                _read_regular(evidence, 16_777_216)
