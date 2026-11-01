"""不可变文本模型与JSON清单；完整验证后原子提交current。"""

import json
import hashlib
import os
import tempfile
from pathlib import Path
from ..contracts import BusinessError, canonical, digest

def require_path_within(path, root):
    target, base = Path(path).resolve(), Path(root).resolve()
    if target != base and base not in target.parents:
        raise ValueError("path outside model store")
    return target


class ArtifactStore:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def save(self, metadata, files):
        hashes = {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}
        manifest = {**metadata, "files": hashes}
        version = digest(manifest)
        dest = require_path_within(self.root / version, self.root)
        with tempfile.TemporaryDirectory(dir=self.root, prefix="pending-") as temp:
            tmp = Path(temp)
            for name, raw in files.items():
                if Path(name).name != name:
                    raise BusinessError("invalid-model-name")
                (tmp / name).write_bytes(raw)
            (tmp / "manifest.json").write_bytes(canonical(manifest))
            if dest.exists():
                self.load(version)
            else:
                os.replace(tmp, dest)
        self.load(version)
        with tempfile.NamedTemporaryFile(dir=self.root, delete=False) as f:
            f.write(version.encode())
            pending = Path(f.name)
        try:
            os.replace(pending, self.root / "current")
        finally:
            pending.unlink(missing_ok=True)
        return version

    def load(self, version=None):
        try:
            version = version or (self.root / "current").read_text(encoding="ascii")
            if len(version) != 64 or any(c not in "0123456789abcdef" for c in version):
                raise ValueError()
            folder = require_path_within(self.root / version, self.root)
            manifest = json.loads((folder / "manifest.json").read_bytes())
            if not isinstance(manifest, dict) or not isinstance(manifest.get("files"), dict):
                raise ValueError()
            if digest(manifest) != version:
                raise ValueError()
            for name, sha in manifest["files"].items():
                if Path(name).name != name:
                    raise ValueError()
                f = require_path_within(folder / name, self.root)
                if hashlib.sha256(f.read_bytes()).hexdigest() != sha:
                    raise ValueError()
            return version, manifest, folder
        except (OSError, ValueError, KeyError, TypeError) as exc:
            raise BusinessError("model-unavailable") from exc
