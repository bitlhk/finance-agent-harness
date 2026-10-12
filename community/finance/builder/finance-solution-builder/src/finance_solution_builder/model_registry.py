"""Server-owned model identities. Discovery never grants a runtime connection."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
from urllib.parse import urlsplit

CATALOG = Path(__file__).resolve().parents[2] / "examples/model-catalog/ea-models.json"
REF = re.compile(r"model-[a-z0-9-]{1,80}\Z")
ENV = re.compile(r"FH_CONN_[A-Z0-9_]{1,100}\Z")


class ModelRegistryError(ValueError):
    pass


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()


def load_json(path):
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (OSError, ValueError, TypeError):
        raise ModelRegistryError("MODEL_REGISTRY_UNAVAILABLE") from None


class ModelRegistry:
    def __init__(self, environment=None):
        self.env = os.environ if environment is None else environment
        document = load_json(self.env.get("FH_MODEL_CATALOG", CATALOG))
        if (set(document) != {"schemaVersion", "source", "models"} or document["schemaVersion"] != "finance-model-catalog.v1"
                or not isinstance(document["source"], dict) or not isinstance(document["models"], list)):
            raise ModelRegistryError("MODEL_CATALOG_INVALID")
        self.models = {}
        fields = {"modelRef", "displayName", "provider", "modelName", "eaCatalogId", "eaConfigured"}
        for item in document["models"]:
            if (not isinstance(item, dict) or set(item) != fields or not isinstance(item.get("modelRef"), str)
                    or not REF.fullmatch(item["modelRef"])
                    or item["modelRef"] in self.models or type(item["eaConfigured"]) is not bool
                    or any(not isinstance(item[k], str) or len(item[k]) > 180 or not item[k]
                           or any(c in item[k] for c in ("\n", "\r", "http://", "https://", "sk-"))
                           for k in fields - {"eaConfigured"})):
                raise ModelRegistryError("MODEL_CATALOG_INVALID")
            self.models[item["modelRef"]] = dict(item)
        self.bindings = {}
        location = self.env.get("FH_MODEL_REGISTRY")
        if location:
            record = load_json(location)
            if (set(record) != {"schemaVersion", "bindings"} or record["schemaVersion"] != "finance-model-bindings.v1"
                    or not isinstance(record["bindings"], list)):
                raise ModelRegistryError("MODEL_REGISTRY_INVALID")
            required = {"modelRef", "revision", "enabled", "protocol", "provider", "modelName", "urlEnvironment", "keyEnvironment"}
            for binding in record["bindings"]:
                if (not isinstance(binding, dict) or set(binding) != required or not isinstance(binding.get("modelRef"), str)
                        or binding["modelRef"] not in self.models
                        or binding["modelRef"] in self.bindings or type(binding["enabled"]) is not bool
                        or any(not isinstance(binding[k], str) for k in required - {"enabled"})
                        or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", str(binding["revision"]))
                        or not all(isinstance(binding[k], str) and ENV.fullmatch(binding[k]) for k in ("urlEnvironment", "keyEnvironment"))):
                    raise ModelRegistryError("MODEL_REGISTRY_INVALID")
                model = self.models[binding["modelRef"]]
                if binding["modelName"] != model["modelName"] or binding["provider"] != model["provider"]:
                    raise ModelRegistryError("MODEL_BINDING_IDENTITY_MISMATCH")
                self.bindings[binding["modelRef"]] = dict(binding)
        # Includes only public metadata and opaque configuration versions, never a key.
        self.catalog_revision = fingerprint(self.public_models())

    def version(self, binding):
        # This is identical to Java's length-prefixed canonicalization. Key rotation is allowed.
        fields = [binding[k] for k in ("modelRef", "revision", "protocol", "provider", "modelName", "urlEnvironment", "keyEnvironment")]
        fields.append(self.env.get(binding["urlEnvironment"], ""))
        data = b"".join(str(len(v.encode())).encode() + b":" + v.encode() for v in fields)
        return "sha256:" + hashlib.sha256(data).hexdigest()

    def status(self, ref):
        if ref not in self.models:
            raise ModelRegistryError("MODEL_UNKNOWN")
        binding = self.bindings.get(ref)
        if binding is None:
            return "UNBOUND"
        if not binding["enabled"]:
            return "DISABLED"
        if binding["protocol"] != "OPENAI_CHAT_COMPLETIONS" or binding["provider"] not in {"OpenAI", "OpenRouter"}:
            return "PROTOCOL_UNSUPPORTED"
        url, key = self.env.get(binding["urlEnvironment"], ""), self.env.get(binding["keyEnvironment"], "")
        try:
            parsed = urlsplit(url)
            _ = parsed.port
            valid = (bool(parsed.hostname) and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
                     and not any(c.isspace() for c in url) and (parsed.scheme == "https" or
                         parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost"}))
        except ValueError:
            valid = False
        return "CONFIGURED_UNTESTED" if valid and key.strip() else "CONFIGURATION_MISSING"

    def public_models(self):
        return [dict(model, bindingStatus=self.status(ref),
                     configVersion=self.version(self.bindings[ref]) if ref in self.bindings else None,
                     protocol=("OPENAI_CHAT_COMPLETIONS" if self.bindings[ref]["protocol"] == "OPENAI_CHAT_COMPLETIONS"
                               else "UNSUPPORTED") if ref in self.bindings else "UNVERIFIED",
                     adapterStatus="OPENAI_CLIENT_AVAILABLE_VALIDATION_PENDING",
                     liveValidation="NOT_RUN") for ref, model in sorted(self.models.items())]

    def snapshot(self, selected, default, revision):
        if revision != self.catalog_revision:
            raise ModelRegistryError("MODEL_CATALOG_CHANGED")
        if not selected or len(selected) != len(set(selected)) or default not in selected:
            raise ModelRegistryError("MODEL_SELECTION_INVALID")
        for ref in selected:
            if self.status(ref) in {"DISABLED", "PROTOCOL_UNSUPPORTED"}:
                raise ModelRegistryError("MODEL_NOT_ENABLED")
        return {"schemaVersion": "finance-model-selection.v1", "catalogRevision": revision,
                "selectedModelRefs": sorted(selected), "defaultModelRef": default,
                "connections": [{"modelRef": ref, "configVersion": self.version(self.bindings[ref]) if ref in self.bindings else None,
                                 "modelName": self.models[ref]["modelName"], "provider": self.models[ref]["provider"]}
                                for ref in sorted(selected)]}

    def check_frozen(self, snapshot):
        for item in snapshot["connections"]:
            ref = item["modelRef"]
            if self.status(ref) != "CONFIGURED_UNTESTED":
                raise ModelRegistryError("MODEL_CONNECTION_UNAVAILABLE")
            if self.version(self.bindings[ref]) != item["configVersion"]:
                raise ModelRegistryError("MODEL_CONFIGURATION_CHANGED")

    def child_environment(self, snapshot):
        self.check_frozen(snapshot)
        result = {"FH_MODEL_REGISTRY": str(Path(self.env["FH_MODEL_REGISTRY"]).resolve())}
        for ref in snapshot["selectedModelRefs"]:
            for field in ("urlEnvironment", "keyEnvironment"):
                key = self.bindings[ref][field]
                result[key] = self.env[key]
        if "modelGeneration" in snapshot:
            from .model_generation import freeze_generation, generation_json
            frozen = freeze_generation(snapshot, snapshot["modelGeneration"])
            result["FH_MODEL_GENERATION_JSON"] = generation_json(frozen["modelGeneration"])
        return result
