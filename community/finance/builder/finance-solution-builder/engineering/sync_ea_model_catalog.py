"""Operator-only metadata discovery. It never resolves EA secrets into Builder bindings."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import uuid


def merge_metadata(models, previous):
    if not isinstance(models, list): raise ValueError("EA_METADATA_INVALID")
    result, used = [], set()
    for model in models:
        if (not isinstance(model, dict) or set(model) != {"id", "name", "modelName", "provider"}
                or any(not isinstance(v, str) or not v or len(v) > 180 or
                       any(c in v for c in ("\n", "\r", "http://", "https://", "sk-")) for v in model.values())
                or model["id"] == "__auto" or model["modelName"] == "__auto"):
            raise ValueError("EA_METADATA_INVALID")
        matches = [m for m in previous if m["eaCatalogId"] == model["id"] and m["provider"] == model["provider"]]
        if not matches:
            matches = [m for m in previous if m["provider"] == model["provider"] and m["modelName"] == model["modelName"]]
        if len(matches) > 1: raise ValueError("EA_CONNECTION_IDENTITY_AMBIGUOUS")
        ref = matches[0]["modelRef"] if matches else "model-" + str(uuid.uuid4())
        if ref in used: raise ValueError("EA_CONNECTION_IDENTITY_AMBIGUOUS")
        used.add(ref)
        result.append({"modelRef": ref, "displayName": model["name"], "provider": model["provider"],
                       "modelName": model["modelName"], "eaCatalogId": model["id"], "eaConfigured": True})
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ea-repo", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    args = parser.parse_args()
    ea = args.ea_repo.resolve()
    script = ('import {listSelectableJiuwenModels} from "./server/_core/jiuwenswarm-model-admin.ts";'
              'listSelectableJiuwenModels({force:true}).then(ms=>process.stdout.write(JSON.stringify(ms.map(m=>'
              '({id:m.id,name:m.name,modelName:m.modelName,provider:m.provider}))))).catch(()=>process.exit(1));')
    try:
        run = subprocess.run([str(ea / "node_modules/.bin/tsx"), "-e", script], cwd=ea,
                             capture_output=True, text=True, timeout=70)
    except (OSError, subprocess.TimeoutExpired):
        raise SystemExit("EA_METADATA_READ_FAILED") from None
    if run.returncode: raise SystemExit("EA_METADATA_READ_FAILED")
    try:
        metadata = json.loads(run.stdout)
        old = json.loads(args.catalog.read_text()) if args.catalog.exists() else {"models": []}
        models = merge_metadata(metadata, old["models"])
        from datetime import datetime, timezone
        document = {"schemaVersion": "finance-model-catalog.v1",
                    "source": {"kind": "EA_READONLY_METADATA", "observedAt": datetime.now(timezone.utc).isoformat(),
                               "inferenceVerified": False}, "models": models}
        from finance_solution_builder.model_registry import ModelRegistry
        encoded = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
        with tempfile.TemporaryDirectory(prefix="finance-metadata-validation-") as staging:
            staged = Path(staging) / "catalog.json"
            staged.write_text(encoded)
            ModelRegistry({"FH_MODEL_CATALOG": str(staged)})
        temporary = args.catalog.with_suffix(".tmp")
        temporary.write_text(encoded)
        temporary.replace(args.catalog)
    except (ValueError, KeyError, OSError):
        raise SystemExit("EA_METADATA_IMPORT_REJECTED") from None
    print(json.dumps({"configuredModels": len(models), "runtimeBindingsChanged": False, "modelCalls": 0}))


if __name__ == "__main__": main()
