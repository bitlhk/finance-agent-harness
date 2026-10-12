"""Loopback-only service-to-service facade for Finance Skill Eval Launcher v1."""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import signal
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from .eval_launcher_service import Launcher, LauncherError, MAX_REQUEST_BYTES, require


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_JSON_FIELD")
        result[key] = value
    return result


def make_server(launcher, principals, host="127.0.0.1", port=0, *, max_connections=16, read_timeout=15):
    require(host in {"127.0.0.1", "localhost"}, "LOOPBACK_REQUIRED")
    require(type(max_connections) is int and 1 <= max_connections <= 128
            and isinstance(read_timeout, (int, float)) and 0 < read_timeout <= 60, "HTTP_LIMIT_INVALID")
    require(principals and all(len(k) == 64 and set(v) == {"owner", "runtimes"}
                              for k, v in principals.items()), "AUTH_CONFIGURATION_INVALID")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def reply(self, code, body):
            raw = json.dumps(body, ensure_ascii=False, allow_nan=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def handle_request(self):
            try:
                require(not self.headers.get("Origin"), "SERVER_CLIENT_REQUIRED", 403)
                auth = self.headers.get("Authorization", "")
                require(auth.startswith("Bearer ") and 32 <= len(auth[7:]) <= 256, "AUTH_REQUIRED", 401)
                token_hash = hashlib.sha256(auth[7:].encode()).hexdigest()
                principal = next((v for k, v in principals.items() if hmac.compare_digest(k, token_hash)), None)
                require(principal is not None, "AUTH_REQUIRED", 401)
                url = urlsplit(self.path)
                require(not url.query and not url.fragment and not url.netloc and "%" not in url.path, "ROUTE_INVALID", 404)
                parts = url.path.strip("/").split("/")
                require(len(parts) >= 3 and parts[1] == "v1", "ROUTE_INVALID", 404)
                runtime = parts[0]
                require(runtime in principal["runtimes"], "RUNTIME_NOT_ALLOWED", 403)
                owner = principal["owner"]
                body = None
                if self.command == "POST":
                    require(not self.headers.get("Transfer-Encoding") and self.headers.get("Content-Type", "").split(";")[0] == "application/json", "JSON_REQUIRED")
                    length = int(self.headers.get("Content-Length", "0"))
                    require(0 < length <= MAX_REQUEST_BYTES, "REQUEST_TOO_LARGE", 413)
                    body = json.loads(self.rfile.read(length), object_pairs_hook=unique_object,
                                      parse_constant=lambda _: (_ for _ in ()).throw(LauncherError("JSON_INVALID")))
                if parts[2:] == ["runtime-capabilities"] and self.command == "GET":
                    return self.reply(200, launcher.capabilities(runtime))
                if parts[2:] == ["runtime-instances"] and self.command == "POST":
                    return self.reply(202, launcher.create(owner, runtime, body, self.headers.get("Idempotency-Key", "")))
                require(len(parts) in {4, 5} and parts[2] == "runtime-instances", "ROUTE_INVALID", 404)
                if len(parts) == 4 and self.command == "GET":
                    return self.reply(200, launcher.get(owner, runtime, parts[3]))
                if len(parts) == 4 and self.command == "DELETE":
                    launcher.delete(owner, runtime, parts[3])
                    return self.reply(200, {"deleted": True})
                if len(parts) == 5 and parts[4] == "execute" and self.command == "POST":
                    return self.reply(200, launcher.execute(owner, runtime, parts[3], body))
                raise LauncherError("ROUTE_INVALID", 404)
            except LauncherError as error:
                self.reply(error.status, {"error": error.code})
            except (ValueError, UnicodeError):
                self.reply(422, {"error": "REQUEST_INVALID"})
            except (BrokenPipeError, ConnectionResetError):
                pass
            except TimeoutError:
                self.reply(408, {"error": "REQUEST_TIMEOUT"})
            except Exception:
                self.reply(500, {"error": "LAUNCHER_FAILED"})

        do_GET = do_POST = do_DELETE = handle_request

    class BoundedServer(ThreadingHTTPServer):
        def __init__(self, *args):
            self.slots = threading.BoundedSemaphore(max_connections)
            super().__init__(*args)

        def get_request(self):
            connection, address = super().get_request()
            connection.settimeout(read_timeout)
            return connection, address

        def process_request(self, request, address):
            if not self.slots.acquire(blocking=False):
                try:
                    request.settimeout(.05)
                    request.sendall(b'HTTP/1.0 503 Service Unavailable\r\nConnection: close\r\n'
                                    b'Content-Type: application/json\r\nContent-Length: 33\r\n\r\n'
                                    b'{"error":"HTTP_CONNECTION_LIMIT"}')
                except OSError:
                    pass
                finally:
                    self.shutdown_request(request)
                return
            try:
                super().process_request(request, address)
            except Exception:
                self.slots.release()
                raise

        def process_request_thread(self, request, address):
            try:
                super().process_request_thread(request, address)
            finally:
                self.slots.release()

    server = BoundedServer((host, port), Handler)
    server.daemon_threads = True
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18218)
    args = parser.parse_args()
    require(args.config.is_file() and not args.config.is_symlink()
            and not args.config.stat().st_mode & 0o077, "PRIVATE_CONFIG_REQUIRED")
    config = json.loads(args.config.read_text(), object_pairs_hook=unique_object)
    from .eval_launcher_backend import BuilderBackend
    backend = BuilderBackend(**config["backend"])
    launcher = Launcher(backend, config["presets"], max_instances=config.get("maxInstances", 2),
                        ttl_seconds=config.get("ttlSeconds", 1800))
    server = make_server(launcher, config["principals"], port=args.port)
    stopping = threading.Event()

    def stop(*_args):
        stopping.set()
        threading.Thread(target=server.shutdown, daemon=True).start()

    def reap():
        while not stopping.wait(1):
            launcher.reap()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    threading.Thread(target=reap, daemon=True).start()
    try:
        server.serve_forever(poll_interval=.2)
    finally:
        stopping.set()
        server.server_close()
        launcher.close()


if __name__ == "__main__":
    main()
