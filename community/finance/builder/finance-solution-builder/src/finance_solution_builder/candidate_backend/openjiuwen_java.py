"""OpenJiuwen Java implementation of Candidate Backend v1."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml
from finance_builder_base import ErrorDetail, ResourceKind
from finance_builder_base.solution_build import CheckStatus

from finance_solution_builder.assets import ResolvedAsset
from finance_solution_builder.candidate_backend.java_runtime import JavaManagedRuntime
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


ALL_OPERATIONS = tuple(LifecycleOperation)


@dataclass(frozen=True)
class JavaArtifactPlan:
    module: str
    artifact_id: str
    version: str
    artifact_path: Path
    main_class: str
    build_command: tuple[str, ...]
    health_path: str
    invocation_path: str = "/a2a"


class OpenJiuwenJavaBackend:
    """Build and run one resolved OpenJiuwen Java Candidate deterministically."""

    def __init__(
        self,
        *,
        project_root: Path,
        delivery_root: Path,
        candidate_dir: Path,
        solution: dict[str, Any],
        assets: list[ResolvedAsset],
        runtime_assets: dict[str, ResolvedAsset],
        assemble_action: Callable[[], None],
        maven: str = "mvn",
        java: str = "java",
        command_runner: Callable[[list[str], Path], subprocess.CompletedProcess[str]] | None = None,
        health_timeout_seconds: float = 60.0,
        request_timeout_seconds: float = 120.0,
    ) -> None:
        self.project_root = project_root.resolve()
        self.delivery_root = delivery_root.resolve()
        self.candidate_dir = candidate_dir.resolve()
        self.solution = solution
        self.assets = list(assets)
        self.runtime_assets = dict(runtime_assets)
        self.assemble_action = assemble_action
        self.maven = maven
        self._command_runner = command_runner or self._run_command
        self._identity = BackendIdentity(backend_id="openjiuwen-java", version="1.0")
        self._capability = BackendCapability(
            backend=self._identity,
            languages=("java",),
            runtime_families=("openjiuwen",),
            artifact_kinds=("jar",),
            invocation_protocols=("a2a",),
            operations=ALL_OPERATIONS,
        )
        self._artifact_plan: JavaArtifactPlan | None = None
        self._runtime = JavaManagedRuntime(
            backend=self._identity,
            delivery_root=self.delivery_root,
            java=java,
            health_timeout_seconds=health_timeout_seconds,
            request_timeout_seconds=request_timeout_seconds,
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
            manifest = self._load_pack(asset)
            for item in manifest.get("capabilities", []):
                capability_id = item.get("capabilityId") if isinstance(item, dict) else item
                if isinstance(capability_id, str) and capability_id not in values:
                    values.append(capability_id)
        return tuple(values)

    @property
    def required_provider_operation_types(self) -> tuple[str, ...]:
        operations = {self._operation_type(item) for item in self.required_capability_ids}
        return tuple(sorted(operations))

    @property
    def host_wiring_ids(self) -> tuple[str, ...]:
        integration = self.solution["runtime"]["integrationRef"]
        profile = self.solution["profileRef"]["id"]
        if integration.endswith("#framework/finance-harness-openjiuwen"):
            integration = "repo://integrations/openjiuwen-java"
        wiring = {
            ("customer-self-service", "repo://integrations/openjiuwen-java"): (
                "non-ai-banking-http",
            ),
            ("employee-assisted", "repo://integrations/employee-agent-java"): (
                "employee-agent-mcp",
            ),
            ("internal-control", "repo://integrations/openjiuwen-java"): (
                "in-process-smart-audit.v1",
            ),
        }
        return wiring.get((profile, integration), ())

    def target(self) -> CandidateTarget:
        runtime_version = self._runtime_version()
        return CandidateTarget(
            language="java",
            runtime_family="openjiuwen",
            runtime_version=runtime_version,
            artifact_kind="jar",
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
                not unresolved and set(request.target.required_asset_ids).issubset(request.resolved_asset_ids),
                "All required assets are resolved and locked.",
                "Required assets are unresolved.",
            )
        )
        capabilities = request.target.required_capability_ids
        provider_coverage = {
            capability: self._provider_for(capability) for capability in capabilities
        }
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
                "provider-operation-coverage",
                all(
                    self._operation_type(capability)
                    in request.target.required_provider_operation_types
                    for capability in capabilities
                ),
                "Provider operation types cover the requested capabilities.",
                "Provider operation type coverage is incomplete.",
            )
        )
        checks.append(
            self._check(
                "host-wiring",
                bool(request.target.host_wiring_ids),
                "Host Wiring is defined for the selected Profile and integration.",
                "Host Wiring is missing for the selected Profile and integration.",
            )
        )
        passed = all(check.status is CheckStatus.PASSED for check in checks)
        outcome = self._outcome(
            LifecycleOperation.PREFLIGHT,
            LifecycleStatus.PASSED if passed else LifecycleStatus.BLOCKED,
            request.task_id,
            None,
            started,
            "Java Candidate preflight passed." if passed else "Java Candidate preflight blocked.",
            None
            if passed
            else ErrorDetail(
                code="candidate.java.preflight_blocked",
                message="Java Candidate preflight found an incomplete or incompatible binding.",
            ),
        )
        return PreflightResult(outcome=outcome, backend=self.identity, checks=tuple(checks))

    def assemble(self, request: AssemblyRequest) -> AssemblyResult:
        started = time.perf_counter()
        candidate_id = self.solution["solutionId"]
        try:
            self.assemble_action()
            manifest_path = self.candidate_dir / "candidate-assembly.json"
            document = {
                "schemaVersion": "openjiuwen-java-assembly.v1",
                "candidateId": candidate_id,
                "profileId": request.target.profile_id,
                "assetIds": list(request.target.required_asset_ids),
                "capabilityIds": list(request.target.required_capability_ids),
                "hostWiringIds": list(request.target.host_wiring_ids),
            }
            manifest_path.write_text(
                json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            reference = self._resource_ref(manifest_path, media_type="application/json")
            return AssemblyResult(
                outcome=self._outcome(
                    LifecycleOperation.ASSEMBLE,
                    LifecycleStatus.PASSED,
                    request.task_id,
                    candidate_id,
                    started,
                    "OpenJiuwen Java Candidate assembled.",
                ),
                candidate_id=candidate_id,
                assembly_refs=(reference,),
            )
        except (OSError, RuntimeError, ValueError):
            return AssemblyResult(
                outcome=self._outcome(
                    LifecycleOperation.ASSEMBLE,
                    LifecycleStatus.FAILED,
                    request.task_id,
                    candidate_id,
                    started,
                    "OpenJiuwen Java Candidate assembly failed.",
                    ErrorDetail(
                        code="candidate.java.assembly_failed",
                        message="OpenJiuwen Java Candidate assembly failed.",
                    ),
                ),
                candidate_id=candidate_id,
                assembly_refs=(),
            )

    def build(self, request: BuildRequest) -> BuildResult:
        started = time.perf_counter()
        try:
            plan = self._plan_artifact()
            result = self._command_runner(list(plan.build_command), self.candidate_dir)
            if result.returncode != 0:
                raise RuntimeError("Maven build failed")
            if not plan.artifact_path.is_file():
                raise RuntimeError("planned artifact was not produced")
            self._verify_executable_jar(plan)
            descriptor = self._descriptor(request, plan)
            self._artifact_plan = plan
            return BuildResult(
                outcome=self._outcome(
                    LifecycleOperation.BUILD,
                    LifecycleStatus.PASSED,
                    request.task_id,
                    request.candidate_id,
                    started,
                    "OpenJiuwen Java Candidate built from the declared Maven model.",
                ),
                descriptor=descriptor,
            )
        except (OSError, RuntimeError, ValueError, ET.ParseError):
            return BuildResult(
                outcome=self._outcome(
                    LifecycleOperation.BUILD,
                    LifecycleStatus.FAILED,
                    request.task_id,
                    request.candidate_id,
                    started,
                    "OpenJiuwen Java Candidate build failed.",
                    ErrorDetail(
                        code="candidate.java.build_failed",
                        message="OpenJiuwen Java Candidate build failed before a verified artifact was selected.",
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

    def _descriptor(self, request: BuildRequest, plan: JavaArtifactPlan) -> CandidateDescriptor:
        artifact_ref = self._resource_ref(plan.artifact_path, media_type="application/java-archive")
        capabilities = self.required_capability_ids
        providers = tuple(
            CapabilityProviderBinding(
                capability_id=capability,
                provider_id=self._provider_for(capability) or "unresolved-provider",
                operation_types=(self._operation_type(capability),),
            )
            for capability in capabilities
        )
        installed_tools = tuple(self._installed_tools())
        provenance = tuple(
            ref
            for ref in (
                self._optional_ref(self.delivery_root / "solution-lock.json"),
                self._optional_ref(self.candidate_dir / "solution.normalized.json"),
            )
            if ref is not None
        )
        return CandidateDescriptor(
            task_id=request.task_id,
            candidate_id=request.candidate_id,
            backend=self.identity,
            language="java",
            runtime=RuntimeIdentity(family="openjiuwen", version=self._runtime_version()),
            artifact=CandidateArtifact(
                artifact_id=plan.artifact_id,
                artifact_kind="jar",
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
            installed_tool_ids=installed_tools,
            provider_bindings=providers,
            provenance_refs=provenance,
            extensions=(
                TypedExtension(
                    extension_type="openjiuwen-java",
                    payload={
                        "mavenModule": plan.module,
                        "artifactPath": artifact_ref.locator,
                        "mainClass": plan.main_class,
                        "launchMode": "java-jar",
                    },
                ),
            ),
        )

    def _plan_artifact(self) -> JavaArtifactPlan:
        module = "agents/domain-runtime" if (self.candidate_dir / "agents/domain-runtime/pom.xml").is_file() else "."
        pom_path = self.candidate_dir / module / "pom.xml" if module != "." else self.candidate_dir / "pom.xml"
        if not pom_path.is_file():
            raise ValueError("Maven model is missing")
        root = ET.parse(pom_path).getroot()
        artifact_id = self._direct_text(root, "artifactId")
        version = self._direct_text(root, "version") or self._nested_text(root, "parent", "version")
        properties = self._pom_properties(root)
        if version is None and module != ".":
            parent_root = ET.parse(self.candidate_dir / "pom.xml").getroot()
            version = self._direct_text(parent_root, "version") or self._nested_text(
                parent_root, "parent", "version"
            )
            properties.update(self._pom_properties(parent_root))
        if not artifact_id or not version:
            raise ValueError("Maven artifact coordinates are incomplete")
        properties.update(
            {
                "project.artifactId": artifact_id,
                "pom.artifactId": artifact_id,
                "project.version": version,
                "pom.version": version,
            }
        )
        final_name = self._first_text(root, "finalName")
        if final_name is None:
            final_name = f"{artifact_id}-{version}"
        final_name = self._resolve_properties(final_name, properties)
        if "${" in final_name:
            raise ValueError("Maven finalName contains unresolved properties")
        main_class = self._first_text(root, "mainClass")
        if not main_class:
            raise ValueError("Maven executable mainClass is missing")
        artifact_path = pom_path.parent / "target" / f"{final_name}.jar"
        command = (
            (self.maven, "-q", "-pl", module, "-am", "-DskipTests", "package")
            if module != "."
            else (self.maven, "-q", "package")
        )
        return JavaArtifactPlan(
            module=module,
            artifact_id=artifact_id,
            version=version,
            artifact_path=artifact_path,
            main_class=main_class,
            build_command=command,
            health_path="/actuator/health" if module != "." else "/health",
        )

    @staticmethod
    def _verify_executable_jar(plan: JavaArtifactPlan) -> None:
        """Ensure the selected artifact is the executable JAR described by Maven."""

        try:
            with zipfile.ZipFile(plan.artifact_path) as archive:
                if archive.testzip() is not None:
                    raise ValueError("JAR contains a corrupt entry")
                try:
                    manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
                except KeyError as exc:
                    raise ValueError("JAR manifest is missing") from exc
                main_class = OpenJiuwenJavaBackend._manifest_main_class(manifest)
                if main_class != plan.main_class:
                    raise ValueError("JAR Main-Class does not match the Maven model")
                class_entry = f"{main_class.replace('.', '/')}.class"
                if class_entry not in archive.namelist():
                    raise ValueError("JAR Main-Class entry is missing")
        except (OSError, UnicodeError, zipfile.BadZipFile) as exc:
            raise ValueError("selected artifact is not a valid executable JAR") from exc

    @staticmethod
    def _manifest_main_class(manifest: str) -> str:
        logical_lines: list[str] = []
        for line in manifest.splitlines():
            if line.startswith(" "):
                if not logical_lines:
                    raise ValueError("invalid JAR manifest continuation")
                logical_lines[-1] += line[1:]
            else:
                logical_lines.append(line)

        main_classes: list[str] = []
        for line in logical_lines:
            if not line:
                break
            name, separator, value = line.partition(":")
            if not separator:
                raise ValueError("invalid JAR manifest attribute")
            if name.casefold() == "main-class":
                candidate = value.strip()
                if candidate:
                    main_classes.append(candidate)
        if len(main_classes) != 1:
            raise ValueError("JAR manifest requires exactly one Main-Class")
        return main_classes[0]

    def _provider_for(self, capability_id: str) -> str | None:
        quoted = re.compile(rf'["\']{re.escape(capability_id)}["\']')
        for asset in self.assets:
            if not asset.source.startswith("repo://providers/") or asset.path is None:
                continue
            for path in asset.path.rglob("*"):
                if path.is_file() and path.suffix.lower() in {".java", ".md", ".yaml", ".yml", ".json"}:
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

    def _runtime_version(self) -> str:
        runtime = self.solution.get("runtime", {})
        resolved = runtime.get("resolvedArtifacts", {})
        reference = resolved.get("templateRef") if isinstance(resolved, dict) else None
        if reference is None and isinstance(runtime, dict):
            # The controlled local template fallback retains the validated,
            # pinned Maven coordinate even though there is no downloaded
            # template POM from which to read the version.
            reference = runtime.get("templateRef")
        if isinstance(reference, str) and reference.startswith("maven://"):
            return reference.rsplit(":", 1)[-1]
        template = self.runtime_assets.get("template")
        if template is not None:
            roots = [template.root_path, template.path]
            for root_path in roots:
                if root_path is None:
                    continue
                pom_path = root_path / "pom.xml"
                if not pom_path.is_file():
                    continue
                root = ET.parse(pom_path).getroot()
                version = self._pom_properties(root).get("openjiuwen.runtime.version", "").strip()
                if version and "${" not in version:
                    return version
        raise ValueError("OpenJiuwen Runtime version is missing from the resolved template")

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

    def _resource_ref(self, path: Path, *, media_type: str) -> CandidateResourceRef:
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
        return self._resource_ref(path, media_type="application/json") if path.is_file() else None

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
    def _local_name(element: ET.Element) -> str:
        return element.tag.rsplit("}", 1)[-1]

    @classmethod
    def _direct_text(cls, root: ET.Element, name: str) -> str | None:
        for child in root:
            if cls._local_name(child) == name and child.text:
                return child.text.strip()
        return None

    @classmethod
    def _nested_text(cls, root: ET.Element, parent: str, child: str) -> str | None:
        for element in root:
            if cls._local_name(element) != parent:
                continue
            return cls._direct_text(element, child)
        return None

    @classmethod
    def _first_text(cls, root: ET.Element, name: str) -> str | None:
        for element in root.iter():
            if cls._local_name(element) == name and element.text:
                return element.text.strip()
        return None

    @classmethod
    def _pom_properties(cls, root: ET.Element) -> dict[str, str]:
        for element in root:
            if cls._local_name(element) == "properties":
                return {
                    cls._local_name(child): child.text.strip()
                    for child in element
                    if child.text and child.text.strip()
                }
        return {}

    @staticmethod
    def _resolve_properties(value: str, properties: dict[str, str]) -> str:
        for _ in range(10):
            updated = re.sub(
                r"\$\{([^}]+)\}",
                lambda match: properties.get(match.group(1), match.group(0)),
                value,
            )
            if updated == value:
                return updated
            value = updated
        return value

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
