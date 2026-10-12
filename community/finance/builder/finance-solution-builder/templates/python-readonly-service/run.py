"""Portable launcher for Python only. Missing environments never fall back to Java."""
import os
from pathlib import Path

root = Path(__file__).resolve().parent
python = root / '.venv/bin/python'
if not python.is_file():
    raise SystemExit('Run prepare_python.py with a compatible Python first')
os.execv(str(python), [str(python), '-I', str(root / 'start_python.py')])
