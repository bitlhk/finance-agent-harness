"""Build/instance-scoped end-user tickets. They cannot administer builds or read raw technical evidence."""
import hashlib
import json
import secrets
import time
import threading
import uuid
from .experience_contracts import ApiError, digest, response_text
from .task_routes import TASK_ACTION_FIELDS, validate_task_body, task_runtime_required


_AUDIT_SPECIALISTS = {
    "smartaudit.personal.borrower-qualification.query": "借款人主体核验专员",
    "smartaudit.personal.income-verification.query": "收入核验专员",
    "smartaudit.personal.asset-liability.query": "资产负债核验专员",
    "smartaudit.personal.repayment-capacity.query": "偿债能力评估专员",
    "smartaudit.enterprise.financial-report.query": "财务报表核验专员",
    "smartaudit.enterprise.financial-summary.query": "财务摘要分析专员",
    "smartaudit.enterprise.transaction-analysis.query": "交易流水分析专员",
    "smartaudit.enterprise.transaction-report.query": "交易报告复核专员",
    "smartaudit.enterprise.due-diligence.query": "尽职调查专员",
}

_PERSONAL_LOAN_CONTROLLED_POLICY = {
    "policyId": "PPL-DEMO-2026-01",
    "version": "1.0.0",
    "approvalStatus": "CONTROLLED_TEST_APPROVED",
    "effectiveFrom": "2026-01-01",
    "effectiveTo": "2026-12-31",
    "productionUse": False,
    "label": "个人贷款受控演示政策（仅测试）",
    "parameters": {
        "ageAndTerm": "借款人年龄与期限之和不超过 70 周岁",
        "collateral": "申请金额不高于抵押物评估值的 70%",
        "incomeVariance": "收入证明、流水、个税和社保的月度口径偏差不高于 15%",
        "repayment": "月均还款额不高于月均稳定收入的 50%；按收入下降 20% 压力测试",
    },
}

_PERSONAL_SUBAGENT_DETAILS = {
    "smartaudit.personal.borrower-qualification.query": {
        "materials": 4, "rules": 2,
        "scope": "核验借款人身份、家庭关系与申请主体信息",
        "evidence": "身份证、户口本、结婚证、贷款申请表",
        "dependency": "由审核案件编排器直接分派",
    },
    "smartaudit.personal.income-verification.query": {
        "materials": 4, "rules": 3,
        "scope": "交叉比对收入证明、流水、个税与社保",
        "evidence": "收入证明、流水明细、个税完税证明、社保证明",
        "dependency": "由审核案件编排器直接分派",
    },
    "smartaudit.personal.asset-liability.query": {
        "materials": 3, "rules": 2,
        "scope": "核验不动产信息与既有贷款负债情况",
        "evidence": "不动产权证书、不动产查询、贷款结清证明",
        "dependency": "由审核案件编排器直接分派",
    },
    "smartaudit.personal.repayment-capacity.query": {
        "materials": 4, "rules": 3,
        "scope": "测算收入稳定性、月供占比与压力情景偿债能力",
        "evidence": "贷款申请表、收入证明、银行流水、既有贷款信息",
        "dependency": "等待收入核验专员的受控回执",
    },
}

_AG_UI_TYPES = {"RUN_STARTED", "RUN_FINISHED", "RUN_ERROR", "STEP_STARTED", "STEP_FINISHED",
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "CUSTOM"}
_AG_UI_CUSTOM_NAMES = {"finance.subagents", "finance.reviewTeam", "finance.display", "finance.evidence",
                        "finance.clearConversation"}


def _completed_capabilities(execution_result):
    """Return only completed capability IDs; never send tool facts to the browser."""
    if not isinstance(execution_result, dict):
        return set()
    completed = set()
    for item in execution_result.get("executedCapabilities", []):
        if not isinstance(item, dict):
            continue
        capability = item.get("capabilityId")
        if isinstance(capability, str) and item.get("resultStatus") == "COMPLETED":
            completed.add(capability)
    return completed


def _subagents(capabilities, execution_result, orchestrator_status):
    completed_capabilities = _completed_capabilities(execution_result)
    timings = execution_result.get("auditTimings", {}) if isinstance(execution_result, dict) else {}
    timings = timings if isinstance(timings, dict) else {}
    members = [{"id": "case-orchestrator", "name": "审核案件编排器", "status": orchestrator_status,
                "summary": "正在校验受控测试政策与材料包" if orchestrator_status == "RUNNING" else "已汇总 4 项受控核验回执，待人工复核交接",
                "materials": 11, "rules": 4,
                "scope": "校验材料包、分派审核任务并汇总审核发现",
                "evidence": "材料完整性校验、四项受控核验回执"}]
    for capability in capabilities:
        name = _AUDIT_SPECIALISTS.get(capability)
        if not name:
            continue
        completed = capability in completed_capabilities
        member = {"id": capability, "name": name, "capabilityId": capability,
                        "status": "COMPLETED" if completed else "READY",
                        "summary": "受控核验已完成，结论待有权人工复核" if completed else "等待编排器调度"}
        member.update(_PERSONAL_SUBAGENT_DETAILS.get(capability, {}))
        elapsed = timings.get(capability)
        if isinstance(elapsed, int) and elapsed >= 0:
            member["elapsedMs"] = elapsed
        members.append(member)
    return {"policy": _PERSONAL_LOAN_CONTROLLED_POLICY if any(capability.startswith("smartaudit.personal.") for capability in capabilities) else None,
            "members": members}


def _review_team(capabilities, execution_result):
    completed_capabilities = _completed_capabilities(execution_result)
    members = [{"id": "case-orchestrator", "name": "审核案件编排器", "status": "COMPLETED"}]
    for capability in capabilities:
        name = _AUDIT_SPECIALISTS.get(capability)
        if name:
            members.append({"id": capability, "name": name, "capabilityId": capability,
                            "status": "COMPLETED" if capability in completed_capabilities else "READY"})
    return {"members": members}


class Experience:
    def __init__(self, jobs):
        self.jobs = jobs; self.tickets = {}; self.lock = jobs.lock

    def issue(self, build_id, owner):
        with self.lock:
            job = self.jobs.owned(build_id, owner)
            if job["status"] != "RUNNING": raise ApiError("INSTANCE_CHANGED", 409)
            root = self.jobs.path(build_id) / "candidate"
            lock = json.loads((root / "service-lock.json").read_text())
            if not lock.get("interface"): raise ApiError("TERMINAL_REBUILD_REQUIRED", 409)
            if digest(root / "ui-config.json") != lock["interface"]["sha256"]: raise ApiError("ARTIFACT_CHANGED", 409)
            self.tickets = {k:v for k,v in self.tickets.items() if v["expires"] > time.monotonic()}
            if len(self.tickets) >= 64: raise ApiError("SESSION_LIMIT", 429)
            token = secrets.token_urlsafe(36)
            self.tickets[hashlib.sha256(token.encode()).hexdigest()] = dict(build=build_id, owner=owner,
                target=self.jobs._capture(build_id), instance=job["instanceId"], session=None, pending=False,
                expires=time.monotonic()+3600, config=lock["interface"]["configuration"], capabilities=lock["capabilities"],
                tasks=task_runtime_required(root, lock),
                root=root, generation=0, speech=threading.Lock(), lockSha=digest(root / "service-lock.json"))
            return {"url": "/experience/" + build_id + "/#" + token, "expiresInSeconds": 3600}

    def current(self, build_id, token):
        with self.lock:
            entry = self.tickets.get(hashlib.sha256(token.encode()).hexdigest())
            if not entry or entry["build"] != build_id or entry["expires"] <= time.monotonic(): raise ApiError("EXPERIENCE_REJECTED", 403)
            if not self.jobs._same_instance(build_id, entry["target"]) or self.jobs.jobs[build_id]["status"] != "RUNNING":
                raise ApiError("INSTANCE_CHANGED", 409)
            return entry

    def call(self, build_id, token, action, body):
        entry = self.current(build_id, token)
        if action == "client-timing":
            if set(body) != {"requestId", "timing"}: raise ApiError("REQUEST_INVALID")
            with self.lock:
                detail = self.jobs.execution_history.get(entry['owner'], body['requestId'])
                if (detail['buildId'] != build_id or detail['instanceId'] != entry['instance']
                        or not entry['session'] or detail['sessionRef'] != hashlib.sha256(entry['session'].encode()).hexdigest()):
                    raise ApiError("EXPERIENCE_REJECTED", 403)
                return self.jobs.execution_history.save_client_timing(entry['owner'], body['requestId'], body['timing'])
        if action == "config":
            from .speech import configuration_status
            return {"buildId": build_id, "instanceId": entry["instance"], "configuration": entry["config"],
                    "capabilities": entry["capabilities"], "artifactLockSha256": entry["lockSha"],
                    "sessionId": entry["session"], "speech": configuration_status(), "modelMode": "SERVER_CONFIGURED",
                    "taskControls": entry["tasks"]}
        with self.lock:
            session, generation = entry["session"], entry["generation"]
            if action == "start":
                if body or session or entry["pending"]: raise ApiError("SESSION_ALREADY_ACTIVE", 409)
                entry["pending"] = True
            elif action in {"end", "reset"}:
                if body: raise ApiError("REQUEST_INVALID")
                entry["session"] = None; entry["generation"] += 1
            else:
                if not session: raise ApiError("SESSION_UNAVAILABLE", 403)
                expected = {"query", "runId"} if action == "chat" else {"runId"} if action == "cancel" else TASK_ACTION_FIELDS.get(action, set())
                if action not in {"chat", "cancel", "snapshot", "status", "activity", *TASK_ACTION_FIELDS} or set(body) != expected:
                    raise ApiError("REQUEST_INVALID")
                if any(not isinstance(v, str) or not v for v in body.values()): raise ApiError("REQUEST_INVALID")
                if action in TASK_ACTION_FIELDS:
                    if not entry["tasks"]: raise ApiError("TASK_NOT_INSTALLED", 409)
                    try: validate_task_body(action, body)
                    except ValueError: raise ApiError("REQUEST_INVALID") from None
        if action in {"end", "reset"} and not session: return {"status": "ENDED", "clearConversation": True}
        route = "/sessions" if action == "start" else f"/sessions/{session}/{'end' if action in {'end','reset'} else action}"
        try:
            code, result = self.jobs._request(build_id, route, body, instance=entry["target"])
        except Exception:
            with self.lock:
                entry["pending"] = False
                if entry["generation"] == generation and entry["session"] == session:
                    entry["session"] = None; entry["generation"] += 1
            if session:
                try: self.jobs._request(build_id, f"/sessions/{session}/end", {}, instance=entry["target"])
                except Exception: pass
            raise ApiError("SERVICE_UNAVAILABLE", 503) from None
        with self.lock:
            self.current(build_id, token)
            if action in {"end", "reset"}: return {"status": "ENDED", "clearConversation": True}
            if entry["generation"] != generation or entry["session"] != session:
                if action == "start": entry["pending"] = False
                if action == "start" and code == 200:
                    self.jobs._request(build_id, f"/sessions/{result['sessionId']}/end", {}, instance=entry["target"])
                raise ApiError("INSTANCE_CHANGED", 409)
            if action == "start":
                entry["pending"] = False
                if code == 200: entry["session"] = result["sessionId"]
            if code != 200:
                if result.get("clearConversation"): entry["session"] = None; entry["generation"] += 1
                allowed = {"AUTHORIZATION_REJECTED", "SESSION_EXPIRED", "SESSION_ENDED", "SESSION_UNAVAILABLE", "RUN_CANCELLED", "MODEL_OR_RUNTIME_FAILED"}
                allowed |= {"STEP_UP_REQUIRED", "CONFIRMATION_EXPIRED_OR_CHANGED", "CONFIRMATION_NOT_AVAILABLE",
                            "CONFIRMATION_BINDING_MISMATCH", "TASK_NOT_AVAILABLE"}
                raise ApiError(result.get("error") if result.get("error") in allowed else "SERVICE_UNAVAILABLE", code)
        if action == "chat":
            # Recheck the current Host authorization before the view crosses the server boundary.
            self.call(build_id, token, "status", {})
            with self.lock:
                self.current(build_id, token)
                if entry["generation"] != generation or entry["session"] != session: raise ApiError("INSTANCE_CHANGED", 409)
            executed_capabilities = []
            for tool in result.get("toolResults", []):
                if not isinstance(tool, dict) or not isinstance(tool.get("data"), dict):
                    continue
                capability = tool["data"].get("capabilityId")
                status = tool["data"].get("resultStatus")
                if isinstance(capability, str) and isinstance(status, str):
                    executed_capabilities.append({"capabilityId": capability, "resultStatus": status})
            return {"text": response_text(result.get("response")), "display": result.get("display"),
                    "sessionId": session, "runId": body["runId"], "status": result.get("status", "COMPLETED"),
                    "executedCapabilities": executed_capabilities,
                    **({"auditTimings": result["auditTimings"]} if isinstance(result.get("auditTimings"), dict) else {}),
                    **({"failureCode": result["failureCode"]} if result.get("failureCode") else {}),
                    **({"executionObservation": result['executionObservation']} if result.get('executionObservation') else {})}
        if action == "start": return {"sessionId": result["sessionId"], "instanceId": entry["instance"]}
        return result

    def audio(self, build_id, token, audio, content_type):
        entry = self.current(build_id, token)
        if entry["config"]["terminal"] != "MOBILE_WEB" or not entry["config"]["voiceInput"]: raise ApiError("ASR_DISABLED", 403)
        with self.lock: generation, session = entry["generation"], entry["session"]
        def authorize():
            self.call(build_id, token, "status", {})
            with self.lock:
                if generation != entry["generation"] or session != entry["session"]: raise ApiError("INSTANCE_CHANGED", 409)
        if not entry["speech"].acquire(blocking=False): raise ApiError("ASR_BUSY", 409)
        try:
            from .speech import transcribe
            return transcribe(audio, content_type, authorize)
        finally: entry["speech"].release()

    def _stream_candidate_agui(self, build_id, entry, body):
        """Open the active candidate's authenticated AG-UI stream, never a browser-controlled endpoint."""
        message_id = "message-" + uuid.uuid4().hex
        envelope = {"threadId": entry["session"], "runId": body["runId"],
                    "messages": [{"id": message_id, "role": "user", "content": body["query"]}],
                    "tools": [], "context": [], "state": {},
                    "forwardedProps": {"instanceId": entry["instance"]}}
        yield from self.jobs._stream_request(build_id, "/sessions/" + entry["session"] + "/ag-ui", envelope,
                                             instance=entry["target"])

    @staticmethod
    def _validated_agui_event(event, session, run_id):
        if not isinstance(event, dict) or event.get("type") not in _AG_UI_TYPES:
            raise ApiError("AG_UI_EVENT_REJECTED", 502)
        if event["type"] in {"RUN_STARTED", "RUN_FINISHED"}:
            if event.get("threadId") != session or event.get("runId") != run_id:
                raise ApiError("AG_UI_EVENT_REJECTED", 502)
        if event["type"] == "CUSTOM" and event.get("name") not in _AG_UI_CUSTOM_NAMES:
            raise ApiError("AG_UI_EVENT_REJECTED", 502)
        return event

    def stream(self, build_id, token, body, send):
        if set(body) != {"query", "runId"}: raise ApiError("REQUEST_INVALID")
        self.call(build_id, token, "status", {})
        entry = self.current(build_id, token); session = entry["session"]; generation = entry["generation"]
        def encode(event): return ("data: " + json.dumps(event, ensure_ascii=False) + "\n\n").encode()
        try:
            for candidate_event in self._stream_candidate_agui(build_id, entry, body):
                with self.lock:
                    self.current(build_id, token)
                    if generation != entry["generation"] or session != entry["session"]:
                        raise ApiError("INSTANCE_CHANGED", 409)
                send(encode(self._validated_agui_event(candidate_event, session, body["runId"])))
        except (BrokenPipeError, ConnectionResetError): raise
        except Exception as error:
            code = error.code if isinstance(error, ApiError) else "SERVICE_UNAVAILABLE"
            send(encode({"type": "RUN_ERROR", "code": code, "message": code}))
