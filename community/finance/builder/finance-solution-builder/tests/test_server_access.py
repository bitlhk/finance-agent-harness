from email.message import Message
import json
import threading
import urllib.error
import urllib.request

import pytest

from finance_solution_builder import workbench
from finance_solution_builder.server_access import ServerAccess, exclusive_data_dir
from finance_solution_builder.service import write_json, digest


@pytest.fixture
def access(tmp_path):
    token = tmp_path / "proxy-token"
    token.write_text("a" * 64 + "\n")
    token.chmod(0o600)
    return ServerAccess.load("https://studio.example.internal", token)


def headers(**overrides):
    value = Message()
    for name, content in dict({"Host": "studio.example.internal", "X-Finance-Proxy-Token": "a" * 64,
                              "X-Finance-User": "alice"}, **overrides).items():
        value[name] = content
    return value


@pytest.mark.parametrize("override", [
    {"Host": "evil.internal"}, {"Origin": "https://evil.internal"},
    {"X-Finance-Proxy-Token": ""}, {"X-Finance-Proxy-Token": "b" * 64},
    {"X-Finance-User": ""}, {"X-Finance-User": "../alice"},
])
def test_proxy_gate_fails_closed(access, override):
    with pytest.raises(PermissionError):
        access.authenticate(headers(**override), "127.0.0.1")


def test_peer_duplicate_headers_and_stable_owner(access):
    assert access.authenticate(headers(), "127.0.0.1") == "alice"
    with pytest.raises(PermissionError):
        access.authenticate(headers(), "10.0.0.2")
    repeated = headers()
    repeated["X-Finance-User"] = "bob"
    with pytest.raises(PermissionError):
        access.authenticate(repeated, "127.0.0.1")
    assert access.owner("alice", b"key") == access.owner("alice", b"key")
    assert access.owner("alice", b"key") != access.owner("bob", b"key")


@pytest.mark.parametrize("origin", ["http://studio.internal", "https://studio.internal/", "https://user@studio.internal",
                                    "https://studio.internal?q=1", "https://studio.internal/#a", "https://studio.internal:99999"])
def test_invalid_origin_rejected(access, tmp_path, origin):
    with pytest.raises(ValueError):
        ServerAccess.load(origin, tmp_path / "proxy-token")


def test_private_credentials_and_single_writer(access, tmp_path):
    token = tmp_path / "proxy-token"
    token.chmod(0o644)
    with pytest.raises(ValueError, match="PRIVATE_FILE_REQUIRED"):
        ServerAccess.load(access.origin, token)
    token.chmod(0o600)
    link = tmp_path / "link"
    link.symlink_to(token)
    with pytest.raises(OSError):
        ServerAccess.load(access.origin, link)
    root = tmp_path / "data"
    with exclusive_data_dir(root):
        with pytest.raises(ValueError, match="IN_USE"):
            with exclusive_data_dir(root):
                pass
    with exclusive_data_dir(root):
        pass
    root.chmod(0o755)
    with pytest.raises(ValueError, match="PRIVATE_DATA"):
        with exclusive_data_dir(root):
            pass


@pytest.fixture
def server(tmp_path, monkeypatch, access):
    servers, instances = [], []
    ready = threading.Event()
    real_server, real_jobs = workbench.ThreadingHTTPServer, workbench.Jobs
    def make_server(*args):
        value = real_server(*args)
        servers.append(value)
        ready.set()
        return value
    def make_jobs(*args, **kwargs):
        value = real_jobs(*args, **kwargs)
        instances.append(value)
        return value
    monkeypatch.setattr(workbench, "ThreadingHTTPServer", make_server)
    monkeypatch.setattr(workbench, "Jobs", make_jobs)
    monkeypatch.setattr(workbench.signal, "signal", lambda *args: None)
    monkeypatch.setattr(workbench, "PROJECT", tmp_path)
    data = tmp_path / "data"
    thread = threading.Thread(target=workbench.serve, args=(tmp_path, 0), kwargs={
        "data_dir": data, "access": access, "max_instances": 2})
    thread.start()
    assert ready.wait(5)
    base = "http://127.0.0.1:" + str(servers[0].server_port)
    def request(path, body=None, *, user="alice", cookie="", csrf="", overrides=None):
        values = dict(headers(**{"X-Finance-User": user}))
        values.update({"Cookie": cookie, "Origin": access.origin, "X-CSRF-Token": csrf, "Content-Type": "application/json"})
        values.update(overrides or {})
        req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=values)
        try:
            response = urllib.request.urlopen(req, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            value = json.loads(raw) if "json" in response.headers.get("Content-Type", "") else raw
            return response.code, value, response.headers
    yield request, instances[0]
    servers[0].shutdown()
    thread.join(10)
    assert not thread.is_alive()


@pytest.mark.parametrize(
    "path",
    [
        "/finance-harness-logo.png",
        "/finance-harness-mark.png",
        "/finance-harness-favicon.png",
    ],
)
def test_brand_assets_are_served_as_versioned_static_files(server, path):
    request, _ = server
    code, body, response = request(path)
    assert code == 200
    assert response["Content-Type"] == "image/png"
    assert body.startswith(b"\x89PNG\r\n\x1a\n")


def test_http_login_binding_csrf_and_owner_isolation(server):
    request, jobs = server
    code, boot, response = request("/api/bootstrap")
    assert code == 200 and boot["deploymentMode"] == "AUTHENTICATED_SERVER"
    cookie = response["Set-Cookie"].split(";")[0]
    assert cookie.startswith("__Host-fh_studio=") and "; Secure" in response["Set-Cookie"]
    assert "HttpOnly" in response["Set-Cookie"] and "SameSite=Strict" in response["Set-Cookie"]
    owner = cookie.split("=", 1)[1].split(".")[0]
    job = dict(id="agent-one", owner=owner, status="BUILT", capabilities=["account.balance.query"])
    jobs.jobs[job["id"]] = job
    jobs.save(job)
    root = jobs.path(job["id"])
    candidate = root / "candidate"
    candidate.mkdir(parents=True)
    write_json(candidate / "service-lock.json", {"files": {}, "buildId": job["id"], "verification": "COMPILED"})
    write_json(root / "build-report.json", {"status": "BUILT", "private": "/secret/internal/path",
                                           "lockSha256": digest(candidate / "service-lock.json")})
    code, value, _ = request("/api/v1/builds", cookie=cookie)
    assert code == 200 and value["builds"][0]["buildId"] == "agent-one" and "secret" not in json.dumps(value)
    assert request("/api/v1/builds", user="bob", cookie=cookie)[0] == 403
    assert request("/api/v1/builds", cookie=cookie, overrides={"X-Finance-Proxy-Token": ""})[0] == 403
    assert request("/api/v1/builds", cookie=cookie, overrides={"Origin": "https://wrong.internal"})[0] == 403
    assert request("/api/jobs/agent-one", cookie=cookie)[0] == 404
    assert request("/api/jobs", {}, cookie=cookie, csrf=boot["csrf"])[0] == 404
    assert request("/api/jobs/agent-one/artifact", cookie=cookie)[0] == 200
    _, bob, bob_response = request("/api/bootstrap", user="bob")
    bob_cookie = bob_response["Set-Cookie"].split(";")[0]
    assert request("/api/v1/builds/agent-one", user="bob", cookie=bob_cookie)[0] == 403
    assert request("/api/jobs/agent-one/artifact", user="bob", cookie=bob_cookie)[0] == 403
    assert request("/api/v1/builds/agent-one/delete", {"buildId": "agent-one"}, user="bob",
                   cookie=bob_cookie, csrf=bob["csrf"])[0] == 403
    assert request("/api/v1/builds/agent-one/delete", {"buildId": "agent-one"}, cookie=cookie)[0] == 403
    assert request("/api/v1/builds/agent-one/delete", {"buildId": "agent-one"}, cookie=cookie, csrf=boot["csrf"])[0] == 200
    assert not root.exists() and (jobs.data_dir / "deletion-receipts/agent-one.json").is_file()


def test_http_bootstrap_stable_login_does_not_publish_raw_reports(server):
    request, jobs = server
    _, first, headers1 = request("/api/bootstrap")
    _, second, headers2 = request("/api/bootstrap")
    assert first["csrf"] == second["csrf"] and headers1["Set-Cookie"] == headers2["Set-Cookie"]
    assert first["jobs"] == []
    assert request("/api/bootstrap", overrides={"Host": "127.0.0.1"})[0] == 403
    assert request("/app.js", overrides={"X-Finance-Proxy-Token": ""})[0] == 403


def test_external_storage_restores_owner_and_limits_instances(tmp_path, monkeypatch):
    project = tmp_path / "source"
    project.mkdir()
    data = tmp_path / "state"
    data.mkdir(mode=0o700)
    jobs = workbench.Jobs(project, project, data_dir=data, max_instances=1)
    job = dict(id="agent-persist", owner="alice", status="STOPPED", capabilities=[])
    jobs.jobs[job["id"]] = job
    jobs.save(job)
    jobs.close()
    restored = workbench.Jobs(project, project, data_dir=data, max_instances=1)
    try:
        assert restored.owned("agent-persist", "alice")["status"] == "STOPPED"
        with pytest.raises(PermissionError):
            restored.owned("agent-persist", "bob")
        assert not (project / "build").exists()
        assert restored.builder.service_output_root == data
        restored.processes["other"] = type("Child", (), {"poll": lambda self: None})()
        monkeypatch.setattr(workbench, "spawn_process", lambda *a, **kw: pytest.fail("spawned"))
        with pytest.raises(ValueError, match="INSTANCE_LIMIT_REACHED"):
            restored.start("agent-persist", "alice")
    finally:
        restored.processes.clear()
        restored.close()
