"""Frozen artifact checks shared by the owned Jobs launcher and finance Backends."""
import json
import os
from pathlib import Path

from finance_builder_base.runtime import require_path_within

from .service import MAIN, digest
from .component_assembly import AssemblyRejected


AGENTSCOPE_ENTRYPOINT = {
    "backendId": "agentscope-java", "protocol": "finance-chat",
    "mainClass": MAIN, "artifactKind": "directory-manifest",
}


def runtime_presentation(runtime, runtime_id):
    value = {key: runtime[key] for key in ("label", "language", "version", "architectureLabel", "description")
             if isinstance(runtime.get(key), str) and runtime[key]}
    value["runtimeId"] = runtime_id
    value.setdefault("label", runtime_id)
    return value


def checked_file(root, relative):
    root = Path(root).absolute()
    path = root / relative
    resolved = require_path_within(path, root)
    if (not resolved.is_file() or any(item.is_symlink() for item in (path, *path.parents)
                                    if item.is_relative_to(root))):
        raise ValueError("ARTIFACT_FILE_MISMATCH")
    return resolved


def require_reuse_runtime(candidate, runtime):
    """User-level reuse is only supported by the original Java Host, never its Python bridge."""
    if (runtime.get("family"), runtime.get("language")) != ("openjiuwen", "java"):
        raise AssemblyRejected("MANAGED_RUNTIME_REUSE_UNSUPPORTED")
    lock = json.loads(checked_file(candidate, "service-lock.json").read_bytes())
    if lock.get("runtime") != runtime:
        raise AssemblyRejected("MANAGED_RUNTIME_REUSE_UNSUPPORTED")


def jar_inventory(candidate):
    """Only the main artifact and flat dependencies may enter the managed JVM."""
    candidate = Path(candidate)
    target = candidate / "target"
    if target.is_symlink() or not target.is_dir():
        raise ValueError("MANAGED_CLASSPATH_MISMATCH")
    files = {}
    for path in sorted(target.rglob("*")):
        if path.is_symlink():
            raise ValueError("MANAGED_CLASSPATH_MISMATCH")
        if path.is_file() and path.suffix.lower() == ".jar":
            relative = path.relative_to(candidate)
            if (relative.parent not in (Path("target"), Path("target/dependency"))
                    or "openjiuwen" in path.name.lower() or "agent-core-java" in path.name.lower()):
                raise ValueError("MANAGED_CLASSPATH_MISMATCH")
            files[relative.as_posix()] = digest(checked_file(candidate, relative))
    if sum(Path(name).parent == Path("target") for name in files) != 1:
        raise ValueError("CANDIDATE_JAR_MISSING")
    return files


def locked_classpath(candidate, files):
    actual = jar_inventory(candidate)
    locked = {name: sha for name, sha in files.items() if Path(name).suffix.lower() == ".jar"}
    if actual != locked:
        raise ValueError("MANAGED_CLASSPATH_MISMATCH")
    ordered = sorted(actual, key=lambda name: (Path(name).parent != Path("target"), name))
    return os.pathsep.join(str(checked_file(candidate, name)) for name in ordered)


def locked_java(manifest):
    """Prefer the executable used to compile this frozen, local candidate."""
    value = manifest.get("javaExecutable")
    if value is None:
        return "java"
    path = Path(value)
    if not path.is_absolute() or path.name != "java" or not path.is_file() or not os.access(path, os.X_OK):
        raise ValueError("MANAGED_JAVA_RUNTIME_MISMATCH")
    return str(path)


def launch_command(candidate, manifest):
    candidate = Path(candidate)
    runtime = manifest["runtime"]
    family = runtime.get("family", "openjiuwen")
    if family == "agentscope" or "managedEntrypoint" in manifest or "runtimeBinding" in manifest:
        from .managed_runtime_support import RUNTIME
        binding = manifest.get("runtimeBinding", {})
        if (manifest.get("managedEntrypoint") != AGENTSCOPE_ENTRYPOINT
                or runtime != RUNTIME or runtime != binding.get("runtime") or binding.get("backendId") != "agentscope-java"
                or binding.get("runtimeVersion") != "2.0.3" or runtime.get("language") != "java"
                or binding.get("invocationProtocol") != "finance-chat"
                or manifest.get("verification") != "COMPILED"):
            raise ValueError("MANAGED_ARTIFACT_BINDING_MISMATCH")
        files = manifest.get("files", {})
        if not files:
            raise ValueError("ARTIFACT_FILE_MISMATCH")
        for relative, expected in files.items():
            if digest(checked_file(candidate, relative)) != expected:
                raise ValueError("ARTIFACT_FILE_MISMATCH")
        classpath = locked_classpath(candidate, files)
        return [locked_java(manifest), "-Xmx512m", "-cp", classpath, MAIN, str(candidate)]
    elif family != "openjiuwen":
        raise ValueError("MANAGED_ARTIFACT_BINDING_MISMATCH")
    jars = list((candidate / "target").glob("*.jar"))
    if len(jars) != 1:
        raise ValueError("CANDIDATE_JAR_MISSING")
    if runtime["language"] == "python":
        return [str(candidate / ".venv/bin/python"), "-I", str(candidate / "start_python.py")]
    if runtime["language"] != "java":
        raise ValueError("MANAGED_ARTIFACT_BINDING_MISMATCH")
    return [locked_java(manifest), "-Xmx512m", "-cp", str(jars[0]) + os.pathsep + str(candidate / "target/dependency/*"), MAIN, str(candidate)]
