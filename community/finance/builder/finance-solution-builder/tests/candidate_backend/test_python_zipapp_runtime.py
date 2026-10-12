"""Managed Python Candidate lifecycle failure and evidence tests."""

from __future__ import annotations

import json
from pathlib import Path

from finance_solution_builder.candidate_backend import (
    EvidenceRequest,
    HealthRequest,
    LifecycleStatus,
    ProtocolBinding,
    PythonZipAppManagedRuntime,
    StartRequest,
    StopRequest,
    verify_candidate_artifact,
)
from tests.candidate_backend.test_openjiuwen_python_zipapp import (
    _assemble_and_build,
    _backend,
)


def test_health_failure_cleans_up_python_process(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    built = _assemble_and_build(backend)
    assert built.descriptor is not None
    descriptor = built.descriptor.model_copy(
        update={"health": ProtocolBinding(protocol="http", path="/missing")}
    )
    runtime = PythonZipAppManagedRuntime(
        backend=descriptor.backend,
        delivery_root=backend.delivery_root,
        python=backend.python,
        health_timeout_seconds=0.5,
    )
    started = runtime.start(
        StartRequest(
            descriptor=descriptor,
            artifact_verification=verify_candidate_artifact(
                descriptor, backend.delivery_root
            ),
        )
    )
    assert started.handle is not None

    result = runtime.health(HealthRequest(handle=started.handle))

    assert result.outcome.status is LifecycleStatus.FAILED
    assert runtime.stop(StopRequest(handle=started.handle)).stopped is True


def test_python_lifecycle_evidence_excludes_environment_values(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    built = _assemble_and_build(backend)
    assert built.descriptor is not None
    marker_name = "DEMO_CREDENTIAL"
    marker_value = "private-fixture-value"
    runtime = PythonZipAppManagedRuntime(
        backend=built.descriptor.backend,
        delivery_root=backend.delivery_root,
        python=backend.python,
        health_timeout_seconds=10,
        environment={marker_name: marker_value},
    )
    started = runtime.start(
        StartRequest(
            descriptor=built.descriptor,
            artifact_verification=verify_candidate_artifact(
                built.descriptor, backend.delivery_root
            ),
        )
    )
    assert started.handle is not None
    assert runtime.health(HealthRequest(handle=started.handle)).ready is True

    evidence = runtime.collect_evidence(EvidenceRequest(handle=started.handle))
    evidence_path = backend.delivery_root / evidence.outcome.evidence_refs[0].locator
    runtime.stop(StopRequest(handle=started.handle))
    evidence_document = json.loads(evidence_path.read_text(encoding="utf-8"))

    assert marker_value not in evidence_path.read_text(encoding="utf-8")
    assert evidence_document["schemaVersion"] == "openjiuwen-python-lifecycle-evidence.v1"
