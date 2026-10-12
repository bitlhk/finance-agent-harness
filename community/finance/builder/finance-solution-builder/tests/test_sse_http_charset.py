import http.cookiejar
from http.server import ThreadingHTTPServer
import json
import threading
from types import SimpleNamespace
import urllib.error
import urllib.request

import pytest

from finance_solution_builder import ag_ui_transport, portable_ui, workbench
from finance_solution_builder.experience import Experience
from finance_solution_builder.experience_contracts import ApiError


TEXT = "\u8d26\u6237\u4f59\u989d\uff1a123.45\u5143"
EVENT = {"type": "TEXT_MESSAGE_CONTENT", "messageId": "message-test", "delta": TEXT}
RAW = ("data: " + json.dumps(EVENT, ensure_ascii=False) + "\n\n").encode("utf-8")
BUILD = "agent-charset"
TOKEN = "local-test-ticket"


def payload():
    return {"threadId": "session-test", "runId": "run-test", "state": {}, "tools": [],
            "context": [], "messages": [{"id": "user-test", "role": "user", "content": TEXT}],
            "forwardedProps": {"instanceId": "instance-test"}}


@pytest.fixture
def http_server(monkeypatch):
    running = []

    def start(module, target):
        ready = threading.Event()
        servers, errors = [], []

        class Server(ThreadingHTTPServer):
            daemon_threads = True

            def serve_forever(self, poll_interval=0.01):
                servers.append(self)
                ready.set()
                super().serve_forever(poll_interval=poll_interval)

        def serve():
            try:
                target()
            except BaseException as error:
                errors.append(error)
                ready.set()

        monkeypatch.setattr(module, "ThreadingHTTPServer", Server)
        thread = threading.Thread(target=serve, daemon=True)
        running.append((thread, servers, errors))
        thread.start()
        assert ready.wait(5), "HTTP server did not become ready"
        assert not errors
        return "http://127.0.0.1:" + str(servers[0].server_port)

    yield start
    for thread, servers, errors in running:
        for server in servers:
            server.shutdown()
        thread.join(10)
        assert not thread.is_alive()
        assert not errors


def post(opener, base, path, headers):
    request = urllib.request.Request(base + path, data=json.dumps(payload()).encode(),
        headers={"Content-Type": "application/json", "Origin": base, **headers})
    try:
        return opener.open(request, timeout=5)
    except urllib.error.HTTPError as error:
        return error


def assert_chinese_sse(response):
    with response:
        assert response.status == 200
        assert response.headers["Content-Type"] == "text/event-stream; charset=utf-8"
        assert response.headers["Cache-Control"] == "no-store"
        raw = response.read()
        assert raw == RAW
        decoded = raw.decode(response.headers.get_content_charset())
        assert TEXT in decoded
        assert json.loads(decoded.removeprefix("data: ").strip()) == EVENT


@pytest.mark.parametrize("route", ["workbench", "experience"])
def test_workbench_sse_routes_declare_utf8_over_http(tmp_path, monkeypatch, http_server, route):
    emitted = []

    def current(self, build, token):
        if build != BUILD or token != TOKEN:
            raise ApiError("EXPERIENCE_REJECTED", 403)
        return {}

    def stream(self, build, token, body, send):
        current(self, build, token)
        assert body == payload()
        emitted.append(route)
        send(RAW)

    def run(api, build, owner, request, send):
        assert build == BUILD and owner
        emitted.append(route)
        send(RAW)

    monkeypatch.setattr(workbench, "PROJECT", tmp_path)
    monkeypatch.setattr(workbench.signal, "signal", lambda *args: None)
    monkeypatch.setattr(Experience, "current", current)
    monkeypatch.setattr(Experience, "stream", stream)
    monkeypatch.setattr(ag_ui_transport, "stream_run", run)
    base = http_server(workbench, lambda: workbench.serve(tmp_path, 0))
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    with opener.open(base + "/api/bootstrap", timeout=5) as response:
        csrf = json.load(response)["csrf"]
    if route == "workbench":
        path, headers = f"/api/v1/builds/{BUILD}/ag-ui", {"X-CSRF-Token": csrf}
    else:
        path, headers = f"/experience/{BUILD}/api/ag-ui", {"X-Agent-Token": TOKEN}
    assert_chinese_sse(post(opener, base, path, headers))
    with post(opener, base, path, {}) as denied:
        assert 400 <= denied.status < 600
        assert denied.headers.get_content_type() == "application/json"
    assert emitted == [route]


def test_portable_sse_declares_utf8_over_http(tmp_path, monkeypatch, http_server):
    emitted = []

    class TicketExperience:
        def __init__(self, host):
            pass

        def issue(self, build, owner):
            assert build == BUILD and owner == "local"
            return {"url": f"/experience/{build}/#{TOKEN}"}

        def current(self, build, token):
            if build != BUILD or token != TOKEN:
                raise ApiError("EXPERIENCE_REJECTED", 403)

        def call(self, build, token, action, body):
            self.current(build, token)
            assert action == "status" and body == {}

        def stream(self, build, token, body, send):
            self.current(build, token)
            assert body == payload()
            emitted.append("portable")
            send(RAW)

    def child(*args, **kwargs):
        (tmp_path / "instance.json").write_text(json.dumps(
            {"buildId": BUILD, "instanceId": "instance-test", "port": 1}))
        return SimpleNamespace(poll=lambda: 0)

    (tmp_path / "service-lock.json").write_text(json.dumps({"files": {}, "interface": {"enabled": True}}))
    monkeypatch.setenv("FH_UI_PORT", "0")
    monkeypatch.setattr(portable_ui.subprocess, "Popen", child)
    monkeypatch.setattr(portable_ui, "Experience", TicketExperience)
    base = http_server(portable_ui, lambda: portable_ui.serve(tmp_path))
    opener = urllib.request.build_opener()
    path = f"/experience/{BUILD}/api/ag-ui"
    assert_chinese_sse(post(opener, base, path, {"X-Agent-Token": TOKEN}))
    with post(opener, base, path, {}) as denied:
        assert denied.status == 403
        assert denied.headers.get_content_type() == "application/json"
    assert emitted == ["portable"]
