"""Private, read-only Eval Launcher protocol and bounded instance lifecycle."""
from __future__ import annotations

import base64
import copy
import hashlib
import io
import json
import re
import secrets
import stat
import threading
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from dataclasses import dataclass, field
from datetime import datetime, timezone

from .method_assets import MAX_FILE, MAX_FILES, MAX_TOTAL, MAX_UPLOAD
from .eval_launcher_backend import PrepareError
from .model_generation import GenerationError, validate_generation

# Base64 ZIP plus JSON-escaped files and duplicate instructions, with envelope room.
MAX_REQUEST_BYTES = 4 * ((MAX_UPLOAD + 2) // 3) + 6 * (MAX_TOTAL + MAX_FILE) + 256 * 1024


class LauncherError(Exception):
    def __init__(self, code, status=422):
        super().__init__(code)
        self.code, self.status = code, status


def require(condition, code, status=422):
    if not condition:
        raise LauncherError(code, status)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def identifier(value):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:@-]{0,127}", value) is not None


def exact(value, keys):
    require(isinstance(value, dict) and set(value) == set(keys), "REQUEST_FIELDS_INVALID")


def validate_skill(skill, variant, max_bytes=MAX_UPLOAD, max_files=MAX_FILES):
    """Archive integrity is distinct from Eval's opaque canonical asset identity."""
    require(isinstance(skill, dict) and isinstance(variant, dict), "SKILL_REQUIRED")
    require(set(skill) <= {"skillId", "version", "artifactSha256", "instructions", "files",
                           "canonicalArchive", "archiveBase64", "archiveSha256"}, "SKILL_FIELDS_INVALID")
    require(variant.get("mode") == "skill_version", "SKILL_VERSION_REQUIRED")
    for key in ("skillId", "version", "artifactSha256"):
        require(isinstance(skill.get(key), str) and skill[key] == variant.get(key), "SKILL_VARIANT_MISMATCH")
    require(re.fullmatch(r"[a-f0-9]{64}", skill["artifactSha256"]) is not None, "SKILL_DIGEST_INVALID")
    require(identifier(skill["skillId"]) and identifier(skill["version"]), "SKILL_ID_INVALID")
    encoded = skill.get("archiveBase64")
    require(isinstance(encoded, str) and len(encoded) <= 4 * ((max_bytes + 2) // 3), "ARCHIVE_LIMIT")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError):
        raise LauncherError("ARCHIVE_INVALID") from None
    require(len(raw) <= max_bytes and digest(raw) == skill.get("archiveSha256"), "ARCHIVE_DIGEST_MISMATCH")
    files, total, names = {}, 0, set()
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            require(0 < len(archive.infolist()) <= max_files, "ARCHIVE_FILE_LIMIT")
            for item in archive.infolist():
                name = item.filename.rstrip("/")
                require(name and len(name) <= 360 and not name.startswith("/") and "\\" not in name
                        and all(part not in {"", ".", ".."} for part in name.split("/"))
                        and ":" not in name and not any(ord(c) < 32 for c in name), "ARCHIVE_PATH_INVALID")
                require(name not in names, "ARCHIVE_DUPLICATE_PATH")
                names.add(name)
                require(not stat.S_ISLNK(item.external_attr >> 16) and not (item.flag_bits & 1), "ARCHIVE_ENTRY_UNSUPPORTED")
                if item.is_dir():
                    continue
                total += item.file_size
                require(item.file_size <= MAX_FILE and total <= MAX_TOTAL, "ARCHIVE_EXPANDED_LIMIT")
                require(item.file_size <= max(item.compress_size, 1) * 100, "ARCHIVE_COMPRESSION_LIMIT")
                with archive.open(item) as stream:
                    content = stream.read(MAX_FILE + 1)
                require(len(content) == item.file_size, "ARCHIVE_SIZE_MISMATCH")
                files[name] = content.decode("utf-8")
    except (zipfile.BadZipFile, UnicodeError, RuntimeError, OSError):
        raise LauncherError("ARCHIVE_INVALID") from None
    require("SKILL.md" in files, "ROOT_SKILL_REQUIRED")
    require(not any(name.startswith("scripts/") or name.endswith((".py", ".js", ".sh", ".jar", ".class"))
                    for name in files), "EXECUTABLE_SKILL_UNSUPPORTED")
    require(skill.get("files") is None or skill["files"] == files, "SKILL_FILES_MISMATCH")
    require(isinstance(skill.get("instructions"), str), "SKILL_INSTRUCTIONS_REQUIRED")
    require(skill["instructions"] == files["SKILL.md"], "SKILL_INSTRUCTIONS_MISMATCH")
    return {"archiveSha256": digest(raw), "sourceFiles": files,
            "sourceFileDigests": {name: digest(value.encode()) for name, value in files.items()}}


@dataclass
class Instance:
    id: str
    owner: str
    runtime: str
    request: dict
    configuration_digest: str
    expires: float
    cancel: threading.Event = field(default_factory=threading.Event)
    case_lock: threading.Lock = field(default_factory=threading.Lock)
    status: str = "starting"
    error: str | None = None
    handle: object = None
    deleted: bool = False
    cases: set = field(default_factory=set)
    prepare_future: object = None
    execution_future: object = None
    cleanup_lock: threading.Lock = field(default_factory=threading.Lock)
    cleanup_error: str | None = None


class Launcher:
    """One server-owned preset per runtime; no client-selected execution code."""
    def __init__(self, backend, presets, *, max_instances=2, ttl_seconds=1800,
                 max_timeout_ms=900000, clock=time.time):
        require(0 < max_instances <= 16 and 30 <= ttl_seconds <= 86400, "LAUNCHER_LIMIT_INVALID")
        self.backend, self.presets = backend, copy.deepcopy(presets)
        self.max_instances, self.ttl_seconds = max_instances, ttl_seconds
        self.max_timeout_ms, self.clock = max_timeout_ms, clock
        self.lock = threading.RLock()
        self.pool = ThreadPoolExecutor(max_workers=max_instances, thread_name_prefix="eval-launch")
        self.instances, self.keys = {}, {}
        self.closing = False

    def capabilities(self, runtime):
        require(runtime in self.presets, "RUNTIME_NOT_CONFIGURED", 404)
        preset = self.presets[runtime]
        return {"schemaVersion": "finance.skill-eval.runtime-capabilities.v1",
                "runtime": preset["runtime"], "models": [preset["model"]],
                "defaultModelId": preset["model"]["modelId"],
                "nativeToolNames": sorted(set(preset["toolNames"])),
                "features": {"streaming": False, "asyncLaunch": True, "packagedMcp": False,
                             "externalMcp": False, "inlineSecrets": False},
                "limits": {"maxArchiveBytes": MAX_UPLOAD, "maxFiles": MAX_FILES,
                           "maxTimeoutMs": self.max_timeout_ms, "maxConcurrentInstances": self.max_instances}}

    def create(self, owner, runtime, body, key):
        self.capabilities(runtime)
        require(identifier(owner) and isinstance(key, str) and 1 <= len(key) <= 128, "REQUEST_ID_INVALID")
        preset = self.presets[runtime]
        require(identifier(preset.get("owner")) and preset["owner"] == owner, "RUNTIME_OWNER_MISMATCH", 403)
        self.reap()
        exact(body, {"schemaVersion", "runId", "side", "modelId", "variant", "skill", "environment", "configuration", "secrets", "limits"}
              | ({"generation"} if isinstance(body, dict) and "generation" in body else set()))
        if "generation" in body:
            try:
                body = {**body, "generation": validate_generation(body["generation"])}
            except GenerationError:
                raise LauncherError("EVAL_GENERATION_INVALID") from None
        require(body["schemaVersion"] == "finance.skill-eval.runtime-instance.v1"
                and identifier(body["runId"]) and body["side"] in {"left", "right"}, "REQUEST_ID_INVALID")
        require(body["modelId"] == preset["model"]["modelId"], "MODEL_NOT_CONFIGURED")
        require(body["configuration"] == {} and body["secrets"] == {"mode": "inline", "values": {}}, "CLIENT_CONFIGURATION_UNSUPPORTED")
        env = body["environment"]
        require(isinstance(env, dict) and set(env) <= {"environmentId", "requiredToolNames", "availableToolNames",
                "harnessToolNames", "mcpServers", "commandScripts", "candidateMcpServices"}, "ENVIRONMENT_UNSUPPORTED")
        require(all(not env.get(k) for k in ("harnessToolNames", "mcpServers", "commandScripts", "candidateMcpServices")), "EXECUTABLE_DEPENDENCY_UNSUPPORTED")
        required = env.get("requiredToolNames", [])
        require(isinstance(required, list) and all(isinstance(t, str) for t in required)
                and set(required) <= set(preset["toolNames"]), "TOOL_NOT_INSTALLED")
        exact(body["limits"], {"timeoutMs"})
        timeout = body["limits"]["timeoutMs"]
        require(type(timeout) is int and 1000 <= timeout <= self.max_timeout_ms, "TIMEOUT_INVALID")
        checked = validate_skill(body["skill"], body["variant"])
        require(preset.get("allowDrafts") is True or checked["sourceFileDigests"] == preset.get("sourceFileDigests"), "SKILL_NOT_ADMITTED")
        snapshot = copy.deepcopy(body)
        snapshot["sourceVerification"] = checked
        config_digest = digest(canonical({"request": body, "preset": preset}))
        with self.lock:
            require(not self.closing, "LAUNCHER_CLOSING", 503)
            old = self.keys.get((owner, runtime, key))
            if old:
                instance = self.instances[old]
                require(instance.configuration_digest == config_digest, "IDEMPOTENCY_CONFLICT", 409)
                require(not instance.deleted, "INSTANCE_EXPIRED", 410)
                return self._state(instance)
            require(len(self.instances) < 128, "INSTANCE_RETENTION_LIMIT_REACHED", 429)
            require(sum(not self._released(i) for i in self.instances.values()) < self.max_instances, "INSTANCE_LIMIT_REACHED", 429)
            instance = Instance("eval-" + secrets.token_hex(12), owner, runtime, snapshot, config_digest, self.clock() + self.ttl_seconds)
            self.instances[instance.id] = instance
            self.keys[(owner, runtime, key)] = instance.id
            instance.prepare_future = self.pool.submit(self._prepare, instance, copy.deepcopy(preset))
            return self._state(instance)

    def _prepare(self, instance, preset):
        try:
            if instance.cancel.is_set():
                return
            handle = self.backend.prepare(instance.request, preset, instance.cancel)
            with self.lock:
                instance.handle = handle
                if not instance.cancel.is_set() and not instance.deleted:
                    instance.status = "ready"
        except Exception as error:
            code = str(error)
            if not re.fullmatch(r"EVAL_[A-Z0-9_]{1,80}", code):
                code = "BUILD_OR_ADMISSION_FAILED"
            with self.lock:
                if isinstance(error, PrepareError):
                    instance.handle = error.cleanup_handle
                instance.status, instance.error = "failed", code
        finally:
            if instance.cancel.is_set() or instance.status == "failed":
                try:
                    self._cleanup(instance)
                except LauncherError:
                    pass

    def _owned(self, owner, runtime, ident, *, include_deleted=False):
        instance = self.instances.get(ident)
        require(instance is not None and instance.owner == owner and instance.runtime == runtime
                and (include_deleted or not instance.deleted), "INSTANCE_NOT_FOUND", 404)
        return instance

    def _released(self, instance):
        return ((instance.deleted or instance.status == "failed") and instance.handle is None
                and not instance.cleanup_lock.locked() and not instance.case_lock.locked()
                and all(f is None or f.done() for f in (instance.prepare_future, instance.execution_future)))

    def _cleanup(self, instance):
        # Serialize retries without holding the manager lock across backend operations.
        if not instance.cleanup_lock.acquire(blocking=False):
            return
        try:
            with self.lock:
                handle = instance.handle
            if handle is None:
                return
            try:
                self.backend.close(handle)
                retired = getattr(handle, "retired", None)
                with self.lock:
                    instance.cleanup_error = "INSTANCE_CLEANUP_FAILED" if getattr(handle, "cleanup_error", None) else None
                    if retired is None or retired.is_set():
                        instance.handle = None
                        instance.cleanup_error = None
            except Exception:
                with self.lock:
                    instance.cleanup_error = "INSTANCE_CLEANUP_FAILED"
                raise LauncherError("INSTANCE_CLEANUP_FAILED", 503) from None
        finally:
            instance.cleanup_lock.release()

    def _state(self, instance):
        return {"schemaVersion": "finance.skill-eval.runtime-instance-state.v1", "instanceId": instance.id,
                "status": instance.status, "executeEndpoint": "v1/runtime-instances/" + instance.id + "/execute" if instance.status == "ready" else None,
                "expiresAt": datetime.fromtimestamp(instance.expires, timezone.utc).isoformat().replace("+00:00", "Z"),
                "error": instance.error, "configurationDigest": instance.configuration_digest}

    def get(self, owner, runtime, ident):
        self.reap()
        with self.lock:
            return self._state(self._owned(owner, runtime, ident))

    def execute(self, owner, runtime, ident, body):
        self.capabilities(runtime)
        exact(body, {"schemaVersion", "caseId", "query", "requiredTools"})
        require(body["schemaVersion"] == "finance.skill-eval.runtime-case.v1" and identifier(body["caseId"]), "CASE_INVALID")
        require(isinstance(body["query"], str) and 0 < len(body["query"]) <= 4000, "QUERY_INVALID")
        require(isinstance(body["requiredTools"], list) and all(isinstance(t, str) for t in body["requiredTools"])
                and set(body["requiredTools"]) <= set(self.presets[runtime]["toolNames"]), "TOOL_NOT_INSTALLED")
        self.reap()
        with self.lock:
            instance = self._owned(owner, runtime, ident)
            require(instance.status == "ready", "INSTANCE_NOT_READY", 409)
            timeout = instance.request["limits"]["timeoutMs"]
        deadline = time.monotonic() + timeout / 1000
        require(instance.case_lock.acquire(timeout=timeout / 1000), "CASE_QUEUE_TIMEOUT", 504)
        pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="eval-case")
        try:
            with self.lock:
                instance = self._owned(owner, runtime, ident)
                require(body["caseId"] not in instance.cases, "CASE_ALREADY_EXECUTED", 409)
                instance.cases.add(body["caseId"])
                handle = instance.handle
            remaining_ms = max(1, int((deadline - time.monotonic()) * 1000))
            with self.lock:
                future = pool.submit(self.backend.execute, handle, copy.deepcopy(body), remaining_ms)
                instance.execution_future = future
            try:
                result = future.result(timeout=remaining_ms / 1000)
            except FutureTimeout:
                self.delete(owner, runtime, ident)
                raise LauncherError("CASE_TIMEOUT_INSTANCE_RELEASED", 504) from None
            with self.lock:
                # Only manager DELETE/TTL marks deleted; backend close also sets cancel.
                require(not instance.deleted, "INSTANCE_NOT_FOUND", 404)
                if isinstance(result, dict) and result.get("status") == "failed":
                    code = result.get("error")
                    if not isinstance(code, str) or not re.fullmatch(r"EVAL_[A-Z0-9_]{1,80}", code):
                        code = "CASE_FAILED_INSTANCE_RELEASED"
                    raise LauncherError(code, 502)
                require(not instance.cancel.is_set(), "INSTANCE_NOT_FOUND", 404)
            require(result.get("runtimeIdentityVerified") is True, "RUNTIME_EVIDENCE_UNVERIFIED", 502)
            require(result.get("modelId") == instance.request["modelId"], "MODEL_EVIDENCE_MISMATCH", 502)
            require(result.get("metadata", {}).get("skillArtifactSha256") == instance.request["skill"]["artifactSha256"], "SKILL_EVIDENCE_MISMATCH", 502)
            return {**result, "runId": instance.request["runId"], "side": instance.request["side"], "caseId": body["caseId"],
                    "metadata": {**result["metadata"], "instanceConfigurationDigest": instance.configuration_digest}}
        except Exception as error:
            if isinstance(error, LauncherError) and error.status != 502:
                raise
            with self.lock:
                # A concurrent external deletion wins before we claim failure cleanup.
                require(not instance.deleted, "INSTANCE_NOT_FOUND", 404)
                instance.deleted = True
                instance.cancel.set()
            try:
                self._cleanup(instance)
            except LauncherError:
                pass  # Keep the primary failure; retained handle is retried by reap().
            if isinstance(error, LauncherError):
                raise
            raise LauncherError("CASE_FAILED_INSTANCE_RELEASED", 502) from None
        finally:
            pool.shutdown(wait=False, cancel_futures=True)
            instance.case_lock.release()

    def delete(self, owner, runtime, ident):
        with self.lock:
            instance = self._owned(owner, runtime, ident, include_deleted=True)
            instance.deleted = True
            instance.cancel.set()
            if instance.prepare_future is not None:
                instance.prepare_future.cancel()
        self._cleanup(instance)

    def reap(self):
        with self.lock:
            retired = {i.id for i in self.instances.values() if i.deleted and self.clock() >= i.expires and self._released(i)}
            for ident in retired:
                self.instances.pop(ident)
            self.keys = {key: ident for key, ident in self.keys.items() if ident not in retired}
            expired = [(i.owner, i.runtime, i.id) for i in self.instances.values() if i.deleted or self.clock() >= i.expires]
            failed = [i for i in self.instances.values() if not i.deleted and i.status == "failed" and i.handle is not None]
        for instance in failed:
            try:
                self._cleanup(instance)
            except LauncherError:
                pass
        for args in expired:
            try:
                self.delete(*args)
            except LauncherError:
                pass

    def close(self):
        with self.lock:
            self.closing = True
            owned = [(i.owner, i.runtime, i.id) for i in self.instances.values()]
        for args in owned:
            try:
                self.delete(*args)
            except LauncherError:
                pass
        self.pool.shutdown(wait=True, cancel_futures=True)
        for args in owned:
            try:
                self.delete(*args)
            except LauncherError:
                pass
