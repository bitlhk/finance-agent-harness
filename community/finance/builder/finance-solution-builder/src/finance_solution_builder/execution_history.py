"""Owner-scoped historical metadata reads. Never invokes Host, model, or business tools."""
from __future__ import annotations

import hashlib
import base64
from datetime import datetime, timezone
import hmac
import json
from pathlib import Path
import re
import math
import os
import secrets
import stat

ID = re.compile(r"[a-f0-9-]{36}")
MAX_BYTES = 1_048_576
MAX_MANIFEST_BYTES = 1_048_576
MAX_LABEL_BYTES = 65_536
EVENT_FIELDS = set("schemaVersion requestId processBootId instanceId sequence spanId parentSpanId component operation phase outcome dispatch at elapsedNanos durationNanos links".split())
LINK_FIELDS = set("sdkTraceId toolCallId harnessRequestId harnessTraceId capabilityId providerId route method httpStatus businessStatus responseStatus attempt runtimeLockDigest exceptionType".split())
SAFE_ID = re.compile(r"[A-Za-z0-9_.:/{}-]{1,160}")
SNAPSHOT_PREPARATION_REASONS = ("BODY_QUEUE_REJECTED", "BODY_PREPARATION_FAILED")


def display_view(value):
    """Bounded frozen component text. Integrity is checked by the caller, not inferred here."""
    if not isinstance(value, dict) or set(value) != {'schemaVersion', 'capabilities', 'sources'} or value['schemaVersion'] != 'finance-observation-display.v0.1':
        raise ValueError('DISPLAY_VERSION_INVALID')
    def text(item):
        return isinstance(item, str) and bool(item.strip()) and len(item) <= 500 and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in item)
    def identifier(item):
        return isinstance(item, str) and bool(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}', item))
    capabilities, sources = value['capabilities'], value['sources']
    if not isinstance(capabilities, list) or len(capabilities) > 256 or not isinstance(sources, list) or len(sources) > 64:
        raise ValueError('DISPLAY_LIMIT')
    seen = set()
    for cap in capabilities:
        if (not isinstance(cap, dict) or set(cap) != {'capabilityId', 'packId', 'version', 'title'}
                or not all(identifier(cap[k]) for k in ('capabilityId', 'packId', 'version'))
                or not text(cap['title']) or cap['capabilityId'] in seen): raise ValueError('DISPLAY_CAPABILITY_INVALID')
        seen.add(cap['capabilityId'])
    seen = set()
    for source in sources:
        if (not isinstance(source, dict) or set(source) != {'providerId', 'version', 'operations'}
                or not identifier(source['providerId']) or not identifier(source['version'])
                or source['providerId'] in seen or not isinstance(source['operations'], list)
                or len(source['operations']) > 256): raise ValueError('DISPLAY_SOURCE_INVALID')
        seen.add(source['providerId'])
        routes = set()
        for op in source['operations']:
            if (not isinstance(op, dict) or set(op) != {'operationId', 'method', 'route', 'title', 'description'}
                    or not identifier(op['operationId'])
                    or not isinstance(op['method'], str) or not re.fullmatch(r'[A-Z]{3,10}', op['method'])
                    or not isinstance(op['route'], str) or not re.fullmatch(r'/[A-Za-z0-9_./{}-]{0,199}', op['route'])
                    or not text(op['title']) or not text(op['description'])): raise ValueError('DISPLAY_OPERATION_INVALID')
            key = (op['method'], op['route'])
            if key in routes: raise ValueError('DISPLAY_ROUTE_DUPLICATE')
            routes.add(key)
    return {'state': 'FROZEN', 'capabilities': capabilities, 'sources': sources}


def observation_view(header, complete, events=(), terminal=None, receipts=()):
    """Only bounded declaration metadata; startup readiness never proves model body capture."""
    assessment = header.get("observationAssessment")
    if not isinstance(assessment, dict):
        return {"status": "LEGACY_UNASSESSED", "items": []}
    if assessment.get("schemaVersion") != "finance-observation-assessment.v0.1":
        return {"status": "UNKNOWN", "items": []}
    def token(value):
        return value if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}", value) else "UNKNOWN"
    def tokens(value, *, empty=False):
        return (isinstance(value, list) and len(value) <= 64 and (empty or bool(value))
                and all(isinstance(item, str) and token(item) == item for item in value))
    rows = []
    source = assessment.get("items")
    if not isinstance(source, list) or len(source) > 64:
        return {"status": "UNKNOWN", "items": []}
    for item in source:
        if not isinstance(item, dict): continue
        requirement, descriptor = item.get("requirement"), item.get("descriptor") or {}
        if not isinstance(requirement, dict) or not isinstance(descriptor, dict): continue
        semantic = token(requirement.get("semanticId"))
        fields = requirement.get("fields")
        missing = []
        actual = "NOT_CHECKED"
        if semantic == "execution.history":
            evidence = {
                "requestId": isinstance(header.get("requestId"), str) and bool(ID.fullmatch(header["requestId"])),
                "outcome": isinstance(terminal, dict) and terminal.get("status") in {
                    "COMPLETED", "ERROR", "DENIED", "SKIPPED", "CANCELLED", "INCOMPLETE"},
            }
            if not tokens(fields):
                actual = "UNKNOWN"
            else:
                missing = [token(field) for field in fields if not evidence.get(field, False)]
                actual = "INCOMPLETE" if not complete or missing else "CAPTURED_METADATA"
        elif semantic == "capability.result":
            scope = requirement.get('capabilityIds')
            if not tokens(fields) or not tokens(scope, empty=True):
                actual = 'UNKNOWN'
            else:
                matching = [e for e in events if e.get('component') == 'capability'
                            and e.get('links', {}).get('capabilityId') in scope]
                endings = [e for e in matching if e.get('phase') == 'END']
                for event in endings:
                    evidence = {**event.get('links', {}), 'at': event.get('at')}
                    missing.extend(token(field) for field in fields if not evidence.get(field))
                if not matching: actual = 'NOT_INVOKED'
                elif not endings or missing or not complete: actual = 'INCOMPLETE'
                else: actual = 'CAPTURED_METADATA'
                missing = sorted(set(missing))
        elif semantic == "model.client.snapshot":
            matching = [r for r in receipts if isinstance(r, dict)
                        and r.get("boundary") in {"CLIENT_REQUEST_PREPARED", "PARSED_MODEL_RESPONSE"}
                        and r.get("requestId") == header.get("requestId")
                        and isinstance(r.get("callId"), str) and ID.fullmatch(r["callId"])]
            states = {r.get("state") for r in matching}
            # A receipt does not authenticate the body; the separately authorized body reader does.
            if any(r.get("storage") == "UNAVAILABLE" for r in matching) or "FAILED" in states: actual = "FAILED"
            elif "TRUNCATED" in states: actual = "TRUNCATED"
            elif "FILTERED" in states: actual = "FILTERED"
            elif "CAPTURED" in states: actual = "BODY_NOT_VERIFIED"
            elif matching: actual = "NOT_COLLECTED"
        rows.append({"semanticId": semantic, "obligation": token(requirement.get("obligation")),
                     "boundary": token(requirement.get("boundary")), "collectorId": token(item.get("collectorId")),
                     "collectorVersion": token(item.get("collectorVersion")),
                     "support": token(descriptor.get("support")), "verification": token(descriptor.get("verification")),
                     "sdkSupport": token(descriptor.get("sdkSupport")),
                     "startupReadiness": token(item.get("readiness")),
                     "actualCapture": actual, "missingFields": missing,
                     "bodyVerification": "NOT_CHECKED" if semantic == "model.client.snapshot" else "NOT_APPLICABLE"})
    return {"status": token(assessment.get("status")), "items": rows}


def valid_event(event, header):
    """Reject untrusted or mixed-request records before rendering historical metadata."""
    if set(event) - EVENT_FIELDS or not (EVENT_FIELDS - {"parentSpanId", "durationNanos"}) <= set(event):
        return False
    if any(event.get(k) != header.get(k) for k in ("requestId", "processBootId", "instanceId")):
        return False
    if any(not isinstance(event[k], str) or not SAFE_ID.fullmatch(event[k])
           for k in ("requestId", "processBootId", "instanceId", "spanId", "component", "operation")):
        return False
    if event.get("parentSpanId") is not None and (not isinstance(event["parentSpanId"], str) or not SAFE_ID.fullmatch(event["parentSpanId"])):
        return False
    for key in ("sequence", "elapsedNanos", "durationNanos"):
        value = event.get(key)
        if key == "durationNanos" and value is None: continue
        if type(value) is not int or value < (1 if key == "sequence" else 0): return False
    if event["phase"] not in {"START", "END", "SIGNAL"} or event["dispatch"] not in {"NOT_OBSERVED", "NOT_SENT", "SENT"}:
        return False
    if event["outcome"] not in {"PENDING", "RETURNED", "COMPLETED", "ERROR", "DENIED", "SKIPPED", "INCOMPLETE", "CANCEL_REQUESTED", "CANCELLED"}:
        return False
    links = event["links"]
    return (isinstance(event["at"], str) and len(event["at"]) <= 40
            and isinstance(links, dict) and not set(links) - LINK_FIELDS
            and all(isinstance(v, str) and re.fullmatch(r"[A-Za-z0-9_.:/{}-]{1,200}", v) for v in links.values()))


def snapshot_storage_result(records, request_id, ref):
    """A storage receipt is metadata evidence only, never authentication of a body."""
    if (ref.get("requestId") != request_id or not isinstance(ref.get("digest"), str)
            or not re.fullmatch(r"[a-f0-9]{64}", ref["digest"])
            or not isinstance(ref.get("callId"), str) or not ID.fullmatch(ref["callId"])
            or ref.get("part") not in ("request", "response")
            or not isinstance(ref.get("spanId"), str) or not SAFE_ID.fullmatch(ref["spanId"])):
        return None
    for row in reversed(records):
        if (not isinstance(row, dict) or row.get("recordType") != "model-snapshot-storage"
                or any(row.get(key) != ref.get(key) for key in ("requestId", "callId", "part", "spanId", "digest"))
                or row.get("state") not in ("WRITTEN", "WRITE_FAILED")):
            continue
        result = {"state": row["state"]}
        if row["state"] == "WRITE_FAILED" and row.get("reason") in ("BODY_STORAGE_QUOTA", "BODY_WRITE_FAILED"):
            result["reason"] = row["reason"]
        if ExecutionHistory._history_time(row.get("at")):
            result["at"] = row["at"]
        return result
    return None


class ExecutionHistory:
    def __init__(self, data_dir: Path):
        self.root = data_dir / "execution-history"
        # Cursors are read-only session state; restarting the reader invalidates them.
        self._cursor_key = secrets.token_bytes(32)

    def directory(self, owner):
        path = self.root / hashlib.sha256(owner.encode()).hexdigest()
        if self.root.is_symlink() or path.is_symlink():
            raise PermissionError("HISTORY_UNAVAILABLE")
        return path

    @staticmethod
    def _read(path, max_bytes=MAX_BYTES):
        try:
            metadata = path.lstat()
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > max_bytes:
                raise PermissionError("HISTORY_UNAVAILABLE")
            descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
            with os.fdopen(descriptor, "rb") as stream:
                opened = os.fstat(stream.fileno())
                if (not stat.S_ISREG(opened.st_mode) or opened.st_size > max_bytes
                        or (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino)):
                    raise PermissionError("HISTORY_UNAVAILABLE")
                data = stream.read(max_bytes + 1)
                if len(data) != opened.st_size or os.fstat(stream.fileno()).st_size != opened.st_size:
                    raise PermissionError("HISTORY_UNAVAILABLE")
            if len(data) > max_bytes: raise PermissionError("HISTORY_UNAVAILABLE")
            return data
        except OSError as exc:
            raise PermissionError("HISTORY_UNAVAILABLE") from exc

    def get(self, owner, request_id):
        if not ID.fullmatch(request_id):
            raise ValueError("INVALID_REQUEST_ID")
        directory = self.directory(owner)
        raw = self._read(directory / (request_id + ".jsonl"))
        records = []; corrupt = False
        for line in raw.splitlines():
            try:
                value = json.loads(line)
                if not isinstance(value, dict): raise ValueError()
                records.append(value)
            except (ValueError, UnicodeDecodeError):
                corrupt = True
        if not records or records[0].get("recordType") != "request":
            raise PermissionError("HISTORY_UNAVAILABLE")
        header = records[0]
        if header.get("ownerRef") != hashlib.sha256(owner.encode()).hexdigest() or header.get("requestId") != request_id:
            raise PermissionError("HISTORY_UNAVAILABLE")
        candidates = [r for r in records if r.get("schemaVersion") == "finance-observation.v0.1"]
        events = [r for r in candidates if valid_event(r, header)]
        terminal = next((r for r in reversed(records) if r.get("recordType") == "terminal"), None)
        recovery = None
        recovery_file = directory / (request_id + ".recovery.json")
        if recovery_file.exists(): recovery = json.loads(self._read(recovery_file))
        gaps = []
        if len(events) != len(candidates): gaps.append("INVALID_OR_MISMATCHED_EVENT")
        terminal_index = next((i for i, r in enumerate(records) if r.get("recordType") == "terminal"), None)
        if terminal_index is not None and any(r.get("schemaVersion") for r in records[terminal_index + 1:]):
            gaps.append("LATE_EVENT_AFTER_TERMINAL")
        if corrupt: gaps.append("CORRUPT_OR_PARTIAL_RECORD")
        if not terminal: gaps.append("MISSING_TERMINAL_EVENT")
        if terminal and (terminal.get("droppedEvents", 0) or not terminal.get("complete")):
            gaps.append("OBSERVATION_DEGRADED")
        if [e.get("sequence") for e in events] != list(range(1, len(events) + 1)):
            gaps.append("SEQUENCE_GAP_OR_REORDER")
        opened = {e["spanId"] for e in events if e.get("phase") == "START"}
        closed = {e["spanId"] for e in events if e.get("phase") == "END"}
        if sum(e.get("phase") == "START" for e in events) != len(opened):
            gaps.append("DUPLICATE_SPAN_START")
        if opened - closed: gaps.append("UNCLOSED_SPANS")
        if closed - opened or any(e.get("parentSpanId") and e["parentSpanId"] not in opened for e in events):
            gaps.append("MISSING_SPAN_PARENT_OR_START")
        status = terminal.get("status") if terminal else "INTERRUPTED" if recovery else "UNFINISHED"
        spans = []
        starts = {e["spanId"]: e for e in events if e.get("phase") == "START"}
        # Each span has one parent. Iterative coloring detects cycles in linear time.
        colors = {}; invalid_hierarchy = False
        for identity in starts:
            current = identity; chain = []
            while current in starts and current not in colors:
                colors[current] = 1; chain.append(current)
                current = starts[current].get("parentSpanId")
            if colors.get(current) == 1: invalid_hierarchy = True
            for identity in chain: colors[identity] = 2
        if invalid_hierarchy: gaps.append("INVALID_SPAN_HIERARCHY")
        ends = {}
        for event in events:
            if event.get("phase") == "END": ends.setdefault(event["spanId"], event)
        for start in starts.values():
            end = ends.get(start["spanId"])
            spans.append({"spanId": start["spanId"], "parentSpanId": start.get("parentSpanId"),
                          "component": start["component"], "operation": start["operation"],
                          "processBootId": start["processBootId"],
                          "startNanos": start["elapsedNanos"], "durationNanos": end.get("durationNanos") if end else None,
                          "outcome": end.get("outcome") if end else "INCOMPLETE",
                          "dispatch": end.get("dispatch") if end else start.get("dispatch"),
                          "links": (end or start).get("links", {})})
        versions = {}
        for kind in ("service", "runtime"):
            path = directory / f"{request_id}.{kind}-lock.json"
            if not path.exists():
                if kind == "service" or any(e.get("operation") == "assembly" and e.get("outcome") == "COMPLETED" for e in events):
                    gaps.append("MISSING_" + kind.upper() + "_LOCK")
                continue
            try:
                data = self._read(path, MAX_MANIFEST_BYTES)
                value = json.loads(data)
                if not isinstance(value, dict): raise ValueError('MANIFEST_INVALID')
            except (OSError, ValueError, RecursionError):
                gaps.append(kind.upper() + "_LOCK_UNAVAILABLE")
                continue
            sha = hashlib.sha256(data).hexdigest()
            expected = header.get("serviceLockDigest") if kind == "service" else next(
                (e.get("links", {}).get("runtimeLockDigest") for e in events if e.get("links", {}).get("runtimeLockDigest")), None)
            if expected != sha: gaps.append(kind.upper() + "_LOCK_DIGEST_UNVERIFIED")
            # Keep version evidence bounded and omit paths/configuration outside the installation identity.
            versions[kind] = {"digest": sha, "verified": expected == sha,
                              "sources": value.get("sources"), "runtime": value.get("runtime"),
                              "capabilities": value.get("capabilities"), "installations": value.get("installations")}
        visible = {k: header.get(k) for k in ("requestId", "buildId", "instanceId", "processBootId", "sessionRef",
                   "subjectRef", "createdAt", "executorWaitNanos", "executorWaitCoverage", "modelDispatchCoverage",
                   "capabilities", "agentName", "requestType")}
        model = next((r for r in records if r.get("recordType") == "model-context"), {})
        visible["modelContext"] = {k: model.get(k) for k in ("modelRef", "modelName", "configVersion", "mode", "clientRunRef")}
        timing_path = directory / (request_id + '.client-timing.json')
        visible['clientTiming'] = json.loads(self._read(timing_path)) if timing_path.exists() else None
        root_end = next((e for e in events if e.get("phase") == "END" and e.get("component") == "host" and e.get("operation") == "request"), None)
        display = {'state': 'UNAVAILABLE' if 'observationDisplayVersion' in header else 'LEGACY_FALLBACK',
                   'capabilities': [], 'sources': []}
        label_ref = next((r for r in records if r.get('recordType') == 'observation-labels'), None)
        if label_ref is not None:
            display['state'] = 'UNAVAILABLE'
            try:
                if label_ref.get('requestId') != request_id or label_ref.get('state') != 'RECORDED':
                    raise ValueError('DISPLAY_REFERENCE_INVALID')
                data = self._read(directory / (request_id + '.labels.json'), MAX_LABEL_BYTES)
                if hashlib.sha256(data).hexdigest() != label_ref.get('digest'):
                    raise ValueError('DISPLAY_DIGEST_MISMATCH')
                binding = next((r for r in records if r.get('recordType') == 'installation-display'), {})
                if (binding.get('requestId') != request_id or binding.get('displayDigest') != label_ref['digest']
                        or hashlib.sha256(self._read(directory / (request_id + '.runtime-lock.json'))).hexdigest()
                        != binding.get('installationDigest')):
                    raise ValueError('DISPLAY_INSTALLATION_MISMATCH')
                display = {**display_view(json.loads(data)), 'digest': label_ref['digest']}
            except (ValueError, OSError):
                pass
        business_results = []; seen_results = set()
        for event in events:
            links = event["links"]
            if (event["component"] == "capability" and event["phase"] == "END"
                    and links.get("capabilityId") and links.get("businessStatus")):
                key = (links["capabilityId"], links["businessStatus"])
                if key not in seen_results:
                    seen_results.add(key)
                    business_results.append(dict(zip(("capabilityId", "businessStatus"), key)))
        return {**visible, "status": status, "complete": not gaps, "gaps": gaps,
                "businessResults": business_results,
                "display": display,
                "observation": observation_view(header, not gaps, events, terminal,
                    [r for r in records if r.get("recordType") == "model-snapshot"]),
                "durationNanos": root_end.get("durationNanos") if root_end else None,
                "recoveredAt": recovery.get("recoveredAt") if recovery else None,
                "spans": spans, "events": events, "versions": versions,
                "snapshots": [{**{k: r.get(k) for k in ("callId", "spanId", "part", "boundary", "storage", "filter", "dispatch")},
                               "storageReason": r.get("storageReason") if r.get("storage") == "UNAVAILABLE"
                                   and r.get("storageReason") in SNAPSHOT_PREPARATION_REASONS else None,
                               "storageResult": snapshot_storage_result(records, request_id, r),
                               "state": "BODY_NOT_VERIFIED" if r.get("state") == "CAPTURED" else r.get("state")}
                              for r in records if r.get("recordType") == "model-snapshot"]}

    def snapshot(self, owner, request_id, call_id, part):
        owner_ref = hashlib.sha256(owner.encode()).hexdigest()
        if owner_ref not in os.environ.get("FH_SNAPSHOT_VIEW_OWNERS", "").split(","):
            raise PermissionError("SNAPSHOT_VIEW_NOT_GRANTED")
        if not ID.fullmatch(call_id) or part not in {"request", "response"}:
            raise ValueError("INVALID_SNAPSHOT_REFERENCE")
        self.get(owner, request_id)
        directory = self.directory(owner)
        records = []
        for line in self._read(directory / (request_id + ".jsonl")).splitlines():
            try: records.append(json.loads(line))
            except (ValueError, UnicodeDecodeError): continue
        ref = next((r for r in reversed(records) if isinstance(r, dict)
                    and r.get("recordType") == "model-snapshot" and r.get("callId") == call_id
                    and r.get("part") == part and r.get("requestId") == request_id), None)
        if ref is None:
            return {"state": "NOT_CAPTURED", "reason": "NO_SNAPSHOT_REFERENCE",
                    "gapReason": "NO_REFERENCE", "evidence": "NO_MATCHING_REFERENCE"}
        folder = directory / "model-snapshots"
        if folder.is_symlink(): raise PermissionError("SNAPSHOT_UNAVAILABLE")
        storage_result = snapshot_storage_result(records, request_id, ref)
        try: raw = self._snapshot_read(folder / f"{request_id}.{call_id}.{part}.json")
        except FileNotFoundError:
            try:
                marker = json.loads(self._snapshot_read(folder / f"{request_id}.{call_id}.{part}.json.expired"))
                digest = ref.get('digest')
                if (isinstance(marker, dict) and isinstance(digest, str) and re.fullmatch(r'[a-f0-9]{64}', digest)
                        and marker.get('digest') == digest and marker.get('state') == 'EXPIRED'):
                    return {'state': 'EXPIRED', 'reason': 'BODY_RETENTION_EXPIRED',
                            'expiredAt': marker.get('expiredAt'), 'gapReason': 'EXPIRED',
                            'evidence': 'DIGEST_BOUND_EXPIRY_MARKER'}
            except (FileNotFoundError, ValueError, UnicodeDecodeError):
                pass
            if storage_result is not None:
                if storage_result["state"] == "WRITE_FAILED":
                    return {"state": "NOT_CAPTURED", "reason": storage_result.get("reason", "BODY_WRITE_FAILED"),
                            "gapReason": "WRITE_FAILED", "evidence": "DIGEST_BOUND_STORAGE_RECEIPT",
                            "storageResult": storage_result}
                return {"state": "NOT_CAPTURED", "reason": "FILE_MISSING", "gapReason": "MISSING",
                        "evidence": "WRITTEN_RECEIPT_BODY_NOT_FOUND", "storageResult": storage_result}
            # UNAVAILABLE proves synchronous storage preparation failed, not its root cause.
            if ref.get("storage") == "UNAVAILABLE":
                reason = ref.get("storageReason")
                return {"state": "NOT_CAPTURED", "reason": reason if reason in SNAPSHOT_PREPARATION_REASONS
                            else "BODY_UNAVAILABLE_OR_NOT_FLUSHED",
                        "gapReason": "STORAGE_UNAVAILABLE", "evidence": "REFERENCE_STORAGE_UNAVAILABLE"}
            return {"state": "NOT_CAPTURED", "reason": "BODY_UNAVAILABLE_OR_NOT_FLUSHED",
                    "gapReason": "UNAVAILABLE", "evidence": "BODY_FILE_NOT_FOUND"}
        if hashlib.sha256(raw).hexdigest() != ref.get("digest"):
            return {"state": "NOT_CAPTURED", "reason": "BODY_DIGEST_MISMATCH",
                    "gapReason": "DIGEST_MISMATCH", "evidence": "BODY_DIGEST_CHECK_FAILED"}
        try:
            body = json.loads(raw)
            if not isinstance(body, dict): raise ValueError()
        except (ValueError, UnicodeDecodeError):
            return {"state": "NOT_CAPTURED", "reason": "BODY_INVALID",
                    "gapReason": "INVALID", "evidence": "BODY_JSON_INVALID"}
        if any(body.get(k) != v for k, v in {"requestId": request_id, "callId": call_id,
                                            "part": part, "ownerRef": owner_ref, "spanId": ref.get("spanId")}.items()):
            raise PermissionError("SNAPSHOT_REFERENCE_MISMATCH")
        result = {k: body.get(k) for k in ("requestId", "callId", "spanId", "part", "boundary", "state", "filter", "dispatch", "at", "body", "changes", "changesOmitted", "storagePolicy")}
        result["storageResult"] = storage_result
        result.update({k: body[k] for k in ("captureRuntime", "nativeCallId", "pythonProjectionNanos", "clockScope") if k in body})
        return result

    @staticmethod
    def _snapshot_read(path):
        """Keep absent files distinct from access denial and unsafe file references."""
        try:
            metadata = path.lstat()
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > MAX_BYTES:
                raise PermissionError("SNAPSHOT_UNAVAILABLE")
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(descriptor, "rb") as stream:
                if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                    raise PermissionError("SNAPSHOT_UNAVAILABLE")
                data = stream.read(MAX_BYTES + 1)
                if len(data) > MAX_BYTES: raise PermissionError("SNAPSHOT_UNAVAILABLE")
                return data
        except FileNotFoundError:
            raise
        except OSError:
            raise PermissionError("SNAPSHOT_UNAVAILABLE") from None

    def save_client_timing(self, owner, request_id, value):
        self.get(owner, request_id)
        fields = {'firstStatusMs', 'bodyDisplayedMs', 'completedMs'}
        if not isinstance(value, dict) or set(value) != fields:
            raise ValueError('INVALID_CLIENT_TIMING')
        if any(v is not None and (type(v) not in (float, int) or not math.isfinite(v) or not 0 <= v <= 3_600_000)
               for v in value.values()): raise ValueError('INVALID_CLIENT_TIMING')
        if value['completedMs'] is None or any(v is not None and v > value['completedMs'] for v in value.values()):
            raise ValueError('INVALID_CLIENT_TIMING')
        record = {**value, 'source': 'BROWSER_REPORTED', 'clock': 'PERFORMANCE_MONOTONIC', 'streaming': 'BUFFERED_NOT_TOKEN_STREAM'}
        path = self.directory(owner) / (request_id + '.client-timing.json')
        try:
            with path.open('x') as output: json.dump(record, output)
        except FileExistsError: raise ValueError('CLIENT_TIMING_ALREADY_RECORDED') from None
        return {'status': 'RECORDED'}

    @staticmethod
    def _history_time(value):
        if not isinstance(value, str) or len(value) > 40:
            return ""
        try:
            timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if timestamp.tzinfo is None: return ""
            return timestamp.astimezone(timezone.utc).isoformat(timespec="microseconds")
        except (ValueError, OverflowError):
            return ""

    def _listing_metadata(self, owner, path):
        """Read filter/order fields only, without opening locks, bodies or display assets."""
        if not ID.fullmatch(path.stem): raise ValueError("INVALID_REQUEST_ID")
        records = self._read(path).splitlines()
        if not records: raise ValueError("HISTORY_UNAVAILABLE")
        header = json.loads(records[0])
        if (not isinstance(header, dict) or header.get("recordType") != "request"
                or header.get("requestId") != path.stem
                or header.get("ownerRef") != hashlib.sha256(owner.encode()).hexdigest()):
            raise PermissionError("HISTORY_UNAVAILABLE")
        terminal = None
        for line in reversed(records[1:]):
            try: value = json.loads(line)
            except (ValueError, UnicodeDecodeError): continue
            if isinstance(value, dict) and value.get("recordType") == "terminal":
                terminal = value
                break
        recovery = path.with_suffix(".recovery.json")
        recovered = json.loads(self._read(recovery)) if recovery.exists() else None
        return {**{key: header.get(key) for key in ("createdAt", "requestId", "buildId", "subjectRef", "sessionRef",
                                                         "agentName", "requestType")},
                "status": terminal.get("status") if terminal else "INTERRUPTED" if recovered else "UNFINISHED"}

    @classmethod
    def _matches(cls, item, filters):
        timestamp = cls._history_time(item.get("createdAt"))
        for key, value in filters.items():
            if key == "date" and timestamp[:10] != value:
                return False
            if key == "createdFrom" and timestamp < value:
                return False
            if key == "createdBefore" and timestamp >= value:
                return False
            if key not in {"date", "createdFrom", "createdBefore"} and item.get(key) != value:
                return False
        return True

    def _cursor(self, binding, after, watermark):
        raw = json.dumps({"v": 1, "binding": binding, "after": after, "watermark": watermark},
                         sort_keys=True, separators=(",", ":")).encode()
        signature = hmac.digest(self._cursor_key, raw, "sha256")
        return base64.urlsafe_b64encode(raw + signature).rstrip(b"=").decode()

    def _cursor_after(self, token, binding):
        try:
            if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,1024}", token):
                raise ValueError()
            encoded = token.encode()
            data = base64.b64decode(encoded + b"=" * (-len(encoded) % 4), altchars=b"-_", validate=True)
            raw, signature = data[:-32], data[-32:]
            if not hmac.compare_digest(signature, hmac.digest(self._cursor_key, raw, "sha256")):
                raise ValueError()
            value = json.loads(raw)
            if (not isinstance(value, dict) or set(value) != {"v", "binding", "after", "watermark"}
                    or value["v"] != 1 or value["binding"] != binding):
                raise ValueError()
            for key in ("after", "watermark"):
                if (not isinstance(value[key], list) or len(value[key]) != 2
                        or not all(isinstance(v, str) for v in value[key]) or not ID.fullmatch(value[key][1])):
                    raise ValueError()
            after, watermark = tuple(value["after"]), tuple(value["watermark"])
            if after > watermark: raise ValueError()
            return after, watermark
        except (ValueError, TypeError, KeyError, UnicodeDecodeError):
            raise ValueError("INVALID_HISTORY_CURSOR") from None

    def list(self, owner, filters=None):
        """Opt-in limit/cursor pagination; legacy callers still receive at most 100.

        Ordering is UTC createdAt (microsecond precision), then requestId, descending.
        Cursors bind owner and nonempty filters, not a frozen dataset. Every call scans
        all metadata; concurrent backdated inserts/updates may affect subsequent pages.
        Detail validation is limited to this page and the first readable lookahead.
        A new ExecutionHistory instance invalidates old cursors: INVALID_HISTORY_CURSOR
        is also used for tampering/binding mismatch; no unsigned token proves expiry.
        No local-time date inference, body reads or business calls occur in listing.
        """
        if filters is None: filters = {}
        if not isinstance(filters, dict): raise ValueError("INVALID_HISTORY_FILTER")
        filters = dict(filters)
        paginated = "limit" in filters or "cursor" in filters
        limit_value, token = filters.pop("limit", ""), filters.pop("cursor", "")
        if not isinstance(limit_value, str) or (limit_value and not re.fullmatch(r"[1-9][0-9]{0,2}", limit_value)):
            raise ValueError("INVALID_HISTORY_LIMIT")
        limit = int(limit_value) if limit_value else 20 if paginated else 100
        if limit > 100: raise ValueError("INVALID_HISTORY_LIMIT")
        if not isinstance(token, str): raise ValueError("INVALID_HISTORY_CURSOR")
        allowed = {"date", "createdFrom", "createdBefore", "buildId", "subjectRef", "sessionRef", "requestId", "status"}
        if set(filters) - allowed or any(not isinstance(v, str) or len(v) > 160 for v in filters.values()):
            raise ValueError("INVALID_HISTORY_FILTER")
        filters = {key: value for key, value in filters.items() if value}
        for key in ("createdFrom", "createdBefore"):
            if key in filters:
                normalized = self._history_time(filters[key])
                if not normalized: raise ValueError("INVALID_HISTORY_TIME_RANGE")
                filters[key] = normalized
        if ("createdFrom" in filters) != ("createdBefore" in filters):
            raise ValueError("INVALID_HISTORY_TIME_RANGE")
        if filters.get("createdFrom", "") >= filters.get("createdBefore", "~"):
            raise ValueError("INVALID_HISTORY_TIME_RANGE")
        binding = hashlib.sha256(json.dumps([owner, filters], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        after, watermark = self._cursor_after(token, binding) if token else (None, None)
        directory = self.directory(owner)
        candidates = []; unreadable = 0; scanned = 0; invalid_times = 0
        try:
            paths = [path for path in directory.iterdir() if path.name.endswith(".jsonl")]
        except FileNotFoundError:
            paths = []
        except OSError:
            raise PermissionError("HISTORY_UNAVAILABLE") from None
        for path in paths:
            scanned += 1
            try: item = self._listing_metadata(owner, path)
            except (OSError, ValueError, PermissionError, KeyError, TypeError):
                unreadable += 1; continue
            timestamp = self._history_time(item.get("createdAt"))
            if not timestamp: invalid_times += 1
            key = (timestamp, path.stem)
            if ((after is None or key < after) and (watermark is None or key <= watermark)
                    and self._matches(item, filters)): candidates.append(key)
        if watermark is None and candidates: watermark = max(candidates)
        result = []; keys = []; inspected = 0; changed = 0
        for key in sorted(candidates, reverse=True):
            inspected += 1
            try: item = self.get(owner, key[1])
            except (OSError, ValueError, PermissionError, KeyError, TypeError):
                unreadable += 1; continue
            # Active files can change between the index scan and detail read.
            if (self._history_time(item.get("createdAt")), item.get("requestId")) != key or not self._matches(item, filters):
                changed += 1; continue
            result.append({k: v for k, v in item.items() if k not in {"spans", "events", "versions"}})
            keys.append(key)
            if len(result) > limit: break
        more = len(result) > limit
        return {"records": result[:limit], "unreadable": unreadable, "truncated": more,
                "nextCursor": self._cursor(binding, keys[limit - 1], watermark) if paginated and more else None,
                "hasMore": more, "scanned": scanned,
                "snapshotWatermark": {"createdAt": watermark[0], "requestId": watermark[1]} if watermark else None,
                "coverage": {"scope": "OWNER_DIRECTORY", "status": "PARTIAL" if unreadable or changed or invalid_times else "COMPLETE",
                             "dateBasis": "UTC", "rangeSemantics": "HALF_OPEN", "consistency": "LIVE_KEYSET", "scanLimit": None,
                             "cursorLifetime": "EXECUTION_HISTORY_INSTANCE",
                             "backdatedInserts": "MAY_APPEAR", "insertsAboveWatermark": "EXCLUDED",
                             "detailInspected": inspected, "detailScope": "PAGE_AND_LOOKAHEAD",
                             "unreadableScope": "METADATA_AND_INSPECTED_DETAILS",
                             "changedDuringRead": changed, "invalidTimestamps": invalid_times}}
