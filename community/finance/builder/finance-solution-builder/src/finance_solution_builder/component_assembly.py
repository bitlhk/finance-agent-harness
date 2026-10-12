"""Candidate component-owned admission. Declarations are not runtime grants."""
from __future__ import annotations

from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path

import jsonschema
import yaml

from finance_builder_base.runtime import require_path_within


class AssemblyRejected(ValueError):
    """A stable public error without paths, source payloads or credentials."""


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise AssemblyRejected("ASSEMBLY_DUPLICATE_FIELD")
        value[key] = item
    return value


def _identity(value):
    return value["id"], value["version"]


class ComponentAssembly:
    """Read a trusted Harness checkout; never fetch assets or execute declarations.

    The caller chooses Pack versions, Provider bindings and host support. A new
    catalog entry alone never qualifies a runtime implementation or grants access.
    """

    GROUPS = {"profile": "profiles", "capability-pack": "capabilityPacks",
              "domain-pack": "domainPacks", "provider": "providers",
              "runtime-integration": "runtimeIntegrations", "eval-pack": "evalPacks"}

    def __init__(self, root: Path):
        self.root = root.resolve()
        self.evidence = {}
        self.catalog = self._json("catalog/finance-assets.v1.json")
        self._validate(self.catalog, self._json("contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json"))
        self.schema = self._json("contracts/json-schema/catalog/finance-component-assembly.v0.1.schema.json")
        self.pack_schema = self._json("contracts/json-schema/capability/capability-pack.v1.schema.json")
        self.domain_schema = self._json("contracts/json-schema/domain/domain-pack.v1.schema.json")
        self.assets = {}
        for kind, group in self.GROUPS.items():
            for asset in self.catalog[group]:
                key = (kind, *_identity(asset))
                if key in self.assets:
                    raise AssemblyRejected("ASSEMBLY_DUPLICATE_ASSET")
                self.assets[key] = asset

    def _read(self, relative):
        path = self.root / relative
        try:
            target = require_path_within(path, self.root)
            if any(p.is_symlink() for p in (path, *path.parents) if p != self.root.parent):
                raise AssemblyRejected("ASSEMBLY_SOURCE_INVALID")
            if not target.is_file() or target.stat().st_size > 2_000_000:
                raise AssemblyRejected("ASSEMBLY_SOURCE_INVALID")
            raw = target.read_bytes()
        except (OSError, ValueError):
            raise AssemblyRejected("ASSEMBLY_SOURCE_UNAVAILABLE") from None
        self.evidence[relative] = "sha256:" + sha256(raw).hexdigest()
        return raw

    def _json(self, relative):
        try:
            return json.loads(self._read(relative), object_pairs_hook=_unique_object)
        except (UnicodeError, json.JSONDecodeError):
            raise AssemblyRejected("ASSEMBLY_JSON_INVALID") from None

    @staticmethod
    def _validate(value, schema):
        try:
            jsonschema.Draft202012Validator(schema).validate(value)
        except jsonschema.ValidationError:
            raise AssemblyRejected("ASSEMBLY_SCHEMA_INVALID") from None

    def _asset(self, kind, identity, host):
        asset = self.assets.get((kind, *_identity(identity)))
        if asset is None:
            raise AssemblyRejected("ASSEMBLY_ASSET_VERSION_UNAVAILABLE")
        compatibility = asset.get("compatibility", {})
        for key, actual in (("profiles", host["profile"]),
                            ("runtimeFamilies", host["runtime"]["family"]),
                            ("runtimeLanguages", host["runtime"]["language"])):
            if key in compatibility and actual not in compatibility[key]:
                raise AssemblyRejected("ASSEMBLY_ASSET_INCOMPATIBLE")
        return asset

    @staticmethod
    def _source(asset):
        source = asset["source"]
        if not source.startswith("repo://"):
            raise AssemblyRejected("ASSEMBLY_LOCAL_SOURCE_REQUIRED")
        path = source[len("repo://"):]
        if not path or path.startswith("/") or ".." in Path(path).parts:
            raise AssemblyRejected("ASSEMBLY_SOURCE_INVALID")
        return path

    def _declaration(self, kind, asset):
        declaration = self._json(self._source(asset) + "/assembly.v0.1.json")
        self._validate(declaration, self.schema)
        if declaration["kind"] != kind or _identity(declaration["owner"]) != _identity(asset):
            raise AssemblyRejected("ASSEMBLY_OWNER_MISMATCH")
        return declaration

    @staticmethod
    def _ref(asset, source=None):
        return {"id": asset["id"], "version": asset["version"], "source": source or asset["source"]}

    @staticmethod
    def _selected_ref(ref):
        if (not isinstance(ref, dict) or set(ref) != {"id", "version", "source"}
                or any(not isinstance(ref[k], str) or not ref[k] for k in ref)):
            raise AssemblyRejected("ASSEMBLY_REFERENCE_INVALID")

    def select(self, selections, *, host, connection_kind):
        """Select catalog identities only; obtain paths/artifacts from managed declarations."""
        if not isinstance(selections, list) or not selections or len(selections) > 32:
            raise AssemblyRejected("ASSEMBLY_SELECTION_INVALID")
        packs, providers, bindings = [], [], {}
        for selection in selections:
            if not isinstance(selection, dict) or set(selection) != {"pack", "provider"}:
                raise AssemblyRejected("ASSEMBLY_SELECTION_INVALID")
            for identity in selection.values():
                if (not isinstance(identity, dict) or set(identity) != {"id", "version"}
                        or any(not isinstance(value, str) or not value for value in identity.values())):
                    raise AssemblyRejected("ASSEMBLY_SELECTION_INVALID")
            pack = self._asset("capability-pack", selection["pack"], host)
            provider = self._asset("provider", selection["provider"], host)
            declaration = self._declaration("provider", provider)
            pack_ref = self._ref(pack)
            provider_ref = self._ref(provider, declaration["artifactRef"])
            if pack_ref in packs:
                raise AssemblyRejected("ASSEMBLY_DUPLICATE_ASSET")
            packs.append(pack_ref)
            if provider_ref not in providers:
                providers.append(provider_ref)
            try:
                contents = yaml.safe_load(self._read(self._source(pack) + "/pack.yaml"))
            except yaml.YAMLError:
                raise AssemblyRejected("ASSEMBLY_PACK_INVALID") from None
            self._validate(contents, self.pack_schema)
            for capability in contents["capabilities"]:
                key = capability["capabilityId"]
                if key in bindings:
                    raise AssemblyRejected("ASSEMBLY_DUPLICATE_CAPABILITY")
                bindings[key] = provider["id"]
        return self.resolve(packs, providers, bindings, host=host, runtime=host["runtime"],
                            connection_kind=connection_kind)

    def resolve(self, pack_refs, provider_refs, bindings, *, host, runtime, connection_kind, domain_refs=None):
        """Resolve explicit choices with no bank calls, artifact writes or fallback.

        Pack installation is atomic: all of its tools must be explicitly bound.
        The returned requiredScopes are requirements only, never granted scopes.
        Evidence identifies bytes observed by this resolution; installation still
        verifies actual artifacts and records its own Lock.
        """
        self.evidence = {k: v for k, v in self.evidence.items() if k.startswith(("catalog/", "contracts/"))}
        host_fields = {"schemaVersion", "runtime", "profile", "features", "operations", "connectionKinds"}
        routing = host.get("routingAssets") if isinstance(host, dict) else None
        routing_valid = routing is None or (isinstance(routing, dict)
            and set(routing) == {"descriptionPattern", "terminologyRefs"}
            and isinstance(routing["descriptionPattern"], str) and "{packId}" in routing["descriptionPattern"]
            and isinstance(routing["terminologyRefs"], list)
            and all(isinstance(item, str) for item in routing["terminologyRefs"]))
        if (not isinstance(host, dict) or host.get("schemaVersion") != "finance-host-support.v0.1"
                or set(host) not in (host_fields, host_fields | {"routingAssets"}) or not routing_valid
                or not isinstance(host["profile"], str) or not host["profile"]
                or not isinstance(host["runtime"], dict)
                or set(host["runtime"]) != {"family", "language", "templateRef", "integrationRef"}
                or any(not isinstance(v, str) or not v for v in host["runtime"].values())
                or any(not isinstance(host[key], list) or not host[key]
                       or any(not isinstance(item, str) for item in host[key])
                       or len(host[key]) != len(set(host[key]))
                       for key in ("features", "operations", "connectionKinds"))):
            raise AssemblyRejected("ASSEMBLY_HOST_DECLARATION_INVALID")
        if runtime != host["runtime"]:
            raise AssemblyRejected("ASSEMBLY_RUNTIME_UNSUPPORTED")
        if connection_kind not in host["connectionKinds"]:
            raise AssemblyRejected("ASSEMBLY_CONNECTION_UNSUPPORTED")
        if not isinstance(pack_refs, list) or not isinstance(provider_refs, list) or not isinstance(bindings, dict):
            raise AssemblyRejected("ASSEMBLY_SELECTION_INVALID")
        for ref in [*pack_refs, *provider_refs]:
            self._selected_ref(ref)
        if any(not isinstance(k, str) or not isinstance(v, str) for k, v in bindings.items()):
            raise AssemblyRejected("ASSEMBLY_BINDING_SET_MISMATCH")
        if (not pack_refs or not provider_refs
                or len({_identity(p) for p in pack_refs}) != len(pack_refs)
                or len({p["id"] for p in provider_refs}) != len(provider_refs)):
            raise AssemblyRejected("ASSEMBLY_SELECTION_INVALID")
        providers = {}
        for ref in provider_refs:
            asset = self._asset("provider", ref, host)
            declaration = self._declaration("provider", asset)
            if ref["source"] != declaration["artifactRef"]:
                raise AssemblyRejected("ASSEMBLY_PROVIDER_ARTIFACT_MISMATCH")
            if declaration["connectionKind"] != connection_kind:
                raise AssemblyRejected("ASSEMBLY_PROVIDER_CONNECTION_MISMATCH")
            providers[ref["id"]] = (asset, declaration)
        caps, packs, domains, scopes = {}, [], {}, set()
        defaults = []
        selected_assets = {}
        for ref in pack_refs:
            asset = self._asset("capability-pack", ref, host)
            if ref["source"] != asset["source"]:
                raise AssemblyRejected("ASSEMBLY_ASSET_SOURCE_MISMATCH")
            selected_assets[("capability-pack", *_identity(asset))] = asset
            declaration = self._declaration("capability-pack", asset)
            if not set(declaration["requiredFeatures"]) <= set(host["features"]):
                raise AssemblyRejected("ASSEMBLY_HOST_FEATURE_MISSING")
            try:
                pack = yaml.safe_load(self._read(self._source(asset) + "/pack.yaml"))
            except yaml.YAMLError:
                raise AssemblyRejected("ASSEMBLY_PACK_INVALID") from None
            self._validate(pack, self.pack_schema)
            if (pack["packId"], pack["version"]) != _identity(asset):
                raise AssemblyRejected("ASSEMBLY_PACK_VERSION_MISMATCH")
            actual = [c["capabilityId"] for c in pack["capabilities"]]
            if len(set(actual)) != len(actual) or set(actual) != set(asset.get("capabilities", [])):
                raise AssemblyRejected("ASSEMBLY_CATALOG_CAPABILITY_MISMATCH")
            for cap in pack["capabilities"]:
                cid = cap["capabilityId"]
                if cid in caps:
                    raise AssemblyRejected("ASSEMBLY_DUPLICATE_CAPABILITY")
                if cap["operation"] not in host["operations"] or cap["operation"].lower() + ".v1" not in host["features"]:
                    raise AssemblyRejected("ASSEMBLY_OPERATION_UNSUPPORTED")
                if bindings.get(cid) not in providers:
                    raise AssemblyRejected("ASSEMBLY_BINDING_MISSING")
                provider, support = providers[bindings[cid]]
                matches = [s for s in support["supports"] if _identity(s["pack"]) == _identity(asset)
                           and cid in s["capabilityIds"] and s["operation"] == cap["operation"]]
                if cid not in provider.get("capabilities", []) or len(matches) != 1:
                    raise AssemblyRejected("ASSEMBLY_PROVIDER_SUPPORT_MISMATCH")
                caps[cid] = deepcopy(cap)
                scopes.update(cap["requiredScopes"])
            packs.append(self._ref(asset))
            for identity in declaration["defaultDomainPacks"]:
                defaults.append({"identity": identity, "capabilityIds": actual})
        chosen_domains = [item["identity"] for item in defaults] if domain_refs is None else domain_refs
        if not isinstance(chosen_domains, list):
            raise AssemblyRejected("ASSEMBLY_REFERENCE_INVALID")
        for identity in chosen_domains:
            domain = self._asset("domain-pack", identity, host)
            if "source" in identity and identity["source"] != domain["source"]:
                raise AssemblyRejected("ASSEMBLY_ASSET_SOURCE_MISMATCH")
            if domain["id"] in domains and domains[domain["id"]]["version"] != domain["version"]:
                raise AssemblyRejected("ASSEMBLY_DOMAIN_VERSION_CONFLICT")
            domains[domain["id"]] = domain
            selected_assets[("domain-pack", *_identity(domain))] = domain
        if set(bindings) != set(caps) or set(bindings.values()) != set(providers):
            raise AssemblyRejected("ASSEMBLY_BINDING_SET_MISMATCH")
        for asset, _ in providers.values():
            selected_assets[("provider", *_identity(asset))] = asset
        for asset in domains.values():
            if not set(asset.get("requiresCapabilities", [])) <= set(caps):
                raise AssemblyRejected("ASSEMBLY_METHOD_CAPABILITY_MISSING")
            try:
                pack = yaml.safe_load(self._read(self._source(asset) + "/pack.yaml"))
            except yaml.YAMLError:
                raise AssemblyRejected("ASSEMBLY_METHOD_INVALID") from None
            self._validate(pack, self.domain_schema)
            if (pack.get("packId"), pack.get("version")) != _identity(asset):
                raise AssemblyRejected("ASSEMBLY_METHOD_VERSION_MISMATCH")
        for asset in selected_assets.values():
            for dep in asset.get("dependencies", []):
                if (dep["kind"], *_identity(dep)) not in selected_assets:
                    raise AssemblyRejected("ASSEMBLY_DEPENDENCY_MISSING")
        return {"capabilityPackRefs": packs,
                "providerRefs": [self._ref(asset, declaration["artifactRef"]) for asset, declaration in providers.values()],
                "domainPackRefs": [self._ref(asset) for asset in domains.values()],
                "capabilityBindings": deepcopy(bindings),
                "requiredScopes": sorted(scopes), "capabilities": list(caps.values()),
                "defaultMethods": deepcopy(defaults),
                "evidence": {"schemaVersion": "finance-assembly-admission.v0.1",
                             "files": dict(sorted(self.evidence.items())),
                             "hostSupportSha256": "sha256:" + sha256(json.dumps(host, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
                             "authorization": "RUNTIME_CHECK_REQUIRED",
                             "installation": "NOT_OBSERVED"}}
