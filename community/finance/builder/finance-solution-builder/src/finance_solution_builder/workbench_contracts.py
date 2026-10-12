"""Scenario-owned UI contracts. Configuration is not connectivity or business authority."""
from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
from typing import Literal, Mapping
from urllib.parse import urlsplit

from finance_builder_base.contracts import ExtensionModel
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from .service import solution_for, CASHFLOW, valid_capability_set
from .model_registry import ModelRegistry, ModelRegistryError
from .terminal_ui import InterfaceConfig, TERMINALS, presentation_config
from .experience_contracts import ApiError
from .managed_runtime_support import ManagedProfileReference

API_VERSION = "finance-workbench-api.v0.2"
RUNTIME = "openjiuwen-java-0.1.15"
TEMPLATE = "banking-readonly-web-v1"
MODEL = "configured-model"
BANK = "local-simulator"
STAGES = ("harness_contracts", "harness_tests_install", "candidate_package")
BASELINE = ("trusted_identity", "current_authorization", "readonly_boundary", "execution_evidence")


class Selection(ExtensionModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)
    extension_type: Literal["finance-workbench.selection"]
    schema_version: Literal["0.2"]
    runtimeId: Literal["openjiuwen-java-0.1.15"]
    applicationTemplateId: Literal["banking-readonly-web-v1"]
    modelConnectionId: Literal["configured-model"]
    bankConnectionId: Literal["local-simulator"]
    capabilityIds: list[Literal["wealth.facts.describe", "account.balance.query", "finance.transaction-summary.query", "finance.spending-analysis.query"]] = Field(
        min_length=1, max_length=4, json_schema_extra={"uniqueItems": True})

    @field_validator("capabilityIds")
    @classmethod
    def unique_capabilities(cls, value):
        if not valid_capability_set(value):
            raise ValueError("DUPLICATE_CAPABILITY")
        return sorted(value)

    def legacy(self):
        return dict(runtime=self.runtimeId, model=self.modelConnectionId,
                    connection=self.bankConnectionId, capabilities=self.capabilityIds)


class MultiModelSelection(ExtensionModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)
    extension_type: Literal["finance-workbench.selection"]
    schema_version: Literal["0.3"]
    runtimeId: Literal["openjiuwen-java-0.1.15"]
    applicationTemplateId: Literal["banking-readonly-web-v1"]
    presentation: Literal["MOBILE_WEB", "DESKTOP_WEB"] = "MOBILE_WEB"
    bankConnectionId: Literal["local-simulator"]
    capabilityIds: list[Literal["wealth.facts.describe", "account.balance.query", "finance.transaction-summary.query", "finance.spending-analysis.query"]] = Field(min_length=1, max_length=4)
    selectedModelRefs: list[str] = Field(min_length=1, max_length=32)
    defaultModelRef: str
    modelCatalogRevision: str = Field(pattern=r"^[a-f0-9]{64}$")

    @model_validator(mode="after")
    def validate_sets(self):
        if (not valid_capability_set(self.capabilityIds)
                or len(self.selectedModelRefs) != len(set(self.selectedModelRefs))
                or self.defaultModelRef not in self.selectedModelRefs
                or any(not re.fullmatch(r"model-[a-z0-9-]{1,80}", ref) for ref in self.selectedModelRefs)):
            raise ValueError("SELECTION_INVALID")
        return self

    def legacy(self):
        return dict(runtime=self.runtimeId, model=MODEL, connection=self.bankConnectionId,
                    capabilities=sorted(self.capabilityIds))


class InstalledRuntimeSelection(MultiModelSelection):
    """Internal startup view of a frozen artifact; not a new public selection format."""
    runtimeId: Literal['openjiuwen-java-0.1.15', 'openjiuwen-python-0.1.18']

    @model_validator(mode='after')
    def finite_python(self):
        if self.runtimeId == 'openjiuwen-python-0.1.18':
            from .python_service import require_python_capabilities
            require_python_capabilities(self.capabilityIds)
        return self


class DirectorySelection(MultiModelSelection):
    schema_version: Literal["0.4"]
    audienceId: Literal["CUSTOMER", "EMPLOYEE", "ENGINEERING"]
    entryMode: Literal["TEMPLATE", "COMPONENTS"]
    scenarioTemplateId: str | None = Field(default=None, max_length=100)
    componentIds: list[str] = Field(min_length=1, max_length=8)
    directoryRevision: str = Field(pattern=r"^[a-f0-9]{64}$")

    @field_validator("componentIds")
    @classmethod
    def unique_components(cls, value):
        if len(value) != len(set(value)) or any(not re.fullmatch(r"[a-z][a-z0-9.-]{0,100}", v) for v in value):
            raise ValueError("COMPONENT_SELECTION_INVALID")
        return value


class TemplateModuleSelection(MultiModelSelection):
    schema_version: Literal["0.5"]
    runtimeId: str = Field(min_length=1, max_length=100, pattern=r"^[a-z][a-z0-9.-]*$")
    scenarioTemplateId: str = Field(min_length=1, max_length=100, pattern=r"^[a-z][a-z0-9.-]*$")
    moduleIds: list[str] = Field(min_length=1, max_length=26)
    directoryRevision: str = Field(pattern=r"^[a-f0-9]{64}$")

    @field_validator("moduleIds")
    @classmethod
    def unique_modules(cls, value):
        if len(value) != len(set(value)) or any(not re.fullmatch(r"[a-z][a-z0-9._-]{0,100}", v) for v in value):
            raise ValueError("MODULE_SELECTION_INVALID")
        return value


class TerminalSelection(TemplateModuleSelection):
    schema_version: Literal["0.6"]
    presentation: Literal["MOBILE_WEB", "DESKTOP_WEB", "ATM_WEB"] = "MOBILE_WEB"
    interfaceConfig: InterfaceConfig = Field(default_factory=InterfaceConfig)

    @model_validator(mode="after")
    def compatible_interface(self):
        presentation_config(self.model_dump())
        return self


class MethodReference(ExtensionModel):
    extension_type: Literal["finance-private-method.reference"] = "finance-private-method.reference"
    schema_version: Literal["0.1"] = "0.1"
    assetId: str = Field(pattern=r"^method-[a-f0-9]{16}$")
    version: str = Field(pattern=r"^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}$")
    releaseDigest: str = Field(pattern=r"^sha256:[a-f0-9]{64}$")

    def reference(self):
        return self.model_dump(include={"assetId", "version", "releaseDigest"})


class MethodSelection(TerminalSelection):
    schema_version: Literal["0.7"]
    methodRef: MethodReference
    reuseFromBuildId: str | None = Field(default=None, pattern=r"^agent-[a-f0-9]{16}$")


class ManagedAssetIdentity(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)
    id: str = Field(min_length=1, max_length=128, pattern=r"^[a-zA-Z0-9_.-]+$")
    version: str = Field(min_length=1, max_length=128, pattern=r"^[a-zA-Z0-9_.+-]+$")


class ManagedPackBinding(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)
    pack: ManagedAssetIdentity
    provider: ManagedAssetIdentity


class ManagedWorkbenchSelection(MultiModelSelection):
    schema_version: Literal["0.8"]
    presentation: Literal["MOBILE_WEB", "DESKTOP_WEB", "ATM_WEB"] = "MOBILE_WEB"
    interfaceConfig: InterfaceConfig = Field(default_factory=InterfaceConfig)
    capabilityIds: list[str] = Field(min_length=1, max_length=128)
    packBindings: list[ManagedPackBinding] = Field(min_length=1, max_length=32)

    @model_validator(mode="after")
    def validate_sets(self):
        # Capability membership is resolved against managed assets, not a business enum.
        if (len(self.capabilityIds) != len(set(self.capabilityIds))
                or any(not re.fullmatch(r"[a-z][a-z0-9.-]{0,127}", v) for v in self.capabilityIds)
                or len(self.selectedModelRefs) != len(set(self.selectedModelRefs))
                or self.defaultModelRef not in self.selectedModelRefs
                or any(not re.fullmatch(r"model-[a-z0-9-]{1,80}", ref) for ref in self.selectedModelRefs)):
            raise ValueError("SELECTION_INVALID")
        return self

    def resolve(self, project, harness, solution_id):
        from .managed_selection import solution_from_selection, selection_support
        support = selection_support(project)
        if self.runtimeId not in support["runtimeIds"] or self.presentation not in support["presentations"]:
            raise ValueError("MANAGED_HOST_UNSUPPORTED")
        presentation_config(self.model_dump())
        solution = solution_from_selection(project, harness, {
            "schemaVersion": "finance-managed-selection.v0.1", "solutionId": solution_id,
            "packBindings": [binding.model_dump() for binding in self.packBindings]})
        if set(solution["capabilityBindings"]) != set(self.capabilityIds):
            raise ValueError("COMPONENT_SELECTION_MISMATCH")
        return solution


class UnifiedWorkbenchSelection(ManagedWorkbenchSelection):
    schema_version: Literal["0.9"]
    runtimeId: Literal["openjiuwen-java-0.1.15", "openjiuwen-python-0.1.18"]
    scenarioTemplateId: str = Field(min_length=1, max_length=100, pattern=r"^[a-z][a-z0-9.-]*$")
    moduleIds: list[str] = Field(min_length=1, max_length=128)
    directoryRevision: str = Field(pattern=r"^[a-f0-9]{64}$")
    methodRef: MethodReference | None = None
    reuseFromBuildId: str | None = Field(default=None, pattern=r"^agent-[a-f0-9]{16}$")

    @field_validator("moduleIds")
    @classmethod
    def unique_modules(cls, value):
        return TemplateModuleSelection.unique_modules(value)

    @model_validator(mode="after")
    def unified_interface(self):
        presentation_config(self.model_dump())
        if self.reuseFromBuildId and self.methodRef is None:
            raise ValueError("SELECTION_INVALID")
        if self.reuseFromBuildId and self.runtimeId == "openjiuwen-python-0.1.18":
            raise ValueError("PYTHON_HOST_REUSE_UNSUPPORTED")
        return self

    def resolve_details(self, project, harness, solution_id, method_snapshot=None):
        from .managed_selection import resolve_unified_selection
        return resolve_unified_selection(project, harness, self, solution_id, method_snapshot)

    def resolve(self, project, harness, solution_id, method_snapshot=None):
        return self.resolve_details(project, harness, solution_id, method_snapshot)[0]


class ManagedRuntimeWorkbenchSelection(UnifiedWorkbenchSelection):
    """Explicit managed Host binding, additive to the fixed legacy 0.9 contract."""
    schema_version: Literal['0.10']
    runtimeId: Literal['agentscope-java-2.0.3']
    hostId: str = Field(pattern=r'^[a-z][a-z0-9.-]{0,127}$')
    profileRef: ManagedProfileReference

    @model_validator(mode='after')
    def no_cross_runtime_reuse(self):
        if self.reuseFromBuildId is not None:
            raise ValueError('MANAGED_RUNTIME_REUSE_UNSUPPORTED')
        return self

    def resolve_details(self, project, harness, solution_id, method_snapshot=None, *, environment=None):
        from .managed_selection import resolve_unified_selection
        return resolve_unified_selection(project, harness, self, solution_id, method_snapshot, environment=environment)


def parse_selection(body):
    try:
        cls = {"0.3": MultiModelSelection, "0.4": DirectorySelection, "0.5": TemplateModuleSelection, "0.6": TerminalSelection,
               "0.7": MethodSelection, "0.8": ManagedWorkbenchSelection,
               "0.9": UnifiedWorkbenchSelection, "0.10": ManagedRuntimeWorkbenchSelection}.get(body.get("schema_version"), Selection) if isinstance(body, dict) else Selection
        return cls.model_validate(body)
    except (ValidationError, TypeError):
        # Pydantic diagnostics include rejected input values; never return them to a client.
        raise ApiError("SELECTION_INVALID") from None


def default_selection(capabilities):
    return dict(extension_type="finance-workbench.selection", schema_version="0.2",
                runtimeId=RUNTIME, applicationTemplateId=TEMPLATE, modelConnectionId=MODEL,
                bankConnectionId=BANK, capabilityIds=sorted(capabilities))


def parse_saved_selection(body):
    # Preserve the stored artifact; normalize only its legacy preflight view.
    if isinstance(body, dict) and body.get("schema_version") in {"0.3", "0.4", "0.5"} and body.get("presentation") == "RESPONSIVE_WEB":
        body = {**body, "presentation": "MOBILE_WEB"}
    return parse_selection(body)


def catalog(harness=None):
    from .directory import describe_directory
    from .template_modules import describe_template_modules
    registry = ModelRegistry()
    value = {
        "schemaVersion": API_VERSION,
        "runtimes": [{"id": RUNTIME, "label": "OpenJiuwen Java", "family": "openjiuwen", "language": "java", "version": "0.1.15", "enabled": True}],
        "applicationTemplates": [{"id": TEMPLATE, "label": "Financial read-only assistant", "enabled": True, "mode": "READ_ONLY",
                                  "profile": "CUSTOMER_SELF", "presentation": "RESPONSIVE_WEB",
                                  "nativePackage": False, "runtimeIds": [RUNTIME]}],
        "modelConnections": [{"id": MODEL, "kind": "MODEL", "configurationOwner": "HOST"}],
        "bankConnections": [{"id": BANK, "kind": "BANK", "provider": "non-ai-banking-reference",
                             "dataClass": "SYNTHETIC_SIMULATOR", "configurationOwner": "HOST"}],
        "capabilities": [
            {"id": "wealth.facts.describe", "label": "财富概览", "description": "查看已知资产范围、风险测评和信息缺口", "operation": "QUERY", "pack": "wealth-facts-description", "enabled": True},
            {"id": "account.balance.query", "label": "余额查询", "description": "查看获授权账户的余额、币种和来源", "operation": "QUERY", "pack": "account-balance-readonly", "enabled": True},
            {"id": "finance.transaction-summary.query", "label": "收支摘要", "description": "按指定月份查看收入、支出和净收支，保留来源范围与缺口", "operation": "QUERY", "pack": "cashflow-readonly", "enabled": True},
            {"id": "finance.spending-analysis.query", "label": "收支分类与月度数据", "description": "查看分类与截至该月的六个月数据；跨月可比性仍待核实", "operation": "QUERY", "pack": "cashflow-readonly", "enabled": True},
        ],
        "allowedCapabilitySets": [["wealth.facts.describe"], ["account.balance.query"],
                                  ["account.balance.query", "wealth.facts.describe"], sorted(CASHFLOW),
                                  sorted(CASHFLOW | {"account.balance.query"}), sorted(CASHFLOW | {"wealth.facts.describe"}),
                                  sorted(CASHFLOW | {"account.balance.query", "wealth.facts.describe"})],
        "mandatoryControls": [{"id": item, "required": True, "configurable": False} for item in BASELINE],
        "selectionSchema": Selection.model_json_schema(),
        "transport": {"current": "AG_UI_SSE", "agUi": "WORKBENCH_ADAPTER_AVAILABLE",
                      "tokenStreaming": False, "independentAgentPage": "BUILD_BOUND_WEB_VIEW"},
    }
    value.update(modelCatalogRevision=registry.catalog_revision, models=registry.public_models(),
                 executionMode="CONTROLLED_TEST" if os.environ.get("FH_MODEL_MODE") == "CONTROLLED_TEST" else "CONFIGURED_MODEL",
                 multiModelSelectionSchema=MultiModelSelection.model_json_schema())
    for capability in value["capabilities"]:
        capability.update(version="0.1.0", category="CASHFLOW" if capability["id"] in CASHFLOW else "WEALTH" if capability["id"].startswith("wealth") else "ACCOUNT",
                          status="AVAILABLE", dependencies=[], conflicts=[], runtimeIds=[RUNTIME], bankConnectionIds=[BANK])
    value["directory"] = describe_directory(harness)
    value["directorySelectionSchema"] = DirectorySelection.model_json_schema()
    value["templateModuleDirectory"] = describe_template_modules(harness)
    value["templateModuleSelectionSchema"] = TemplateModuleSelection.model_json_schema()
    value["terminalSelectionSchema"] = TerminalSelection.model_json_schema()
    value["methodSelectionSchema"] = MethodSelection.model_json_schema()
    value["managedSelectionSchema"] = ManagedWorkbenchSelection.model_json_schema()
    value["unifiedSelectionSchema"] = UnifiedWorkbenchSelection.model_json_schema()
    value["managedRuntimeSelectionSchema"] = ManagedRuntimeWorkbenchSelection.model_json_schema()
    if harness is not None:
        from .managed_selection import selection_catalog
        try:
            value["managedDirectory"] = selection_catalog(Path(__file__).resolve().parents[2], harness)
        except (OSError, ValueError, KeyError, TypeError):
            value["managedDirectory"] = {"status": "UNAVAILABLE", "entries": []}
        from .unified_catalog import unified_catalog
        try:
            unified = unified_catalog(Path(__file__).resolve().parents[2], harness)
            value["unifiedCatalog"] = unified
            value["unifiedDirectory"] = unified["directory"]
            value["unifiedTemplateModuleDirectory"] = unified["templateModuleDirectory"]
            value["unifiedManagedDirectory"] = {k: v for k, v in unified.items() if k not in {"directory", "templateModuleDirectory"}}
        except (OSError, ValueError, KeyError, TypeError):
            value["unifiedDirectory"] = {"status": "UNAVAILABLE", "components": []}
            value["unifiedTemplateModuleDirectory"] = {"status": "UNAVAILABLE", "templates": [], "modules": [], "components": []}
            value["unifiedManagedDirectory"] = {"status": "UNAVAILABLE", "entries": []}
            value["unifiedCatalog"] = {"status": "UNAVAILABLE", "entries": []}
    value["terminals"] = TERMINALS
    value["interfaceDefaults"] = InterfaceConfig().model_dump()
    from .speech import configuration_status
    value["speech"] = configuration_status()
    return value


def valid_endpoint(value, *, model=False):
    try:
        uri = urlsplit(value)
        _ = uri.port
        return bool(uri.hostname and not uri.username and not uri.password and not uri.query and not uri.fragment
                    and not any(c.isspace() for c in value)
                    and (uri.scheme == "https" or (uri.scheme == "http" and
                         (not model or uri.hostname in {"127.0.0.1", "localhost"}))))
    except ValueError:
        return False


def connection_status(environment: Mapping[str, str] | None = None):
    env = os.environ if environment is None else environment

    def item(identity, kind, fields, secret_ref):
        missing = [field for field in fields if not env.get(field, "").strip()]
        url_field = "FH_MODEL_URL" if kind == "MODEL" else "FH_BANK_URL"
        invalid = []
        if env.get(url_field) and not valid_endpoint(env[url_field], model=kind == "MODEL"):
            invalid.append(url_field)
        if kind == "MODEL" and env.get("FH_MODEL_MODE", "LIVE") not in {"LIVE", "CONTROLLED_TEST"}:
            invalid.append("FH_MODEL_MODE")
        if kind == "BANK" and env.get("FH_ACCESS_POLICY"):
            try:
                policy = Path(env["FH_ACCESS_POLICY"])
                if not policy.is_file() or not os.access(policy, os.R_OK):
                    invalid.append("FH_ACCESS_POLICY")
            except (OSError, ValueError):
                invalid.append("FH_ACCESS_POLICY")
        state = "MISSING" if missing else "INVALID" if invalid else "CONFIGURED_UNTESTED"
        return {"id": identity, "kind": kind, "secretRef": secret_ref,
                "configurationStatus": state, "missingFields": missing, "invalidFields": invalid,
                "connectivityStatus": "NOT_CHECKED", "authorizationStatus": "RUNTIME_CHECK_REQUIRED"}

    model = item(MODEL, "MODEL", ("FH_MODEL_URL", "FH_MODEL_NAME", "FH_MODEL_KEY"), "host-secret://model/local")
    name = env.get("FH_MODEL_NAME", "")
    secrets = [env.get(key, "") for key in ("FH_MODEL_KEY", "FH_BANK_SECRET", "FH_BANK_LOGIN")]
    model["modelName"] = (name if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,95}", name)
                          and not any(secret and secret in name for secret in secrets) else None)
    model["executionMode"] = "EXPLICIT_CONTROLLED_TEST" if env.get("FH_MODEL_MODE") == "CONTROLLED_TEST" else "CONFIGURED_MODEL"
    bank = item(BANK, "BANK", ("FH_BANK_URL", "FH_BANK_LOGIN", "FH_BANK_SECRET", "FH_ACCESS_POLICY"), "host-secret://bank/local")
    return {"schemaVersion": API_VERSION, "connections": [model, bank], "networkRequests": 0,
            "inspectionMode": "LOCAL_CONFIGURATION_ONLY"}


def start_readiness(snapshot, environment=None):
    """Local configuration blockers, not a network or authorization verdict."""
    blockers = []
    connections = connection_status(environment)['connections']
    for item in connections:
        if item['kind'] == 'MODEL' and snapshot:
            continue
        if item['configurationStatus'] != 'CONFIGURED_UNTESTED':
            blockers.append({'id': item['id'], 'code': 'CONNECTION_CONFIGURATION_MISSING',
                             'status': item['configurationStatus'], 'requiresRebuild': False})
    if snapshot:
        try:
            registry = ModelRegistry(environment)
            for frozen in snapshot['connections']:
                ref = frozen['modelRef']
                status = registry.status(ref)
                if status != 'CONFIGURED_UNTESTED':
                    blockers.append({'id': ref, 'code': 'MODEL_CONNECTION_UNAVAILABLE',
                                     'status': status, 'requiresRebuild': frozen['configVersion'] is None})
                elif registry.version(registry.bindings[ref]) != frozen['configVersion']:
                    blockers.append({'id': ref, 'code': 'MODEL_CONFIGURATION_CHANGED',
                                     'status': 'CHANGED', 'requiresRebuild': True})
        except ModelRegistryError as error:
            blockers.append({'id': 'model-registry', 'code': str(error),
                             'status': 'UNAVAILABLE', 'requiresRebuild': False})
    return {'configurationReady': not blockers, 'blockers': blockers,
            'connectivityStatus': 'NOT_CHECKED', 'authorizationStatus': 'RUNTIME_CHECK_REQUIRED'}


def preflight(selection: Selection, project: Path, harness: Path, maven: str, environment=None, *, method_snapshot=None):
    from .directory import resolve_directory
    from .template_modules import resolve_template_modules
    resolved = None
    if isinstance(selection, (DirectorySelection, TemplateModuleSelection)):
        try:
            resolved = (resolve_template_modules(selection, harness) if isinstance(selection, TemplateModuleSelection)
                        else resolve_directory(selection, harness))
        except ValueError as error:
            raise ApiError(str(error), 409) from None
    checks = []

    def check(identity, ok, blocking):
        checks.append({"id": identity, "status": "PASS" if ok else "FAIL", "blocks": blocking})

    check("java_executable", shutil.which("java") is not None, "BUILD")
    check("maven_executable", shutil.which(maven) is not None, "BUILD")
    if not isinstance(selection, ManagedRuntimeWorkbenchSelection):
        check("service_template", (project / "templates/local-readonly-service/pom.xml").is_file(), "BUILD")
    if selection.runtimeId == 'openjiuwen-python-0.1.18':
        import subprocess
        env = os.environ if environment is None else environment
        try:
            checked = subprocess.run([env.get('FH_PYTHON_EXECUTABLE', 'python3'), '-I', '-c',
                'import sys;sys.exit(0 if (3,11)<=sys.version_info[:2]<(3,14) else 1)'], capture_output=True, timeout=5)
            compatible = checked.returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            compatible = False
        check('python_311_313_executable', compatible, 'BUILD')
        check('python_service_template', (project/'templates/python-readonly-service/start_python.py').is_file(), 'BUILD')
    try:
        if isinstance(selection, ManagedRuntimeWorkbenchSelection):
            solution, resolved, unified_plan = selection.resolve_details(project, harness, 'preflight-only', method_snapshot,
                                                                         environment=environment)
            from .managed_runtime_support import require_backend_admission, resolve_binding, template_path
            record, host, _, _ = resolve_binding(project, selection.runtimeId, selection.hostId,
                selection.profileRef.model_dump(), environment=environment)
            check('service_template', (template_path(project, host) / 'pom.xml').is_file(), 'BUILD')
            observation = require_backend_admission(project, harness, solution, unified_plan['runtimeBinding'], environment,
                                                    method_snapshot=method_snapshot)
            unified_plan['runtimeObservationAdmission'] = observation
            check('managed_runtime_required_observation', True, 'BUILD')
        elif isinstance(selection, UnifiedWorkbenchSelection):
            solution, resolved, unified_plan = selection.resolve_details(project, harness, "preflight-only", method_snapshot)
        else:
            solution = (selection.resolve(project, harness, "preflight-only")
                    if isinstance(selection, ManagedWorkbenchSelection)
                    else solution_for(selection.capabilityIds, "preflight-only"))
    except (OSError, ValueError, KeyError, TypeError) as error:
        safe_codes = {"RUNTIME_NOT_SUPPORTED", "PYTHON_COMPOSITION_UNSUPPORTED", "DIRECTORY_CHANGED",
                      "MANAGED_METHOD_UNSUPPORTED", "TEMPLATE_BINDING_MISMATCH", "MODULE_SELECTION_MISMATCH",
                      "MODULE_NOT_IN_TEMPLATE", "MODULE_UNAVAILABLE", "TEMPLATE_UNAVAILABLE",
                      "COMPONENT_SELECTION_MISMATCH", "ASSEMBLY_METHOD_SNAPSHOT_MISMATCH",
                      "ASSET_CAPABILITY_NOT_INSTALLED", "ASSEMBLY_DUPLICATE_ASSET", "MANAGED_HOST_UNSUPPORTED"}
        from .managed_runtime_support import SAFE_ERRORS
        safe_codes |= SAFE_ERRORS
        code = str(error) if isinstance(selection, UnifiedWorkbenchSelection) and str(error) in safe_codes else "COMPONENT_SELECTION_REJECTED"
        raise ApiError(code, 409) from None
    from .component_plan import qualify_solution
    component_admission = None
    try:
        component_admission = (unified_plan if isinstance(selection, UnifiedWorkbenchSelection)
                               else qualify_solution(project, harness, solution))["evidence"]
        check("component_assembly_admission", True, "BUILD")
    except (OSError, ValueError, KeyError, TypeError):
        check("component_assembly_admission", False, "BUILD")
    refs = [solution["profileRef"], *solution["domainPackRefs"], *solution["capabilityPackRefs"]]
    # An external evaluation pack is catalog-governed build input, not a file
    # inside the Harness archive. Repository-backed packs remain checked here.
    if solution["evaluationPackRef"]["source"].startswith("repo://"):
        refs.append(solution["evaluationPackRef"])
    assets = True
    for ref in refs:
        if isinstance(selection, UnifiedWorkbenchSelection) and method_snapshot is not None and ref["id"] == method_snapshot["assetId"]:
            continue
        path = harness / ref["source"].removeprefix("repo://")
        assets = assets and path.exists() and path.resolve().is_relative_to(harness.resolve())
    check("selected_assets_present", assets, "BUILD")
    connections = connection_status(environment)
    if CASHFLOW & set(selection.capabilityIds):
        env = os.environ if environment is None else environment
        policy = env.get("FH_CASHFLOW_ACCESS_POLICY")
        check("cashflow_policy_configuration", bool(policy) and Path(policy).is_file()
              and os.access(policy, os.R_OK), "START")
    if isinstance(selection, MultiModelSelection):
        try:
            registry = ModelRegistry(environment)
            snapshot = registry.snapshot(selection.selectedModelRefs, selection.defaultModelRef, selection.modelCatalogRevision)
        except ModelRegistryError as error:
            raise ApiError(str(error), 409) from None
        connections["connections"] = [item for item in connections["connections"] if item["kind"] != "MODEL"]
        for ref in selection.selectedModelRefs:
            connections["connections"].append({"id": ref, "kind": "MODEL", "configurationStatus": registry.status(ref),
                                                "liveValidation": "NOT_RUN"})
    else:
        snapshot = None
    for connection in connections["connections"]:
        check(connection["id"] + "_configuration", connection["configurationStatus"] == "CONFIGURED_UNTESTED", "START")
    can_build = all(item["status"] == "PASS" for item in checks if item["blocks"] == "BUILD")
    can_start = can_build and all(item["status"] == "PASS" for item in checks)
    return {"schemaVersion": API_VERSION, "selection": selection.model_dump(), "checks": checks, "modelSelection": snapshot,
            "componentAdmission": component_admission,
            "directorySelection": resolved,
            "canBuild": can_build, "startPrerequisitesSatisfied": can_start,
            "connectivityStatus": "NOT_CHECKED", "authorizationStatus": "RUNTIME_CHECK_REQUIRED",
            "runtimeVersionStatus": "VERIFIED_DURING_BUILD_AND_START", "connections": connections["connections"],
            "networkRequests": 0, "preflightIsAuthorization": False}


def artifact_identity(candidate: Path, report: dict, build_id: str):
    import json
    from .service import digest
    lock_file = candidate / "service-lock.json"
    expected = report.get("lockSha256")
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        return None
    try:
        if digest(lock_file) != expected:
            return None
        lock = json.loads(lock_file.read_text(encoding="utf-8"))
        if lock.get("buildId") != build_id or lock.get("verification") != "COMPILED":
            return None
    except (OSError, ValueError, AttributeError):
        return None
    return {"artifactId": "sha256:" + expected, "lockSha256": expected,
            "verification": "MANIFEST_MATCHED_FILES_RECHECKED_AT_START"}


def stage_view(report, runtime_language=None):
    observed = {item.get("name"): item for item in report.get("stages", []) if isinstance(item, dict)}
    stages = []
    names = ("asset_reassembly",) if "asset_reassembly" in observed else STAGES
    if "component_installation" in observed:
        names += ("component_installation",)
    if "observation_admission" in observed:
        names += ("observation_admission",)
    if runtime_language == 'python' or report.get('runtimeLanguage') == 'python':
        names += ('python_installation_plan', 'python_environment', 'python_resolve_wheels', 'python_locked_install', 'python_dependency_check')
    planned = report.get('plannedStages')
    known = {*STAGES, 'asset_reassembly', 'component_installation', 'observation_admission',
             'python_installation_plan', 'python_environment', 'python_resolve_wheels',
             'python_locked_install', 'python_dependency_check'}
    if (isinstance(planned, list) and 0 < len(planned) <= len(known)
            and all(isinstance(name, str) and name in known for name in planned)
            and len(planned) == len(set(planned))):
        # The backend plans stages; only actual stage receipts determine success.
        names = tuple(planned)
        names += tuple(name for name in observed if name in known and name not in names)
    for name in names:
        item = observed.get(name, {})
        state = {"PASS": "SUCCEEDED", "FAIL": "FAILED", "RUNNING": "RUNNING"}.get(item.get("status"), "PENDING")
        stages.append({"id": name, "status": state,
                       "elapsedMillis": item.get("elapsedMillis") if type(item.get("elapsedMillis")) is int else None,
                       "error": {"code": "BUILD_STAGE_FAILED", "stageId": name} if state == "FAILED" else None})
    return stages
