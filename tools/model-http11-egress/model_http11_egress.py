#!/usr/bin/env python3
"""Loopback-only HTTP/1.1 streaming egress for one allowlisted model connection."""

import hashlib
import hmac
import http.client
import json
import os
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

MAX_REQUEST_BYTES = 512 * 1024
LOCAL_PATH = "/v1/chat/completions"
_metrics_lock = threading.Lock()


def configuration():
    base = urlsplit(os.environ["MODEL_EGRESS_UPSTREAM_BASE"].rstrip("/"))
    if base.scheme != "https" or not base.hostname or base.username or base.password or base.query or base.fragment:
        raise SystemExit("MODEL_EGRESS_UPSTREAM_BASE_INVALID")
    expected_origin = "https://" + base.hostname + ((":" + str(base.port)) if base.port else "")
    if os.environ.get("MODEL_EGRESS_ALLOWED_ORIGIN") != expected_origin:
        raise SystemExit("MODEL_EGRESS_ORIGIN_NOT_ALLOWLISTED")
    for name in ("MODEL_EGRESS_UPSTREAM_KEY", "MODEL_EGRESS_CALLER_TOKEN", "MODEL_EGRESS_MODEL"):
        if len(os.environ.get(name, "")) < 16:
            raise SystemExit(name + "_INVALID")
    path = base.path.rstrip("/")
    upstream_path = path + "/chat/completions" if path.endswith("/v1") else path + LOCAL_PATH
    return base, upstream_path


BASE, UPSTREAM_PATH = configuration()
METRICS_PATH = os.environ.get("MODEL_EGRESS_METRICS_PATH")


def metric(value):
    value = {"atEpochMs": int(time.time() * 1000), **value}
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"))
    if METRICS_PATH:
        with _metrics_lock:
            with open(METRICS_PATH, "a", encoding="utf-8") as output:
                output.write(encoded + "\n")
    print(encoded, file=sys.stderr, flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        return

    def reject(self, status, code):
        payload = json.dumps({"error": code}, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True

    def do_POST(self):
        if self.client_address[0] not in ("127.0.0.1", "::1") or self.path != LOCAL_PATH:
            self.reject(404, "MODEL_EGRESS_ROUTE_DENIED")
            return
        expected = "Bearer " + os.environ["MODEL_EGRESS_CALLER_TOKEN"]
        if not hmac.compare_digest(self.headers.get("Authorization", ""), expected):
            self.reject(401, "MODEL_EGRESS_CALLER_DENIED")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_REQUEST_BYTES:
            self.reject(413, "MODEL_EGRESS_REQUEST_SIZE_INVALID")
            return
        body = self.rfile.read(length)
        try:
            request = json.loads(body)
        except Exception:
            self.reject(400, "MODEL_EGRESS_REQUEST_INVALID")
            return
        if request.get("stream") is not True or request.get("model") != os.environ["MODEL_EGRESS_MODEL"]:
            self.reject(400, "MODEL_EGRESS_REQUEST_NOT_ALLOWLISTED")
            return

        connection = http.client.HTTPSConnection(BASE.hostname, BASE.port, timeout=180,
                context=ssl.create_default_context())
        try:
            connection.request("POST", UPSTREAM_PATH, body=body, headers={
                "Authorization": "Bearer " + os.environ["MODEL_EGRESS_UPSTREAM_KEY"],
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
            })
            upstream = connection.getresponse()
        except Exception as failure:
            metric({"event": "open_failed", "failureType": type(failure).__name__})
            self.reject(502, "MODEL_EGRESS_UPSTREAM_UNAVAILABLE")
            connection.close()
            return

        content_type = upstream.getheader("Content-Type", "").split(";", 1)[0].lower()
        upstream_id = upstream.getheader("x-request-id") or upstream.getheader("x-tt-logid") or ""
        metric({"event": "opened", "protocol": "HTTP/1.1" if upstream.version == 11 else "HTTP/1.0",
                "status": upstream.status, "contentType": content_type,
                "upstreamRequestIdDigest": hashlib.sha256(upstream_id.encode()).hexdigest() if upstream_id else None})
        if upstream.status != 200 or content_type != "text/event-stream":
            upstream.read(MAX_REQUEST_BYTES + 1)
            self.reject(502, "MODEL_EGRESS_UPSTREAM_REJECTED")
            connection.close()
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        events = 0
        done = False
        downstream_failed = False
        while True:
            line = upstream.readline()
            if not line:
                break
            try:
                self.wfile.write(("%x\r\n" % len(line)).encode("ascii"))
                self.wfile.write(line)
                self.wfile.write(b"\r\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                downstream_failed = True
                break
            text = line.decode("utf-8", "replace").strip()
            if text.startswith("data:"):
                payload = text[5:].strip()
                if payload == "[DONE]":
                    done = True
                elif payload:
                    events += 1
        if not downstream_failed:
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        metric({"event": "closed", "events": events, "doneObserved": done,
                "downstreamFailed": downstream_failed})
        connection.close()


def main():
    host = "127.0.0.1"
    port = int(os.environ.get("MODEL_EGRESS_PORT", "61412"))
    metric({"event": "started", "listen": host + ":" + str(port), "model": os.environ["MODEL_EGRESS_MODEL"]})
    ThreadingHTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
