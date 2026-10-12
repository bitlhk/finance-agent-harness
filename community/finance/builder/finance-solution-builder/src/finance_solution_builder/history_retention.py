"""Local operator retention. Dry-run by default; never calls a model or a bank."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
from .execution_history import ExecutionHistory


def prune(data_dir, owner, days=30, apply=False, now=None):
    if not 1 <= days <= 3650:
        raise ValueError("INVALID_RETENTION_DAYS")
    import fcntl  # The current shared-file Host deployment is Linux/Unix only.
    store = ExecutionHistory(Path(data_dir))
    directory = store.directory(owner)
    report = {"dryRun": not apply, "days": days, "eligible": [], "deleted": [], "skipped": 0}
    if not directory.exists():
        return report
    cutoff = (now or datetime.now(timezone.utc)) - timedelta(days=days)
    fd = os.open(directory / '.quota.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'r+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        for path in directory.glob('*.jsonl'):
            try:
                detail = store.get(owner, path.stem)
                header = json.loads(store._read(path).splitlines()[0])
                created = datetime.fromisoformat(header['createdAt'].replace('Z', '+00:00'))
                pid = header.get('pid')
                if created.tzinfo is None or created >= cutoff or type(pid) is not int or pid <= 0:
                    report['skipped'] += 1
                    continue
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    pass
                except PermissionError:
                    report['skipped'] += 1
                    continue
                else:
                    report['skipped'] += 1
                    continue
                if detail['status'] == 'UNFINISHED':
                    report['skipped'] += 1
                    continue
                files = [directory / (path.stem + suffix) for suffix in
                         ('.service-lock.json', '.runtime-lock.json', '.recovery.json', '.client-timing.json', '.jsonl')]
                if any(p.is_symlink() for p in files):
                    report['skipped'] += 1
                    continue
                report['eligible'].append(path.stem)
                if apply:
                    for item in files:
                        item.unlink(missing_ok=True)
                    report['deleted'].append(path.stem)
            except (OSError, ValueError, KeyError, TypeError):
                report['skipped'] += 1
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--data-dir', type=Path, required=True)
    parser.add_argument('--owner', required=True, help='Trusted local operator workspace identity, not a customer Grant')
    parser.add_argument('--days', type=int, default=30)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(prune(args.data_dir, args.owner, args.days, args.apply), indent=2))
