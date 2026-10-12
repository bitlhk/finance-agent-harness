from pathlib import Path
import subprocess


def test_method_editor_async_and_dirty_state():
    project = Path(__file__).resolve().parents[1]
    result = subprocess.run(["node", "engineering/test_method_state.mjs"], cwd=project,
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "4 method state checks passed" in result.stdout


def test_asset_reading_projection():
    project=Path(__file__).resolve().parents[1]
    result=subprocess.run(['node','engineering/test_asset_reading.mjs'],cwd=project,capture_output=True,text=True,timeout=30)
    assert result.returncode==0,result.stdout+result.stderr
    assert '13 asset reading checks passed' in result.stdout


def test_asset_graph_projection():
    project=Path(__file__).resolve().parents[1]
    result=subprocess.run(['node','engineering/test_asset_graph.mjs'],cwd=project,capture_output=True,text=True,timeout=30)
    assert result.returncode==0,result.stdout+result.stderr
    assert 'PASS: projection' in result.stdout
