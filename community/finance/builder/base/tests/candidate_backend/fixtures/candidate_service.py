"""Standard-library protocol double for the Python Candidate Backend tests."""

from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import request as url_request


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/health":
            self.send_error(404)
            return
        self._write({"status": "ready"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/a2a":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        if "query" in request:
            adapter_url = os.environ.get("HARNESS_ADAPTER_URL", "")
            if not adapter_url:
                self.send_error(503)
                return
            canonical_request = {
                "schemaVersion": "finance-capability-request.v1",
                "capabilityId": "wealth.previsit-context.query",
                "input": {"query": request["query"]},
            }
            outbound = url_request.Request(
                adapter_url,
                data=json.dumps(canonical_request).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with url_request.urlopen(outbound, timeout=5) as response:
                canonical_response = json.loads(response.read())
            self._write({"result": {"intent": canonical_response["intent"]}})
            return
        self._write(
            {
                "jsonrpc": "2.0",
                "id": request.get("id", "probe"),
                "result": {"status": "COMPLETED", "prediction": "fixture-intent"},
            }
        )

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def _write(self, document: dict[str, object]) -> None:
        body = json.dumps(document).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    port = int(os.environ["PORT"])
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
