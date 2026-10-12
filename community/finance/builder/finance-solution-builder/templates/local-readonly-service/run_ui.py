"""Run the frozen terminal UI and native Host without the Builder repository."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parent
sys.path.insert(0, str(root / "portable"))
from finance_solution_builder.portable_ui import serve

if __name__ == "__main__": serve(root)
