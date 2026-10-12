import base64
import io
import json
import subprocess
import zipfile
import pytest
from finance_solution_builder.developer_kit import developer_kit
from finance_solution_builder.method_assets import sha
from finance_solution_builder.experience_contracts import ApiError


def git(root, *args):
    subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)


def test_pinned_source_only_no_environment_or_dirty_bytes(tmp_path):
    git(tmp_path, "init");(tmp_path / "pom.xml").write_text("original")
    (tmp_path / "secret.env").write_text("private-not-in-kit")
    git(tmp_path, "add", ".");git(tmp_path, "-c", "user.name=Test", "-c", "user.email=t@invalid", "commit", "-m", "fixture")
    (tmp_path / "pom.xml").write_text("uncommitted")
    response = developer_kit(tmp_path);raw = base64.b64decode(response["base64"])
    assert sha(raw) == response["sha256"]
    with zipfile.ZipFile(io.BytesIO(raw)) as package:
        assert "harness/secret.env" not in package.namelist()
        assert package.read("harness/pom.xml") == b"original"
        lock = json.loads(package.read("source-lock.json"))
        for path, digest in lock["files"].items():
            assert sha(package.read(path)) == digest
    assert developer_kit(tmp_path)["sha256"] == response["sha256"]


def test_unsafe_source_symlink_rejected(tmp_path):
    git(tmp_path, "init");(tmp_path / "pom.xml").symlink_to("/etc/passwd")
    git(tmp_path, "add", ".");git(tmp_path, "-c", "user.name=Test", "-c", "user.email=t@invalid", "commit", "-m", "fixture")
    with pytest.raises(ApiError, match="UNSAFE_ENTRY"):
        developer_kit(tmp_path)
