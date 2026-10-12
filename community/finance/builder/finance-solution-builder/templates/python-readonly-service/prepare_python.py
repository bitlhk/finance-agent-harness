"""Offline installation from the delivered, hash-locked wheels on the build platform."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent
if not (3, 11) <= sys.version_info[:2] < (3, 14):
    raise SystemExit('PYTHON_311_TO_313_REQUIRED')
lock = json.loads((root / 'service-lock.json').read_text())
for name, expected in lock['files'].items():
    path = (root / name).resolve()
    if not path.is_relative_to(root) or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise SystemExit('ARTIFACT_MISMATCH')
if (root / '.venv').exists():
    raise SystemExit('ENVIRONMENT_ALREADY_EXISTS')
environment = dict(os.environ)
for name in ('PYTHONPATH', 'PYTHONHOME', 'VIRTUAL_ENV', 'UV_PROJECT_ENVIRONMENT'):
    environment.pop(name, None)
subprocess.run([sys.executable, '-m', 'venv', str(root / '.venv')], check=True, env=environment)
python = root / '.venv/bin/python'
subprocess.run([str(python), '-m', 'pip', 'install', '--no-index', '--require-hashes',
               '--find-links', str(root / 'python-wheels'), '-r', str(root / 'python-requirements.lock')],
               check=True, env=environment)
subprocess.run([str(python), '-m', 'pip', 'check'], check=True, env=environment)
