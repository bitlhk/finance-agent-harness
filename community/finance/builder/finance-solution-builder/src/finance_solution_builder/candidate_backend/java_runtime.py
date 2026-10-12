"""Managed process lifecycle for an OpenJiuwen Java Candidate."""

from __future__ import annotations

import hashlib
import json
import os
import re
import socket
import subprocess
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from finance_builder_base import ErrorDetail, ResourceKind

from finance_solution_builder.candidate_backend.models import (
    BackendIdentity,
    CandidateDescriptor,
    CandidateResourceRef,
    EvidenceRequest,
    EvidenceResult,
    HealthRequest,
    HealthResult,
    InvocationRequest,
    InvocationResult,
    LifecycleOperation,
    LifecycleResult,
    LifecycleStatus,
    RunHandle,
    RunState,
    RuntimeEndpoint,
    StartRequest,
    StartResult,
    StopRequest,
    StopResult,
    verify_candidate_artifact,
    verify_run_handle,
)


_MAX_RESPONSE_BYTES = 1024 * 1024


@dataclass
class _ManagedInstance:
    descriptor: CandidateDescriptor
    handle: RunHandle
    process: subprocess.Popen[bytes]
    events: list[dict[str, Any]] = field(default_factory=list)


class JavaManagedRuntime:
    """Own only the Java processes started through this runtime instance."""

    def __init__(
        self,
        *,
        backend: BackendIdentity,
        delivery_root: Path,
        java: str = "java",
        health_timeout_seconds: float = 60.0,
        request_timeout_seconds: float = 120.0,
        environment: dict[str, str] | None = None,
    ) -> None:
        self.backend = backend
        self.delivery_root = delivery_root.resolve()
        self.java = java
        self.health_timeout_seconds = health_timeout_seconds
        self.request_timeout_seconds = request_timeout_seconds
        self.environment = dict(environment or {})
        self._instances: dict[str, _ManagedInstance] = {}
        self._stopped_handles: set[str] = set()

    def start(self, request: StartRequest) -> StartResult:
        descriptor = request.descriptor
        started = time.perf_counter()
        if descriptor.backend != self.backend:
            return StartResult(
                outcome=self._failed(
                    LifecycleOperation.START,
                    descriptor.task_id,
                    descriptor.candidate_id,
                    started,
                    "candidate.runtime.backend_mismatch",
                    "Candidate descriptor belongs to another Backend.",
                )
            )
        try:
            actual_verification = verify_candidate_artifact(
                descriptor, self.delivery_root
            )
            if actual_verification != request.artifact_verification:
                raise ValueError("candidate.artifact.verification_mismatch")
            artifact = self._resolve_ref(descriptor.artifact.resource)
            candidate_root = self._candidate_root(artifact)
            port = self._available_port()
            base_uri = f"http://127.0.0.1:{port}"
            env = os.environ.copy()
            env.update(self.environment)
            env.update(
                {
                    "PORT": str(port),
                    "SERVER_PORT": str(port),
                    "THREE_ENGINE_SELF_AGENT_ID": "agent.wealth_aggregate",
                    "OPENJIUWEN_SERVICE_AGENT_ID": "agent.wealth_aggregate",
                    "THREE_ENGINE_AGENTS_ROOT": str(candidate_root / "agents"),
                    "THREE_ENGINE_MODEL_MODE": "stub",
                    "THREE_ENGINE_SUPPLY_ENABLED": "false",
                    "THREE_ENGINE_GATEWAY_REGISTER": "false",
                }
            )
            process = subprocess.Popen(
                [self.java, "-jar", str(artifact)],
                cwd=candidate_root,
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            handle = RunHandle(
                task_id=descriptor.task_id,
                candidate_id=descriptor.candidate_id,
                backend=descriptor.backend,
                descriptor_digest=descriptor.sha256(),
                lock_digest=descriptor.lock_digest,
                state=RunState.RUNNING,
                endpoints=(
                    RuntimeEndpoint(
                        name="agent",
                        protocol=descriptor.invocation.protocol,
                        uri=base_uri + descriptor.invocation.path,
                    ),
                    RuntimeEndpoint(
                        name="health",
                        protocol=descriptor.health.protocol,
                        uri=base_uri + descriptor.health.path,
                    ),
                ),
            )
            key = self._handle_key(handle)
            instance = _ManagedInstance(
                descriptor=descriptor,
                handle=handle,
                process=process,
            )
            instance.events.append(self._event("start", "passed"))
            self._instances[key] = instance
            return StartResult(
                outcome=self._passed(
                    LifecycleOperation.START,
                    descriptor.task_id,
                    descriptor.candidate_id,
                    started,
                    "Managed Java Candidate started.",
                ),
                handle=handle,
            )
        except (OSError, ValueError):
            return StartResult(
                outcome=self._failed(
                    LifecycleOperation.START,
                    descriptor.task_id,
                    descriptor.candidate_id,
                    started,
                    "candidate.runtime.start_failed",
                    "Managed Java Candidate could not be started.",
                )
            )

    def health(self, request: HealthRequest) -> HealthResult:
        started = time.perf_counter()
        instance = self._known_instance(request.handle)
        if instance is None:
            return HealthResult(
                outcome=self._failed(
                    LifecycleOperation.HEALTH,
                    request.handle.task_id,
                    request.handle.candidate_id,
                    started,
                    "candidate.runtime.unknown_handle",
                    "Run handle is not managed by this Backend instance.",
                ),
                ready=False,
            )
        endpoint = self._endpoint(request.handle, "health")
        deadline = time.monotonic() + self.health_timeout_seconds
        while time.monotonic() < deadline:
            if instance.process.poll() is not None:
                self._stop_instance(instance)
                instance.events.append(self._event("health", "failed"))
                return HealthResult(
                    outcome=self._failed(
                        LifecycleOperation.HEALTH,
                        request.handle.task_id,
                        request.handle.candidate_id,
                        started,
                        "candidate.runtime.exited_before_ready",
                        "Managed Java Candidate stopped before becoming ready.",
                    ),
                    ready=False,
                )
            try:
                with urllib.request.urlopen(endpoint.uri, timeout=1.0) as response:
                    body = json.loads(response.read(_MAX_RESPONSE_BYTES).decode("utf-8"))
                    status = str(body.get("status", "")).lower()
                    if response.status == 200 and status in {"up", "ok", "ready"}:
                        instance.events.append(self._event("health", "passed"))
                        return HealthResult(
                            outcome=self._passed(
                                LifecycleOperation.HEALTH,
                                request.handle.task_id,
                                request.handle.candidate_id,
                                started,
                                "Managed Java Candidate is ready.",
                            ),
                            ready=True,
                        )
            except (OSError, ValueError):
                time.sleep(0.2)
        self._stop_instance(instance)
        instance.events.append(self._event("health", "failed"))
        return HealthResult(
            outcome=self._failed(
                LifecycleOperation.HEALTH,
                request.handle.task_id,
                request.handle.candidate_id,
                started,
                "candidate.runtime.health_timeout",
                "Managed Java Candidate did not become ready before the deadline.",
            ),
            ready=False,
        )

    def invoke(self, request: InvocationRequest) -> InvocationResult:
        started = time.perf_counter()
        instance = self._known_instance(request.handle)
        if instance is None:
            return InvocationResult(
                outcome=self._failed(
                    LifecycleOperation.INVOKE,
                    request.handle.task_id,
                    request.handle.candidate_id,
                    started,
                    "candidate.runtime.unknown_handle",
                    "Run handle is not managed by this Backend instance.",
                ),
                request_id=request.request_id,
            )
        if request.protocol != instance.descriptor.invocation.protocol:
            self._stop_instance(instance)
            return InvocationResult(
                outcome=self._failed(
                    LifecycleOperation.INVOKE,
                    request.handle.task_id,
                    request.handle.candidate_id,
                    started,
                    "candidate.runtime.protocol_mismatch",
                    "Invocation protocol does not match the Candidate descriptor.",
                ),
                request_id=request.request_id,
            )
        try:
            payload_path = self._resolve_ref(request.payload_ref)
            self._verify_ref(payload_path, request.payload_ref)
            payload = payload_path.read_bytes()
            endpoint = self._endpoint(request.handle, "agent")
            http_request = urllib.request.Request(
                endpoint.uri,
                data=payload,
                headers={
                    "Content-Type": "application/json",
                    "Accept": "application/json, text/event-stream",
                },
                method="POST",
            )
            with urllib.request.urlopen(
                http_request, timeout=self.request_timeout_seconds
            ) as response:
                response_body = response.read(_MAX_RESPONSE_BYTES + 1)
                if not 200 <= response.status < 300 or len(response_body) > _MAX_RESPONSE_BYTES:
                    raise ValueError("invalid controlled invocation response")
                media_type = response.headers.get_content_type()
            response_dir = self.delivery_root / "runtime" / "responses"
            response_dir.mkdir(parents=True, exist_ok=True)
            safe_request_id = re.sub(r"[^A-Za-z0-9_.-]", "-", request.request_id)
            response_path = response_dir / f"{safe_request_id}.response"
            response_path.write_bytes(response_body)
            response_ref = self._resource_ref(response_path, media_type=media_type)
            instance.events.append(
                self._event(
                    "invoke",
                    "passed",
                    requestId=request.request_id,
                    responseSha256=response_ref.sha256,
                )
            )
            return InvocationResult(
                outcome=self._passed(
                    LifecycleOperation.INVOKE,
                    request.handle.task_id,
                    request.handle.candidate_id,
                    started,
                    "Controlled Candidate invocation completed.",
                    evidence_refs=(response_ref,),
                ),
                request_id=request.request_id,
                response_ref=response_ref,
            )
        except (OSError, ValueError):
            self._stop_instance(instance)
            instance.events.append(self._event("invoke", "failed"))
            return InvocationResult(
                outcome=self._failed(
                    LifecycleOperation.INVOKE,
                    request.handle.task_id,
                    request.handle.candidate_id,
                    started,
                    "candidate.runtime.invoke_failed",
                    "Controlled Candidate invocation failed.",
                ),
                request_id=request.request_id,
            )

    def collect_evidence(self, request: EvidenceRequest) -> EvidenceResult:
        started = time.perf_counter()
        instance = self._known_instance(request.handle)
        if instance is None:
            return EvidenceResult(
                outcome=self._failed(
                    LifecycleOperation.COLLECT_EVIDENCE,
                    request.handle.task_id,
                    request.handle.candidate_id,
                    started,
                    "candidate.runtime.unknown_handle",
                    "Run handle is not managed by this Backend instance.",
                )
            )
        evidence_dir = self.delivery_root / "runtime" / "evidence"
        evidence_dir.mkdir(parents=True, exist_ok=True)
        evidence_path = evidence_dir / f"{request.handle.candidate_id}-lifecycle.json"
        document = {
            "schemaVersion": "openjiuwen-java-lifecycle-evidence.v1",
            "taskId": request.handle.task_id,
            "candidateId": request.handle.candidate_id,
            "descriptorDigest": request.handle.descriptor_digest,
            "events": instance.events,
        }
        evidence_path.write_text(
            json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        evidence_ref = self._resource_ref(evidence_path, media_type="application/json")
        return EvidenceResult(
            outcome=self._passed(
                LifecycleOperation.COLLECT_EVIDENCE,
                request.handle.task_id,
                request.handle.candidate_id,
                started,
                "Public lifecycle evidence collected.",
                evidence_refs=(evidence_ref,),
            )
        )

    def stop(self, request: StopRequest) -> StopResult:
        started = time.perf_counter()
        key = self._handle_key(request.handle)
        instance = self._instances.pop(key, None)
        if instance is None:
            if key in self._stopped_handles:
                return StopResult(
                    outcome=self._passed(
                        LifecycleOperation.STOP,
                        request.handle.task_id,
                        request.handle.candidate_id,
                        started,
                        "Managed Java Candidate was already stopped.",
                    ),
                    stopped=True,
                )
            return StopResult(
                outcome=self._failed(
                    LifecycleOperation.STOP,
                    request.handle.task_id,
                    request.handle.candidate_id,
                    started,
                    "candidate.runtime.unknown_handle",
                    "Run handle is not managed by this Backend instance.",
                ),
                stopped=False,
            )
        self._stop_instance(instance)
        instance.events.append(self._event("stop", "passed"))
        self._stopped_handles.add(key)
        return StopResult(
            outcome=self._passed(
                LifecycleOperation.STOP,
                request.handle.task_id,
                request.handle.candidate_id,
                started,
                "Managed Java Candidate stopped.",
            ),
            stopped=True,
        )

    def _known_instance(self, handle: RunHandle) -> _ManagedInstance | None:
        instance = self._instances.get(self._handle_key(handle))
        if instance is None:
            return None
        try:
            verify_run_handle(instance.descriptor, handle)
        except ValueError:
            return None
        return instance

    def _resolve_ref(self, resource: CandidateResourceRef) -> Path:
        if resource.kind is not ResourceKind.WORKSPACE_PATH:
            raise ValueError("only workspace resources are supported")
        path = (self.delivery_root / resource.locator).resolve()
        if not path.is_relative_to(self.delivery_root):
            raise ValueError("resource escapes delivery root")
        return path

    def _candidate_root(self, artifact: Path) -> Path:
        relative = artifact.relative_to(self.delivery_root)
        if len(relative.parts) < 2:
            raise ValueError("Candidate artifact is not contained in a Candidate directory")
        return self.delivery_root / relative.parts[0]

    @staticmethod
    def _verify_ref(path: Path, resource: CandidateResourceRef) -> None:
        if not path.is_file() or resource.sha256 is None:
            raise ValueError("resource is missing or unlocked")
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != resource.sha256:
            raise ValueError("resource digest mismatch")

    def _resource_ref(self, path: Path, *, media_type: str) -> CandidateResourceRef:
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        return CandidateResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=path.relative_to(self.delivery_root).as_posix(),
            media_type=media_type,
            sha256=digest,
        )

    @staticmethod
    def _endpoint(handle: RunHandle, name: str) -> RuntimeEndpoint:
        return next(endpoint for endpoint in handle.endpoints if endpoint.name == name)

    @staticmethod
    def _available_port() -> int:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return int(sock.getsockname()[1])

    @staticmethod
    def _handle_key(handle: RunHandle) -> str:
        return handle.sha256()

    @staticmethod
    def _event(operation: str, status: str, **detail: Any) -> dict[str, Any]:
        return {"operation": operation, "status": status, **detail}

    @staticmethod
    def _stop_instance(instance: _ManagedInstance) -> None:
        process = instance.process
        if process.poll() is not None:
            return
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                capture_output=True,
                check=False,
            )
        else:
            process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    @staticmethod
    def _passed(
        operation: LifecycleOperation,
        task_id: str,
        candidate_id: str,
        started: float,
        summary: str,
        *,
        evidence_refs: tuple[CandidateResourceRef, ...] = (),
    ) -> LifecycleResult:
        return LifecycleResult(
            operation=operation,
            status=LifecycleStatus.PASSED,
            task_id=task_id,
            candidate_id=candidate_id,
            duration_ms=max(0, int((time.perf_counter() - started) * 1000)),
            summary=summary,
            evidence_refs=evidence_refs,
        )

    @staticmethod
    def _failed(
        operation: LifecycleOperation,
        task_id: str,
        candidate_id: str,
        started: float,
        code: str,
        message: str,
    ) -> LifecycleResult:
        return LifecycleResult(
            operation=operation,
            status=LifecycleStatus.FAILED,
            task_id=task_id,
            candidate_id=candidate_id,
            duration_ms=max(0, int((time.perf_counter() - started) * 1000)),
            summary=message,
            error=ErrorDetail(code=code, message=message),
        )
