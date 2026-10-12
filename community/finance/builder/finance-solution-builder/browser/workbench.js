import { renderMarkdown } from "./markdown.js";
import { executionHistory } from "./execution-history.js";
import {defaultUI, terminals, terminalName, themeName} from "./presentation.js";
import {devicePreview, resizeDevicePreviews} from "./device-preview.js";
import {methodAssets} from "./method-assets.js";
import {plannedCapabilities, methodGuideView, sameAsset, assetRef, componentEntries, bindingCapabilities, bindingModules, packChoices, catalogCapabilities} from "./business-assets.js";
import {capabilityDetails} from "./capability-details.js";
import {industryStandards} from "./industry-standards.js";
import {captureScroll, restoreScroll} from "./message-scroll.js";
import {renderWorkbench, installNavigation} from "./workbench-shell.js";
import {runtimePresentation, selectionSchemaVersion, runtimeHostBinding, runtimeStatusLabel} from "./runtime-presentation.js";
import {createElement, Blocks, UserRoundCheck, ShieldCheck, LockKeyhole, FileCheck2,
  Hammer, RefreshCw, Play, Square, Landmark, ShieldOff, MessagesSquare, ArrowUp,
  Download, Check, Plus, Trash2, Smartphone, Monitor, Mic, PanelRightClose,
  ArrowLeft, CircleHelp, LogOut, X, Maximize2, SlidersHorizontal, Wallet, ChartPie,
  ArrowLeftRight, CreditCard, BriefcaseBusiness, ChartColumnIncreasing, Clock3,
  Info, Upload, BookOpen, House, Minimize2, ChartColumn, Menu, PanelLeftOpen,
  PanelLeftClose, Plug, ClipboardCheck, FileText, Package, ArrowUpRight, ZoomIn, ZoomOut, MousePointer2, CornerDownRight} from "lucide";

(() => {
  "use strict";
  const $ = (s) => document.querySelector(s),
    app = $("#app"),
    drawer = $("#drawer");
  const preferredDefaultModelRef = "model-ea-002";
  installNavigation(app);
  let moduleDraft = null;
  let previewDraft = null;
  let horizontalDrag = null, suppressedStripClick = null;
  const visibleModels = () => state.catalog.models
    .filter(m => m.bindingStatus === "CONFIGURED_UNTESTED")
    .sort((a, b) => Number(state.models.has(b.modelRef)) - Number(state.models.has(a.modelRef)))
    .slice(0, 3);
  const esc = (v) =>
    String(v ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
  const iconNodes=Object.fromEntries(Object.entries({Blocks,UserRoundCheck,ShieldCheck,
    LockKeyhole,FileCheck2,Hammer,RefreshCw,Play,Square,Landmark,ShieldOff,
    MessagesSquare,ArrowUp,Download,Check,Plus,Trash2,Smartphone,Monitor,Mic,
    PanelRightClose,ArrowLeft,CircleHelp,LogOut,X,Maximize2,SlidersHorizontal,
    Wallet,ChartPie,ArrowLeftRight,CreditCard,BriefcaseBusiness,ChartColumnIncreasing,
    Clock3,Info,Upload,BookOpen,House,Minimize2,ChartColumn,Menu,PanelLeftOpen,
    PanelLeftClose,Plug,ClipboardCheck,FileText,Package,ArrowUpRight,ZoomIn,ZoomOut,MousePointer2,CornerDownRight}).map(([name,node])=>[name.replace(/([a-z])([A-Z0-9])/g,'$1-$2').toLowerCase(),node]));
  const iconCache=new Map();
  const inlineIcon=(name,attrs)=>{
    const [tag,defaults,children]=iconNodes[name]||Blocks;
    return createElement([tag,{...defaults,...attrs},children]);
  };
  // Inline Lucide geometry avoids external-use instances losing their glyph on DOM replacement.
  const icon = name => {
    if(!iconCache.has(name)){
      const shape=['arrow-up-right','arrow-down'].includes(name)?'arrow-up':name==='arrow-right'?'arrow-left':name;
      const variant=name==='arrow-up-right'?'arrow-diagonal':name==='arrow-down'?'arrow-down':name==='arrow-right'?'arrow-right':'';
      iconCache.set(name,inlineIcon(shape,{class:`icon ${variant}`,'aria-hidden':'true'}).outerHTML);
    }
    return iconCache.get(name);
  };
  const names = {}, descriptions = {};
  const stages = {
    asset_reassembly: "方法装配与制品核验",
    harness_contracts: "合同与资产校验",
    harness_tests_install: "Harness 回归与制品安装",
    candidate_package: "Agent 编译与打包",
    component_installation: "组件安装准入",
    observation_admission: "观测要求核验",
    python_installation_plan: "核验只读安装计划",
    python_environment: "创建 Python 隔离环境",
    python_resolve_wheels: "锁定 Python SDK 与依赖",
    python_locked_install: "按摘要安装 Runtime",
    python_dependency_check: "核验 Python 依赖",
  };
  const stageDetails = {
    python_installation_plan: ["实际 Provider · Schema · 资产摘要", "校验计划与运行时注册分别记录"],
    python_environment: ["独立虚拟环境", "与 Builder 运行环境分离"],
    python_resolve_wheels: ["OpenJiuwen Python 0.1.18", "记录实际 wheel 版本、平台与 SHA256"],
    python_locked_install: ["离线 wheel 集合 · 强制摘要校验", "安装原生 Python Runtime，并连接 Harness 金融执行桥"],
    python_dependency_check: ["pip check · 制品清单", "核验已安装依赖与独立交付文件"],
    asset_reassembly: ["版本化方法 · 已核验 Host / Harness JAR", "装入冻结方法与参考资料，生成新的安装清单"],
    harness_contracts: [
      "JSON Schema · Pack / Provider 合同",
      "核对能力依赖、只读边界与资产摘要",
    ],
    harness_tests_install: [
      "Maven reactor · Harness JAR",
      "运行公共回归，安装当前 Harness 制品",
    ],
    candidate_package: [
      "候选 Agent 制品",
      "装配选定工具、编译宿主，锁定模型与运行依赖",
    ],
    component_installation: [
      "能力、来源与观测资格",
      "核对实际安装组件及所需执行证据",
    ],
    observation_admission: [
      "运行记录与采集器资格",
      "核对当前制品、采集器证据及存储可用性",
    ],
  };
  const states = {
    QUEUED: "排队中",
    RUNNING: "构建中",
    SUCCEEDED: "构建完成",
    FAILED: "失败",
    INTERRUPTED: "已中断",
    READY: "运行中",
    NOT_STARTED: "未启动",
    STOPPED: "已停止",
    STARTING: "启动中",
  };
  const statuses = {
    UNBOUND: "接入待配置",
    CONFIGURATION_MISSING: "连接配置缺失",
    CONFIGURED_UNTESTED: "已绑定 · 调用待验证",
    DISABLED: "已禁用",
    PROTOCOL_UNSUPPORTED: "协议不支持",
  };
  const errors = {
    DIRECTORY_CHANGED: "目录已更新，请刷新后核对方案。",
    AUDIENCE_NOT_BUILDABLE: "当前使用对象尚未接通可信身份与宿主，不能构建。",
    COMPONENT_UNAVAILABLE: "所选组件不可用，请重新选择。",
    COMPONENT_SELECTION_REJECTED: "能力包组合或来源引用未通过校验，请核对版本、数据提供方与所选框架。",
    COMPONENT_SELECTION_MISMATCH: "组件与能力清单不一致，请重新选择。",
    TEMPLATE_UNAVAILABLE: "场景模板不可用。",
    MODULE_UNAVAILABLE: "该业务模块尚未接入，不能构建。",
    MODULE_NOT_IN_TEMPLATE: "业务模块不属于当前模板。",
    MODULE_SELECTION_MISMATCH: "业务模块与实际操作清单不一致，请重新选择。",
    TEMPLATE_BINDING_MISMATCH: "模板、框架或连接不兼容。",
    RUNTIME_NOT_SUPPORTED: "所选框架尚未接入，不能构建。",
    MANAGED_RUNTIME_DISABLED: "所选框架尚未开放，请选择当前可用的运行框架。",
    MANAGED_RUNTIME_UNAVAILABLE: "未找到所选框架的受管支持声明，请核对当前目录。",
    MANAGED_RUNTIME_DECLARATION_INVALID: "运行框架支持声明不完整，需要维护者检查版本与宿主配置。",
    MANAGED_HOST_AMBIGUOUS: "存在多个匹配宿主，尚未确定唯一接入；请核对宿主与使用范围。",
    MANAGED_HOST_BINDING_MISMATCH: "宿主、运行框架与使用范围不匹配，请重新核对组合。",
    MANAGED_HOST_SOURCE_INVALID: "宿主模板来源无法核验，请联系维护者检查受管制品。",
    MANAGED_RUNTIME_VERSION_MISMATCH: "运行框架版本与已验证的接入版本不一致，不能构建。",
    MANAGED_RUNTIME_CAPABILITY_UNSUPPORTED: "所选能力组合不在当前框架已验证的范围内；不会自动更换框架或增加工具。",
    MANAGED_RUNTIME_QUALIFICATION_REQUIRED: "缺少当前运行制品的观测资格记录，需要维护者完成验证。",
    MANAGED_RUNTIME_QUALIFICATION_REJECTED: "当前运行制品未通过观测资格核验，请维护者检查版本和验证记录。",
    RUNTIME_QUALIFICATION_UNREADABLE: "运行服务无法读取已配置的观测资格证据，请维护者检查发布目录权限。",
    RUNTIME_QUALIFICATION_DIGEST_MISSING: "已配置观测资格文件，但缺少对应摘要，请维护者补齐发布配置。",
    RUNTIME_QUALIFICATION_DIGEST_MISMATCH: "观测资格文件与配置摘要不一致，请维护者核对当前发布版本。",
    MANAGED_RUNTIME_REUSE_UNSUPPORTED: "该运行框架不能复用此宿主制品，请按所选框架重新构建。",
    MANAGED_METHOD_RUNTIME_UNSUPPORTED: "此发布包的兼容声明不支持所选框架。请选择兼容版本，原发布包及验证记录不会改变。",
    PYTHON_HOST_REUSE_UNSUPPORTED: "Python 专业方法版本需要重新构建，暂不复用已有 Python 宿主制品。",
    BUILD_RETENTION_LIMIT_REACHED: "已达到构建保留上限，请先停止并删除不再使用的 Agent。",
    INSUFFICIENT_BUILD_STORAGE: "服务器可用空间不足，请先清理已归档的构建产物。",
    AUTHORIZATION_REJECTED: "授权已失效，相关对话已清除。",
    MODEL_OR_RUNTIME_FAILED: "模型或 Runtime 调用失败，会话已清除。",
    MODEL_NOT_SELECTED: "该模型不在本次构建允许列表内。",
    MODEL_CONNECTION_UNAVAILABLE:
      "所选模型尚未绑定有效连接；请选已绑定模型重新构建。",
    MODEL_CONFIGURATION_CHANGED: "模型连接版本已改变，请重新构建。",
    START_PREFLIGHT_FAILED: "启动前置配置不完整，请查看模型与银行连接。",
    BUSINESS_TIME_CONFIGURATION_INCOMPLETE: "服务端业务时间配置不完整，请联系维护者核对业务日期、时区和来源。",
    BUSINESS_TIME_CONFIGURATION_INVALID: "服务端业务时间配置无效，请联系维护者核对业务日期、时区和来源。",
    MODEL_CATALOG_CHANGED: "模型目录已变化，请刷新目录后重新选择。",
    MODEL_UNKNOWN: "模型引用不在当前目录中。",
    MODEL_NOT_ENABLED: "所选模型已禁用或协议不受支持。",
    MODEL_REGISTRY_UNAVAILABLE: "服务端模型连接注册表不可用。",
    MODEL_REGISTRY_INVALID: "服务端模型连接配置不符合契约。",
    MODEL_CATALOG_INVALID: "模型目录格式不符合契约。",
    INSTANCE_CHANGED: "实例或会话已变化，旧响应已丢弃。",
    BUILD_PREFLIGHT_FAILED: "构建前置检查未通过。",
    SESSION_ALREADY_ACTIVE: "此实例已有会话，请刷新状态。",
    AGENT_STOP_REQUIRED: "请先停止 Agent；构建中或状态未确认的实例不能删除。",
    TASK_RECOVERY_STATE_RETAINED: "仍有任务恢复记录；请先查证并处理原任务，不能直接删除。",
    TASK_STATE_IN_USE: "任务状态仍被运行进程占用；请停止该实例后重试。",
    AGENT_DELETE_FAILED: "部分文件清理失败，请稍后重试。",
    ACCESS_REJECTED: "记录已删除或不属于当前工作空间，请刷新列表。",
  };
  const state = {
    page: "create",
    scenario: "mobile-banking",
    businessDomain: "business-processing",
    taskBlueprint: "account-cashflow-service",
    customComposition: false,
    runtime: "openjiuwen-java-0.1.15",
    packBindings: [],
    modules: new Set(),
    migrationWarnings: [],
    missingMethod: null,
    moduleDetail: null,
    defaultsInitialized: false,
    expanded: false,
    directoryQuery: "",
    directoryDomain: "",
    template: "MOBILE_WEB",
    interface: defaultUI(),
    previewPage: "RESULT",
    models: new Set(),
    defaultModel: "",
    catalog: null,
    connections: [],
    builds: [],
    active: null,
    csrf: "",
    busy: false,
    notice: "",
    history: new Map(),
    optimizationViews: new Map(),
    optimizationNodes: new Map(),
    epoch: 0,
    dialogDraft: null,
    returnFocus: null,
    editing: null,
    serverMode: false,
    selectedMethod: null,
    methodVersions: [],
  };
  const assets = methodAssets({api, esc, icon, markdown:renderMarkdown, rerender:()=>render(),
    directory:()=>businessDirectory(),
    useBusiness:id=>{
      if(!unifiedReady()){state.page='create';render();return;}
      const part=moduleDirectory().components.find(p=>p.id===id);
      if(!part)return;
      for(const entry of componentEntries([part],managedEntries()))addPack(entry);
      syncModules();
      state.page='create';render();
    },
    models:()=>state.catalog?.models||[], capabilities:()=>state.catalog?.capabilities||[], refreshChoices:items=>{state.methodVersions=items.flatMap(a=>a.versions);},
    choose:version=>{if(selectMethod(version)===false)return false;state.page='create';render();return true;}});
  const model = (ref) => state.catalog?.models.find((x) => x.modelRef === ref);
  const modelName = (ref) => model(ref)?.displayName || ref || "服务端模型";
  const categoryName = (value) =>
    state.catalog?.directory.domains.find(d => d.id === value)?.label ||
    ({ OpenAI: "OpenAI 兼容" })[value] || value;
  const active = () => state.builds.find((x) => x.buildId === state.active);
  const memory = (id) => {
    if (!state.history.has(id))
      state.history.set(id, {
        sessionId: null,
        modelRef: null,
        messages: [],
        archives: [],
        evidence: null,
      });
    return state.history.get(id);
  };
  function clear(id) {
    state.history.delete(id);
    state.epoch++;
    if (state.active === id) {
      drawer.close();
      drawer.replaceChildren();
    }
  }
  const button = (label, action, cls = "secondary-button", disabled = false) =>
    `<button class="${cls}" data-action="${action}" ${disabled ? "disabled" : ""}>${label}</button>`;
  async function api(path, body) {
    const response = await fetch(path, {
      method: body === undefined ? "GET" : "POST",
      cache: "no-store",
      headers:
        body === undefined
          ? {}
          : { "Content-Type": "application/json", "X-CSRF-Token": state.csrf },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const value = await response.json();
    if (!response.ok) {
      const e = new Error(
        errors[value.error?.code] ||
          errors[value.code] ||
          value.error?.code ||
          value.code ||
          value.error ||
          "请求失败",
      );
      e.data = value;
      e.status = response.status;
      throw e;
    }
    return value;
  }
  function storeBuild(b) {
    const i = state.builds.findIndex((x) => x.buildId === b.buildId);
    if (i < 0) state.builds.unshift(b);
    else state.builds[i] = b;
  }
  const executionRecords = executionHistory({api, esc, icon, render:()=>render()});
  app.addEventListener('click', executionRecords.click);
  app.addEventListener('submit', executionRecords.submit);
  function shell(body, title) {
    return `<div class="app-shell"><aside class="sidebar"><button class="brand" data-nav="create" aria-label="Finance Harness Studio 首页"><img class="brand-logo-full" src="/finance-harness-logo.png" alt="Finance Harness"><img class="brand-logo-mark" src="/finance-harness-mark.png" alt="" aria-hidden="true"></button><nav class="sidebar-nav" aria-label="工作空间">${[
      ["create", "hammer", "创建 Agent"],
      ["agents", "blocks", "我的 Agent"],
      ["history", "clock-3", "运行记录"],
      ["assets", "file-check-2", "业务资产"],
      ["connections", "landmark", "连接管理"],
    ]
      .map(
        ([id, i, label]) =>
          `<button class="nav-item ${state.page === id ? "active" : ""}" data-nav="${id}" title="${label}">${icon(i)}<span>${label}</span></button>`,
      )
      .join(
        "",
      )}</nav><footer class="sidebar-footer"><button class="nav-item standards-link ${state.page === 'standards' ? 'active' : ''}" data-nav="standards" title="业界标准" ${state.page === 'standards' ? 'aria-current="page"' : ''}>${icon('book-open')}<span>业界标准</span></button>${state.serverMode ? `<a class="nav-item developer-guide-link" href="/developer-guide/" target="_blank" rel="noopener noreferrer" title="开发指导（新窗口）">${icon("file-check-2")}<span>开发指导</span></a>` : ''}<div class="sidebar-note">${state.serverMode ? '演示' : '本地'}工作空间</div></footer></aside><main class="main" id="main-content"><header class="topbar"><div class="breadcrumbs">工作空间 <span>/</span> <b>${title}</b></div><span class="proto-badge">${state.catalog.executionMode === "CONTROLLED_TEST" ? "受控模型测试 · 模拟银行" : "本地模拟银行 · 合成数据"}</span></header><div class="workspace-content">${notice()}${body}</div></main></div>`;
  }
  const notice = () =>
    state.notice
      ? `<div class="live-error" role="alert">${esc(state.notice)}</div>`
      : "";
  const selection = () => ({
    extension_type: "finance-workbench.selection",
    schema_version: selectionSchemaVersion(framework()),
    ...selectedRuntimeHost().fields,
    ...(state.selectedMethod ? {methodRef: {assetId:state.selectedMethod.assetId,version:state.selectedMethod.version,releaseDigest:state.selectedMethod.releaseDigest}} : {}),
    scenarioTemplateId: state.scenario,
    moduleIds: [...state.modules].sort(),
    directoryRevision: moduleDirectory().revision,
    packBindings: state.packBindings.map(binding=>({pack:assetRef(binding.pack),provider:assetRef(binding.provider)})),
    runtimeId: state.runtime,
    applicationTemplateId: "banking-readonly-web-v1",
    presentation: state.template,
    interfaceConfig: {...state.interface, voiceInput: state.template === "ATM_WEB" ? false : state.interface.voiceInput},
    capabilityIds: selectedCapabilityIds(),
    selectedModelRefs: [...state.models].sort(),
    defaultModelRef: state.defaultModel,
    modelCatalogRevision: state.catalog.modelCatalogRevision,
    bankConnectionId: "local-simulator",
  });
  const moduleDirectory = () => state.catalog?.unifiedTemplateModuleDirectory || state.catalog?.unifiedCatalog?.templateModuleDirectory;
  const businessDirectory = () => state.catalog?.unifiedDirectory || state.catalog?.unifiedCatalog?.directory;
  const unifiedReady = () => Boolean(moduleDirectory()?.revision && businessDirectory()?.revision &&
    Array.isArray(moduleDirectory().businessDomains) && Array.isArray(moduleDirectory().taskBlueprints) &&
    Array.isArray(moduleDirectory().moduleDomainAssignments) &&
    Array.isArray(moduleDirectory().templates) && Array.isArray(moduleDirectory().modules) &&
    Array.isArray(moduleDirectory().components) && Array.isArray(moduleDirectory().runtimes) &&
    Array.isArray(state.catalog?.unifiedManagedDirectory?.entries || state.catalog?.unifiedCatalog?.entries));
  const scenario = () => moduleDirectory().templates.find(t => t.id === state.scenario);
  const businessDomain = () => moduleDirectory().businessDomains.find(item => item.id === state.businessDomain);
  const taskBlueprint = () => moduleDirectory().taskBlueprints.find(item => item.id === state.taskBlueprint);
  const taskBlueprintsForDomain = () => moduleDirectory().taskBlueprints.filter(
    item => (item.businessDomainId === state.businessDomain || item.relatedBusinessDomainIds?.includes(state.businessDomain)) && item.availability !== 'UNAVAILABLE',
  ).sort((left,right)=>Number(right.businessDomainId===state.businessDomain)-Number(left.businessDomainId===state.businessDomain));
  const domainSourceModuleCount = () => moduleDirectory().moduleDomainAssignments.filter(
    item => item.businessDomainId === state.businessDomain || item.relatedBusinessDomainIds?.includes(state.businessDomain),
  ).length;
  const framework = () => moduleDirectory().runtimes.find(r => r.id === state.runtime);
  const availableModules = () => moduleDirectory().modules;
  const managedEntries = () => state.catalog?.unifiedManagedDirectory?.entries || state.catalog?.unifiedCatalog?.entries || [];
  const selectedRuntimeHost = () => runtimeHostBinding(framework(), state.packBindings, managedEntries(), state.template, 'banking-readonly-web-v1');
  const selectedCapabilityIds = () => bindingCapabilities(state.packBindings,managedEntries());
  const moduleParts = module => moduleDirectory().components.filter(part=>module.componentIds.includes(part.id));
  const moduleEntries = module => componentEntries(moduleParts(module),managedEntries());
  const canSelectModule = module => moduleEntries(module).length>0;
  const sameMethodVersion = (a,b) => Boolean(a&&b&&a.assetId===b.assetId&&a.version===b.version&&a.releaseDigest===b.releaseDigest);
  const syncModules = () => {state.modules=new Set(bindingModules(state.packBindings,moduleDirectory()));};
  function addPack(entry) {
    if(state.packBindings.some(binding=>sameAsset(binding.pack,entry.pack)))return;
    const declared=moduleDirectory().components.flatMap(part=>part.packBindings||[]).find(binding=>sameAsset(binding.pack,entry.pack));
    const provider=declared?.provider || (entry.providers.length===1?entry.providers[0]:null);
    state.packBindings.push({pack:assetRef(entry.pack),...(provider?{provider:assetRef(provider)}:{})});
  }
  function selectMethod(version) {
    if(!unifiedReady()){toast('统一目录暂不可用，请刷新后选择。');return false;}
    if(state.selectedMethod&&version&&state.selectedMethod.assetId!==version.assetId&&
       !confirm('当前已选择另一专业方法，替换后保留现有业务能力选择？'))return false;
    const deps=version?.capabilityIds||[];
    state.selectedMethod=version;
    state.missingMethod=null;
    for(const id of deps){
      if(selectedCapabilityIds().includes(id))continue;
      const entries=managedEntries().filter(entry=>entry.capabilities.some(c=>c.capabilityId===id));
      if(entries.length===1)addPack(entries[0]);
    }
    syncModules();
  }
  function selectModule(id, checked) {
    const module=availableModules().find(m=>m.id===id&&canSelectModule(m));if(!module)return;
    const entries=moduleEntries(module);
    if(checked)entries.forEach(addPack);
    else state.packBindings=state.packBindings.filter(binding=>!entries.some(entry=>sameAsset(binding.pack,entry.pack)));
    syncModules();
  }
  function selectionBlockers() {
    if(!unifiedReady())return ['统一能力目录暂不可用，新建已阻止。请刷新目录；原 Agent 仍可查看和启动。'];
    const reasons=[];
    reasons.push(...state.migrationWarnings);
    if(!taskBlueprint() && !state.customComposition && !state.editing)reasons.push('当前业务板块没有已验证任务场景，请选择可用场景。');
    if(!['PARTIAL','AVAILABLE'].includes(scenario()?.status))reasons.push('当前场景模板尚未接入。');
    if(framework()?.status!=='AVAILABLE')reasons.push('所选框架尚未接入，已保留能力选择。');
    if(!state.packBindings.length)reasons.push('请选择业务能力包。');
    const hostBindingError = selectedRuntimeHost().error;
    if(hostBindingError)reasons.push(errors[hostBindingError]);
    for(const binding of state.packBindings){
      const entry=managedEntries().find(value=>sameAsset(value.pack,binding.pack));
      if(!entry){reasons.push(`原能力包 ${binding.pack.id} @ ${binding.pack.version} 不可用，原引用已保留。`);continue;}
      if(!binding.provider)reasons.push(`${entry.label}：请选择数据提供方。`);
      else if(!entry.providers.some(provider=>sameAsset(provider,binding.provider)))reasons.push(`${entry.label}：原数据提供方版本不可用，原引用已保留。`);
      if(!entry.runtimeIds?.includes(state.runtime))reasons.push(`${entry.label} 不支持所选框架，已保留选择。`);
      if(state.selectedMethod&&entry.capabilities.some(c=>c.operation==='TASK'))reasons.push('办理能力与私有 SOP 组合尚未通过后端验证，请明确调整选择。');
      else if(state.selectedMethod&&entry.methodCustomizationSupported!==true)reasons.push(`${entry.label} 暂不支持与私有 SOP 组合，请明确调整选择。`);
    }
    if(state.missingMethod)reasons.push('原专业方法版本不可用，请明确选择可用方法；原 Agent 未修改。');
    if(state.selectedMethod&&!state.methodVersions.some(v=>sameMethodVersion(v,state.selectedMethod)))reasons.push('已选专业方法版本已不可用或已归档，原引用已保留；请明确调整方法选择。');
    if(state.selectedMethod?.capabilityIds.some(id=>!selectedCapabilityIds().includes(id)))reasons.push('已选专业方法缺少依赖能力包，请补充选择或更换方法。');
    if([...state.modules].some(id=>!availableModules().some(module=>module.id===id)))reasons.push('原业务模块不在当前目录中，请核对新草稿。');
    return [...new Set(reasons)];
  }
  const buildableSelection = () => selectionBlockers().length===0;
  function create() {
    if(!unifiedReady())return shell(`<div class="page-title-row"><h1>创建金融 Agent</h1></div><section class="build-blockers" role="status"><h2>构建前需处理</h2><p>${esc(selectionBlockers()[0])}</p>${button('刷新目录','refresh')}${button('查看原 Agent','list')}</section>${button('开始构建','build','primary-button',true)}`, '创建 Agent');
    const dir = moduleDirectory(), chosen = scenario(), rt = framework();
    const blockers=[...selectionBlockers(),
      ...(!state.models.has(state.defaultModel)||!selectedModelsReady()?['未选择可用模型连接。']:[]),
      ...(!bankReady()?['模拟银行连接缺少服务端配置。']:[])];
    return shell(`
      <div class="page-title-row"><h1>创建金融 Agent</h1><span class="catalog-edition">目录 ${esc(dir.version)}</span></div>
      <div class="builder-columns"><div class="module-builder">
        <section class="config-section">
          <div class="section-head template-head"><h2><span class="step-number">1</span>业务板块</h2></div>
          <div class="scene-options template-strip business-domain-strip" role="radiogroup" aria-label="业务板块">${dir.businessDomains.map(businessDomainOption).join('')}</div>
          <p class="selection-help">业务板块用于组织任务，不改变能力权限、运行框架或已有 Agent。</p>
        </section>
        <section class="config-section">
          <div class="section-head template-head"><h2><span class="step-number">2</span>任务场景</h2></div>
          ${taskBlueprintsForDomain().length || domainSourceModuleCount()
            ? `<div class="scene-options template-strip task-blueprint-strip" role="radiogroup" aria-label="任务场景">${taskBlueprintsForDomain().map(taskBlueprintOption).join('')}<button class="scene-option ${state.customComposition ? 'is-selected' : ''}" data-custom-composition="true" role="radio" aria-checked="${state.customComposition}" tabindex="${state.customComposition ? 0 : -1}">${icon('blocks')}<span><strong>自定义组合</strong><small>从当前板块明确选择能力</small></span>${state.customComposition?`<span class="scene-check">${icon('check')}</span>`:''}</button></div>`
            : `<div class="directory-empty" role="status">${esc(businessDomain()?.label || '当前板块')}暂无已验证任务场景，不能据此构建 Agent。</div>`}
          <p class="selection-help">任务场景引用已登记能力；手机银行等入口作为渠道信息保留，不再作为一级模板。</p>
        </section>
        <section class="config-section">
          <div class="section-head module-head"><h2><span class="step-number">3</span>能力模块</h2><div class="module-filter"><span id="module-count">已选 ${state.modules.size} / ${filteredModules().length}</span><input id="directory-search" type="search" aria-label="搜索能力模块" placeholder="搜索当前场景能力" value="${esc(state.directoryQuery)}"></div></div>
          <div id="module-results" class="module-grid">${moduleResults()}</div>
          <div id="module-pagination" class="module-pagination">${modulePagination()}</div>
        </section>
        <section class="config-section">
          <div class="section-head"><h2><span class="step-number">4</span>运行框架</h2></div>
          <div class="compact-config"><div class="runtime-config config-column"><h3>底层框架</h3>
          <div class="framework-options" role="radiogroup" aria-label="底层框架">
            ${dir.runtimes.map(r => `<label class="framework-option ${state.runtime === r.id ? "is-selected" : ""}"><input type="radio" name="runtime" data-runtime="${r.id}" ${state.runtime === r.id ? "checked" : ""} ${r.status !== "AVAILABLE" ? "disabled" : ""}><span><strong>${esc(r.label)}</strong><small>${esc(r.description || [r.language,r.version].filter(Boolean).join(" · ") || "金融接线待适配")}</small></span><small class="module-status">${esc(runtimeStatusLabel(r))}</small></label>`).join("")}
          </div>
          ${rt?.status !== "AVAILABLE" ? '<p class="directory-blocked" role="status">框架待接入，当前不能构建。</p>' : ""}
          </div><div class="shared-settings config-column">
          <div><h2>模型</h2><button class="connection-button" data-catalog="models">${icon("blocks")}<span class="connection-text"><strong>${esc(modelName(state.defaultModel))}</strong><small>${state.models.size} 个已选 · 默认模型</small><small class="${selectedModelsReady() ? "state-success" : "state-warning"}">${selectedModelsReady() ? "连接已绑定" : "连接待绑定"}</small></span></button></div>
          <div><h2>银行连接</h2><button class="connection-button" data-action="bank-picker">${icon("landmark")}<span class="connection-text"><strong>本地模拟银行</strong><small>${bankReady() ? "连接已配置" : "连接待配置"}</small></span></button></div>
          </div><div class="terminal-settings config-column"><h3>界面定制</h3><div class="customize-summary"><span class="theme-swatch theme-${state.interface.theme}" aria-hidden="true"></span><div><strong>${esc(state.interface.assistantName)}</strong><p>${themeName(state.interface.theme)}</p></div><button class="icon-button" data-action="customize" aria-label="界面定制" title="界面定制">${icon('sliders-horizontal')}</button></div></div></div>
        </section>
        <section class="config-section terminal-section"><div class="section-head"><h2><span class="step-number">5</span>应用终端</h2></div><div class="terminal-selection"><div class="presentation-options" role="radiogroup" aria-label="应用终端">${terminals.map(t=>`<button role="radio" aria-checked="${state.template===t.id}" tabindex="${state.template===t.id?0:-1}" data-template="${t.id}" class="${state.template===t.id?'selected':''}">${icon(t.icon)}${t.label}</button>`).join('')}</div><button class="secondary-button" data-action="enlarge-preview" aria-haspopup="dialog">${icon('maximize-2')}预览</button></div></section>
      </div></div>
      ${state.editing ? `<div class="edit-context">基于 <code>${esc(state.editing)}</code> 修改，原 Agent 保留。</div>` : ""}
      <section class="planned-selection"><h2>计划安装</h2>${state.packBindings.map(binding=>{const entry=managedEntries().find(e=>sameAsset(e.pack,binding.pack));return `<span>${esc(entry?.label||'原能力包不可用')} · v${esc(binding.pack.version)}</span>`;}).join('')}<small>${state.selectedMethod?esc(state.selectedMethod.name)+' · v'+esc(state.selectedMethod.version):state.missingMethod?'原专业方法版本不可用，引用已保留':'采用目录声明方法'}</small><details><summary>所选引用与版本</summary><pre>${esc(JSON.stringify({packBindings:state.packBindings,capabilityIds:selectedCapabilityIds(),methodRef:state.selectedMethod?{assetId:state.selectedMethod.assetId,version:state.selectedMethod.version,releaseDigest:state.selectedMethod.releaseDigest}:state.missingMethod},null,2))}</pre></details></section>
      ${blockers.length?`<section class="build-blockers" role="status"><h2>构建前需处理</h2><ul>${blockers.map(b=>`<li>${esc(b)}</li>`).join('')}</ul><button class="text-button" data-nav="connections">前往连接管理</button><p>当前页面仅查看连接状态；缺少配置请由管理员在服务端处理。返回后保留业务与方法选择。</p></section>`:''}
      <div class="build-footer module-build-footer"><div class="selection-summary"><strong>${esc(state.customComposition ? '自定义组合' : taskBlueprint()?.label || chosen?.label || '任务场景待确认')} · ${selectedCapabilityIds().length} 项业务能力</strong><small>${esc(businessDomain()?.label || '业务板块待确认')} · ${terminalName(state.template)} · ${themeName(state.interface.theme)} · ${state.models.size} 个模型</small></div>${button((state.editing ? "构建新版本 " : "开始构建 ") + icon("arrow-up-right"), "build", "primary-button", state.busy || blockers.length>0)}</div>
      <div class="guardrails module-controls"><span>${icon("shield-check")}身份与实授权限</span><span>操作边界</span><span>来源证据</span><span>强制启用</span><span>AG-UI</span></div>`, "创建 Agent");
  }
  function businessDomainOption(item) {
    return `<button class="scene-option ${state.businessDomain === item.id ? "is-selected" : ""}" data-business-domain="${item.id}" role="radio" aria-checked="${state.businessDomain === item.id}" tabindex="${state.businessDomain === item.id ? 0 : -1}">${icon(item.icon)}<span><strong>${esc(item.label)}</strong><small>${moduleDirectory().moduleDomainAssignments.filter(value=>value.businessDomainId===item.id).length} 个来源模块</small></span>${state.businessDomain === item.id ? `<span class="scene-check">${icon('check')}</span>` : ''}</button>`;
  }
  function taskBlueprintOption(item) {
    const kind=item.kind==='APPLICATION_CONFIGURATION'?'应用配置':`${item.moduleIds.length} 个能力模块`;
    return `<button class="scene-option ${state.taskBlueprint === item.id ? "is-selected" : ""}" data-task-blueprint="${item.id}" role="radio" aria-checked="${state.taskBlueprint === item.id}" tabindex="${state.taskBlueprint === item.id ? 0 : -1}">${icon(businessDomain()?.icon || 'blocks')}<span><strong>${esc(item.label)}</strong><small>${kind} · ${item.status==='PARTIAL'?'限定接入':'已接入'}</small></span>${state.taskBlueprint === item.id ? `<span class="scene-check">${icon('check')}</span>` : ''}</button>`;
  }
  function filteredModules() {
    const t = scenario();
    const q = state.directoryQuery.trim().toLowerCase();
    const blueprint = taskBlueprint();
    const domainModuleIds=moduleDirectory().moduleDomainAssignments
      .filter(item=>item.businessDomainId===state.businessDomain||item.relatedBusinessDomainIds?.includes(state.businessDomain)).map(item=>item.moduleId);
    const visibleIds = new Set(blueprint?.moduleIds || (state.customComposition ? [...domainModuleIds,...state.modules] : [...state.modules]));
    const modules = availableModules().filter(module => visibleIds.has(module.id));
    const featured=blueprint?.moduleIds||t?.featuredModuleIds||[];
    const ordered = [...featured.map(id => modules.find(m => m.id === id)), ...modules.filter(m => !featured.includes(m.id)).sort((a,b)=>Number(canSelectModule(b))-Number(canSelectModule(a)))].filter(Boolean);
    return q ? ordered.filter(m => [m.label, ...(m.aliases || []), m.scope, m.id].join(' ').toLowerCase().includes(q)) : ordered;
  }
  function modulePagination() {
    const total=filteredModules().length;
    return `<span role="status">共 ${total} 个模块 · 横向滑动查看更多</span><button class="icon-button" data-module-scroll="prev" aria-label="向左浏览模块" title="向左浏览模块">${icon('arrow-left')}</button><button class="icon-button" data-module-scroll="next" aria-label="向右浏览模块" title="向右浏览模块">${icon('arrow-right')}</button>`;
  }
  function moduleResults() {
    const filtered=filteredModules();
    return filtered.map(m => {
      const entries=moduleEntries(m), selected=entries.filter(entry=>state.packBindings.some(binding=>sameAsset(binding.pack,entry.pack)));
      const available=canSelectModule(m), task=entries.some(e=>e.capabilities.some(c=>c.operation==='TASK'));
      const summary=available?(selected.length?selected:entries).map(entry=>entry.label).join('、'):(m.scope||'尚未配置可安装能力');
      const interaction=available?` role="button" tabindex="0" data-module-detail="${esc(m.id)}" aria-label="${esc(m.label)}，${selected.length?'修改':'选择'}能力"`:' aria-disabled="true"';
      return `<article class="business-module ${selected.length?'is-selected':''} ${available?'':'is-pending'}" data-module-card="${esc(m.id)}"${interaction}><div class="module-card-heading"><span class="business-module-icon">${icon(m.icon||'blocks')}</span><strong>${esc(m.label)}</strong>${selected.length?'<span class="module-selected-tag">已选</span>':available?'':'<span class="module-pending-tag">规划中</span>'}</div><p class="module-card-summary">${esc(summary)}</p>${available?`<div class="module-card-footer"><span>${task?'模拟银行 · 提交前确认':'只读查询'}</span><span class="module-choice-hint">${selected.length?'修改选择':'选择能力'}</span></div>`:''}</article>`;
    }).join('') || '<p class="directory-empty">没有匹配模块</p>';
  }
  function moduleDetails(id, preserve=false) {
    const item = availableModules().find(m => m.id === id);
    if (!item) return;
    if(!preserve || moduleDraft?.id!==id)moduleDraft={id,revision:moduleDirectory().revision,bindings:structuredClone(state.packBindings),selectedMethod:state.selectedMethod,missingMethod:state.missingMethod};
    const parts = moduleDirectory().components.filter(c => item.componentIds.includes(c.id));
    const choices = packChoices(parts,managedEntries(),moduleDraft.bindings,{esc,capabilities:state.catalog.capabilities,moduleId:id});
    const count=bindingCapabilities(moduleDraft.bindings.filter(binding=>moduleEntries(item).some(entry=>sameAsset(entry.pack,binding.pack))),managedEntries()).length;
    state.dialogDraft = null;
    state.moduleDetail=id;
    if(!drawer.open)rememberFocus();
    drawer.className = "catalog-drawer";
    drawer.innerHTML = `<div class="drawer-header"><h2>${esc(item.label)}</h2><button class="icon-button drawer-close" aria-label="关闭">${icon('x')}</button></div><div class="drawer-body">${choices}${plannedCapabilities(parts,esc)}</div><div class="drawer-footer"><span aria-live="polite">已选 ${count} 项能力<small class="module-method-summary">${moduleDraft.selectedMethod?esc(moduleDraft.selectedMethod.name)+' · v'+esc(moduleDraft.selectedMethod.version):moduleDraft.missingMethod?'原方法版本不可用，引用保留':'采用目录声明方法'}</small></span><button class="secondary-button module-cancel">取消</button><button class="primary-button" data-module-confirm>确定</button></div>`;
    showDrawer();
  }
  function moduleCapabilityDetail(id,version) {
    if(!moduleDraft)return;
    const entry=managedEntries().find(e=>e.pack.id===id&&e.pack.version===version);
    const item=availableModules().find(m=>m.id===moduleDraft.id);
    if(!entry||!item||!moduleEntries(item).includes(entry))return;
    const parts=moduleParts(item).filter(p=>(p.packBindings||[]).some(b=>sameAsset(b.pack,entry.pack)));
    const binding=moduleDraft.bindings.find(b=>sameAsset(b.pack,entry.pack));
    const detail=capabilityDetails(parts,{esc,runtime:framework(),connection:state.connections.find(c=>c.kind==='BANK')?.displayName,method:moduleDraft.selectedMethod});
    const guides=parts.filter(p=>p.methodGuide);
    const metadata = `<dl class="pack-metadata"><dt>能力包</dt><dd>${esc(entry.pack.id)} · ${esc(entry.pack.version)}</dd><dt>兼容提供方</dt><dd>${entry.providers.map(p=>`${esc(p.id)} · ${esc(p.version)}`).join('<br>')}</dd><dt>目录核验</dt><dd>${esc(entry.qualification||'未提供核验状态')}</dd><dt>实际安装</dt><dd>${esc(entry.installation||'尚未观察')}</dd><dt>业务授权</dt><dd>${esc(entry.authorization||'运行时检查，目录不授予权限')}</dd><dt>所需权限</dt><dd>${esc([...new Set(entry.capabilities.flatMap(c=>c.requiredScopes||[]))].join('、')||'目录未提供')}</dd></dl>`;
    drawer.innerHTML=`<div class="drawer-header"><button class="text-button" data-module-back>${icon('arrow-left')}返回</button><h2>${esc(entry.label)}</h2><button class="icon-button drawer-close" aria-label="关闭">${icon('x')}</button></div><div class="drawer-body"><section><h3>业务功能</h3><p>${esc(entry.scope||entry.description||parts[0]?.scope||'业务说明未提供')}</p></section><section><h3>目录默认专业方法（SOP）</h3>${guides.length?guides.map(p=>methodGuideView(p.methodGuide,esc)).join(''):parts.some(p=>p.domainPackRefs?.length)?'<p>已引用专业方法包，当前目录未提供正文说明。</p>':'<p>未附带专业方法。</p>'}${draftMethodView(entry,parts)}</section><section><h3>工具与执行要求</h3><ul>${entry.capabilities.map(c=>`<li>${esc(state.catalog.capabilities.find(v=>v.id===c.capabilityId)?.label||c.label||entry.label)} · ${c.operation==='TASK'?'办理':'只读查询'}</li>`).join('')}</ul><p>${esc(item.scope)}</p></section><details><summary>能力包信息</summary>${detail}${metadata}${entry.providers.length>1?`<label class="pack-provider-label">数据提供方<select data-pack-provider="${esc(id)}" data-pack-version="${esc(version)}" data-pack-module="${esc(item.id)}" ${binding?'':'disabled'}><option value="">请选择提供方</option>${entry.providers.map(p=>`<option value="${esc(JSON.stringify(assetRef(p)))}" ${sameAsset(p,binding?.provider)?'selected':''}>${esc(p.label||p.name||p.id)} · ${esc(p.version)}</option>`).join('')}</select></label>`:''}</details></div><div class="drawer-footer"><button class="primary-button" data-module-back>返回选择</button></div>`;
    showDrawer();
  }
  function draftMethodChoices(entry) {
    const bindings=moduleDraft.bindings;
    const entries=bindings.map(b=>managedEntries().find(e=>sameAsset(e.pack,b.pack)));
    const caps=bindingCapabilities(bindings,managedEntries());
    const candidates=entry?[...entries,entry]:entries;
    if(!entries.length||candidates.some(e=>!e||e.methodCustomizationSupported!==true||!e.runtimeIds?.includes(state.runtime)||e.capabilities.some(c=>c.operation!=='QUERY')))return [];
    return state.methodVersions.filter(v=>Array.isArray(v.capabilityIds)&&v.capabilityIds.length&&v.capabilityIds.every(id=>caps.includes(id))&&(!entry||v.capabilityIds.some(id=>entry.capabilities.some(c=>c.capabilityId===id))));
  }
  function draftMethodView(entry,parts) {
    const choices=draftMethodChoices(entry),selected=moduleDraft.selectedMethod;
    const available=choices.some(v=>sameMethodVersion(v,selected));
    const compatible=selected&&draftMethodChoices().some(v=>sameMethodVersion(v,selected));
    const recorded=selected&&state.methodVersions.some(v=>sameMethodVersion(v,selected));
    return `<div class="draft-method-selection"><h4>计划方法覆盖</h4><p>当前 Agent 草稿共用一个私有方法版本，不是单个能力包的独立覆盖。目录默认方法说明不等于计划覆盖，实际安装以新构建记录为准。</p><label>已发布方法<select data-method-choice="${esc(entry.pack.id)}" data-pack-version="${esc(entry.pack.version)}" aria-label="计划专业方法">${moduleDraft.missingMethod?'<option value="missing" selected disabled>原方法版本不可用，引用已保留</option>':''}${selected&&!available?`<option value="original-method" selected disabled>${esc(selected.name)} · v${esc(selected.version)}（${compatible?'作用于其他已选能力':recorded?'当前能力或运行时不兼容':'原方法版本不可用，引用保留'}）</option>`:''}<option value="" ${!selected&&!moduleDraft.missingMethod?'selected':''}>采用目录声明方法</option>${choices.map(v=>`<option value="${esc(v.assetId)}@${esc(v.version)}" data-release-digest="${esc(v.releaseDigest)}" ${sameMethodVersion(v,selected)?'selected':''}>${esc(v.name)} · v${esc(v.version)}</option>`).join('')}</select></label>${selected?`<p>方法声明依赖：${selected.capabilityIds.map(id=>esc(names[id]||id)).join('、')}。选择会更换本 Agent 的计划专业方法，不自动增删能力包。</p>`:''}${choices.length?'':'<p>此能力在当前草稿与运行时下没有可选的已发布私有方法；依赖能力须先在草稿中明确选齐。</p>'}${parts[0]?`<button class="text-button" data-business-open="${esc(parts[0].id)}" data-method-management>管理与定制专业方法 ${icon('arrow-up-right')}</button>`:''}</div>`;
  }
  function selectScenario(id) {
    const t = moduleDirectory().templates.find(t => t.id === id);
    if (!t || state.scenario === id) return;
    state.scenario = id;
    state.selectedMethod = null;
    applyTemplateDefaults(t);
    state.directoryQuery = "";
    state.expanded = false;
    state.notice = "";
    state.editing = null;
    state.epoch++;
    render();
    $(`[data-scenario="${id}"]`)?.focus({preventScroll: true});
    const strip=app.querySelector('.template-strip'),selected=strip?.querySelector(`[data-scenario="${CSS.escape(id)}"]`);
    if(strip&&selected){const box=selected.getBoundingClientRect(),bounds=strip.getBoundingClientRect();if(box.left<bounds.left||box.right>bounds.right)strip.scrollLeft+=box.left-bounds.left;}
  }
  function selectBusinessDomain(id) {
    const domain = moduleDirectory().businessDomains.find(item => item.id === id);
    if(!domain || state.businessDomain === id)return;
    state.businessDomain=id;
    if(state.customComposition){
      state.directoryQuery='';
      state.notice='';
      state.epoch++;
      render();
      $(`[data-business-domain="${CSS.escape(id)}"]`)?.focus({preventScroll:true});
      return;
    }
    const blueprint=taskBlueprintsForDomain()[0] || null;
    state.taskBlueprint=blueprint?.id || null;
    state.customComposition=false;
    state.directoryQuery='';
    state.notice='';
    state.editing=null;
    state.selectedMethod=null;
    if(blueprint)applyTaskBlueprintDefaults(blueprint);
    else {
      state.packBindings=[];
      syncModules();
    }
    state.epoch++;
    render();
    $(`[data-business-domain="${CSS.escape(id)}"]`)?.focus({preventScroll:true});
  }
  function selectTaskBlueprint(id) {
    const blueprint=moduleDirectory().taskBlueprints.find(item=>item.id===id&&
      (item.businessDomainId===state.businessDomain||item.relatedBusinessDomainIds?.includes(state.businessDomain)));
    if(!blueprint || state.taskBlueprint===id)return;
    state.taskBlueprint=id;
    state.customComposition=false;
    state.directoryQuery='';
    state.notice='';
    state.editing=null;
    state.selectedMethod=null;
    applyTaskBlueprintDefaults(blueprint);
    state.epoch++;
    render();
    $(`[data-task-blueprint="${CSS.escape(id)}"]`)?.focus({preventScroll:true});
  }
  function applyTaskBlueprintDefaults(blueprint) {
    state.scenario = blueprint.legacyTemplateId;
    state.packBindings=[];
    state.migrationWarnings=[];
    state.missingMethod=null;
    for(const packId of blueprint.packIds){
      const entry=managedEntries().find(item=>item.pack.id===packId);
      if(entry)addPack(entry);
    }
    syncModules();
  }
  function selectCustomComposition() {
    state.taskBlueprint=null;
    state.customComposition=true;
    state.directoryQuery='';
    state.notice='';
    state.editing=null;
    state.selectedMethod=null;
    state.packBindings=[];
    state.migrationWarnings=[];
    state.missingMethod=null;
    syncModules();
    state.epoch++;
    render();
    $('[data-custom-composition]')?.focus({preventScroll:true});
  }
  function applyTemplateDefaults(template) {
    state.packBindings=[];
    state.migrationWarnings=[];
    state.missingMethod=null;
    if(Array.isArray(template.defaultPackBindings))state.packBindings=structuredClone(template.defaultPackBindings);
    else componentEntries(moduleDirectory().components.filter(part=>(template.defaultComponentIds||[]).includes(part.id)),managedEntries()).forEach(addPack);
    syncModules();
  }

  function rememberFocus(element = document.activeElement) {
    const attribute = ["data-action", "data-catalog", "data-module-detail", "data-delete", "data-scenario", "data-business-domain", "data-task-blueprint"].find(name => element?.hasAttribute(name));
    state.returnFocus = {element, page: state.page, active: state.active,
      selector: attribute ? `[${attribute}="${CSS.escape(element.getAttribute(attribute))}"]` : null};
  }
  function showDrawer() {
    drawer.querySelectorAll('svg > use[href^="/icons.svg#"]').forEach(use=>{
      const svg=use.parentElement,name=use.getAttribute('href').slice('/icons.svg#'.length);
      svg.replaceWith(inlineIcon(name,Object.fromEntries([...svg.attributes].map(a=>[a.name,a.value]))));
    });
    const title = drawer.querySelector("h2");
    if (title) {title.id = "drawer-title"; drawer.setAttribute("aria-labelledby", title.id);}
    drawer.showModal();
    if(!drawer.contains(document.activeElement))drawer.querySelector('button:not(:disabled)')?.focus({preventScroll:true});
  }
  function scenarioKeys(event) {
    const current = event.target.closest("[data-scenario]");
    if (!current || !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) return;
    const group = current.closest('[role="radiogroup"]');
    const options = [...group.querySelectorAll('[data-scenario]')];
    const index = options.indexOf(current), direction = ["ArrowLeft", "ArrowUp"].includes(event.key) ? -1 : 1;
    const next = event.key === "Home" ? 0 : event.key === "End" ? options.length - 1 : (index + direction + options.length) % options.length;
    event.preventDefault();
    options[next].click();
  }
  app.addEventListener("keydown", scenarioKeys);
  drawer.addEventListener("keydown", scenarioKeys);
  const selectedModelsReady = () =>
    state.models.size > 0 &&
    [...state.models].every(
      (r) => model(r)?.bindingStatus === "CONFIGURED_UNTESTED",
    );
  const bankReady = () =>
    state.connections.find((c) => c.kind === "BANK")?.configurationStatus ===
    "CONFIGURED_UNTESTED";
  const editable = (b) =>
    !["QUEUED", "RUNNING"].includes(b.buildStatus) &&
    !["READY", "STARTING", "STOPPING", "UNMANAGED"].includes(b.instance.status);
  const details = (b) =>
    `<details><summary>构建版本与计划装配</summary><pre>${esc(JSON.stringify({ buildId:b.buildId, artifact:b.artifact, instance:b.instance, methods:b.methodRef, models:b.modelSelection, capabilities:b.capabilities, sources:b.sources, verification:b.verification },null,2))}</pre></details><details><summary>实际安装记录 · ${b.installation?.status==='RECORDED_FOR_ARTIFACT'?'已观察':'尚未观察'}</summary>${b.installation?.lock?`<p>该制品的历史安装 Lock，不代表当前会话授权。</p><pre>${esc(JSON.stringify(b.installation.lock,null,2))}</pre>`:'<p>启动并创建会话后生成实际安装记录。</p>'}</details>`;
  const selectedAgents = new Set();
  let deletingAgents = false;
  function toggleAgent(id) {
    const b = state.builds.find(b => b.buildId === id);
    if (deletingAgents || !b || !editable(b)) return;
    if (selectedAgents.has(id)) selectedAgents.delete(id);
    else selectedAgents.add(id);
    const focus = document.activeElement?.matches('[data-select-agent]');
    render();
    if (focus) app.querySelector(`[data-select-agent="${CSS.escape(id)}"]`)?.focus({preventScroll:true});
  }
  function confirmBatchDelete() {
    const ids = state.builds.filter(b => selectedAgents.has(b.buildId) && editable(b)).map(b => b.buildId);
    if (!ids.length || deletingAgents) return;
    rememberFocus();
    drawer.innerHTML = `<div class="drawer-header"><h2>删除 ${ids.length} 个 Agent？</h2><button class="icon-button drawer-close" aria-label="关闭">&#215;</button></div><div class="drawer-body"><p>将删除所选 Agent 的生成包、日志和本地运行记录。此操作无法撤销；公共依赖和未选中的 Agent 不受影响。</p><ul>${ids.map(id => `<li>${esc(state.builds.find(b => b.buildId === id).selection.interfaceConfig?.assistantName || '金融 Agent')}<br><code>${esc(id)}</code></li>`).join('')}</ul><p>运行中的 Agent 不会自动停止或删除。</p></div><div class="drawer-footer"><button class="secondary-button drawer-close">取消</button><button class="primary-button" id="batch-delete-confirm">确认删除 ${ids.length} 个</button></div>`;
    drawer.querySelector('#batch-delete-confirm').addEventListener('click', async () => {
      if (deletingAgents) return;
      deletingAgents = true;
      drawer.close();
      render();
      const failures = [];
      let deleted = 0;
      try {
        for (const id of ids) {
          try {
            await api(`/api/v1/builds/${id}/delete`, {buildId: id});
            clear(id);
            selectedAgents.delete(id);
            if (state.active === id) state.active = null;
            if (state.editing === id) state.editing = null;
            state.builds = state.builds.filter(b => b.buildId !== id);
            deleted++;
          } catch (error) { failures.push(`${id}（${error.message}）`); }
        }
      } finally {
        deletingAgents = false;
        state.notice = failures.length ? `已删除 ${deleted} 个；${failures.length} 个未删除：${failures.join('、')}` : '';
        render();
        toast(`已删除 ${deleted} 个 Agent${failures.length ? `，${failures.length} 个未删除` : ''}。`);
      }
    });
    showDrawer();
  }
  function builds() {
    for (const id of selectedAgents) if (!state.builds.some(b => b.buildId === id && editable(b))) selectedAgents.delete(id);
    const toolbar = `<div class="agent-selection-toolbar"><label><input type="checkbox" id="select-all-agents" ${state.builds.some(editable) && state.builds.filter(editable).every(b => selectedAgents.has(b.buildId)) ? 'checked' : ''} ${deletingAgents ? 'disabled' : ''}>全选可删除项</label><span role="status">已选 ${selectedAgents.size} 项</span><button class="secondary-button danger-button" data-action="batch-delete" ${!selectedAgents.size || deletingAgents ? 'disabled' : ''}>${icon('trash-2')}${deletingAgents ? '正在删除' : '批量删除'}</button></div>`;
    return shell(
      `<div class="page-title-row"><h1>我的 Agent</h1>${button("创建 Agent", "new", "primary-button")}</div>${toolbar}<div class="agents-grid">${state.builds.map(agentCard).join('') || '<div class="empty-state"><h2>还没有构建记录</h2></div>'}</div>`,
      "我的 Agent",
    );
  }
  function agentCard(b) {
    const id = esc(b.buildId), eligible = editable(b) && !deletingAgents;
    return `<article class="agent-card ${selectedAgents.has(b.buildId) ? 'is-selected' : ''}" data-select-agent="${id}" tabindex="${eligible ? '0' : '-1'}">
      <label class="agent-selection"><input type="checkbox" data-agent-checkbox="${id}" aria-label="选择 Agent ${id}" ${selectedAgents.has(b.buildId) ? 'checked' : ''} ${eligible ? '' : 'disabled'}></label>
      <div class="agent-card-top"><div class="agent-avatar">${icon('blocks')}</div><span class="record-label">${states[b.instance.status === 'READY' ? 'READY' : b.buildStatus] || b.buildStatus}</span></div>
      <h2>${esc(b.selection.interfaceConfig?.assistantName || '我的金融助手')}</h2><p>${esc(terminalName(b.selection.presentation || 'MOBILE_WEB'))} · ${esc(themeName(b.selection.interfaceConfig?.theme || 'FINANCE_RED'))}</p>
      <p>${b.selection.capabilityIds.map(c => esc(names[c] || c)).join(' · ')}</p>
      <div class="agent-card-footer"><button class="primary-button" data-open="${id}">查看 Agent</button><button class="text-button" data-edit="${id}" ${deletingAgents ? 'disabled' : ''}>修改配置并重新构建</button><button class="icon-button danger-button" data-delete="${id}" aria-label="删除 Agent ${id}" title="${eligible ? '删除 Agent' : '请先停止运行或等待任务结束'}" ${eligible ? '' : 'disabled'}>${icon('trash-2')}</button></div><p class="record-time">${id}</p></article>`;
  }
  async function loadOptimizationView(buildId) {
    const view = await api(`/api/v1/builds/${buildId}/optimization-view`);
    state.optimizationViews.set(buildId, view);
    if (!state.optimizationNodes.has(buildId) && view.storyline?.length) {
      const current = view.storyline.find((item) => item.status === "executing");
      state.optimizationNodes.set(
        buildId,
        (current || view.storyline[view.storyline.length - 1]).id,
      );
    }
    return view;
  }
  const metricLabel = (name) =>
    ({ accuracy: "准确率", precision: "精确率", recall: "召回率", f1: "F1" })[
      name.toLowerCase()
    ] || name;
  const optimizationStatus = (status) =>
    ({
      completed: "已完成",
      executing: "进行中",
      passed: "通过",
      failed: "未通过",
      blocked: "阻塞",
      skipped: "跳过",
      rejected: "已拒绝",
      "timed-out": "超时",
      "not-executed": "未执行",
      unverifiable: "不可核验",
    })[status] || status;
  function optimizationPanel(buildId) {
    if (active()?.buildStatus === "FAILED")
      return `<section class="optimization-workbench"><header><div><span class="eyebrow">EVALUATION & OPTIMIZATION</span><h2>Candidate 装配失败</h2><p>构建未形成可启动制品；请先查看失败阶段并重新构建，当前不能运行 Evaluation。</p></div><span class="workbench-state danger">不可执行</span></header></section>`;
    const view = state.optimizationViews.get(buildId);
    if (!view)
      return `<section class="optimization-workbench optimization-loading" id="optimization-workbench"><p>正在读取 Evaluation 与优化记录…</p></section>`;
    if (view.status === "not-executed")
      return `<section class="optimization-workbench" id="optimization-workbench"><header><div><span class="eyebrow">EVALUATION & OPTIMIZATION</span><h2>业务效果尚未评测</h2><p>Candidate 已完成装配；运行 Evaluation 后，这里会按轮次展示样本执行、问题归因、修正对象和交付裁决。</p></div><span class="workbench-state neutral">未执行</span></header>${nextOptimizationAction(view)}</section>`;
    if (view.status === "unverifiable")
      return `<section class="optimization-workbench" id="optimization-workbench"><header><div><span class="eyebrow">EVALUATION & OPTIMIZATION</span><h2>优化记录暂时无法核验</h2><p>Builder 已停止展示不一致的记录，避免把旧 Candidate 或损坏证据当成本轮事实。</p></div><span class="workbench-state danger">需检查</span></header><div class="optimization-issues">${view.verifiability.issues.map((code) => `<code>${esc(code)}</code>`).join("")}</div>${nextOptimizationAction(view)}</section>`;
    const nodes = view.storyline || [];
    const selectedId = state.optimizationNodes.get(buildId) || nodes[0]?.id;
    const selected = nodes.find((item) => item.id === selectedId) || nodes[0];
    const evaluation = view.evaluation;
    return `<section class="optimization-workbench" id="optimization-workbench"><header><div><span class="eyebrow">EVALUATION & OPTIMIZATION</span><h2>Builder 怎样把 Candidate 优化到可交付</h2><p>同一条事实链串起基线评测、问题归因、受控修正、重建复测和最终裁决。</p></div><span class="workbench-state ${view.status === "completed" ? "success" : "running"}">${optimizationStatus(view.status)}</span></header>${optimizationSummary(view)}<div class="optimization-storyline" aria-label="自优化 Storyline">${nodes.map((node, index) => `<button class="optimization-node ${node.id === selected?.id ? "selected" : ""}" data-optimization-node="${esc(node.id)}"><span>${String(index + 1).padStart(2, "0")}</span><strong>${esc(node.title)}</strong><small>${esc(optimizationStatus(node.status))}</small></button>`).join("")}</div>${optimizationNodeDetail(selected)}${evaluationPanel(evaluation)}${roundPanel(view.rounds)}${proposalPanel(view.proposals)}${nextOptimizationAction(view)}</section>`;
  }
  function optimizationSummary(view) {
    const budget = view.request?.budget || {};
    const result = view.result;
    const usage = result?.usage || {};
    const levels = view.evaluation?.evidenceLevels || [];
    return `<div class="optimization-summary"><article><span>迭代预算</span><strong>${usage.rounds ?? view.rounds.length} / ${budget.maxRounds ?? "—"} 轮</strong><small>构造任务 ${usage.constructionTasks ?? 0} / ${budget.maxConstructionTasks ?? "—"} · 连续无提升 ${usage.consecutiveNoImprovement ?? 0}</small></article><article><span>时间与成本</span><strong>${((usage.durationMs ?? 0) / 1000).toFixed(1)} 秒</strong><small>成本 ${usage.costMicrounits ?? 0} / ${budget.maxCostMicrounits ?? "—"} 微单位</small></article><article><span>最终门禁</span><strong>${view.request?.finalGate ? "启用" : "未启用"}</strong><small>Holdout ${view.request?.holdoutAuthorized ? "已授权" : "未授权"}${result?.holdoutConsumed ? " · 已使用" : ""}</small></article><article><span>证据级别</span><strong>${esc(view.candidate?.evidenceLevel || "未记录")}</strong><small>${levels.map((item) => `${esc(item.level)}：${esc(optimizationStatus(item.status))}`).join(" · ") || "暂无分级证据"}</small></article></div>`;
  }
  function optimizationNodeDetail(node) {
    if (!node) return "";
    const refs = (label, values) => `<div><dt>${label}</dt><dd>${values.length ? values.map((ref) => `<code title="sha256:${esc(ref.sha256)}">${esc(ref.locator)}</code>`).join("") : "无"}</dd></div>`;
    const occurred = node.occurredAtEpochSeconds
      ? new Date(node.occurredAtEpochSeconds * 1000).toLocaleString("zh-CN", { hour12: false })
      : "源记录未提供阶段时间";
    return `<article class="optimization-node-detail"><div><span>当前阶段</span><h3>${esc(node.title)}</h3><p>${esc(node.summary)}</p><small>时间：${esc(occurred)}</small></div><dl>${refs("读取", node.inputRefs)}${refs("产出", node.outputRefs)}</dl></article>`;
  }
  function evaluationPanel(evaluation) {
    if (!evaluation) return "";
    const progress = evaluation.progress;
    return `<div class="optimization-section"><div class="section-head"><div><span class="eyebrow">BUSINESS EVALUATION</span><h3>逐条执行冻结测试集</h3></div><strong>${progress.completed} / ${progress.total}</strong></div><progress max="${progress.total || 1}" value="${progress.completed}"></progress><div class="metric-strip">${evaluation.metrics.map((item) => `<article><span>${esc(metricLabel(item.name))}</span><strong>${(item.value * 100).toFixed(2)}%</strong><small>${item.numerator == null ? "统一规则计算" : `${item.numerator} / ${item.denominator}`}</small></article>`).join("")}</div><div class="sample-log" role="log" aria-label="Evaluation 样本执行记录">${evaluation.samples.length ? evaluation.samples.map((item) => `<div><span class="sample-state ${item.status}">${item.matched ? "✓" : "!"}</span><strong>${esc(item.sampleId)}</strong><span>${esc(item.datasetId)}</span><small>${esc(optimizationStatus(item.status))} · ${item.durationMillis} ms${item.errorCode ? ` · ${esc(item.errorCode)}` : ""}</small></div>`).join("") : "<p>当前报告没有公开样本明细。</p>"}</div></div>`;
  }
  function roundPanel(rounds) {
    if (!rounds?.length)
      return `<div class="optimization-section"><div class="section-head"><div><span class="eyebrow">CONTROLLED LOOP</span><h3>本轮没有执行修正</h3></div></div><p>基线报告没有产生可自动执行的修正对象，Builder 保留停止原因供负责人复核。</p></div>`;
    return `<div class="optimization-section"><div class="section-head"><div><span class="eyebrow">CONTROLLED LOOP</span><h3>问题归因与受控修正</h3></div><strong>${rounds.length} 轮</strong></div><div class="round-grid">${rounds.map((round) => `<article><header><span>ROUND ${round.round}</span><strong>${esc(optimizationStatus(round.status))}</strong></header><h4>${round.target ? `${esc(round.target.kind)} · ${esc(round.target.action)}` : "没有可执行修正对象"}</h4><p>${round.findings.length ? `处理 ${round.findings.length} 个结构化问题：${round.findings.map((finding) => esc(finding.summary)).join("；")}` : "本轮没有新的结构化问题。"}</p>${round.target ? `<div class="round-explanation"><p><strong>修正对象</strong>${esc(round.target.ref)}</p><p><strong>修正原因</strong>${esc(round.target.reason)}</p><p><strong>责任方</strong>${esc(round.target.ownerId)}</p></div>` : ""}${round.metrics.length ? `<div class="round-metrics">${round.metrics.map((metric) => `<span>${esc(metricLabel(metric.name))} ${metric.delta >= 0 ? "+" : ""}${(metric.delta * 100).toFixed(2)}%</span>`).join("")}</div>` : ""}<dl><div><dt>父版本</dt><dd><code>${esc(round.parentLockDigest.slice(0, 12))}</code></dd></div><div><dt>候选版本</dt><dd>${round.candidateLockDigest ? `<code>${esc(round.candidateLockDigest.slice(0, 12))}</code>` : "未生成"}</dd></div><div><dt>实际变更摘要</dt><dd>${round.changeDigest ? `<code>${esc(round.changeDigest.slice(0, 12))}</code>` : "未发生变更"}</dd></div><div><dt>审批 / 治理事实</dt><dd>${esc(round.approvalFact)}</dd></div><div><dt>是否晋级</dt><dd>${round.promoted == null ? "未裁决" : round.promoted ? "是" : "否"}</dd></div><div><dt>停止原因</dt><dd>${round.stopReason ? esc(round.stopReason) : "继续评测"}</dd></div></dl></article>`).join("")}</div></div>`;
  }
  function proposalPanel(proposals) {
    if (!proposals?.length) return "";
    return `<div class="optimization-section"><div class="section-head"><div><span class="eyebrow">HARNESS CHANGE PROPOSAL</span><h3>需要外部资产负责人处理</h3></div><strong>${proposals.length} 项</strong></div><div class="proposal-grid">${proposals.map((proposal) => `<article><header><strong>${esc(proposal.target.id || "未命名资产")}</strong><span>${esc(proposal.status || "待处理")}</span></header><p>${esc(proposal.summary)}</p><small>${esc(proposal.target.kind || "asset")} · ${esc(proposal.target.version || "未记录版本")}</small>${proposal.scopes.length ? `<div>${proposal.scopes.map((scope) => `<code>${esc(scope)}</code>`).join("")}</div>` : ""}</article>`).join("")}</div></div>`;
  }
  function nextOptimizationAction(view) {
    const next = view.nextAction;
    return `<aside class="optimization-next"><div><span class="eyebrow">NEXT ACTION</span><h3>${esc(next.label)}</h3><p>${esc(next.reason)}</p></div><span>${icon("arrow-up-right")}</span></aside>`;
  }
  function buildPage() {
    const b = active();
    if (!b) return builds();
    const running = ["QUEUED", "RUNNING"].includes(b.buildStatus);
    const runtime = runtimePresentation(b, moduleDirectory()?.runtimes || state.catalog.templateModuleDirectory?.runtimes || []);
    return shell(
      `<div class="build-page" data-building="${b.buildId}"><div class="hero"><h1 id="build-title">${running ? "正在装配 Finance Harness" : states[b.buildStatus] || b.buildStatus}</h1><p class="build-reference">${esc(b.buildId)}</p></div><div class="building-emblem assembly-flow ${running ? "running" : ""}"><div class="assembly-node">${icon("blocks")}<strong>${esc(runtime.label)}</strong><small>${esc(runtime.detail)}</small></div><div class="assembly-link"></div><div class="assembly-node harness-node"><span class="assembly-logo">FH</span><strong>Finance Harness</strong><small>${b.selection.capabilityIds.length} 项业务能力 · 强制治理</small></div><div class="assembly-link"></div><div class="assembly-node">${icon("messages-square")}<strong>金融 Agent</strong><small>${esc(runtime.architecture)}</small></div></div><div id="build-progress">${buildProgress(b)}</div><div class="build-stages">${b.stages.map((s, i) => `<div class="build-stage" data-stage="${s.id}">${buildStage(s, i)}</div>`).join("")}</div><div class="build-installation"><h2>计划装配</h2><dl><dt>能力包</dt><dd>${(b.selection.packBindings?.map(binding=>binding.pack) || b.directorySelection?.components?.flatMap(c=>c.capabilityPackRefs||[]) || []).map(ref=>`<code>${esc(ref.id)} @ ${esc(ref.version)}</code>`).join(" ") || "原记录未提供显式能力包引用"}</dd><dt>工具边界</dt><dd>按所选能力包声明装配；每次调用检查身份、权限与操作边界</dd><dt>模型绑定</dt><dd>${(b.modelSelection?.selectedModelRefs || []).map((r) => esc(modelName(r))).join(" / ")}</dd></dl></div><div id="optimization-slot">${optimizationPanel(b.buildId)}</div><div id="build-actions">${buildActions(b)}</div><div id="build-evidence">${details(b)}</div></div>`,
      "构建任务",
    );
  }
  function buildProgress(b) {
    const done = b.stages.filter((s) => s.status === "SUCCEEDED").length;
    const current = b.stages.find((s) => s.status === "RUNNING");
    return `<div class="progress-label"><span>${current ? stages[current.id] : states[b.buildStatus] || b.buildStatus}</span><span>${done} / ${b.stages.length} 阶段完成</span></div><progress max="${b.stages.length}" value="${done}" aria-label="已完成构建阶段"></progress>`;
  }
  function buildStage(s, i, build = active()) {
    const detail = stageDetails[s.id] || ["构建阶段", "查看构建报告以确认实际结果"];
    const title = s.id === 'candidate_package'
      ? `${runtimePresentation(build, moduleDirectory()?.runtimes || []).label} · 候选 Agent`
      : detail[0];
    return `<span class="stage-marker ${s.status === "RUNNING" ? "stage-active" : ""}">${s.status === "SUCCEEDED" ? icon("check") : s.status === "RUNNING" ? icon("refresh-cw") : i + 1}</span><div class="stage-content"><div class="stage-heading"><strong>${esc(stages[s.id] || s.id)}</strong><span>${states[s.status] || "等待中"}${s.elapsedMillis == null ? "" : " · " + (s.elapsedMillis / 1000).toFixed(1) + "s"}</span></div><p>${esc(title)}</p><small>${esc(detail[1])}</small></div>`;
  }
  function buildActions(b) {
    const issues = b.startReadiness?.blockers || [];
    const running = ["QUEUED", "RUNNING"].includes(b.buildStatus);
    const failedStage = b.stages.find(s => s.status === "FAILED")?.id;
    const failure = failedStage === "component_installation" || failedStage === "observation_admission"
      ? `${stages[failedStage]}未通过；Agent 已编译，但不能启动。请在构建证据中查看该阶段日志，核对采集器资格、安装能力与当前制品版本。`
      : b.error ? errors[b.error.code] || b.error.code : "";
    const buildReady = b.buildStatus === "SUCCEEDED";
    return `${issues.length ? `<div class="start-blockers"><strong>启动条件未满足</strong><ul>${issues.map((i) => `<li>${esc(i.id === "local-simulator" ? "本地模拟银行" : modelName(i.id))}：${i.requiresRebuild && i.code === "MODEL_CONFIGURATION_CHANGED" ? "构建时未绑定当前连接，需要重新构建" : esc(statuses[i.status] || "连接待配置")}</li>`).join("")}</ul><button class="text-button" data-edit="${b.buildId}">修改配置并重新构建</button></div>` : ""}<div class="complete-actions">${button(b.instance.status === "READY" ? "打开 Agent" : "启动 Agent", "launch", "primary-button", !b.artifact || !buildReady || state.busy || running || (b.instance.status !== "READY" && issues.length > 0))}${b.instance.status === "READY" ? button(icon("square")+"停止实例", "stop", "secondary-button") : ""}${button("返回列表", "list")}</div>${failure ? `<div class="live-error">${esc(failure)}</div>` : ""}`;
  }
  function updateBuildPage(b) {
    if ($("[data-building]")?.dataset.building !== b.buildId) return render();
    $("#build-title").textContent = ["QUEUED", "RUNNING"].includes(
      b.buildStatus,
    )
      ? "正在装配 Finance Harness"
      : states[b.buildStatus] || b.buildStatus;
    $(".assembly-flow").classList.toggle(
      "running",
      ["QUEUED", "RUNNING"].includes(b.buildStatus),
    );
    const replaceChanged = (node, html) => {
      if (node.dataset.content !== html) {
        node.innerHTML = html;
        node.dataset.content = html;
      }
    };
    replaceChanged($("#build-progress"), buildProgress(b));
    if (b.stages.some(s => !app.querySelector(`[data-stage="${s.id}"]`))) {
      $(".build-stages").innerHTML = b.stages.map((s, i) => `<div class="build-stage" data-stage="${s.id}">${buildStage(s, i)}</div>`).join("");
    }
    b.stages.forEach((s, i) =>
      replaceChanged(
        app.querySelector(`[data-stage="${s.id}"]`),
        buildStage(s, i),
      ),
    );
    replaceChanged($("#build-actions"), buildActions(b));
    replaceChanged($("#optimization-slot"), optimizationPanel(b.buildId));
    if (!$("#build-evidence details")?.open)
      replaceChanged($("#build-evidence"), details(b));
  }
  function connections() {
    return shell(
      `<div class="page-title-row"><h1>模型与连接</h1>${button(icon("refresh-cw") + "刷新目录", "refresh")}</div><div class="notice-list">${visibleModels().map((m) => `<div class="connection-row">${icon("blocks")}<div><h2>${esc(m.displayName)}</h2><p>${esc(categoryName(m.provider))} · ${esc(statuses[m.bindingStatus] || m.bindingStatus)}</p><small class="status-text">${m.eaConfigured ? "EA 已配置 · 元数据快照" : "宿主目录"} / 真实调用未验证</small></div></div>`).join("")}<div class="connection-row">${icon("landmark")}<div><h2>本地模拟银行</h2><p>${esc(state.connections.find((c) => c.kind === "BANK")?.configurationStatus || "MISSING")}</p></div></div></div>`,
      "连接管理",
    );
  }
  function answer(v) {
    if (typeof v === "string") return v;
    if (v && typeof v === "object")
      for (const k of ["output", "answer", "content", "response", "result"])
        if (v[k] !== undefined) return answer(v[k]);
    return JSON.stringify(v, null, 2);
  }
  const messages = (list) =>
    list
      .map(
        (m) =>
          `<div class="message ${m.role}">${m.role === "user" ? `<div class="user-bubble">${esc(m.text)}</div>` : `<div class="assistant-label"><span class="mini-fh">FH</span>金融助手 <span class="answer-model">${esc(modelName(m.modelRef))}</span></div><div class="assistant-content">${renderMarkdown(m.text)}</div>`}</div>`,
      )
      .join("");
  function sourceSummary(h) {
    const e = h.evidence;
    if (!e?.response) return '<p class="status-text">尚无本会话结果</p>';
    const tools = [
      ...new Set(
        (e.events || [])
          .filter((v) => v.hook === "AFTER_TOOL_CALL")
          .map((v) => v.tool),
      ),
    ];
    return `<dl class="source-summary"><dt>模型</dt><dd>${esc(modelName(e.modelRef))}</dd><dt>已调用工具</dt><dd>${tools.map(esc).join("<br>") || "无"}</dd><dt>观察时间</dt><dd>${esc(e.at)}</dd><dt>数据环境</dt><dd>本地模拟银行</dd><dt>模型配置版本</dt><dd><code>${esc(e.modelConfigVersion)}</code></dd></dl>`;
  }
  function agent() {
    const b = active();
    if (!b) return builds();
    const h = memory(b.buildId),
      refs = b.modelSelection?.selectedModelRefs || ["configured-model"];
    return `<div class="agent-page"><header class="agent-topbar"><button class="back-button" data-nav="agents">返回装配台</button><span class="agent-top-title">客户自助 · 模拟环境</span><div class="agent-top-actions">${button("撤销本次授权", "revoke", "text-button", !h.sessionId)}${button(icon("square") + "停止", "stop", "secondary-button", b.instance.status !== "READY")}</div></header><main id="main-content">${notice()}<div class="agent-shell ${b.selection.presentation === "DESKTOP_WEB" ? "desktop" : "mobile"}"><section class="chat-column"><header class="chat-header"><div class="agent-avatar">${icon("messages-square")}</div><div><h1>我的金融助手</h1><p>${b.selection.capabilityIds.map((c) => esc(names[c] || c)).join(" · ")}</p></div><label class="agent-model-button"><span class="visually-hidden">当前模型</span><select id="session-model" ${state.busy || !h.sessionId ? "disabled" : ""}>${refs.map((r) => `<option value="${esc(r)}" ${r === (h.modelRef || b.modelSelection?.defaultModelRef) ? "selected" : ""}>${esc(modelName(r))}</option>`).join("")}</select></label></header><div class="chat-messages" id="chat-messages" role="log" aria-live="polite">${h.messages.length ? messages(h.messages) : '<div class="chat-welcome"><h2>你好，<br>有什么可以帮你？</h2></div>'}${state.busy ? '<div class="message pending">正在处理...</div>' : ""}${h.archives.length ? `<details class="session-history"><summary>历史会话（${h.archives.length}）</summary>${h.archives.map((x) => `<h3>${esc(modelName(x.modelRef))}</h3>${messages(x.messages)}`).join("")}</details>` : ""}</div><div class="chat-composer-area"><form id="chat-form" class="chat-composer"><textarea id="query" rows="1" maxlength="4000" aria-label="消息" placeholder="问问你的金融助手" ${state.busy || !h.sessionId ? "disabled" : ""}></textarea><button class="send-button" type="submit" title="发送" aria-label="发送" ${state.busy || !h.sessionId ? "disabled" : ""}>${icon("arrow-up")}</button></form><p class="composer-caption">${h.sessionId ? "本地模拟银行 · 非投资建议" : "会话未授权"}</p><button class="source-link" data-action="evidence">来源与执行证据</button></div></section>${b.selection.presentation === "DESKTOP_WEB" ? `<aside class="source-aside"><h2>事实与来源</h2>${sourceSummary(h)}</aside>` : ""}</div>${details(b)}</main></div>`;
  }
  function render(forceLatest = false) {
    const scroll = captureScroll($('#chat-messages'), forceLatest);
    const draft = $("#query")?.value;
    const templateScroll=app.querySelector('.template-strip')?.scrollLeft||0;
    const moduleScroll=app.querySelector('#module-results')?.scrollLeft||0;
    renderWorkbench(app, (
      { create, history:()=>shell(executionRecords.html(),'运行记录'), standards:()=>shell(industryStandards(esc, icon),'业界标准'), assets:()=>shell(assets.html(),'业务资产'), agents: builds, building: buildPage, connections, agent }[
        state.page
      ] || create
    )());
    app.querySelector('.app-shell')?.classList.toggle('homepage-vertical',state.page==='create');
    const strip=app.querySelector('.template-strip');if(strip)strip.scrollLeft=templateScroll;
    const modules=app.querySelector('#module-results');if(modules)modules.scrollLeft=moduleScroll;
    if (state.page === 'history') executionRecords.layout();
    if (state.page === 'assets') assets.afterRender();
    if (draft && $("#query")) $("#query").value = draft;
    resizeDevicePreviews();
    app.querySelectorAll('[data-module][data-partial="true"]').forEach(input=>{input.indeterminate=true;});
    restoreScroll($('#chat-messages'), scroll);
  }
  function toast(text) {
    $("#toast").textContent = text;
    setTimeout(() => {
      if ($("#toast").textContent === text) $("#toast").textContent = "";
    }, 5000);
  }
  function openCatalog(kind) {
    if (kind !== "models") return;
    rememberFocus();
    state.dialogDraft = {
      kind,
      selected: new Set([...state.models].filter(ref => model(ref)?.bindingStatus === 'CONFIGURED_UNTESTED')),
      defaultModel: state.defaultModel,
    };
    if (!state.dialogDraft.selected.has(state.dialogDraft.defaultModel)) state.dialogDraft.defaultModel = [...state.dialogDraft.selected][0] || '';
    drawer.className = "catalog-drawer";
    drawer.innerHTML = `<div class="drawer-header"><h2>${kind === "caps" ? "金融能力库" : "选择模型"}</h2><button class="icon-button drawer-close" aria-label="关闭">&#215;</button></div><div class="drawer-body"><div class="catalog-controls"><input class="catalog-search" aria-label="搜索目录" placeholder="搜索名称"><label class="filter-selected"><input id="only-selected" type="checkbox">仅看已选</label></div><div class="catalog-groups"><select id="category" aria-label="目录分类"><option value="">全部</option>${(kind === "caps" ? state.catalog.directory.domains.map(d => d.id) : [...new Set(state.catalog.models.map((m) => m.provider))]).map((c) => `<option value="${esc(c)}">${esc(categoryName(c))}</option>`).join("")}</select></div><div class="catalog-results"></div></div><div class="drawer-footer"><div class="catalog-summary"></div><button class="secondary-button" id="catalog-cancel">取消</button><button class="primary-button" id="catalog-save">保存选择</button></div>`;
    renderCatalog();
    showDrawer();
  }
  function openBank() {
    rememberFocus();
    state.dialogDraft = null;
    drawer.className = "catalog-drawer bank-drawer";
    const bank = state.connections.find((c) => c.kind === "BANK");
    drawer.innerHTML = `<div class="drawer-header"><h2>选择银行连接</h2><button class="icon-button drawer-close" aria-label="关闭">&#215;</button></div><div class="drawer-body"><label class="bank-choice"><input type="radio" name="bank" checked value="local-simulator"><div><strong>本地模拟银行</strong><p>non-ai-banking · 合成测试数据</p><span class="${bankReady() ? "state-success" : "state-warning"}">${bankReady() ? "服务端连接已配置" : "服务端连接待配置"}</span></div></label><dl class="bank-details"><dt>支持的能力</dt><dd>财富概览、账户余额</dd><dt>数据权限</dt><dd>由当前模拟银行登录与授权校验</dd><dt>连接检查</dt><dd>配置检查完成；实际连通与授权在启动会话时核验</dd>${bank?.missingFields?.length ? `<dt>缺少配置</dt><dd>${bank.missingFields.map(esc).join(" · ")}</dd>` : ""}</dl></div><div class="drawer-footer"><button class="secondary-button drawer-close">取消</button><button class="primary-button" id="bank-save">使用此连接</button></div>`;
    showDrawer();
  }
  function confirmDelete(id) {
    const b = state.builds.find((x) => x.buildId === id);
    if (!b || !editable(b)) return toast(errors.AGENT_STOP_REQUIRED);
    rememberFocus();
    state.dialogDraft = null;
    drawer.className = "catalog-drawer delete-drawer";
    drawer.innerHTML = `<div class="drawer-header"><h2>删除 Agent？</h2><button class="icon-button drawer-close" aria-label="关闭">&#215;</button></div><div class="drawer-body"><p>将删除此 Agent 的生成包、构建日志和本地运行记录，只保留不含对话内容的构建删除摘要。</p><p>此操作无法撤销，其他 Agent 和公共依赖缓存不受影响。</p><code>${esc(id)}</code></div><div class="drawer-footer"><button class="secondary-button drawer-close">取消</button><button class="primary-button" data-delete-confirm="${id}">确认删除</button></div>`;
    showDrawer();
  }
  function customize() {
    rememberFocus(); state.dialogDraft=null;
    const c=state.interface;
    drawer.className='catalog-drawer';
    drawer.innerHTML=`<div class="drawer-header"><h2>界面定制</h2><button class="icon-button drawer-close" aria-label="关闭">&#215;</button></div><div class="drawer-body customize-form"><label>助手名称<input id="ui-name" type="text" maxlength="24" value="${esc(c.assistantName)}"></label><label>标识<select id="ui-icon">${[["landmark","银行"],["blocks","应用"],["shield-check","守护"]].map(([v,l])=>`<option value="${v}" ${c.brandIcon===v?"selected":""}>${l}</option>`).join("")}</select></label><label>主题<select id="ui-theme"><option value="FINANCE_RED" ${c.theme==='FINANCE_RED'?'selected':''}>默认主题</option><option value="EXAMPLE_TEAL" ${c.theme==='EXAMPLE_TEAL'?'selected':''}>示例银行主题</option></select></label><label>余额卡片<select id="ui-card"><option value="balance-summary-v1" ${c.balanceCard==='balance-summary-v1'?'selected':''}>金额摘要</option><option value="balance-ledger-v1" ${c.balanceCard==='balance-ledger-v1'?'selected':''}>账户明细表</option></select></label><label>字体<select id="ui-font"><option value="STANDARD" ${c.fontSize==='STANDARD'?'selected':''}>标准</option><option value="LARGE" ${c.fontSize==='LARGE'?'selected':''}>大字</option></select></label><label>快捷入口顺序<select id="ui-order"><option value="BALANCE,WEALTH" ${c.shortcuts[0]==='BALANCE'?'selected':''}>余额在前</option><option value="WEALTH,BALANCE" ${c.shortcuts[0]==='WEALTH'?'selected':''}>资产在前</option></select></label><label class="check-setting"><input id="ui-voice" type="checkbox" ${c.voiceInput?'checked':''} ${state.template==='ATM_WEB'||state.catalog.speech?.status!=='CONFIGURED_UNTESTED'?'disabled':''}>手机语音输入</label><small>${state.catalog.speech?.status==='CONFIGURED_UNTESTED'?'讯飞语音识别 · 服务端已配置':'语音服务待配置，文字输入可用'}</small><label class="check-setting"><input id="ui-panel" type="checkbox" ${c.sourcePanelExpanded?'checked':''}>电脑默认展开结果面板</label></div><div class="drawer-footer"><button class="text-button" id="ui-reset">恢复默认</button><button class="primary-button" id="ui-save">应用</button></div>`;
    showDrawer();
  }
  function enlargePreview() {
    rememberFocus(); state.dialogDraft=null;
    previewDraft={template:state.template,page:state.previewPage};
    renderPreviewDialog();
  }
  function renderPreviewDialog() {
    if(!previewDraft)return;
    drawer.className='device-preview-dialog';
    drawer.innerHTML=`<div class="drawer-header"><h2>应用终端预览</h2><button class="icon-button drawer-close" aria-label="关闭">${icon('x')}</button></div><div class="preview-dialog-controls"><div class="presentation-options" role="radiogroup" aria-label="预览终端">${terminals.map(t=>`<button role="radio" aria-checked="${previewDraft.template===t.id}" tabindex="${previewDraft.template===t.id?0:-1}" data-preview-terminal="${t.id}" class="${previewDraft.template===t.id?'selected':''}">${icon(t.icon)}${t.label}</button>`).join('')}</div><div class="preview-tabs">${[['HOME','首页'],['RESULT','结果']].map(([id,label])=>`<button data-preview-draft-page="${id}" aria-pressed="${previewDraft.page===id}" class="${previewDraft.page===id?'selected':''}">${label}</button>`).join('')}</div></div>${devicePreview(state.interface,previewDraft.template,selectedCapabilityIds(),previewDraft.page,true)}<div class="drawer-footer"><p>布局预览，不代表私有 SOP 执行效果。</p><button class="secondary-button" data-preview-cancel>取消</button><button class="primary-button" data-preview-apply>使用此形态</button></div>`;
    showDrawer(); resizeDevicePreviews();
  }
  function renderCatalog() {
    const d = state.dialogDraft;
    if (!d) return;
    const q = drawer.querySelector(".catalog-search").value.toLowerCase(),
      group = $("#category").value,
      only = $("#only-selected").checked;
    const items = visibleModels().map((m) => ({
            ref: m.modelRef,
            name: m.displayName,
            description: categoryName(m.provider),
            group: m.provider,
            enabled: m.bindingStatus === "CONFIGURED_UNTESTED",
            status: statuses[m.bindingStatus] || m.bindingStatus,
          }));
    drawer.querySelector(".catalog-results").innerHTML =
      items
        .filter(
          (m) =>
            (!group || m.group === group) &&
            (!only || d.selected.has(m.ref)) &&
            (m.name + " " + m.description).toLowerCase().includes(q),
        )
        .map(
          (m) =>
            `<div class="${d.kind === "caps" ? "library-cap" : "model-row"} ${d.selected.has(m.ref) ? "is-selected" : ""} ${m.enabled ? "" : "is-unavailable"}"><label class="model-choice"><input type="checkbox" data-pick="${esc(m.ref)}" ${d.selected.has(m.ref) ? "checked" : ""} ${m.enabled ? "" : "disabled"}><span><strong>${esc(m.name)}</strong><small>${esc(m.description)}</small></span></label><span class="catalog-status">${esc(m.status)}</span>${d.kind === "models" ? `<label class="default-model"><input type="radio" name="default-model" value="${esc(m.ref)}" ${d.defaultModel === m.ref ? "checked" : ""} ${!d.selected.has(m.ref) ? "disabled" : ""}>默认</label>` : ""}</div>`,
        )
        .join("") || '<p class="catalog-empty">没有匹配项</p>';
    drawer.querySelector(".catalog-summary").innerHTML =
      `<strong>已选 ${d.selected.size} 项</strong>${d.kind === "models" ? `<span>默认 · ${esc(modelName(d.defaultModel))}</span>` : ""}`;
    $("#catalog-save").disabled =
      !d.selected.size ||
      (d.kind === "models" && !d.selected.has(d.defaultModel));
  }
  drawer.addEventListener("input", (e) => {
    if (e.target.matches(".catalog-search")) renderCatalog();
  });
  drawer.addEventListener("change", (e) => {
    if(e.target.id==='ui-theme') return;
    const d = state.dialogDraft;
    if (!d) return;
    if (e.target.dataset.pick) {
      const ref = e.target.dataset.pick;
      if (e.target.checked) d.selected.add(ref);
      else d.selected.delete(ref);
      if (!d.selected.has(d.defaultModel))
        d.defaultModel = [...d.selected][0] || "";
    }
    if (e.target.name === "default-model") d.defaultModel = e.target.value;
    renderCatalog();
  });
  drawer.addEventListener("click", (e) => {
    if(e.target===drawer&&moduleDraft){
      const box=drawer.getBoundingClientRect();
      if(e.clientX<box.left||e.clientX>box.right||e.clientY<box.top||e.clientY>box.bottom)drawer.close();
      return;
    }
    if(drawer.classList.contains('device-preview-dialog')){
      if(e.target===drawer){
        const box=drawer.getBoundingClientRect();
        if(e.clientX<box.left||e.clientX>box.right||e.clientY<box.top||e.clientY>box.bottom)drawer.close();
        return;
      }
      if(e.target.closest('[data-preview-cancel]')){drawer.close();return;}
      if(e.target.closest('[data-preview-apply]')){
        if(previewDraft){state.template=previewDraft.template;state.epoch++;}
        drawer.close();render();return;
      }
      const terminal=e.target.closest('[data-preview-terminal]'),page=e.target.closest('[data-preview-draft-page]');
      if(previewDraft&&(terminal||page)){
        if(terminal)previewDraft.template=terminal.dataset.previewTerminal;
        if(page)previewDraft.page=page.dataset.previewDraftPage;
        renderPreviewDialog();
        drawer.querySelector(terminal?`[data-preview-terminal="${previewDraft.template}"]`:`[data-preview-draft-page="${previewDraft.page}"]`)?.focus({preventScroll:true});return;
      }
    }
    const capability=e.target.closest('[data-pack-detail]');
    if(capability){moduleCapabilityDetail(capability.dataset.packDetail,capability.dataset.packVersion);return;}
    if(e.target.closest('[data-module-back]')){if(moduleDraft)moduleDetails(moduleDraft.id,true);return;}
    if(e.target.closest('[data-module-confirm]')){
      if(!moduleDraft||moduleDraft.revision!==moduleDirectory().revision){toast('目录已更新，请重新选择。');drawer.close();return;}
      state.packBindings=structuredClone(moduleDraft.bindings);state.selectedMethod=moduleDraft.selectedMethod;state.missingMethod=moduleDraft.missingMethod;syncModules();state.epoch++;drawer.close();render();return;
    }
    if(e.target.id==='ui-reset'){state.interface=defaultUI();drawer.close();render();return;}
    if(e.target.id==='ui-save'){
      const name=$('#ui-name').value.trim();if(!name||/[<>\x00-\x1f]/.test(name)){toast('名称不能为空或包含特殊标记。');return;}
      state.interface={...state.interface,assistantName:name,brandIcon:$('#ui-icon').value,theme:$('#ui-theme').value,balanceCard:$('#ui-card').value,fontSize:$('#ui-font').value,shortcuts:[...$('#ui-order').value.split(','),...state.interface.shortcuts.filter(s=>s.startsWith('CASHFLOW_'))],voiceInput:!$('#ui-voice').disabled&&$('#ui-voice').checked,sourcePanelExpanded:$('#ui-panel').checked};drawer.close();render();return;
    }
    const business = e.target.closest("[data-business-open]");
    if(business?.hasAttribute('data-method-management')&&!confirm('前往方法管理将放弃本抽屉尚未确认的能力与方法修改。已确认的 Agent 草稿保留，是否继续？'))return;
    if(business){assets.showBusiness(business.dataset.businessOpen);drawer.close();state.page='assets';render();return;}
    const confirmed = e.target.closest("[data-delete-confirm]");
    if (confirmed) {
      const id = confirmed.dataset.deleteConfirm;
      drawer.close();
      perform(async () => {
        await api(`/api/v1/builds/${id}/delete`, { buildId: id });
        clear(id);
        if (state.active === id) state.active = null;
        if (state.editing === id) state.editing = null;
        state.optimizationViews.delete(id);
        state.optimizationNodes.delete(id);
        state.builds = state.builds.filter((b) => b.buildId !== id);
        state.page = "agents";
        toast("Agent 已删除，生成文件已清理。");
      });
      return;
    }
    if (e.target.id === "bank-save") {
      drawer.close();
      toast("已选择本地模拟银行。");
      return;
    }
    if (e.target.closest(".drawer-close,.module-cancel,#catalog-cancel")) drawer.close();
    if (e.target.id === "catalog-save") {
      const d = state.dialogDraft;
      state.models = d.selected;
      state.defaultModel = d.defaultModel;
      drawer.close();
      render();
    }
  });
  drawer.addEventListener("close", () => {
    // close is queued: another dialog or render may already have replaced its opener.
    if (drawer.open) return;
    moduleDraft = null;
    previewDraft = null;
    state.dialogDraft = null;
    const target = state.returnFocus;
    state.returnFocus = null;
    if (!target || target.page !== state.page || target.active !== state.active) return;
    const current = document.activeElement;
    if (current !== document.body && current?.isConnected && !drawer.contains(current)) return;
    const element = target.element?.isConnected ? target.element : target.selector ? $(target.selector) : null;
    if (element && !element.disabled) element.focus({preventScroll: true});
  });
  drawer.addEventListener('keydown',e=>{
    if(e.key==='Tab'&&drawer.open){
      const nodes=[...drawer.querySelectorAll('button,input,select,textarea,a[href],[tabindex]')].filter(n=>!n.disabled&&n.tabIndex>=0&&n.getClientRects().length);
      const first=nodes[0],last=nodes.at(-1),current=document.activeElement;
      if(nodes.length&&(e.shiftKey&&(current===first||!drawer.contains(current))||!e.shiftKey&&(current===last||!drawer.contains(current)))){e.preventDefault();(e.shiftKey?last:first).focus();}
    }
    const current=e.target.closest('[data-preview-terminal]');
    if(!current||!['ArrowLeft','ArrowRight','Home','End'].includes(e.key))return;
    const options=[...drawer.querySelectorAll('[data-preview-terminal]')],index=options.indexOf(current);
    const next=e.key==='Home'?0:e.key==='End'?options.length-1:(index+(e.key==='ArrowLeft'?-1:1)+options.length)%options.length;
    e.preventDefault();options[next].click();
  });
  async function perform(fn) {
    if (state.busy) return;
    state.epoch++;
    state.busy = true;
    state.notice = "";
    render();
    try {
      await fn();
    } catch (e) {
      state.notice = e.message;
    } finally {
      state.busy = false;
      render();
    }
  }
  async function reload() {
    const cat = await api("/api/v1/catalog");
    state.catalog = cat;
    cat.capabilities=catalogCapabilities(cat.capabilities||[],managedEntries());
    if(unifiedReady()&&!state.defaultsInitialized){
      const initial=taskBlueprint();
      if(initial)applyTaskBlueprintDefaults(initial);
      state.defaultsInitialized=true;
    }
    for (const c of cat.capabilities) {
      names[c.id] = c.label;
      descriptions[c.id] = c.description;
    }
    state.connections = (await api("/api/v1/connections")).connections;
    state.builds = (await api("/api/v1/builds")).builds;
  }
  function target() {
    const b = active(),
      h = memory(b.buildId);
    return {
      buildId: b.buildId,
      instanceId: b.instance.instanceId,
      sessionId: h.sessionId,
      epoch: state.epoch,
    };
  }
  function isCurrent(t) {
    return (
      state.active === t.buildId &&
      active()?.instance.instanceId === t.instanceId &&
      memory(t.buildId).sessionId === t.sessionId &&
      state.epoch === t.epoch
    );
  }
  async function sessionCall(action, extra = {}) {
    const t = target();
    try {
      const r = action === "chat" ? await FinanceAgUi.chat({
        url: `/api/v1/builds/${t.buildId}/ag-ui`, csrf: state.csrf,
        instanceId: t.instanceId, sessionId: t.sessionId, query: extra.query,
      }) : await api(`/api/v1/builds/${t.buildId}/${action}`, {
        instanceId: t.instanceId,
        sessionId: t.sessionId,
        ...extra,
      });
      if (!isCurrent(t)) return null;
      return r;
    } catch (e) {
      if (isCurrent(t) && e.data?.clearConversation) {
        clear(t.buildId);
        state.notice = e.message;
        render();
      }
      throw e;
    }
  }
  async function launch() {
    let b = active();
    const epoch = state.epoch,
      id = b.buildId;
    const current = () => state.active === id && state.epoch === epoch;
    if (b.instance.status !== "READY") {
      b = await api(`/api/v1/builds/${b.buildId}/start`, {
        artifactId: b.artifact.artifactId,
      });
      if (!current()) return;
      storeBuild(b);
    }
    if (["0.6","0.7","0.8","0.9","0.10"].includes(b.selection.schema_version)) {
      const link=await api(`/api/v1/builds/${b.buildId}/experience`,{instanceId:b.instance.instanceId});
      if(current()) location.assign(link.url);
      return;
    }
    const h = memory(b.buildId);
    if (!h.sessionId) {
      if (b.instance.sessionId) {
        h.sessionId = b.instance.sessionId;
        h.modelRef = b.instance.modelRef || b.modelSelection?.defaultModelRef;
      } else {
        const r = await api(`/api/v1/builds/${b.buildId}/sessions`, {
          instanceId: b.instance.instanceId,
        });
        if (!current()) return;
        h.sessionId = r.sessionId;
        h.modelRef = r.result.modelRef;
        h.evidence = r.result;
      }
    }
    state.page = "agent";
    state.epoch++;
  }
  app.addEventListener("click", (e) => {
    const clickedStrip=e.target.closest('.template-strip,#module-results');
    if(clickedStrip&&suppressedStripClick?.strip===clickedStrip&&performance.now()<suppressedStripClick.until){
      e.preventDefault();
      return;
    }
    const card = e.target.closest('[data-select-agent]');
    if (card && !e.target.closest('button,a,input,label')) { toggleAgent(card.dataset.selectAgent); return; }
    const moduleCard=e.target.closest('[data-module-detail]');
    if(moduleCard&&!e.target.closest('button,a,input,label')){
      moduleDetails(moduleCard.dataset.moduleDetail);
      return;
    }
    const el = e.target.closest("button,a");
    if (!el) return;
    if(el.dataset.templateScroll){
      const strip=app.querySelector('.template-strip');
      strip?.scrollBy({left:(el.dataset.templateScroll==='prev'?-1:1)*strip.clientWidth*0.8,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});
      return;
    }
    if(el.dataset.moduleScroll){
      const strip=app.querySelector('#module-results');
      strip?.scrollBy({left:(el.dataset.moduleScroll==='prev'?-1:1)*strip.clientWidth*0.8,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});
      return;
    }
    if (el.dataset.action === 'batch-delete') { confirmBatchDelete(); return; }
    if(el.dataset.previewPage){state.previewPage=el.dataset.previewPage;render();return;}
    if(el.dataset.action==='enlarge-preview'){enlargePreview();return;}
    if(el.dataset.action==='customize'){customize();return;}
    if (el.dataset.optimizationNode) {
      state.optimizationNodes.set(state.active, el.dataset.optimizationNode);
      const slot = $("#optimization-slot");
      if (slot) slot.innerHTML = optimizationPanel(state.active);
      slot?.querySelector(
        `[data-optimization-node="${el.dataset.optimizationNode}"]`,
      )?.focus();
      return;
    }
    if (el.dataset.moduleDetail) {
      moduleDetails(el.dataset.moduleDetail);
      return;
    }
    if(el.dataset.businessDomain){
      selectBusinessDomain(el.dataset.businessDomain);
      return;
    }
    if(el.dataset.taskBlueprint){
      selectTaskBlueprint(el.dataset.taskBlueprint);
      return;
    }
    if(el.dataset.customComposition){
      selectCustomComposition();
      return;
    }
    if (el.dataset.scenario) {
      selectScenario(el.dataset.scenario);
      return;
    }
    if (el.dataset.nav) {
      if(state.page==='assets'&&el.dataset.nav!=='assets'&&!assets.leave())return;
      state.page = el.dataset.nav;
      state.notice = "";
      state.epoch++;
      render();
      if(state.page==='assets')assets.refresh().then(render).catch(e=>toast(e.message));
      if(state.page==='history')executionRecords.refresh();
      return;
    }
    if (el.dataset.template) {
      state.template = el.dataset.template;
      render();
      return;
    }
    if (el.dataset.catalog) {
      openCatalog(el.dataset.catalog);
      return;
    }
    if (el.dataset.open) {
      state.active = el.dataset.open;
      state.page = "building";
      state.epoch++;
      render();
      const buildId = state.active;
      loadOptimizationView(buildId)
        .then(() => {
          if (state.active !== buildId || state.page !== "building") return;
          const slot = $("#optimization-slot");
          if (slot) slot.innerHTML = optimizationPanel(buildId);
        })
        .catch((error) => toast(error.message));
      return;
    }
    if (el.dataset.edit) {
      const b = state.builds.find((x) => x.buildId === el.dataset.edit);
      if(!b)return;
      const savedMethod = b.selection.methodRef ? state.methodVersions.find(v=>v.assetId===b.selection.methodRef.assetId&&v.version===b.selection.methodRef.version&&v.releaseDigest===b.selection.methodRef.releaseDigest) : null;
      state.scenario = b.selection.scenarioTemplateId || 'customer-custom';
      state.packBindings = structuredClone(b.selection.packBindings || []);
      state.migrationWarnings=Array.isArray(b.selection.packBindings)?[]:['原 Agent 未记录显式能力包与提供方版本，无法安全迁移。请另建空白草稿并重新明确选择；原制品保持不变。'];
      state.missingMethod=b.selection.methodRef&&!savedMethod?structuredClone(b.selection.methodRef):null;
      state.selectedMethod = savedMethod;
      state.runtime = b.selection.runtimeId;
      state.modules = new Set(b.selection.moduleIds || (unifiedReady()?bindingModules(state.packBindings,moduleDirectory()):[]));
      const exactBlueprint=moduleDirectory().taskBlueprints.find(item=>
        item.moduleIds.length===state.modules.size&&item.moduleIds.every(id=>state.modules.has(id)));
      state.taskBlueprint=exactBlueprint?.id || null;
      state.customComposition=!exactBlueprint;
      const firstModule=[...state.modules][0];
      state.businessDomain=exactBlueprint?.businessDomainId ||
        moduleDirectory().moduleDomainAssignments.find(item=>item.moduleId===firstModule)?.businessDomainId ||
        'business-processing';
      state.directoryQuery = "";
      state.expanded = false;
      if (b.modelSelection) {
        state.models = new Set(b.modelSelection.selectedModelRefs);
        state.defaultModel = b.modelSelection.defaultModelRef;
      }
      state.template = b.selection.presentation || "MOBILE_WEB";
      if(state.template==='RESPONSIVE_WEB')state.template='MOBILE_WEB';
      state.interface = b.selection.interfaceConfig ? structuredClone(b.selection.interfaceConfig) : defaultUI();
      state.editing = b.buildId;
      state.page = "create";
      state.epoch++;
      render();
      return;
    }
    if (el.dataset.delete) {
      confirmDelete(el.dataset.delete);
      return;
    }
    const a = el.dataset.action;
    if (!a) return;
    if (a === "new" || a === "list") {
      if (a === "new") state.editing = null;
      state.page = a === "new" ? "create" : "agents";
      render();
      return;
    }
    if (a === "bank-picker") {
      openBank();
      return;
    }
    if (a === "evidence") {
      const h = memory(active().buildId);
      rememberFocus(el);
      drawer.className = "catalog-drawer";
      drawer.innerHTML = `<div class="drawer-header"><h2>来源与执行证据</h2><button class="icon-button drawer-close" aria-label="关闭">&#215;</button></div><div class="drawer-body"><pre>${esc(JSON.stringify(h.evidence || { status: "暂无当前会话证据" }, null, 2))}</pre></div>`;
      showDrawer();
      return;
    }
    if (a === "revoke") {
      const t = target();
      clear(t.buildId);
      drawer.close();
      render();
      api(`/api/v1/builds/${t.buildId}/revoke`, {
        instanceId: t.instanceId,
        sessionId: t.sessionId,
      })
        .then(() => toast("授权已撤销，对话已清除。"))
        .catch((e) => toast(e.message));
      return;
    }
    if (a === "stop") {
      const b = active();
      clear(b.buildId);
      const epoch = state.epoch;
      render();
      api(`/api/v1/builds/${b.buildId}/stop`, {
        instanceId: b.instance.instanceId,
      })
        .then((r) => {
          storeBuild(r);
          if (state.active !== b.buildId || state.epoch !== epoch) return;
          state.page = "building";
          render();
        })
        .catch((e) => toast(e.message));
      return;
    }
    perform(async () => {
      const epoch = state.epoch;
      if (a === "refresh") {
        await reload();
        toast("目录已刷新");
      }
      if (a === "build") {
        if(!buildableSelection())throw new Error(selectionBlockers().join(' '));
        const s = selection(),
          pre = await api("/api/v1/preflight", s);
        if (!pre.canBuild) throw new Error("构建前置检查未通过");
        const b = await api("/api/v1/builds", s);
        storeBuild(b);
        if (state.epoch !== epoch) return;
        state.active = b.buildId;
        state.page = "building";
      }
      if (a === "launch") await launch();
    });
  });
  app.addEventListener('pointerdown',(e)=>{
    const strip=e.target.closest('.template-strip,#module-results');
    if(!strip||!e.isPrimary||e.button!==0||strip.scrollWidth<=strip.clientWidth+1)return;
    horizontalDrag={pointerId:e.pointerId,startX:e.clientX,startY:e.clientY,startScroll:strip.scrollLeft,
      horizontal:false,strip};
  });
  app.addEventListener('pointermove',(e)=>{
    if(!horizontalDrag||horizontalDrag.pointerId!==e.pointerId)return;
    const dx=e.clientX-horizontalDrag.startX,dy=e.clientY-horizontalDrag.startY;
    if(!horizontalDrag.horizontal&&Math.abs(dx)>10&&Math.abs(dx)>Math.abs(dy)*1.2){
      horizontalDrag.horizontal=true;
      horizontalDrag.strip.classList.add('is-dragging');
      horizontalDrag.strip.setPointerCapture?.(e.pointerId);
    }
    if(!horizontalDrag.horizontal)return;
    e.preventDefault();
    horizontalDrag.strip.scrollLeft=horizontalDrag.startScroll-dx;
  });
  const finishHorizontalDrag=(e)=>{
    if(!horizontalDrag||horizontalDrag.pointerId!==e.pointerId)return;
    const {strip,horizontal}=horizontalDrag;
    horizontalDrag=null;
    strip.classList.remove('is-dragging');
    if(strip.hasPointerCapture?.(e.pointerId))strip.releasePointerCapture(e.pointerId);
    if(horizontal)suppressedStripClick={strip,until:performance.now()+350};
  };
  app.addEventListener('pointerup',finishHorizontalDrag);
  app.addEventListener('pointercancel',finishHorizontalDrag);
  app.addEventListener("change", (e) => {
    if (e.target.dataset.agentCheckbox) { toggleAgent(e.target.dataset.agentCheckbox); return; }
    if (e.target.id === 'select-all-agents') {
      if (deletingAgents) return;
      selectedAgents.clear();
      if (e.target.checked) state.builds.filter(editable).forEach(b => selectedAgents.add(b.buildId));
      render();
      return;
    }
    if(e.target.id==='selected-method'){selectMethod(state.methodVersions.find(v=>`${v.assetId}@${v.version}`===e.target.value)||null);render();return;}
    if (e.target.dataset.module) {
      const id = e.target.dataset.module;
      if (!availableModules().some(m => m.id === id && canSelectModule(m))) return;
      selectModule(id,e.target.checked);
      render();
      app.querySelector(`[data-module="${id}"]`)?.focus();
      return;
    }
    if (e.target.dataset.runtime) {
      state.runtime = e.target.dataset.runtime;
      state.epoch++;
      render();
      app.querySelector(`[data-runtime="${state.runtime}"]`)?.focus();
      return;
    }
    if (e.target.id === "session-model") {
      const ref = e.target.value;
      if (ref === memory(active().buildId).modelRef) return;
      perform(async () => {
        const b = active(),
          h = memory(b.buildId),
          r = await sessionCall("switch-model", { modelRef: ref });
        if (!r) return;
        if (h.messages.length)
          h.archives.push({ modelRef: h.modelRef, messages: [...h.messages] });
        h.messages = [];
        h.sessionId = r.sessionId;
        h.modelRef = r.result.modelRef;
        h.evidence = r.result;
        state.epoch++;
        toast("已开启新会话，历史未传给新模型。");
      });
    }
  });
  app.addEventListener("input", (e) => {
    if (e.target.id !== "directory-search") return;
    state.directoryQuery = e.target.value;
    $("#module-results").innerHTML = moduleResults();
    const pagination = $('#module-pagination');
    if (pagination) pagination.innerHTML = modulePagination();
  });
  app.addEventListener("submit", (e) => {
    if (e.target.id !== "chat-form") return;
    e.preventDefault();
    const query = $("#query").value.trim();
    if (!query || !memory(active().buildId).sessionId) return;
    $("#query").value = "";
    perform(async () => {
      const b = active(),
        h = memory(b.buildId);
      h.messages.push({ role: "user", text: query });
      render(true);
      const r = await sessionCall("chat", { query });
      if (!r) return;
      h.messages.push({
        role: "assistant",
        text: answer(r.result.response),
        modelRef: r.result.modelRef,
      });
      h.evidence = r.result;
      render();
      requestAnimationFrame(() => {
        r.browserTiming?.displayed();
        const id = r.result.executionObservation?.requestId;
        if (id && r.browserTiming) api(`/api/v1/execution-history/${encodeURIComponent(id)}/client-timing`, r.browserTiming.result()).catch(() => {});
      });
    });
  });
  app.addEventListener("keydown", (e) => {
    if(e.target.matches('[data-module-detail]')&&[' ','Enter'].includes(e.key)){
      e.preventDefault();
      moduleDetails(e.target.dataset.moduleDetail);
      return;
    }
    if (e.target.matches('[data-select-agent]') && [' ', 'Enter'].includes(e.key)) {
      e.preventDefault(); toggleAgent(e.target.dataset.selectAgent); return;
    }
    if (
      e.target.id === "query" &&
      e.key === "Enter" &&
      !e.shiftKey &&
      !e.isComposing
    ) {
      e.preventDefault();
      $("#chat-form").requestSubmit();
    }
    if (
      e.target.dataset.template &&
      ["ArrowLeft", "ArrowRight"].includes(e.key)
    ) {
      e.preventDefault();
      const index=terminals.findIndex(t=>t.id===state.template);
      state.template=terminals[(index+(e.key==='ArrowLeft'?-1:1)+terminals.length)%terminals.length].id;
      render();
      app.querySelector(`[data-template="${state.template}"]`)?.focus();
    }
  });
  let polling = false;
  async function poll() {
    if (polling || state.busy || !active()) return;
    polling = true;
    const id = state.active;
    const epoch = state.epoch;
    try {
      const b = await api("/api/v1/builds/" + id);
      if (id !== state.active || epoch !== state.epoch || state.busy) return;
      const previous = active();
      const h = memory(id);
      if (
        h.sessionId &&
        (b.instance.status !== "READY" ||
          b.instance.instanceId !== previous.instance.instanceId ||
          b.instance.sessionId !== h.sessionId)
      ) {
        clear(id);
        state.notice = errors.INSTANCE_CHANGED;
        storeBuild(b);
        render();
        return;
      }
      storeBuild(b);
      await loadOptimizationView(id).catch(() => null);
      if (
        id === state.active &&
        h.sessionId &&
        !state.busy &&
        b.instance.status === "READY"
      ) {
        await sessionCall("authorization");
      }
      if (
        id === state.active &&
        !state.busy &&
        !drawer.open &&
        state.page === "building"
      )
        updateBuildPage(b);
    } catch (e) {
      if (id === state.active && epoch === state.epoch) {
        clear(id);
        state.notice = e.message;
        if (state.page === "agent" && !state.busy) render();
      }
    } finally {
      polling = false;
    }
  }
  app.addEventListener('click', e=>assets.act(e));
  app.addEventListener('input', e=>assets.input(e));
  app.addEventListener('change', e=>assets.change(e));
  drawer.addEventListener('change', e=>{
    if(e.target.hasAttribute('data-method-choice')){
      if(!moduleDraft||!unifiedReady())return;
      const input=e.target,entry=managedEntries().find(e=>e.pack.id===input.dataset.methodChoice&&e.pack.version===input.dataset.packVersion);
      if(!entry)return;
      const version=draftMethodChoices(entry).find(v=>`${v.assetId}@${v.version}`===input.value&&v.releaseDigest===input.selectedOptions[0]?.dataset.releaseDigest);
      if(input.value&&!version)return;
      moduleDraft.selectedMethod=version||null;moduleDraft.missingMethod=null;
      moduleCapabilityDetail(entry.pack.id,entry.pack.version);
      drawer.querySelector('[data-method-choice]')?.focus({preventScroll:true});return;
    }
    const input=e.target,packId=input.dataset.packId||input.dataset.packProvider;
    if(!packId||!unifiedReady()||!moduleDraft)return;
    const entry=managedEntries().find(value=>value.pack.id===packId&&value.pack.version===input.dataset.packVersion);
    if(!entry)return;
    if(input.dataset.packId){
      if(input.checked&&!moduleDraft.bindings.some(b=>sameAsset(b.pack,entry.pack))){
        const declared=moduleDirectory().components.flatMap(p=>p.packBindings||[]).find(b=>sameAsset(b.pack,entry.pack));
        const provider=declared?.provider||(entry.providers.length===1?entry.providers[0]:null);
        moduleDraft.bindings.push({pack:assetRef(entry.pack),...(provider?{provider:assetRef(provider)}:{})});
      }else if(!input.checked)moduleDraft.bindings=moduleDraft.bindings.filter(binding=>!sameAsset(binding.pack,entry.pack));
    }else{
      const binding=moduleDraft.bindings.find(value=>sameAsset(value.pack,entry.pack));
      const provider=entry.providers.find(value=>JSON.stringify(assetRef(value))===input.value);
      if(!binding||!provider)return;
      binding.provider=assetRef(provider);
    }
    if(input.dataset.packProvider)return;
    const selector=input.dataset.packId?'data-pack-id':'data-pack-provider',scroll=drawer.querySelector('.drawer-body')?.scrollTop||0;
    moduleDetails(input.dataset.packModule,true);
    drawer.querySelector(`[${selector}="${CSS.escape(packId)}"][data-pack-version="${CSS.escape(entry.pack.version)}"]`)?.focus({preventScroll:true});
    drawer.querySelector('.drawer-body').scrollTop=scroll;
  });
  (async () => {
    const boot = await api("/api/bootstrap");
    state.csrf = boot.csrf;
    state.serverMode = boot.deploymentMode === "AUTHENTICATED_SERVER";
    await reload();
    await assets.refresh();
    const configured = state.catalog.models.filter(
      (m) => m.bindingStatus === "CONFIGURED_UNTESTED",
    );
    const first =
      configured.find((m) => m.modelRef === preferredDefaultModelRef) ||
      configured[0];
    if (first) {
      state.models.add(first.modelRef);
      state.defaultModel = first.modelRef;
    }
    const requested = new URLSearchParams(location.search).get("agent");
    if (state.builds.some((b) => b.buildId === requested)) {
      state.active = requested;
      state.page = "building";
      await loadOptimizationView(requested);
    }
    render();
    setInterval(poll, 2000);
  })().catch((e) => {
    app.innerHTML = `<div class="loading"><h1>工作台暂不可用</h1><p role="alert">${esc(e.message)}</p></div>`;
  });
})();
