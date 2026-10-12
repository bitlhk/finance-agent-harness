"""Offline HTTP fixture lifecycle tests, not native Runtime acceptance."""
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import urllib.request

import pytest

from finance_solution_builder.eval_launcher_service import Launcher
from test_eval_launcher_service import Backend, preset, source, wait_until


PROJECT = Path(__file__).resolve().parents[1]


@pytest.fixture
def native(monkeypatch):
    monkeypatch.syspath_prepend(str(PROJECT / "engineering"))
    return importlib.import_module("verify_eval_launcher_native")


@pytest.mark.parametrize("seconds", [0, 1201, True])
def test_fixture_lifetime_is_bounded(native, tmp_path, seconds):
    with pytest.raises(ValueError, match="SERVE_SECONDS_INVALID"):
        native.serve_fixture(None, {}, {}, "owner", tmp_path / "info.json", seconds)


@pytest.mark.parametrize("stop_mode", ["file", "deadline"])
@pytest.mark.parametrize("live", [False, True])
def test_fixture_private_info_http_and_bounded_stop(native, tmp_path, stop_mode, live):
    presets = {"oj": preset(), "as": {**preset(), "runtime": {"type": "agentscope", "version": "2.0.3"}}}
    launcher = Launcher(Backend(), presets)
    path, stop = tmp_path / "info.json", tmp_path / "stop"
    errors = []
    def run():
        try:
            native.serve_fixture(launcher, presets, source(), "owner", path, 1 if stop_mode == "deadline" else 30, stop,
                                 live_model={"base": "http://127.0.0.1:1234/v1", "name": "test-model"} if live else None)
        except Exception as error:
            errors.append(error)
    worker = threading.Thread(target=run)
    began = time.monotonic()
    worker.start()
    try:
        wait_until(path.exists)
        info = json.loads(path.read_text())
        assert path.stat().st_mode & 0o777 == 0o600
        assert info["status"] == "READY" and len(info["token"]) >= 32
        assert info["modelId"] == "launcher-model" and info["skill"]["archiveBase64"]
        assert {key: value["path"] for key, value in info["runtimes"].items()} == {"oj": "oj/", "as": "as/"}
        assert info["baseUrl"].startswith("http://127.0.0.1:")
        assert info["realModel"] == ("LIVE" if live else "NOT_RUN")
        assert info["mode"] == ("LIVE_NATIVE_FIXTURE" if live else "CONTROLLED_NATIVE_FIXTURE")
        if live:
            assert info["modelHttpEvidence"] == "EXTERNAL_LOOPBACK_RELAY"
            assert info["modelInvocationStatus"] == "NOT_OBSERVED_BY_FIXTURE"
        for runtime in info["runtimes"]:
            req = urllib.request.Request(info["baseUrl"] + runtime + "/v1/runtime-capabilities",
                                         headers={"Authorization": "Bearer " + info["token"]})
            with urllib.request.urlopen(req, timeout=2) as response:
                assert json.load(response)["runtime"] == presets[runtime]["runtime"]
        if stop_mode == "file":
            stop.touch()
        worker.join(timeout=4)
        assert not worker.is_alive() and not errors
        assert json.loads(path.read_text())["status"] == "STOPPED"
        if stop_mode == "deadline":
            assert time.monotonic() - began >= 1
    finally:
        stop.touch()
        worker.join(timeout=4)
        launcher.close()


@pytest.mark.parametrize("base", ["http://127.0.0.1:1234/v1/", "https://localhost/v1"])
def test_live_model_loopback_options(native, base):
    assert native.live_model_options(base, "test-model", Path("info")) == {
        "base": base.rstrip("/"), "name": "test-model"}


@pytest.mark.parametrize("base", [
    "https://example.com/v1", "http://127.0.0.2/v1", "http://[::1]/v1",
    "http://localhost.evil/v1", "http://user:secret@localhost/v1",
    "http://localhost/v1?", "http://localhost/v1#", "http://localhost/v1?q=secret",
    "http://localhost:65536/v1", "http://localhost:0/v1", "file://localhost/v1",
    " http://localhost/v1", "http://localhost/\n", "http://localhost\\evil/v1",
])
def test_live_model_rejects_non_loopback_or_ambiguous_urls(native, base):
    with pytest.raises(ValueError, match="^LIVE_MODEL_LOOPBACK_URL_REQUIRED$"):
        native.live_model_options(base, "test-model", Path("info"))


@pytest.mark.parametrize("base,name,info", [
    (None, "test-model", Path("info")), ("http://localhost/v1", None, Path("info")),
    ("http://localhost/v1", "test-model", None),
])
def test_live_model_requires_pair_and_serve(native, base, name, info):
    with pytest.raises(ValueError, match="^LIVE_MODEL_REQUIRES_SERVE_AND_PAIRED_OPTIONS$"):
        native.live_model_options(base, name, info)


@pytest.mark.parametrize("name", ["", " model", "model\n", "x" * 181, "sk-secret", "https://secret"])
def test_live_model_name_validation(native, name):
    with pytest.raises(ValueError, match="^LIVE_MODEL_NAME_INVALID$"):
        native.live_model_options("http://localhost/v1", name, Path("info"))


def test_model_environment_default_unchanged_and_live_has_only_placeholder(native):
    before = dict(os.environ)
    assert native.live_model_options(None, None, None) is None
    controlled = native.fixture_model_environment("http://127.0.0.1:1234", None)
    assert controlled == {
        "FH_CONN_LAUNCHER_TEST_URL": "http://127.0.0.1:1234/v1",
        "FH_CONN_LAUNCHER_TEST_KEY": "controlled-test-not-a-secret",
        "FH_MODEL_URL": "http://127.0.0.1:1234/v1", "FH_MODEL_NAME": "controlled-eval-launcher",
        "FH_MODEL_KEY": "controlled-test-not-a-secret", "FH_MODEL_MODE": "CONTROLLED_TEST"}
    live = native.fixture_model_environment("http://127.0.0.1:1234", {
        "base": "http://localhost:5678/v1", "name": "approved-model"})
    assert live["FH_MODEL_MODE"] == "LIVE"
    assert live["FH_MODEL_URL"] == live["FH_CONN_LAUNCHER_TEST_URL"] == "http://localhost:5678/v1"
    assert live["FH_MODEL_KEY"] == live["FH_CONN_LAUNCHER_TEST_KEY"] == "loopback-relay-placeholder"
    assert live["FH_MODEL_NAME"] == "approved-model"
    assert not any(key.startswith("FH_BANK") for key in live)
    assert dict(os.environ) == before


def test_invalid_live_cli_rejected_before_bootstrap(tmp_path):
    output = tmp_path / "output"
    result = subprocess.run([sys.executable, str(PROJECT / "engineering/verify_eval_launcher_native.py"),
        "--harness", str(tmp_path), "--output", str(output),
        "--live-model-base", "http://localhost/v1", "--live-model-name", "test-model"],
        capture_output=True, text=True, timeout=10)
    assert result.returncode == 2
    assert "LIVE_MODEL_REQUIRES_SERVE_AND_PAIRED_OPTIONS" in result.stderr
    assert not output.exists()


def test_fixture_sigterm_runs_shutdown_and_preserves_private_info(tmp_path):
    info = tmp_path / "info.json"
    script = '''import sys
from pathlib import Path
from verify_eval_launcher_native import serve_fixture
from finance_solution_builder.eval_launcher_service import Launcher
from test_eval_launcher_service import Backend, preset, source
launcher = Launcher(Backend(), {"oj": preset()})
try:
    serve_fixture(launcher, {"oj": preset()}, source(), "owner", Path(sys.argv[1]), 30)
finally:
    launcher.close()
'''
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONPATH=os.pathsep.join((
        str(PROJECT / "engineering"), str(PROJECT / "tests"), str(PROJECT / "src"), str(PROJECT.parent / "base/src"))))
    child = subprocess.Popen([sys.executable, "-c", script, str(info)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        wait_until(info.exists)
        child.terminate()
        stdout, stderr = child.communicate(timeout=5)
        assert child.returncode == 0, stderr
        value = json.loads(info.read_text())
        assert value["status"] == "STOPPED" and value["token"] not in stdout + stderr
        assert info.stat().st_mode & 0o777 == 0o600
    finally:
        if child.poll() is None:
            child.kill()
            child.communicate(timeout=5)
