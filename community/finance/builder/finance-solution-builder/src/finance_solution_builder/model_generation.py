"""Closed, credential-free generation configuration frozen into model selection."""
import copy
import hashlib
import json
import math


class GenerationError(ValueError):
    def __init__(self):
        super().__init__("EVAL_GENERATION_INVALID")


def validate_generation(value):
    if not isinstance(value, dict) or set(value) != {"schemaVersion", "temperature", "top_p", "max_tokens"}:
        raise GenerationError()
    if value["schemaVersion"] != "finance.eval-generation.v1":
        raise GenerationError()
    temperature, top_p, maximum = value["temperature"], value["top_p"], value["max_tokens"]
    if (type(temperature) not in (int, float) or not 0 <= temperature <= 2
            or type(top_p) not in (int, float) or not 0 < top_p <= 1
            or not math.isfinite(temperature) or not math.isfinite(top_p)
            or type(maximum) is not int or not 1 <= maximum <= 32768):
        raise GenerationError()
    return {"schemaVersion": value["schemaVersion"], "temperature": float(temperature),
            "top_p": float(top_p), "max_tokens": maximum}


def freeze_generation(snapshot, binding):
    if (not isinstance(snapshot, dict) or not isinstance(binding, dict)
            or set(binding) != {"modelRef", "parameters"}
            or not isinstance(binding["modelRef"], str)
            or binding["modelRef"] not in snapshot.get("selectedModelRefs", [])
            or not any(c.get("modelRef") == binding["modelRef"] for c in snapshot.get("connections", []))):
        raise GenerationError()
    frozen = copy.deepcopy(snapshot)
    frozen["modelGeneration"] = {"modelRef": binding["modelRef"], "parameters": validate_generation(binding["parameters"])}
    if "modelGeneration" in snapshot and snapshot["modelGeneration"] != frozen["modelGeneration"]:
        raise GenerationError()
    return frozen


def generation_json(binding):
    return json.dumps(binding, sort_keys=True, ensure_ascii=True, separators=(",", ":"), allow_nan=False)


def generation_digest(binding):
    return hashlib.sha256(generation_json(binding).encode()).hexdigest()
