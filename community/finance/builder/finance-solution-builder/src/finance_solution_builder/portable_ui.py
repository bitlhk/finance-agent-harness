"""Loopback-only exported UI gateway. Reuses the exact workbench experience boundary."""
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from .experience import Experience
from .experience_contracts import ApiError, digest


class PortableHost:
    def __init__(self, root, child, token, info):
        self.root, self.child, self.token, self.info = root, child, token, info
        self.lock = threading.RLock()
        self.jobs = {info["buildId"]: {"status": "RUNNING", "instanceId": info["instanceId"]}}
    def owned(self, build, owner):
        if build not in self.jobs or owner != "local":
            raise ApiError("EXPERIENCE_REJECTED", 403)
        return self.jobs[build]
    def path(self, build):
        # Experience's existing artifact path contract is job/candidate.
        class CandidatePath:
            def __truediv__(_, name):
                if name != "candidate":
                    raise ValueError("PATH_INVALID")
                return self.root
        return CandidatePath()
    def _capture(self, build): return self.info["instanceId"]
    def _same_instance(self, build, target):
        return build in self.jobs and target == self.info["instanceId"] and self.child.poll() is None
    def _request(self, build, route, body, instance):
        if not self._same_instance(build, instance):
            raise ApiError("INSTANCE_CHANGED", 409)
        request = urllib.request.Request(f"http://127.0.0.1:{self.info['port']}" + route,
            data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        try:
            response = urllib.request.urlopen(request, timeout=120)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.code, json.load(response)


def serve(root):
    root = Path(root).resolve()
    lock = json.loads((root / "service-lock.json").read_text())
    for name, expected in lock["files"].items():
        file = root / name
        if not file.resolve().is_relative_to(root) or not file.is_file() or digest(file) != expected:
            raise SystemExit("ARTIFACT_DIGEST_MISMATCH")
    if not lock.get("interface"):
        raise SystemExit("TERMINAL_REBUILD_REQUIRED")
    token = secrets.token_urlsafe(40)
    env = dict(os.environ, FH_HOST_TOKEN=token, FH_EXPECTED_LOCK=digest(root / "service-lock.json"), FH_PORT="0")
    info_file = root / "instance.json"
    info_file.unlink(missing_ok=True)
    log = (root / "ui-host.log").open("ab")
    child = subprocess.Popen([sys.executable, str(root / "run.py")], cwd=root, env=env,
        stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    server = None
    try:
        deadline = time.monotonic() + 40
        while not info_file.exists():
            if child.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("HOST_START_FAILED: inspect ui-host.log")
            time.sleep(.1)
        info = json.loads(info_file.read_text())
        host = PortableHost(root, child, token, info)
        experience = Experience(host)
        build = info["buildId"]
        prefix = "/experience/" + build + "/"
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def reply(self, code, body):
                payload = json.dumps(body, ensure_ascii=False).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            def safe(self):
                expected = f"127.0.0.1:{self.server.server_port}"
                if self.headers.get("Host") != expected or self.headers.get("Origin", "http://" + expected) != "http://" + expected:
                    raise ApiError("ORIGIN_REJECTED", 403)
            def do_GET(self):
                try:
                    self.safe()
                    if not self.path.startswith(prefix):
                        raise ApiError("NOT_FOUND", 404)
                    name = self.path[len(prefix):] or "index.html"
                    if name not in {"index.html", "app.js", "style.css", "icons.svg"}:
                        raise ApiError("NOT_FOUND", 404)
                    file = root / "ui" / name
                    if digest(file) != lock["files"]["ui/" + name]:
                        raise ApiError("ARTIFACT_CHANGED", 409)
                    self.send_response(200)
                    self.send_header("Content-Type", {"html":"text/html", "js":"text/javascript", "css":"text/css", "svg":"image/svg+xml"}[file.suffix[1:]])
                    self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'")
                    self.send_header("Referrer-Policy", "no-referrer")
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(file.read_bytes())
                except ApiError as error:
                    self.reply(error.status, {"error": {"code": error.code}})
            def do_POST(self):
                try:
                    self.safe()
                    if not self.path.startswith(prefix + "api/"):
                        raise ApiError("NOT_FOUND", 404)
                    action = self.path[len(prefix + "api/"):]
                    ticket = self.headers.get("X-Agent-Token", "")
                    experience.current(build, ticket)
                    size = int(self.headers.get("Content-Length", "0"))
                    if size < 1 or size > (4*1024*1024 if action == "transcribe" else 16384):
                        raise ApiError("BODY_TOO_LARGE", 413)
                    payload = self.rfile.read(size)
                    if action == "transcribe":
                        return self.reply(200, experience.audio(build, ticket, payload, self.headers.get("Content-Type", "")))
                    body = json.loads(payload)
                    if not isinstance(body, dict):
                        raise ApiError("REQUEST_INVALID")
                    if action == "ag-ui":
                        experience.call(build, ticket, "status", {})
                        self.send_response(200)
                        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                        self.send_header("Cache-Control", "no-store")
                        self.end_headers()
                        experience.stream(build, ticket, body, lambda value: (self.wfile.write(value), self.wfile.flush()))
                    else:
                        self.reply(200, experience.call(build, ticket, action, body))
                except ApiError as error:
                    self.reply(error.status, {"error": {"code": error.code}})
                except ValueError as error:
                    code = str(error) if str(error).startswith(("ASR_", "AUDIO_")) else "REQUEST_INVALID"
                    self.reply(400, {"error": {"code": code}})
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception:
                    self.reply(503, {"error": {"code": "SERVICE_UNAVAILABLE"}})
        server = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("FH_UI_PORT", "0"))), Handler)
        server.daemon_threads = True
        ticket = experience.issue(build, "local")
        url = f"http://127.0.0.1:{server.server_port}" + ticket["url"]
        (root / "ui-instance.json").write_text(json.dumps({"url": url, "buildId": build, "instanceId": info["instanceId"]}))
        (root / "ui-instance.json").chmod(0o600)
        print("LOCAL_UI_READY " + url, flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if server:
            server.server_close()
        if child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
        log.close()
