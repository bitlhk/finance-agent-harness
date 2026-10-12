"""Deterministic Skill dependency hints. Suggestions never grant or bind tools."""
from __future__ import annotations

import re
import json

import yaml


def _metadata(source):
    if not source.startswith("---\n"): return {}
    match = re.search(r"\n---(?:\n|$)", source[4:])
    if not match: return {}
    try:
        value = yaml.safe_load(source[4:4 + match.start()])
        return value if isinstance(value, dict) else {}
    except yaml.YAMLError:
        return {}


def _declared(value):
    raw = value.get("allowed-tools", value.get("allowedTools", []))
    if isinstance(raw, str): return [item for item in re.split(r"[,\s]+", raw) if item]
    return [item for item in raw if isinstance(item, str) and item.strip()] if isinstance(raw, list) else []


def analyze_skill(source, harness):
    catalog = json.loads(
        (harness / "catalog/finance-assets.v1.json").read_text(encoding="utf-8")
    )
    tools = {}
    for asset in catalog["capabilityPacks"]:
        path = harness / asset["source"].removeprefix("repo://") / "pack.yaml"
        if not path.is_file(): continue
        pack = yaml.safe_load(path.read_text(encoding="utf-8"))
        for item in pack.get("capabilities", []):
            tools.setdefault(item["toolName"], []).append({"capabilityPack":{"id":asset["id"],"version":asset["version"]},
                "capabilityId":item["capabilityId"], "toolName":item["toolName"],
                "source":"catalog://"+catalog["catalogRevision"]+"/"+asset["id"],
                "status":"MATCHED", "locations":[]})
    metadata = _metadata(source)
    declarations = _declared(metadata)
    lines = source.splitlines()
    mentioned = {name for name in tools if any(re.search(r"(?<![A-Za-z0-9_])"+re.escape(name)+r"(?![A-Za-z0-9_])", line) for line in lines)}
    explicit_candidates = []
    for line in lines:
        explicit_candidates.extend(re.findall(r"`([A-Za-z][A-Za-z0-9_.-]{2,127})`", line))
        explicit_candidates.extend(re.findall(r"(?:调用|使用工具)\s*[`'\"]?([A-Za-z][A-Za-z0-9_.-]{2,127})", line))
    names = list(dict.fromkeys([*declarations, *sorted(mentioned), *explicit_candidates]))
    dependencies, unknown = [], []
    for name in names:
        locations = [{"file":"SKILL.md", "line":index+1,
                      "kind":"DECLARATION" if name in declarations and index < 20 else "BODY_REFERENCE"}
                     for index,line in enumerate(lines) if name in line]
        matches = tools.get(name, [])
        if len(matches) == 1: dependencies.append({**matches[0], "locations":locations})
        elif len(matches) > 1:
            dependencies.append({"toolName":name, "status":"AMBIGUOUS", "locations":locations,
                                 "candidates":[item["capabilityId"] for item in matches]})
        else: unknown.append({"toolName":name, "status":"PENDING_INTEGRATION", "locations":locations})
    references = []
    for index, line in enumerate(lines):
        for value in re.findall(r"(?:references|scripts)/[^\s\)\]`<>\"']+", line):
            references.append({"path":value.rstrip(".,;:!?。；，：！？"), "file":"SKILL.md", "line":index+1,
                               "status":"ANALYZE_ONLY" if value.startswith("scripts/") else "REFERENCED"})
    return {"schemaVersion":"finance-skill-dependency-analysis.v0.1", "analyzer":"BUILDER_STATIC",
            "dependencies":dependencies, "unknownTools":unknown, "resourceReferences":references,
            "uncertainties":(["Skill 未声明或提及可识别工具；需要维护者确认是否为纯方法资产。"] if not names else [])
                + (["脚本仅作为静态内容分析，当前工作台不会执行。"] if any(r["status"]=="ANALYZE_ONLY" for r in references) else []),
            "professionalQuality":"NOT_EVALUATED", "evaluationService":"NOT_INVOKED"}
