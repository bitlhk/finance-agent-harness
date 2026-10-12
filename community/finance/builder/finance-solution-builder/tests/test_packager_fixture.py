"""The test double must preserve the production Packager's deterministic ZIP identity."""
import importlib.util
import json
from pathlib import Path
import zipfile


def test_fixture_zip_is_independent_of_wall_clock(tmp_path, monkeypatch):
    path = Path(__file__).parent / "fixtures/business-assets/engineering/business_package.py"
    spec = importlib.util.spec_from_file_location("fixed_test_packager", path)
    packager = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(packager)
    (tmp_path / "skill").mkdir()
    (tmp_path / "skill/SKILL.md").write_text("# Synthetic method\n")
    (tmp_path / "package-source.json").write_text(json.dumps({"files": ["skill/SKILL.md"]}))
    first, second = tmp_path / "one.zip", tmp_path / "two.zip"
    monkeypatch.setattr(zipfile.time, "localtime", lambda *_: (2025, 1, 1, 0, 0, 0, 2, 1, -1))
    packager.build(tmp_path, first)
    monkeypatch.setattr(zipfile.time, "localtime", lambda *_: (2026, 9, 21, 0, 0, 4, 2, 1, -1))
    packager.build(tmp_path, second)
    assert first.read_bytes() == second.read_bytes()
    with zipfile.ZipFile(first) as archive:
        assert all(item.date_time == (1980, 1, 1, 0, 0, 0) for item in archive.infolist())
