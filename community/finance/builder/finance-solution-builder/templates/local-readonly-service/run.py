"""Portable foreground launcher. Ctrl+C stops only this launcher's child process."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
jars = [p for p in (root / "target").glob("*.jar") if p.is_file()]
if len(jars) != 1:
    raise SystemExit("Build the candidate before starting it")
command = ["java", "-Xmx512m", "-cp", str(jars[0]) + os.pathsep + str(root / "target/dependency/*"),
           "io.github.bitlhk.finance.builder.local.LocalAgentHost", str(root)]
child = subprocess.Popen(command, cwd=root)
try:
    raise SystemExit(child.wait())
except KeyboardInterrupt:
    child.terminate()
    try:
        child.wait(timeout=15)
    except subprocess.TimeoutExpired:
        child.kill()
        child.wait()
