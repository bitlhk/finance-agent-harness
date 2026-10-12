"""Optional, pinned development metadata. Never runs commands or installs prompt text."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime
import json
from pathlib import Path
import re

import jsonschema
import yaml

from .assets import tree_digest
from .experience_contracts import ApiError
from .method_assets import canonical, sha

INDEX = "metadata/development-support/index.v0.1.json"
SCHEMA = "finance-component-development-support.v0.1"
INDEX_SCHEMA = "finance-development-support-index.v0.1"
ENVIRONMENT = ("runtimeId", "integratorId", "profileId", "hostId", "testDataId")


def _path(root, source):
    if not isinstance(source, str) or not source.startswith("repo://"):
        raise ApiError("DEVELOPMENT_SUPPORT_REFERENCE_INVALID", 409)
    relative = source.removeprefix("repo://")
    if not relative or any(p in {"", ".", ".."} for p in relative.split("/")) or "\\" in relative:
        raise ApiError("DEVELOPMENT_SUPPORT_REFERENCE_INVALID", 409)
    root = Path(root).resolve()
    path = root / relative
    if (not path.resolve().is_relative_to(root)
            or any(p.is_symlink() for p in (path, *path.parents) if p != root)):
        raise ApiError("DEVELOPMENT_SUPPORT_REFERENCE_INVALID", 409)
    return path


def _read(path):
    try:
        return path.read_bytes()
    except OSError:
        raise ApiError("DEVELOPMENT_SUPPORT_UNAVAILABLE", 409) from None


def _json(raw):
    try:
        return json.loads(raw)
    except (ValueError, UnicodeError):
        raise ApiError("DEVELOPMENT_SUPPORT_INVALID", 409) from None


def _validate(harness, value, schema_name=SCHEMA, definition=None):
    schema = _json(_read(harness / "contracts/json-schema/catalog" / (schema_name + ".schema.json")))
    if definition:
        schema = {"$ref": "#/$defs/" + definition, "$defs": schema["$defs"]}
    try:
        jsonschema.Draft202012Validator(schema).validate(value)
    except jsonschema.ValidationError:
        raise ApiError("DEVELOPMENT_SUPPORT_INVALID", 409) from None


def _fixed_subject(harness, subject):
    _validate(harness, subject, definition="subject")
    path = _path(harness, subject["source"])
    if not path.exists() or any(p.is_symlink() for p in path.rglob("*")):
        raise ApiError("DEVELOPMENT_SUPPORT_SUBJECT_UNAVAILABLE", 409)
    if tree_digest(path) != subject["digest"]:
        raise ApiError("DEVELOPMENT_SUPPORT_SUBJECT_DIGEST_MISMATCH", 409)
    if subject["kind"] in {"domain-pack", "capability-pack"}:
        try:
            pack = yaml.safe_load(_read(path / "pack.yaml"))
        except yaml.YAMLError:
            raise ApiError("DEVELOPMENT_SUPPORT_INVALID", 409) from None
        if not isinstance(pack, dict) or (pack.get("packId"), pack.get("version")) != (subject["id"], subject["version"]):
            raise ApiError("DEVELOPMENT_SUPPORT_SUBJECT_VERSION_MISMATCH", 409)
        capabilities = pack.get("capabilities", [])
        return [c["capabilityId"] for c in capabilities] if subject["kind"] == "capability-pack" else capabilities
    return []


def _scope_status(declared, selected):
    if not set(declared["capabilityIds"]) <= set(selected["capabilityIds"]):
        return "SCOPE_MISMATCH"
    if any(key in declared and key in selected and declared[key] != selected[key] for key in ENVIRONMENT):
        return "SCOPE_MISMATCH"
    if any(key in declared and key not in selected for key in ENVIRONMENT):
        return "SCOPE_UNRESOLVED"
    return "AVAILABLE"


def _unique(values):
    return [deepcopy(v) for i, v in enumerate(values) if v not in values[:i]]


def _projection(subjects, scope, matches, skipped, records):
    case_sets = _unique([value["methodCases"] for _, value in matches])
    if len(case_sets) > 1:
        raise ApiError("DEVELOPMENT_SUPPORT_CONFLICT", 409)
    cases = case_sets[0] if case_sets else []
    status = ("AVAILABLE" if cases else "CASES_REQUIRED") if matches else (
        "SCOPE_UNRESOLVED" if "SCOPE_UNRESOLVED" in skipped else
        "SCOPE_MISMATCH" if skipped else "NOT_DECLARED")
    warnings = []
    if not cases:
        warnings.append({"code": "ASSET_CASE_REQUIRED", "message": "请补充验证案例"})
    if "SCOPE_UNRESOLVED" in skipped:
        warnings.append({"code": "DEVELOPMENT_SUPPORT_SCOPE_UNRESOLVED", "message": "部分案例需先选择验证环境"})
    if "SCOPE_MISMATCH" in skipped:
        warnings.append({"code": "DEVELOPMENT_SUPPORT_SCOPE_MISMATCH", "message": "部分声明不适用于当前验证环境"})
    result = {
        "schemaVersion": "finance-development-support-projection.v0.1",
        "status": status, "message": "有案例，未运行" if cases else "请补充验证案例",
        "subjects": deepcopy(subjects), "scope": deepcopy(scope),
        "methodCases": cases, "casesDigest": sha(canonical(cases)),
        "sources": _unique([ref for ref, _ in matches]),
        "caseRefs": _unique([r for _, v in matches for r in v["caseRefs"]]),
        "commands": _unique([r for _, v in matches for r in v["commands"]]),
        "warnings": warnings,
    }
    result["validation"] = project_verification(result, records)
    return result


def load_development_support(harness, subject, scope=None, *, records=()):
    """Load one immutable subject; absence of the optional index works with old Harnesses."""
    return load_subjects(harness, [subject], scope, records=records)


def load_subjects(harness, subjects, scope=None, *, records=()):
    harness = Path(harness)
    derive_scope = scope is None
    scope = deepcopy(scope) if scope is not None else {"capabilityIds": []}
    matches, skipped = [], []
    index_path = harness / INDEX
    if not index_path.exists():
        return _projection(subjects, scope, matches, skipped, records)
    capabilities = []
    for subject in subjects:
        capabilities.extend(_fixed_subject(harness, subject))
    if derive_scope and capabilities:
        scope["capabilityIds"] = sorted(set(capabilities))
    _validate(harness, scope, definition="scope")
    index = _json(_read(_path(harness, "repo://" + INDEX)))
    _validate(harness, index, INDEX_SCHEMA)
    seen = {}
    for ref in index["declarations"]:
        if ref["source"] in seen and seen[ref["source"]] != ref["digest"]:
            raise ApiError("DEVELOPMENT_SUPPORT_CONFLICT", 409)
        seen[ref["source"]] = ref["digest"]
        raw = _read(_path(harness, ref["source"]))
        if sha(raw) != ref["digest"]:
            raise ApiError("DEVELOPMENT_SUPPORT_DIGEST_MISMATCH", 409)
        value = _json(raw)
        _validate(harness, value)
        declared = value["subject"]
        identities = [s for s in subjects if all(s[k] == declared[k] for k in ("kind", "id", "version", "source"))]
        if not identities:
            continue
        if any(s["digest"] != declared["digest"] for s in identities):
            raise ApiError("DEVELOPMENT_SUPPORT_SUBJECT_DIGEST_MISMATCH", 409)
        status = _scope_status(value["scope"], scope)
        if status != "AVAILABLE":
            skipped.append(status)
            continue
        for case_ref in value["caseRefs"]:
            if sha(_read(_path(harness, case_ref["source"]))) != case_ref["digest"]:
                raise ApiError("DEVELOPMENT_SUPPORT_CASE_DIGEST_MISMATCH", 409)
        for command in value["commands"]:
            if command["workingDirectory"] != "repo://":
                _path(harness, command["workingDirectory"])
        matches.append((ref, value))
    return _projection(subjects, scope, matches, skipped, records)


def case_provenance(support):
    return {"schemaVersion": "finance-method-case-provenance.v0.1",
            "kind": "COMPONENT_DECLARATION" if support["sources"] else "NO_APPLICABLE_DECLARATION",
            **{key: deepcopy(support[key]) for key in ("subjects", "scope", "sources", "casesDigest")}}


def project_verification(support, records=()):
    """Project server-owned existing reports plus fixed bindings, never client/platform claims.

    Each record has subject, casesDigest, scope, executedAt, reportRef and report.
    reportRef.digest is SHA-256 of canonical report JSON. The caller must have
    checked owner/access and loaded the report from its managed evidence store.
    Legacy reports missing a precise binding remain unverified, not backfilled.
    Owner releases are projected separately by method_verification; their trials
    never enter this base-component status. Integrator scope, when
    provided, is the exact loaded integration artifact reference, not its label.
    """
    projected = []
    for record in records:
        if not isinstance(record, dict):
            projected.append({"status": "EVIDENCE_INCOMPLETE"})
            continue
        status = _record_status(support, record)
        projected.append({"status": status, **{k: deepcopy(record[k]) for k in
                          ("subject", "scope", "executedAt", "reportRef") if k in record}})
    statuses = [r["status"] for r in projected]
    covered = bool(support["subjects"]) and all(
        any(r["status"] == "PASS" and r.get("subject") == subject for r in projected)
        for subject in support["subjects"])
    status = ("PASS" if covered else "EVIDENCE_INCOMPLETE" if "PASS" in statuses
              else statuses[0] if statuses else "NOT_RUN")
    return {"status": status, "reason": {
        "PASS": "该版本运行检查通过，专业质量未评估", "NOT_RUN": "有案例，未运行" if support["methodCases"] else "请补充验证案例",
        "VERSION_MISMATCH": "证据属于其他版本", "DIGEST_MISMATCH": "证据属于旧摘要",
        "SUBJECT_MISMATCH": "证据属于其他组件", "CASE_MISMATCH": "证据案例不匹配",
        "ENVIRONMENT_MISMATCH": "当前验证环境未验证", "ENVIRONMENT_UNRESOLVED": "请先选择验证环境",
        "UNSCOPED_REPORT": "不能将平台汇总或未绑定报告作为组件通过证据",
        "REPORT_DIGEST_MISMATCH": "报告来源摘要不匹配", "EVIDENCE_INCOMPLETE": "验证证据不完整",
        "FAIL": "该版本运行检查未通过",
        "RUNTIME_MISMATCH": "报告内实际Runtime或Integrator与选择不符",
    }[status], "professionalQuality": "NOT_EVALUATED", "records": projected}


def _record_status(support, record):
    report = record.get("report", {})
    if not isinstance(report, dict) or report.get("schemaVersion") != "private-method-trial.v0.1":
        return "UNSCOPED_REPORT"
    subject = record.get("subject", {})
    if not isinstance(subject, dict):
        return "EVIDENCE_INCOMPLETE"
    related = [s for s in support["subjects"] if all(s[k] == subject.get(k) for k in ("kind", "id", "source"))]
    if not related:
        return "SUBJECT_MISMATCH"
    if not any(s["version"] == subject.get("version") for s in related):
        return "VERSION_MISMATCH"
    if subject not in related:
        return "DIGEST_MISMATCH"
    if not support["methodCases"] or record.get("casesDigest") != support["casesDigest"]:
        return "CASE_MISMATCH"
    scope = record.get("scope", {})
    if any(not support["scope"].get(k) for k in ENVIRONMENT):
        return "ENVIRONMENT_UNRESOLVED"
    if scope != support["scope"]:
        return "ENVIRONMENT_MISMATCH"
    reference = record.get("reportRef", {})
    if not isinstance(reference, dict) or reference.get("digest") != sha(canonical(report)) or not reference.get("source"):
        return "REPORT_DIGEST_MISMATCH"
    try:
        if datetime.fromisoformat(record["executedAt"].replace("Z", "+00:00")).tzinfo is None:
            return "EVIDENCE_INCOMPLETE"
    except (ValueError, KeyError, TypeError, AttributeError):
        return "EVIDENCE_INCOMPLETE"
    comparison = report.get("comparison", {})
    candidate = comparison.get("candidate", {}) if isinstance(comparison, dict) else {}
    if not isinstance(candidate, dict):
        return "EVIDENCE_INCOMPLETE"
    cases = candidate.get("cases", [])
    if (not isinstance(cases, list) or not cases or not all(isinstance(c, dict) for c in cases)
            or len(cases) != len(support["methodCases"])
            or [c.get("queryDigest") for c in cases] != [sha(c["query"].encode()) for c in support["methodCases"]]
            or not all(c.get("loadedMethodObserved") is True for c in cases)):
        return "EVIDENCE_INCOMPLETE"
    runtime_status = _actual_runtime_status(report, candidate, cases, scope, subject)
    if runtime_status:
        return runtime_status
    if report.get("status") != "PASS" or report.get("runtimeVerified") is not True:
        return "FAIL"
    return "PASS"


def _actual_runtime_status(report, candidate, cases, scope, subject):
    # The existing trialRuntime has only family/language. Actual SDK/integrator
    # versions and digests come from every observed installation lock, not scope.
    trial_runtime = report.get("trialRuntime")
    runtime = candidate.get("runtime")
    if not isinstance(trial_runtime, dict) or not isinstance(runtime, dict):
        return "EVIDENCE_INCOMPLETE"
    family, language = trial_runtime.get("family"), trial_runtime.get("language")
    if not family or not language:
        return "EVIDENCE_INCOMPLETE"
    if (not scope["runtimeId"].startswith(f"{family}-{language}-")
            or any(runtime.get(k) != trial_runtime[k] for k in ("family", "language"))):
        return "RUNTIME_MISMATCH"
    first_loaded = None
    for case in cases:
        lock = case.get("runtimeLock")
        if not isinstance(lock, dict) or not isinstance(lock.get("runtime"), dict):
            return "EVIDENCE_INCOMPLETE"
        observed = lock["runtime"]
        if any(observed.get(k) != trial_runtime[k] for k in ("family", "language")):
            return "RUNTIME_MISMATCH"
        assets = lock.get("assets", [])
        if not isinstance(assets, list) or not all(isinstance(a, dict) for a in assets):
            return "EVIDENCE_INCOMPLETE"
        if not any(a.get("kind") == subject["kind"].replace("-", "_").upper()
                   and all(a.get(k) == subject[k] for k in ("id", "version", "source", "digest")) for a in assets):
            return "EVIDENCE_INCOMPLETE"
        loaded = {}
        for kind in ("RUNTIME_TEMPLATE", "RUNTIME_INTEGRATION"):
            matches = [a for a in assets if a.get("kind") == kind]
            if len(matches) != 1 or not re.fullmatch(r"sha256:[0-9a-f]{64}", str(matches[0].get("digest", ""))):
                return "EVIDENCE_INCOMPLETE"
            loaded[kind] = matches[0]
        if first_loaded is not None and first_loaded != loaded:
            return "RUNTIME_MISMATCH"
        first_loaded = loaded
        sdk, integration = loaded["RUNTIME_TEMPLATE"], loaded["RUNTIME_INTEGRATION"]
        if not sdk.get("version") or not integration.get("version"):
            return "EVIDENCE_INCOMPLETE"
        references = observed.get("resolvedArtifacts") or observed
        if not isinstance(references, dict):
            return "EVIDENCE_INCOMPLETE"
        if (scope["runtimeId"] != f"{family}-{language}-{sdk['version']}"
                or scope["integratorId"] != integration.get("id")
                or sdk.get("id") != references.get("templateRef")
                or integration.get("id") != references.get("integrationRef")
                or not str(sdk.get("id", "")).endswith(":" + sdk["version"])
                or not str(integration.get("id", "")).endswith(":" + integration["version"])):
            return "RUNTIME_MISMATCH"
    return None
