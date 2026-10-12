import json
import zipfile
import pytest

from finance_solution_builder.service import solution_for, validate_service
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.workbench import Jobs


@pytest.mark.parametrize("selection", [["wealth.facts.describe"], ["account.balance.query"], ["wealth.facts.describe", "account.balance.query"]])
def test_finite_compositions(selection):
    value = solution_for(selection, "agent-test")
    assert validate_service(value) == selection
    SolutionBuilder._validate_assembly_support(value)
    assert all("transfer" not in str(ref) for ref in value["capabilityPackRefs"])


@pytest.mark.parametrize("selection", [[], ["unknown"], ["account.balance.query", "account.balance.query"], [42], "wealth.facts.describe"])
def test_invalid_selections(selection):
    with pytest.raises(ValueError):
        solution_for(selection, "agent-test")


@pytest.mark.parametrize("mutation", ["runtime", "provider", "model", "profile", "source", "extra"])
def test_no_unvalidated_runtime_or_provider(mutation):
    value = solution_for(["wealth.facts.describe"], "agent-test")
    if mutation == "runtime":
        value["runtime"]["templateRef"] = "maven://fake:version:999"
    if mutation == "provider":
        value["providerRefs"][0]["id"] = "untrusted"
    if mutation == "model":
        value["modelRef"] = "fixture://silent-fallback"
    if mutation == "profile":
        value["profileRef"]["id"] = "admin"
    if mutation == "source":
        value["capabilityPackRefs"][0]["source"] = "repo://../../outside"
    if mutation == "extra":
        value["trusted"] = True
    with pytest.raises(ValueError):
        validate_service(value)


def test_jobs_have_owner_boundary_and_no_untracked_process_kill(tmp_path):
    jobs = Jobs(tmp_path, tmp_path)
    jobs.jobs["agent-one"] = {"id": "agent-one", "owner": "alice", "status": "BUILT", "capabilities": []}
    with pytest.raises(PermissionError):
        jobs.owned("agent-one", "bob")
    with pytest.raises(PermissionError):
        jobs.stop("agent-one", "bob")
    with pytest.raises(ValueError, match="NO_OWNED_PROCESS"):
        jobs.stop("agent-one", "alice")
    jobs.close()


def test_restart_does_not_adopt_a_pid_from_disk(tmp_path):
    records = tmp_path / "build/workbench-records"
    records.mkdir(parents=True)
    (records / "agent-old.json").write_text(json.dumps({"id":"agent-old", "owner":"owner", "status":"RUNNING", "port":1, "pid":1}))
    jobs = Jobs(tmp_path, tmp_path)
    assert jobs.jobs["agent-old"]["status"] == "UNMANAGED_AFTER_RESTART"
    assert jobs.processes == {}
    jobs.close()


def test_build_failure_retains_report_and_never_allows_start(tmp_path, monkeypatch):
    jobs = Jobs(tmp_path, tmp_path)
    monkeypatch.setattr(jobs.builder, "build", lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("private path and secret")))
    value = jobs.create({"capabilities":["wealth.facts.describe"],"runtime":"openjiuwen-java-0.1.15","model":"configured-model","connection":"local-simulator"}, "owner")
    jobs.queue.shutdown(wait=True)
    job = jobs.jobs[value["id"]]
    assert job["status"] == "FAILED"
    assert "secret" not in json.dumps(jobs.public(job))
    with pytest.raises(ValueError, match="BUILD_OR_STOP_REQUIRED"):
        jobs.start(job["id"], "owner")
    jobs.close()


def test_artifact_download_is_locked_owned_and_excludes_runtime_secrets(tmp_path):
    from finance_solution_builder.service import digest, write_json
    jobs=Jobs(tmp_path,tmp_path)
    job={"id":"agent-one","owner":"alice","status":"BUILT"}
    jobs.jobs[job["id"]]=job
    candidate=jobs.path(job["id"])/"candidate"
    candidate.mkdir(parents=True)
    (candidate/"run.py").write_text("print('portable')")
    (candidate/"access-policy.json").write_text("private policy")
    (candidate/"instance.json").write_text("private session")
    write_json(candidate/"service-lock.json",{"files":{"run.py":digest(candidate/"run.py")}})
    write_json(candidate.parent/"build-report.json",{"lockSha256":digest(candidate/"service-lock.json")})
    with pytest.raises(PermissionError):
        jobs.artifact(job["id"],"bob")
    with zipfile.ZipFile(jobs.artifact(job["id"],"alice")) as archive:
        assert set(archive.namelist())=={"agent-one/run.py","agent-one/service-lock.json"}
    (candidate/"run.py").write_text("changed")
    with pytest.raises(ValueError,match="ARTIFACT_FILE_MISMATCH"):
        jobs.artifact(job["id"],"alice")
    jobs.close()


def test_stopped_instance_cannot_return_old_chat(tmp_path, monkeypatch):
    jobs=Jobs(tmp_path,tmp_path)
    jobs.jobs["agent-one"]={"id":"agent-one","owner":"alice","status":"RUNNING"}
    jobs.sessions["agent-one"]="old-session"
    old=object()
    jobs.processes["agent-one"]=old
    def stopped(*args,**kwargs):
        jobs.processes.pop("agent-one")
        return 200,{"response":"old private data"}
    monkeypatch.setattr(jobs,"_request",stopped)
    code,result=jobs.talk("agent-one","alice","chat",{"query":"hello"})
    assert code==409 and result["clearConversation"] and "private" not in json.dumps(result)
    jobs.close()
