import json
import sys
import zipfile
from dataclasses import replace
from datetime import UTC, datetime
from pathlib import Path

import pytest
from finance_builder_base import ErrorDetail, ResourceKind, ResourceRef
from finance_builder_base.evaluation import AcceptanceRule, RuleGroup, RuleOperator

from finance_solution_builder.assets import (
    AssetResolver,
    HarnessArchiveAnchor,
    ResolvedAsset,
    archive_tree_digest,
    write_archive_integrity,
)
from finance_solution_builder.asset_governance import AssetCatalog
from finance_solution_builder.builder import SolutionBuilder, Stage
from finance_solution_builder.candidate_backend.models import (
    BuildRequest as CandidateBuildRequest,
    BuildResult as CandidateBuildResult,
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
)
from finance_solution_builder.candidate_backend.openjiuwen_java import OpenJiuwenJavaBackend
from finance_solution_builder.evaluation import (
    DatasetPartition,
    DatasetPurpose,
    EvaluationMode,
    EvaluationPack,
    EvaluationSample,
    MetricKind,
    MetricSpec,
    SandboxEvaluationRequest,
)
from finance_solution_builder.optimization import CandidateOverlay, OverlayFile


def _resolved(root: Path, asset_id: str, source: str) -> ResolvedAsset:
    path = root / asset_id
    path.mkdir(parents=True, exist_ok=True)
    return ResolvedAsset(
        id=asset_id,
        version="1.0",
        source=source,
        status="resolved",
        digest="sha256:" + "a" * 64,
        path=path,
        root_path=path,
    )


def _write_asset_catalog(
    harness: Path,
    *,
    profile_id: str,
    profile_source: str,
    pack_id: str,
    pack_source: str,
    provider_id: str,
    provider_source: str,
    integration_source: str,
    capability: str,
    evaluation_source: str,
) -> None:
    catalog = harness / "catalog/finance-assets.v1.json"
    schema = harness / "contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json"
    catalog.parent.mkdir(parents=True)
    schema.parent.mkdir(parents=True)
    schema.write_text(json.dumps({"type": "object"}), encoding="utf-8")
    dependency = []
    capability_packs = []
    if profile_id == "employee-assisted":
        dependency = [{"kind": "capability-pack", "id": "wealth-capabilities", "version": "1.0"}]
        capability_packs = [
            {
                "id": "wealth-capabilities",
                "version": "1.0",
                "displayName": "Wealth capabilities",
                "source": "repo://capability-packs/wealth",
                "governanceRef": "owner",
                "capabilities": [capability],
            }
        ]
    common = {"displayName": "Fixture", "governanceRef": "owner"}
    document = {
        "schemaVersion": "finance-asset-catalog.v1",
        "catalogVersion": "1.0",
        "catalogRevision": "fixture-r1",
        "governancePolicies": {
            "owner": {
                "owner": "fixture-owner",
                "repository": "https://example.invalid/harness",
                "changeMode": "proposal-only",
                "allowedChangeScopes": ["tests"],
                "approvalPolicy": "owner-review",
                "verification": {"kind": "command", "value": "pytest"},
            }
        },
        "runtimeIntegrations": [
            {
                **common,
                "id": "runtime-integration",
                "version": "1.0",
                "source": integration_source,
                "compatibility": {
                    "runtimeFamilies": ["openjiuwen"],
                    "runtimeLanguages": ["java"],
                },
            }
        ],
        "profiles": [{**common, "id": profile_id, "version": "1.0", "source": profile_source}],
        "domainPacks": [
            {
                **common,
                "id": pack_id,
                "version": "1.0",
                "source": pack_source,
                "requiresCapabilities": [capability],
                "dependencies": dependency,
                "compatibility": {"profiles": [profile_id]},
            }
        ],
        "capabilityPacks": capability_packs,
        "providers": [
            {
                **common,
                "id": provider_id,
                "version": "1.0",
                "source": provider_source,
                "capabilities": [capability],
            }
        ],
        "evalPacks": [
            {
                **common,
                "id": "wealth-management",
                "version": "1.0",
                "source": evaluation_source,
                "compatibility": {"profiles": [profile_id]},
            }
        ],
    }
    catalog.write_text(json.dumps(document), encoding="utf-8")


def _builder_fixture(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    profile_id: str,
) -> tuple[SolutionBuilder, Path, dict[str, ResolvedAsset]]:
    project = tmp_path / "builder"
    harness = tmp_path / "harness"
    schema = harness / "contracts/json-schema/solution/solution-build-input.v1.schema.json"
    schema.parent.mkdir(parents=True)
    schema.write_text(json.dumps({"type": "object"}), encoding="utf-8")
    template = _resolved(tmp_path, "template", "git+https://example.invalid/template.git@abc")
    (template.path / "pom.xml").write_text(
        """<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>
        <artifactId>@@SOLUTION_ID@@</artifactId><version>1.0</version>
        <properties><openjiuwen.runtime.version>0.1.1.post1</openjiuwen.runtime.version></properties>
        <build><plugins><plugin>
        <configuration><mainClass>example.Main</mainClass><finalName>@@SOLUTION_ID@@-all</finalName>
        </configuration></plugin></plugins></build></project>""",
        encoding="utf-8",
    )
    (template.path / "README.md").write_text("@@DISPLAY_NAME@@", encoding="utf-8")
    if profile_id == "employee-assisted":
        pack_id = "wealth-employee-assisted"
        provider_id = "employee-agent-reference"
        integration_source = "repo://integrations/employee-agent-java"
        capability = "wealth.previsit-context.query"
    else:
        pack_id = "wealth-customer-self-service"
        provider_id = "non-ai-banking-reference"
        integration_source = (
            "git+https://example.invalid/mobile-banking.git@abc"
            "#framework/finance-harness-openjiuwen"
        )
        capability = "wealth.customer-context.query"
    profile = _resolved(tmp_path, profile_id, f"repo://profiles/{profile_id}")
    domain = _resolved(tmp_path, pack_id, f"repo://domain-packs/wealth/{profile_id}")
    (domain.path / "pack.yaml").write_text(
        f"capabilities:\n  - {capability}\nskills:\n  - directory: wealth-skill\n",
        encoding="utf-8",
    )
    (domain.path / "skills" / "wealth-skill").mkdir(parents=True)
    provider = _resolved(tmp_path, provider_id, f"repo://providers/{provider_id}")
    (provider.path / "Provider.java").write_text(
        f'class Provider {{ String capability = "{capability}"; }}', encoding="utf-8"
    )
    evaluation = _resolved(tmp_path, "wealth-management", "repo://eval-packs/wealth")
    integration = _resolved(tmp_path, "runtime-integration", integration_source)
    assets = {
        profile.id: profile,
        domain.id: domain,
        provider.id: provider,
        evaluation.id: evaluation,
        "openjiuwen-java-template": template,
        "runtime-integration": integration,
    }
    if profile_id == "employee-assisted":
        assets["wealth-capabilities"] = _resolved(
            tmp_path,
            "wealth-capabilities",
            "repo://capability-packs/wealth",
        )
    _write_asset_catalog(
        harness,
        profile_id=profile_id,
        profile_source=profile.source,
        pack_id=pack_id,
        pack_source=domain.source,
        provider_id=provider_id,
        provider_source=provider.source,
        integration_source=integration_source,
        capability=capability,
        evaluation_source=evaluation.source,
    )
    harness_source = "https://github.com/example/finance-agent-harness.git"
    harness_revision = "b" * 40
    write_archive_integrity(
        harness,
        revision=harness_revision,
        source=harness_source,
    )
    anchor = HarnessArchiveAnchor(
        source=harness_source,
        revision=harness_revision,
        tree_digest=archive_tree_digest(harness),
    )
    monkeypatch.setattr(AssetResolver, "resolve", lambda _self, ref: assets[ref["id"]])
    builder = SolutionBuilder(project, harness, harness_archive_anchor=anchor)
    solution = project / f"{profile_id}.json"
    solution.parent.mkdir(parents=True)
    solution.write_text(
        json.dumps(
            {
                "schemaVersion": "finance-agent-solution-build.v1",
                "solutionId": f"wealth-{profile_id}-demo",
                "displayName": "Wealth Demo",
                "runtime": {
                    "family": "openjiuwen",
                    "language": "java",
                    "templateRef": "git+https://example.invalid/template.git@abc",
                    "integrationRef": integration_source,
                },
                "profileRef": {
                    "id": profile_id,
                    "version": "1.0",
                    "source": profile.source,
                },
                "domainPackRefs": [{"id": pack_id, "version": "1.0", "source": domain.source}],
                "providerRefs": [{"id": provider_id, "version": "1.0", "source": provider.source}],
                "modelRef": "env://FINANCE_AGENT_MODEL",
                "evaluationPackRef": {
                    "id": evaluation.id,
                    "version": "1.0",
                    "source": evaluation.source,
                },
            }
        ),
        encoding="utf-8",
    )
    return builder, solution, assets


def test_preflight_rejection_preserves_existing_output(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    project = tmp_path / "builder"
    harness = tmp_path / "harness"
    schema = harness / "contracts/json-schema/solution/solution-build-input.v1.schema.json"
    schema.parent.mkdir(parents=True)
    schema.write_text(json.dumps({"type": "object"}), encoding="utf-8")
    solution = project / "anti-fraud.json"
    solution.parent.mkdir(parents=True)
    solution.write_text(
        json.dumps(
            {
                "runtime": {"integrationRef": "repo://integrations/openjiuwen-java"},
                "profileRef": {"id": "customer-self-service"},
                "domainPackRefs": [{"id": "transfer-anti-fraud"}],
                "providerRefs": [{"id": "fraud-mining-reference"}],
            }
        ),
        encoding="utf-8",
    )
    output = project / "build/anti-fraud"
    output.mkdir(parents=True)
    sentinel = output / "previous-good-build.txt"
    sentinel.write_text("keep", encoding="utf-8")

    monkeypatch.setattr(
        AssetCatalog,
        "load",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            ValueError("asset.compatibility.multiple_providers_unsupported")
        ),
    )
    with pytest.raises(ValueError, match="multiple_providers_unsupported"):
        SolutionBuilder(
            project,
            harness,
            harness_archive_anchor=HarnessArchiveAnchor(
                source="https://github.com/example/finance-agent-harness.git",
                revision="a" * 40,
                tree_digest="sha256:" + "b" * 64,
            ),
        ).build(solution, output, verify=False)

    assert sentinel.read_text(encoding="utf-8") == "keep"


@pytest.mark.parametrize("profile_id", ["customer-self-service", "employee-assisted"])
def test_backend_migration_preserves_solution_lock_and_report_projection(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, profile_id: str
) -> None:
    builder, solution, _assets = _builder_fixture(tmp_path, monkeypatch, profile_id=profile_id)
    output = builder.project_root / "build" / profile_id

    result = builder.build(solution, output, verify=False)

    lock = json.loads(result.lock_path.read_text(encoding="utf-8"))
    report = json.loads(result.report_path.read_text(encoding="utf-8"))
    assembly = json.loads(
        (result.candidate_dir / "candidate-assembly.json").read_text(encoding="utf-8")
    )
    assert lock["schemaVersion"] == "finance-solution-lock.v1"
    assert lock["assetCatalog"]["schemaVersion"] == "finance-asset-catalog.v1"
    closure_ids = {item["id"] for item in lock["assetClosure"]}
    assert {profile_id, "wealth-management", "runtime-integration"}.issubset(closure_ids)
    if profile_id == "employee-assisted":
        assert "wealth-capabilities" in closure_ids
    assert report["schemaVersion"] == "finance-solution-build-report.v1"
    assert report["status"] == "passed"
    assert assembly["profileId"] == profile_id
    assert assembly["capabilityIds"]
    assert (result.candidate_dir / "solution.yaml").is_file()


def test_published_asset_version_produces_a_new_lock(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    builder, solution, assets = _builder_fixture(
        tmp_path, monkeypatch, profile_id="customer-self-service"
    )
    first = builder.build(solution, builder.project_root / "build/first", verify=False)
    first_lock = json.loads(first.lock_path.read_text(encoding="utf-8"))

    catalog_path = builder.harness_repo / "catalog/finance-assets.v1.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    catalog["catalogRevision"] = "fixture-r2"
    catalog["domainPacks"][0]["version"] = "1.1"
    catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
    solution_payload = json.loads(solution.read_text(encoding="utf-8"))
    solution_payload["domainPackRefs"][0]["version"] = "1.1"
    solution.write_text(json.dumps(solution_payload), encoding="utf-8")
    domain_id = catalog["domainPacks"][0]["id"]
    assets[domain_id] = replace(
        assets[domain_id],
        version="1.1",
        digest="sha256:" + "d" * 64,
    )
    published_revision = "c" * 40
    write_archive_integrity(
        builder.harness_repo,
        revision=published_revision,
        source="https://github.com/example/finance-agent-harness.git",
    )
    builder.harness_archive_anchor = HarnessArchiveAnchor(
        source="https://github.com/example/finance-agent-harness.git",
        revision=published_revision,
        tree_digest=archive_tree_digest(builder.harness_repo),
    )

    second = builder.build(solution, builder.project_root / "build/second", verify=False)
    second_lock = json.loads(second.lock_path.read_text(encoding="utf-8"))

    assert first_lock["assetCatalog"]["catalogRevision"] == "fixture-r1"
    assert second_lock["assetCatalog"]["catalogRevision"] == "fixture-r2"
    assert SolutionBuilder.asset_closure_digest(first_lock) != (
        SolutionBuilder.asset_closure_digest(second_lock)
    )


def test_unresolved_required_asset_is_rejected_before_existing_output_is_replaced(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    builder, solution, assets = _builder_fixture(
        tmp_path, monkeypatch, profile_id="customer-self-service"
    )
    assets["wealth-management"] = ResolvedAsset(
        id="wealth-management",
        version="1.0",
        source="repo://eval-packs/wealth",
        status="unresolved",
        error="path not found",
    )
    output = builder.project_root / "build" / "customer-self-service"
    output.mkdir(parents=True)
    sentinel = output / "previous-good-build.txt"
    sentinel.write_text("keep", encoding="utf-8")

    with pytest.raises(RuntimeError, match="required Solution assets"):
        builder.build(solution, output, verify=False)

    assert sentinel.read_text(encoding="utf-8") == "keep"


def test_stage_report_redacts_host_paths_and_credential_assignments(tmp_path: Path) -> None:
    stages = []
    credential_name = "A" + "PI_KEY"
    credential_value = "value" + "-for-test"

    SolutionBuilder(tmp_path, tmp_path)._run_stage(
        stages,
        "privacy_probe",
        [
            sys.executable,
            "-c",
            f'print("{credential_name}={credential_value} C:\\\\customer\\\\record.json")',
        ],
        tmp_path,
    )

    serialized = json.dumps(stages[0].__dict__)
    assert credential_value not in serialized
    assert "customer" not in serialized
    assert "<redacted-credential>" in serialized
    assert "<redacted-path>" in serialized


def _install_lifecycle_failure_fixture(
    builder: SolutionBuilder,
    monkeypatch: pytest.MonkeyPatch,
    *,
    failure_stage: str,
) -> list[str]:
    events: list[str] = []

    def outcome(
        operation: LifecycleOperation,
        status: LifecycleStatus,
        task_id: str,
        candidate_id: str,
        *,
        evidence_refs: tuple[CandidateResourceRef, ...] = (),
    ) -> LifecycleResult:
        return LifecycleResult(
            operation=operation,
            status=status,
            task_id=task_id,
            candidate_id=candidate_id,
            duration_ms=1,
            summary=f"{operation.value} fixture {status.value}.",
            evidence_refs=evidence_refs,
            error=(
                ErrorDetail(
                    code=f"candidate.fixture.{operation.value}.failed",
                    message=f"Candidate {operation.value} fixture failed.",
                )
                if status is LifecycleStatus.FAILED
                else None
            ),
        )

    def build_backend(
        backend: OpenJiuwenJavaBackend, request: CandidateBuildRequest
    ) -> CandidateBuildResult:
        plan = backend._plan_artifact()
        plan.artifact_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(plan.artifact_path, "w") as archive:
            archive.writestr(
                "META-INF/MANIFEST.MF",
                f"Manifest-Version: 1.0\r\nMain-Class: {plan.main_class}\r\n\r\n",
            )
            archive.writestr(f"{plan.main_class.replace('.', '/')}.class", b"fixture")
        descriptor = backend._descriptor(request, plan)
        return CandidateBuildResult(
            outcome=outcome(
                LifecycleOperation.BUILD,
                LifecycleStatus.PASSED,
                request.task_id,
                request.candidate_id,
            ),
            descriptor=descriptor,
        )

    def start_backend(backend: OpenJiuwenJavaBackend, request: StartRequest) -> StartResult:
        events.append("start")
        descriptor = request.descriptor
        if failure_stage == "start":
            return StartResult(
                outcome=outcome(
                    LifecycleOperation.START,
                    LifecycleStatus.FAILED,
                    descriptor.task_id,
                    descriptor.candidate_id,
                )
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
                    name="a2a",
                    protocol="http",
                    uri="http://127.0.0.1:18080/a2a",
                ),
            ),
        )
        return StartResult(
            outcome=outcome(
                LifecycleOperation.START,
                LifecycleStatus.PASSED,
                descriptor.task_id,
                descriptor.candidate_id,
            ),
            handle=handle,
        )

    def health_backend(_backend: OpenJiuwenJavaBackend, request: HealthRequest) -> HealthResult:
        events.append("health")
        status = LifecycleStatus.FAILED if failure_stage == "health" else LifecycleStatus.PASSED
        return HealthResult(
            outcome=outcome(
                LifecycleOperation.HEALTH,
                status,
                request.handle.task_id,
                request.handle.candidate_id,
            ),
            ready=status is LifecycleStatus.PASSED,
        )

    def invoke_backend(
        backend: OpenJiuwenJavaBackend, request: InvocationRequest
    ) -> InvocationResult:
        events.append("invoke")
        if failure_stage == "invoke":
            return InvocationResult(
                outcome=outcome(
                    LifecycleOperation.INVOKE,
                    LifecycleStatus.FAILED,
                    request.handle.task_id,
                    request.handle.candidate_id,
                ),
                request_id=request.request_id,
            )
        response_path = backend.delivery_root / "runtime" / "responses" / "probe.json"
        response_path.parent.mkdir(parents=True, exist_ok=True)
        response_path.write_text("{}\n", encoding="utf-8")
        response_ref = backend._resource_ref(response_path, media_type="application/json")
        return InvocationResult(
            outcome=outcome(
                LifecycleOperation.INVOKE,
                LifecycleStatus.PASSED,
                request.handle.task_id,
                request.handle.candidate_id,
            ),
            request_id=request.request_id,
            response_ref=response_ref,
        )

    def evidence_backend(
        backend: OpenJiuwenJavaBackend, request: EvidenceRequest
    ) -> EvidenceResult:
        events.append("evidence")
        evidence_path = backend.delivery_root / "runtime" / "evidence" / "lifecycle.json"
        evidence_path.parent.mkdir(parents=True, exist_ok=True)
        evidence_path.write_text('{"events":[]}\n', encoding="utf-8")
        evidence_ref = backend._resource_ref(evidence_path, media_type="application/json")
        return EvidenceResult(
            outcome=outcome(
                LifecycleOperation.COLLECT_EVIDENCE,
                LifecycleStatus.PASSED,
                request.handle.task_id,
                request.handle.candidate_id,
                evidence_refs=(evidence_ref,),
            )
        )

    def stop_backend(_backend: OpenJiuwenJavaBackend, request: StopRequest) -> StopResult:
        events.append("stop")
        return StopResult(
            outcome=outcome(
                LifecycleOperation.STOP,
                LifecycleStatus.PASSED,
                request.handle.task_id,
                request.handle.candidate_id,
            ),
            stopped=True,
        )

    def pass_command(stages: list[Stage], name: str, _command: list[str], _cwd: Path, *, maven: bool = False) -> None:
        assert maven
        stages.append(Stage(name=name, status="passed", durationMs=1))

    monkeypatch.setattr(builder, "_run_stage", pass_command)
    monkeypatch.setattr(OpenJiuwenJavaBackend, "build", build_backend)
    monkeypatch.setattr(OpenJiuwenJavaBackend, "start", start_backend)
    monkeypatch.setattr(OpenJiuwenJavaBackend, "health", health_backend)
    monkeypatch.setattr(OpenJiuwenJavaBackend, "invoke", invoke_backend)
    monkeypatch.setattr(OpenJiuwenJavaBackend, "collect_evidence", evidence_backend)
    monkeypatch.setattr(OpenJiuwenJavaBackend, "stop", stop_backend)
    return events


def _sandbox_request(*, expected: object = None) -> SandboxEvaluationRequest:
    expected_value = {} if expected is None else expected
    return SandboxEvaluationRequest(
        request_id="builder-sandbox-evaluation",
        mode=EvaluationMode.TUNING,
        actor_id="builder-test",
        purpose="verify Builder lifecycle integration",
        pack=EvaluationPack(
            pack_id="builder-test-pack",
            version="1.0.0",
            partitions=(
                DatasetPartition(
                    dataset_id="regression",
                    version="1.0.0",
                    purpose=DatasetPurpose.REGRESSION,
                    source=CandidateResourceRef(
                        kind="workspace-path",
                        locator="evaluation/input/regression.json",
                        sha256="a" * 64,
                    ),
                    authorized=True,
                    authorization_id="builder-test-authorization",
                    declared_sample_count=2,
                    samples=(
                        EvaluationSample(
                            sample_id="sample-1",
                            payload={"message": "sanitized request one"},
                            expected=expected_value,
                        ),
                        EvaluationSample(
                            sample_id="sample-2",
                            payload={"message": "sanitized request two"},
                            expected=expected_value,
                        ),
                    ),
                ),
            ),
            metrics=(MetricSpec(name="accuracy", kind=MetricKind.ACCURACY),),
            acceptance=RuleGroup(
                logic="all",
                rules=(
                    AcceptanceRule(
                        metric="accuracy",
                        operator=RuleOperator.GTE,
                        threshold=1.0,
                    ),
                ),
            ),
        ),
    )


@pytest.mark.parametrize("failure_stage", ["start", "health", "invoke"])
def test_lifecycle_failure_writes_report_and_collects_evidence_before_stop(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    failure_stage: str,
) -> None:
    builder, solution, assets = _builder_fixture(
        tmp_path, monkeypatch, profile_id="customer-self-service"
    )
    events = _install_lifecycle_failure_fixture(builder, monkeypatch, failure_stage=failure_stage)
    output = builder.project_root / "build" / "failure"

    expected_error = {
        "start": "candidate Backend start",
        "health": "candidate Backend health",
        "invoke": "candidate Backend protocol invocation",
    }[failure_stage]
    with pytest.raises(RuntimeError, match=expected_error):
        builder.build(solution, output, verify=True)

    report = json.loads((output / "build-report.json").read_text(encoding="utf-8"))
    assert report["status"] == "failed"
    assert any(
        stage["name"]
        == f"candidate_{'protocol_' if failure_stage == 'invoke' else ''}{failure_stage}"
        and stage["status"] == "failed"
        for stage in report["stages"]
    )
    if failure_stage == "start":
        assert events == ["start"]
        assert report["lifecycleEvidenceRefs"] == []
    else:
        assert events[-2:] == ["evidence", "stop"]
        assert report["lifecycleEvidenceRefs"][0]["locator"].endswith(
            "runtime/evidence/lifecycle.json"
        )


@pytest.mark.parametrize(
    "failure_stage",
    ["harness_tests", "harness_install", "candidate_asset_tests"],
)
def test_command_stage_failure_writes_report(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    failure_stage: str,
) -> None:
    builder, solution, assets = _builder_fixture(
        tmp_path, monkeypatch, profile_id="customer-self-service"
    )
    _install_lifecycle_failure_fixture(builder, monkeypatch, failure_stage="none")
    output = builder.project_root / "build" / "command-failure"

    def command_stage(stages: list[Stage], name: str, _command: list[str], _cwd: Path, *, maven: bool = False) -> None:
        assert maven
        status = "failed" if name == failure_stage else "passed"
        stages.append(
            Stage(
                name=name,
                status=status,
                durationMs=1,
                detail=f"{name} fixture {status}.",
            )
        )
        if name == "harness_install" and failure_stage == "candidate_asset_tests":
            marker = output / "candidate/agents/domain-runtime/pom.xml"
            marker.parent.mkdir(parents=True, exist_ok=True)
            marker.write_text(
                "<project>"
                "<modelVersion>4.0.0</modelVersion>"
                "<groupId>example</groupId>"
                "<artifactId>domain-runtime</artifactId>"
                "<version>1.0</version>"
                "<build><plugins><plugin><configuration>"
                "<mainClass>example.Main</mainClass>"
                "<finalName>domain-runtime</finalName>"
                "</configuration></plugin></plugins></build>"
                "</project>",
                encoding="utf-8",
            )
        if status == "failed":
            raise RuntimeError(f"{name} failed")

    monkeypatch.setattr(builder, "_run_stage", command_stage)

    with pytest.raises(RuntimeError, match=failure_stage):
        builder.build(solution, output, verify=True)

    report = json.loads((output / "build-report.json").read_text(encoding="utf-8"))
    assert report["status"] == "failed"
    failed = [stage for stage in report["stages"] if stage["status"] == "failed"]
    assert [stage["name"] for stage in failed] == [failure_stage]
    assert all(stage["name"] != "candidate_start" for stage in report["stages"])


def test_builder_runs_sandbox_evaluation_in_same_candidate_lifecycle(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    builder, solution, assets = _builder_fixture(
        tmp_path, monkeypatch, profile_id="customer-self-service"
    )
    events = _install_lifecycle_failure_fixture(builder, monkeypatch, failure_stage="none")
    output = builder.project_root / "build" / "sandbox-success"

    result = builder.build(
        solution,
        output,
        verify=True,
        sandbox_evaluation=_sandbox_request(),
    )

    assert events == ["start", "health", "invoke", "invoke", "invoke", "evidence", "stop"]
    assert result.evaluation_report_path == output / "evaluation/evaluation-report.json"
    evaluation_report = json.loads(result.evaluation_report_path.read_text(encoding="utf-8"))
    assert evaluation_report["conclusion"] == "passed"
    assert evaluation_report["progress"] == {"completed": 2, "total": 2}
    assert "sanitized request" not in result.evaluation_report_path.read_text(encoding="utf-8")
    build_report = json.loads(result.report_path.read_text(encoding="utf-8"))
    assert build_report["verification"]["sandboxCandidateEvaluationExecuted"] is True
    assert build_report["verification"]["sandboxCandidateEvaluationStatus"] == "passed"
    assert build_report["sandboxEvaluationReportRef"]["locator"] == (
        "evaluation/evaluation-report.json"
    )


def test_builder_sandbox_quality_failure_writes_report_and_stops(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    builder, solution, assets = _builder_fixture(
        tmp_path, monkeypatch, profile_id="customer-self-service"
    )
    events = _install_lifecycle_failure_fixture(builder, monkeypatch, failure_stage="none")
    output = builder.project_root / "build" / "sandbox-failure"

    with pytest.raises(RuntimeError, match="Sandbox Evaluation did not pass"):
        builder.build(
            solution,
            output,
            verify=True,
            sandbox_evaluation=_sandbox_request(expected={"route": "expected"}),
        )

    assert events[-2:] == ["evidence", "stop"]
    report = json.loads((output / "build-report.json").read_text(encoding="utf-8"))
    assert report["status"] == "failed"
    assert report["verification"]["sandboxCandidateEvaluationExecuted"] is True
    assert report["verification"]["sandboxCandidateEvaluationStatus"] == "failed"
    assert report["sandboxEvaluationReportRef"]["locator"] == ("evaluation/evaluation-report.json")


def test_builder_snapshot_failure_does_not_start_candidate_and_writes_report(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    builder, solution, assets = _builder_fixture(
        tmp_path, monkeypatch, profile_id="customer-self-service"
    )
    events = _install_lifecycle_failure_fixture(builder, monkeypatch, failure_stage="none")
    request = _sandbox_request()
    original = request.pack.partitions[0]
    overlapping = original.model_copy(
        update={
            "dataset_id": "optimization",
            "purpose": DatasetPurpose.OPTIMIZATION,
        }
    )
    request = request.model_copy(
        update={"pack": request.pack.model_copy(update={"partitions": (original, overlapping)})}
    )
    output = builder.project_root / "build" / "snapshot-failure"

    with pytest.raises(ValueError, match="sample identity overlaps"):
        builder.build(
            solution,
            output,
            verify=True,
            sandbox_evaluation=request,
        )

    assert events == []
    report = json.loads((output / "build-report.json").read_text(encoding="utf-8"))
    assert report["status"] == "failed"
    assert report["verification"]["sandboxCandidateEvaluationExecuted"] is False
    assert any(
        stage["name"] == "evaluation_snapshot" and stage["status"] == "failed"
        for stage in report["stages"]
    )


def test_builder_without_sandbox_input_keeps_legacy_evaluation_status(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    builder, solution, assets = _builder_fixture(
        tmp_path, monkeypatch, profile_id="customer-self-service"
    )
    _install_lifecycle_failure_fixture(builder, monkeypatch, failure_stage="none")
    output = builder.project_root / "build" / "legacy-path"

    result = builder.build(solution, output, verify=True)

    report = json.loads(result.report_path.read_text(encoding="utf-8"))
    assert result.evaluation_report_path is None
    assert report["sandboxEvaluationReportRef"] is None
    assert report["verification"]["sandboxCandidateEvaluationExecuted"] is False
    assert report["verification"]["sandboxCandidateEvaluationStatus"] == "not-executed"


def test_asset_closure_digest_ignores_time_and_optimization_facts() -> None:
    from finance_solution_builder.builder import SolutionBuilder

    first = {
        "schemaVersion": "finance-solution-lock.v1",
        "solutionId": "demo",
        "resolvedAt": "2026-01-01T00:00:00Z",
        "assets": [{"id": "asset", "version": "1"}],
    }
    second = {
        **first,
        "resolvedAt": "2026-01-02T00:00:00Z",
        "optimization": {"parentLockDigest": "a" * 64},
    }
    assert SolutionBuilder.asset_closure_digest(first) == SolutionBuilder.asset_closure_digest(
        second
    )


def test_lock_records_parent_candidate_and_verified_overlay(tmp_path) -> None:
    builder = SolutionBuilder(tmp_path / "builder", tmp_path / "harness")
    builder._git_remote = lambda _repo: "https://example.invalid/harness.git"
    solution = {"solutionId": "demo", "modelRef": "model-1"}
    asset = ResolvedAsset("asset", "1", "repo://asset", "resolved", "sha256:" + "a" * 64)
    runtime = {
        "template": ResolvedAsset(
            "template", "1", "repo://template", "resolved", "sha256:" + "b" * 64
        ),
        "integration": ResolvedAsset(
            "integration", "1", "repo://integration", "resolved", "sha256:" + "c" * 64
        ),
    }
    started = datetime(2026, 1, 1, tzinfo=UTC)
    base = builder._lock(
        solution,
        "https://github.com/example/harness.git",
        "revision-1",
        [asset],
        runtime,
        started,
    )
    closure = builder.asset_closure_digest(base)
    overlay = CandidateOverlay(
        overlay_id="overlay-1",
        round_index=1,
        parent_candidate_id="candidate-1",
        parent_lock_digest="d" * 64,
        asset_closure_digest=closure,
        root_ref=ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="overlays/one"),
        files=(OverlayFile(path="src/Main.java", digest="e" * 64),),
    )
    revised = builder._lock(
        solution,
        "https://github.com/example/harness.git",
        "revision-1",
        [asset],
        runtime,
        started,
        candidate_overlay=overlay,
    )
    assert revised["optimization"]["parentCandidateId"] == "candidate-1"
    assert revised["optimization"]["parentLockDigest"] == "d" * 64
    assert builder.asset_closure_digest(revised) == closure


def test_lock_rejects_overlay_from_another_asset_closure(tmp_path) -> None:
    builder = SolutionBuilder(tmp_path / "builder", tmp_path / "harness")
    builder._git_remote = lambda _repo: "https://example.invalid/harness.git"
    asset = ResolvedAsset("asset", "1", "repo://asset", "resolved", "sha256:" + "a" * 64)
    runtime = {"template": asset, "integration": asset}
    overlay = CandidateOverlay(
        overlay_id="overlay-1",
        round_index=1,
        parent_candidate_id="candidate-1",
        parent_lock_digest="d" * 64,
        asset_closure_digest="f" * 64,
        root_ref=ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="overlays/one"),
        files=(OverlayFile(path="src/Main.java", digest="e" * 64),),
    )
    with pytest.raises(ValueError, match="asset_closure_mismatch"):
        builder._lock(
            {"solutionId": "demo", "modelRef": "model-1"},
            "https://github.com/example/harness.git",
            "revision-1",
            [asset],
            runtime,
            datetime(2026, 1, 1, tzinfo=UTC),
            candidate_overlay=overlay,
        )
