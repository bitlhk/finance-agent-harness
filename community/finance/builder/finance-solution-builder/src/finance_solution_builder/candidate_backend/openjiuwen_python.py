"""Native Python finance Candidate backend. Lifecycle reuses the existing owned Host manager."""
import json
import hashlib
from pathlib import Path
import shutil
import time
from functools import wraps

from finance_builder_base import ErrorDetail, ResourceKind
from finance_builder_base.solution_build import CheckStatus
from .models import (AssemblyResult, BackendCapability, BackendIdentity, BuildResult,
    CandidateArtifact, CandidateCheck, CandidateDescriptor, CandidateEntrypoint, CandidateResourceRef,
    CapabilityProviderBinding, EvidenceResult, HealthResult, InvocationResult, LifecycleOperation,
    LifecycleResult, LifecycleStatus, PreflightResult, ProtocolBinding, RunHandle, RunState,
    RuntimeEndpoint, RuntimeIdentity, StartResult, StopResult, verify_run_handle)
from ..python_service import build_python_service, validate_python
from ..service import digest, write_json


def measured(operation):
    @wraps(operation)
    def run(*args, **kwargs):
        started = time.monotonic()
        result = operation(*args, **kwargs)
        outcome = result.outcome.model_copy(update={'duration_ms': round((time.monotonic()-started)*1000)})
        return result.model_copy(update={'outcome': outcome})
    return run


class OpenJiuwenPythonBackend:
    def __init__(self, *, builder, solution, output, options=None):
        self.builder, self.solution, self.output = builder, solution, Path(output)
        self.options = options or {}
        self._identity = BackendIdentity(backend_id='openjiuwen-python-finance', version='0.1')
        self._jobs = None
        self._handle = None
        self._descriptor = None
        self._assembly = None
        self._stopped = set()

    @property
    def identity(self):
        return self._identity

    @property
    def capability(self):
        return BackendCapability(backend=self.identity, languages=('python',), runtime_families=('openjiuwen',),
            artifact_kinds=('directory-manifest',), invocation_protocols=('finance-chat',),
            operations=tuple(LifecycleOperation))

    def _outcome(self, operation, passed=True, *, task=None, refs=()):
        return LifecycleResult(operation=operation, status=LifecycleStatus.PASSED if passed else LifecycleStatus.BLOCKED,
            task_id=task or self.solution['solutionId'], candidate_id=self.solution['solutionId'], duration_ms=0,
            summary='Python finance lifecycle completed.' if passed else 'Python finance lifecycle boundary rejected.',
            evidence_refs=refs, error=None if passed else ErrorDetail(code='candidate.python.boundary_rejected',
                message='Candidate identity, configuration or lifecycle requirement was not satisfied.'))

    def _validated_capabilities(self):
        if self.options.get('managed'):
            return validate_python(self.solution, managed=True, project=self.builder.project_root,
                                   harness=self.builder.harness_repo, method_snapshot=self.options.get('method_snapshot'))
        return validate_python(self.solution)

    @measured
    def preflight(self, request):
        supported = True
        capabilities = []
        try: capabilities = self._validated_capabilities()
        except ValueError: supported = False
        target = request.target
        supported = supported and (target.language, target.runtime_family, target.runtime_version,
            target.artifact_kind, target.invocation_protocol, target.profile_id) == (
                'python', 'openjiuwen', '0.1.18', 'directory-manifest', 'finance-chat', 'local-read-only')
        supported = supported and set(target.required_capability_ids) == set(capabilities)
        supported = supported and set(target.required_provider_operation_types) <= {'query'}
        supported = supported and target.host_wiring_ids == ('owned-java-pipe',)
        assets = all((self.builder.harness_repo / ref['source'].removeprefix('repo://')).exists()
            for ref in [self.solution['profileRef'], *self.solution['capabilityPackRefs'], self.solution['evaluationPackRef']])
        checks = tuple(CandidateCheck(check_id=name, status=CheckStatus.PASSED if passed else CheckStatus.BLOCKED,
            summary='Required boundary available.' if passed else 'Required boundary unavailable.')
            for name, passed in [('finite-native-python-target', supported), ('harness-assets', assets),
                                ('java-bridge-toolchain', bool(shutil.which('java') and shutil.which(self.builder.maven)))])
        return PreflightResult(outcome=self._outcome(LifecycleOperation.PREFLIGHT,
            all(c.status == CheckStatus.PASSED for c in checks), task=request.task_id), backend=self.identity, checks=checks)

    @measured
    def assemble(self, request):
        from .models import PreflightRequest
        checked = self.preflight(PreflightRequest(task_id=request.task_id, target=request.target,
                                                resolved_asset_ids=request.target.required_asset_ids))
        expected_digest = hashlib.sha256(json.dumps(self.solution,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        passed = (checked.outcome.status == LifecycleStatus.PASSED and request.task_id == self.solution['solutionId']
                  and request.lock_digest == expected_digest)
        if passed:
            self._assembly = (request.task_id, request.lock_digest, request.asset_refs)
        return AssemblyResult(outcome=self._outcome(LifecycleOperation.ASSEMBLE, passed, task=request.task_id),
                              candidate_id=self.solution['solutionId'], assembly_refs=request.asset_refs if passed else ())

    def generate(self, verify=True):
        # The legacy local-service entry and SPI build share exactly this implementation.
        self._validated_capabilities()
        return build_python_service(self.builder, self.solution, self.output, verify, **self.options)

    @measured
    def build(self, request):
        if self._assembly != (request.task_id, request.lock_digest, request.assembly_refs) or request.candidate_id != self.solution['solutionId']:
            return BuildResult(outcome=self._outcome(LifecycleOperation.BUILD, False, task=request.task_id))
        try:
            result = self.generate()
            lock = json.loads(result.lock_path.read_text())
            plan = json.loads((result.candidate_dir/'installation-plan.json').read_text())
            resource = CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH,
                locator='candidate/service-lock.json', sha256=digest(result.lock_path))
            descriptor = CandidateDescriptor(task_id=request.task_id, candidate_id=request.candidate_id,
                backend=self.identity, language='python', runtime=RuntimeIdentity(family='openjiuwen', version='0.1.18'),
                artifact=CandidateArtifact(artifact_id=request.candidate_id, artifact_kind='directory-manifest', resource=resource),
                entrypoint=CandidateEntrypoint(mode='managed-artifact', resource=resource),
                invocation=ProtocolBinding(protocol='finance-chat', path='/sessions', media_type='application/json'),
                health=ProtocolBinding(protocol='http', path='/health', media_type='application/json'),
                lock_digest=digest(result.lock_path), installed_asset_ids=tuple(
                    ref['id'] for ref in [self.solution['profileRef'], *self.solution['domainPackRefs'], *self.solution['capabilityPackRefs']]),
                installed_capability_ids=tuple(lock['capabilities']),
                installed_tool_ids=tuple(item['definition']['toolName'] for item in plan['capabilities']),
                provider_bindings=tuple(CapabilityProviderBinding(capability_id=item['definition']['capabilityId'],
                    provider_id=item['providerId'], operation_types=('query',)) for item in plan['capabilities']))
            write_json(self.output/'candidate-descriptor.json', descriptor.to_wire())
            self._descriptor = descriptor
            return BuildResult(outcome=self._outcome(LifecycleOperation.BUILD), descriptor=descriptor)
        except Exception:
            return BuildResult(outcome=self._outcome(LifecycleOperation.BUILD, False))

    @measured
    def start(self, request):
        try:
            if request.descriptor != self._descriptor or self.output.parent.name != 'workbench':
                raise ValueError('CANDIDATE_BINDING_REJECTED')
            from .models import verify_candidate_artifact
            if verify_candidate_artifact(request.descriptor, self.output) != request.artifact_verification:
                raise ValueError('ARTIFACT_CHANGED')
            if self._jobs is None:
                from ..workbench import Jobs
                self._jobs = Jobs(self.builder.project_root, self.builder.harness_repo, self.builder.maven,
                                  data_dir=self.output.parent.parent)
            jobs = self._jobs
            identity = self.solution['solutionId']
            if jobs.path(identity) != self.output or self._handle is not None:
                raise ValueError('CANDIDATE_ALREADY_RUNNING_OR_PATH_MISMATCH')
            job = dict(id=identity, owner='candidate-backend', status='BUILT',
                       capabilities=self._validated_capabilities(), createdAt=time.time())
            if self.options.get('model_selection'): job['modelSelection'] = self.options['model_selection']
            jobs.jobs[identity] = job
            jobs.save(job)
            running = jobs.start(identity, 'candidate-backend')
            handle = RunHandle(task_id=identity, candidate_id=identity, backend=self.identity,
                descriptor_digest=request.descriptor.sha256(), lock_digest=request.descriptor.lock_digest,
                state=RunState.RUNNING, endpoints=(RuntimeEndpoint(name='host', protocol='finance-chat',
                    uri='http://127.0.0.1:'+str(running['port'])),))
            self._handle = handle
            return StartResult(outcome=self._outcome(LifecycleOperation.START), handle=handle)
        except Exception:
            return StartResult(outcome=self._outcome(LifecycleOperation.START, False))

    def _owned(self, handle):
        if self._handle != handle or self._jobs is None:
            raise ValueError('INSTANCE_CHANGED')
        verify_run_handle(self._descriptor, handle)
        return self._jobs

    @measured
    def health(self, request):
        passed = False
        try:
            code, result = self._owned(request.handle)._request(request.handle.candidate_id, '/health')
            passed = code == 200 and result.get('runtime') == 'openjiuwen-python-0.1.18'
        except Exception: pass
        return HealthResult(outcome=self._outcome(LifecycleOperation.HEALTH, passed), ready=passed)

    @measured
    def invoke(self, request):
        response = None
        try:
            jobs = self._owned(request.handle)
            ref = request.payload_ref
            payload = (self.output/ref.locator).resolve()
            if (request.protocol != 'finance-chat' or ref.kind != ResourceKind.WORKSPACE_PATH
                    or not payload.is_relative_to(self.output.resolve()) or digest(payload) != ref.sha256):
                raise ValueError('INVOCATION_BINDING_REJECTED')
            identity = request.handle.candidate_id
            if identity not in jobs.sessions:
                code, _ = jobs.talk(identity, 'candidate-backend', 'session', {})
                if code != 200: raise ValueError('SESSION_REJECTED')
            code, result = jobs.talk(identity, 'candidate-backend', 'chat', json.loads(payload.read_text()))
            if code != 200: raise ValueError('INVOCATION_REJECTED')
            path = self.output/'backend-evidence'/('response-'+request.request_id+'.json')
            if not path.resolve().is_relative_to((self.output/'backend-evidence').resolve()):
                raise ValueError('REQUEST_ID_INVALID')
            path.parent.mkdir(exist_ok=True)
            write_json(path, result)
            path.chmod(0o600)
            response = CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH,
                locator=path.relative_to(self.output).as_posix(), sha256=digest(path))
        except Exception: pass
        return InvocationResult(outcome=self._outcome(LifecycleOperation.INVOKE, response is not None),
                                request_id=request.request_id, response_ref=response)

    @measured
    def collect_evidence(self, request):
        try:
            self._owned(request.handle)
            refs = tuple(CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH,
                locator=p.relative_to(self.output).as_posix(), sha256=digest(p))
                for p in sorted((self.output/'candidate/runtime-evidence').glob('*/runtime-lock.json')))
            return EvidenceResult(outcome=self._outcome(LifecycleOperation.COLLECT_EVIDENCE, bool(refs), refs=refs))
        except Exception:
            return EvidenceResult(outcome=self._outcome(LifecycleOperation.COLLECT_EVIDENCE, False))

    @measured
    def stop(self, request):
        if request.handle.sha256() in self._stopped:
            return StopResult(outcome=self._outcome(LifecycleOperation.STOP), stopped=True)
        passed = False
        try:
            jobs = self._owned(request.handle)
            jobs.stop(request.handle.candidate_id, 'candidate-backend')
            self._handle = None
            self._stopped.add(request.handle.sha256())
            passed = True
        except Exception: pass
        return StopResult(outcome=self._outcome(LifecycleOperation.STOP, passed), stopped=passed)
