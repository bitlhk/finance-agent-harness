"""Opt-in real managed Backend acceptance against loopback-only controlled services.

No qualification, compiler, Registry, Jobs or Runtime operations are mocked. This is
not a LIVE model or browser acceptance test. Operator qualification pins are required.
"""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sys
import time
import uuid

import pytest

from finance_solution_builder.managed_runtime_support import declarations, require_backend_admission
from finance_solution_builder.method_assets import installation_digest, runtime_files
from finance_solution_builder.model_registry import ModelRegistry
from finance_solution_builder.service import digest, write_json
from finance_solution_builder.unified_catalog import unified_catalog
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_contracts import parse_selection


PROJECT = Path(__file__).resolve().parents[1]
pytestmark = pytest.mark.skipif(os.environ.get("FH_RUN_AGENTSCOPE_BACKEND") != "1",
                                reason="explicit isolated Java/HTTP qualification only")


def _selection(harness, model_revision, pack_ids=("account-balance-readonly",)):
    catalog = unified_catalog(PROJECT, harness)
    directory = catalog["templateModuleDirectory"]
    entries = [next(e for e in catalog["entries"] if e["pack"]["id"] == identity) for identity in pack_ids]
    runtime = declarations(PROJECT)[0]
    return {"extension_type": "finance-workbench.selection", "schema_version": "0.10",
            "runtimeId": runtime["id"], "hostId": runtime["hosts"][0]["id"],
            "profileRef": runtime["hosts"][0]["profileRef"],
            "applicationTemplateId": "banking-readonly-web-v1", "bankConnectionId": "local-simulator",
            "scenarioTemplateId": "customer-custom", "directoryRevision": directory["revision"],
            "selectedModelRefs": ["model-controlled-host"], "defaultModelRef": "model-controlled-host",
            "modelCatalogRevision": model_revision,
            "moduleIds": [m["id"] for m in directory["modules"] if any(e["componentId"] in m["componentIds"] for e in entries)],
            "packBindings": [{"pack": entry["pack"], "provider": entry["providers"][0]} for entry in entries],
            "capabilityIds": sorted({cap["capabilityId"] for entry in entries for cap in entry["capabilities"]})}


def _wait_build(jobs, identity):
    deadline = time.monotonic() + 900
    while time.monotonic() < deadline:
        status = jobs.jobs[identity]["status"]
        if status not in {"QUEUED", "BUILDING"}:
            assert status == "BUILT", "ACTUAL_BACKEND_BUILD_FAILED_CHECK_STAGES"
            return
        time.sleep(0.1)
    raise AssertionError("ACTUAL_BACKEND_BUILD_TIMEOUT")


def _turns(jobs, identity, owner, fixture, completion, marker=None):
    before = len(fixture.model_requests)
    jobs.start(identity, owner)
    instance = jobs.jobs[identity]["instanceId"]
    child = jobs.processes[identity]
    code, session = jobs.talk(identity, owner, "session", {}, expected_instance=instance)
    assert code == 200 and session["sessionId"]
    evidence = []
    try:
        for turn in range(2):
            fixture.enqueue(completion(tool="account_balance_query"))
            fixture.enqueue(completion("Balance 12345.67 CNY, controlled-bank."))
            code, reply = jobs.talk(identity, owner, "chat", {"query": "Read my balance", "runId": f"balance-{turn}"},
                                    expected_instance=instance)
            assert code == 200 and "12345.67" in reply["response"]
            assert len(reply["executions"]) == 1 and reply["executions"][0]["result"]["status"] == "COMPLETED"
            assert reply["runtimeLock"]["installations"]
            assert jobs.processes[identity] is child and jobs.jobs[identity]["instanceId"] == instance
            evidence.append({"turn": turn, "httpStatus": code, "runtimeRegistered": True,
                             "providerCompleted": True})
        assert len(fixture.model_requests) - before == 4
        if marker is not None:
            assert marker in json.dumps(fixture.model_requests[before:])
        with pytest.raises(PermissionError):
            jobs.talk(identity, "different-owner", "status", {})
        lock = json.loads((jobs.path(identity) / "candidate/service-lock.json").read_bytes())
        assert lock["runtime"]["family"] == "agentscope"
        assert lock["runtimeBinding"]["managedTemplate"]["digest"] == lock["managedTemplateDigest"]
        assert lock["managedTemplateDigest"] != lock["hostTemplateDigest"]
        return {"status": "PASS_CONTROLLED_NATIVE", "turns": evidence, "modelHttp": 4,
                "ownerDenied": True, "sameProcessAndGeneration": True,
                "lockSha256": digest(jobs.path(identity) / "candidate/service-lock.json")}
    finally:
        jobs.stop(identity, owner, expected_instance=instance)
        assert child.poll() is not None and jobs.jobs[identity]["status"] == "STOPPED"


def _spi_invoke(jobs, identity, owner, fixture, completion, monkeypatch):
    from finance_builder_base import ResourceKind
    from finance_solution_builder.candidate_backend.agentscope_java import AgentScopeJavaBackend
    from finance_solution_builder.candidate_backend.models import (
        CandidateDescriptor, CandidateResourceRef, InvocationRequest, LifecycleStatus,
        StartRequest, StopRequest, verify_candidate_artifact)
    from finance_solution_builder.candidate_backend.restricted_response import owner_ref, read_response
    output = jobs.path(identity)
    solution = json.loads((jobs.inputs / (identity + ".json")).read_bytes())
    descriptor = CandidateDescriptor.model_validate_json((output / "candidate-descriptor.json").read_bytes())
    for key, value in {"FH_SNAPSHOTS_ENABLED": "true", "FH_SNAPSHOT_CAPTURE_OWNERS": owner_ref(owner),
            "FH_SNAPSHOT_VIEW_OWNERS": owner_ref(owner) + "," + owner_ref("different-owner"),
            "FH_SNAPSHOT_MAX_FILES": "32", "FH_SNAPSHOT_MAX_BYTES": str(16 * 1024 * 1024),
            "FH_SNAPSHOT_RETENTION_SECONDS": "60", "FH_CANDIDATE_RESPONSE_MAX_FILES": "9",
            "FH_CANDIDATE_RESPONSE_MAX_BYTES": str(3 * 1024 * 1024),
            "FH_CANDIDATE_RESPONSE_RETENTION_SECONDS": "60"}.items():
        monkeypatch.setenv(key, value)
    adapter = AgentScopeJavaBackend(builder=jobs.builder, solution=solution, output=output,
        runtime_binding=jobs.jobs[identity]["runtimeBinding"], jobs=jobs, owner=owner)
    started = adapter.start(StartRequest(descriptor=descriptor,
        artifact_verification=verify_candidate_artifact(descriptor, output)))
    assert started.outcome.status == LifecycleStatus.PASSED
    child = jobs.processes[identity]
    instance = jobs.jobs[identity]["instanceId"]
    payload = output / "spi-input.json"
    write_json(payload, {"query": "Read my balance", "runId": "spi-real-native"})
    request = InvocationRequest(handle=started.handle, request_id="spi-real-native", protocol="finance-chat",
        payload_ref=CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator=payload.name, sha256=digest(payload)))
    try:
        before = (len(fixture.model_requests), len(fixture.bank_requests))
        with monkeypatch.context() as denied:
            denied.setenv("FH_SNAPSHOT_CAPTURE_OWNERS", "")
            assert adapter.invoke(request).outcome.status == LifecycleStatus.BLOCKED
        assert identity not in jobs.sessions and before == (len(fixture.model_requests), len(fixture.bank_requests))
        fixture.enqueue(completion(tool="account_balance_query"))
        fixture.enqueue(completion("Balance 12345.67 CNY, controlled-bank."))
        result = adapter.invoke(request)
        assert result.outcome.status == LifecycleStatus.PASSED
        reply = read_response(output, result.response_ref, owner=owner, request=request, instance_id=instance,
                              verify_identity=lambda: adapter._owned(started.handle))
        assert "12345.67" in reply["response"] and reply["executions"][0]["result"]["status"] == "COMPLETED"
        assert reply["runtimeLock"]["installations"] and result.outcome.evidence_refs
        assert jobs.processes[identity] is child and jobs.jobs[identity]["instanceId"] == instance
        with pytest.raises((PermissionError, ValueError)):
            read_response(output, result.response_ref, owner="different-owner", request=request, instance_id=instance,
                          verify_identity=lambda: jobs.owned(identity, "different-owner"))
        after = (len(fixture.model_requests), len(fixture.bank_requests))
        assert after[0] - before[0] == 2
        assert adapter.invoke(request).outcome.status == LifecycleStatus.BLOCKED
        assert after == (len(fixture.model_requests), len(fixture.bank_requests))
        return {"status": "PASS_CONTROLLED_NATIVE_SPI", "modelHttp": 2, "actualReply": True,
                "providerCompleted": True, "runtimeRegistered": True, "deniedZeroDispatch": True,
                "otherOwnerBodyDenied": True, "duplicateZeroDispatch": True,
                "responseRef": result.response_ref.model_dump(mode="json", by_alias=True),
                "receiptRefs": [ref.model_dump(mode="json", by_alias=True) for ref in result.outcome.evidence_refs]}
    finally:
        assert adapter.stop(StopRequest(handle=started.handle)).stopped and child.poll() is not None


def test_real_registry_jobs_and_private_method(monkeypatch):
    output = Path(os.environ["FH_AGENTSCOPE_BACKEND_OUTPUT"]).absolute()
    assert not output.exists() and not any(p.is_symlink() for p in [output, *output.parents])
    output.mkdir(parents=True, mode=0o700)
    harness = Path(os.environ.get("FH_AGENTSCOPE_HARNESS", "/home/ubuntu/finance-harness-agentscope-query")).resolve()
    sys.path.insert(0, str(PROJECT / "engineering"))
    from agentscope_host_checks import Fixtures, completion, SUBJECT, MODEL_KEY
    fixture = Fixtures(harness)
    owner = "controlled-backend-owner"
    report = {"schemaVersion": "finance-backend-controlled-acceptance.v0.1", "status": "RUNNING",
              "realModel": "NOT_RUN", "browser": "NOT_RUN", "professionalQuality": "NOT_ASSESSED",
              "checks": {}, "stage": "selection"}
    jobs = None
    try:
        policy = {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": fixture.bank_url,
                  "subjects": {SUBJECT: {"enabled": True,
                      "expiresAt": (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat(),
                      "scopes": ["account:read", "cashflow:read"]}}}
        write_json(output / "policy.json", policy)
        write_json(output / "model-catalog.json", {"schemaVersion": "finance-model-catalog.v1",
            "source": {"kind": "CONTROLLED_TEST"}, "models": [{"modelRef": "model-controlled-host",
                "displayName": "Controlled Host", "provider": "OpenAI", "modelName": "controlled-host",
                "eaCatalogId": "controlled", "eaConfigured": True}]})
        write_json(output / "model-registry.json", {"schemaVersion": "finance-model-bindings.v1", "bindings": [{
            "modelRef": "model-controlled-host", "revision": "v1", "enabled": True,
            "protocol": "OPENAI_CHAT_COMPLETIONS", "provider": "OpenAI", "modelName": "controlled-host",
            "urlEnvironment": "FH_CONN_CONTROLLED_URL", "keyEnvironment": "FH_CONN_CONTROLLED_KEY"}]})
        for key, value in {"FH_CANDIDATE_RUNTIME_IDS": "agentscope-java-2.0.3", "FH_BANK_URL": fixture.bank_url,
                "FH_BANK_LOGIN": "fixture", "FH_BANK_SECRET": "fixture-bank-secret",
                "FH_ACCESS_POLICY": str(output / "policy.json"), "FH_MODEL_MODE": "CONTROLLED_TEST",
                "FH_MODEL_URL": fixture.model_url, "FH_MODEL_NAME": "controlled-host", "FH_MODEL_KEY": MODEL_KEY,
                "FH_MODEL_CATALOG": str(output / "model-catalog.json"),
                "FH_MODEL_REGISTRY": str(output / "model-registry.json"),
                "FH_CONN_CONTROLLED_URL": fixture.model_url, "FH_CONN_CONTROLLED_KEY": MODEL_KEY,
                "FH_SNAPSHOTS_ENABLED": "false", "FH_SNAPSHOT_CAPTURE_OWNERS": ""}.items():
            monkeypatch.setenv(key, value)
        for key in ("FH_HISTORY_DIR", "FH_HISTORY_OWNER"):
            monkeypatch.delenv(key, raising=False)
        registry = ModelRegistry()
        body = _selection(harness, registry.catalog_revision)
        write_json(output / "selection.json", body)
        selected = parse_selection(body)
        model_snapshot = registry.snapshot(selected.selectedModelRefs, selected.defaultModelRef, selected.modelCatalogRevision)
        registry.check_frozen(model_snapshot)
        jobs = Jobs(PROJECT, harness, data_dir=output / "data")
        report["stage"] = "baseline-prequeue-build"
        job = jobs.create(selected.legacy(), owner, selection=selected.model_dump(), model_snapshot=model_snapshot)
        _wait_build(jobs, job["id"])
        report["stage"] = "baseline-start-talk-stop"
        report["checks"]["baseline"] = _turns(jobs, job["id"], owner, fixture, completion)
        assert not fixture.model_requests[0]["body"].get("stream", False)
        report["stage"] = "actual-native-spi-invoke"
        report["checks"]["spiInvoke"] = _spi_invoke(jobs, job["id"], owner, fixture, completion, monkeypatch)

        data = {"name": "Controlled private balance method", "content": "# Method\nPRIVATE-BACKEND-FROZEN-MARKER",
                "resources": {}, "requiredResources": [], "capabilityIds": ["account.balance.query"],
                "cases": [{"name": "balance", "query": "Read my balance", "expectedTerms": ["12345.67"]}]}
        draft = jobs.method_assets.create(owner, data)
        snapshot = {"assetId": draft["assetId"], "version": "0.0.0", "data": draft["data"],
                    "releaseDigest": draft["contentDigest"],
                    "installationDigest": installation_digest(runtime_files(draft["assetId"], "0.0.0", draft["data"]))}
        preview_body = dict(body, methodRef={key: snapshot[key] for key in ("assetId", "version", "releaseDigest")})
        preview_id = "agent-" + uuid.uuid4().hex[:16]
        preview = parse_selection(preview_body)
        solution, _, plan = preview.resolve_details(PROJECT, harness, preview_id, snapshot)
        report["stage"] = "private-draft-real-trial"
        admission = require_backend_admission(PROJECT, harness, solution, plan["runtimeBinding"], method_snapshot=snapshot)
        source = jobs.inputs / (preview_id + ".json")
        write_json(source, solution)
        jobs.builder.build(source, jobs.path(preview_id), managed=True, runtime_binding=plan["runtimeBinding"],
                           runtime_admission=admission, method_snapshot=snapshot,
                           model_selection=model_snapshot,
                           observation_requirements=Path(os.environ["FH_OBSERVATION_REQUIREMENTS"]))
        # This is an internal draft trial, not a published owner reference or a queued Job.
        jobs.jobs[preview_id] = {"id": preview_id, "owner": owner, "status": "BUILT", "createdAt": time.time(),
                                "capabilities": ["account.balance.query"], "runtimeBinding": plan["runtimeBinding"],
                                "modelSelection": model_snapshot,
                                "runtimeAdmission": admission, "runtimePresentation": plan["runtimeBinding"]["runtimePresentation"]}
        jobs.save(jobs.jobs[preview_id])
        preview_evidence = _turns(jobs, preview_id, owner, fixture, completion, "PRIVATE-BACKEND-FROZEN-MARKER")
        report["checks"]["privateTrial"] = preview_evidence
        trial = jobs.method_assets.record_trial(owner, draft["assetId"], draft,
            {"status": "PASS", "runtimeVerified": True, "model": "CONTROLLED_TEST", "source": "CONTROLLED_HTTP",
             "professionalQuality": "NOT_ASSESSED", "evidence": preview_evidence})
        release = jobs.method_assets.publish(owner, draft["assetId"], draft["revision"], "1.0.0", trial["trialId"])
        ref = {key: release[key] for key in ("assetId", "version", "releaseDigest")}
        changed = deepcopy(data)
        changed["content"] = "# Changed draft\nMUST-NOT-INSTALL-NEW-DRAFT"
        jobs.method_assets.update(owner, draft["assetId"], draft["revision"], changed)
        request = parse_selection(dict(body, methodRef=ref))
        report["stage"] = "private-release-prequeue-build"
        job = jobs.create(request.legacy(), owner, selection=request.model_dump(), model_snapshot=model_snapshot)
        _wait_build(jobs, job["id"])
        start = len(fixture.model_requests)
        report["stage"] = "private-release-start-talk-stop"
        report["checks"]["privateRelease"] = _turns(jobs, job["id"], owner, fixture, completion,
                                                     "PRIVATE-BACKEND-FROZEN-MARKER")
        assert "MUST-NOT-INSTALL-NEW-DRAFT" not in json.dumps(fixture.model_requests[start:])
        with pytest.raises(Exception, match="ASSET_NOT_AVAILABLE"):
            jobs.create(request.legacy(), "different-owner", selection=request.model_dump())
        report.update(status="PASS_CONTROLLED_BACKEND_AND_PRIVATE_METHOD", stage="complete")
    except BaseException as failure:
        report.update(status="FAIL", failureType=type(failure).__name__)
        raise
    finally:
        if jobs is not None:
            jobs.close()
        fixture.close()
        report.update(modelHttp=len(fixture.model_requests), bankHttp=len(fixture.bank_requests),
                      allOwnedProcessesStopped=jobs is None or all(p.poll() is not None for p in jobs.processes.values()))
        write_json(output / "report.json", report)
