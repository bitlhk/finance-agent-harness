"""Unit boundaries only: mocked Java qualification is never executable acceptance evidence."""
from copy import deepcopy
import json
from pathlib import Path
import shutil
from types import SimpleNamespace

import pytest

from finance_solution_builder import agentscope_service as service
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.candidate_backend import agentscope_java as backend
from finance_solution_builder.candidate_backend.models import PreflightRequest, LifecycleStatus
from finance_solution_builder.component_assembly import AssemblyRejected
from finance_solution_builder.managed_launcher import AGENTSCOPE_ENTRYPOINT, launch_command, require_reuse_runtime, jar_inventory, locked_classpath
from finance_solution_builder.service import digest, write_json
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_contracts import parse_selection
from tests.test_component_assembly import PROJECT, root
from tests.test_managed_runtime_selection import candidate, body

REAL_PREFLIGHT = backend.preflight_managed_admission


def test_generator_materializes_selected_host_support_not_default(native):
    index = native.project / 'managed-support/runtime-hosts.v0.1.json'
    declaration = json.loads(index.read_bytes())
    host = declaration['runtimes'][0]['hosts'][0]
    host['supportFile'] = 'alternate-host-support.json'
    template = native.project / 'templates/agentscope-query-service'
    selected = json.loads((template / 'host-support.v0.1.json').read_bytes())
    # Same supported operations, different immutable source bytes and identity.
    (template / host['supportFile']).write_text(json.dumps(selected, indent=4) + '\n')
    write_json(index, declaration)
    request = parse_selection(body((native.project, native.harness)))
    solution, resolved, _ = request.resolve_details(native.project, native.harness, 'selected-host-source')
    build = service.AgentScopeServiceBuild(native.builder, solution, native.output,
                                           resolved['runtimeBinding'], None)
    build.assemble()
    assert (build.candidate / 'host-support.v0.1.json').read_bytes() == (template / host['supportFile']).read_bytes()
    assert 'sha256:' + digest(build.candidate / 'host-support.v0.1.json') == build.plan['managedTemplate']['supportDigest']


@pytest.fixture
def native(candidate, tmp_path, monkeypatch):
    project, harness = candidate
    fixture_support = (project / "templates/agentscope-query-service/host-support.v0.1.json").read_bytes()
    support_file = project / "templates/agentscope-query-service/agentscope-support.v0.1.json"
    fixture_profile = support_file.read_bytes()
    shutil.copytree(PROJECT / "templates/agentscope-query-service", project / "templates/agentscope-query-service", dirs_exist_ok=True)
    (project / "templates/agentscope-query-service/host-support.v0.1.json").write_bytes(fixture_support)
    support = json.loads(fixture_profile)
    support.update(descriptions=[], terminology=[], observationRequirements=[])
    write_json(support_file, support)
    request = parse_selection(body(candidate))
    solution, directory, plan = request.resolve_details(project, harness, "agent-" + "a" * 16)
    for ref in (solution["profileRef"], solution["evaluationPackRef"]):
        target = harness / ref["source"].removeprefix("repo://")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("fixture build-only asset")
    monkeypatch.setattr(service, "revision", lambda *_: {"commit": "a" * 40, "worktreeDirty": False})
    admission = {"status": "READY", "requiredSatisfied": True, "unitFixtureOnly": True}
    import hashlib
    admission["runtimeDependencies"] = {"agentscope-core.jar": hashlib.sha256(b"test-dependency").hexdigest()}
    monkeypatch.setattr(backend, "preflight_managed_admission", lambda *_a, **_k: deepcopy(admission))
    builder = SolutionBuilder(project, harness, service_output_root=tmp_path / "builds")
    return SimpleNamespace(project=project, harness=harness, request=request, solution=solution,
        directory=directory, binding=plan["runtimeBinding"], builder=builder,
        output=tmp_path / "builds/agent-native", admission=admission)


def test_real_registry_assemble_build_generate_native_files(native, monkeypatch):
    events = []
    def stage(self, name, command, cwd, *, maven=False):
        assert maven == (name == "candidate_package")
        events.append(name)
        if name == "candidate_package":
            (self.candidate / "target/dependency").mkdir(parents=True)
            (self.candidate / "target/native.jar").write_bytes(b"test-jar-not-executable")
            (self.candidate / "target/dependency/agentscope-core.jar").write_bytes(b"test-dependency")
        else:
            assert command[-2].endswith(".AgentScopeAdmission")
            write_json(self.candidate / "component-installation-plan.json", {"capabilities": [
                {"definition": cap, "providerId": self.solution["capabilityBindings"][cap["capabilityId"]]}
                for cap in self.plan["capabilities"]]})
    monkeypatch.setattr(service.AgentScopeServiceBuild, "stage", stage)
    monkeypatch.setattr("finance_solution_builder.service.build_service", lambda *_a, **_k: pytest.fail("old generator"))
    original_select = backend.BackendRegistry.select
    def select(self, *args, **kwargs):
        events.append("registry")
        selected = original_select(self, *args, **kwargs)
        for operation in ("preflight", "assemble", "build"):
            original = getattr(selected, operation)
            def wrapped(request, original=original, operation=operation):
                events.append(operation)
                return original(request)
            monkeypatch.setattr(selected, operation, wrapped)
        return selected
    monkeypatch.setattr(backend.BackendRegistry, "select", select)
    result = backend.build_managed_candidate(native.builder, native.solution, native.output,
        runtime_binding=native.binding, runtime_admission=native.admission)
    assert result.status == "BUILT"
    assert events == ["registry", "preflight", "assemble", "preflight", "build", "candidate_package", "component_installation"]
    lock = json.loads(result.lock_path.read_bytes())
    assert lock["runtime"] == native.solution["runtime"]
    assert lock["runtimeBinding"] == native.binding
    assert lock["managedEntrypoint"] == AGENTSCOPE_ENTRYPOINT
    assert lock["runtimePresentation"] == native.binding["runtimePresentation"]
    plan = json.loads((result.candidate_dir / "component-plan.json").read_bytes())
    assert plan["managedTemplate"] == native.binding["managedTemplate"]
    assert plan["runtimeBinding"] == native.binding
    assert lock["managedTemplateDigest"] == plan["managedTemplate"]["digest"]
    assert lock["hostTemplateDigest"] == service.host_template_digest(native.project, native.binding)
    assert lock["managedTemplateDigest"] != lock["hostTemplateDigest"]
    assert lock["sharedHostSources"] == json.loads((native.output / "template-sources.json").read_bytes())
    assert lock["sharedHostSources"] == service.host_source_inventory(native.project, native.binding)
    original = json.loads((result.candidate_dir / "solution-input.json").read_bytes())
    installed = json.loads((result.candidate_dir / "solution.json").read_bytes())
    assert original == native.solution
    assert original["evaluationPackRef"] and "evaluationPackRef" not in installed
    assert installed["providerRefs"][0]["source"].endswith("/assembly.v0.1.json")
    assert installed["capabilityPackRefs"][0]["source"].endswith("/pack.yaml")
    assert all(ref["digest"].startswith("sha256:") for ref in installed["providerRefs"] + installed["capabilityPackRefs"])
    descriptor = json.loads((native.output / "candidate-descriptor.json").read_bytes())
    assert descriptor["backend"]["backendId"] == "agentscope-java"
    assert descriptor["invocation"]["protocol"] == "finance-chat"
    assert descriptor["extensions"][0]["payload"]["status"] == "PREPARED_NOT_RUNTIME_REGISTERED"
    assert descriptor["provenanceRefs"][0]["locator"] == "candidate/component-installation-plan.json"
    assert not (result.candidate_dir / "src/main/java/io/github/bitlhk/finance/builder/local/ReadOnlyProviders.java").exists()
    report = json.loads(result.report_path.read_bytes())
    assert report["plannedStages"] == ["candidate_package", "component_installation"]


def test_generate_only_never_claims_compiled(native):
    result = backend.build_managed_candidate(native.builder, native.solution, native.output, verify=False,
        runtime_binding=native.binding, runtime_admission=native.admission)
    assert result.status == "NOT_RUN"
    assert json.loads(result.lock_path.read_bytes())["verification"] == "NOT_RUN"
    assert not (native.output / "candidate-descriptor.json").exists()


def test_host_support_source_uses_frozen_selected_template_bytes(native):
    expected = service.support_profile_bytes(native.project, native.harness, native.binding)
    target = native.project / "templates/agentscope-query-service/agentscope-support.v0.1.json"
    target.write_bytes(expected)
    binding = deepcopy(native.binding)
    binding["supportProfile"] = {"source": "builder-template://agentscope-query-service/agentscope-support.v0.1.json",
                                 "digest": "sha256:" + digest(target)}
    assert service.support_profile_bytes(native.project, native.harness, binding) == expected
    target.write_bytes(expected + b" ")
    with pytest.raises(AssemblyRejected, match="BINDING_MISMATCH"):
        service.support_profile_bytes(native.project, native.harness, binding)


@pytest.mark.parametrize("source", ["builder-template://other-host/support.json",
    "builder-template://agentscope-query-service/../other-host/support.json", "file:///tmp/support.json",
    "builder-template://agentscope-query-service/undeclared.json", "repo://integrations/agentscope-java/reference/support.json"])
def test_host_support_source_rejects_other_template_or_unbounded_reference(native, source):
    binding = deepcopy(native.binding)
    binding["supportProfile"]["source"] = source
    with pytest.raises(AssemblyRejected, match="BINDING_MISMATCH"):
        service.support_profile_bytes(native.project, native.harness, binding)


def test_builder_dispatches_managed_registry_before_legacy(native, monkeypatch, tmp_path):
    source = tmp_path / "input.json"
    write_json(source, native.solution)
    monkeypatch.setattr("finance_solution_builder.builder.validate_solution", lambda *_: None)
    calls = []
    result = object()
    monkeypatch.setattr(backend, "build_managed_candidate", lambda *a, **k: calls.append((a, k)) or result)
    assert native.builder.build(source, native.output, managed=True, runtime_binding=native.binding,
        runtime_admission=native.admission) is result
    assert calls[0][1]["runtime_binding"] == native.binding


@pytest.mark.parametrize("field,value", [("invocation_protocol", "a2a"), ("runtime_version", "99"),
                                        ("host_wiring_ids", ("wrong-host",)), ("required_capability_ids", ("other.query",))])
def test_preflight_target_mismatch_rejected(native, field, value):
    instance = backend.AgentScopeJavaBackend(builder=native.builder, solution=native.solution,
        output=native.output, runtime_binding=native.binding)
    target = instance.target().model_copy(update={field: value})
    result = instance.preflight(PreflightRequest(task_id=native.solution["solutionId"], target=target,
                                                resolved_asset_ids=target.required_asset_ids))
    assert result.outcome.status == LifecycleStatus.BLOCKED
    assert not native.output.exists()


def test_preflight_qualification_changed_after_queue_rejected(native):
    with pytest.raises(AssemblyRejected, match="QUALIFICATION_REJECTED"):
        backend.build_managed_candidate(native.builder, native.solution, native.output,
            runtime_binding=native.binding, runtime_admission={**native.admission, "changed": True})
    assert not native.output.exists()


def test_unconfigured_actual_qualification_fails_closed(candidate, monkeypatch):
    # This test uses the real hook, not the native fixture's successful test double.
    request = parse_selection(body(candidate))
    solution, _, plan = request.resolve_details(*candidate, "unit-preflight")
    with pytest.raises(AssemblyRejected, match="QUALIFICATION_REQUIRED"):
        backend.preflight_managed_admission(*candidate, solution, plan["runtimeBinding"], environment={})


def test_jobs_freeze_display_and_binding_and_owner(native, monkeypatch, tmp_path):
    jobs = Jobs(native.project, native.harness, data_dir=tmp_path / "jobs")
    monkeypatch.setattr(jobs.queue, "submit", lambda *_: None)
    calls = []
    monkeypatch.setattr(jobs.builder, "build", lambda *a, **k: calls.append(k))
    try:
        job = jobs.create(native.request.legacy(), "owner-a", selection=native.request.model_dump())
        expected = deepcopy(job["runtimePresentation"])
        native.binding["runtimePresentation"]["label"] = "Changed outside job"
        jobs.build(job["id"])
        assert calls[0]["runtime_binding"] == jobs.jobs[job["id"]]["runtimeBinding"]
        assert calls[0]["runtime_admission"] == native.admission
        assert calls[0]["managed"] is True
        assert jobs.public(jobs.jobs[job["id"]])["runtimePresentation"] == expected
        stored = json.loads((jobs.records / (job["id"] + ".json")).read_bytes())
        assert stored["runtimePresentation"] == expected
        with pytest.raises(PermissionError):
            jobs.owned(job["id"], "owner-b")
    finally:
        jobs.close()


def test_jobs_reject_before_queue_when_qualification_missing(candidate, monkeypatch, tmp_path):
    jobs = Jobs(*candidate, data_dir=tmp_path / "jobs")
    monkeypatch.setattr(jobs.queue, "submit", lambda *_: pytest.fail("unqualified request queued"))
    monkeypatch.delenv("FH_AGENTSCOPE_ADMISSION_CANDIDATE", raising=False)
    try:
        selected = parse_selection(body(candidate))
        with pytest.raises(AssemblyRejected, match="QUALIFICATION"):
            jobs.create(selected.legacy(), "owner", selection=selected.model_dump())
        assert not jobs.jobs and not list(jobs.inputs.iterdir())
    finally:
        jobs.close()


@pytest.mark.parametrize("runtime", [{"family": "agentscope", "language": "java"},
                                    {"family": "openjiuwen", "language": "python"}])
def test_new_and_python_never_accept_binary_seed(tmp_path, runtime):
    with pytest.raises(ValueError, match="REUSE_UNSUPPORTED"):
        require_reuse_runtime(tmp_path / "nonexistent", runtime)


def test_java_seed_requires_exact_runtime(tmp_path):
    write_json(tmp_path / "service-lock.json", {"runtime": {"family": "openjiuwen", "language": "java", "version": "old"}})
    with pytest.raises(AssemblyRejected, match="REUSE_UNSUPPORTED"):
        require_reuse_runtime(tmp_path, {"family": "openjiuwen", "language": "java", "version": "new"})


@pytest.mark.parametrize("runtime", [{"family": "agentscope", "language": "java"},
                                    {"family": "openjiuwen", "language": "python"}])
def test_method_build_does_not_implicitly_create_cross_runtime_seed(native, tmp_path, monkeypatch, runtime):
    jobs = Jobs(native.project, native.harness, data_dir=tmp_path / "jobs")
    identity = "agent-" + "b" * 16
    job = {"id": identity, "owner": "owner", "status": "QUEUED", "capabilities": [],
           "methodRef": {"assetId": "private"}}
    jobs.jobs[identity] = job
    write_json(jobs.inputs / (identity + ".json"), {"runtime": runtime})
    monkeypatch.setattr(jobs.method_assets, "release", lambda *_: {})
    monkeypatch.setattr(jobs.method_assets, "check_source", lambda *_: None)
    monkeypatch.setattr("finance_solution_builder.method_trials.reusable_seed", lambda *_: pytest.fail("cross-runtime seed"))
    calls = []
    monkeypatch.setattr(jobs.builder, "build", lambda *_a, **k: calls.append(k))
    try:
        jobs.build(identity)
        assert jobs.jobs[identity]["status"] == "BUILT"
        assert "reuse_candidate" not in calls[0]
    finally:
        jobs.close()


@pytest.fixture
def artifact(tmp_path):
    target = tmp_path / "target"
    target.mkdir()
    (target / "native.jar").write_bytes(b"fixture")
    from finance_solution_builder.managed_runtime_support import RUNTIME
    runtime = deepcopy(RUNTIME)
    lock = {"runtime": runtime, "verification": "COMPILED", "managedEntrypoint": AGENTSCOPE_ENTRYPOINT,
            "runtimeBinding": {"runtime": runtime, "backendId": "agentscope-java", "runtimeVersion": "2.0.3",
                               "invocationProtocol": "finance-chat"},
            "files": {"target/native.jar": digest(target / "native.jar")}}
    return tmp_path, lock


def test_launch_uses_only_frozen_artifact_no_catalog(artifact, monkeypatch):
    monkeypatch.setattr("finance_solution_builder.unified_catalog.unified_catalog", lambda *_: pytest.fail("latest catalog"))
    command = launch_command(*artifact)
    assert command[0] == "java" and command[-2] == AGENTSCOPE_ENTRYPOINT["mainClass"]


def test_launch_uses_the_java_executable_frozen_with_the_artifact(artifact):
    candidate, lock = artifact
    executable = Path(shutil.which("java")).resolve()
    lock["javaExecutable"] = str(executable)
    assert launch_command(candidate, lock)[0] == str(executable)


@pytest.mark.parametrize("mutation", ["extra-jar", "uppercase-extra", "dependency-symlink", "target-symlink",
    "tampered-jar", "symlink", "entrypoint", "binding", "uncompiled", "unknown-runtime", "forged-coordinate"])
def test_launch_refuses_unlocked_or_wrong_runtime_artifact(artifact, mutation, tmp_path):
    candidate, lock = artifact
    if mutation in {"extra-jar", "uppercase-extra"}:
        dependency = candidate / "target/dependency"
        dependency.mkdir()
        (dependency / ("extra.JAR" if mutation == "uppercase-extra" else "extra.jar")).write_bytes(b"unexpected")
    if mutation == "dependency-symlink":
        (candidate / "outside-dependency").mkdir()
        (candidate / "target/dependency").symlink_to(candidate / "outside-dependency", target_is_directory=True)
    if mutation == "target-symlink":
        (candidate / "target").rename(candidate / "outside-target")
        (candidate / "target").symlink_to(candidate / "outside-target", target_is_directory=True)
    if mutation == "tampered-jar":
        (candidate / "target/native.jar").write_bytes(b"changed")
    if mutation == "symlink":
        target = candidate / "target/native.jar"
        target.rename(candidate / "actual.jar")
        target.symlink_to(candidate / "actual.jar")
    if mutation == "entrypoint": lock["managedEntrypoint"] = {"mainClass": "arbitrary.Class"}
    if mutation == "binding": lock["runtimeBinding"]["runtimeVersion"] = "99"
    if mutation == "uncompiled": lock["verification"] = "NOT_RUN"
    if mutation == "unknown-runtime": lock["runtime"]["family"] = "unknown"
    if mutation == "forged-coordinate": lock["runtime"]["templateRef"] = "maven://fake:sdk:2.0.3"
    with pytest.raises(ValueError):
        launch_command(candidate, lock)


def test_cancel_before_stage_does_not_spawn_and_reports_terminal(native, monkeypatch):
    import threading
    native.builder.service_cancel = threading.Event()
    native.builder.service_cancel.set()
    build = service.AgentScopeServiceBuild(native.builder, native.solution, native.output, native.binding, native.admission)
    build.assemble()
    monkeypatch.setattr(service, "spawn_process", lambda *_a, **_k: pytest.fail("cancelled build spawned"))
    with pytest.raises(RuntimeError, match="BUILD_STAGE_FAILED"):
        build.build()
    report = json.loads(build.report_path.read_bytes())
    assert report["status"] == "FAILED"
    assert report["stages"][0]["status"] == "FAIL" and report["stages"][0]["exitCode"] == 130
    assert native.builder.service_process is None


@pytest.fixture
def owned_backend(native, tmp_path, monkeypatch, request):
    import subprocess
    import sys
    from finance_solution_builder.candidate_backend.models import (
        CandidateDescriptor, CandidateArtifact, CandidateEntrypoint, CandidateResourceRef,
        ProtocolBinding, RuntimeIdentity, StartRequest, verify_candidate_artifact)
    from finance_builder_base import ResourceKind
    from finance_solution_builder.workbench import ENV_KEYS
    jobs = Jobs(native.project, native.harness, data_dir=tmp_path / "lifecycle")
    identity = native.solution["solutionId"]
    output = jobs.path(identity)
    candidate = output / "candidate"
    (candidate / "target").mkdir(parents=True)
    main_path = "target/" + getattr(request, "param", "native.jar")
    (candidate / main_path).write_bytes(b"unit-process-fixture")
    lock = {"runtime": native.solution["runtime"], "runtimeBinding": native.binding,
            "verification": "COMPILED", "managedEntrypoint": AGENTSCOPE_ENTRYPOINT,
            "runtimeAdmission": {"nativeQualificationDigest": "sha256:" + "a" * 64,
                                 "hostQualificationDigest": "b" * 64},
            "files": {main_path: digest(candidate / main_path)}}
    write_json(candidate / "service-lock.json", lock)
    checksum = digest(candidate / "service-lock.json")
    write_json(output / "build-report.json", {"status": "BUILT", "lockSha256": checksum})
    jobs.jobs[identity] = {"id": identity, "owner": "owner-a", "status": "BUILT", "capabilities": [],
                           "runtimeBinding": native.binding}
    adapter = backend.AgentScopeJavaBackend(builder=native.builder, solution=native.solution, output=output,
        runtime_binding=native.binding, jobs=jobs, owner="owner-a")
    resource = CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="candidate/service-lock.json", sha256=checksum)
    descriptor = CandidateDescriptor(task_id=identity, candidate_id=identity, backend=adapter.identity,
        language="java", runtime=RuntimeIdentity(family="agentscope", version="2.0.3"),
        artifact=CandidateArtifact(artifact_id=identity, artifact_kind="directory-manifest", resource=resource),
        entrypoint=CandidateEntrypoint(mode="managed-artifact", resource=resource),
        invocation=ProtocolBinding(protocol="finance-chat", path="/sessions"),
        health=ProtocolBinding(protocol="http", path="/health"), lock_digest=checksum,
        installed_asset_ids=("fixture-asset",))
    write_json(output / "candidate-descriptor.json", descriptor.to_wire())
    request = StartRequest(descriptor=descriptor, artifact_verification=verify_candidate_artifact(descriptor, output))
    for key in ENV_KEYS:
        monkeypatch.setenv(key, "unit-test-only")
    monkeypatch.setenv("FH_AGENTSCOPE_QUALIFICATION_SHA256", lock["runtimeAdmission"]["nativeQualificationDigest"])
    monkeypatch.setenv("FH_OBSERVATION_QUALIFICATION_SHA256", lock["runtimeAdmission"]["hostQualificationDigest"])
    from finance_solution_builder.candidate_backend.restricted_response import owner_ref
    monkeypatch.setenv("FH_SNAPSHOTS_ENABLED", "true")
    monkeypatch.setenv("FH_SNAPSHOT_CAPTURE_OWNERS", owner_ref("owner-a"))
    monkeypatch.delenv("FH_SNAPSHOT_VIEW_OWNERS", raising=False)
    monkeypatch.setenv("FH_CANDIDATE_RESPONSE_MAX_FILES", "65")
    monkeypatch.setenv("FH_CANDIDATE_RESPONSE_MAX_BYTES", str(8 * 1024 * 1024))
    monkeypatch.setenv("FH_CANDIDATE_RESPONSE_RETENTION_SECONDS", "60")
    children, routes = [], []
    def spawn(command, **kwargs):
        assert command[-2] == AGENTSCOPE_ENTRYPOINT["mainClass"]
        child = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(120)"])
        children.append(child)
        write_json(candidate / "instance.json", {"pid": child.pid, "buildId": identity, "port": 1,
                                                  "instanceId": str(child.pid)})
        return child
    def response(job_id, route, body=None, **kwargs):
        routes.append(route)
        if route == "/health":
            return 200, {"artifactLock": lock, "instanceId": jobs.jobs[identity]["instanceId"],
                         "runtime": "agentscope-java-2.0.3"}
        if route == "/sessions":
            return 200, {"sessionId": "owned-session"}
        assert route == "/sessions/owned-session/chat"
        return 200, {"response": "unit response", "requestId": "host-request", "executions": [
            {"result": {"status": "COMPLETED", "data": {"unit": "private tool result"}}}]}
    monkeypatch.setattr("finance_solution_builder.workbench.spawn_process", spawn)
    monkeypatch.setattr(jobs, "_request", response)
    monkeypatch.setattr("finance_solution_builder.workbench.Jobs", lambda *_a, **_k: pytest.fail("Backend recursively created Jobs"))
    yield SimpleNamespace(adapter=adapter, jobs=jobs, request=request, identity=identity, children=children, routes=routes)
    jobs.close()
    for child in children:
        if child.poll() is None:
            child.terminate()
        child.wait(timeout=5)


def test_spi_owned_start_two_turns_stop_uses_existing_jobs(owned_backend):
    from finance_builder_base import ResourceKind
    from finance_solution_builder.candidate_backend.models import (
        HealthRequest, InvocationRequest, StopRequest, CandidateResourceRef)
    rig = owned_backend
    started = rig.adapter.start(rig.request)
    assert started.outcome.status == LifecycleStatus.PASSED and len(rig.children) == 1
    assert rig.adapter.health(HealthRequest(handle=started.handle)).ready
    for index in range(2):
        path = rig.adapter.output / ("turn-" + str(index) + ".json")
        write_json(path, {"query": "test query"})
        result = rig.adapter.invoke(InvocationRequest(handle=started.handle, request_id="turn-" + str(index),
            protocol="finance-chat", payload_ref=CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH,
                locator=path.name, sha256=digest(path))))
        assert result.outcome.status == LifecycleStatus.PASSED
        actual = json.loads((rig.adapter.output / result.response_ref.locator).read_bytes())
        assert actual["response"] == "unit response" and actual["executions"][0]["result"]["status"] == "COMPLETED"
        receipt = json.loads((rig.adapter.output / result.outcome.evidence_refs[0].locator).read_bytes())
        assert receipt["delivery"] == "CAPTURED" and receipt["facts"]["businessOutcome"] == "NOT_ASSESSED"
        assert "unit response" not in json.dumps(receipt) and "private tool result" not in json.dumps(receipt)
        assert result.response_ref.locator.startswith("runtime/responses/")
        assert result.response_ref.media_type == "application/json"
        assert not (rig.adapter.output / "backend-evidence").exists()
    assert rig.routes.count("/sessions") == 1 and rig.routes.count("/sessions/owned-session/chat") == 2
    stopped = rig.adapter.stop(StopRequest(handle=started.handle))
    assert stopped.stopped and rig.children[0].poll() is not None
    assert rig.identity not in rig.jobs.processes and rig.identity not in rig.jobs.sessions
    assert rig.adapter.stop(StopRequest(handle=started.handle)).stopped
    assert not rig.adapter.health(HealthRequest(handle=started.handle)).ready


def test_spi_cross_owner_start_never_spawns(owned_backend):
    rig = owned_backend
    rig.adapter.owner = "owner-b"
    assert rig.adapter.start(rig.request).outcome.status == LifecycleStatus.BLOCKED
    assert not rig.children


@pytest.mark.parametrize("owned_backend", ["native.JAR"], indirect=True)
def test_owned_jobs_accepts_locked_uppercase_main_jar(owned_backend):
    from finance_solution_builder.candidate_backend.models import StopRequest
    rig = owned_backend
    started = rig.adapter.start(rig.request)
    assert started.outcome.status == LifecycleStatus.PASSED and len(rig.children) == 1
    assert rig.adapter.stop(StopRequest(handle=started.handle)).stopped
    assert rig.children[0].poll() is not None


def test_spi_old_handle_does_not_stop_replacement(owned_backend):
    from finance_solution_builder.candidate_backend.models import StopRequest, HealthRequest
    rig = owned_backend
    started = rig.adapter.start(rig.request)
    assert started.handle is not None
    rig.jobs.stop(rig.identity, "owner-a")
    rig.jobs.start(rig.identity, "owner-a")
    replacement = rig.children[-1]
    assert not rig.adapter.health(HealthRequest(handle=started.handle)).ready
    assert not rig.adapter.stop(StopRequest(handle=started.handle)).stopped
    assert replacement.poll() is None and rig.jobs.processes[rig.identity] is replacement


def test_spi_failed_handle_creation_cleans_started_owned_process(owned_backend, monkeypatch):
    rig = owned_backend
    monkeypatch.setattr(backend, "RunHandle", lambda **_k: (_ for _ in ()).throw(ValueError("unit failure")))
    result = rig.adapter.start(rig.request)
    assert result.outcome.status == LifecycleStatus.BLOCKED
    assert len(rig.children) == 1 and rig.children[0].poll() is not None
    assert rig.identity not in rig.jobs.processes


def test_start_rejects_replaced_qualification_without_spawning(owned_backend, monkeypatch):
    monkeypatch.setenv("FH_AGENTSCOPE_QUALIFICATION_SHA256", "sha256:" + "c" * 64)
    assert owned_backend.adapter.start(owned_backend.request).outcome.status == LifecycleStatus.BLOCKED
    assert not owned_backend.children


def test_java_assessment_requires_actual_ready_verified_items():
    assessment = {"schemaVersion": "finance-observation-assessment.v0.1", "status": "READY", "items": [{
        "requirement": {"semanticId": "execution.history", "boundary": "HOST", "obligation": "REQUIRED"},
        "collectorId": "fixture", "collectorVersion": "fixture-digest", "readiness": "READY", "capture": "NOT_COLLECTED",
        "descriptor": {"verification": "VERIFIED", "support": "SUPPORTED", "availability": "ENABLED"}}]}
    result = backend._ready_assessment(assessment)
    assert result["requiredSatisfied"] is True and result["items"][0]["capture"] == "NOT_COLLECTED"
    assessment["items"][0]["descriptor"]["verification"] = "NOT_CHECKED"
    with pytest.raises(ValueError):
        backend._ready_assessment(assessment)


@pytest.mark.parametrize("value", [
    {"status": "READY", "requiredSatisfied": True},
    {"schemaVersion": "finance-observation-assessment.v0.1", "status": "READY", "items": []},
    {"schemaVersion": "finance-observation-assessment.v0.1", "status": "LEGACY_UNASSESSED", "items": []},
])
def test_declared_assessment_flag_is_not_qualification(value):
    with pytest.raises(ValueError):
        backend._ready_assessment(value)


def test_optional_gap_is_preserved_and_does_not_block_preparation(native, monkeypatch):
    assessment = {"schemaVersion": "finance-observation-assessment.v0.1", "status": "OPTIONAL_GAPS", "items": [{
        "requirement": {"semanticId": "execution.history", "boundary": "HOST", "obligation": "REQUIRED"},
        "collectorId": "fixture", "collectorVersion": "fixture-digest", "readiness": "READY", "capture": "NOT_COLLECTED",
        "descriptor": {"verification": "VERIFIED", "support": "SUPPORTED", "availability": "ENABLED"}}, {
        "requirement": {"semanticId": "model.client.snapshot", "boundary": "MODEL_CLIENT", "obligation": "OPTIONAL"},
        "collectorId": None, "collectorVersion": None, "readiness": "DISABLED", "capture": "NOT_COLLECTED", "descriptor": None}]}
    projected = backend._ready_assessment(assessment)
    assert projected["status"] == "OPTIONAL_GAPS" and projected["requiredSatisfied"] is True
    assert projected["items"][1]["readiness"] == "DISABLED"
    native.admission["assessments"] = {"hostObservationAssessment": projected}
    monkeypatch.setattr(backend, "preflight_managed_admission", lambda *_a, **_k: deepcopy(native.admission))
    result = backend.build_managed_candidate(native.builder, native.solution, native.output, verify=False,
        runtime_binding=native.binding, runtime_admission=native.admission)
    assert result.status == "NOT_RUN"
    assert json.loads(result.lock_path.read_bytes())["runtimeAdmission"]["assessments"]["hostObservationAssessment"] == projected
    assessment["items"][0]["readiness"] = "DISABLED"
    with pytest.raises(ValueError):
        backend._ready_assessment(assessment)


def test_spi_durations_are_monotonic_measurements(native, monkeypatch):
    instance = backend.AgentScopeJavaBackend(builder=native.builder, solution=native.solution, output=native.output,
                                             runtime_binding=native.binding)
    ticks = iter([10.0, 10.125])
    monkeypatch.setattr(backend.time, "monotonic", lambda: next(ticks))
    @backend._measured
    def measured():
        return backend.HealthResult(outcome=instance._outcome(backend.LifecycleOperation.HEALTH), ready=True)
    assert measured().outcome.duration_ms == 125


def _invocation(rig, handle, request_id):
    from finance_builder_base import ResourceKind
    from finance_solution_builder.candidate_backend.models import InvocationRequest, CandidateResourceRef
    path = rig.adapter.output / "request.json"
    write_json(path, {"query": "private query must not persist in receipts"})
    return rig.adapter.invoke(InvocationRequest(handle=handle, request_id=request_id, protocol="finance-chat",
        payload_ref=CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator=path.name, sha256=digest(path))))


def test_duplicate_spi_request_is_immutable_and_not_invoked_twice(owned_backend):
    rig = owned_backend
    handle = rig.adapter.start(rig.request).handle
    first = _invocation(rig, handle, "same-id")
    assert first.outcome.status == LifecycleStatus.PASSED
    path = rig.adapter.output / first.response_ref.locator
    original = path.read_bytes()
    routes = list(rig.routes)
    assert _invocation(rig, handle, "same-id").outcome.status == LifecycleStatus.BLOCKED
    assert path.read_bytes() == original and rig.routes == routes


def test_spi_receipt_symlink_never_writes_outside_job(owned_backend, tmp_path):
    rig = owned_backend
    handle = rig.adapter.start(rig.request).handle
    outside = tmp_path / "outside"
    outside.mkdir()
    (rig.adapter.output / "runtime").symlink_to(outside, target_is_directory=True)
    routes = list(rig.routes)
    assert _invocation(rig, handle, "denied-symlink").outcome.status == LifecycleStatus.BLOCKED
    assert not list(outside.iterdir()) and rig.routes == routes


def test_spi_receipt_quota_rejects_before_invocation(owned_backend, monkeypatch):
    rig = owned_backend
    handle = rig.adapter.start(rig.request).handle
    monkeypatch.setenv("FH_CANDIDATE_RESPONSE_MAX_FILES", "4")
    routes = list(rig.routes)
    assert _invocation(rig, handle, "quota-denied").outcome.status == LifecycleStatus.BLOCKED
    assert rig.routes == routes


@pytest.fixture
def private_method(native, tmp_path):
    from finance_solution_builder.method_assets import MethodAssets
    from tests.test_method_assets import content
    store = MethodAssets(tmp_path / "private-method-store")
    data = content()
    data["capabilityIds"] = ["account.balance.query"]
    draft = store.create("owner-a", data)
    # A store fixture, not a runtime qualification or acceptance claim.
    trial = store.record_trial("owner-a", draft["assetId"], draft,
                               {"status": "PASS", "runtimeVerified": True, "model": "CONTROLLED_TEST"})
    release = store.publish("owner-a", draft["assetId"], 1, "1.0.0", trial["trialId"])
    ref = {key: release[key] for key in ("assetId", "version", "releaseDigest")}
    return store, ref, store.release("owner-a", ref)


def test_private_method_is_projected_from_frozen_release_not_current_draft(native, private_method):
    from tests.test_method_assets import content
    store, ref, snapshot = private_method
    changed = content()
    changed["capabilityIds"] = ["account.balance.query"]
    changed["resources"]["references/policy.md"] = "CHANGED-DRAFT-MUST-NOT-INSTALL"
    store.update("owner-a", ref["assetId"], 1, changed)
    selected = parse_selection(body((native.project, native.harness), methodRef=ref))
    solution, _, plan = selected.resolve_details(native.project, native.harness, native.solution["solutionId"], snapshot)
    result = backend.build_managed_candidate(native.builder, solution, native.output, verify=False,
        runtime_binding=plan["runtimeBinding"], runtime_admission=native.admission, method_snapshot=snapshot)
    installed = json.loads((result.candidate_dir / "solution.json").read_bytes())
    assert installed["domainPackRefs"][0]["id"] == snapshot["assetId"]
    assert installed["domainPackRefs"][0]["digest"] == snapshot["installationDigest"]
    text = next((result.candidate_dir / "assets/private-methods").rglob("SKILL.md")).read_text()
    assert "SOURCE-MARKER-ONE" in text and "CHANGED-DRAFT-MUST-NOT-INSTALL" not in text
    lock = json.loads(result.lock_path.read_bytes())
    assert lock["method"]["releaseDigest"] == ref["releaseDigest"]
    assert lock["method"]["installationDigest"] == snapshot["installationDigest"]


def test_jobs_pass_owner_verified_private_snapshot_to_prequeue(native, private_method, tmp_path, monkeypatch):
    store, ref, snapshot = private_method
    jobs = Jobs(native.project, native.harness, data_dir=tmp_path / "private-jobs")
    jobs.method_assets = store
    monkeypatch.setattr(jobs.queue, "submit", lambda *_: None)
    calls = []
    def admission(*_args, **kwargs):
        calls.append(kwargs["method_snapshot"])
        assert kwargs["method_snapshot"] == snapshot
        return native.admission
    monkeypatch.setattr(backend, "preflight_managed_admission", admission)
    monkeypatch.setattr("finance_solution_builder.method_trials.reusable_seed", lambda *_: pytest.fail("native private Java seed"))
    built = []
    monkeypatch.setattr(jobs.builder, "build", lambda *_a, **k: built.append(k))
    try:
        selected = parse_selection(body((native.project, native.harness), methodRef=ref))
        job = jobs.create(selected.legacy(), "owner-a", selection=selected.model_dump())
        assert calls == [snapshot]
        jobs.build(job["id"])
        assert built[0]["method_snapshot"] == snapshot and "reuse_candidate" not in built[0]
        with pytest.raises(Exception, match="ASSET_NOT_AVAILABLE"):
            jobs.create(selected.legacy(), "owner-b", selection=selected.model_dump())
        assert len(jobs.jobs) == 1
    finally:
        jobs.close()


@pytest.mark.parametrize("mutation", [None, "uppercase-extra", "dependency-symlink"])
def test_prequeue_runs_materialized_pinned_jars_with_complete_source_lock(native, tmp_path, monkeypatch, mutation):
    bundle = tmp_path / "operator-bundle"
    (bundle / "target/dependency").mkdir(parents=True)
    (bundle / "target/qualified.jar").write_bytes(b"qualified-test-host")
    (bundle / "target/dependency/native.JAR").write_bytes(b"qualified-test-dependency")
    lock = {"runtime": native.solution["runtime"],
            "hostTemplateDigest": service.host_template_digest(native.project, native.binding),
            "files": jar_inventory(bundle)}
    write_json(bundle / "service-lock.json", lock)
    if mutation == "uppercase-extra":
        (bundle / "target/dependency/unknown.JAR").write_bytes(b"not-pinned")
    if mutation == "dependency-symlink":
        (bundle / "target/dependency").rename(bundle / "external-dependency")
        (bundle / "target/dependency").symlink_to(bundle / "external-dependency", target_is_directory=True)
    requirement = native.harness / "unit-observation.json"
    write_json(requirement, {"schemaVersion": "finance-observation-requirements.v0.1", "id": "fixture", "version": "1",
                             "requirements": []})
    env = {"FH_AGENTSCOPE_ADMISSION_CANDIDATE": str(bundle),
           "FH_AGENTSCOPE_ADMISSION_LOCK_SHA256": "sha256:" + digest(bundle / "service-lock.json"),
           "FH_OBSERVATION_REQUIREMENTS": str(requirement),
           "FH_AGENTSCOPE_QUALIFICATION_FILE": str(tmp_path / "native-proof.json"),
           "FH_AGENTSCOPE_QUALIFICATION_SHA256": "sha256:" + "a" * 64,
           "FH_OBSERVATION_QUALIFICATION_FILE": str(tmp_path / "host-proof.json"),
           "FH_OBSERVATION_QUALIFICATION_SHA256": "b" * 64,
           "FH_MODEL_KEY": "must-not-reach-admission", "FH_BANK_SECRET": "must-not-reach-admission"}
    def java(command, **kwargs):
        assert mutation is None, "invalid pinned JAR set reached Java"
        temporary = Path(command[-1])
        assert "*" not in command[command.index("-cp") + 1]
        assert str(temporary / "target/dependency/native.JAR") in command[command.index("-cp") + 1]
        assert str(bundle) not in command[command.index("-cp") + 1]
        assert str(temporary / "target/qualified.jar") in command[command.index("-cp") + 1]
        pinned = json.loads((temporary / "service-lock.json").read_bytes())
        for name, sha in lock["files"].items():
            assert digest(temporary / name) == pinned["files"][name] == sha
        assert pinned["sharedHostSources"] == service.host_source_inventory(native.project, native.binding)
        assert pinned["managedTemplateDigest"] == native.binding["managedTemplate"]["digest"]
        assert pinned["runtimeBinding"] == native.binding
        plan = json.loads((temporary / "component-plan.json").read_bytes())
        assert plan["managedTemplate"] == native.binding["managedTemplate"]
        assert "FH_MODEL_KEY" not in kwargs["env"] and "FH_BANK_SECRET" not in kwargs["env"]
        plan = temporary / "component-installation-plan.json"
        write_json(plan, {"unitFixtureOnly": True})
        assessment = {"schemaVersion": "finance-observation-assessment.v0.1", "status": "READY", "items": [{
            "requirement": {"semanticId": "execution.history", "boundary": "HOST", "obligation": "REQUIRED"},
            "collectorId": "fixture", "collectorVersion": "fixture-digest", "readiness": "READY", "capture": "NOT_COLLECTED",
            "descriptor": {"verification": "VERIFIED", "support": "SUPPORTED", "availability": "ENABLED"}}]}
        result = {"schemaVersion": "finance-agentscope-host-admission.v0.1", "runtime": "agentscope-java-2.0.3", "status": "READY",
                  "installation": "NOT_OBSERVED", "modelInvocation": "NOT_OBSERVED", "installationPlanSha256": digest(plan),
                  "observationAssessment": assessment, "hostObservationAssessment": assessment}
        return SimpleNamespace(returncode=0, stdout=json.dumps(result).encode())
    monkeypatch.setattr(backend.subprocess, "run", java)
    if mutation is not None:
        with pytest.raises(AssemblyRejected, match="QUALIFICATION_REJECTED"):
            REAL_PREFLIGHT(native.project, native.harness, native.solution, native.binding, environment=env)
        return
    result = REAL_PREFLIGHT(native.project, native.harness, native.solution, native.binding, environment=env)
    assert result["requiredSatisfied"] is True
    assert result["runtimeDependencies"] == {"native.JAR": digest(bundle / "target/dependency/native.JAR")}


def test_service_build_rejects_unqualified_uppercase_dependency(native, monkeypatch):
    build = service.AgentScopeServiceBuild(native.builder, native.solution, native.output, native.binding, native.admission)
    build.assemble()
    def package(name, _command, _cwd, *, maven=False):
        assert maven
        assert name == "candidate_package", "unqualified JAR reached Java admission"
        target = build.candidate / "target"
        (target / "dependency").mkdir(parents=True)
        (target / "host.jar").write_bytes(b"unit-host")
        (target / "dependency/agentscope-core.jar").write_bytes(b"test-dependency")
        (target / "dependency/unknown.JAR").write_bytes(b"unknown")
    monkeypatch.setattr(build, "stage", package)
    with pytest.raises(ValueError, match="QUALIFIED_RUNTIME_DEPENDENCIES_CHANGED"):
        build.build()
    assert build.report["status"] == "FAILED"


def test_real_jvm_uppercase_jar_is_loaded_only_when_explicitly_locked(tmp_path):
    import os
    import subprocess
    import zipfile
    if not shutil.which("javac") or not shutil.which("java"):
        pytest.skip("real JDK required for uppercase JAR loading probe")
    source = tmp_path / "source"
    source.mkdir()
    (source / "LockedDependency.java").write_text('public class LockedDependency { public static String value() { return "LOCKED"; } }')
    (source / "Unexpected.java").write_text('public class Unexpected {}')
    (source / "Probe.java").write_text('public class Probe { public static void main(String[] args) throws Exception { '
        'System.out.print(LockedDependency.value() + ":"); try { Class.forName("Unexpected"); '
        'System.out.print("EXTRA_LOADED"); } catch (ClassNotFoundException expected) { System.out.print("LOCKED_ONLY"); } } }')
    subprocess.run(["javac", *map(str, source.glob("*.java"))], check=True, capture_output=True, timeout=30)
    target = tmp_path / "candidate/target"
    dependency = target / "dependency"
    dependency.mkdir(parents=True)
    def package(path, name):
        with zipfile.ZipFile(path, "w") as archive:
            archive.write(source / (name + ".class"), name + ".class")
    package(target / "main.jar", "Probe")
    package(dependency / "LOCKED.JAR", "LockedDependency")
    candidate = target.parent
    files = jar_inventory(candidate)
    classpath = locked_classpath(candidate, files)
    assert "LOCKED.JAR" in classpath and "*" not in classpath
    assert classpath.split(os.pathsep)[0] == str(target / "main.jar")
    package(dependency / "UNLOCKED.JAR", "Unexpected")
    wildcard = str(target / "main.jar") + os.pathsep + str(dependency / "*")
    unsafe = subprocess.run(["java", "-cp", wildcard, "Probe"], check=True, capture_output=True, text=True, timeout=15)
    safe = subprocess.run(["java", "-cp", classpath, "Probe"], check=True, capture_output=True, text=True, timeout=15)
    assert unsafe.stdout == "LOCKED:EXTRA_LOADED"
    assert safe.stdout == "LOCKED:LOCKED_ONLY"
    with pytest.raises(ValueError, match="MANAGED_CLASSPATH_MISMATCH"):
        locked_classpath(candidate, files)
