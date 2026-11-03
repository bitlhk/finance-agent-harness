"""Prepare the separately owned, pinned original tool for the native CI test.

Only public fixed sources are fetched; no model or bank is contacted. The tool
has its own Python 3.12 environment, independent of the contract-test matrix.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess

UPSTREAM_URL = "https://gitcode.com/openJiuwen/agent-store.git"
UPSTREAM_REVISION = "0e158eee225c698774aa75671c944624cd36550b"
BUILDER_URL = "https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork.git"
BUILDER_REVISION = "cacc782ee9903152a85322ae2ede3588f13f67b3"
TOOL_PATH = "community/finance/workshop/tools/market-risk-capital-readonly"


def run(command, **kwargs):
    return subprocess.run(command, check=True, timeout=300, **kwargs)


def fixed_checkout(url, revision, destination, paths):
    """Create a fresh sparse checkout; never alter an existing developer tree."""
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    git = ["git", "-c", "core.autocrlf=false", "-c", "credential.helper=",
           "-C", str(destination)]
    run([*git, "init", "-q"], env=env)
    run([*git, "remote", "add", "origin", url], env=env)
    run([*git, "sparse-checkout", "init", "--cone"], env=env)
    run([*git, "sparse-checkout", "set", *paths], env=env)
    run([*git, "fetch", "--depth=1", "--no-tags", "--filter=blob:none", "origin", revision], env=env)
    run([*git, "checkout", "--detach", "FETCH_HEAD"], env=env)
    actual = subprocess.check_output([*git, "rev-parse", "HEAD"], env=env, timeout=30).decode().strip()
    if actual != revision:
        raise ValueError("ORIGINAL_TOOL_SOURCE_REVISION_MISMATCH")
    return destination


def export_properties(release, release_digest, github_env):
    """Only non-secret, single-line values may enter the job environment."""
    values = {"FH_ORIGINAL_TOOL_RELEASE": str(Path(release).resolve()),
              "FH_ORIGINAL_TOOL_RELEASE_DIGEST": release_digest}
    if any("\n" in v or "\r" in v for v in values.values()):
        raise ValueError("INVALID_CI_ENVIRONMENT_VALUE")
    with Path(github_env).open("a", encoding="utf-8", newline="\n") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")


def prepare(workspace, uv="uv"):
    workspace = Path(workspace).resolve()
    workspace.mkdir(parents=True, exist_ok=False)
    upstream = fixed_checkout(UPSTREAM_URL, UPSTREAM_REVISION, workspace / "upstream",
                              ["community/finance/scenario/bank-mktrisk-kit"])
    builder = fixed_checkout(BUILDER_URL, BUILDER_REVISION, workspace / "builder", [TOOL_PATH])
    publisher = builder / TOOL_PATH / "publish_original_tool.py"
    spec = importlib.util.spec_from_file_location("original_tool_publisher", publisher)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    release = workspace / "release"
    manifest = module.publish(upstream, release)
    # --frozen prevents a resolver update; the venv is outside the Harness Python environment.
    run([uv, "sync", "--project", str(release), "--python", "3.12", "--frozen", "--no-dev"])
    python = release / (".venv/Scripts/python.exe" if os.name == "nt" else ".venv/bin/python")
    run([str(python), "-B", "-c",
         "from pathlib import Path; from market_risk_capital_service import verify_release, dependency_receipt; "
         f"verify_release(Path('.'), {manifest['releaseDigest']!r}); "
         "print(dependency_receipt(Path('.')))"], cwd=release,
        env={**os.environ, "PYTHONPATH": str(release), "PYTHONDONTWRITEBYTECODE": "1"})
    receipt = {"upstreamRevision": UPSTREAM_REVISION, "builderRevision": BUILDER_REVISION,
               "releaseDigest": manifest["releaseDigest"], "toolPython": "3.12",
               "dependencyInstall": "FROZEN_LOCK", "businessExecution": "NOT_RUN"}
    (workspace / "preparation.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return release, manifest["releaseDigest"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--github-env", type=Path, required=True)
    parser.add_argument("--uv", default="uv")
    args = parser.parse_args()
    release, identity = prepare(args.workspace, args.uv)
    export_properties(release, identity, args.github_env)


if __name__ == "__main__":
    main()
