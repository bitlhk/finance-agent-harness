from __future__ import annotations

import hashlib
import json
from pathlib import Path

from finance_builder_base import ResourceKind, ResourceRef
from finance_builder_base.evaluation import EvaluationProgress, MetricValue
from finance_solution_builder.evaluation import (
    EvaluationSampleEvidence,
    SampleExecutionStatus,
)
from finance_solution_builder.optimization import (
    OptimizationRequest,
    OptimizationRecordStore,
    OptimizationResult,
    OptimizationRoundRecord,
    OptimizationStopReason,
    OptimizationUsage,
)
from finance_solution_builder.optimization.models import OptimizationRoundStatus
from finance_solution_builder.workbench_projection import OptimizationViewProjector
from tests.candidate_backend.support import descriptor
from tests.optimization import finding, report, request, snapshot


def write_json(root: Path, locator: str, value) -> ResourceRef:
    path = root / locator
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
    path.write_bytes(payload)
    return ResourceRef(
        kind=ResourceKind.WORKSPACE_PATH,
        locator=locator,
        sha256=hashlib.sha256(payload).hexdigest(),
    )


def baseline_candidate(root: Path):
    lock_ref = write_json(
        root, "solution-lock.json", {"solutionId": "wealth-demo"}
    )
    candidate = descriptor().model_copy(update={"lock_digest": lock_ref.sha256})
    candidate_ref = write_json(
        root, "candidate/candidate-descriptor.json", candidate.to_wire()
    )
    baseline = snapshot().model_copy(
        update={
            "candidate_id": candidate.candidate_id,
            "descriptor_digest": candidate.sha256(),
            "lock_digest": lock_ref.sha256,
            "artifact_digest": candidate.artifact.resource.sha256,
        }
    )
    return baseline, candidate_ref, lock_ref


def complete_binding(root: Path):
    baseline, candidate_ref, lock_ref = baseline_candidate(root)
    baseline_report = report(
        baseline, accuracy=0.8, passed=False, findings=()
    ).model_copy(
        update={
            "progress": EvaluationProgress(completed=2, total=3),
            "metrics": (
                MetricValue(
                    name="accuracy", value=0.8, numerator=2, denominator=3
                ),
            ),
            "samples": (
                EvaluationSampleEvidence(
                    sample_id="sample-1",
                    dataset_id="regression",
                    status=SampleExecutionStatus.PASSED,
                    matched=True,
                    duration_ms=12,
                    input_digest="1" * 64,
                    expected_digest="2" * 64,
                    actual_digest="3" * 64,
                ),
            ),
        }
    )
    optimization_request = request(baseline, findings=())
    store = OptimizationRecordStore(root / "optimization", root)
    request_ref = store.bind_request(optimization_request)
    result = OptimizationResult(
        request_id=optimization_request.request_id,
        best_snapshot=baseline,
        rounds=0,
        stop_reason=OptimizationStopReason.NO_EXECUTABLE_TARGET,
        final_usage=OptimizationUsage(
            rounds=0,
            duration_ms=25,
            cost_microunits=0,
            consecutive_no_improvement=0,
        ),
        record_refs=(),
    )
    result_ref = store.write_result(result)
    baseline_ref = write_json(
        root, "evaluation/baseline.json", baseline_report.to_wire()
    )
    return {
        "schema_version": "1.0",
        "candidate_descriptor_ref": candidate_ref.model_dump(mode="json"),
        "solution_lock_ref": lock_ref.model_dump(mode="json"),
        "request_ref": request_ref.model_dump(mode="json"),
        "baseline_evaluation_ref": baseline_ref.model_dump(mode="json"),
        "round_refs": [],
        "result_ref": result_ref.model_dump(mode="json"),
        "evaluation_refs": [],
        "proposal_refs": [],
    }


def project(root: Path, binding):
    return OptimizationViewProjector(root, "agent-one").project(
        binding,
        build_status="SUCCEEDED",
        created_at=1.0,
        selection={"capabilityIds": ["wealth.previsit-context.query"]},
    )


def blocked_round_binding(root: Path):
    binding = complete_binding(root)
    baseline_request = OptimizationRequest.model_validate(
        json.loads((root / binding["request_ref"]["locator"]).read_text())
    )
    issue = finding()
    optimization_request = request(baseline_request.baseline, findings=(issue,))
    binding["request_ref"] = write_json(
        root, "optimization/request.json", optimization_request.to_wire()
    ).model_dump(mode="json")
    binding["baseline_evaluation_ref"] = write_json(
        root,
        "evaluation/baseline.json",
        report(
            optimization_request.baseline,
            accuracy=0.8,
            passed=False,
            findings=(issue,),
        ).to_wire(),
    ).model_dump(mode="json")
    usage = OptimizationUsage(
        rounds=1,
        duration_ms=40,
        cost_microunits=1,
        consecutive_no_improvement=1,
    )
    record = OptimizationRoundRecord(
        request_id=optimization_request.request_id,
        round_index=1,
        parent_snapshot=optimization_request.baseline,
        finding_ids=(issue.finding_id,),
        status=OptimizationRoundStatus.BLOCKED,
        approval_fact="No authorized automatic correction was available.",
        usage=usage,
        best_candidate_id=optimization_request.baseline.candidate_id,
        best_lock_digest=optimization_request.baseline.lock_digest,
        stop_reason=OptimizationStopReason.NO_IMPROVEMENT,
    )
    round_ref = write_json(
        root, "optimization/round-001.json", record.to_wire()
    )
    result = OptimizationResult(
        request_id=optimization_request.request_id,
        best_snapshot=optimization_request.baseline,
        rounds=1,
        stop_reason=OptimizationStopReason.NO_IMPROVEMENT,
        final_usage=usage,
        record_refs=(round_ref,),
    )
    binding["round_refs"] = [round_ref.model_dump(mode="json")]
    binding["result_ref"] = write_json(
        root, "optimization/result.json", result.to_wire()
    ).model_dump(mode="json")
    return binding


def test_missing_optimization_is_explicitly_not_executed(tmp_path: Path) -> None:
    view = project(tmp_path, None)
    assert view["status"] == "not-executed"
    assert view["nextAction"]["kind"] == "run-evaluation"
    assert view["result"] is None


def test_verified_projection_exposes_only_safe_sample_summary(tmp_path: Path) -> None:
    view = project(tmp_path, complete_binding(tmp_path))

    assert view["schemaVersion"] == "finance-workbench-optimization-view.v1"
    assert view["status"] == "completed"
    assert view["verifiability"] == {"status": "verified", "issues": []}
    assert view["evaluation"]["progress"] == {
        "completed": 2,
        "total": 3,
        "phase": "executing",
    }
    assert view["evaluation"]["samples"] == [
        {
            "sampleId": "sample-1",
            "datasetId": "regression",
            "status": "passed",
            "matched": True,
            "durationMillis": 12,
            "errorStage": None,
            "errorCode": None,
        }
    ]
    assert view["nextAction"]["kind"] == "review-stop"
    assert view["storyline"][0]["occurredAtEpochSeconds"] == 1.0
    assert view["storyline"][2]["occurredAtEpochSeconds"] is None
    serialized = json.dumps(view, ensure_ascii=False)
    assert "input_digest" not in serialized
    assert "expected_digest" not in serialized
    assert "actual_digest" not in serialized
    assert "prompt" not in serialized.lower()


def test_digest_drift_fails_closed_without_leaking_source_content(
    tmp_path: Path,
) -> None:
    binding = complete_binding(tmp_path)
    baseline_path = tmp_path / binding["baseline_evaluation_ref"]["locator"]
    baseline_path.write_text(
        '{"query": "private customer text"}\n', encoding="utf-8"
    )

    view = project(tmp_path, binding)

    assert view["status"] == "unverifiable"
    assert view["verifiability"]["issues"] == [
        "OPTIMIZATION_REFERENCE_DIGEST_MISMATCH"
    ]
    assert "private customer text" not in json.dumps(view, ensure_ascii=False)


def test_reference_escape_and_candidate_identity_drift_fail_closed(
    tmp_path: Path,
) -> None:
    binding = complete_binding(tmp_path)
    binding["request_ref"]["locator"] = "../outside.json"
    assert project(tmp_path, binding)["verifiability"]["issues"] == [
        "OPTIMIZATION_FACT_INVALID"
    ]

    drift_root = tmp_path / "drift"
    binding = complete_binding(drift_root)
    descriptor_ref = binding["candidate_descriptor_ref"]
    descriptor_path = drift_root / descriptor_ref["locator"]
    value = json.loads(descriptor_path.read_text(encoding="utf-8"))
    value["candidateId"] = "another-candidate"
    payload = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
    descriptor_path.write_bytes(payload)
    descriptor_ref["sha256"] = hashlib.sha256(payload).hexdigest()

    view = project(drift_root, binding)
    assert view["status"] == "unverifiable"
    assert view["verifiability"]["issues"] == [
        "CANDIDATE_DESCRIPTOR_IDENTITY_DRIFT"
    ]


def test_binding_rejects_duplicate_or_undigested_references(tmp_path: Path) -> None:
    duplicate = complete_binding(tmp_path / "duplicate")
    duplicate["evaluation_refs"] = [duplicate["baseline_evaluation_ref"]]
    assert project(tmp_path / "duplicate", duplicate)["verifiability"]["issues"] == [
        "OPTIMIZATION_FACT_INVALID"
    ]

    undigested = complete_binding(tmp_path / "undigested")
    undigested["request_ref"]["sha256"] = None
    assert project(tmp_path / "undigested", undigested)["verifiability"]["issues"] == [
        "OPTIMIZATION_FACT_INVALID"
    ]


def test_round_projection_preserves_diagnosis_and_stop_reason(tmp_path: Path) -> None:
    view = project(tmp_path, blocked_round_binding(tmp_path))

    assert view["status"] == "completed"
    assert view["rounds"][0]["findingIds"] == ["finding-1"]
    assert view["rounds"][0]["target"] is None
    assert view["rounds"][0]["stopReason"] == "no-improvement"
    assert view["result"]["stopReason"] == "no-improvement"
    assert [item["id"] for item in view["storyline"]][-1] == "decision"


def test_finding_text_that_is_not_public_safe_fails_closed(tmp_path: Path) -> None:
    private_text = "D:/private/customer-record.json"
    binding = blocked_round_binding(tmp_path)
    request_path = tmp_path / binding["request_ref"]["locator"]
    request_value = json.loads(request_path.read_text())
    request_value["findings"][0]["summary"] = private_text
    binding["request_ref"] = write_json(
        tmp_path, "optimization/request.json", request_value
    ).model_dump(mode="json")

    view = project(
        tmp_path,
        binding,
    )

    assert view["status"] == "unverifiable"
    assert view["verifiability"]["issues"] == ["OPTIMIZATION_FACT_INVALID"]
    assert private_text not in json.dumps(view, ensure_ascii=False)


def test_terminal_view_uses_the_bound_final_evaluation(tmp_path: Path) -> None:
    binding = complete_binding(tmp_path)
    optimization_request = OptimizationRequest.model_validate(
        json.loads((tmp_path / binding["request_ref"]["locator"]).read_text())
    )
    final_report = report(
        optimization_request.baseline,
        accuracy=1.0,
        passed=True,
        findings=(),
    )
    final_ref = write_json(
        tmp_path, "evaluation/final.json", final_report.to_wire()
    )
    result_path = tmp_path / binding["result_ref"]["locator"]
    result = OptimizationResult.model_validate(json.loads(result_path.read_text()))
    result = result.model_copy(
        update={"final_report_digest": final_report.sha256()}
    )
    binding["result_ref"] = write_json(
        tmp_path, "optimization/result.json", result.to_wire()
    ).model_dump(mode="json")
    binding["evaluation_refs"] = [final_ref.model_dump(mode="json")]

    view = project(tmp_path, binding)

    assert view["evaluation"]["conclusion"] == "passed"
    assert view["evaluation"]["metrics"][0]["value"] == 1.0
    assert final_ref.locator in {
        item["locator"] for item in view["storyline"][-1]["inputRefs"]
    }


def test_unreferenced_evaluation_report_fails_closed(tmp_path: Path) -> None:
    binding = complete_binding(tmp_path)
    optimization_request = OptimizationRequest.model_validate(
        json.loads((tmp_path / binding["request_ref"]["locator"]).read_text())
    )
    unused = report(
        optimization_request.baseline,
        accuracy=0.7,
        passed=False,
        findings=(),
    )
    binding["evaluation_refs"] = [
        write_json(
            tmp_path, "evaluation/unused.json", unused.to_wire()
        ).model_dump(mode="json")
    ]

    view = project(tmp_path, binding)

    assert view["status"] == "unverifiable"
    assert view["verifiability"]["issues"] == [
        "EVALUATION_REPORT_NOT_REFERENCED"
    ]
