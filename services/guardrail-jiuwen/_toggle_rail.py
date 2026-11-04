"""Toggle guardrail rail extension via JiuwenSwarm WebSocket API.

This script handles the _registered_rails cache issue:
- toggle OFF first to clear _registered_rails
- then delete + re-import to clear _rail_instances cache
- then toggle ON to register on the CURRENT agent instance

路径自动检测：无需修改任何硬编码路径，脚本会自动定位 adapters/jiuwen_adapter 目录。
"""
import asyncio
import json
import os
import sys

# 自动检测 adapter 目录路径（相对于本脚本所在目录）
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_ADAPTER_DIR = os.path.join(_SCRIPT_DIR, "adapters", "jiuwen_adapter").replace("\\", "/")


async def send_req(ws, req_id, method, params=None):
    """Send a request and read the response (skip non-matching messages)."""
    req = {"type": "req", "id": str(req_id), "method": method, "params": params or {}}
    await ws.send(json.dumps(req, ensure_ascii=False))
    # Read responses until we get one matching our id
    for _ in range(10):
        resp = await asyncio.wait_for(ws.recv(), timeout=15)
        data = json.loads(resp)
        if data.get("id") == str(req_id) or data.get("type") == "resp":
            return data
        # Skip notifications/events
    return data


async def main():
    try:
        import websockets
    except ImportError:
        print("websockets not installed: pip install websockets")
        sys.exit(1)

    uri = "ws://127.0.0.1:19000/ws"
    print(f"Connecting to {uri} ...")

    async with websockets.connect(uri) as ws:
        # Wait for connection.ack
        resp = await asyncio.wait_for(ws.recv(), timeout=10)
        print(f"connect: {resp[:100]}")

        req_id = 0

        # 1. List extensions
        req_id += 1
        resp = await send_req(ws, req_id, "extensions.list")
        payload = resp.get("payload", resp)
        extensions = payload.get("extensions", [])
        print(f"\n--- extensions.list ---")
        for ext in extensions:
            print(f"  {ext['name']}: enabled={ext['enabled']}, loaded={ext.get('loaded', '?')}")

        # 2. Toggle OFF to clear _registered_rails
        req_id += 1
        print(f"\n--- toggle OFF ---")
        resp = await send_req(ws, req_id, "extensions.toggle", {
            "name": "jiuwen_adapter", "enabled": False
        })
        print(f"  result: ok={resp.get('ok')}")

        await asyncio.sleep(2)

        # 3. Delete extension to clear _rail_instances cache
        req_id += 1
        print(f"\n--- delete extension ---")
        resp = await send_req(ws, req_id, "extensions.delete", {
            "name": "jiuwen_adapter"
        })
        print(f"  result: ok={resp.get('ok')}")

        await asyncio.sleep(1)

        # 4. Re-import extension from source
        req_id += 1
        print(f"\n--- import extension ---")
        resp = await send_req(ws, req_id, "extensions.import", {
            "folder_path": _ADAPTER_DIR
        })
        print(f"  result: ok={resp.get('ok')}")
        if not resp.get("ok"):
            print(f"  error: {resp.get('payload', {}).get('error', 'unknown')}")
            return

        await asyncio.sleep(1)

        # 5. Toggle ON to register on CURRENT agent instance
        req_id += 1
        print(f"\n--- toggle ON ---")
        resp = await send_req(ws, req_id, "extensions.toggle", {
            "name": "jiuwen_adapter", "enabled": True
        })
        print(f"  result: ok={resp.get('ok')}")
        if resp.get("ok"):
            print(f"  extension: {json.dumps(resp.get('payload', {}), ensure_ascii=False)}")
        else:
            print(f"  error: {resp.get('payload', {}).get('error', 'unknown')}")

        # 6. Verify
        req_id += 1
        resp = await send_req(ws, req_id, "extensions.list")
        payload = resp.get("payload", resp)
        extensions = payload.get("extensions", [])
        print(f"\n--- verify ---")
        for ext in extensions:
            if ext["name"] == "jiuwen_adapter":
                print(f"  {ext['name']}: enabled={ext['enabled']}, loaded={ext.get('loaded', '?')}")

        print("\nDone! Try sending a message in JiuwenSwarm now.")


asyncio.run(main())
