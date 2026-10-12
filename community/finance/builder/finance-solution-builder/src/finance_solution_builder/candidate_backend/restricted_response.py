"""Owner-authorized SPI delivery, not a general body archive or download route.

Budgets are explicit and per Job, separate from model snapshot accounting. Bodies
expire lazily on reserve/read; bounded immutable metadata prevents request replay.
WORKSPACE_PATH is for the authorized immediate server consumer only. Delayed or
multi-owner consumers must use read_response, not a generic filesystem reader.
"""
from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import time
import uuid

from finance_builder_base import ResourceKind

from .models import CandidateResourceRef


BODY_LIMIT = 1024 * 1024
META_LIMIT = 8192
MAX_RECORDS = 256
_ENTRY = re.compile(r"([a-f0-9]{64})\.(reserved|receipt|response)\.json")


class DeliveryRejected(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def owner_ref(owner):
    if not isinstance(owner, str) or not owner:
        raise DeliveryRejected("owner_required")
    return hashlib.sha256(owner.encode()).hexdigest()


def require_grant(owner, *, view=False):
    expected = owner_ref(owner)
    key = "FH_SNAPSHOT_VIEW_OWNERS" if view else "FH_SNAPSHOT_CAPTURE_OWNERS"
    allowed = {part.strip() for part in os.environ.get(key, "").split(",")}
    if os.environ.get("FH_SNAPSHOTS_ENABLED") != "true" or expected not in allowed:
        raise DeliveryRejected("view_denied" if view else "capture_denied")
    return expected


@dataclass(frozen=True)
class Policy:
    files: int
    bytes: int
    retention: int

    @classmethod
    def current(cls):
        values = []
        for suffix in ("MAX_FILES", "MAX_BYTES", "RETENTION_SECONDS"):
            raw = os.environ.get("FH_CANDIDATE_RESPONSE_" + suffix, "")
            if not re.fullmatch(r"[1-9][0-9]{0,9}", raw):
                raise DeliveryRejected("policy_required")
            values.append(int(raw))
        result = cls(*values)
        if result.files < 5 or result.bytes <= 2 * META_LIMIT or result.retention > 31 * 86400:
            raise DeliveryRejected("policy_invalid")
        return result

    @property
    def response_limit(self):
        return min(BODY_LIMIT, self.bytes - 2 * META_LIMIT)


def bounded_json(value, limit):
    result = bytearray()
    for part in json.JSONEncoder(ensure_ascii=True, allow_nan=False, separators=(",", ":")).iterencode(value):
        if len(part) > limit - len(result):
            raise DeliveryRejected("response_too_large")
        result.extend(part.encode("ascii"))
    return bytes(result)


def _open_directory(path):
    path = Path(path).absolute()
    if ".." in path.parts:
        raise DeliveryRejected("path_rejected")
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _regular(descriptor, limit):
    return _regular_info(os.fstat(descriptor), limit)


def _regular_info(info, limit):
    if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != os.geteuid()
            or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > limit):
        raise DeliveryRejected("path_rejected")
    return info


def read_payload(output, ref):
    relative = Path(ref.locator)
    if relative.is_absolute() or not relative.parts or ".." in relative.parts:
        raise DeliveryRejected("path_rejected")
    parent = _open_directory(Path(output) / relative.parent)
    try:
        descriptor = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        with os.fdopen(descriptor, "rb") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > 65536:
                raise DeliveryRejected("payload_rejected")
            raw = stream.read(65537)
        if len(raw) > 65536 or hashlib.sha256(raw).hexdigest() != ref.sha256:
            raise DeliveryRejected("payload_rejected")
        value = json.loads(raw)
        if (not isinstance(value, dict) or set(value) not in ({"query"}, {"query", "runId"})
                or not isinstance(value.get("query"), str)
                or "runId" in value and (not isinstance(value["runId"], str)
                                        or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value["runId"]))):
            raise DeliveryRejected("payload_rejected")
        return value
    finally:
        os.close(parent)


class ResponseStore:
    def __init__(self, output, owner, *, create=True):
        self.output, self.owner = Path(output), owner
        self.root = self.runtime = self.directory = None
        try:
            self.root = _open_directory(self.output)
            for parent, name in ((self.root, "runtime"),):
                if create:
                    try:
                        os.mkdir(name, 0o700, dir_fd=parent)
                    except FileExistsError:
                        pass
                self.runtime = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            if create:
                try:
                    os.mkdir("responses", 0o700, dir_fd=self.runtime)
                except FileExistsError:
                    pass
            self.directory = os.open("responses", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=self.runtime)
            info = os.fstat(self.directory)
            if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise DeliveryRejected("path_rejected")
            self.check_path()
        except BaseException:
            self.close()
            raise

    def close(self):
        for name in ("directory", "runtime", "root"):
            descriptor = getattr(self, name)
            if descriptor is not None:
                os.close(descriptor)
                setattr(self, name, None)

    def check_path(self):
        current = _open_directory(self.output / "runtime/responses")
        try:
            actual, expected = os.fstat(current), os.fstat(self.directory)
            if (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino):
                raise DeliveryRejected("path_changed")
        finally:
            os.close(current)

    @contextmanager
    def locked(self):
        descriptor = os.open(".lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600, dir_fd=self.directory)
        try:
            _regular(descriptor, 0)
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            current = os.stat(".lock", dir_fd=self.directory, follow_symlinks=False)
            held = os.fstat(descriptor)
            if (current.st_dev, current.st_ino) != (held.st_dev, held.st_ino):
                raise DeliveryRejected("path_changed")
            self.check_path()
            yield
        finally:
            os.close(descriptor)

    def _read(self, name, limit):
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=self.directory)
        with os.fdopen(descriptor, "rb") as stream:
            _regular(stream.fileno(), limit)
            data = stream.read(limit + 1)
        if len(data) > limit:
            raise DeliveryRejected("response_too_large")
        return data

    def _publish(self, name, data):
        temporary = ".pending-" + uuid.uuid4().hex
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=self.directory)
        linked = False
        try:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.link(temporary, name, src_dir_fd=self.directory, dst_dir_fd=self.directory, follow_symlinks=False)
            linked = True
            os.unlink(temporary, dir_fd=self.directory)
            os.fsync(self.directory)
            if self._read(name, len(data)) != data:
                raise DeliveryRejected("content_changed")
        except BaseException:
            # Reservations are never removed, even if durability is uncertain.
            if linked and name.endswith(".response.json"):
                os.unlink(name, dir_fd=self.directory)
            raise
        finally:
            try:
                os.unlink(temporary, dir_fd=self.directory)
            except FileNotFoundError:
                pass

    def _records(self):
        names = os.listdir(self.directory)
        if len(names) > 1 + MAX_RECORDS * 4:
            raise DeliveryRejected("quota_exhausted")
        # Every publisher holds the OS lock. A remaining temporary here is from
        # an interrupted publisher, never an active delivery; discard its body.
        pending = [name for name in names if re.fullmatch(r"\.pending-[a-f0-9]{32}", name)]
        for name in pending:
            info = os.stat(name, dir_fd=self.directory, follow_symlinks=False)
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                    or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink not in (1, 2)
                    or info.st_size > BODY_LIMIT):
                raise DeliveryRejected("path_rejected")
            os.unlink(name, dir_fd=self.directory)
        if pending:
            os.fsync(self.directory)
        records, present = {}, set()
        for name in names:
            if name == ".lock" or name in pending:
                continue
            match = _ENTRY.fullmatch(name)
            if match is None:
                raise DeliveryRejected("path_rejected")
            key, kind = match.groups()
            if kind == "response":
                _regular_info(os.stat(name, dir_fd=self.directory, follow_symlinks=False), BODY_LIMIT)
            else:
                raw = self._read(name, META_LIMIT)
            present.add(key)
            if kind == "reserved":
                value = json.loads(raw)
                if (value.get("requestIdSha256") != key or value.get("ownerRef") != owner_ref(self.owner)
                        or value.get("schemaVersion") != "finance-candidate-response-reservation.v0.1"
                        or type(value.get("responseLimit")) is not int or not 0 < value["responseLimit"] <= BODY_LIMIT
                        or type(value.get("expiresAt")) not in (int, float)
                        or not 0 < value["expiresAt"] < 10**12):
                    raise DeliveryRejected("identity_changed")
                records[key] = value
        if present != set(records):
            raise DeliveryRejected("identity_changed")
        return records

    def _expire(self, records):
        now = time.time()
        changed = False
        for key, value in records.items():
            if now >= value["expiresAt"]:
                try:
                    os.unlink(key + ".response.json", dir_fd=self.directory)
                    changed = True
                except FileNotFoundError:
                    pass
        if changed:
            os.fsync(self.directory)

    def reserve(self, request, instance_id, verify_identity):
        owner = require_grant(self.owner)
        policy = Policy.current()
        key = hashlib.sha256(request.request_id.encode()).hexdigest()
        with self.locked():
            verify_identity()
            records = self._records()
            self._expire(records)
            if key in records:
                raise DeliveryRejected("duplicate_request")
            now = time.time()
            # Four physical entries per reservation include atomic publication's
            # temporary file. Metadata capacity remains charged after body expiry.
            reserved_bytes = sum(2 * META_LIMIT + (value["responseLimit"] if now < value["expiresAt"] else 0)
                                 for value in records.values())
            if (len(records) >= MAX_RECORDS or 1 + 4 * (len(records) + 1) > policy.files
                    or reserved_bytes + 2 * META_LIMIT + policy.response_limit > policy.bytes):
                raise DeliveryRejected("quota_exhausted")
            value = {"schemaVersion": "finance-candidate-response-reservation.v0.1", "ownerRef": owner,
                     "jobId": request.handle.candidate_id, "handleDigest": request.handle.sha256(),
                     "instanceId": instance_id, "requestIdSha256": key, "payloadSha256": request.payload_ref.sha256,
                     "responseLimit": policy.response_limit, "createdAt": now, "expiresAt": now + policy.retention,
                     "dispatch": "NOT_CONFIRMED", "automaticRetry": False}
            require_grant(self.owner)
            self._publish(key + ".reserved.json", bounded_json(value, META_LIMIT))
            return Reservation(self, key, value)

    def ref(self, name, limit):
        data = self._read(name, limit)
        return CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="runtime/responses/" + name,
                                    media_type="application/json", sha256=hashlib.sha256(data).hexdigest())

    def discard_body(self, key):
        try:
            os.unlink(key + ".response.json", dir_fd=self.directory)
            os.fsync(self.directory)
        except FileNotFoundError:
            pass


class Reservation:
    def __init__(self, store, key, value):
        self.store, self.key, self.value = store, key, value

    def evidence(self):
        try:
            self.store.check_path()
        except (OSError, ValueError):
            return None
        for suffix in ("receipt", "reserved"):
            try:
                return self.store.ref(self.key + "." + suffix + ".json", META_LIMIT)
            except (OSError, ValueError):
                pass
        return None

    def _current(self, verify_identity):
        require_grant(self.store.owner)
        verify_identity()
        self.store.check_path()
        if time.time() >= self.value["expiresAt"]:
            raise DeliveryRejected("response_expired")

    def _receipt(self, facts, status, *, data=None, code=None):
        value = {**self.value, "schemaVersion": "finance-candidate-invocation-receipt.v0.1",
                 "dispatch": "ATTEMPTED", "delivery": status, "facts": facts,
                 "responseSha256": hashlib.sha256(data).hexdigest() if data is not None else None,
                 "responseBytes": len(data) if data is not None else None, "errorCode": code}
        self.store._publish(self.key + ".receipt.json", bounded_json(value, META_LIMIT))

    def complete(self, response, facts, verify_identity):
        data = bounded_json(response, self.value["responseLimit"])
        name = self.key + ".response.json"
        with self.store.locked():
            self._current(verify_identity)
            self.store._publish(name, data)
            try:
                self._current(verify_identity)
                self._receipt(facts, "CAPTURED", data=data)
                result = self.store.ref(name, self.value["responseLimit"])
                self._current(verify_identity)
                return result
            except BaseException:
                self.store.discard_body(self.key)
                raise

    def failed(self, facts, code):
        try:
            with self.store.locked():
                self.store.discard_body(self.key)
                self._receipt(facts, "FAILED", code=code)
        except (OSError, ValueError):
            pass


def read_response(output, ref, *, owner, request, instance_id, verify_identity):
    """Restricted delayed reader. No generic Eval or HTTP route is enabled here."""
    require_grant(owner, view=True)
    key = hashlib.sha256(request.request_id.encode()).hexdigest()
    if (ref.kind != ResourceKind.WORKSPACE_PATH or ref.locator != "runtime/responses/" + key + ".response.json"
            or ref.media_type != "application/json"):
        raise DeliveryRejected("identity_changed")
    store = ResponseStore(output, owner, create=False)
    try:
        with store.locked():
            verify_identity()
            records = store._records()
            store._expire(records)
            value = records.get(key, {})
            if (value.get("jobId") != request.handle.candidate_id or value.get("handleDigest") != request.handle.sha256()
                    or value.get("instanceId") != instance_id or value.get("payloadSha256") != request.payload_ref.sha256):
                raise DeliveryRejected("identity_changed")
            if time.time() >= value["expiresAt"]:
                raise DeliveryRejected("response_expired")
            receipt = json.loads(store._read(key + ".receipt.json", META_LIMIT))
            data = store._read(key + ".response.json", value["responseLimit"])
            if (receipt.get("delivery") != "CAPTURED" or receipt.get("responseSha256") != ref.sha256
                    or receipt.get("responseBytes") != len(data) or hashlib.sha256(data).hexdigest() != ref.sha256
                    or any(receipt.get(field) != value[field] for field in value if field not in {"schemaVersion", "dispatch"})):
                raise DeliveryRejected("content_changed")
            require_grant(owner, view=True)
            verify_identity()
            store.check_path()
            return json.loads(data)
    finally:
        store.close()
