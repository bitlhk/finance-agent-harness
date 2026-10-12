from pathlib import Path
import subprocess


def test_shared_cashflow_cards():
    result = subprocess.run(["node", "engineering/test_cashflow_display.mjs"],
                            cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
