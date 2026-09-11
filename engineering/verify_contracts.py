"""Offline JSON Schema and fixture gate; never resolves references over the network."""

import copy
import hashlib
import json
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
FORMATS = FormatChecker()


@FORMATS.checks("iana-time-zone", raises=(ZoneInfoNotFoundError, ValueError, TypeError))
def valid_zone(value):
    return isinstance(value, str) and bool(ZoneInfo(value))


def main():
    assert not FORMATS.conforms("not-a-date", "date-time"), "Install required date-time validator; do not silently skip formats"
    schemas = {}
    registry = Registry()
    for path in sorted((ROOT / "contracts/json-schema").rglob("*.schema.json")):
        schema = json.loads(path.read_text())
        Draft202012Validator.check_schema(schema)
        schemas[path.name.removesuffix(".schema.json")] = schema
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
    required = {
        "finance-context.v1",
        "capability-request.v1",
        "capability-result.v1",
        "evidence-receipt.v1",
        "provider-descriptor.v1",
        "capability-pack.v1",
        "domain-pack.v1",
        "solution-build-input.v1",
        "finance-asset-catalog.v1",
        "finance-asset-change-proposal.v1",
    }
    assert required <= schemas.keys(), f"Missing required schemas: {required - schemas.keys()}"

    def validator(name):
        return Draft202012Validator(schemas[name], registry=registry, format_checker=FORMATS)

    count = 0
    for path in sorted((ROOT / "conformance/fixtures/v1").rglob("*.json")):
        data = json.loads(path.read_text())
        name = path.name.split(".")[0] + ".v1"
        errors = list(validator(name).iter_errors(data))
        assert bool(errors) == (path.parent.name == "invalid"), f"Unexpected fixture outcome: {path} {errors}"
        count += 1

    for directory, schema_name in [("capability-packs", "capability-pack.v1"), ("domain-packs", "domain-pack.v1")]:
        for path in sorted((ROOT / directory).rglob("pack.yaml")):
            validator(schema_name).validate(yaml.safe_load(path.read_text()))
            count += 1
    wealth_fixtures = sorted((ROOT / "conformance/fixtures/wealth/v0.1").rglob("*.json"))
    assert wealth_fixtures, "Wealth contract fixtures must not be empty"
    for path in wealth_fixtures:
        errors = list(validator("wealth-facts.v0.1").iter_errors(json.loads(path.read_text())))
        assert bool(errors) == (path.parent.name == "invalid"), f"Unexpected wealth fixture: {path} {errors}"
        count += 1
    for path in sorted((ROOT / "examples/solutions").rglob("*")):
        if path.name in {"solution.yaml", "solution-without-domain.yaml", "solution-build-input.v1.json"}:
            validator("solution-build-input.v1").validate(yaml.safe_load(path.read_text()))
            count += 1

    catalog_path = ROOT / "catalog/finance-assets.v1.json"
    catalog = json.loads(catalog_path.read_text())
    catalog_schema = validator("finance-asset-catalog.v1")
    catalog_schema.validate(catalog)
    count += 1

    proposal_path = ROOT / "examples/governance/finance-asset-change-proposal.v1.json"
    proposal = json.loads(proposal_path.read_text())
    proposal_schema = validator("finance-asset-change-proposal.v1")
    proposal_schema.validate(proposal)
    count += 1
    canonical_catalog = catalog_path.read_text().encode()
    expected_catalog_digest = "sha256:" + hashlib.sha256(canonical_catalog).hexdigest()
    assert proposal["catalog"]["schemaVersion"] == catalog["schemaVersion"], (
        "Proposal catalog schema does not match the published Catalog"
    )
    assert proposal["catalog"]["catalogVersion"] == catalog["catalogVersion"], (
        "Proposal catalog version does not match the published Catalog"
    )
    assert proposal["catalog"]["catalogRevision"] == catalog["catalogRevision"], (
        "Proposal catalog revision does not match the published Catalog"
    )
    assert proposal["catalog"]["digest"] == expected_catalog_digest, (
        "Proposal catalog digest does not match the published Catalog"
    )
    count += 1

    invalid_catalog = copy.deepcopy(catalog)
    invalid_catalog["reviewInvalidField"] = True
    assert not catalog_schema.is_valid(invalid_catalog), "Catalog accepted an unknown field"
    count += 1

    invalid_proposal = copy.deepcopy(proposal)
    invalid_proposal["reviewInvalidField"] = True
    assert not proposal_schema.is_valid(invalid_proposal), "Proposal accepted an unknown field"
    count += 1

    for required_snapshot_field in ("harnessRevision", "digest"):
        invalid_proposal = copy.deepcopy(proposal)
        invalid_proposal["catalog"].pop(required_snapshot_field)
        assert not proposal_schema.is_valid(invalid_proposal), (
            f"Proposal accepted catalog without {required_snapshot_field}"
        )
        count += 1

    invalid_proposal = copy.deepcopy(proposal)
    invalid_proposal["status"] = "accepted"
    assert not proposal_schema.is_valid(invalid_proposal), "Proposal accepted a non-proposed status"
    count += 1

    external_proposal = copy.deepcopy(proposal)
    external_proposal["target"]["kind"] = "external-environment"
    external_proposal["catalog"] = None
    proposal_schema.validate(external_proposal)
    count += 1

    invalid_external_proposal = copy.deepcopy(external_proposal)
    invalid_external_proposal["catalog"] = proposal["catalog"]
    assert not proposal_schema.is_valid(invalid_external_proposal), (
        "External-environment proposal accepted a catalog snapshot"
    )
    count += 1

    invalid_asset_proposal = copy.deepcopy(proposal)
    invalid_asset_proposal["catalog"] = None
    assert not proposal_schema.is_valid(invalid_asset_proposal), (
        "Catalog-managed asset proposal accepted a null catalog snapshot"
    )
    count += 1

    catalog_capability_packs = {
        (asset["id"], asset["version"]): asset
        for asset in catalog["capabilityPacks"]
    }
    for domain_asset in catalog["domainPacks"]:
        dependency_keys = [
            (dependency["id"], dependency["version"])
            for dependency in domain_asset.get("dependencies", [])
            if dependency["kind"] == "capability-pack"
        ]
        source = domain_asset["source"]
        if not source.startswith("repo://"):
            continue
        manifest_path = ROOT / source.removeprefix("repo://") / "pack.yaml"
        manifest = yaml.safe_load(manifest_path.read_text())
        manifest_keys = [
            (reference["id"], reference["version"])
            for reference in manifest.get("capabilityPackRefs", [])
        ]
        assert dependency_keys == manifest_keys, (
            f"Catalog and Domain Pack dependencies differ: {domain_asset['id']}"
        )
        capability_owner = {}
        for dependency_key in dependency_keys:
            capability_pack = catalog_capability_packs[dependency_key]
            for capability in capability_pack.get("capabilities", []):
                previous = capability_owner.setdefault(capability, dependency_key)
                assert previous == dependency_key, (
                    f"{domain_asset['id']} installs duplicate capability {capability} "
                    f"from {previous[0]} and {dependency_key[0]}"
                )
        count += 1

    result_schema = validator("capability-result.v1")
    original = json.loads((ROOT / "conformance/fixtures/v1/valid/capability-result.need-confirmation.json").read_text())
    for status in ["NEED_CONFIRMATION", "PROCESSING", "UNKNOWN"]:
        probe = copy.deepcopy(original)
        probe["status"] = status
        probe.pop("task")
        assert not result_schema.is_valid(probe), f"Missing task accepted: {status}"
        probe["task"] = None
        assert not result_schema.is_valid(probe), f"Null task accepted: {status}"
        count += 2
    probe = copy.deepcopy(original)
    probe["task"]["confirmationRequired"] = False
    assert not result_schema.is_valid(probe), "Missing confirmation flag accepted"
    print(f"PASS: {len(schemas)} schemas, {count + 1} fixtures/assets/state probes")


if __name__ == "__main__":
    main()
