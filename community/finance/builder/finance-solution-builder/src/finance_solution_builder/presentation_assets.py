"""Package bounded, declarative QUERY views from selected trusted capability packs."""
import json
import re
from pathlib import Path


def presentation_manifest(harness: Path, plan: dict) -> dict:
    result = {}
    root = harness.resolve()
    for ref in plan["capabilityPackRefs"]:
        source = ref["source"]
        if not source.startswith("repo://"):
            raise ValueError("PRESENTATION_SOURCE_INVALID")
        relative = Path(source.removeprefix("repo://"))
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("PRESENTATION_SOURCE_INVALID")
        path = root / relative / "presentation.v0.1.json"
        if any(part.is_symlink() for part in [path, *path.parents] if part != root):
            raise ValueError("PRESENTATION_SYMLINK_REJECTED")
        if not path.is_file():
            continue
        if path.stat().st_size > 65536:
            raise ValueError("PRESENTATION_TOO_LARGE")
        data = json.loads(path.read_text(encoding="utf-8"))
        if set(data) != {"schemaVersion", "views"} or data["schemaVersion"] != "finance-pack-presentation.v0.1":
            raise ValueError("PRESENTATION_SCHEMA_INVALID")
        views = data["views"]
        if not isinstance(views, list) or not 1 <= len(views) <= 16:
            raise ValueError("PRESENTATION_VIEWS_INVALID")
        # A pack cannot provide a view for another pack's capability.
        import yaml
        pack = yaml.safe_load((path.parent / "pack.yaml").read_text(encoding="utf-8"))
        owned = {c["capabilityId"] for c in pack["capabilities"] if c["operation"] == "QUERY"}
        for view in views:
            if not isinstance(view, dict) or set(view) != {"capabilityId", "factsVersion", "title", "scope", "rows", "fields"}:
                raise ValueError("PRESENTATION_VIEW_INVALID")
            cap = view["capabilityId"]
            if cap not in owned or cap not in plan["capabilityBindings"] or cap in result:
                raise ValueError("PRESENTATION_CAPABILITY_INVALID")
            for name in ("capabilityId", "factsVersion"):
                if not isinstance(view[name], str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", view[name]):
                    raise ValueError("PRESENTATION_IDENTIFIER_INVALID")
            for name in ("title", "scope"):
                if not isinstance(view[name], str) or not 1 <= len(view[name]) <= 240 or any(ord(c) < 32 for c in view[name]):
                    raise ValueError("PRESENTATION_TEXT_INVALID")
            if view["rows"] not in ("SELF", "items"):
                raise ValueError("PRESENTATION_ROWS_INVALID")
            fields = view["fields"]
            if not isinstance(fields, dict) or not 1 <= len(fields) <= 16:
                raise ValueError("PRESENTATION_FIELDS_INVALID")
            for key, label in fields.items():
                if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]{0,63}", key) or key in {"accountId", "customerId", "principalRef", "subjectRef", "nextCursor"}:
                    raise ValueError("PRESENTATION_FIELD_INVALID")
                if not isinstance(label, str) or not 1 <= len(label) <= 80 or any(ord(c) < 32 for c in label):
                    raise ValueError("PRESENTATION_LABEL_INVALID")
            result[cap] = view
    return {"schemaVersion": "finance-pack-presentation.v0.1", "views": list(result.values())}
