"""One-file guarded release; does not restart agents or change CSP."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

target = Path('/opt/finance-studio/releases/20260922-agentscope-task-r6/release/builder/community/finance/builder/finance-solution-builder/browser/execution-history.js')
backup = Path('/root/backups/finance-waterfall-csp-20261006')
old = 'b8488f8bd7d07d11917b84c11d1510486ac3473b45ee0c7573a5c0137ed31d6b'
new = 'dc7376690b90c8b5cf56f970a87250fb529fe43ce61d2e780763506c7b1768c2'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

action = sys.argv[1]
if action == 'publish':
    source = Path(sys.argv[2])
    assert digest(target) == old, 'Production changed; refuse overwrite'
    assert digest(source) == new, 'Candidate digest mismatch'
    backup.mkdir(mode=0o700, parents=True, exist_ok=False)
    shutil.copy2(target, backup / 'execution-history.js')
elif action == 'rollback':
    source = backup / 'execution-history.js'
    assert digest(target) == new, 'Newer production change; refuse rollback'
    assert digest(source) == old, 'Backup digest mismatch'
else:
    raise ValueError('Expected publish or rollback')

stat = target.stat()
temporary = target.with_name('execution-history.js.csp-next')
with temporary.open('xb') as output:
    output.write(source.read_bytes())
    output.flush()
    os.fsync(output.fileno())
os.chmod(temporary, stat.st_mode)
os.chown(temporary, stat.st_uid, stat.st_gid)
assert digest(target) == (old if action == 'publish' else new), 'Concurrent change'
os.replace(temporary, target)
assert digest(target) == (new if action == 'publish' else old)
result = {'action': action, 'path': str(target), 'sha256': digest(target), 'backup': str(backup), 'serviceRestarted': False, 'cspChanged': False}
with (backup / (action + '.json')).open('x') as output:
    json.dump(result, output, indent=2)
print(json.dumps(result))
