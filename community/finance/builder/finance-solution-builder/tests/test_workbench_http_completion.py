import http.cookiejar
from http.server import ThreadingHTTPServer
import json
import threading
import urllib.error
import urllib.request

import pytest

from finance_solution_builder import workbench


@pytest.mark.parametrize("action", ["start", "stop"])
@pytest.mark.parametrize("message,status,public", [
    ("INSTANCE_CHANGED", 409, "INSTANCE_CHANGED"), ("private upstream detail", 503, "OPERATION_FAILED"),
])
def test_async_completion_http_error_is_safe_and_machine_readable(tmp_path, monkeypatch, action, message, status, public):
    servers = []
    ready = threading.Event()

    def server(*args):
        instance = ThreadingHTTPServer(*args)
        servers.append(instance)
        ready.set()
        return instance

    def fail(*args): raise RuntimeError(message)

    monkeypatch.setattr(workbench, "PROJECT", tmp_path)
    monkeypatch.setattr(workbench, "ThreadingHTTPServer", server)
    monkeypatch.setattr(workbench.signal, "signal", lambda *args: None)
    monkeypatch.setattr(workbench.Jobs, action, fail)
    thread = threading.Thread(target=workbench.serve, args=(tmp_path, 0))
    thread.start()
    try:
        assert ready.wait(5)
        base = "http://127.0.0.1:" + str(servers[0].server_port)
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        with opener.open(base + "/api/bootstrap", timeout=5) as response:
            boot = json.load(response)
        req = urllib.request.Request(base + "/api/jobs/agent-test/" + action, data=b"{}", headers={
            "Content-Type": "application/json", "X-CSRF-Token": boot["csrf"], "Origin": base})
        with pytest.raises(urllib.error.HTTPError) as caught:
            opener.open(req, timeout=5)
        with caught.value as response:
            assert response.code == status
            body = json.load(response)
        assert body["error"] == public and "private" not in json.dumps(body)
        if status == 409:
            assert body["clearConversation"]
    finally:
        if servers:
            servers[0].shutdown()
        thread.join(10)
        assert not thread.is_alive()
