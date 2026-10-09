"""Probe the installed source dependency, not Host qualification or a deployment change."""
import hashlib
import importlib.metadata
import json

import anyio
import httpx
from mcp.server.lowlevel.server import Server
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager


PROBE_TTL = 0.25
HEADERS = {
    "Accept": "application/json, text/event-stream",
    "MCP-Protocol-Version": "2025-06-18",
}
INITIALIZE = {
    "jsonrpc": "2.0", "id": 1, "method": "initialize",
    "params": {
        "protocolVersion": "2025-06-18", "capabilities": {},
        "clientInfo": {"name": "cleanup-probe", "version": "1"},
    },
}
INITIALIZED = {"jsonrpc": "2.0", "method": "notifications/initialized"}


async def main():
    default_manager = StreamableHTTPSessionManager(Server("default-probe"))
    manager = StreamableHTTPSessionManager(
        Server("ttl-probe"), json_response=True, session_idle_timeout=PROBE_TTL,
    )
    header_seen, release_body, held_done, stop_heartbeat = (anyio.Event() for _ in range(4))
    held_id = []

    async def app(scope, receive, send):
        mode = dict(scope["headers"]).get(b"x-probe-mode")

        async def observed_send(message):
            if message["type"] == "http.response.start":
                headers = dict(message["headers"])
                if mode == b"hold":
                    held_id.append(headers[b"mcp-session-id"].decode("ascii"))
                    header_seen.set()
                if mode == b"oversized":
                    headers[b"content-length"] = str(256 * 1024 + 1).encode("ascii")
                    message = {**message, "headers": list(headers.items())}
            elif message["type"] == "http.response.body":
                if mode == b"hold":
                    await release_body.wait()
                elif mode == b"oversized":
                    message = {**message, "body": b"x" * (256 * 1024 + 1)}
            await send(message)

        await manager.handle_request(scope, receive, observed_send)

    async with manager.run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://127.0.0.1:56363", headers=HEADERS) as client:
            orphan = await client.post("/mcp", json=INITIALIZE, headers={"x-probe-mode": "oversized"})
            assert orphan.status_code == 200 and len(orphan.content) > 256 * 1024
            orphan_id = orphan.headers["mcp-session-id"]
            control = await client.post("/mcp", json=INITIALIZE)
            control_id = control.headers["mcp-session-id"]
            assert orphan_id != control_id

            async def notify(session_id):
                return await client.post("/mcp", json=INITIALIZED, headers={"Mcp-Session-Id": session_id})

            assert (await notify(control_id)).status_code == 202

            async def heartbeat():
                while not stop_heartbeat.is_set():
                    response = await client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "ping"},
                                                 headers={"Mcp-Session-Id": control_id})
                    assert response.status_code == 200
                    await anyio.sleep(PROBE_TTL / 5)

            async def held_initialize():
                response = await client.post("/mcp", json=INITIALIZE, headers={"x-probe-mode": "hold"})
                assert response.status_code == 200
                held_done.set()

            async with anyio.create_task_group() as tasks:
                tasks.start_soon(heartbeat)
                try:
                    await anyio.sleep(PROBE_TTL * 3)
                    orphan_status = (await notify(orphan_id)).status_code
                    assert orphan_status == 404
                    control_status = (await notify(control_id)).status_code
                    assert control_status == 202
                    tasks.start_soon(held_initialize)
                    await header_seen.wait()
                    await anyio.sleep(PROBE_TTL * 3)
                    held_status = (await notify(held_id[0])).status_code
                    assert held_status == 202, "Idle TTL is suspended while initialize is in flight"
                    release_body.set()
                    await held_done.wait()
                    await anyio.sleep(PROBE_TTL * 3)
                    released_status = (await notify(held_id[0])).status_code
                    assert released_status == 404
                finally:
                    release_body.set()
                    stop_heartbeat.set()

    provenance = {}
    for package, files in {
        "mcp": ("mcp/server/streamable_http_manager.py", "mcp/server/streamable_http.py"),
        "fastmcp": ("fastmcp/server/http.py",),
    }.items():
        distribution = importlib.metadata.distribution(package)
        provenance[package] = {
            "version": distribution.version,
            "sha256": {name: hashlib.sha256(distribution.locate_file(name).read_bytes()).hexdigest() for name in files},
        }
    print(json.dumps({
        "qualification": "BLOCK",
        "defaultIdleTimeoutSeconds": default_manager.session_idle_timeout,
        "defaultTtlWallClockTested": False,
        "probeIdleTimeoutSeconds": PROBE_TTL,
        "oversizedInitializeAfterIdleStatus": orphan_status,
        "otherSessionWhileKeptAliveStatus": control_status,
        "initializeInFlightBeyondIdleStatus": held_status,
        "initializeAfterReleasedIdleStatus": released_status,
        "deleteRequests": 0,
        "provenance": provenance,
    }, sort_keys=True))


if __name__ == "__main__":
    anyio.run(main)
