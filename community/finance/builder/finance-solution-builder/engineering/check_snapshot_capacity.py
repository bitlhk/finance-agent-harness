"""Read-only local capacity alarm. Never reads bodies or deletes evidence."""
import argparse
import json
import os
from pathlib import Path
import re


def inspect(root, max_files, max_bytes):
    if not 1 <= max_files <= 4096 or not 65536 <= max_bytes <= 1073741824:
        raise ValueError("INVALID_SNAPSHOT_LIMIT")
    if root.is_symlink() or not root.is_dir():
        raise ValueError("HISTORY_DIRECTORY_UNAVAILABLE")
    rows = []
    for owner in sorted(root.iterdir()):
        if not re.fullmatch(r"[a-f0-9]{64}", owner.name):
            continue
        folder = owner / "model-snapshots"
        if owner.is_symlink() or folder.is_symlink():
            raise ValueError("UNSAFE_SNAPSHOT_DIRECTORY")
        if not folder.exists():
            continue
        entries = list(folder.iterdir())
        if any(p.is_symlink() for p in entries):
            raise ValueError("UNSAFE_SNAPSHOT_ENTRY")
        sizes = [p.stat().st_size for p in entries if p.is_file() and p.suffix == ".json"]
        count, size = len(sizes), sum(sizes)
        ratio = max(count / max_files, size / max_bytes)
        rows.append({"ownerRef": owner.name, "files": count, "bytes": size,
                     "maxFiles": max_files, "maxBytes": max_bytes,
                     "state": "FULL" if ratio >= 1 else "WARNING" if ratio >= .8 else "AVAILABLE"})
    return {"scope": "OWNER_SNAPSHOT_BODIES", "retention": "UNCHANGED", "owners": rows,
            "alert": any(row["state"] != "AVAILABLE" for row in rows)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("history", type=Path)
    parser.add_argument("--max-files", type=int, default=int(os.environ.get("FH_SNAPSHOT_MAX_FILES", 128)))
    parser.add_argument("--max-bytes", type=int, default=int(os.environ.get("FH_SNAPSHOT_MAX_BYTES", 16777216)))
    args = parser.parse_args()
    try:
        report = inspect(args.history, args.max_files, args.max_bytes)
    except (OSError, ValueError) as error:
        print(json.dumps({"alert": True, "state": "UNAVAILABLE", "errorType": type(error).__name__}))
        raise SystemExit(2)
    print(json.dumps(report))
    raise SystemExit(1 if report["alert"] else 0)
