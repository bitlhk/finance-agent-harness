import pytest
from engineering.check_snapshot_capacity import inspect


def test_capacity_warns_before_full_without_reading_or_deleting(tmp_path):
    folder = tmp_path / ("a" * 64) / "model-snapshots"
    folder.mkdir(parents=True)
    for i in range(4):
        (folder / f"{i}.json").write_bytes(b"not JSON and never parsed")
    assert inspect(tmp_path, 5, 65536)["owners"][0]["state"] == "WARNING"
    assert inspect(tmp_path, 4, 65536)["owners"][0]["state"] == "FULL"
    assert not inspect(tmp_path, 1024, 67108864)["alert"]
    assert len(list(folder.iterdir())) == 4


def test_capacity_counts_bytes_and_ignores_expiry_markers(tmp_path):
    folder = tmp_path / ("a" * 64) / "model-snapshots"
    folder.mkdir(parents=True)
    (folder / "body.json").write_bytes(b"x" * 65536)
    (folder / "old.json.expired").write_bytes(b"x" * 65536)
    report = inspect(tmp_path, 128, 65536)
    assert report["alert"] and report["owners"][0]["files"] == 1
    assert report["owners"][0]["bytes"] == 65536


def test_capacity_refuses_symlinks_and_invalid_limits(tmp_path):
    with pytest.raises(ValueError): inspect(tmp_path, 0, 65536)
    (tmp_path / ("b" * 64)).symlink_to(tmp_path, target_is_directory=True)
    with pytest.raises(ValueError, match="UNSAFE"): inspect(tmp_path, 128, 65536)
