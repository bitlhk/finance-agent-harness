"""Read-only projections of owner-scoped immutable releases and their stored trials."""
from __future__ import annotations

import json
import os
import re
import stat

from .development_support import ENVIRONMENT, _actual_runtime_status
from .experience_contracts import ApiError
from .method_assets import canonical, installation_digest, runtime_files, runtime_reference, sha

MAX_SCAN_ENTRIES = 256
MAX_SCAN_RELEASES = 64
MAX_REPORTS = 20
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_SCAN_BYTES = 16 * 1024 * 1024
REFERENCE_KEYS = ("assetId", "version", "releaseDigest")


def _base_match(subjects, binding):
    refs = binding.get("basePackages", []) + binding.get("copiedFrom", [])
    for subject in subjects:
        related = [r for r in refs if r.get("kind") == subject["kind"] and r.get("id") == subject["id"]]
        if not related:
            return "NOT_RECORDED"
        if not any(r.get("version") == subject["version"] for r in related):
            return "VERSION_MISMATCH"
        if not any(all(r.get(k) == subject[k] for k in ("kind", "id", "version", "source", "digest")) for r in related):
            return "DIGEST_MISMATCH"
    return "MATCHED" if subjects else "NOT_RECORDED"


def _children(path, scan):
    try:
        with os.scandir(path) as entries:
            for entry in entries:
                if scan["scannedEntries"] >= MAX_SCAN_ENTRIES:
                    scan["truncated"] = True
                    break
                scan["scannedEntries"] += 1
                if entry.is_dir(follow_symlinks=False):
                    yield entry.name
                elif entry.is_symlink():
                    scan["unavailableReleases"] += 1
    except FileNotFoundError:
        return
    except OSError:
        scan["unavailableReleases"] += 1


def owner_method_verifications(store, owner, component_id, subjects, scope=None):
    """Bound scanning before reads; never call unbounded list/get or follow other owners."""
    scan = {"status": "COMPLETE", "scannedEntries": 0, "scannedReleases": 0,
            "unavailableReleases": 0, "truncated": False}
    records, budget = [], 0
    with store.lock:
        space = store._space(owner)
        if space.is_symlink():
            raise ApiError("ASSET_NOT_AVAILABLE", 404)
        for asset_id in _children(space, scan):
            if not re.fullmatch(r"method-[a-f0-9]{16}", asset_id):
                continue
            folder = store._folder(owner, asset_id) / "releases"
            if folder.is_symlink():
                scan["unavailableReleases"] += 1
                continue
            for version in _children(folder, scan):
                if not re.fullmatch(r"[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}", version):
                    continue
                if scan["scannedReleases"] >= MAX_SCAN_RELEASES or len(records) >= MAX_REPORTS:
                    scan["truncated"] = True
                    break
                scan["scannedReleases"] += 1
                path = folder / version / "release.json"
                trial_path = path.with_name("trial.json")
                try:
                    metadata = [p.lstat() for p in (path, trial_path)]
                    if any(not stat.S_ISREG(info.st_mode) for info in metadata):
                        raise ValueError("unsafe entry")
                    sizes = [info.st_size for info in metadata]
                    if any(size > MAX_FILE_BYTES for size in sizes):
                        raise ValueError("entry too large")
                    # release_trial also re-reads the pinned release and trial.
                    budget += 3 * sum(sizes)
                    if budget > MAX_SCAN_BYTES:
                        scan["truncated"] = True
                        break
                    with path.open("rb") as stream:
                        peek = json.loads(stream.read(MAX_FILE_BYTES + 1))
                    binding = peek.get("source", {}).get("binding", {})
                    if (peek.get("source", {}).get("kind") != "BUSINESS_DERIVED"
                            or binding.get("componentId") != component_id):
                        continue
                    if peek["assetId"] != asset_id or peek["version"] != version:
                        raise ValueError("identity mismatch")
                    reference = {k: peek[k] for k in REFERENCE_KEYS}
                    release, trial = store.release_trial(owner, reference)
                    record = release_verification(release, trial, scope)
                    record["baseMatch"] = _base_match(subjects, release["source"]["binding"])
                    records.append(record)
                except (ApiError, OSError, ValueError, KeyError, TypeError, AttributeError):
                    scan["unavailableReleases"] += 1
            if scan["truncated"]:
                break
    if scan["truncated"]:
        scan["status"] = "TRUNCATED"
    elif scan["unavailableReleases"]:
        scan["status"] = "PARTIAL"
    records.sort(key=lambda r: (r["createdAt"] or 0, r["assetRef"]["assetId"], r["assetRef"]["version"]), reverse=True)
    return records, scan


def _runtime(cases):
    """Project only loaded identities; outer trialRuntime cannot supply SDK versions."""
    actual = None
    for case in cases:
        lock = case.get("runtimeLock", {})
        runtime = lock.get("runtime", {})
        assets = lock.get("assets", [])
        loaded = {}
        for kind, field in (("RUNTIME_TEMPLATE", "sdk"), ("RUNTIME_INTEGRATION", "integrator")):
            matches = [a for a in assets if a.get("kind") == kind]
            if len(matches) != 1:
                return None
            loaded[field] = {k: matches[0].get(k) for k in ("id", "version", "digest")}
            if not all(isinstance(v, str) and v for v in loaded[field].values()):
                return None
        value = {"family": runtime.get("family"), "language": runtime.get("language"),
                 "version": loaded["sdk"]["version"], **loaded}
        value["runtimeId"] = f"{value['family']}-{value['language']}-{value['version']}"
        if actual is not None and actual != value:
            return None
        actual = value
    return actual


def _method_check(release, trial, report, selected, result):
    missing = result["missingFields"]
    for field in ("contentDigest", "revision", "baseSourceDigest", "trialInstallationVersion", "trialInstallationDigest", "trialRuntime", "comparison"):
        if field not in report:
            missing.append("report." + field)
    if report.get("schemaVersion") != "private-method-trial.v0.1":
        return "UNSCOPED_REPORT"
    if missing:
        return "EVIDENCE_INCOMPLETE"
    if (report["contentDigest"] != release["contentDigest"]
            or trial["contentDigest"] != release["contentDigest"]
            or sha(canonical(release["data"])) != release["contentDigest"]
            or report["revision"] != release["revision"]
            or trial["revision"] != release["revision"]):
        return "CONTENT_MISMATCH"
    if report["baseSourceDigest"] != sha(canonical(release["source"])):
        return "SOURCE_MISMATCH"
    installed = installation_digest(runtime_files(release["assetId"], report["trialInstallationVersion"], release["data"]))
    if installed != report["trialInstallationDigest"]:
        return "METHOD_DIGEST_MISMATCH"
    subject = {"kind": "domain-pack", **runtime_reference({"assetId": release["assetId"],
        "version": report["trialInstallationVersion"], "installationDigest": installed})}
    result["testedMethodRef"] = {k: subject[k] for k in ("id", "version", "source", "digest")}
    candidate = report.get("comparison", {}).get("candidate", {})
    if not isinstance(candidate, dict) or not isinstance(candidate.get("cases"), list):
        missing.append("report.comparison.candidate.cases")
        return "EVIDENCE_INCOMPLETE"
    cases = candidate.get("cases", [])
    expected = release["data"]["cases"]
    if (not expected or len(cases) != len(expected)
            or [c.get("queryDigest") for c in cases] != [sha(c["query"].encode()) for c in expected]):
        return "CASE_MISMATCH"
    if not all(c.get("loadedMethodObserved") is True for c in cases):
        return "EVIDENCE_INCOMPLETE"
    if (candidate.get("businessWriteAttempts") != 0
            or not all(all(type(c.get(k)) is int and c[k] > 0
                           for k in ("modelRequests", "businessReadCount", "toolCount")) for c in cases)):
        missing.append("report.comparison.candidate.executionCounters")
        return "EVIDENCE_INCOMPLETE"
    actual = _runtime(cases)
    if actual is None:
        missing.append("report.comparison.candidate.cases.runtimeLock")
        return "EVIDENCE_INCOMPLETE"
    observed_scope = {"capabilityIds": sorted(release["data"]["capabilityIds"]),
                      "runtimeId": actual["runtimeId"], "integratorId": actual["integrator"]["id"]}
    status = _actual_runtime_status(report, candidate, cases, observed_scope, subject)
    if status:
        if status == "EVIDENCE_INCOMPLETE":
            missing.append("runtimeLock.methodOrRuntimeIdentity")
        return status
    result["actualRuntime"] = actual
    profiles = []
    for case in cases:
        installations = case["runtimeLock"].get("domainInstallations", [])
        matches = [p for p in installations if p.get("packId") == subject["id"] and p.get("packVersion") == subject["version"]]
        if len(matches) != 1:
            missing.append("runtimeLock.domainInstallations")
            return "EVIDENCE_INCOMPLETE"
        if sorted(matches[0].get("capabilities", [])) != observed_scope["capabilityIds"]:
            return "SCOPE_MISMATCH"
        profiles.append(matches[0].get("profile"))
    if not profiles[0] or len(set(profiles)) != 1:
        return "SCOPE_MISMATCH"
    observed_scope["profileId"] = profiles[0]
    result["scope"] = observed_scope
    if selected:
        for key, value in selected.items():
            if key not in observed_scope:
                result["scopeMatch"] = "NOT_RECORDED"
                return "SCOPE_NOT_RECORDED"
            if observed_scope[key] != value:
                result["scopeMatch"] = "MISMATCH"
                return "SCOPE_MISMATCH"
        if any(key in selected for key in ENVIRONMENT):
            result["scopeMatch"] = "MATCHED"
    if report.get("status") != "PASS" or report.get("runtimeVerified") is not True:
        return "FAIL"
    result["binding"] = "SAME_CONTENT_PUBLISHED_VERSION"
    return "PASS"


def release_verification(release, trial, scope=None):
    """Caller must obtain this immutable pair from MethodAssets.release_trial(owner, ref)."""
    report = trial.get("report", {})
    reference = {k: release[k] for k in REFERENCE_KEYS}
    url = "/api/v1/assets/{assetId}/packages/{version}/{releaseDigest}/contents".format(**reference)
    report_digest = sha(canonical(report))
    derived = release.get("source", {}).get("kind") == "BUSINESS_DERIVED"
    timestamp = trial.get("createdAt")
    timestamp = timestamp if isinstance(timestamp, (int, float)) and not isinstance(timestamp, bool) else None
    result = {"kind": "DERIVED_METHOD_RELEASE" if derived else "METHOD_RELEASE",
              "label": "派生方法版本的验证" if derived else "已发布方法版本的验证",
              "name": release["data"].get("name"),
              "assetRef": reference, "testedMethodRef": None, "binding": "NOT_RECORDED",
              "casesDigest": sha(canonical(release["data"]["cases"])), "reportDigest": report_digest,
              "actualRuntime": None, "scope": {}, "scopeMatch": "NOT_SELECTED",
              "createdAt": timestamp, "timeSource": "TRIAL_RECORDED_AT" if timestamp is not None else "NOT_RECORDED",
              "reportRef": {"source": f"method-release://{reference['assetId']}/{reference['version']}/trial/{trial['trialId']}",
                            "digest": report_digest, "url": url}, "missingFields": []}
    try:
        status = _method_check(release, trial, report, scope, result)
    except (ApiError, ValueError, KeyError, TypeError, AttributeError):
        status = "EVIDENCE_INCOMPLETE"
        result["missingFields"].append("report.matchableEvidence")
    if timestamp is None:
        result["missingFields"].append("trial.createdAt")
        if status == "PASS":
            status = "EVIDENCE_INCOMPLETE"
    result["missingFields"].extend(["scope.hostId", "scope.testDataId", "executedAt"])
    if status == "PASS":
        status = "RUNTIME_CHECK_PASSED"
    result["validation"] = {"status": status, "kind": "METHOD_RUNTIME_CHECK",
        "environmentStatus": "NOT_FULLY_RECORDED", "reason": {
        "RUNTIME_CHECK_PASSED": "该方法相同内容在所列SDK的运行检查通过；Host、测试数据版本与执行时刻未记录，不代表完整环境验收、基础组件验证或发布后重新执行",
        "FAIL": "该方法运行检查未通过", "UNSCOPED_REPORT": "旧报告未记录可核验的组件试跑格式",
        "EVIDENCE_INCOMPLETE": "旧报告缺少可匹配的版本、案例或实际安装证据",
        "CONTENT_MISMATCH": "报告内容或修订与发布内容不匹配", "SOURCE_MISMATCH": "报告派生来源摘要不匹配",
        "METHOD_DIGEST_MISMATCH": "试跑方法安装摘要与发布内容不匹配", "CASE_MISMATCH": "报告案例与发布案例不匹配",
        "RUNTIME_MISMATCH": "报告内实际Runtime或Integrator不一致", "SCOPE_MISMATCH": "报告范围与选择不匹配",
        "SCOPE_NOT_RECORDED": "报告未记录所选Host或测试数据条件，不能宣称该环境通过",
    }[status], "professionalQuality": "NOT_EVALUATED"}
    return result
