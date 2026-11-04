"""Prepare pinned material sources and their ordinary locked Python environment.

The contract matrix interpreter is independent of this Python 3.12 tool. Only
public fixed sources/dependencies are fetched; no model, bank or Key is used.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from prepare_original_market_risk_tool import fixed_checkout, run

UPSTREAM_URL = "https://gitcode.com/openJiuwen/agent-store.git"
UPSTREAM_REVISION = "0e158eee225c698774aa75671c944624cd36550b"
BUILDER_URL = "https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork.git"
BUILDER_REVISION = "b948d8060c5410cb657c720b6c9bd32d7ab211e8"
TOOL_PATH = "community/finance/workshop/tools/financial-material-processing"
ORIGINAL_PATHS = ["community/finance/scenario/fintable-extract",
                  "community/finance/workshop/skills/smart-audit"]
PROPERTIES = {
    "fh.python": "FH_MATERIAL_PYTHON",
    "fh.materialServiceSource": "FH_MATERIAL_SERVICE_SOURCE",
    "fh.originalMaterialSource": "FH_ORIGINAL_MATERIAL_SOURCE",
    "fh.materialEvidence": "FH_MATERIAL_EVIDENCE",
}


def export_properties(properties, github_env):
    """Export exactly the four non-secret native-test path inputs atomically."""
    if set(properties) != set(PROPERTIES):
        raise ValueError("MATERIAL_CI_PROPERTIES_REQUIRED")
    lines = []
    for prop, variable in PROPERTIES.items():
        value = os.fspath(properties[prop])
        if not value or any(c in value for c in "\r\n\0"):
            raise ValueError("INVALID_CI_ENVIRONMENT_VALUE")
        # Resolving a POSIX venv's Python symlink selects its base interpreter
        # instead, losing the sibling pyvenv.cfg and installed dependencies.
        lines.append(variable + "=" + str(Path(value).absolute()) + "\n")
    with Path(github_env).open("a", encoding="utf-8", newline="\n") as stream:
        stream.write("".join(lines))


def prepare(workspace, uv="uv"):
    """Create a fresh fixed source closure; never reuse a mutable developer tree."""
    workspace = Path(workspace).resolve()
    workspace.mkdir(parents=True, exist_ok=False)
    upstream = fixed_checkout(UPSTREAM_URL, UPSTREAM_REVISION,
                              workspace / "upstream", ORIGINAL_PATHS)
    builder = fixed_checkout(BUILDER_URL, BUILDER_REVISION,
                             workspace / "builder", [TOOL_PATH])
    source = builder / TOOL_PATH
    lock_before = (source / "uv.lock").read_bytes()
    run([uv, "sync", "--project", str(source), "--python", "3.12",
         "--frozen", "--no-dev", "--group", "test"])
    if (source / "uv.lock").read_bytes() != lock_before:
        raise ValueError("MATERIAL_CI_LOCK_CHANGED")
    python = source / (".venv/Scripts/python.exe" if os.name == "nt" else ".venv/bin/python")
    release = workspace / "release"
    preflight = workspace / "preflight.json"
    script = """
import importlib.util,json,sys
from pathlib import Path
from publish_original_tool import publish
from engine_registry import Engines
from reportlab.platypus import SimpleDocTemplate, Table
assert sys.version_info[:2] == (3,12), 'Material tool requires its own Python3.12'
assert importlib.util.find_spec('pymupdf') is None, 'Ordinary CI must not install PyMuPDF'
manifest=publish(Path(sys.argv[1]),Path(sys.argv[2]))
engine=Engines(Path(sys.argv[2]))
engine.table_module()
Path(sys.argv[3]).write_text(json.dumps({'releaseDigest':manifest['releaseDigest'],
    'python':sys.version.split()[0],'pymupdfInstalled':False}),encoding='utf-8')
"""
    env = dict(os.environ, PYTHONPATH=str(source), PYTHONUTF8="1", PYTHONDONTWRITEBYTECODE="1")
    env.pop("FH_MATERIAL_QUALIFICATION_AUTHORITY", None)
    run([str(python), "-B", "-c", script, str(upstream), str(release), str(preflight)],
        cwd=source, env=env)
    checked = json.loads(preflight.read_text(encoding="utf-8"))
    evidence = workspace / "native-evidence"
    evidence.mkdir()
    receipt = {"upstreamRevision": UPSTREAM_REVISION, "builderRevision": BUILDER_REVISION,
               "releaseDigest": checked["releaseDigest"], "toolPython": checked["python"],
               "dependencyInstall": "FROZEN_LOCK_WITH_TEST_GROUP", "pymupdfInstalled": checked["pymupdfInstalled"],
               "businessExecution": "NOT_RUN", "realModelRequests": 0}
    (workspace / "preparation.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return {"fh.python": python, "fh.materialServiceSource": source,
            "fh.originalMaterialSource": upstream, "fh.materialEvidence": evidence}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--github-env", type=Path, required=True)
    parser.add_argument("--uv", default="uv")
    args = parser.parse_args()
    properties = prepare(args.workspace, args.uv)
    export_properties(properties, args.github_env)


if __name__ == "__main__":
    main()
