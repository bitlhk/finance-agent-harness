from pathlib import Path
import subprocess


def test_history_business_labels():
    result = subprocess.run(["node", "engineering/test_history_labels.mjs"],
                            cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
