import json
import hashlib
from pathlib import Path

import pytest

from finance_solution_builder.qualification_access import require_qualification_access


def test_reads_qualification_and_all_referenced_evidence(tmp_path):
    evidence = tmp_path / "checks.json"
    evidence.write_text("{}")
    qualification = tmp_path / "qualification.json"
    qualification.write_text(json.dumps({"collectors": [{"evidence": [{"file": evidence.name}]}]}))

    require_qualification_access({
        "FH_OBSERVATION_QUALIFICATION_FILE": str(qualification),
        "FH_OBSERVATION_QUALIFICATION_SHA256": hashlib.sha256(qualification.read_bytes()).hexdigest(),
    })


def test_checks_each_qualification_digest_contract(tmp_path):
    qualification = tmp_path / "qualification.json"
    qualification.write_text("{}")
    digest = hashlib.sha256(qualification.read_bytes()).hexdigest()

    require_qualification_access({
        "FH_OBSERVATION_QUALIFICATION_FILE": str(qualification),
        "FH_OBSERVATION_QUALIFICATION_SHA256": digest,
        "FH_AGENTSCOPE_QUALIFICATION_FILE": str(qualification),
        "FH_AGENTSCOPE_QUALIFICATION_SHA256": "sha256:" + digest,
    })
    with pytest.raises(ValueError, match="RUNTIME_QUALIFICATION_DIGEST_MISMATCH"):
        require_qualification_access({
            "FH_AGENTSCOPE_QUALIFICATION_FILE": str(qualification),
            "FH_AGENTSCOPE_QUALIFICATION_SHA256": digest,
        })


def test_openjiuwen_does_not_depend_on_agentscope_native_qualification(tmp_path):
    qualification = tmp_path / "host.json"
    qualification.write_text("{}")
    require_qualification_access({
        "FH_OBSERVATION_QUALIFICATION_FILE": str(qualification),
        "FH_OBSERVATION_QUALIFICATION_SHA256": hashlib.sha256(qualification.read_bytes()).hexdigest(),
        "FH_AGENTSCOPE_QUALIFICATION_FILE": str(tmp_path / "missing-native.json"),
    }, runtime_id="openjiuwen-java-0.1.15")


def test_rejects_missing_referenced_evidence_before_build(tmp_path):
    qualification = tmp_path / "qualification.json"
    qualification.write_text(json.dumps({"collectors": [{"evidence": [{"file": "missing.json"}]}]}))

    with pytest.raises(ValueError, match="RUNTIME_QUALIFICATION_UNREADABLE"):
        require_qualification_access({
            "FH_OBSERVATION_QUALIFICATION_FILE": str(qualification),
            "FH_OBSERVATION_QUALIFICATION_SHA256": hashlib.sha256(qualification.read_bytes()).hexdigest(),
        })


def test_rejects_missing_native_qualification_before_build(tmp_path):
    with pytest.raises(ValueError, match="RUNTIME_QUALIFICATION_UNREADABLE"):
        require_qualification_access({"FH_AGENTSCOPE_QUALIFICATION_FILE": str(tmp_path / "missing.json")})


def test_rejects_missing_digest_before_build(tmp_path):
    qualification = tmp_path / "qualification.json"
    qualification.write_text("{}")
    with pytest.raises(ValueError, match="RUNTIME_QUALIFICATION_DIGEST_MISSING"):
        require_qualification_access({"FH_OBSERVATION_QUALIFICATION_FILE": str(qualification)})


def test_translates_permission_failure_to_stable_error(monkeypatch, tmp_path):
    qualification = tmp_path / "qualification.json"
    qualification.write_text("{}")
    original = Path.is_file

    def denied(path):
        if path == qualification:
            raise PermissionError(path)
        return original(path)

    monkeypatch.setattr(Path, "is_file", denied)
    with pytest.raises(ValueError, match="RUNTIME_QUALIFICATION_UNREADABLE"):
        require_qualification_access({
            "FH_OBSERVATION_QUALIFICATION_FILE": str(qualification),
            "FH_OBSERVATION_QUALIFICATION_SHA256": hashlib.sha256(qualification.read_bytes()).hexdigest(),
        })
