"""Offline lifecycle tests. No Java, model, bank, publication or deployment."""
import copy
import base64
import io
import json
import threading
import time
import zipfile
from types import SimpleNamespace

import pytest

from finance_solution_builder.eval_launcher_backend import (
    BackendError, BuilderBackend, EvalLauncherBackend, TEMPLATES, sha,
)
from finance_solution_builder.method_assets import runtime_files, installation_digest
from finance_solution_builder.workbench import trusted_business_time_environment
from finance_solution_builder.model_generation import freeze_generation, generation_digest


class FakeJobs:
    def __init__(self, root, family):
        self.root, self.family = root, family
        self.data_dir = root
        self.lock = threading.RLock()
        self.jobs, self.processes, self.sessions = {}, {}, {}
        self.calls, self.stops, self.deleted = [], [], []
        self.entered, self.unblock = threading.Event(), threading.Event()
        self.block = False
        self.fault = None
        self.data = {"name": "Read balance", "kind": "METHOD", "content": "# Method\nRead the balance.",
                     "resources": {}, "requiredResources": [], "capabilityIds": ["account.balance.query"],
                     "cases": [{"name": "balance", "query": "Read balance", "expectedTerms": []}]}
        self.release = {"assetId": "method-aaaaaaaaaaaaaaaa", "version": "1.0.0", "releaseDigest": "sha256:" + "a" * 64, "source": {"kind": "AUTHORED"},
                        "data": self.data}
        files = runtime_files(self.release["assetId"], "1.0.0", self.data)
        self.release["installationDigest"] = installation_digest(files)
        self.skill_digest = "sha256:" + sha(files[f"skills/{self.release['assetId']}/SKILL.md"])
        self.method_assets = SimpleNamespace(release=lambda owner, ref: copy.deepcopy(self.release), check_source=lambda _: None)
        self.capabilities = [{"capabilityId": "account.balance.query", "operation": "QUERY", "toolName": "balance_query"}]
        self.runtime = {"family": family, "language": "java", "templateRef": TEMPLATES[family], "integrationRef": "maven://finance/integration"}

    def path(self, key):
        return self.root / key

    def owned(self, key, owner):
        assert self.jobs[key]["owner"] == owner
        return self.jobs[key]

    def stop(self, key, owner, *, expected_instance):
        assert self.owned(key, owner)["instanceId"] == expected_instance
        self.stops.append((key, expected_instance))
        self.jobs[key]["status"] = "STOPPED"
        self.processes.pop(key, None)
        self.sessions.pop(key, None)
        self.unblock.set()

    def delete(self, key, owner):
        assert self.owned(key, owner)["status"] not in {"BUILDING", "QUEUED", "RUNNING"}
        self.jobs.pop(key)
        self.deleted.append(key)

    def talk(self, key, owner, action, body, *, expected_instance, expected_session):
        assert self.owned(key, owner)["instanceId"] == expected_instance
        self.calls.append((key, action, copy.deepcopy(body), expected_session))
        if action in {"session", "switch-model"}:
            if action == "switch-model":
                assert expected_session == self.sessions[key]
            session = f"session-{len(self.calls)}"
            self.sessions[key] = session
            return 200, {"sessionId": session, "buildId": key, "instanceId": expected_instance,
                         "modelRef": "model-test", "modelConfigVersion": "sha256:config",
                         "installations": [{"capabilities": self.capabilities}]}
        self.entered.set()
        if self.block:
            assert self.unblock.wait(3)
        method = self.jobs[key].get("methodSnapshot", self.release)
        method_files = runtime_files(method["assetId"], method["version"], method["data"])
        skill_digest = "sha256:" + sha(method_files[f"skills/{method['assetId']}/SKILL.md"])
        reply = {"response": {"output": "Balance is 42."}, "sessionId": self.sessions[key],
                 "executionObservation": {"requestId": "request-test", "archiveStatus": "RECORDING"},
                 "modelRef": "model-test", "modelName": "upstream", "modelConfigVersion": "sha256:config",
                 "evidenceKind": "EXPLICIT_TEST_MODEL",
                 "toolResults": [{"data": {"capabilityId": "account.balance.query", "resultStatus": "COMPLETED"}}],
                 "runtimeLock": {"solutionId": key, "runtime": self.runtime,
                     "assets": [{"id": method["assetId"], "version": method["version"], "digest": method["installationDigest"]}],
                     "installations": [{"capabilities": self.capabilities}],
                     "domainInstallations": [{"packId": method["assetId"], "packVersion": method["version"],
                         "skills": [{"directory": method["assetId"], "digest": skill_digest}]}]}}
        if self.fault:
            self.fault(reply)
        binding = self.jobs[key].get("modelGeneration")
        if binding is not None:
            reply.update(modelGeneration=copy.deepcopy(binding), modelGenerationSha256=generation_digest(binding))
        return 200, reply


class FakeApi:
    def __init__(self, jobs):
        self.jobs = jobs
        self.queued = False
        self.created = threading.Event()
        self.start_cancel = None
        self.candidate_snapshot = None
        self.starts = []

    def post(self, path, body, owner, *, generation_binding=None):
        if path == "/api/v1/builds":
            key = "agent-" + str(len(self.jobs.jobs) + 1)
            self.jobs.jobs[key] = {"owner": owner, "status": "BUILDING" if self.queued else "BUILT"}
            snapshot = self.candidate_snapshot or self.jobs.release
            self.jobs.jobs[key]["methodSnapshot"] = copy.deepcopy(snapshot)
            method = {k: v for k, v in snapshot.items() if k != "data"}
            model_selection = {"selectedModelRefs": ["model-test"], "defaultModelRef": "model-test",
                               "connections": [{"modelRef": "model-test", "modelName": "upstream", "configVersion": "sha256:config"}]}
            if generation_binding is not None:
                model_selection = freeze_generation(model_selection, generation_binding)
                self.jobs.jobs[key]["modelGeneration"] = model_selection["modelGeneration"]
            lock = {"buildId": key, "verification": "COMPILED", "runtime": self.jobs.runtime,
                    "capabilities": ["account.balance.query"], "method": method,
                    "modelSelection": model_selection}
            file = self.jobs.path(key) / "candidate/service-lock.json"
            file.parent.mkdir(parents=True)
            file.write_text(json.dumps(lock))
            self.created.set()
            return 202, self.inspect(key, owner)
        key = path.split("/")[-2]
        assert path.endswith("/start")
        assert body == {"artifactId": "sha256:" + sha((self.jobs.path(key) / "candidate/service-lock.json").read_bytes())}
        self.starts.append((key, copy.deepcopy(body), owner))
        instance = "instance-" + key + "-" + str(len(self.starts))
        self.jobs.jobs[key].update(status="RUNNING", instanceId=instance)
        self.jobs.processes[key] = object()
        if self.start_cancel:
            self.start_cancel.set()
        return 200, {"instance": {"status": "READY", "instanceId": instance}}

    def inspect(self, key, owner):
        job = self.jobs.owned(key, owner)
        file = self.jobs.path(key) / "candidate/service-lock.json"
        return {"buildId": key, "buildStatus": "RUNNING" if job["status"] == "BUILDING" else "SUCCEEDED",
                "artifact": {"artifactId": "sha256:" + sha(file.read_bytes())}}


@pytest.fixture(params=["openjiuwen", "agentscope"])
def setup(tmp_path, request, monkeypatch):
    family = request.param
    version = "0.1.15" if family == "openjiuwen" else "2.0.3"
    jobs = FakeJobs(tmp_path, family)
    api = FakeApi(jobs)
    backend = EvalLauncherBackend(jobs, api, poll_seconds=0.002)
    monkeypatch.setattr(backend, "_selection_plan", lambda selection, method: {"capabilities": copy.deepcopy(jobs.capabilities)})
    files = {"SKILL.md": jobs.data["content"]}
    source = {k: sha(v.encode()) for k, v in files.items()}
    preset = {"owner": "eval-owner", "selection": {"runtimeId": family + "-java-" + version, "capabilityIds": ["account.balance.query"],
              "methodRef": {k: jobs.release[k] for k in ("assetId", "version", "releaseDigest")}},
              "runtime": {"type": family, "version": version}, "model": {"modelId": "execution", "upstreamModelId": "upstream"},
              "modelRef": "model-test", "modelMetadata": {"configVersion": "sha256:config", "parameters": {"maxTokens": 2000}},
              "toolNames": ["balance_query"], "sourceFileDigests": source,
              "businessDate": trusted_business_time_environment()["FH_BUSINESS_DATE"]}
    skill = {"skillId": "eval-original", "version": "source-v1", "artifactSha256": "b" * 64, "archiveSha256": "c" * 64}
    req = {"modelId": "execution", "skill": skill, "variant": {"mode": "skill_version", **{k: skill[k] for k in ("skillId", "version", "artifactSha256")}},
           "limits": {"timeoutMs": 1000}, "sourceVerification": {"sourceFiles": files, "sourceFileDigests": source, "archiveSha256": "c" * 64}}
    yield backend, jobs, api, req, preset
    jobs.unblock.set()


CASE = {"caseId": "one", "query": "Read balance", "requiredTools": ["balance_query"]}


def test_generation_freezes_request_and_survives_host_restart(setup):
    backend, jobs, api, req, preset = setup
    req["generation"] = {"schemaVersion": "finance.eval-generation.v1", "temperature": 0, "top_p": .75, "max_tokens": 1234}
    h = backend.prepare(req, preset, threading.Event())
    expected = copy.deepcopy(h.generation_binding)
    req["generation"]["temperature"] = 2
    preset["modelMetadata"]["parameters"] = {"temperature": 2}
    for _ in range(2):
        result = backend.execute(h, CASE, 1000)
        assert result["status"] == "completed"
        assert result["metadata"]["modelGeneration"] == expected
        assert result["metadata"]["modelGenerationHostVerified"] is True
        assert result["metadata"]["modelParametersVerified"] is False
        assert result["metadata"]["declaredModelParameters"] == {"temperature": 0, "top_p": .75, "max_tokens": 1234}
        assert h.service_lock["modelSelection"]["modelGeneration"] == expected
    assert api.starts[0] == api.starts[1]
    backend.close(h)
    assert h.retired.wait(1)


def test_generation_missing_host_ack_is_not_verified(setup, monkeypatch):
    backend, jobs, api, req, preset = setup
    req["generation"] = {"schemaVersion": "finance.eval-generation.v1", "temperature": .5, "top_p": 1, "max_tokens": 10}
    h = backend.prepare(req, preset, threading.Event())
    original = jobs.talk
    def talk(*args, **kwargs):
        code, result = original(*args, **kwargs)
        result.pop("modelGenerationSha256", None)
        return code, result
    monkeypatch.setattr(jobs, "talk", talk)
    result = backend.execute(h, CASE, 1000)
    assert result["error"] == "EVAL_GENERATION_RESPONSE_MISMATCH" and not result["runtimeIdentityVerified"]
    assert h.retired.wait(1)


@pytest.mark.parametrize("timeout", [10, 1000])
def test_restart_elapsed_and_deadline_include_startup(setup, monkeypatch, timeout):
    backend, jobs, api, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    assert backend.execute(h, CASE, 1000)["status"] == "completed"
    original = api.post
    def delayed(*args):
        time.sleep(.04)
        return original(*args)
    monkeypatch.setattr(api, "post", delayed)
    result = backend.execute(h, CASE, timeout)
    assert result["elapsedMs"] >= 40
    assert result["metadata"]["isolationMode"] == "FRESH_HOST_SESSION"
    assert 40 <= result["metadata"]["isolationRestartMs"] <= result["elapsedMs"]
    if timeout == 10:
        assert result["error"] == "EVAL_CASE_TIMEOUT" and not result["runtimeIdentityVerified"]
        assert len(jobs.calls) == 2
    else:
        assert result["status"] == "completed"
        assert jobs.calls[-2][1] == "session" and jobs.calls[-2][3] is None
    backend.close(h)
    assert h.retired.wait(1)


def test_delete_during_restart_cleans_new_generation_without_session(setup, monkeypatch):
    backend, jobs, api, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    assert backend.execute(h, CASE, 1000)["status"] == "completed"
    old = h.instance_id
    entered, finish = threading.Event(), threading.Event()
    original = api.post
    def blocked(*args):
        entered.set()
        assert finish.wait(2)
        return original(*args)
    monkeypatch.setattr(api, "post", blocked)
    results = []
    worker = threading.Thread(target=lambda: results.append(backend.execute(h, CASE, 1000)))
    closer = threading.Thread(target=lambda: backend.close(h))
    worker.start()
    try:
        assert entered.wait(1)
        closer.start()
        assert h.cancel_event.wait(1)
    finally:
        finish.set()
        worker.join(2)
        if closer.ident is not None:
            closer.join(2)
    assert not worker.is_alive() and not closer.is_alive()
    assert results[0]["error"] == "EVAL_CANCELLED"
    assert h.instance_id != old and h.session_id is None
    assert jobs.stops == [(h.build_id, old), (h.build_id, h.instance_id)]
    assert len(jobs.calls) == 2 and h.retired.wait(1)


@pytest.mark.parametrize("failure", ["raise-after-start", "wrong-response-id", "artifact-changed"])
def test_restart_failure_retains_actual_new_cleanup_target(setup, monkeypatch, failure):
    backend, jobs, api, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    assert backend.execute(h, CASE, 1000)["status"] == "completed"
    old = h.instance_id
    original = api.post
    def faulty(*args):
        code, response = original(*args)
        if failure == "raise-after-start":
            raise RuntimeError("private upstream failure")
        if failure == "wrong-response-id":
            response["instance"]["instanceId"] = "unowned-generation"
        if failure == "artifact-changed":
            path = jobs.path(h.build_id) / "candidate/service-lock.json"
            path.write_text(path.read_text() + " ")
        return code, response
    monkeypatch.setattr(api, "post", faulty)
    result = backend.execute(h, CASE, 1000)
    assert result["status"] == "failed" and not result["runtimeIdentityVerified"]
    assert "private" not in result["error"]
    assert h.instance_id not in {old, "unowned-generation"}
    assert jobs.stops == [(h.build_id, old), (h.build_id, h.instance_id)]
    assert len(jobs.calls) == 2 and h.retired.wait(1)


def test_restart_rejects_replaced_old_generation_without_stopping_it(setup):
    backend, jobs, api, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    assert backend.execute(h, CASE, 1000)["status"] == "completed"
    jobs.jobs[h.build_id]["instanceId"] = "unowned-generation"
    result = backend.execute(h, CASE, 1000)
    assert result["status"] == "failed" and not result["runtimeIdentityVerified"]
    h.reaper.join(1)
    assert not jobs.stops and len(api.starts) == 1 and len(jobs.calls) == 2
    assert jobs.jobs[h.build_id]["instanceId"] == "unowned-generation"
    assert h.cleanup_error == "EVAL_BUILD_RETIRE_FAILED"


@pytest.mark.parametrize("interrupt", ["cancel", "artifact-changed"])
def test_restart_rechecks_cancel_and_artifact_before_start(setup, monkeypatch, interrupt):
    backend, jobs, api, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    assert backend.execute(h, CASE, 1000)["status"] == "completed"
    original = jobs.stop
    def stop(*args, **kwargs):
        original(*args, **kwargs)
        if interrupt == "cancel":
            h.cancel_event.set()
        else:
            path = jobs.path(h.build_id) / "candidate/service-lock.json"
            path.write_text(path.read_text() + " ")
    monkeypatch.setattr(jobs, "stop", stop)
    result = backend.execute(h, CASE, 1000)
    assert result["status"] == "failed" and not result["runtimeIdentityVerified"]
    assert len(api.starts) == 1 and len(jobs.calls) == 2
    assert h.retired.wait(1)


def test_prepare_execute_fresh_sessions_and_evidence(setup):
    backend, jobs, api, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    first = backend.execute(h, CASE, 1000)
    second = backend.execute(h, CASE, 1000)
    assert first["status"] == second["status"] == "completed"
    assert first["runtimeIdentityVerified"]
    assert first["toolCalls"] == [{"name": "balance_query", "status": "completed"}]
    assert first["loadedSkills"] == [{"skillId": "eval-original", "version": "source-v1"}]
    assert first["metadata"]["skillArtifactSha256"] == "b" * 64
    assert first["metadata"]["installationDigest"] != "b" * 64
    assert first["metadata"]["modelParametersVerified"] is False
    assert first["metadata"]["isolationMode"] == second["metadata"]["isolationMode"] == "FRESH_HOST_SESSION"
    assert first["metadata"]["isolationRestartMs"] == 0
    assert second["elapsedMs"] >= second["metadata"]["isolationRestartMs"]
    assert "runId" not in first and "instanceConfigurationDigest" not in first["metadata"]
    assert [c[1] for c in jobs.calls] == ["session", "chat", "session", "chat"]
    assert first["metadata"]["instanceId"] != second["metadata"]["instanceId"]
    assert api.starts[0] == api.starts[1]
    assert first["metadata"]["sessionId"] != second["metadata"]["sessionId"]
    backend.close(h)
    backend.close(h)
    assert h.retired.wait(1)
    assert len(jobs.stops) == 2


@pytest.mark.parametrize("fault", [
    lambda r: r.update(modelName="wrong"),
    lambda r: r.update(modelConfigVersion="changed"),
    lambda r: r.update(sessionId="other"),
    lambda r: r["runtimeLock"].update(solutionId="other"),
    lambda r: r["runtimeLock"]["runtime"].update(templateRef="wrong"),
    lambda r: r["runtimeLock"]["domainInstallations"][0]["skills"][0].update(digest="wrong"),
    lambda r: r["runtimeLock"]["assets"][0].update(digest="wrong"),
])
def test_identity_mismatch_has_no_unverified_output(setup, fault):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    jobs.fault = fault
    result = backend.execute(h, CASE, 1000)
    assert result["status"] == "failed" and not result["runtimeIdentityVerified"]
    assert not result["output"] and not result["loadedSkills"] and not result["metadata"].get("skillArtifactSha256")
    assert h.retired.wait(1)


def test_manual_digest_mapping_cannot_substitute_different_method(setup):
    backend, jobs, _, req, preset = setup
    jobs.release["data"]["content"] = "# Other method"
    with pytest.raises(BackendError, match="EVAL_SOURCE_METHOD_MISMATCH"):
        backend.prepare(req, preset, threading.Event())
    assert not jobs.jobs


@pytest.mark.parametrize("change,error", [
    (lambda p: p.update(allowDrafts="true"), "DRAFT_SETTING_INVALID"),
    (lambda p: p["sourceFileDigests"].update({"SKILL.md": "wrong"}), "SOURCE_NOT_ADMITTED"),
    (lambda p: p["modelMetadata"].update(configVersion="wrong"), "MODEL_MISMATCH"),
])
def test_bad_presets_fail_closed(setup, change, error):
    backend, jobs, _, req, preset = setup
    preset = copy.deepcopy(preset)
    change(preset)
    with pytest.raises(BackendError, match=error):
        backend.prepare(req, preset, threading.Event())
    assert not jobs.processes


def test_cancel_during_build_reaps_after_build_without_start(setup):
    backend, jobs, api, req, preset = setup
    api.queued = True
    cancel = threading.Event()
    errors = []
    def prepare():
        try:
            backend.prepare(req, preset, cancel)
        except BackendError as error:
            errors.append(str(error))
    thread = threading.Thread(target=prepare)
    thread.start()
    assert api.created.wait(1)
    cancel.set()
    thread.join(1)
    assert errors == ["EVAL_CANCELLED"]
    jobs.jobs["agent-1"]["status"] = "BUILT"
    for worker in threading.enumerate():
        if worker.name == "eval-build-reaper":
            worker.join(1)
    assert jobs.deleted == ["agent-1"] and not jobs.stops


def test_cancel_arriving_during_start_stops_and_retires(setup):
    backend, jobs, api, req, preset = setup
    cancel = threading.Event()
    api.start_cancel = cancel
    with pytest.raises(BackendError, match="EVAL_CANCELLED"):
        backend.prepare(req, preset, cancel)
    for worker in threading.enumerate():
        if worker.name == "eval-build-reaper":
            worker.join(1)
    assert len(jobs.stops) == 1 and jobs.deleted == ["agent-1"]


@pytest.mark.parametrize("interrupt", ["close", "timeout"])
def test_concurrent_close_or_timeout_stops_only_owned_instance(setup, interrupt):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    jobs.block = True
    results = []
    thread = threading.Thread(target=lambda: results.append(backend.execute(h, CASE, 30 if interrupt == "timeout" else 1000)))
    thread.start()
    assert jobs.entered.wait(1)
    if interrupt == "close":
        backend.close(h)
    thread.join(1)
    assert results[0]["status"] == "failed" and results[0]["output"] == ""
    assert h.retired.wait(1) and jobs.stops == [(h.build_id, h.instance_id)]


def test_same_handle_concurrency_serializes_with_fresh_sessions(setup):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    jobs.block = True
    results = []
    threads = [threading.Thread(target=lambda: results.append(backend.execute(h, CASE, 1000))) for _ in range(2)]
    for thread in threads:
        thread.start()
    assert jobs.entered.wait(1)
    jobs.unblock.set()
    for thread in threads:
        thread.join(1)
    assert len(results) == 2 and all(r["status"] == "completed" for r in results)
    assert results[0]["metadata"]["sessionId"] != results[1]["metadata"]["sessionId"]
    backend.close(h)
    assert h.retired.wait(1)


def test_cli_constructor_is_real_and_does_not_launch(tmp_path):
    backend = BuilderBackend(str(tmp_path), str(tmp_path), str(tmp_path / "private"))
    try:
        assert not backend.jobs.jobs and not backend.jobs.processes
    finally:
        backend.jobs.close()


@pytest.mark.parametrize("change", [
    lambda r: r.update(configuration={"arbitrary": "value"}),
    lambda r: r.update(secrets={"values": {"apiKey": "private"}}),
    lambda r: r.update(environment={"mcpServers": [{"endpoint": "https://untrusted.invalid"}]}),
    lambda r: r.update(environment={"commandScripts": [{"path": "bad.py"}]}),
])
def test_no_untrusted_execution_or_credentials(setup, change):
    backend, jobs, _, req, preset = setup
    change(req)
    with pytest.raises(BackendError):
        backend.prepare(req, preset, threading.Event())
    assert not jobs.jobs and not jobs.calls


def test_precancel_never_creates_job(setup):
    backend, jobs, _, req, preset = setup
    cancel = threading.Event()
    cancel.set()
    with pytest.raises(BackendError, match="EVAL_CANCELLED"):
        backend.prepare(req, preset, cancel)
    assert not jobs.jobs


def test_artifact_tamper_rejected_before_host_call(setup):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    (jobs.path(h.build_id) / "candidate/service-lock.json").write_text("changed")
    result = backend.execute(h, CASE, 1000)
    assert result["error"] == "EVAL_ARTIFACT_CHANGED" and not jobs.calls
    assert h.retired.wait(1)


def test_query_only_checked_before_chat(setup):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    jobs.capabilities[0]["operation"] = "EXECUTE"
    result = backend.execute(h, CASE, 1000)
    assert result["error"] == "EVAL_QUERY_ONLY_REQUIRED"
    assert [c[1] for c in jobs.calls] == ["session"]
    assert h.retired.wait(1)


def test_raw_error_not_exposed(setup):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    def fail(*args, **kwargs):
        raise RuntimeError("/private/config apiKey=canary")
    jobs.talk = fail
    result = backend.execute(h, CASE, 1000)
    assert result["error"] == "EVAL_HOST_REJECTED"
    assert "canary" not in json.dumps(result) and "/private" not in json.dumps(result)
    assert h.retired.wait(1)


def test_waiting_case_deadline_does_not_stop_active_case(setup):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    h.execution_lock.acquire()
    try:
        with pytest.raises(BackendError, match="EVAL_CASE_QUEUE_TIMEOUT"):
            backend.execute(h, CASE, 5)
        assert not jobs.stops
    finally:
        h.execution_lock.release()
        backend.close(h)
    assert h.retired.wait(1)


def test_close_cannot_stop_replacement_instance(setup):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    jobs.jobs[h.build_id]["instanceId"] = "replacement"
    with pytest.raises(BackendError, match="EVAL_INSTANCE_CHANGED"):
        backend.close(h)
    h.reaper.join(1)
    assert not jobs.stops and h.cleanup_error == "EVAL_BUILD_RETIRE_FAILED"


def test_cleanup_failure_is_retryable(setup):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    stop = jobs.stop
    def fail(*args, **kwargs):
        raise RuntimeError("private detail")
    jobs.stop = fail
    with pytest.raises(BackendError, match="EVAL_CLEANUP_FAILED"):
        backend.close(h)
    h.reaper.join(1)
    jobs.stop = stop
    backend.close(h)
    assert h.retired.wait(1) and h.cleanup_error is None


def test_resolved_task_rejected_before_any_build_or_start(setup):
    backend, jobs, api, req, preset = setup
    jobs.capabilities[0]["operation"] = "TASK"
    with pytest.raises(BackendError, match="EVAL_QUERY_ONLY_REQUIRED"):
        backend.prepare(req, preset, threading.Event())
    assert not api.created.is_set() and not jobs.jobs and not jobs.processes


def test_resolved_extra_capability_rejected_before_build(setup):
    backend, jobs, api, req, preset = setup
    jobs.capabilities.append({"capabilityId": "extra.query", "operation": "QUERY", "toolName": "extra"})
    with pytest.raises(BackendError, match="EVAL_SELECTION_CAPABILITIES_MISMATCH"):
        backend.prepare(req, preset, threading.Event())
    assert not api.created.is_set()


def test_unified_selection_plan_uses_existing_resolver(tmp_path, monkeypatch):
    from finance_solution_builder import workbench_contracts
    received = []
    class Selection:
        def resolve_details(self, *args):
            received.append(args)
            return {}, {}, {"capabilities": [{"operation": "TASK"}]}
    monkeypatch.setattr(workbench_contracts, "UnifiedWorkbenchSelection", Selection)
    monkeypatch.setattr(workbench_contracts, "parse_selection", lambda value: Selection())
    jobs = SimpleNamespace(project=tmp_path, builder=SimpleNamespace(harness_repo=tmp_path / "harness"))
    backend = EvalLauncherBackend(jobs, api=object())
    method = {"data": "frozen"}
    assert backend._selection_plan({}, method)["capabilities"] == [{"operation": "TASK"}]
    assert received == [(tmp_path, tmp_path / "harness", "eval-readonly-preflight", method)]


@pytest.mark.parametrize("status", ["FAILED", "UNKNOWN", "NEED_USER", "PROCESSING"])
def test_tool_failure_state_and_retained_evidence_survive_cleanup(setup, status):
    backend, jobs, _, req, preset = setup
    h = backend.prepare(req, preset, threading.Event())
    jobs.fault = lambda r: r["toolResults"][0]["data"].update(resultStatus=status)
    result = backend.execute(h, CASE, 1000)
    assert result["toolCalls"] == [{"name": "balance_query", "status": "failed"}]
    metadata = result["metadata"]
    assert metadata["toolOutcomes"][0]["resultStatus"] == status
    assert metadata["businessOutcome"] == "NOT_ASSESSED"
    assert metadata["buildId"] == h.build_id and metadata["instanceId"] == h.instance_id
    assert metadata["requestId"] == "request-test"
    assert metadata["runId"] == jobs.calls[-1][2]["runId"]
    ref = metadata["retainedEvidenceRef"]
    file = jobs.data_dir / ref["collection"] / ref["ownerScope"] / ref["buildId"] / (ref["runId"] + ".json")
    backend.close(h)
    assert h.retired.wait(1)
    raw = file.read_bytes()
    record = json.loads(raw)
    assert sha(raw) == ref["sha256"] and record["runtimeLock"]["solutionId"] == h.build_id
    assert record["serviceLock"]["buildId"] == h.build_id
    assert file.stat().st_mode & 0o777 == 0o600
    assert str(jobs.root) not in json.dumps(metadata)


def test_existing_jobs_delete_preserves_execution_history(tmp_path):
    backend = BuilderBackend(str(tmp_path), str(tmp_path), str(tmp_path / "private"))
    jobs = backend.jobs
    key = "agent-retained"
    try:
        jobs.jobs[key] = {"id": key, "owner": "owner", "status": "STOPPED"}
        jobs.path(key).mkdir(parents=True)
        history = jobs.data_dir / "execution-history" / sha(b"owner") / "request.json"
        history.parent.mkdir(parents=True)
        history.write_text('{"requestId":"retained"}')
        jobs.delete(key, "owner")
        assert history.is_file() and json.loads(history.read_text())["requestId"] == "retained"
    finally:
        jobs.close()


def test_failed_build_cleanup_retains_private_diagnostics(tmp_path, monkeypatch):
    from finance_solution_builder.eval_launcher_backend import Handle, DIAGNOSTIC_FILE_LIMIT
    backend = BuilderBackend(str(tmp_path), str(tmp_path), str(tmp_path / "private"))
    jobs = backend.jobs
    key = "agent-failed"
    h = Handle({}, {"owner": "owner"}, threading.Event(), build_id=key)
    h.prepare_done.set()
    monkeypatch.setenv("TEST_MODEL_API_KEY", "secret-canary-value")
    try:
        jobs.jobs[key] = {"id": key, "owner": "owner", "status": "FAILED"}
        source = jobs.path(key)
        (source / "logs").mkdir(parents=True)
        (source / "build-report.json").write_text(json.dumps({"status": "FAILED", "buildId": key,
            "configuration": "must-not-retain", "stages": [{"name": "asset_reassembly", "status": "failed",
            "detail": "qualification mismatch", "command": ["secret-command"]}]}))
        (source / "logs/candidate_package.log").write_text(
            "compiler failure\nsecret-canary-value\nAuthorization: Bearer opaque\nrequest body: customer\n"
            + "x" * DIAGNOSTIC_FILE_LIMIT)
        (source / "instance.log").write_text("private-customer-request")
        (source / "logs/component_installation.log").symlink_to(source / "instance.log")
        backend.close(h)
        assert h.retired.wait(2), h.cleanup_error
        assert not source.exists()
        target = jobs.data_dir / "eval-launcher-evidence" / sha(b"owner") / key / "retainedEvidence/build-diagnostics.json"
        raw = target.read_text()
        record = json.loads(raw)
        files = {f["path"]: f for f in record["files"]}
        assert record["status"] == "FAILED" and record["buildId"] == key
        report = json.loads(files["build-report.json"]["content"])
        assert report["stages"][0]["detail"] == "qualification mismatch"
        assert files["logs/candidate_package.log"]["truncated"]
        assert files["logs/component_installation.log"]["omitted"] == "SYMLINK"
        for value in ("secret-canary-value", "opaque", "customer", "must-not-retain", "secret-command"):
            assert value not in raw
        assert target.stat().st_mode & 0o777 == 0o600
        assert target.parent.stat().st_mode & 0o777 == 0o700
    finally:
        jobs.close()


def test_diagnostic_retention_failure_preserves_failed_job(setup, monkeypatch):
    backend, jobs, api, req, preset = setup
    from finance_solution_builder.eval_launcher_backend import Handle
    h = Handle(req, preset, threading.Event(), build_id="failed")
    jobs.jobs[h.build_id] = {"id": h.build_id, "owner": preset["owner"], "status": "FAILED"}
    h.prepare_done.set()
    def fail(*args):
        raise OSError("private-path")
    monkeypatch.setattr(backend, "_retain_build_diagnostics", fail)
    backend.close(h)
    h.reaper.join(1)
    assert h.cleanup_error == "EVAL_BUILD_RETIRE_FAILED"
    assert not h.retired.is_set() and h.build_id in jobs.jobs
    assert not jobs.deleted


def draft_request(req, content="# Candidate\nRead and explain the balance.", resources=None):
    from finance_solution_builder.eval_launcher_service import validate_skill
    stream = io.BytesIO()
    files = {"SKILL.md": content, **(resources or {})}
    with zipfile.ZipFile(stream, "w") as archive:
        for name, value in files.items():
            archive.writestr(name, value)
    result = copy.deepcopy(req)
    result["skill"].update(archiveBase64=base64.b64encode(stream.getvalue()).decode(),
                           archiveSha256=sha(stream.getvalue()), instructions=content, files=files)
    result["sourceVerification"] = validate_skill(result["skill"], result["variant"])
    return result


@pytest.fixture
def draft_setup(setup, tmp_path, monkeypatch):
    from finance_solution_builder.method_assets import MethodAssets
    backend, jobs, api, req, preset = setup
    store = MethodAssets(tmp_path / "preview-only")
    jobs.method_assets.import_asset = store.import_asset
    jobs.method_assets.require_active = lambda owner, asset: copy.deepcopy(jobs.release)
    preset = copy.deepcopy(preset)
    preset["allowDrafts"] = True
    preset.pop("sourceFileDigests")
    def create_candidate(h):
        api.candidate_snapshot = h.candidate_snapshot
        code, state = api.post("/api/v1/builds", h.preset["selection"], h.preset["owner"])
        assert code == 202
        h.build_id = state["buildId"]
        return state
    monkeypatch.setattr(backend, "_create_candidate", create_candidate)
    return backend, jobs, api, draft_request(req), preset, store


def test_draft_executes_mapped_snapshot_without_publishing(draft_setup):
    backend, jobs, _, req, preset, store = draft_setup
    original = copy.deepcopy(preset)
    template = copy.deepcopy(jobs.release)
    h = backend.prepare(req, preset, threading.Event())
    snapshot = h.candidate_snapshot
    assert snapshot["version"] == "0.0.0" and snapshot["publicationStatus"] == "NOT_PUBLISHED"
    assert snapshot["data"]["content"] == req["skill"]["instructions"] != jobs.data["content"]
    assert snapshot["data"]["capabilityIds"] == jobs.data["capabilityIds"]
    assert snapshot["data"]["cases"] == jobs.data["cases"]
    assert snapshot["source"] == jobs.release["source"]
    assert preset == original and jobs.release == template
    result = backend.execute(h, CASE, 1000)
    assert result["status"] == "completed", result
    meta = result["metadata"]
    assert meta["methodKind"] == "EXECUTION_CANDIDATE" and meta["publicationStatus"] == "NOT_PUBLISHED"
    assert meta["skillIdentityBasis"] == "IMPORTED_SOURCE_BYTES_TO_EXECUTION_CANDIDATE"
    assert meta["executionCandidateRef"]["version"] == "0.0.0"
    assert meta["baseMethodRef"] == original["selection"]["methodRef"]
    assert meta["skillArtifactSha256"] == req["skill"]["artifactSha256"]
    ref = meta["retainedEvidenceRef"]
    file = jobs.data_dir / ref["collection"] / ref["ownerScope"] / ref["buildId"] / (ref["runId"] + ".json")
    assert json.loads(file.read_text())["executionCandidate"] == snapshot
    assert not list(store.root.rglob("draft.json")) and not list(store.root.rglob("release.json"))
    backend.close(h)
    assert h.retired.wait(1)


def test_draft_resources_use_existing_runtime_transform(draft_setup):
    backend, jobs, _, req, preset, _ = draft_setup
    req = draft_request(req, "# Candidate\nUse references/policy.md.", {"references/policy.md": "Never invent facts."})
    h = backend.prepare(req, preset, threading.Event())
    files = runtime_files(h.method["assetId"], "0.0.0", h.candidate_snapshot["data"])
    raw = files[f"skills/{h.method['assetId']}/SKILL.md"]
    assert b"Never invent facts." in raw
    assert h.skill_digest == "sha256:" + sha(raw)
    assert h.method["installationDigest"] == installation_digest(files)
    backend.close(h)
    assert h.retired.wait(1)


@pytest.mark.parametrize("content,resources", [
    ("# Candidate\nUse references/missing.md.", {}),
    ("# Candidate\nUse references/a.md.", {"references/a.md": "See references/missing.md"}),
    ("# Candidate\nUse references/a.md.", {"references/a.md": ""}),
])
def test_invalid_draft_rejected_before_build(draft_setup, content, resources):
    backend, jobs, api, req, preset, _ = draft_setup
    req = draft_request(req, content, resources)
    with pytest.raises(BackendError, match="EVAL_DRAFT_CONTENT_INVALID"):
        backend.prepare(req, preset, threading.Event())
    assert not api.created.is_set() and not jobs.jobs


def test_draft_markdown_cannot_grant_client_scopes(draft_setup):
    backend, jobs, _, req, preset, _ = draft_setup
    req = draft_request(req, "---\nscopes: [transfer:write]\ncapabilityIds: [transfer.execute]\n---\n# Text only\nRead balance.")
    h = backend.prepare(req, preset, threading.Event())
    assert h.selected_capabilities == {"account.balance.query"}
    assert h.candidate_snapshot["data"]["capabilityIds"] == ["account.balance.query"]
    assert "scopes" not in h.candidate_snapshot["data"]
    assert "scopes" not in h.candidate_snapshot["source"]
    backend.close(h)
    assert h.retired.wait(1)


def test_draft_checks_source_again_on_candidate(draft_setup):
    backend, jobs, api, req, preset, _ = draft_setup
    def check(value):
        if value.get("admissionKind") == "EXECUTION_CANDIDATE":
            raise BackendError("EVAL_SOURCE_REVOKED")
    jobs.method_assets.check_source = check
    with pytest.raises(BackendError, match="EVAL_SOURCE_REVOKED"):
        backend.prepare(req, preset, threading.Event())
    assert not api.created.is_set()


def test_draft_archive_cannot_disagree_with_verified_mapping(draft_setup):
    backend, jobs, api, req, preset, _ = draft_setup
    req["sourceVerification"]["sourceFiles"]["SKILL.md"] = "Changed mapping"
    req["sourceVerification"]["sourceFileDigests"]["SKILL.md"] = sha(b"Changed mapping")
    with pytest.raises(BackendError, match="EVAL_SOURCE_NOT_ADMITTED"):
        backend.prepare(req, preset, threading.Event())
    assert not api.created.is_set()


def test_snapshot_resolver_is_exact_owner_bound_and_not_a_release(draft_setup):
    from finance_solution_builder.eval_launcher_backend import Handle, _ExecutionCandidateMethods
    backend, jobs, _, req, preset, store = draft_setup
    h = Handle(copy.deepcopy(req), copy.deepcopy(preset), threading.Event())
    backend._validate(h)
    resolver = _ExecutionCandidateMethods(jobs.method_assets, preset["owner"], h.candidate_snapshot, h.base_method, h.cancel_event)
    ref = h.preset["selection"]["methodRef"]
    assert resolver.release(preset["owner"], ref) == h.candidate_snapshot
    with pytest.raises(BackendError, match="REFERENCE_MISMATCH"):
        resolver.release("other-owner", ref)
    with pytest.raises(BackendError, match="REFERENCE_MISMATCH"):
        resolver.release(preset["owner"], h.base_method)
    with pytest.raises(Exception):
        store.release(preset["owner"], ref)
    returned = resolver.release(preset["owner"], ref)
    returned["data"]["content"] = "changed"
    assert resolver.release(preset["owner"], ref)["data"]["content"] != "changed"
    h.cancel_event.set()
    with pytest.raises(BackendError, match="EVAL_CANCELLED"):
        resolver.release(preset["owner"], ref)


@pytest.mark.parametrize("cancel_before_build", [False, True])
@pytest.mark.parametrize("explicit_generation", [False, True])
def test_draft_uses_real_jobs_create_build_with_private_snapshot(draft_setup, tmp_path, monkeypatch, cancel_before_build, explicit_generation):
    from finance_solution_builder.eval_launcher_backend import Handle
    from finance_solution_builder import workbench, workbench_contracts, method_trials, managed_runtime_support
    from finance_solution_builder.workbench_api import WorkbenchApi
    fake_backend, fake_jobs, _, req, preset, _ = draft_setup
    h = Handle(copy.deepcopy(req), copy.deepcopy(preset), threading.Event())
    if explicit_generation:
        h.request["generation"] = {"schemaVersion": "finance.eval-generation.v1", "temperature": .2, "top_p": .9, "max_tokens": 100}
    fake_backend._validate(h)
    h.preset["selection"]["schema_version"] = "0.10" if fake_jobs.family == "agentscope" else "0.9"
    actual = workbench.Jobs(tmp_path / "project", tmp_path / "harness", data_dir=tmp_path / "jobs")
    original_pool = actual.queue
    original_pool.shutdown(wait=True)
    queued, builds, preflights, admissions, release_lookups = [], [], [], [], []
    class Queue:
        def submit(self, fn, *args):
            queued.append((fn, args))
        def shutdown(self, **kwargs):
            pass
    actual.queue = Queue()
    base = copy.deepcopy(fake_jobs.release)
    def released(owner, reference):
        release_lookups.append(copy.deepcopy(reference))
        assert owner == preset["owner"] and reference == h.base_method
        return copy.deepcopy(base)
    monkeypatch.setattr(actual.method_assets, "release", released)
    monkeypatch.setattr(actual.method_assets, "require_active", lambda owner, asset_id: base if asset_id == base["assetId"] else pytest.fail("candidate reached public store"))
    monkeypatch.setattr(actual.method_assets, "check_source", lambda value: None)
    monkeypatch.setattr(actual.builder, "build", lambda *args, **kwargs: builds.append((args, kwargs)))
    monkeypatch.setattr(method_trials, "reusable_seed", lambda *args: None)
    body = {"capabilities": ["account.balance.query"], "runtime": preset["selection"]["runtimeId"],
            "model": "configured-model", "connection": "local-simulator"}
    class Selection:
        reuseFromBuildId = None
        def __init__(self, value):
            self.value = copy.deepcopy(value)
            self.methodRef = SimpleNamespace(reference=lambda: copy.deepcopy(value["methodRef"]))
        def legacy(self):
            return copy.deepcopy(body)
        def model_dump(self):
            return copy.deepcopy(self.value)
        def resolve_details(self, project, harness, build_id, method_snapshot):
            assert method_snapshot == h.candidate_snapshot
            return {"solutionId": build_id, "runtime": copy.deepcopy(fake_jobs.runtime)}, {}, {
                "runtimeBinding": {"runtimePresentation": {"runtimeId": body["runtime"]}}}
    monkeypatch.setattr(workbench_contracts, "parse_selection", Selection)
    def preflight(selected, project, harness, maven, *, method_snapshot):
        preflights.append(copy.deepcopy(method_snapshot))
        return {"canBuild": True, "modelSelection": {"selectedModelRefs": ["model-test"],
                "connections": [{"modelRef": "model-test"}]}, "directorySelection": {}}
    monkeypatch.setattr(workbench_contracts, "preflight", preflight)
    def admit(*args, **kwargs):
        admissions.append(copy.deepcopy(kwargs["method_snapshot"]))
        return {"nativeQualificationDigest": "existing-qualification"}
    monkeypatch.setattr(managed_runtime_support, "require_backend_admission", admit)
    backend = EvalLauncherBackend(actual, WorkbenchApi(actual))
    try:
        state = backend._create_candidate(h)
        assert state["buildStatus"] == "QUEUED" and h.build_id in actual.jobs
        assert len(queued) == 1 and preflights == [h.candidate_snapshot]
        record = actual.jobs[h.build_id]
        assert record["modelSelection"].get("modelGeneration") == h.generation_binding
        assert record["methodRef"] == h.preset["selection"]["methodRef"]
        assert record["executionCandidate"]["publicationStatus"] == "NOT_PUBLISHED"
        assert actual.method_assets.release(preset["owner"], h.base_method) == base
        if cancel_before_build:
            h.cancel_event.set()
        callback, args = queued[0]
        callback(*args)
        if cancel_before_build:
            assert not builds and record["status"] == "FAILED"
        else:
            assert record["status"] == "BUILT" and len(builds) == 1
            assert builds[0][1]["method_snapshot"] == h.candidate_snapshot
            assert builds[0][1]["model_selection"] == record["modelSelection"]
        if fake_jobs.family == "agentscope":
            assert admissions == [h.candidate_snapshot]
        assert all(reference == h.base_method for reference in release_lookups)
        assert not list(actual.method_assets.root.rglob("release.json"))
        assert not list(actual.method_assets.root.rglob("draft.json"))
    finally:
        h.prepare_done.set()
        if h.build_id:
            if actual.jobs[h.build_id]["status"] == "QUEUED":
                actual.jobs[h.build_id]["status"] = "INTERRUPTED"
            backend.close(h)
            assert h.retired.wait(1)
        actual.close()


def test_snapshot_tampering_or_template_revocation_blocks_resolver(draft_setup):
    from finance_solution_builder.eval_launcher_backend import Handle, _ExecutionCandidateMethods
    backend, jobs, _, req, preset, _ = draft_setup
    h = Handle(copy.deepcopy(req), copy.deepcopy(preset), threading.Event())
    backend._validate(h)
    resolver = _ExecutionCandidateMethods(jobs.method_assets, preset["owner"], h.candidate_snapshot, h.base_method, h.cancel_event)
    resolver.snapshot["data"]["content"] = "Tampered"
    with pytest.raises(BackendError, match="EVAL_CANDIDATE_CHANGED"):
        resolver.release(preset["owner"], h.preset["selection"]["methodRef"])
    resolver.snapshot = copy.deepcopy(h.candidate_snapshot)
    def revoked(*args):
        raise BackendError("EVAL_TEMPLATE_ARCHIVED")
    jobs.method_assets.require_active = revoked
    with pytest.raises(BackendError, match="EVAL_TEMPLATE_ARCHIVED"):
        resolver.release(preset["owner"], h.preset["selection"]["methodRef"])


@pytest.mark.parametrize("extended", [False, True])
@pytest.mark.parametrize("draft_mode", [False, True])
def test_real_method_store_reference_contract(setup, tmp_path, extended, draft_mode):
    """Exercise the real strict reader, not a permissive fake release() method.

    The serialized records are parser fixtures in tmp_path, not native admission
    evidence. No publish(), trial runner, model or build is invoked here.
    """
    from finance_solution_builder.eval_launcher_backend import Handle
    from finance_solution_builder.method_assets import MethodAssets, canonical, sha as asset_sha, write_private
    from finance_solution_builder.workbench_contracts import MethodReference
    from finance_solution_builder.experience_contracts import ApiError
    backend, jobs, _, req, preset = setup
    store = MethodAssets(tmp_path / "strict-store")
    draft = store.create(preset["owner"], jobs.data)
    method = {**copy.deepcopy(jobs.release), "assetId": draft["assetId"], "data": draft["data"],
              "contentDigest": draft["contentDigest"], "trialId": "trial-parser-fixture"}
    runtime = runtime_files(draft["assetId"], "1.0.0", draft["data"])
    method["installationDigest"] = installation_digest(runtime)
    trial = {"trialId": method["trialId"], "contentDigest": method["contentDigest"]}
    method["trialDigest"] = asset_sha(canonical(trial))
    method.pop("releaseDigest")
    method["releaseDigest"] = asset_sha(canonical(method))
    folder = store.root / sha(preset["owner"].encode()) / draft["assetId"] / "releases/1.0.0"
    write_private(folder / "release.json", method)
    write_private(folder / "trial.json", trial)
    reference = {k: method[k] for k in ("assetId", "version", "releaseDigest")}
    decorated = MethodReference.model_validate(reference).model_dump()
    with pytest.raises(ApiError, match="ASSET_REFERENCE_INVALID"):
        store.release(preset["owner"], decorated)
    assert store.release(preset["owner"], reference) == method
    before = {p: p.read_bytes() for p in store.root.rglob("*.json")}
    jobs.method_assets = store
    preset = copy.deepcopy(preset)
    preset["selection"]["methodRef"] = decorated if extended else reference
    if draft_mode:
        preset["allowDrafts"] = True
        preset.pop("sourceFileDigests")
        req = draft_request(req)
    h = Handle(copy.deepcopy(req), preset, threading.Event())
    backend._validate(h)
    assert h.method["version"] == ("0.0.0" if draft_mode else "1.0.0")
    assert h.base_method == (reference if draft_mode else None)
    assert {p: p.read_bytes() for p in store.root.rglob("*.json")} == before
    if draft_mode:
        with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
            store.release(preset["owner"], {k: h.method[k] for k in ("assetId", "version", "releaseDigest")})


def test_draft_direct_backend_rejects_packaged_script(draft_setup):
    backend, jobs, api, req, preset, _ = draft_setup
    files = {"SKILL.md": "# Candidate\nRun scripts/tool.py", "scripts/tool.py": "print('untrusted')"}
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, content in files.items():
            archive.writestr(name, content)
    req["skill"].update(archiveBase64=base64.b64encode(stream.getvalue()).decode(), archiveSha256=sha(stream.getvalue()))
    req["sourceVerification"] = {"sourceFiles": files, "sourceFileDigests": {k: sha(v.encode()) for k, v in files.items()},
                                 "archiveSha256": sha(stream.getvalue())}
    with pytest.raises(BackendError, match="EVAL_DRAFT_CONTENT_INVALID"):
        backend.prepare(req, preset, threading.Event())
    assert not api.created.is_set() and not jobs.jobs
