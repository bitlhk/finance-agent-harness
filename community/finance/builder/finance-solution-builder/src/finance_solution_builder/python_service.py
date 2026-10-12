"""Finite Python read-only Candidate with an explicitly separate Java bridge plan."""
from email.parser import BytesParser
import json
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import time
import zipfile
from finance_builder_base.runtime import spawn_process

from .service import (build_service, digest, solution_for, stable_failure_code, validate_service,
                      valid_capability_set, stop_owned_process, write_json)
from .readonly import revision

RUNTIME_ID = 'openjiuwen-python-0.1.18'
RUNTIME = {'family': 'openjiuwen', 'language': 'python',
           'templateRef': 'repo://templates/python-readonly-service',
           'integrationRef': 'pypi://openjiuwen:0.1.18'}


def isolated_python_environment(environment=None):
    """Keep network configuration while excluding the Builder interpreter paths."""
    value = dict(os.environ if environment is None else environment)
    for name in ('PYTHONPATH', 'PYTHONHOME', 'VIRTUAL_ENV', 'UV_PROJECT_ENVIRONMENT'):
        value.pop(name, None)
    return value


def require_readable_local_wheels(environment=None):
    """Reject unreadable offline SDK assets before creating a partial Candidate."""
    value = os.environ if environment is None else environment
    for item in shlex.split(value.get('PIP_FIND_LINKS', '')):
        if '://' in item:
            continue
        source = Path(item)
        if not source.exists():
            continue
        wheels = source.glob('*.whl') if source.is_dir() else (source,)
        if any(not os.access(wheel, os.R_OK) for wheel in wheels):
            raise PermissionError('PYTHON_RUNTIME_WHEEL_UNREADABLE')


def installed_file_hashes(site):
    site = Path(site)
    # pip RECORD contains relocated script hashes; executed package files remain locked.
    return {p.relative_to(site).as_posix(): digest(p) for p in site.rglob('*')
            if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc'
            and not (p.name == 'RECORD' and p.parent.name.endswith('.dist-info'))}


def python_solution(capabilities, build_id, method_ref=None, method_capabilities=None):
    require_python_capabilities(capabilities)
    value = solution_for(capabilities, build_id, method_ref, method_capabilities)
    value['runtime'] = dict(RUNTIME)
    return value


def managed_python_bridge(project, harness, solution, method_snapshot=None):
    from .component_plan import qualify_solution, support_file_for
    if solution.get('runtime') != RUNTIME:
        raise ValueError('PYTHON_COMPOSITION_UNSUPPORTED')
    capabilities = sorted(solution['capabilityBindings'])
    require_python_capabilities(capabilities)
    support = json.loads(support_file_for(project, solution['profileRef']['id']).read_bytes())
    bridge = dict(solution, runtime=support['runtime'])
    plan = qualify_solution(project, harness, bridge, method_snapshot)
    if any(cap['operation'] != 'QUERY' for cap in plan['capabilities']):
        raise ValueError('PYTHON_COMPOSITION_UNSUPPORTED')
    return bridge, plan


def validate_python(solution, *, managed=False, project=None, harness=None, method_snapshot=None):
    if managed:
        _, plan = managed_python_bridge(project, harness, solution, method_snapshot)
        return sorted(plan['capabilityBindings'])
    if solution.get('runtime') != RUNTIME:
        raise ValueError('PYTHON_COMPOSITION_UNSUPPORTED')
    from .readonly import RECIPE
    bridge = dict(solution, runtime=json.loads(RECIPE.read_text())['runtime'])
    capabilities = validate_service(bridge)
    require_python_capabilities(capabilities)
    return capabilities


def require_python_capabilities(capabilities):
    catalog = json.loads(
        Path(__file__).with_name('template-modules.v1.json').read_text(encoding='utf-8')
    )
    runtime = next(item for item in catalog['runtimes'] if item['id'] == RUNTIME_ID)
    if (not valid_capability_set(capabilities)
            or not set(capabilities) <= set(runtime['capabilityIds'])):
        raise ValueError('PYTHON_COMPOSITION_UNSUPPORTED')


def build_python_service(builder, solution, output, verify, **options):
    started = time.monotonic()
    managed = options.get('managed', False)
    if managed:
        bridge_solution, _ = managed_python_bridge(builder.project_root, builder.harness_repo, solution,
                                                   options.get('method_snapshot'))
    else:
        capabilities = validate_python(solution)
        bridge_solution = dict(solution, runtime=solution_for(capabilities, solution['solutionId'])['runtime'])
    capabilities = sorted(bridge_solution['capabilityBindings'])
    require_readable_local_wheels()
    # Existing Java host reuse is supported; Python environment reuse is not implied.
    source = options.get('reuse_candidate')
    if source is not None and json.loads((source/'service-lock.json').read_text()).get('javaBridge'):
        raise ValueError('PYTHON_HOST_REUSE_UNSUPPORTED')
    interpreter = os.environ.get('FH_PYTHON_EXECUTABLE', 'python3')
    check = subprocess.run([interpreter, '-I', '-c',
        'import sys;sys.exit(0 if (3,11)<=sys.version_info[:2]<(3,14) else 1)'], capture_output=True)
    if check.returncode:
        raise ValueError('PYTHON_311_TO_313_REQUIRED')
    observation_requirements = options.pop('observation_requirements', None)
    # Build and verify the real Java bridge with the same existing Harness pipeline.
    result = build_service(builder, bridge_solution, output, verify, **options)
    candidate = result.candidate_dir
    report = json.loads(result.report_path.read_text())
    report['status'] = 'BUILDING'
    report['runtimeLanguage'] = 'python'
    write_json(result.report_path, report)

    def stage(name, command):
        log = output / 'logs' / (name + '.log')
        log.parent.mkdir(parents=True, exist_ok=True)
        started = time.monotonic()
        item = dict(name=name, status='RUNNING', log='logs/' + log.name)
        report['stages'].append(item)
        write_json(result.report_path, report)
        process = None
        cancel = getattr(builder, 'service_cancel', None)
        with log.open('wb') as stream:
            try:
                if cancel is not None and cancel.is_set():
                    raise RuntimeError('BUILD_CANCELLED')
                process = spawn_process(command, cwd=candidate, stdout=stream, stderr=subprocess.STDOUT,
                                        env=isolated_python_environment(),
                                        start_new_session=os.name == 'posix')
                builder.service_process = process
                if cancel is not None and cancel.is_set():
                    stop_owned_process(process, group=True)
                code = process.wait(timeout=900)
            except subprocess.TimeoutExpired:
                stop_owned_process(process, group=True)
                code = 124
            except OSError:
                code = 127
            except RuntimeError:
                code = 130
            finally:
                builder.service_process = None
        item.update(dict(name=name, status='PASS' if code == 0 else 'FAIL',
            exitCode=code, log='logs/' + log.name, logSha256=digest(log),
            elapsedMillis=round((time.monotonic() - started) * 1000)))
        write_json(result.report_path, report)
        if code:
            raise RuntimeError('PYTHON_BUILD_STAGE_FAILED')

    try:
        shutil.copytree(builder.project_root / 'templates/python-readonly-service', candidate, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
        write_json(candidate / 'bridge-solution.json', bridge_solution)
        write_json(candidate / 'solution.json', solution)
        if verify:
            jars = list((candidate/'target').glob('*.jar'))
            if len(jars) != 1:
                raise ValueError('CANDIDATE_JAR_MISSING')
            stage('python_environment', [interpreter, '-m', 'venv', str(candidate / '.venv')])
            python = str(candidate / '.venv/bin/python')
            wheels = candidate / 'python-wheels'
            wheels.mkdir()
            stage('python_resolve_wheels', [python, '-m', 'pip', 'download', '--only-binary=:all:',
                  '--dest', str(wheels), 'openjiuwen==0.1.18'])
            dependencies = []
            for wheel in sorted(wheels.glob('*.whl')):
                with zipfile.ZipFile(wheel) as archive:
                    metadata = [p for p in archive.namelist() if p.endswith('.dist-info/METADATA') and p.count('/') == 1]
                    if len(metadata) != 1:
                        raise ValueError('PYTHON_WHEEL_METADATA_INVALID')
                    info = BytesParser().parsebytes(archive.read(metadata[0]))
                    dependencies.append(dict(name=info['Name'], version=info['Version'],
                                             file=wheel.name, sha256=digest(wheel)))
            if not any(d['name'].lower() == 'openjiuwen' and d['version'] == '0.1.18' for d in dependencies):
                raise ValueError('PYTHON_SDK_MISSING')
            (candidate / 'python-requirements.lock').write_text(''.join(
                f"{d['name']}=={d['version']} --hash=sha256:{d['sha256']}\n" for d in dependencies))
            write_json(candidate / 'python-dependencies.json', dict(schemaVersion='python-wheel-lock.v0.1',
                       runtime='openjiuwen==0.1.18', compatibility='BUILD_PLATFORM_WHEELS', dependencies=dependencies))
            stage('python_locked_install', [python, '-m', 'pip', 'install', '--no-index', '--find-links', str(wheels),
                  '--require-hashes', '-r', str(candidate / 'python-requirements.lock')])
            stage('python_dependency_check', [python, '-m', 'pip', 'check'])
            site = subprocess.check_output([python, '-I', '-c', 'import sysconfig;print(sysconfig.get_path("purelib"))'], text=True).strip()
            installed = installed_file_hashes(site)
            write_json(candidate / 'python-environment-lock.json', dict(schemaVersion='python-installed-files.v0.1',
                scope='SITE_PACKAGES_EXCEPT_CACHE_AND_RELOCATABLE_PIP_RECORD', files=installed))
        lock = json.loads((candidate / 'service-lock.json').read_text())
        if lock['sources'] != {'harness': revision(builder.harness_repo), 'builder': revision(builder.project_root)}:
            raise ValueError('SOURCE_CHANGED_DURING_PYTHON_BUILD')
        lock['javaBridge'] = dict(runtime=bridge_solution['runtime'], execution='OWNED_STDIN_STDOUT_SUBPROCESS',
                                 hostTemplateDigest=lock['hostTemplateDigest'],
                                 installation='VALIDATED_PLAN_AT_SESSION_OPEN_NO_JAVA_AGENT')
        lock['runtime'] = solution['runtime']
        lock['snapshotCapture'] = 'CLIENT_BOUNDARY_OPT_IN_V01'
        lock['hostTemplateDigest'] += ':' + digest(candidate / 'finance_python/host.py')
        if observation_requirements is not None:
            from .observation_assets import materialize
            write_json(candidate / 'observation-reference.json', materialize(
                builder.harness_repo, Path(observation_requirements), candidate, capabilities))
        lock['files'] = {p.relative_to(candidate).as_posix(): digest(p) for p in sorted(candidate.rglob('*'))
            if p.is_file() and not ({'.venv', '__pycache__'} & set(p.relative_to(candidate).parts))
            and p.name != 'service-lock.json' and ('target' not in p.parts or p.suffix == '.jar')}
        write_json(candidate / 'service-lock.json', lock)
        if verify:
            # Qualify the final Python hooks/runtime, not the intermediate Java bridge identity.
            stage('python_installation_plan', ['java', '-cp',
                str(jars[0]) + os.pathsep + 'target/dependency/*',
                'io.github.bitlhk.finance.builder.local.ReadOnlyPlanExporter', str(candidate)])
            lock['files']['installation-plan.json'] = digest(candidate / 'installation-plan.json')
            write_json(candidate / 'service-lock.json', lock)
        write_json(output / 'solution-lock.json', lock)
        report.update(status='BUILT' if verify else 'NOT_RUN', lockSha256=digest(output / 'solution-lock.json'),
                      elapsedMillis=round((time.monotonic()-started)*1000))
        write_json(result.report_path, report)
        return result
    except Exception as error:
        report.update(status='FAILED', failureCode=stable_failure_code(error))
        report['elapsedMillis'] = round((time.monotonic()-started)*1000)
        write_json(result.report_path, report)
        raise RuntimeError('PYTHON_CANDIDATE_BUILD_FAILED: inspect stage logs') from None
