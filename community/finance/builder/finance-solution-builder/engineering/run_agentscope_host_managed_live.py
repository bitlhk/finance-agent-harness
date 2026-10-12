"""Operator-authorized read of two managed config files; secrets only enter child memory."""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile


def managed_connection():
    def read_remote(path):
        return subprocess.check_output(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
                                        "root@123.60.154.110", "cat " + path], text=True, timeout=30)
    values = {}
    for line in read_remote("/etc/finance-studio/model.env").splitlines():
        if not line.strip() or line.lstrip().startswith("#"): continue
        key, separator, value = line.partition("=")
        if separator:
            words = shlex.split(value)
            values[key.strip()] = words[0] if words else ""
    registry = json.loads(read_remote("/etc/finance-studio/model-bindings.v1.json"))
    selected = [item for item in registry["bindings"] if item.get("enabled") is True and "deepseek" in item.get("modelName", "").lower()]
    if not selected: raise ValueError("ENABLED_DEEPSEEK_BINDING_REQUIRED")
    binding = selected[0]
    fields = ("modelRef", "revision", "enabled", "protocol", "provider", "modelName", "urlEnvironment", "keyEnvironment")
    public = {"schemaVersion": registry["schemaVersion"], "bindings": [{name: binding[name] for name in fields}]}
    environment = {binding[name]: values[binding[name]] for name in ("urlEnvironment", "keyEnvironment")}
    if not all(environment.values()): raise ValueError("MANAGED_MODEL_CONFIGURATION_EMPTY")
    return public, binding["modelRef"], environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--native-bundle", type=Path, required=True)
    parser.add_argument("--host-qualification", type=Path, required=True)
    parser.add_argument("--model-timeout", type=int, default=45)
    args = parser.parse_args()
    os.umask(0o077)
    if args.output.exists(): raise ValueError("OUTPUT_EXISTS")
    try:
        registry, reference, connection = managed_connection()
    except Exception:
        raise RuntimeError("MANAGED_MODEL_READ_FAILED") from None
    with tempfile.TemporaryDirectory(prefix="agentscope-managed-model-") as directory:
        path = Path(directory) / "model-bindings.json"
        path.write_text(json.dumps(registry))
        path.chmod(0o600)
        command = [sys.executable, str(Path(__file__).with_name("verify_agentscope_host_live.py")), str(args.output),
                   "--candidate", str(args.candidate), "--harness", str(args.harness),
                   "--native-bundle", str(args.native_bundle), "--host-qualification", str(args.host_qualification),
                   "--model-registry", str(path), "--model-ref", reference, "--model-timeout", str(args.model_timeout)]
        result = subprocess.run(command, env=dict(os.environ, **connection))
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
