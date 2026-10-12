import base64
import copy
import hashlib
import io
import json
import socket
import threading
import time
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest

from finance_solution_builder.eval_launcher_service import (
    Instance, Launcher, LauncherError, MAX_FILE, MAX_FILES, MAX_REQUEST_BYTES,
    MAX_TOTAL, MAX_UPLOAD, validate_skill,
)
from finance_solution_builder.eval_launcher_http import make_server


def source(files=None):
    files = files or {"SKILL.md": "---\nname: balance-review\n---\nRead the admitted balance tool."}
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as z:
        for name, content in files.items():
            z.writestr(zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0)), content)
    raw = archive.getvalue()
    return {"skillId": "balance-review", "version": "0.1.0", "artifactSha256": "a" * 64,
            "instructions": files.get("SKILL.md", ""), "files": files,
            "archiveBase64": base64.b64encode(raw).decode(), "archiveSha256": hashlib.sha256(raw).hexdigest()}


def request(skill=None):
    skill = skill or source()
    return {"schemaVersion": "finance.skill-eval.runtime-instance.v1", "runId": "run-1",
            "side": "left", "modelId": "model-test", "skill": skill,
            "variant": {"mode": "skill_version", **{k: skill[k] for k in ("skillId", "version", "artifactSha256")}},
            "environment": {"environmentId": "test", "requiredToolNames": ["balance"]},
            "configuration": {}, "secrets": {"mode": "inline", "values": {}}, "limits": {"timeoutMs": 1000}}


def preset():
    return {"owner": "owner", "runtime": {"type": "openjiuwen", "version": "0.1.15"},
            "model": {"modelId": "model-test", "upstreamModelId": "controlled-model"},
            "toolNames": ["balance"],
            "sourceFileDigests": {name: hashlib.sha256(value.encode()).hexdigest() for name, value in source()["files"].items()}}


def case(ident="case-1"):
    return {"schemaVersion": "finance.skill-eval.runtime-case.v1", "caseId": ident,
            "query": "Read my balance", "requiredTools": ["balance"]}


class Backend:
    def __init__(self):
        self.closed = []
        self.calls = []
        self.preparing = threading.Event()
        self.prepare_gate = threading.Event()
        self.prepare_gate.set()
        self.delay = 0
        self.bad_identity = False

    def prepare(self, body, config, cancel):
        self.preparing.set()
        assert self.prepare_gate.wait(3)
        return {"source": body, "cancel": cancel}

    def execute(self, handle, value, timeout):
        self.calls.append(value["caseId"])
        handle["cancel"].wait(self.delay)
        return {"status": "completed", "modelId": "model-test", "skillVersion": "0.1.0",
                "output": "Controlled result", "elapsedMs": 1, "toolCalls": [],
                "loadedSkills": [{"skillId": "balance-review", "version": "0.1.0"}], "usage": None,
                "runtimeIdentityVerified": not self.bad_identity, "streamCompleted": True, "error": None,
                "metadata": {"skillArtifactSha256": handle["source"]["skill"]["artifactSha256"]}}

    def close(self, handle):
        self.closed.append(handle)
        handle["cancel"].set()


@pytest.fixture
def service():
    backend = Backend()
    launcher = Launcher(backend, {"oj": preset(), "as": {**preset(), "runtime": {"type": "agentscope", "version": "2.0.3"}}})
    yield launcher, backend
    backend.prepare_gate.set()
    launcher.close()


def ready(launcher, runtime="oj", key="key-1"):
    state = launcher.create("owner", runtime, request(), key)
    deadline = time.monotonic() + 3
    while state["status"] == "starting" and time.monotonic() < deadline:
        time.sleep(.005)
        state = launcher.get("owner", runtime, state["instanceId"])
    assert state["status"] == "ready"
    return state


def test_identity_idempotency_and_cleanup(service):
    launcher, backend = service
    state = ready(launcher)
    assert launcher.create("owner", "oj", request(), "key-1")["instanceId"] == state["instanceId"]
    result = launcher.execute("owner", "oj", state["instanceId"], case())
    assert result["runId"] == "run-1" and result["caseId"] == "case-1"
    assert result["metadata"]["instanceConfigurationDigest"] == state["configurationDigest"]
    with pytest.raises(LauncherError, match="CASE_ALREADY_EXECUTED"):
        launcher.execute("owner", "oj", state["instanceId"], case())
    assert backend.calls == ["case-1"]
    launcher.delete("owner", "oj", state["instanceId"])
    assert len(backend.closed) == 1
    with pytest.raises(LauncherError, match="INSTANCE_NOT_FOUND"):
        launcher.get("owner", "oj", state["instanceId"])


def test_owner_runtime_isolation_and_quota(service):
    launcher, _ = service
    state = ready(launcher)
    for owner, runtime in [("other", "oj"), ("owner", "as")]:
        with pytest.raises(LauncherError, match="INSTANCE_NOT_FOUND"):
            launcher.get(owner, runtime, state["instanceId"])
    ready(launcher, "as")
    with pytest.raises(LauncherError, match="INSTANCE_LIMIT_REACHED"):
        launcher.create("owner", "oj", request(), "third")
    changed = request()
    changed["runId"] = "run-2"
    with pytest.raises(LauncherError, match="IDEMPOTENCY_CONFLICT"):
        launcher.create("owner", "oj", changed, "key-1")


@pytest.mark.parametrize("configured_owner", ["other-owner", None, "", "__missing__"])
def test_create_requires_explicit_matching_preset_owner(service, configured_owner):
    launcher, backend = service
    if configured_owner == "__missing__":
        launcher.presets["oj"].pop("owner")
    else:
        launcher.presets["oj"]["owner"] = configured_owner
    with pytest.raises(LauncherError) as exc:
        launcher.create("owner", "oj", request(), "key")
    assert exc.value.status == 403 and exc.value.code == "RUNTIME_OWNER_MISMATCH"
    assert not backend.preparing.is_set() and not backend.closed
    assert not launcher.instances and not launcher.keys


def test_cross_owner_alias_create_rejected_before_backend(service):
    launcher, backend = service
    with pytest.raises(LauncherError) as exc:
        launcher.create("ownerB", "oj", request(), "key")
    assert exc.value.status == 403 and exc.value.code == "RUNTIME_OWNER_MISMATCH"
    assert not backend.preparing.is_set() and not launcher.instances


@pytest.mark.parametrize("files,code", [
    ({"../SKILL.md": "bad"}, "ARCHIVE_PATH_INVALID"),
    ({"SKILL.md": "test", "scripts/run.sh": "test"}, "EXECUTABLE_SKILL_UNSUPPORTED"),
    ({"references/a.md": "test"}, "ROOT_SKILL_REQUIRED"),
])
def test_archive_rejects_paths_code_and_missing_root(files, code):
    body = request(source(files))
    with pytest.raises(LauncherError, match=code):
        validate_skill(body["skill"], body["variant"])


def test_archive_digests_are_not_interchangeable():
    body = request()
    checked = validate_skill(body["skill"], body["variant"])
    assert checked["archiveSha256"] != body["skill"]["artifactSha256"]
    body["skill"]["archiveSha256"] = "0" * 64
    with pytest.raises(LauncherError, match="ARCHIVE_DIGEST_MISMATCH"):
        validate_skill(body["skill"], body["variant"])


@pytest.mark.parametrize("mutate,code", [
    (lambda b: b["secrets"]["values"].update(key="secret"), "CLIENT_CONFIGURATION_UNSUPPORTED"),
    (lambda b: b["environment"].update(commandScripts=["evil"]), "EXECUTABLE_DEPENDENCY_UNSUPPORTED"),
    (lambda b: b["environment"].update(requiredToolNames=["transfer"]), "TOOL_NOT_INSTALLED"),
    (lambda b: b.update(modelId="another"), "MODEL_NOT_CONFIGURED"),
])
def test_client_cannot_expand_execution(service, mutate, code):
    launcher, backend = service
    body = request()
    mutate(body)
    with pytest.raises(LauncherError, match=code):
        launcher.create("owner", "oj", body, "key")
    assert not backend.preparing.is_set()


def test_deleted_during_prepare_late_handle_is_closed(service):
    launcher, backend = service
    backend.prepare_gate.clear()
    state = launcher.create("owner", "oj", request(), "key")
    assert backend.preparing.wait(1)
    launcher.delete("owner", "oj", state["instanceId"])
    backend.prepare_gate.set()
    launcher.pool.shutdown(wait=True)
    assert len(backend.closed) == 1


def test_ttl_reaps_own_handle(service):
    launcher, backend = service
    now = time.time()
    launcher.clock = lambda: now
    state = ready(launcher)
    now += 1801
    launcher.reap()
    assert len(backend.closed) == 1
    assert launcher.instances[state["instanceId"]].deleted


def test_timeout_and_identity_failure_release_resources(service):
    launcher, backend = service
    state = ready(launcher)
    backend.delay = 5
    with pytest.raises(LauncherError, match="CASE_TIMEOUT_INSTANCE_RELEASED"):
        launcher.execute("owner", "oj", state["instanceId"], case())
    assert len(backend.closed) == 1
    backend.delay, backend.bad_identity = 0, True
    state = ready(launcher, key="second")
    with pytest.raises(LauncherError, match="RUNTIME_EVIDENCE_UNVERIFIED"):
        launcher.execute("owner", "oj", state["instanceId"], case())
    assert len(backend.closed) == 2


def test_concurrent_cases_serialize(service):
    launcher, backend = service
    state = ready(launcher)
    backend.delay = .05
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = [pool.submit(launcher.execute, "owner", "oj", state["instanceId"], case(f"case-{i}")) for i in range(2)]
        assert all(f.result()["status"] == "completed" for f in pending)
    assert len(set(backend.calls)) == 2


def test_native_tool_inventory_is_preset_scoped_and_not_an_execution_grant():
    backend = Backend()
    launcher = Launcher(backend, {
        "oj": {**preset(), "toolNames": ["z_native", "balance", "z_native"]},
        "as": {**preset(), "toolNames": ["as_native"]},
        "other-owner": {**preset(), "owner": "another", "toolNames": ["private_native"]},
    })
    try:
        capabilities = launcher.capabilities("oj")
        assert capabilities["nativeToolNames"] == ["balance", "z_native"]
        assert launcher.capabilities("as")["nativeToolNames"] == ["as_native"]
        assert capabilities["features"]["externalMcp"] is False
        capabilities["nativeToolNames"].append("injected")
        assert launcher.capabilities("oj")["nativeToolNames"] == ["balance", "z_native"]
        for name in ("as_native", "private_native", "injected"):
            body = request()
            body["environment"]["requiredToolNames"] = [name]
            with pytest.raises(LauncherError, match="TOOL_NOT_INSTALLED"):
                launcher.create("owner", "oj", body, "key-" + name)
        assert not backend.preparing.is_set()
        state = ready(launcher)
        value = case()
        value["requiredTools"] = ["as_native"]
        with pytest.raises(LauncherError, match="TOOL_NOT_INSTALLED"):
            launcher.execute("owner", "oj", state["instanceId"], value)
        assert backend.calls == []
    finally:
        launcher.close()


def test_native_tool_inventory_can_be_empty():
    launcher = Launcher(Backend(), {"oj": {**preset(), "toolNames": []}})
    try:
        assert launcher.capabilities("oj")["nativeToolNames"] == []
    finally:
        launcher.close()


def test_http_authenticated_protocol(service):
    launcher, _ = service
    token = "test-only-token-" + "x" * 32
    server = make_server(launcher, {hashlib.sha256(token.encode()).hexdigest(): {"owner": "owner", "runtimes": ["oj"]}})
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = f"http://127.0.0.1:{server.server_port}/oj/v1/runtime-capabilities"
    try:
        with pytest.raises(urllib.error.HTTPError) as exc:
            urllib.request.urlopen(url)
        assert exc.value.code == 401
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Authorization": "Bearer " + token})) as response:
            capabilities = json.load(response)
            assert capabilities["runtime"]["version"] == "0.1.15"
            assert capabilities["nativeToolNames"] == ["balance"]
        with pytest.raises(urllib.error.HTTPError) as exc:
            urllib.request.urlopen(urllib.request.Request(url, headers={"Authorization": "Bearer " + token, "Origin": "https://untrusted.example"}))
        assert exc.value.code == 403
        req = urllib.request.Request(url.replace("runtime-capabilities", "runtime-instances"), data=b'{"a":1,"a":2}',
                                     headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        with pytest.raises(urllib.error.HTTPError) as exc:
            urllib.request.urlopen(req)
        assert json.load(exc.value)["error"] == "DUPLICATE_JSON_FIELD"
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def wait_until(predicate):
    deadline = time.monotonic() + 3
    while not predicate() and time.monotonic() < deadline:
        time.sleep(.005)
    assert predicate()


def test_cleanup_failure_retains_handle_and_retries(service):
    launcher, backend = service
    state = ready(launcher)
    instance = launcher.instances[state["instanceId"]]
    original = backend.close
    handle = instance.handle
    def fail(value):
        raise RuntimeError("private/path/secret")
    backend.close = fail
    with pytest.raises(LauncherError, match="^INSTANCE_CLEANUP_FAILED$"):
        launcher.delete("owner", "oj", instance.id)
    assert instance.deleted and instance.handle is handle
    assert instance.cleanup_error == "INSTANCE_CLEANUP_FAILED"
    assert not launcher._released(instance)
    backend.close = original
    launcher.delete("owner", "oj", instance.id)
    launcher.delete("owner", "oj", instance.id)
    assert backend.closed == [handle] and instance.handle is None


def test_ttl_cleanup_exception_isolated_and_retried(service):
    launcher, backend = service
    first = launcher.instances[ready(launcher)["instanceId"]]
    second = launcher.instances[ready(launcher, key="second")["instanceId"]]
    original = backend.close
    failed_handle = first.handle
    def fail_one(handle):
        if handle is failed_handle:
            raise RuntimeError("private failure")
        original(handle)
    backend.close = fail_one
    launcher.clock = lambda: max(first.expires, second.expires) + 1
    launcher.reap()
    assert first.handle is failed_handle and first.cleanup_error
    assert second.deleted and second.handle is None
    launcher.reap()
    assert first.id in launcher.instances and second.id not in launcher.instances
    backend.close = original
    launcher.reap()
    launcher.reap()
    assert not launcher.instances


def test_concurrent_delete_ttl_does_not_duplicate_close_or_release_quota(service):
    launcher, backend = service
    launcher.max_instances = 1
    instance = launcher.instances[ready(launcher)["instanceId"]]
    entered, finish = threading.Event(), threading.Event()
    original = backend.close
    calls = []
    def close(handle):
        calls.append(handle)
        entered.set()
        assert finish.wait(3)
        original(handle)
    backend.close = close
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(launcher.delete, "owner", "oj", instance.id)
        try:
            assert entered.wait(1)
            launcher.clock = lambda: instance.expires + 1
            launcher.reap()
            launcher.delete("owner", "oj", instance.id)
            assert instance.id in launcher.instances and len(calls) == 1
            with pytest.raises(LauncherError, match="INSTANCE_LIMIT_REACHED"):
                launcher.create("owner", "oj", request(), "second")
        finally:
            finish.set()
        pending.result(timeout=2)
    launcher.reap()
    assert instance.id not in launcher.instances and len(calls) == 1


def test_shutdown_cleanup_failure_does_not_skip_other_instances(service):
    launcher, backend = service
    first = launcher.instances[ready(launcher)["instanceId"]]
    second = launcher.instances[ready(launcher, key="second")["instanceId"]]
    failed_handle = first.handle
    original = backend.close
    def fail_one(handle):
        if handle is failed_handle:
            raise RuntimeError("private failure")
        original(handle)
    backend.close = fail_one
    launcher.close()
    assert first.handle is failed_handle and second.handle is None
    assert launcher.pool._shutdown
    backend.close = original
    launcher.close()
    assert first.handle is None


def test_running_prepare_keeps_quota_until_late_cleanup(service):
    launcher, backend = service
    launcher.max_instances = 1
    backend.prepare_gate.clear()
    state = launcher.create("owner", "oj", request(), "key")
    instance = launcher.instances[state["instanceId"]]
    assert backend.preparing.wait(1)
    launcher.delete("owner", "oj", instance.id)
    with pytest.raises(LauncherError, match="INSTANCE_LIMIT_REACHED"):
        launcher.create("owner", "oj", request(), "second")
    original = backend.close
    def fail(handle):
        raise RuntimeError("late cleanup failure")
    backend.close = fail
    backend.prepare_gate.set()
    instance.prepare_future.result(timeout=2)
    assert instance.handle is not None and instance.cleanup_error
    with pytest.raises(LauncherError, match="INSTANCE_LIMIT_REACHED"):
        launcher.create("owner", "oj", request(), "second")
    backend.close = original
    ready(launcher, key="second")


def test_queued_prepare_future_cancelled_before_backend_call():
    backend = Backend()
    launcher = Launcher(backend, {"oj": preset()}, max_instances=1)
    gate, started = threading.Event(), threading.Event()
    def occupy_worker():
        started.set()
        gate.wait(3)
    blocker = launcher.pool.submit(occupy_worker)
    try:
        assert started.wait(1)
        state = launcher.create("owner", "oj", request(), "key")
        instance = launcher.instances[state["instanceId"]]
        launcher.delete("owner", "oj", instance.id)
        assert instance.prepare_future.cancelled()
        assert not backend.preparing.is_set()
        assert launcher._released(instance)
        launcher.create("owner", "oj", request(), "second")
    finally:
        gate.set()
        blocker.result(timeout=2)
        launcher.close()


def test_async_retirement_keeps_handle_and_quota(service):
    launcher, backend = service
    launcher.max_instances = 1
    instance = launcher.instances[ready(launcher)["instanceId"]]
    instance.handle = SimpleNamespace(retired=threading.Event())
    handle = instance.handle
    backend.close = lambda value: None
    launcher.delete("owner", "oj", instance.id)
    assert instance.handle is handle
    with pytest.raises(LauncherError, match="INSTANCE_LIMIT_REACHED"):
        launcher.create("owner", "oj", request(), "second")
    launcher.clock = lambda: instance.expires + 1
    launcher.reap()
    assert instance.id in launcher.instances
    handle.retired.set()
    ready(launcher, key="second")


def test_delete_during_execution_keeps_quota_until_worker_finishes(service):
    launcher, backend = service
    launcher.max_instances = 1
    instance = launcher.instances[ready(launcher)["instanceId"]]
    entered, finish = threading.Event(), threading.Event()
    original = backend.execute
    def execute(*args):
        entered.set()
        finish.wait(3)
        return original(*args)
    backend.execute = execute
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(launcher.execute, "owner", "oj", instance.id, case())
        try:
            assert entered.wait(1)
            launcher.delete("owner", "oj", instance.id)
            with pytest.raises(LauncherError, match="INSTANCE_LIMIT_REACHED"):
                launcher.create("owner", "oj", request(), "second")
        finally:
            finish.set()
        with pytest.raises(LauncherError, match="INSTANCE_NOT_FOUND"):
            future.result(timeout=2)
    ready(launcher, key="second")


def test_idempotency_at_retention_capacity(service):
    launcher, _ = service
    state = ready(launcher)
    for n in range(127):
        ident = f"tombstone-{n}"
        launcher.instances[ident] = Instance(ident, "owner", "oj", {}, "", launcher.clock() + 100, deleted=True)
    assert launcher.create("owner", "oj", request(), "key-1")["instanceId"] == state["instanceId"]
    changed = request()
    changed["runId"] = "different"
    with pytest.raises(LauncherError, match="IDEMPOTENCY_CONFLICT"):
        launcher.create("owner", "oj", changed, "key-1")
    with pytest.raises(LauncherError, match="INSTANCE_RETENTION_LIMIT_REACHED"):
        launcher.create("owner", "oj", request(), "new")


@pytest.mark.parametrize("files,code", [
    ({"SKILL.md": "x" * (MAX_FILE + 1)}, "ARCHIVE_EXPANDED_LIMIT"),
    ({"SKILL.md": "x", **{f"references/{n}.md": "x" for n in range(MAX_FILES)}}, "ARCHIVE_FILE_LIMIT"),
    ({"SKILL.md": "x", **{f"references/{n}.md": "x" * MAX_FILE for n in range(4)}}, "ARCHIVE_EXPANDED_LIMIT"),
])
def test_archive_backend_limits(files, code):
    body = request(source(files))
    with pytest.raises(LauncherError, match=code):
        validate_skill(body["skill"], body["variant"])


def test_archive_upload_limit_and_capabilities(service):
    launcher, _ = service
    limits = launcher.capabilities("oj")["limits"]
    assert limits["maxArchiveBytes"] == MAX_UPLOAD == 512 * 1024
    assert limits["maxFiles"] == MAX_FILES == 32
    body = request()
    raw = b"x" * (MAX_UPLOAD + 1)
    body["skill"].update(archiveBase64=base64.b64encode(raw).decode(), archiveSha256=hashlib.sha256(raw).hexdigest())
    with pytest.raises(LauncherError, match="ARCHIVE_(LIMIT|DIGEST_MISMATCH)"):
        validate_skill(body["skill"], body["variant"])


@pytest.fixture
def bounded_http(service):
    launcher, _ = service
    token = "t" * 40
    server = make_server(launcher, {hashlib.sha256(token.encode()).hexdigest(): {"owner": "owner", "runtimes": ["oj"]}},
                         max_connections=1, read_timeout=.2)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.01), daemon=True)
    thread.start()
    yield server, token
    server.shutdown()
    server.server_close()
    thread.join(timeout=2)


def test_http_header_timeout_and_connection_limit(bounded_http):
    server, token = bounded_http
    address = server.server_address
    with socket.create_connection(address, timeout=2) as stalled:
        stalled.sendall(b"GET /oj/v1/runtime-capabilities HTTP/1.0\r\n")
        wait_until(lambda: server.slots._value == 0)
        with socket.create_connection(address, timeout=2) as extra:
            response = extra.recv(4096)
            assert b"503 Service Unavailable" in response and b"HTTP_CONNECTION_LIMIT" in response
        assert stalled.recv(4096) == b""
    wait_until(lambda: server.slots._value == 1)
    url = f"http://127.0.0.1:{server.server_port}/oj/v1/runtime-capabilities"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"Authorization": "Bearer " + token}), timeout=2) as response:
        assert response.status == 200


def test_http_body_timeout_and_full_body_limit(bounded_http):
    server, token = bounded_http
    headers = ("POST /oj/v1/runtime-instances HTTP/1.0\r\nAuthorization: Bearer " + token +
               "\r\nContent-Type: application/json\r\nContent-Length: ")
    with socket.create_connection(server.server_address, timeout=2) as stalled:
        stalled.sendall((headers + "100\r\n\r\n{").encode())
        assert b"408 Request Timeout" in stalled.recv(4096)
    wait_until(lambda: server.slots._value == 1)
    with socket.create_connection(server.server_address, timeout=2) as oversized:
        oversized.sendall((headers + str(MAX_REQUEST_BYTES + 1) + "\r\n\r\n").encode())
        assert b"413" in oversized.recv(4096)


def test_http_allowed_alias_cannot_cross_preset_owner(service, bounded_http):
    launcher, backend = service
    server, token = bounded_http
    launcher.presets["oj"]["owner"] = "ownerA"
    url = f"http://127.0.0.1:{server.server_port}/oj/v1/runtime-instances"
    req = urllib.request.Request(url, data=json.dumps(request()).encode(), headers={
        "Authorization": "Bearer " + token, "Content-Type": "application/json", "Idempotency-Key": "key"})
    with pytest.raises(urllib.error.HTTPError) as exc:
        urllib.request.urlopen(req, timeout=2)
    assert exc.value.code == 403
    assert json.load(exc.value) == {"error": "RUNTIME_OWNER_MISMATCH"}
    assert not backend.preparing.is_set() and not launcher.instances


def test_full_json_duplicate_files_budget(service, bounded_http):
    launcher, _ = service
    server, token = bounded_http
    files = {"SKILL.md": "\x01" * MAX_FILE, **{f"references/{n}.md": "\x01" * MAX_FILE for n in range(3)}}
    assert sum(len(v.encode()) for v in files.values()) == MAX_TOTAL
    body = request(source(files))
    checked = validate_skill(body["skill"], body["variant"])
    launcher.presets["oj"]["sourceFileDigests"] = checked["sourceFileDigests"]
    raw = json.dumps(body, ensure_ascii=True).encode()
    assert len(raw) < MAX_REQUEST_BYTES
    url = f"http://127.0.0.1:{server.server_port}/oj/v1/runtime-instances"
    req = urllib.request.Request(url, data=raw, headers={"Authorization": "Bearer " + token,
        "Content-Type": "application/json", "Idempotency-Key": "large"})
    with urllib.request.urlopen(req, timeout=3) as response:
        assert response.status == 202


@pytest.mark.parametrize("failure", ["failed-build", "cancel", "ordinary-error", "close-error"])
def test_real_backend_failed_prepare_transfers_cleanup_ownership(tmp_path, monkeypatch, failure):
    from finance_solution_builder.eval_launcher_backend import BuilderBackend
    backend = BuilderBackend(str(tmp_path), str(tmp_path), str(tmp_path / "private"))
    backend.poll_seconds = .002
    jobs = backend.jobs
    # Fault injection replaces admission/build work, not prepare/close/reaper or Jobs.delete.
    monkeypatch.setattr(backend, "_validate", lambda handle: None)
    created, permit_delete, delete_attempted = threading.Event(), threading.Event(), threading.Event()
    key = "agent-prepare-failure"
    private_error = str(tmp_path / "private-key-canary")
    def post(path, body, owner):
        assert path == "/api/v1/builds"
        with jobs.lock:
            jobs.jobs[key] = {"id": key, "owner": owner,
                              "status": "BUILDING" if failure == "cancel" else "FAILED"}
        jobs.path(key).mkdir(parents=True)
        (jobs.path(key) / "build-report.json").write_text('{"status":"FAILED","stages":[]}')
        created.set()
        return 202, {"buildId": key, "buildStatus": "FAILED" if failure in {"failed-build", "close-error"} else "RUNNING"}
    def inspect(*args):
        if failure == "ordinary-error":
            raise RuntimeError(private_error)
        return {"buildId": key, "buildStatus": "RUNNING"}
    backend.api = SimpleNamespace(post=post, inspect=inspect)
    original_delete = jobs.delete
    def delete(*args):
        delete_attempted.set()
        if not permit_delete.is_set():
            raise OSError(private_error)
        return original_delete(*args)
    monkeypatch.setattr(jobs, "delete", delete)
    if failure == "close-error":
        original_close = backend.close
        close_calls = []
        def close(handle):
            close_calls.append(handle)
            if len(close_calls) == 1:
                raise RuntimeError(private_error)
            original_close(handle)
        monkeypatch.setattr(backend, "close", close)
    config = {**preset(), "owner": "owner", "selection": {}}
    launcher = Launcher(backend, {"oj": config}, max_instances=1)
    instance = None
    try:
        state = launcher.create("owner", "oj", request(), "first")
        instance = launcher.instances[state["instanceId"]]
        assert created.wait(1)
        if failure == "cancel":
            launcher.delete("owner", "oj", instance.id)
        instance.prepare_future.result(timeout=2)
        handle = instance.handle
        assert handle is not None and handle.build_id == key
        assert not handle.retired.is_set() and not launcher._released(instance)
        assert instance.error == {"failed-build": "EVAL_BUILD_FAILED", "cancel": "EVAL_CANCELLED",
                                  "ordinary-error": "EVAL_PREPARE_FAILED", "close-error": "EVAL_PREPARE_CLEANUP_FAILED"}[failure]
        public = json.dumps(launcher._state(instance))
        assert private_error not in public and "cleanup_handle" not in public and key not in public
        with pytest.raises(LauncherError, match="INSTANCE_LIMIT_REACHED"):
            launcher.create("owner", "oj", request(), "second")
        if failure == "cancel":
            with jobs.lock:
                jobs.jobs[key]["status"] = "FAILED"
        assert delete_attempted.wait(1)
        handle.reaper.join(timeout=2)
        assert handle.cleanup_error == "EVAL_BUILD_RETIRE_FAILED"
        assert key in jobs.jobs and jobs.path(key).exists()
        launcher.reap()
        handle.reaper.join(timeout=2)
        assert instance.handle is handle and not launcher._released(instance)
        permit_delete.set()
        launcher.reap()
        assert handle.retired.wait(2)
        launcher.reap()
        assert instance.handle is None and launcher._released(instance)
        assert key not in jobs.jobs and not jobs.path(key).exists()
        if failure != "cancel":
            assert launcher.get("owner", "oj", instance.id)["status"] == "failed"
    finally:
        permit_delete.set()
        with jobs.lock:
            if key in jobs.jobs:
                jobs.jobs[key]["status"] = "FAILED"
        launcher.close()
        if instance is not None and instance.handle is not None and instance.handle.reaper is not None:
            instance.handle.reaper.join(timeout=2)
        jobs.close()


def test_ordinary_exception_cannot_supply_cleanup_handle(service):
    launcher, backend = service
    error = RuntimeError("private credential canary")
    error.cleanup_handle = object()
    def prepare(*args):
        raise error
    backend.prepare = prepare
    state = launcher.create("owner", "oj", request(), "fail")
    instance = launcher.instances[state["instanceId"]]
    instance.prepare_future.result(timeout=2)
    assert instance.handle is None
    assert launcher.get("owner", "oj", instance.id)["error"] == "BUILD_OR_ADMISSION_FAILED"
    assert not backend.closed


def test_real_backend_preallocation_failure_does_not_hold_quota(tmp_path, monkeypatch):
    from finance_solution_builder.eval_launcher_backend import BackendError, BuilderBackend
    backend = BuilderBackend(str(tmp_path), str(tmp_path), str(tmp_path / "private"))
    def reject(handle):
        raise BackendError("EVAL_SOURCE_NOT_ADMITTED")
    monkeypatch.setattr(backend, "_validate", reject)
    launcher = Launcher(backend, {"oj": preset()}, max_instances=1)
    try:
        state = launcher.create("owner", "oj", request(), "first")
        instance = launcher.instances[state["instanceId"]]
        instance.prepare_future.result(timeout=2)
        assert instance.handle is None and launcher._released(instance)
        assert instance.error == "EVAL_SOURCE_NOT_ADMITTED"
        assert not backend.jobs.jobs
        launcher.create("owner", "oj", request(), "second")
    finally:
        launcher.close()
        backend.jobs.close()


@pytest.mark.parametrize("error,expected", [
    ("EVAL_HOST_REJECTED", "EVAL_HOST_REJECTED"),
    ("EVAL_EXECUTION_FAILED", "EVAL_EXECUTION_FAILED"),
    ("EVAL_PRIVATE /private/key", "CASE_FAILED_INSTANCE_RELEASED"),
    ("EVAL_HOST_REJECTED\n", "CASE_FAILED_INSTANCE_RELEASED"),
    ("EVAL_" + "X" * 81, "CASE_FAILED_INSTANCE_RELEASED"),
    ({"secret": "canary"}, "CASE_FAILED_INSTANCE_RELEASED"),
    (None, "CASE_FAILED_INSTANCE_RELEASED"),
])
@pytest.mark.parametrize("verified", [False, True])
def test_backend_failed_result_keeps_safe_error_and_cleans(service, error, expected, verified):
    launcher, backend = service
    instance = launcher.instances[ready(launcher)["instanceId"]]
    result = {"status": "failed", "error": error, "runtimeIdentityVerified": verified,
              "output": "private failure output", "metadata": {"private": "canary"}}
    def execute(handle, *args):
        handle["cancel"].set()
        return result
    backend.execute = execute
    with pytest.raises(LauncherError) as exc:
        launcher.execute("owner", "oj", instance.id, case())
    assert exc.value.status == 502 and exc.value.code == expected
    assert instance.deleted and instance.handle is None and len(backend.closed) == 1
    assert launcher._released(instance)
    assert result["runtimeIdentityVerified"] is verified


@pytest.mark.parametrize("retire", ["delete", "ttl"])
def test_external_retirement_wins_over_backend_failed_result(service, retire):
    launcher, backend = service
    instance = launcher.instances[ready(launcher)["instanceId"]]
    entered, finish = threading.Event(), threading.Event()
    def execute(handle, *args):
        entered.set()
        assert finish.wait(3)
        handle["cancel"].set()
        return {"status": "failed", "error": "EVAL_HOST_REJECTED", "runtimeIdentityVerified": False}
    backend.execute = execute
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(launcher.execute, "owner", "oj", instance.id, case())
        try:
            assert entered.wait(1)
            if retire == "delete":
                launcher.delete("owner", "oj", instance.id)
            else:
                launcher.clock = lambda: instance.expires + 1
                launcher.reap()
        finally:
            finish.set()
        with pytest.raises(LauncherError) as exc:
            future.result(timeout=2)
    assert exc.value.status == 404 and exc.value.code == "INSTANCE_NOT_FOUND"
    assert instance.handle is None and len(backend.closed) == 1


def test_failed_execution_cleanup_retry_does_not_mask_primary_error(service):
    launcher, backend = service
    instance = launcher.instances[ready(launcher)["instanceId"]]
    handle = instance.handle
    def execute(value, *args):
        value["cancel"].set()
        return {"status": "failed", "error": "EVAL_HOST_REJECTED", "runtimeIdentityVerified": False}
    def fail_close(value):
        raise RuntimeError("private cleanup canary")
    backend.execute = execute
    original = backend.close
    backend.close = fail_close
    with pytest.raises(LauncherError) as exc:
        launcher.execute("owner", "oj", instance.id, case())
    assert exc.value.status == 502 and exc.value.code == "EVAL_HOST_REJECTED"
    assert instance.handle is handle and instance.cleanup_error == "INSTANCE_CLEANUP_FAILED"
    assert not launcher._released(instance)
    backend.close = original
    launcher.reap()
    assert instance.handle is None and launcher._released(instance)


def test_unverified_completed_result_still_rejected(service):
    launcher, backend = service
    instance = launcher.instances[ready(launcher)["instanceId"]]
    backend.bad_identity = True
    with pytest.raises(LauncherError) as exc:
        launcher.execute("owner", "oj", instance.id, case())
    assert exc.value.status == 502 and exc.value.code == "RUNTIME_EVIDENCE_UNVERIFIED"
    assert instance.handle is None


@pytest.mark.parametrize("generation", [None, {}, {"schemaVersion": "other", "temperature": 0, "top_p": 1, "max_tokens": 1},
    {"schemaVersion": "finance.eval-generation.v1", "temperature": True, "top_p": 1, "max_tokens": 1},
    {"schemaVersion": "finance.eval-generation.v1", "temperature": 0, "top_p": 1, "max_tokens": 1, "url": "https://untrusted"}])
def test_generation_invalid_before_backend(service, generation):
    launcher, backend = service
    body = {**request(), "generation": generation}
    with pytest.raises(LauncherError, match="EVAL_GENERATION_INVALID"):
        launcher.create("owner", "oj", body, "key")
    assert not backend.preparing.is_set() and not launcher.instances


def test_generation_idempotency_and_request_copy(service):
    launcher, _ = service
    body = {**request(), "generation": {"schemaVersion": "finance.eval-generation.v1", "temperature": 0, "top_p": 1, "max_tokens": 1}}
    state = launcher.create("owner", "oj", body, "key")
    instance = launcher.instances[state["instanceId"]]
    instance.prepare_future.result(timeout=2)
    assert launcher.create("owner", "oj", body, "key")["configurationDigest"] == state["configurationDigest"]
    body["generation"]["max_tokens"] = 2
    assert instance.request["generation"]["max_tokens"] == 1
    with pytest.raises(LauncherError, match="IDEMPOTENCY_CONFLICT"):
        launcher.create("owner", "oj", body, "key")
