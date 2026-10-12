import http.cookiejar
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import shutil
import threading
import urllib.error
import urllib.request

import pytest

from finance_builder_base import ResourceKind, ResourceRef
from finance_solution_builder.assets import HarnessArchiveAnchor
from finance_solution_builder import workbench
from finance_solution_builder.optimization import (
    CandidateOverlay,
    CorrectionExecution,
    OverlayFile,
    RebuildOutcome,
)
from finance_solution_builder.service import digest, write_json
from finance_solution_builder.workbench_api import WorkbenchApi, public_error
from finance_solution_builder.workbench_contracts import ApiError, default_selection
from tests.candidate_backend.support import descriptor
from tests.optimization import finding, report, request
from tests.test_workbench_optimization import (
    baseline_candidate,
    complete_binding,
    write_json as write_optimization_json,
)


@pytest.fixture
def jobs(tmp_path):
    value = workbench.Jobs(tmp_path, tmp_path)
    yield value
    value.processes.clear()
    value.close()


def completed(jobs, status="BUILT", key="agent-test"):
    jobs.jobs[key] = dict(id=key, owner="owner", status=status, capabilities=["account.balance.query"])
    root = jobs.path(key)
    candidate = root / "candidate"
    candidate.mkdir(parents=True)
    write_json(candidate / "service-lock.json", {"buildId": key, "verification": "COMPILED", "files": {}})
    write_json(root / "build-report.json", {"status": "BUILT", "lockSha256": digest(candidate / "service-lock.json"),
               "stages": [{"name": name, "status": "PASS", "elapsedMillis": 1} for name in
                          ("harness_contracts", "harness_tests_install", "candidate_package")]})
    return key


def test_build_instance_and_artifact_states_are_separate(jobs):
    key = completed(jobs)
    api = WorkbenchApi(jobs)
    initial = api.inspect(key, "owner")
    assert initial["buildStatus"] == "SUCCEEDED" and initial["instance"]["status"] == "NOT_STARTED"
    assert initial["artifact"]["artifactId"].startswith("sha256:")
    assert initial["instance"]["sessionId"] is None
    assert initial["links"]["agentPage"] == "/?agent=" + key
    jobs.jobs[key].update(status="FAILED", error="INSTANCE_START_FAILED")
    failed = api.inspect(key, "owner")
    assert failed["buildStatus"] == "SUCCEEDED" and failed["instance"]["status"] == "FAILED"
    assert failed["artifact"] == initial["artifact"]
    (jobs.path(key) / "candidate/service-lock.json").write_text("tampered")
    tampered = api.inspect(key, "owner")
    assert tampered["buildStatus"] == "INVALID_ARTIFACT" and tampered["artifact"] is None


def test_jobs_passes_a_non_git_harness_anchor_to_the_builder(tmp_path):
    anchor = HarnessArchiveAnchor(source="https://github.com/bitlhk/finance-agent-harness.git",
                                  revision="a" * 40, tree_digest="sha256:" + "b" * 64)
    jobs = workbench.Jobs(tmp_path, tmp_path, harness_archive_anchor=anchor)
    try:
        assert jobs.builder.harness_archive_anchor == anchor
    finally:
        jobs.close()


def test_jobs_enable_the_controlled_java_template_fallback_for_managed_audit_builds(tmp_path):
    jobs = workbench.Jobs(tmp_path, tmp_path)
    try:
        assert jobs.builder.allow_template_fallback is True
    finally:
        jobs.close()


def test_runtime_presentation_is_frozen_owner_scoped_and_allowlisted(jobs):
    key = completed(jobs)
    jobs.jobs[key]["selection"] = {"runtimeId": "native-query"}
    jobs.jobs[key]["runtimePresentation"] = {"runtimeId": "native-query", "label": "Pinned runtime",
        "language": "java", "version": "2.0.3", "architectureLabel": "Portable artifact",
        "privateOperatorConfig": "NOT_PUBLIC"}
    value = WorkbenchApi(jobs).inspect(key, "owner")["runtimePresentation"]
    assert value == {"runtimeId": "native-query", "label": "Pinned runtime", "language": "java",
                     "version": "2.0.3", "architectureLabel": "Portable artifact"}
    assert "NOT_PUBLIC" not in json.dumps(value)
    with pytest.raises(PermissionError):
        WorkbenchApi(jobs).inspect(key, "other")


@pytest.mark.parametrize("presentation", [None, {}, {"runtimeId":"other", "label":"Wrong"},
                                        {"runtimeId":"native-query", "label":123}])
def test_missing_or_mismatched_runtime_presentation_is_not_invented(jobs, presentation):
    key = completed(jobs)
    jobs.jobs[key]["selection"] = {"runtimeId":"native-query"}
    jobs.jobs[key]["runtimePresentation"] = presentation
    assert WorkbenchApi(jobs).inspect(key, "owner")["runtimePresentation"] is None


def test_managed_admission_errors_are_actionable_without_leaking_details():
    from finance_solution_builder.component_assembly import AssemblyRejected
    from finance_solution_builder.managed_runtime_support import SAFE_ERRORS
    for code in SAFE_ERRORS:
        assert public_error(AssemblyRejected(code)) == (409, {"code": code, "retryable": False})
    for error in (AssemblyRejected("/private/operator-secret"),
                  AssemblyRejected("MANAGED_RUNTIME_UNAVAILABLE /private/operator-secret"),
                  RuntimeError("MANAGED_RUNTIME_UNAVAILABLE")):
        assert public_error(error) == (503, {"code": "OPERATION_FAILED", "retryable": False})


def test_native_runtime_installation_is_preserved_as_artifact_evidence(jobs):
    key = completed(jobs)
    jobs.jobs[key]["selection"] = {"runtimeId": "agentscope-java-2.0.3"}
    lock = {"schemaVersion": "finance-solution-lock.v1", "solutionId": key,
            "runtime": {"family": "agentscope", "language": "java",
                        "templateRef": "maven://io.agentscope:agentscope-core:2.0.3"},
            "assets": [], "domainInstallations": [],
            "installations": [{"integrationId": "agentscope-java", "capabilities": [
                {"capabilityId": "account.balance.query", "operation": "QUERY", "toolName": "balance_query"}]}]}
    evidence = jobs.path(key) / "candidate/runtime-evidence/session/runtime-lock.json"
    evidence.parent.mkdir(parents=True)
    write_json(evidence, lock)
    value = WorkbenchApi(jobs).inspect(key, "owner")["installation"]
    assert value == {"status": "RECORDED_FOR_ARTIFACT", "lock": lock, "currentInstanceClaim": False}
    assert "javaBridge" not in value["lock"]
    lock["solutionId"] = "another-build"
    write_json(evidence, lock)
    assert WorkbenchApi(jobs).inspect(key, "owner")["installation"] == {"status": "NOT_OBSERVED"}


@pytest.mark.parametrize("plan_version", ["v0.1", "v0.2"])
@pytest.mark.parametrize("fault", [None, "wrong-build", "wrong-tools", "write-tool", "unregistered", "unknown-version"])
def test_python_installation_keeps_runtime_bridge_distinction(jobs, fault, plan_version):
    key = completed(jobs)
    jobs.jobs[key]["selection"] = {"runtimeId": "openjiuwen-python-0.1.18"}
    observed = {"runtime": "openjiuwen-python-0.1.18", "registrationStatus": "RUNTIME_REGISTERED",
        "pythonTools": ["balance_query"], "javaBridge": {"schemaVersion": "finance-installation-plan." + plan_version,
        "solutionId": key, "status": "VALIDATED_NOT_RUNTIME_REGISTERED", "assets": [],
        "capabilities": [{"definition": {"capabilityId": "account.balance.query", "toolName": "balance_query", "operation": "QUERY"}}],
        "methods": [{"packId": "private", "version": "1.0.0", "capabilities": ["account.balance.query"],
                     "skills": [{"directory": "private", "entry": "SKILL.md", "digest": "sha256:abc", "instructions": "PRIVATE_BODY"}]}]}}
    if fault == "wrong-build":
        observed["javaBridge"]["solutionId"] = "other"
    if fault == "wrong-tools":
        observed["pythonTools"] = ["unknown_tool"]
    if fault == "write-tool":
        observed["javaBridge"]["capabilities"][0]["definition"]["operation"] = "TASK"
    if fault == "unregistered":
        observed["registrationStatus"] = "NOT_REGISTERED"
    if plan_version == "v0.2":
        observed["javaBridge"]["observationAssessment"] = {"status": "QUALIFIED", "items": []}
    if fault == "unknown-version":
        observed["javaBridge"]["schemaVersion"] = "finance-installation-plan.v999"
    evidence = jobs.path(key) / "candidate/runtime-evidence/session/runtime-lock.json"
    evidence.parent.mkdir(parents=True)
    write_json(evidence, observed)
    actual = WorkbenchApi(jobs).inspect(key, "owner")["installation"]
    assert actual["status"] == ("NOT_OBSERVED" if fault else "RECORDED_FOR_ARTIFACT")
    if not fault:
        assert actual["format"] == "PYTHON_REGISTRATION_WITH_JAVA_BRIDGE"
        assert actual["lock"]["javaBridge"]["status"] == "VALIDATED_NOT_RUNTIME_REGISTERED"
        assert actual["currentInstanceClaim"] is False
        assert actual["lock"]["javaBridge"]["schemaVersion"] == "finance-installation-plan." + plan_version
    assert "PRIVATE_BODY" not in json.dumps(actual)
    with pytest.raises(PermissionError):
        WorkbenchApi(jobs).inspect(key, "other")


def test_stage_failure_does_not_complete_later_stages_or_leak_report(jobs):
    key = completed(jobs, "FAILED")
    write_json(jobs.path(key) / "build-report.json", {"status": "FAILED", "private": "/home/private KEY=secret",
               "stages": [{"name": "harness_contracts", "status": "FAIL", "command": "secret", "log": "/home/private"}]})
    value = WorkbenchApi(jobs).inspect(key, "owner")
    assert value["buildStatus"] == "FAILED"
    assert [stage["status"] for stage in value["stages"]] == ["FAILED", "PENDING", "PENDING"]
    assert "secret" not in json.dumps(value) and "/home/" not in json.dumps(value)
    with pytest.raises(PermissionError):
        WorkbenchApi(jobs).inspect(key, "other")


def test_api_creation_reuses_real_queue_and_persists_selection(jobs, monkeypatch):
    calls = []
    project = Path(__file__).resolve().parents[1]
    shutil.copytree(project / "tests/fixtures/component-assembly", jobs.builder.harness_repo, dirs_exist_ok=True)
    jobs.builder.project_root = project
    monkeypatch.setattr("finance_solution_builder.workbench_api.preflight", lambda *args: {"canBuild": True})
    monkeypatch.setattr(jobs.builder, "build", lambda *args: calls.append(args))
    body = default_selection(["account.balance.query"])
    code, result = WorkbenchApi(jobs).post("/api/v1/builds", body, "owner")
    jobs.queue.shutdown(wait=True)
    assert code == 202 and len(calls) == 1
    key = result["buildId"]
    assert json.loads((jobs.inputs / (key + ".json")).read_text())["capabilityBindings"] == {
        "account.balance.query": "non-ai-banking-reference"}
    assert jobs.jobs[key]["selection"] == body
    assert WorkbenchApi(jobs).inspect(key, "owner")["buildStatus"] != "SUCCEEDED"  # no actual report


def test_optimization_view_is_owner_scoped_and_binding_stays_private(jobs):
    key = completed(jobs)
    binding = complete_binding(jobs.path(key))

    public = jobs.attach_optimization(key, "owner", binding)
    code, view = WorkbenchApi(jobs).get(
        f"/api/v1/builds/{key}/optimization-view", "owner"
    )

    assert "optimizationViewBinding" not in public
    assert "optimizationViewBinding" not in WorkbenchApi(jobs).inspect(key, "owner")
    assert code == 200
    assert view["buildId"] == key
    assert view["status"] == "completed"
    persisted = json.loads((jobs.records / f"{key}.json").read_text(encoding="utf-8"))
    assert persisted["optimizationViewBinding"]["request_ref"]["sha256"]
    with pytest.raises(PermissionError):
        WorkbenchApi(jobs).get(
            f"/api/v1/builds/{key}/optimization-view", "other"
        )


def test_normal_optimization_run_automatically_publishes_verified_view(jobs):
    key = completed(jobs)
    baseline, _, _ = baseline_candidate(jobs.path(key))
    issue = finding()
    optimization_request = request(
        baseline, findings=(issue,), governance=()
    )
    baseline_report = report(
        baseline, accuracy=0.8, passed=False, findings=(issue,)
    )

    class NotCalled:
        def execute(self, *_args, **_kwargs):
            raise AssertionError("correction must not run without governance")

        def rebuild(self, *_args, **_kwargs):
            raise AssertionError("rebuild must not run without a correction")

        def evaluate(self, *_args, **_kwargs):
            raise AssertionError("evaluation must not run without a rebuilt candidate")

    result = jobs.run_optimization(
        key,
        "owner",
        optimization_request,
        baseline_report,
        correction_executor=NotCalled(),
        rebuild_gateway=NotCalled(),
        evaluation_gateway=NotCalled(),
    )
    _, view = WorkbenchApi(jobs).get(
        f"/api/v1/builds/{key}/optimization-view", "owner"
    )

    assert result.rounds == 1
    assert view["status"] == "completed"
    assert view["verifiability"] == {"status": "verified", "issues": []}
    assert view["request"]["requestId"] == optimization_request.request_id
    assert view["rounds"][0]["findingIds"] == [issue.finding_id]
    persisted = json.loads(
        (jobs.records / f"{key}.json").read_text(encoding="utf-8")
    )
    assert persisted["optimizationViewBinding"]["result_ref"]["locator"] == (
        "optimization/result.json"
    )


def test_control_plane_run_reaches_controller_binding_and_read_view(jobs):
    key = completed(jobs)
    build_root = jobs.path(key)
    baseline, _, _ = baseline_candidate(build_root)
    issue = finding()
    optimization_request = request(
        baseline, findings=(issue,), governance=()
    )
    baseline_report = report(
        baseline, accuracy=0.8, passed=False, findings=(issue,)
    )
    request_ref = write_optimization_json(
        build_root, "control/request.json", optimization_request.to_wire()
    )
    baseline_ref = write_optimization_json(
        build_root, "control/baseline.json", baseline_report.to_wire()
    )

    class NotCalled:
        def execute(self, *_args, **_kwargs):
            raise AssertionError("correction must not run without governance")

        def rebuild(self, *_args, **_kwargs):
            raise AssertionError("rebuild must not run without a correction")

        def evaluate(self, *_args, **_kwargs):
            raise AssertionError("evaluation must not run without a rebuilt candidate")

    calls = []

    def gateways(job_id, owner, supplied_request, supplied_report):
        calls.append((job_id, owner, supplied_request, supplied_report))
        return workbench.WorkbenchOptimizationGateways(
            correction_executor=NotCalled(),
            rebuild_gateway=NotCalled(),
            evaluation_gateway=NotCalled(),
        )

    jobs.optimization_gateway_factory = gateways
    api = WorkbenchApi(jobs)
    code, executed_view = api.post(
        f"/api/v1/builds/{key}/optimization-runs",
        {
            "requestRef": request_ref.model_dump(mode="json"),
            "baselineEvaluationRef": baseline_ref.model_dump(mode="json"),
        },
        "owner",
    )
    read_code, read_view = api.get(
        f"/api/v1/builds/{key}/optimization-view", "owner"
    )

    assert code == 200 and read_code == 200
    assert executed_view == read_view
    assert read_view["status"] == "completed"
    assert read_view["verifiability"] == {"status": "verified", "issues": []}
    assert calls == [(key, "owner", optimization_request, baseline_report)]
    persisted = json.loads(
        (jobs.records / f"{key}.json").read_text(encoding="utf-8")
    )
    assert persisted["optimizationViewBinding"]["request_ref"]["locator"] == (
        "optimization/request.json"
    )


def test_control_plane_run_rejects_unconfigured_or_cross_build_records(jobs):
    first = completed(jobs, key="agent-first")
    second = completed(jobs, key="agent-second")
    baseline, _, _ = baseline_candidate(jobs.path(second))
    request_ref = write_optimization_json(
        jobs.path(second), "control/request.json", request(baseline).to_wire()
    )
    baseline_ref = write_optimization_json(
        jobs.path(second),
        "control/baseline.json",
        report(baseline, accuracy=1.0, passed=True).to_wire(),
    )
    api = WorkbenchApi(jobs)
    body = {
        "requestRef": request_ref.model_dump(mode="json"),
        "baselineEvaluationRef": baseline_ref.model_dump(mode="json"),
    }

    with pytest.raises(ApiError, match="OPTIMIZATION_EXECUTION_UNAVAILABLE"):
        api.post(f"/api/v1/builds/{first}/optimization-runs", body, "owner")

    jobs.optimization_gateway_factory = lambda *_args: pytest.fail(
        "cross-Build records reached the gateway factory"
    )
    with pytest.raises(ValueError, match="OPTIMIZATION_REFERENCE_OUTSIDE_BUILD"):
        api.post(f"/api/v1/builds/{first}/optimization-runs", body, "owner")


def test_successful_correction_rebuild_and_evaluation_publish_verified_view(jobs):
    key = completed(jobs)
    build_root = jobs.path(key)
    baseline, _, _ = baseline_candidate(build_root)
    issue = finding()
    optimization_request = request(baseline, findings=(issue,))
    baseline_report = report(
        baseline, accuracy=0.8, passed=False, findings=(issue,)
    )

    def file_ref(path):
        return ResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=path.relative_to(build_root).as_posix(),
            sha256=digest(path),
        )

    class Correction:
        def execute(self, target, round_index):
            del target
            source = build_root / "optimization/corrections/round-001.patch"
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("increase timeout from 1000 to 1500\n", encoding="utf-8")
            evidence = build_root / "optimization/evidence/round-001.json"
            evidence.parent.mkdir(parents=True, exist_ok=True)
            write_json(
                evidence,
                {"roundIndex": round_index, "change": "increase-timeout"},
            )
            overlay = CandidateOverlay(
                overlay_id="overlay-1",
                round_index=round_index,
                parent_candidate_id=baseline.candidate_id,
                parent_lock_digest=baseline.lock_digest,
                asset_closure_digest="c" * 64,
                root_ref=file_ref(source),
                files=(
                    OverlayFile(
                        path="config/runtime.yaml",
                        sha256=digest(source),
                    ),
                ),
            )
            return CorrectionExecution(
                change_digest=overlay.sha256(),
                evidence_refs=(file_ref(evidence),),
                duration_ms=5,
                cost_microunits=1,
                overlay=overlay,
            )

    class Rebuilder:
        def rebuild(self, parent, correction, round_index):
            del correction
            round_root = build_root / f"optimization/builds/round-{round_index:03d}"
            (round_root / "candidate").mkdir(parents=True, exist_ok=True)
            lock_path = round_root / "solution-lock.json"
            write_json(
                lock_path,
                {"solutionId": "wealth-demo", "roundIndex": round_index},
            )
            candidate = descriptor().model_copy(
                update={"lock_digest": digest(lock_path)}
            )
            write_json(
                round_root / "candidate/candidate-descriptor.json",
                candidate.to_wire(),
            )
            report_path = round_root / "build-report.json"
            write_json(report_path, {"status": "SUCCEEDED"})
            candidate_snapshot = parent.model_copy(
                update={
                    "descriptor_digest": candidate.sha256(),
                    "lock_digest": digest(lock_path),
                    "artifact_digest": candidate.artifact.resource.sha256,
                }
            )
            return RebuildOutcome(
                snapshot=candidate_snapshot,
                lock_ref=file_ref(lock_path),
                report_ref=file_ref(report_path),
            )

    class Evaluator:
        def evaluate(self, candidate, correction, *, final_gate):
            assert correction is not None
            assert final_gate is False
            return report(candidate, accuracy=0.9, passed=True, findings=())

    request_ref = write_optimization_json(
        build_root, "control/request.json", optimization_request.to_wire()
    )
    baseline_ref = write_optimization_json(
        build_root, "control/baseline.json", baseline_report.to_wire()
    )
    jobs.optimization_gateway_factory = lambda *_args: (
        workbench.WorkbenchOptimizationGateways(
            correction_executor=Correction(),
            rebuild_gateway=Rebuilder(),
            evaluation_gateway=Evaluator(),
        )
    )
    code, view = WorkbenchApi(jobs).post(
        f"/api/v1/builds/{key}/optimization-runs",
        {
            "requestRef": request_ref.model_dump(mode="json"),
            "baselineEvaluationRef": baseline_ref.model_dump(mode="json"),
        },
        "owner",
    )

    assert code == 200
    assert view["candidate"]["lockDigest"] != baseline.lock_digest
    assert view["status"] == "completed"
    assert view["verifiability"] == {"status": "verified", "issues": []}
    assert view["rounds"][0]["status"] == "succeeded"
    assert view["rounds"][0]["promoted"] is True
    assert view["evaluation"]["metrics"][0]["value"] == 0.9
    assert view["result"]["stopReason"] == "acceptance-met"


def test_automatic_binding_is_not_published_when_identity_verification_fails(jobs):
    key = completed(jobs)
    build_root = jobs.path(key)
    baseline, _, _ = baseline_candidate(build_root)
    issue = finding()
    optimization_request = request(
        baseline, findings=(issue,), governance=()
    )
    baseline_report = report(
        baseline, accuracy=0.8, passed=False, findings=(issue,)
    )
    descriptor_path = build_root / "candidate/candidate-descriptor.json"
    descriptor_path.write_text('{"candidateId": "tampered"}\n', encoding="utf-8")

    class NotCalled:
        def execute(self, *_args, **_kwargs):
            raise AssertionError("correction must not run without governance")

        def rebuild(self, *_args, **_kwargs):
            raise AssertionError("rebuild must not run without a correction")

        def evaluate(self, *_args, **_kwargs):
            raise AssertionError("evaluation must not run without a candidate")

    with pytest.raises(ValueError, match="OPTIMIZATION_BINDING_INVALID"):
        jobs.run_optimization(
            key,
            "owner",
            optimization_request,
            baseline_report,
            correction_executor=NotCalled(),
            rebuild_gateway=NotCalled(),
            evaluation_gateway=NotCalled(),
        )

    assert "optimizationViewBinding" not in jobs.jobs[key]


def test_optimization_view_fails_closed_for_missing_bound_record(jobs):
    key = completed(jobs)
    binding = complete_binding(jobs.path(key))
    jobs.attach_optimization(key, "owner", binding)
    (jobs.path(key) / binding["request_ref"]["locator"]).unlink()

    _, view = WorkbenchApi(jobs).get(
        f"/api/v1/builds/{key}/optimization-view", "owner"
    )

    assert view["status"] == "unverifiable"
    assert view["verifiability"]["issues"] == [
        "OPTIMIZATION_REFERENCE_MISSING"
    ]


def test_optimization_views_are_isolated_between_solution_builds(jobs):
    first = completed(jobs, key="agent-wealth-previsit")
    second = completed(jobs, key="agent-wealth-diagnosis")
    first_binding = complete_binding(jobs.path(first))
    second_binding = complete_binding(jobs.path(second))
    jobs.attach_optimization(first, "owner", first_binding)
    jobs.attach_optimization(second, "owner", second_binding)

    (jobs.path(first) / first_binding["request_ref"]["locator"]).unlink()
    _, first_view = WorkbenchApi(jobs).get(
        f"/api/v1/builds/{first}/optimization-view", "owner"
    )
    _, second_view = WorkbenchApi(jobs).get(
        f"/api/v1/builds/{second}/optimization-view", "owner"
    )

    assert first_view["status"] == "unverifiable"
    assert second_view["status"] == "completed"
    assert first_view["buildId"] == first
    assert second_view["buildId"] == second


def test_legacy_build_without_binding_remains_visible(jobs):
    key = completed(jobs)

    _, view = WorkbenchApi(jobs).get(
        f"/api/v1/builds/{key}/optimization-view", "owner"
    )

    assert view["status"] == "not-executed"
    assert WorkbenchApi(jobs).inspect(key, "owner")["buildStatus"] == "SUCCEEDED"


@pytest.mark.parametrize("action,body", [
    ("stop", {"instanceId": "old"}), ("sessions", {"instanceId": "old"}),
    ("chat", {"instanceId": "new", "sessionId": "old", "query": "hi"}),
    ("authorization", {"instanceId": "old", "sessionId": "old"}),
    ("revoke", {"instanceId": "old", "sessionId": "old"}),
])
def test_stale_ui_identity_never_calls_host_or_kills_current(jobs, monkeypatch, action, body):
    key = completed(jobs, "RUNNING")
    jobs.jobs[key]["instanceId"] = "new"
    jobs.sessions[key] = "new-session"
    jobs.processes[key] = type("Child", (), {"poll": lambda self: None})()
    monkeypatch.setattr(jobs, "_request", lambda *args, **kwargs: pytest.fail("host was called"))
    monkeypatch.setattr(workbench, "stop_owned_process", lambda *args: pytest.fail("current process was stopped"))
    if action == "stop":
        with pytest.raises(RuntimeError, match="INSTANCE_CHANGED"):
            WorkbenchApi(jobs).post("/api/v1/builds/" + key + "/" + action, body, "owner")
    else:
        code, result = WorkbenchApi(jobs).post("/api/v1/builds/" + key + "/" + action, body, "owner")
        assert code == 409 and result["clearConversation"]
    assert jobs.sessions[key] == "new-session" and jobs.jobs[key]["status"] == "RUNNING"


def test_stale_artifact_does_not_spawn(jobs, monkeypatch):
    key = completed(jobs)
    target = jobs.path(key) / "candidate/target"
    target.mkdir()
    (target / "test.jar").touch()
    for name in workbench.ENV_KEYS:
        monkeypatch.setenv(name, "configured")
    monkeypatch.setattr(workbench, "spawn_process", lambda *args, **kwargs: pytest.fail("spawned"))
    with pytest.raises(RuntimeError, match="ARTIFACT_CHANGED"):
        jobs.start(key, "owner", expected_artifact="sha256:" + "f" * 64)


def test_built_artifact_export_and_retry_survive_instance_failure(jobs, monkeypatch):
    key = completed(jobs, "FAILED")
    assert jobs.artifact(key, "owner").is_file()
    for name in workbench.ENV_KEYS:
        monkeypatch.setenv(name, "configured")
    target = jobs.path(key) / "candidate/target"
    target.mkdir()
    (target / "test.jar").touch()
    with pytest.raises(RuntimeError, match="ARTIFACT_CHANGED"):
        jobs.start(key, "owner", expected_artifact="sha256:" + "f" * 64)
    jobs.processes[key] = type("Child", (), {"poll": lambda self: None})()
    with pytest.raises(ValueError, match="BUILD_OR_STOP_REQUIRED"):
        jobs.start(key, "owner")


def test_start_response_cannot_adopt_later_instance(jobs, monkeypatch):
    key = completed(jobs)
    api = WorkbenchApi(jobs)
    artifact = api.inspect(key, "owner")["artifact"]["artifactId"]
    monkeypatch.setattr("finance_solution_builder.workbench_api.preflight", lambda *args: {"startPrerequisitesSatisfied": True})
    def start(*args, **kwargs):
        jobs.jobs[key].update(status="RUNNING", instanceId="replacement")
        return {"instanceId": "original"}
    monkeypatch.setattr(jobs, "start", start)
    with pytest.raises(ApiError, match="INSTANCE_CHANGED"):
        api.post("/api/v1/builds/" + key + "/start", {"artifactId": artifact}, "owner")


@pytest.mark.parametrize("action", ["start", "stop"])
def test_completion_rejects_replacement_starting_before_new_id_is_written(jobs, monkeypatch, action):
    key = completed(jobs)
    jobs.jobs[key]["instanceId"] = "previous"
    api = WorkbenchApi(jobs)
    artifact = api.inspect(key, "owner")["artifact"]["artifactId"]
    monkeypatch.setattr("finance_solution_builder.workbench_api.preflight", lambda *args: {"startPrerequisitesSatisfied": True})
    def completed_then_restarted(*args, **kwargs):
        jobs.jobs[key]["status"] = "STARTING"
        return {"instanceId": "previous"}
    monkeypatch.setattr(jobs, action, completed_then_restarted)
    body = {"artifactId": artifact} if action == "start" else {"instanceId": "previous"}
    with pytest.raises(ApiError, match="INSTANCE_CHANGED"):
        api.post("/api/v1/builds/" + key + "/" + action, body, "owner")


def test_denial_envelope_omits_accidental_error_payload(jobs, monkeypatch):
    key = completed(jobs)
    monkeypatch.setattr(jobs, "talk", lambda *args, **kwargs: (403, {"error": "AUTHORIZATION_REJECTED", "clearConversation": True,
                                                                         "response": "private financial data"}))
    code, result = WorkbenchApi(jobs).post("/api/v1/builds/" + key + "/chat", {
        "instanceId": "one", "sessionId": "session-one", "query": "hi"}, "owner")
    assert code == 403 and "private" not in json.dumps(result) and result["runId"].startswith("run-")


@pytest.fixture
def http_api(tmp_path, monkeypatch):
    servers = []
    ready = threading.Event()
    def server(*args):
        value = ThreadingHTTPServer(*args)
        servers.append(value)
        ready.set()
        return value
    monkeypatch.setattr(workbench, "PROJECT", tmp_path)
    monkeypatch.setattr(workbench, "ThreadingHTTPServer", server)
    monkeypatch.setattr(workbench.signal, "signal", lambda *args: None)
    thread = threading.Thread(target=workbench.serve, args=(tmp_path, 0))
    thread.start()
    assert ready.wait(5)
    base = "http://127.0.0.1:" + str(servers[0].server_port)
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    with opener.open(base + "/api/bootstrap", timeout=5) as response:
        boot = json.load(response)
    def request(path, body=None, *, csrf=True):
        headers = {"Content-Type": "application/json", "Origin": base}
        if csrf:
            headers["X-CSRF-Token"] = boot["csrf"]
        req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            response = opener.open(req, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.code, json.load(response)
    yield request
    servers[0].shutdown()
    thread.join(10)
    assert not thread.is_alive()


def test_http_contracts_csrf_and_safe_invalid_inputs(http_api, monkeypatch):
    monkeypatch.setenv("FH_MODEL_KEY", "key-private-canary")
    monkeypatch.setenv("FH_MODEL_NAME", "key-private-canary")
    code, result = http_api("/api/v1/catalog")
    assert code == 200 and result["schemaVersion"] == "finance-workbench-api.v0.2"
    code, result = http_api("/api/v1/connections")
    assert code == 200 and "key-private" not in json.dumps(result)
    body = default_selection(["wealth.facts.describe"])
    code, result = http_api("/api/v1/builds", body, csrf=False)
    assert code == 403 and result["error"]["code"] == "ACCESS_REJECTED"
    body["secret"] = "private-input"
    code, result = http_api("/api/v1/preflight", body)
    assert code == 400 and result["error"]["code"] == "SELECTION_INVALID" and "private" not in json.dumps(result)
    code, result = http_api("/api/v1/builds")
    assert code == 200 and result["builds"] == []
    code, result = http_api("/api/v1/builds/unknown")
    assert code == 403
    code, result = http_api(
        "/api/v1/builds/unknown/optimization-runs",
        {"requestRef": {}, "baselineEvaluationRef": {}},
    )
    assert code == 403 and result["error"]["code"] == "ACCESS_REJECTED"
