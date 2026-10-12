"""Read-only edge checks. Credentials are read privately, never printed."""
import base64
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request


def main():
    access_file, public_dir, internal_dir, output = map(Path, sys.argv[1:])
    assert access_file.stat().st_mode & 0o077 == 0, "PRIVATE_ACCESS_FILE_REQUIRED"
    access = json.loads(access_file.read_text())
    base = access["url"].rstrip("/")
    token = base64.b64encode((access["username"] + ":" + access["password"]).encode()).decode()
    checks, hashes = [], {}

    def request(route, authenticated=False, method="GET", overrides=None):
        time.sleep(0.12)
        headers = {"Authorization": "Basic " + token} if authenticated else {}
        headers.update(overrides or {})
        req = urllib.request.Request(base + route, method=method, headers=headers)
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.read(), response.headers

    def check(name, passed):
        assert passed, name
        checks.append(name)

    for route in ["/", "/api/bootstrap", "/api/v1/catalog", "/api/v1/builds",
                  "/developer-guide/internal", "/developer-guide/internal/",
                  "/developer-guide/internal/index.html", "/developer-guide/internal/handbook.pdf"]:
        check("anonymous-denied:" + route, request(route)[0] == 401)
    check("spoofed-proxy-denied", request("/api/bootstrap", overrides={"X-Finance-User": "studio-admin", "X-Finance-Proxy-Token": "not-trusted"})[0] == 401)
    check("anonymous-build-post-denied", request("/api/v1/builds", method="POST")[0] == 401)
    for route, file, content_type, authenticated in [
        ("/developer-guide/", public_dir / "index.html", "text/html", False),
        ("/developer-guide/handbook.pdf", public_dir / "handbook.pdf", "application/pdf", False),
        ("/developer-guide/internal/", internal_dir / "index.html", "text/html", True),
        ("/developer-guide/internal/handbook.pdf", internal_dir / "Finance-Harness-开发指导-20260908.pdf", "application/pdf", True),
    ]:
        status, body, headers = request(route, authenticated)
        check("read:" + route, status == 200)
        check("mime:" + route, headers.get("Content-Type", "").startswith(content_type))
        digest = hashlib.sha256(body).hexdigest()
        check("exact-digest:" + route, digest == hashlib.sha256(file.read_bytes()).hexdigest())
        check("no-cache:" + route, "no-store" in headers.get("Cache-Control", ""))
        check("no-cookie:" + route, not headers.get("Set-Cookie"))
        check("nosniff:" + route, headers.get("X-Content-Type-Options") == "nosniff")
        if content_type == "text/html":
            check("no-connections:" + route, "connect-src 'none'" in headers.get("Content-Security-Policy", ""))
        hashes[route] = digest
    for route in ["/developer-guide/manifest.json", "/developer-guide/sources/harness/README.md",
                  "/developer-guide/.git/config", "/developer-guide/internal/sources/harness/README.md"]:
        check("unpublished-file-denied:" + route, request(route)[0] == 404)
    check("anonymous-index-readable", request("/developer-guide/index.html")[0] == 200)
    check("short-path-readable", request("/developer-guide")[0] == 200)
    for route in ["/developer-guide/", "/developer-guide/index.html", "/developer-guide/handbook.pdf"]:
        check("document-write-denied:" + route, request(route, method="POST")[0] == 403)
    check("workbench-auth-still-active", request("/api/bootstrap", True)[0] == 200)
    result = {"mode": "PUBLIC_REDACTED_GUIDE_PRIVATE_WORKBENCH", "checks": checks,
              "count": len(checks), "hashes": hashes,
              "scope": "Static documents, denied unauthenticated request, authenticated metadata; no model, bank or build operations"}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
