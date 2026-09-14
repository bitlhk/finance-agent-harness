"""Offline cross-check of candidate companion declarations and existing Pack files."""
from pathlib import Path
import json

import jsonschema
import yaml


def verify(root):
    catalog = json.loads((root / "catalog/finance-assets.v1.json").read_text())
    schema = json.loads((root / "contracts/json-schema/catalog/finance-component-assembly.v0.1.schema.json").read_text())
    validator = jsonschema.Draft202012Validator(schema)
    declarations, packs = {}, {}
    for group, kind in (("capabilityPacks", "capability-pack"), ("providers", "provider")):
        for asset in catalog[group]:
            if not asset["source"].startswith("repo://"):
                continue
            path = (root / asset["source"][7:]).resolve()
            if not path.is_relative_to(root.resolve()):
                raise ValueError("SOURCE_OUTSIDE_ROOT")
            file = path / "assembly.v0.1.json"
            if not file.exists():
                continue
            declaration = json.loads(file.read_text())
            validator.validate(declaration)
            identity = (asset["id"], asset["version"])
            assert declaration["kind"] == kind
            assert declaration["owner"] == {"id": identity[0], "version": identity[1]}
            declarations[(kind, *identity)] = declaration
            if kind == "capability-pack":
                pack = yaml.safe_load((path / "pack.yaml").read_text())
                assert (pack["packId"], pack["version"]) == identity
                assert {c["capabilityId"] for c in pack["capabilities"]} == set(asset["capabilities"])
                packs[identity] = pack
                for ref in declaration["defaultDomainPacks"]:
                    assert sum(d["id"] == ref["id"] and d["version"] == ref["version"] for d in catalog["domainPacks"]) == 1
    assert packs and any(key[0] == "provider" for key in declarations), "NO_DECLARATIONS_TESTED"
    support_count = 0
    for (kind, pid, version), declaration in declarations.items():
        assert not validator.is_valid(dict(declaration, grantedScopes=["all"]))
        if kind != "provider":
            continue
        asset = next(a for a in catalog["providers"] if (a["id"], a["version"]) == (pid, version))
        seen = set()
        for support in declaration["supports"]:
            pack_id = (support["pack"]["id"], support["pack"]["version"])
            assert pack_id in packs, "UNQUALIFIED_PACK_SUPPORT"
            actual = {c["capabilityId"]: c["operation"] for c in packs[pack_id]["capabilities"]}
            for cid in support["capabilityIds"]:
                assert cid in asset["capabilities"] and actual.get(cid) == support["operation"]
                assert (pack_id, cid) not in seen, "DUPLICATE_PROVIDER_SUPPORT"
                seen.add((pack_id, cid))
                support_count += 1
    return {"declarations": len(declarations), "packDeclarations": len(packs),
            "supportedCapabilities": support_count, "scope": "OFFLINE_CONTRACT_ONLY",
            "runtimeInstallation": "NOT_RUN", "businessAuthorization": "NOT_RUN"}


if __name__ == "__main__":
    print(json.dumps(verify(Path(__file__).resolve().parents[1]), indent=2))
