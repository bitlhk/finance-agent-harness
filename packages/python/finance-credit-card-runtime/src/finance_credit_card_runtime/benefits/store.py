"""单活服务的推荐记录；每次数据库连接确定性关闭。"""

import json
import sqlite3
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

from ..contracts import BusinessError, canonical, digest

TERMINAL = {"complete", "partial", "no-candidates", "failed", "cancelled", "interrupted"}


class RecommendationStore:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.path)) as con, con:
            con.execute("CREATE TABLE IF NOT EXISTS benefit_runs (run_id TEXT PRIMARY KEY, journey TEXT NOT NULL, operation TEXT NOT NULL, fingerprint TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL, result TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL, UNIQUE(journey,operation))")
            con.execute("CREATE TABLE IF NOT EXISTS benefit_calls (run_id TEXT NOT NULL, engine TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(run_id,engine))")

    def recover(self):
        # Called only by the owner after the workbench single-active lock is acquired.
        with closing(sqlite3.connect(self.path)) as con, con:
            con.execute("UPDATE benefit_runs SET status='interrupted' WHERE status IN ('queued','running')")

    def create(self, journey, request, context):
        request_data = request.model_dump(mode="json")
        if not request.marketing_copy:
            request_data.pop("marketing_copy", None)  # 保持旧排序操作的指纹不变。
        if not request.conversation:
            request_data.pop("conversation", None)
        if request.parent_run_id is None:
            request_data.pop("parent_run_id", None)
        payload = {"request": request_data, "context": context.model_dump(mode="json")}
        fingerprint = digest(payload)
        with closing(sqlite3.connect(self.path, timeout=10)) as con, con:
            con.execute("BEGIN IMMEDIATE")
            found = con.execute("SELECT run_id,fingerprint FROM benefit_runs WHERE journey=? AND operation=?", (journey,request.operation_key)).fetchone()
            if found:
                if found[1] != fingerprint:
                    raise BusinessError("operation-input-conflict")
                return found[0], False
            if con.execute("SELECT count(*) FROM benefit_runs WHERE status IN ('queued','running')").fetchone()[0] >= 8:
                raise BusinessError("recommendation-queue-full")
            run_id = "benefit-run-" + uuid.uuid4().hex[:20]
            con.execute("INSERT INTO benefit_runs (run_id,journey,operation,fingerprint,payload,status,created_at) VALUES (?,?,?,?,?,'queued',?)",
                        (run_id,journey,request.operation_key,fingerprint,canonical(payload).decode(),datetime.now(timezone.utc).isoformat()))
        return run_id, True

    def get(self, journey, run_id):
        with closing(sqlite3.connect(self.path)) as con:
            row = con.execute("SELECT payload,status,result,created_at FROM benefit_runs WHERE journey=? AND run_id=?",(journey,run_id)).fetchone()
            if row is None:
                raise BusinessError("not-found")
            calls = [json.loads(x[0]) for x in con.execute("SELECT payload FROM benefit_calls WHERE run_id=? ORDER BY engine", (run_id,))]
        return {"run_id":run_id,"journey_id":journey,"payload":json.loads(row[0]),"status":row[1],
                "result":json.loads(row[2]),"created_at":row[3],"engine_receipts":calls}

    def rows(self, journey):
        with closing(sqlite3.connect(self.path)) as con:
            return [{"run_id":r[0],"status":r[1],"created_at":r[2]} for r in con.execute(
                "SELECT run_id,status,created_at FROM benefit_runs WHERE journey=? ORDER BY created_at DESC LIMIT 200",(journey,))]

    def transition(self, journey, run_id, expected, status, result=None):
        if status not in TERMINAL | {"running"}:
            raise ValueError("unknown recommendation state")
        with closing(sqlite3.connect(self.path)) as con, con:
            cur = con.execute("UPDATE benefit_runs SET status=?,result=? WHERE journey=? AND run_id=? AND status=?",
                (status,canonical(result or {}).decode(),journey,run_id,expected))
            return cur.rowcount == 1

    def audit(self, run_id, result):
        # Audit survives cancellation without allowing late results to replace the terminal state.
        with closing(sqlite3.connect(self.path)) as con, con:
            con.execute("INSERT OR IGNORE INTO benefit_calls VALUES (?,?,?)",
                        (run_id,result.engine,canonical({"engine":result.engine,"calls":[x for x in result.calls],"elapsed_ms":result.elapsed_ms,"status":result.status}).decode()))
            row = con.execute("SELECT result FROM benefit_runs WHERE run_id=? AND status='running'", (run_id,)).fetchone()
            if row is not None:
                checkpoint = json.loads(row[0])
                checkpoint.update(framework="openjiuwen.WorkflowAgent", partial_preview=True)
                checkpoint.setdefault("results", {})[result.engine] = result.model_dump(mode="json")
                con.execute("UPDATE benefit_runs SET result=? WHERE run_id=? AND status='running'",
                            (canonical(checkpoint).decode(), run_id))

    def audit_marketing(self, run_id, value):
        with closing(sqlite3.connect(self.path)) as con, con:
            con.execute("INSERT INTO benefit_calls VALUES (?,?,?) ON CONFLICT(run_id,engine) DO UPDATE SET payload=excluded.payload",
                        (run_id, "marketing", canonical({"engine":"marketing", "stage":"compose-first-item",
                         "status":value.get("status","failed"), "calls":value.get("calls",[])}).decode()))

    def audit_dialogue(self, run_id, value):
        with closing(sqlite3.connect(self.path)) as con, con:
            con.execute("INSERT INTO benefit_calls VALUES (?,?,?) ON CONFLICT(run_id,engine) DO UPDATE SET payload=excluded.payload",
                        (run_id, "dialogue", canonical({"engine":"dialogue", **value}).decode()))

    def cancel(self, journey, run_id):
        # Match both live states in one transaction: a worker can claim queued
        # work while cancellation is in flight.
        with closing(sqlite3.connect(self.path)) as con, con:
            con.execute("UPDATE benefit_runs SET status='cancelled',result='{}' WHERE journey=? AND run_id=? AND status IN ('queued','running')",
                        (journey,run_id))
        return self.get(journey,run_id)
