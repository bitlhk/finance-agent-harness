"""Scenario-owned managed declarations; never a second Backend registry or grant."""
from copy import deepcopy
from hashlib import sha256
import json
import os
from pathlib import Path
import re
from typing import Literal

from finance_builder_base.runtime import require_path_within
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from .assets import tree_digest
from .component_assembly import AssemblyRejected, ComponentAssembly, _unique_object

INDEX = Path("managed-support/runtime-hosts.v0.1.json")
CANDIDATE_ENV = "FH_CANDIDATE_RUNTIME_IDS"
RUNTIME_ID = "agentscope-java-2.0.3"
SUPPORT_PROFILE_SOURCE = "builder-template://agentscope-query-service/agentscope-support.v0.1.json"
RUNTIME = {"family": "agentscope", "language": "java",
           "templateRef": "maven://io.agentscope:agentscope-core:2.0.3",
           "integrationRef": "maven://io.github.bitlhk.finance:finance-harness-agentscope-java:0.1.0-SNAPSHOT"}
SAFE_ERRORS = {"MANAGED_RUNTIME_DISABLED", "MANAGED_RUNTIME_DECLARATION_INVALID", "MANAGED_RUNTIME_UNAVAILABLE",
               "MANAGED_HOST_AMBIGUOUS", "MANAGED_HOST_BINDING_MISMATCH", "MANAGED_HOST_SOURCE_INVALID",
               "MANAGED_RUNTIME_VERSION_MISMATCH", "MANAGED_RUNTIME_CAPABILITY_UNSUPPORTED",
               "MANAGED_RUNTIME_QUALIFICATION_REQUIRED", "MANAGED_RUNTIME_QUALIFICATION_REJECTED",
               "MANAGED_RUNTIME_REUSE_UNSUPPORTED", "MANAGED_METHOD_RUNTIME_UNSUPPORTED"}


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class ManagedProfileReference(StrictModel):
    id: str = Field(pattern=r"^[a-z][a-z0-9.-]{0,127}$")
    version: str = Field(pattern=r"^[a-zA-Z0-9_.+-]{1,128}$")
    source: str = Field(pattern=r"^repo://profiles/[a-z][a-z0-9.-]{0,127}\.md$")


class HostDeclaration(StrictModel):
    id: str = Field(pattern=r"^[a-z][a-z0-9.-]{0,127}$")
    templateSource: str = Field(pattern=r"^builder-template://[a-z][a-z0-9-]{0,127}$")
    supportFile: str = Field(pattern=r"^[a-z][a-z0-9.-]{0,127}\.json$")
    profileRef: ManagedProfileReference
    presentations: list[Literal["MOBILE_WEB", "DESKTOP_WEB"]] = Field(min_length=1, max_length=2)
    applicationTemplateIds: list[str] = Field(min_length=1, max_length=32)
    connectionKind: Literal["synthetic-bank-http.v1"]


class RuntimeDeclaration(StrictModel):
    id: Literal["agentscope-java-2.0.3"]
    label: str = Field(min_length=1, max_length=80)
    description: str = Field(min_length=1, max_length=200)
    architectureLabel: str = Field(min_length=1, max_length=80)
    family: Literal["agentscope"]
    language: Literal["java"]
    version: Literal["2.0.3"]
    status: Literal["CANDIDATE", "AVAILABLE"]
    enabled: bool
    selectionSchemaVersion: Literal["0.10"]
    backendId: Literal["agentscope-java"]
    invocationProtocol: Literal["finance-chat"]
    artifactKind: Literal["directory-manifest"]
    runtime: dict[str, str]
    supportProfileSource: Literal["builder-template://agentscope-query-service/agentscope-support.v0.1.json"]
    hosts: list[HostDeclaration] = Field(min_length=1, max_length=32)


class ManagedRuntimeIndex(StrictModel):
    schemaVersion: Literal["finance-managed-runtime-support.v0.1"]
    runtimes: list[RuntimeDeclaration] = Field(max_length=32)


def _managed_path(root, relative):
    root = Path(root).resolve()
    path = root / relative
    try:
        resolved = require_path_within(path, root)
        if any(p.is_symlink() for p in (path, *path.parents) if p.is_relative_to(root)):
            raise ValueError
        return resolved
    except (OSError, ValueError):
        raise AssemblyRejected("MANAGED_HOST_SOURCE_INVALID") from None


def declarations(project):
    path = _managed_path(project, INDEX)
    if not path.exists():
        return []
    try:
        if not path.is_file() or path.stat().st_size > 65536:
            raise ValueError
        raw = path.read_bytes()
        index = ManagedRuntimeIndex.model_validate(json.loads(raw, object_pairs_hook=_unique_object))
        values = [r.model_dump() for r in index.runtimes]
        if len({r['id'] for r in values}) != len(values):
            raise ValueError
        for item in values:
            if item['runtime'] != RUNTIME or (item['enabled'] and item['status'] != 'AVAILABLE'):
                raise ValueError
            item['declarationDigest'] = 'sha256:' + sha256(raw).hexdigest()
        return values
    except (OSError, UnicodeError, ValueError, ValidationError):
        raise AssemblyRejected("MANAGED_RUNTIME_DECLARATION_INVALID") from None


def candidate_enabled(record, environment=None):
    env = os.environ if environment is None else environment
    return (record['enabled'] and record['status'] == 'AVAILABLE') or (
        record['status'] == 'CANDIDATE' and record['id'] in env.get(CANDIDATE_ENV, '').split(','))


def template_path(project, host):
    return _managed_path(project, 'templates/' + host['templateSource'].removeprefix('builder-template://'))


def host_support(project, record, host):
    path = _managed_path(project, str(template_path(project, host).relative_to(Path(project).resolve())) + '/' + host['supportFile'])
    try:
        if not path.is_file() or path.stat().st_size > 65536:
            raise ValueError
        raw = path.read_bytes()
        support = json.loads(raw, object_pairs_hook=_unique_object)
        if support['runtime'] != record['runtime'] or support['profile'] != host['profileRef']['id']:
            raise ValueError
        if support['operations'] not in (['QUERY'], ['QUERY', 'TASK']) or 'observation.v0.1' not in support['features']:
            raise ValueError
        if 'TASK' in support['operations'] and not {'task.v1', 'trusted-confirmation.v1', 'persistent-task.v1', 'trusted-grants.v1'} <= set(support['features']):
            raise ValueError
        return support, path
    except (OSError, UnicodeError, KeyError, TypeError, ValueError):
        raise AssemblyRejected("MANAGED_HOST_BINDING_MISMATCH") from None


def resolve_binding(project, runtime_id, host_id, profile_ref, *, environment=None, require_enabled=True):
    records = [r for r in declarations(project) if r['id'] == runtime_id]
    if len(records) != 1:
        raise AssemblyRejected("MANAGED_RUNTIME_UNAVAILABLE")
    record = records[0]
    matches = [h for h in record['hosts'] if h['id'] == host_id and h['profileRef'] == profile_ref]
    if len(matches) > 1:
        raise AssemblyRejected("MANAGED_HOST_AMBIGUOUS")
    if not matches:
        raise AssemblyRejected("MANAGED_HOST_BINDING_MISMATCH")
    if require_enabled and not candidate_enabled(record, environment):
        raise AssemblyRejected("MANAGED_RUNTIME_DISABLED")
    support, path = host_support(project, record, matches[0])
    return record, matches[0], support, path


def supported_selection(project, assembly, record, host, bindings):
    support, _ = host_support(project, record, host)
    resolved = assembly.select(bindings, host=support, connection_kind=host['connectionKind'])
    validate_runtime_support(assembly, record, host, resolved, project=project)
    return support, resolved


def _support_profile(project, record, host):
    source = record['supportProfileSource']
    if source != SUPPORT_PROFILE_SOURCE or not source.startswith(host['templateSource'] + '/'):
        raise AssemblyRejected('MANAGED_HOST_SOURCE_INVALID')
    path = _managed_path(project, 'templates/' + source.removeprefix('builder-template://'))
    try:
        if not path.is_file() or path.stat().st_size > 65536:
            raise ValueError
        raw = path.read_bytes()
        profile = json.loads(raw, object_pairs_hook=_unique_object)
        if not isinstance(profile, dict):
            raise ValueError
        return profile, {'source': source, 'digest': 'sha256:' + sha256(raw).hexdigest()}
    except (OSError, UnicodeError, ValueError):
        raise AssemblyRejected('MANAGED_HOST_SOURCE_INVALID') from None


def validate_runtime_support(assembly, record, host, resolved, *, project=None):
    if project is None:
        raise AssemblyRejected('MANAGED_HOST_SOURCE_INVALID')
    profile, reference = _support_profile(project, record, host)
    mode = (profile.get('schemaVersion'), profile.get('operation'))
    supported_operations = {'QUERY'} if mode == ('agentscope-query-support.v0.1', 'QUERY') else (
        {'QUERY', 'TASK'} if mode == ('agentscope-task-support.v0.1', 'QUERY_TASK') else set())
    if (not supported_operations
            or (profile.get('family'), profile.get('language'), profile.get('sdkVersion'))
            != (record['family'], record['language'], record['version'])):
        raise AssemblyRejected('MANAGED_RUNTIME_VERSION_MISMATCH')
    matches = [p for p in profile.get('profiles', []) if
               {k: p.get('profile', {}).get(k) for k in ('id', 'version', 'source')} == host['profileRef']]
    if len(matches) != 1:
        raise AssemblyRejected('MANAGED_HOST_BINDING_MISMATCH')
    allowed = matches[0].get('allowedCapabilityIds', [])
    if (not set(resolved['capabilityBindings']) <= set(allowed)
            or any(c['operation'] not in supported_operations for c in resolved['capabilities'])):
        raise AssemblyRejected('MANAGED_RUNTIME_CAPABILITY_UNSUPPORTED')
    for provider in resolved['providerRefs']:
        if not any((p.get('id'), p.get('version'), p.get('artifactRef'), p.get('connectionKind')) ==
                   (provider['id'], provider['version'], provider['source'], host['connectionKind']) for p in profile.get('providers', [])):
            raise AssemblyRejected('MANAGED_RUNTIME_CAPABILITY_UNSUPPORTED')
    # Keep Builder-owned source identities separate from Harness-relative files.
    resolved['evidence']['supportProfile'] = reference


def validate_method_runtime(release, runtime):
    """Check frozen package requirements, not the runtime scope of a trial report."""
    source = release.get('source', {})
    if source.get('kind') != 'BUSINESS_PACKAGE_IMPORTED':
        return
    manifest = source.get('manifest')
    requirements = manifest.get('requirements') if isinstance(manifest, dict) else None
    if not isinstance(requirements, dict):
        raise AssemblyRejected('MANAGED_METHOD_RUNTIME_UNSUPPORTED')
    for field, actual in (('runtimeFamilies', runtime['family']), ('runtimeLanguages', runtime['language'])):
        allowed = requirements.get(field)
        if (not isinstance(allowed, list) or not allowed
                or any(not isinstance(value, str) or not value for value in allowed) or actual not in allowed):
            raise AssemblyRejected('MANAGED_METHOD_RUNTIME_UNSUPPORTED')


def managed_template_identity(project, host, support_path):
    template = template_path(project, host)
    if any(path.is_symlink() for path in template.rglob('*')):
        raise AssemblyRejected('MANAGED_HOST_SOURCE_INVALID')
    identity = {'source': host['templateSource'], 'digest': tree_digest(template),
                'supportSource': host['templateSource'] + '/' + host['supportFile'],
                'supportDigest': 'sha256:' + sha256(support_path.read_bytes()).hexdigest()}
    manifest_path = template / 'shared-sources.json'
    if not manifest_path.exists():
        return identity
    try:
        if manifest_path.stat().st_size > 65536:
            raise ValueError
        manifest = json.loads(manifest_path.read_bytes(), object_pairs_hook=_unique_object)
        if (set(manifest) != {'schemaVersion', 'sourceTemplate', 'javaPackage', 'main', 'test', 'files', 'excludedRuntimeSources'}
                or manifest['schemaVersion'] != 'finance-template-shared-sources.v0.1'
                or not re.fullmatch(r'[a-z][a-z0-9-]{0,127}', manifest['sourceTemplate'])
                or not re.fullmatch(r'[a-zA-Z0-9_/]{1,240}', manifest['javaPackage'])
                or any(not isinstance(manifest[k], list) or len(manifest[k]) > 256 for k in ('main', 'test', 'files', 'excludedRuntimeSources'))):
            raise ValueError
        relative_files = list(manifest['files'])
        for kind in ('main', 'test'):
            for name in manifest[kind]:
                if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_]{1,128}\.java', name):
                    raise ValueError
                if name in manifest['excludedRuntimeSources']:
                    raise ValueError
                relative_files.append('src/' + kind + '/java/' + manifest['javaPackage'] + '/' + name)
        if len(relative_files) != len(set(relative_files)):
            raise ValueError
        files = {}
        composed = {path.relative_to(template).as_posix(): path for path in template.rglob('*')
                    if path.is_file() and not {'.git', 'target', '__pycache__'}.intersection(path.relative_to(template).parts)}
        for relative in relative_files:
            if (not isinstance(relative, str) or not relative or '\\' in relative
                    or Path(relative).is_absolute() or '..' in Path(relative).parts):
                raise ValueError
            path = _managed_path(project, 'templates/' + manifest['sourceTemplate'] + '/' + relative)
            if not path.is_file() or path.stat().st_size > 2_000_000:
                raise ValueError
            source = 'builder-template://' + manifest['sourceTemplate'] + '/' + relative
            files[source] = 'sha256:' + sha256(path.read_bytes()).hexdigest()
            if relative in composed:
                raise ValueError
            composed[relative] = path
        identity['sharedSources'] = dict(sorted(files.items()))
        identity['sharedSourcesDigest'] = 'sha256:' + sha256(json.dumps(identity['sharedSources'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        combined = sha256()
        for relative, path in sorted(composed.items()):
            combined.update(relative.encode())
            combined.update(b'\0')
            combined.update(path.read_bytes())
            combined.update(b'\0')
        identity['localDigest'] = identity['digest']
        identity['digest'] = 'sha256:' + combined.hexdigest()
        return identity
    except (OSError, UnicodeError, ValueError, KeyError, TypeError):
        raise AssemblyRejected('MANAGED_HOST_SOURCE_INVALID') from None


def freeze_binding(project, record, host, support_path, *, harness=None):
    display = {k: record[k] for k in ('label', 'family', 'language', 'version', 'invocationProtocol')}
    presentation = {k: record[k] for k in ('label', 'language', 'version', 'description', 'architectureLabel')}
    presentation['runtimeId'] = record['id']
    binding = {'schemaVersion': 'finance-managed-runtime-binding.v0.1', 'runtimeId': record['id'],
            'runtimeVersion': record['version'], 'hostId': host['id'], 'backendId': record['backendId'],
            'runtime': deepcopy(record['runtime']), 'profileRef': deepcopy(host['profileRef']),
            'invocationProtocol': record['invocationProtocol'], 'artifactKind': record['artifactKind'],
            'declarationDigest': record['declarationDigest'], 'display': display, 'runtimePresentation': presentation,
            'managedTemplate': managed_template_identity(project, host, support_path), 'capabilitySelection': 'ATOMIC_PACK'}
    _, binding['supportProfile'] = _support_profile(project, record, host)
    return binding


def require_backend_admission(project, harness, solution, binding, environment=None, *, method_snapshot=None):
    """The fixed backend must prove current artifacts; declaration checks cannot pass this gate."""
    try:
        from .candidate_backend.agentscope_java import preflight_managed_admission
    except ImportError:
        raise AssemblyRejected('MANAGED_RUNTIME_QUALIFICATION_REQUIRED') from None
    try:
        result = preflight_managed_admission(project, harness, solution, binding, environment=environment,
                                            method_snapshot=method_snapshot)
        if not isinstance(result, dict) or result.get('status') != 'READY' or result.get('requiredSatisfied') is not True:
            raise ValueError
        return result
    except (OSError, ValueError, KeyError, TypeError):
        raise AssemblyRejected('MANAGED_RUNTIME_QUALIFICATION_REJECTED') from None
