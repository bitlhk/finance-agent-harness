"""Opt-in real-model task-selection checks against synthetic banking HTTP fixtures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import time

from verify_local_service import Endpoints, expiry, request
from finance_solution_builder.service import stop_owned_process


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not all(os.environ.get(k) for k in ("FH_MODEL_URL", "FH_MODEL_KEY", "FH_MODEL_NAME")):
        raise ValueError("EXPLICIT_REAL_MODEL_CONFIGURATION_REQUIRED")
    candidate = args.candidate.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    bank = Endpoints()
    policy = args.output / "access-policy.json"
    policy.write_text(json.dumps({"schemaVersion": "local-simulator-access.v0.1", "bankUrl": bank.base,
        "subjects": {bank.customer: {"enabled": True, "expiresAt": expiry(),
            "scopes": ["account:read", "wealth:facts:read", "wealth:customer:read"]}}}))
    policy.chmod(0o600)
    token = secrets.token_urlsafe(32)
    env = dict(os.environ, FH_BANK_URL=bank.base, FH_BANK_LOGIN="demo", FH_BANK_SECRET="demo123",
               FH_ACCESS_POLICY=str(policy.resolve()), FH_HOST_TOKEN=token, FH_PORT="0", FH_MODEL_MODE="LIVE")
    info = candidate / "instance.json"
    info.unlink(missing_ok=True)
    jar = next((candidate / "target").glob("*.jar"))
    report = {"mode": "EXPLICIT_REAL_MODEL_WITH_SYNTHETIC_BANK", "model": env["FH_MODEL_NAME"],
              "cases": [], "realBankIam": "NOT_RUN", "humanReview": "NOT_RUN",
              "artifactLockSha256": hashlib.sha256((candidate / "service-lock.json").read_bytes()).hexdigest()}
    cases = [
        [("查下我的余额", "balance"), ("你好", "chat"), ("谢谢", "chat"), ("再查一次余额", "balance")],
        [("你好", "chat"), ("你好，顺便查一下我的余额", "balance"), ("下午好", "chat")],
        [("看看我的资产和风险测评", "wealth"), ("你好", "chat"), ("只查账户余额，不查财富概览", "balance")],
        [("查下我的余额", "balance"), ("你好", "chat")],
        [("你能做什么？", "chat"), ("先不要查询我的账户，聊聊天吧", "chat")],
    ]
    child = None
    try:
        with (args.output / "host.log").open("wb") as log:
            child = subprocess.Popen(["java", "-Xmx512m", "-cp", str(jar)+os.pathsep+str(candidate/"target/dependency/*"),
                "io.github.bitlhk.finance.builder.local.LocalAgentHost", str(candidate)],
                cwd=candidate, env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 30
            while not info.exists():
                if child.poll() is not None or time.monotonic() > deadline: raise RuntimeError("HOST_START_FAILED")
                time.sleep(.1)
            instance = json.loads(info.read_text())
            base = "http://127.0.0.1:" + str(instance["port"])
            for session_index, sequence in enumerate(cases):
                status, opened = request(base, token, "/sessions", {})
                if status != 200: raise RuntimeError("TEST_SESSION_REJECTED")
                sid = opened["sessionId"]
                for turn, (query, expected) in enumerate(sequence):
                    before = len(bank.reads)
                    began = time.monotonic()
                    status, result = request(base, token, f"/sessions/{sid}/chat", {"query": query})
                    tool_names = {e["tool"] for e in result.get("events", []) if e.get("hook") == "AFTER_TOOL_CALL"}
                    expected_tools = {"account_balance_query"} if expected == "balance" else {"wealth_facts_describe"} if expected == "wealth" else set()
                    response = json.dumps(result.get("response"), ensure_ascii=False)
                    item = {"session": session_index, "turn": turn, "query": query, "expected": expected,
                            "status": status, "tools": sorted(tool_names), "newBusinessReads": len(bank.reads)-before,
                            "elapsedSeconds": round(time.monotonic()-began, 2), "evidenceSha256": result.get("evidenceSha256")}
                    item["passed"] = status == 200 and tool_names == expected_tools
                    if expected == "chat":
                        item["passed"] &= len(bank.reads) == before and not any(amount in response for amount in
                            ("12345.67", "12,345.67", "1000000.12", "1,000,000.12"))
                    elif expected == "balance":
                        item["passed"] &= len(bank.reads) > before and any(amount in response for amount in ("12345.67", "12,345.67"))
                    report["cases"].append(item)
                    (args.output / f"session-{session_index}-turn-{turn}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
                    print(json.dumps(item, ensure_ascii=False), flush=True)
                request(base, token, f"/sessions/{sid}/revoke", {})
            report["businessWrites"] = len(bank.writes)
            report["status"] = "PASSED" if all(c["passed"] for c in report["cases"]) and not bank.writes else "FAILED"
    finally:
        stop_owned_process(child)
        bank.close()
        policy.unlink(missing_ok=True)
        (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    if report["status"] != "PASSED": raise AssertionError("DIALOGUE_SCOPE_NOT_PASSED")


if __name__ == "__main__": main()
