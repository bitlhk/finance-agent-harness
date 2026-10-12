"""Launch with this Candidate's hash-locked environment, not the Builder interpreter."""
import asyncio
import hashlib
import json
from pathlib import Path
import sys
import sysconfig

root = Path(__file__).resolve().parent
manifest = json.loads((root / 'service-lock.json').read_text())
environment = root / 'python-environment-lock.json'
if hashlib.sha256(environment.read_bytes()).hexdigest() != manifest['files']['python-environment-lock.json']:
    raise SystemExit('PYTHON_ENVIRONMENT_LOCK_MISMATCH')
site = Path(sysconfig.get_path('purelib')).resolve()
for name, expected in json.loads(environment.read_text())['files'].items():
    file = (site / name).resolve()
    if not file.is_relative_to(site) or not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != expected:
        raise SystemExit('PYTHON_INSTALLED_ARTIFACT_MISMATCH')
sys.path.insert(0, str(root))
from finance_python.host import serve

try:
    asyncio.run(serve(root))
except Exception:
    print('PYTHON_FINANCE_HOST_START_OR_STOP_FAILED', file=sys.stderr)
    sys.exit(1)
