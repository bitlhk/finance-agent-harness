"""OpenJiuwen Python implementation of Candidate Backend v1."""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import zipfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml
from finance_builder_base import ErrorDetail, ResourceKind
from finance_builder_base.solution_build import CheckStatus

from finance_solution_builder.assets import ResolvedAsset
from finance_solution_builder.candidate_backend.models import (
    AssemblyRequest,
    AssemblyResult,
    BackendCapability,
    BackendIdentity,
    BuildRequest,
    BuildResult,
    CandidateArtifact,
    CandidateCheck,
    CandidateDescriptor,
    CandidateEntrypoint,
    CandidateResourceRef,
    CandidateTarget,
    CapabilityProviderBinding,
    EvidenceRequest,
    EvidenceResult,
    HealthRequest,
    HealthResult,
    InvocationRequest,
    InvocationResult,
    LifecycleOperation,
    LifecycleResult,
    LifecycleStatus,
    PreflightRequest,
    PreflightResult,
    ProtocolBinding,
    RuntimeIdentity,
    StartRequest,
    StartResult,
    StopRequest,
    StopResult,
    TypedExtension,
)
from finance_solution_builder.candidate_backend.python_zipapp_runtime import (
    PythonZipAppManagedRuntime,
)


ALL_OPERATIONS = tuple(LifecycleOperation)
OPENJIUWEN_VERSION = "0.1.16"
RUNTIME_SERVICE_VERSION = "0.1.0"
TEMPLATE_MANIFEST = "python-candidate-template.json"


@dataclass(frozen=True)
class PythonZipAppArtifactPlan:
    module_entrypoint: str
    artifact_name: str
    artifact_path: Path
    python_requires: str
    health_path: str
    invocation_path: str
    harness_transport: str


class OpenJiuwenPythonZipAppBackend:
    """Build and run one resolved OpenJiuwen Python Candidate deterministically."""

    def __init__(
        self,
        *,
        project_root: Path,
        delivery_root: Path,
        candidate_dir: Path,
        solution: dict[str, Any],
        assets: list[ResolvedAsset],
        runtime_assets: dict[str, ResolvedAsset],
        python: str = sys.executable,
        command_runner: Callable[[list[str], Path], subprocess.CompletedProcess[str]] | None = None,
        health_timeout_seconds: float = 60.0,
        request_timeout_seconds: float = 120.0,
        environment: dict[str, str] | None = None,
    ) -> None:
        self.project_root = project_root.resolve()
        self.delivery_root = delivery_root.resolve()
        self.candidate_dir = candidate_dir.resolve()
        self.solution = solution
        self.assets = list(assets)
        self.runtime_assets = dict(runtime_assets)
        self.python = python
        self._command_runner = command_runner or self._run_command
        self._identity = BackendIdentity(backend_id="openjiuwen-python", version="1.0")
        self._capability = BackendCapability(
            backend=self._identity,
            languages=("python",),
            runtime_families=("openjiuwen",),
            artifact_kinds=("python-zipapp",),
            invocation_protocols=("a2a",),
            operations=ALL_OPERATIONS,
        )
        self._runtime = PythonZipAppManagedRuntime(
            backend=self._identity,
            delivery_root=self.delivery_root,
            python=self.python,
            health_timeout_seconds=health_timeout_seconds,
            request_timeout_seconds=request_timeout_seconds,
            environment=environment,
            evidence_schema_version="openjiuwen-python-lifecycle-evidence.v1",
        )

    @property
    def identity(self) -> BackendIdentity:
        return self._identity

    @property
    def capability(self) -> BackendCapability:
        return self._capability

    @property
    def required_capability_ids(self) -> tuple[str, ...]:
        values: list[str] = []
        for asset in self._domain_and_capability_assets():
            for item in self._load_pack(asset).get("capabilities", []):
                value = item.get("capabilityId") if isinstance(item, dict) else item
                if isinstance(value, str) and value not in values:
                    values.append(value)
        return tuple(values)

    @property
    def required_provider_operation_types(self) -> tuple[str, ...]:
        return tuple(sorted({self._operation_type(item) for item in self.required_capability_ids}))

    @property
    def host_wiring_ids(self) -> tuple[str, ...]:
        integration = self.runtime_assets.get("integration")
        return () if integration is None or integration.status != "resolved" else (integration.id,)

    def target(self) -> CandidateTarget:
        return CandidateTarget(
            language="python",
            runtime_family="openjiuwen",
            runtime_version=OPENJIUWEN_VERSION,
            artifact_kind="python-zipapp",
            invocation_protocol="a2a",
            required_operations=ALL_OPERATIONS,
            required_asset_ids=tuple(self._resolved_asset_ids()),
            required_capability_ids=self.required_capability_ids,
            required_provider_operation_types=self.required_provider_operation_types,
            profile_id=self.solution["profileRef"]["id"],
            host_wiring_ids=self.host_wiring_ids,
        )

    def preflight(self, request: PreflightRequest) -> PreflightResult:
        started = time.perf_counter()
        checks: list[CandidateCheck] = []
        unresolved = [
            asset.id for asset in self._all_assets() if asset.status not in {"resolved", "fallback"}
        ]
        checks.append(
            self._check(
                "asset-closure",
                not unresolved
                and set(request.target.required_asset_ids).issubset(request.resolved_asset_ids),
                "All required assets are resolved and locked.",
                "Required assets are unresolved.",
            )
        )
        try:
            plan = self._plan_artifact()
        except (OSError, ValueError, json.JSONDecodeError):
            plan = None
        checks.append(
            self._check(
                "python-template",
                plan is not None,
                "The Python Candidate template contract is valid.",
                "The Python Candidate template contract is missing or invalid.",
            )
        )
        dependency_facts = self._inspect_interpreter()
        checks.append(
            self._check(
                "python-dependencies",
                plan is not None
                and self._python_supported(plan.python_requires, dependency_facts)
                and dependency_facts.get("openjiuwen") == OPENJIUWEN_VERSION
                and dependency_facts.get("openjiuwen-runtime-service")
                == RUNTIME_SERVICE_VERSION,
                "The controlled interpreter provides the pinned OpenJiuwen dependencies.",
                "The controlled interpreter does not provide the pinned OpenJiuwen dependencies.",
            )
        )
        checks.append(
            self._check(
                "harness-integration",
                plan is not None and self._integration_transport_valid(plan.harness_transport),
                "The resolved Runtime Integration provides the declared Harness transport.",
                "The resolved Runtime Integration does not provide the declared Harness transport.",
            )
        )
        capabilities = request.target.required_capability_ids
        provider_coverage = {item: self._provider_for(item) for item in capabilities}
        checks.append(
            self._check(
                "capability-coverage",
                bool(capabilities) and all(provider_coverage.values()),
                "Every required capability has a Provider implementation.",
                "A required capability has no Provider implementation.",
            )
        )
        checks.append(
            self._check(
                "host-wiring",
                bool(request.target.host_wiring_ids),
                "Host Wiring is defined for the selected integration.",
                "Host Wiring is missing for the selected integration.",
            )
        )
        passed = all(check.status is CheckStatus.PASSED for check in checks)
        return PreflightResult(
            outcome=self._outcome(
                LifecycleOperation.PREFLIGHT,
                LifecycleStatus.PASSED if passed else LifecycleStatus.BLOCKED,
                request.task_id,
                None,
                started,
                "Python Candidate preflight passed."
                if passed
                else "Python Candidate preflight blocked.",
                None
                if passed
                else ErrorDetail(
                    code="candidate.python.preflight_blocked",
                    message="Python Candidate preflight found an incomplete or incompatible binding.",
                ),
            ),
            backend=self.identity,
            checks=tuple(checks),
        )

    def assemble(self, request: AssemblyRequest) -> AssemblyResult:
        started = time.perf_counter()
        candidate_id = self.solution["solutionId"]
        try:
            plan = self._plan_artifact()
            template = self.runtime_assets["template"]
            if template.path is None:
                raise ValueError("Python Candidate template has no local path")
            self._copy_template(template.path, self.candidate_dir)
            self._materialize_assets()
            main_module, separator, callable_name = plan.module_entrypoint.partition(":")
            if not separator or not main_module or not callable_name:
                raise ValueError("moduleEntrypoint must use module:callable")
            (self.candidate_dir / "__main__.py").write_text(
                "from " + main_module + " import " + callable_name + "\n\n"
                "if __name__ == '__main__':\n"
                "    " + callable_name + "()\n",
                encoding="utf-8",
            )
            (self.candidate_dir / "solution.normalized.json").write_text(
                json.dumps(self.solution, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            manifest_path = self.candidate_dir / "candidate-assembly.json"
            manifest_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "openjiuwen-python-assembly.v1",
                        "candidateId": candidate_id,
                        "profileId": request.target.profile_id,
                        "assetIds": list(request.target.required_asset_ids),
                        "capabilityIds": list(request.target.required_capability_ids),
                        "hostWiringIds": list(request.target.host_wiring_ids),
                        "harnessTransport": plan.harness_transport,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            return AssemblyResult(
                outcome=self._outcome(
                    LifecycleOperation.ASSEMBLE,
                    LifecycleStatus.PASSED,
                    request.task_id,
                    candidate_id,
                    started,
                    "OpenJiuwen Python Candidate assembled.",
                ),
                candidate_id=candidate_id,
                assembly_refs=(self._resource_ref(manifest_path, "application/json"),),
            )
        except (OSError, RuntimeError, ValueError, json.JSONDecodeError):
            return AssemblyResult(
                outcome=self._outcome(
                    LifecycleOperation.ASSEMBLE,
                    LifecycleStatus.FAILED,
                    request.task_id,
                    candidate_id,
                    started,
                    "OpenJiuwen Python Candidate assembly failed.",
                    ErrorDetail(
                        code="candidate.python.assembly_failed",
                        message="OpenJiuwen Python Candidate assembly failed.",
                    ),
                ),
                candidate_id=candidate_id,
                assembly_refs=(),
            )

    def build(self, request: BuildRequest) -> BuildResult:
        started = time.perf_counter()
        try:
            plan = self._plan_artifact(candidate_root=self.candidate_dir)
            for source in sorted(self.candidate_dir.rglob("*.py")):
                compile(source.read_bytes(), source.as_posix(), "exec")
            self._write_zipapp(plan.artifact_path)
            descriptor = self._descriptor(request, plan)
            return BuildResult(
                outcome=self._outcome(
                    LifecycleOperation.BUILD,
                    LifecycleStatus.PASSED,
                    request.task_id,
                    request.candidate_id,
                    started,
                    "OpenJiuwen Python Candidate built as a deterministic ZipApp.",
                ),
                descriptor=descriptor,
            )
        except (OSError, SyntaxError, ValueError, zipfile.BadZipFile):
            return BuildResult(
                outcome=self._outcome(
                    LifecycleOperation.BUILD,
                    LifecycleStatus.FAILED,
                    request.task_id,
                    request.candidate_id,
                    started,
                    "OpenJiuwen Python Candidate build failed.",
                    ErrorDetail(
                        code="candidate.python.build_failed",
                        message="OpenJiuwen Python Candidate build failed before a verified artifact was selected.",
                    ),
                )
            )

    def start(self, request: StartRequest) -> StartResult:
        return self._runtime.start(request)

    def health(self, request: HealthRequest) -> HealthResult:
        return self._runtime.health(request)

    def invoke(self, request: InvocationRequest) -> InvocationResult:
        return self._runtime.invoke(request)

    def collect_evidence(self, request: EvidenceRequest) -> EvidenceResult:
        return self._runtime.collect_evidence(request)

    def stop(self, request: StopRequest) -> StopResult:
        return self._runtime.stop(request)

    def _plan_artifact(
        self, *, candidate_root: Path | None = None
    ) -> PythonZipAppArtifactPlan:
        root = candidate_root
        if root is None:
            template = self.runtime_assets.get("template")
            root = None if template is None else template.path
        if root is None:
            raise ValueError("Python Candidate template is unresolved")
        manifest_path = root / TEMPLATE_MANIFEST
        document = json.loads(manifest_path.read_text(encoding="utf-8"))
        expected = {
            "schemaVersion": "openjiuwen-python-template.v1",
            "openjiuwenVersion": OPENJIUWEN_VERSION,
            "runtimeServiceVersion": RUNTIME_SERVICE_VERSION,
            "harnessTransport": "canonical-json-http.v1",
        }
        if not isinstance(document, dict) or any(document.get(k) != v for k, v in expected.items()):
            raise ValueError("Python Candidate template identity is incompatible")
        module_entrypoint = document.get("moduleEntrypoint")
        artifact_name = document.get("artifactName")
        python_requires = document.get("pythonRequires")
        health_path = document.get("healthPath")
        invocation_path = document.get("invocationPath")
        if not isinstance(module_entrypoint, str) or re.fullmatch(
            r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*:[A-Za-z_]\w*", module_entrypoint
        ) is None:
            raise ValueError("Python Candidate moduleEntrypoint is invalid")
        if not isinstance(artifact_name, str) or re.fullmatch(
            r"[A-Za-z0-9][A-Za-z0-9_.-]*\.pyz", artifact_name
        ) is None:
            raise ValueError("Python Candidate artifactName is invalid")
        if not isinstance(python_requires, str) or re.fullmatch(
            r">=\d+\.\d+", python_requires
        ) is None:
            raise ValueError("Python Candidate pythonRequires is invalid")
        if not all(
            isinstance(value, str) and value.startswith("/") and ".." not in value.split("/")
            for value in (health_path, invocation_path)
        ):
            raise ValueError("Python Candidate protocol path is invalid")
        return PythonZipAppArtifactPlan(
            module_entrypoint=module_entrypoint,
            artifact_name=artifact_name,
            artifact_path=self.candidate_dir / artifact_name,
            python_requires=python_requires,
            health_path=health_path,
            invocation_path=invocation_path,
            harness_transport=document["harnessTransport"],
        )

    def _inspect_interpreter(self) -> dict[str, str]:
        script = (
            "import json, sys; from importlib.metadata import version; "
            "print(json.dumps({'python': f'{sys.version_info.major}.{sys.version_info.minor}', "
            "'openjiuwen': version('openjiuwen'), "
            "'openjiuwen-runtime-service': version('openjiuwen-runtime-service')}))"
        )
        try:
            result = self._command_runner([self.python, "-c", script], self.project_root)
            value = json.loads(result.stdout) if result.returncode == 0 else None
        except (OSError, json.JSONDecodeError):
            return {}
        return value if isinstance(value, dict) and all(isinstance(v, str) for v in value.values()) else {}

    @staticmethod
    def _python_supported(requirement: str, facts: dict[str, str]) -> bool:
        value = facts.get("python", "")
        try:
            actual = tuple(int(part) for part in value.split("."))
            required = tuple(int(part) for part in requirement.removeprefix(">=").split("."))
        except ValueError:
            return False
        return len(actual) == 2 and len(required) == 2 and actual >= required

    def _integration_transport_valid(self, expected: str) -> bool:
        integration = self.runtime_assets.get("integration")
        if integration is None or integration.path is None:
            return False
        for name in ("python-harness-integration.json", "harness-integration.json"):
            path = integration.path / name
            if not path.is_file():
                continue
            try:
                document = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                return False
            return isinstance(document, dict) and document.get("harnessTransport") == expected
        return False

    def _descriptor(
        self, request: BuildRequest, plan: PythonZipAppArtifactPlan
    ) -> CandidateDescriptor:
        artifact_ref = self._resource_ref(plan.artifact_path, "application/x-python-zipapp")
        capabilities = self.required_capability_ids
        providers = tuple(
            CapabilityProviderBinding(
                capability_id=capability,
                provider_id=self._provider_for(capability) or "unresolved-provider",
                operation_types=(self._operation_type(capability),),
            )
            for capability in capabilities
        )
        provenance = tuple(
            reference
            for reference in (
                self._optional_ref(self.delivery_root / "solution-lock.json"),
                self._optional_ref(self.candidate_dir / "solution.normalized.json"),
            )
            if reference is not None
        )
        return CandidateDescriptor(
            task_id=request.task_id,
            candidate_id=request.candidate_id,
            backend=self.identity,
            language="python",
            runtime=RuntimeIdentity(family="openjiuwen", version=OPENJIUWEN_VERSION),
            artifact=CandidateArtifact(
                artifact_id=plan.artifact_name.removesuffix(".pyz"),
                artifact_kind="python-zipapp",
                resource=artifact_ref,
            ),
            entrypoint=CandidateEntrypoint(mode="managed-artifact", resource=artifact_ref),
            invocation=ProtocolBinding(
                protocol="a2a", path=plan.invocation_path, media_type="application/json"
            ),
            health=ProtocolBinding(protocol="http", path=plan.health_path),
            lock_digest=request.lock_digest,
            installed_asset_ids=tuple(self._resolved_asset_ids()),
            installed_capability_ids=capabilities,
            installed_tool_ids=tuple(self._installed_tools()),
            provider_bindings=providers,
            provenance_refs=provenance,
            extensions=(
                TypedExtension(
                    extension_type="openjiuwen-python",
                    payload={
                        "artifactPath": artifact_ref.locator,
                        "moduleEntrypoint": plan.module_entrypoint,
                        "launchMode": "python-zipapp",
                        "openJiuwenVersion": OPENJIUWEN_VERSION,
                        "runtimeServiceVersion": RUNTIME_SERVICE_VERSION,
                        "harnessTransport": plan.harness_transport,
                    },
                ),
            ),
        )

    def _write_zipapp(self, artifact_path: Path) -> None:
        excluded = {artifact_path.resolve(), self.candidate_dir / "candidate-descriptor.json"}
        files = [
            path
            for path in self.candidate_dir.rglob("*")
            if path.is_file()
            and path.resolve() not in excluded
            and "__pycache__" not in path.parts
        ]
        artifact_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(artifact_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(files, key=lambda item: item.relative_to(self.candidate_dir).as_posix()):
                name = path.relative_to(self.candidate_dir).as_posix()
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, path.read_bytes())
        with zipfile.ZipFile(artifact_path) as archive:
            if archive.testzip() is not None or "__main__.py" not in archive.namelist():
                raise ValueError("Python ZipApp is invalid")

    def _materialize_assets(self) -> None:
        assets_root = self.candidate_dir / "assets"
        integration = self.runtime_assets.get("integration")
        materialized = [*self.assets]
        if integration is not None:
            materialized.append(integration)
        for asset in materialized:
            destination = assets_root / re.sub(r"[^A-Za-z0-9_.-]", "-", asset.id)
            destination.mkdir(parents=True, exist_ok=True)
            (destination / "asset-lock.json").write_text(
                json.dumps(asset.lock_record(), ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            if asset.path is None or not asset.path.is_dir():
                continue
            if asset in self._domain_and_capability_assets():
                self._copy_named_files(asset.path, destination, ("pack.yaml", "pack.yml"))
                source_skills = asset.path / "skills"
                if source_skills.is_dir():
                    shutil.copytree(
                        source_skills,
                        destination / "skills",
                        dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns(".git", "__pycache__"),
                    )
            if integration is asset:
                self._copy_named_files(
                    asset.path,
                    destination,
                    ("python-harness-integration.json", "harness-integration.json"),
                )
                for name in ("contracts", "fixtures"):
                    source = asset.path / name
                    if source.is_dir():
                        shutil.copytree(
                            source,
                            destination / name,
                            dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns(".git", "__pycache__"),
                        )

    @staticmethod
    def _copy_named_files(source: Path, destination: Path, names: tuple[str, ...]) -> None:
        for name in names:
            path = source / name
            if path.is_file():
                shutil.copy2(path, destination / name)

    def _provider_for(self, capability_id: str) -> str | None:
        quoted = re.compile(rf'["\']{re.escape(capability_id)}["\']')
        for asset in self.assets:
            if not asset.source.startswith("repo://providers/") or asset.path is None:
                continue
            for path in asset.path.rglob("*"):
                if path.is_file() and path.suffix.lower() in {
                    ".py",
                    ".md",
                    ".yaml",
                    ".yml",
                    ".json",
                }:
                    try:
                        if quoted.search(path.read_text(encoding="utf-8")):
                            return asset.id
                    except (OSError, UnicodeError):
                        continue
        return None

    def _domain_and_capability_assets(self) -> list[ResolvedAsset]:
        return [
            asset
            for asset in self.assets
            if asset.source.startswith("repo://domain-packs/")
            or asset.source.startswith("repo://capability-packs/")
        ]

    @staticmethod
    def _load_pack(asset: ResolvedAsset) -> dict[str, Any]:
        if asset.path is None:
            return {}
        for name in ("pack.yaml", "pack.yml"):
            path = asset.path / name
            if path.is_file():
                value = yaml.safe_load(path.read_text(encoding="utf-8"))
                return value if isinstance(value, dict) else {}
        return {}

    def _installed_tools(self) -> list[str]:
        values: list[str] = []
        for asset in self._domain_and_capability_assets():
            manifest = self._load_pack(asset)
            for item in manifest.get("skills", []):
                value = item.get("directory") if isinstance(item, dict) else item
                if isinstance(value, str) and value not in values:
                    values.append(value)
            for item in manifest.get("capabilities", []):
                if isinstance(item, dict) and isinstance(item.get("toolName"), str):
                    value = item["toolName"]
                    if value not in values:
                        values.append(value)
        return values

    def _all_assets(self) -> list[ResolvedAsset]:
        return [*self.assets, *self.runtime_assets.values()]

    def _resolved_asset_ids(self) -> list[str]:
        return list(dict.fromkeys(asset.id for asset in self._all_assets() if asset.status == "resolved"))

    @staticmethod
    def _operation_type(capability_id: str) -> str:
        return "query" if capability_id.endswith(".query") else "task"

    @staticmethod
    def _check(
        check_id: str, passed: bool, passed_summary: str, failed_summary: str
    ) -> CandidateCheck:
        return CandidateCheck(
            check_id=check_id,
            status=CheckStatus.PASSED if passed else CheckStatus.BLOCKED,
            summary=passed_summary if passed else failed_summary,
        )

    def _resource_ref(self, path: Path, media_type: str) -> CandidateResourceRef:
        path = path.resolve()
        if not path.is_relative_to(self.delivery_root):
            raise ValueError("candidate resource is outside the delivery root")
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        return CandidateResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=path.relative_to(self.delivery_root).as_posix(),
            media_type=media_type,
            sha256=digest,
        )

    def _optional_ref(self, path: Path) -> CandidateResourceRef | None:
        return self._resource_ref(path, "application/json") if path.is_file() else None

    @staticmethod
    def _copy_template(source: Path, destination: Path) -> None:
        shutil.copytree(
            source,
            destination,
            ignore=shutil.ignore_patterns(".git", "target", "node_modules", "__pycache__"),
        )

    @staticmethod
    def _run_command(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            command,
            cwd=cwd,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
        )

    @staticmethod
    def _outcome(
        operation: LifecycleOperation,
        status: LifecycleStatus,
        task_id: str,
        candidate_id: str | None,
        started: float,
        summary: str,
        error: ErrorDetail | None = None,
    ) -> LifecycleResult:
        return LifecycleResult(
            operation=operation,
            status=status,
            task_id=task_id,
            candidate_id=candidate_id,
            duration_ms=max(0, int((time.perf_counter() - started) * 1000)),
            summary=summary,
            error=error,
        )
