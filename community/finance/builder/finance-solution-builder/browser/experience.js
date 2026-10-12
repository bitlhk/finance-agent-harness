import { renderMarkdown } from "./markdown.js";
import { cards, esc, icon } from "./presentation.js";
import { HttpAgent } from "@ag-ui/client";
import { requestTiming } from './request-timing.js';
import { captureScroll, restoreScroll } from "./message-scroll.js";
import { buildAuditPrompt, scenarioFromCapabilities, validateBundle } from "./smart-audit-bundle.js";

const app = document.querySelector("#experience"),
  modal = document.querySelector("#details");
const key = "finance-experience:" + location.pathname;
let token = location.hash.slice(1) || sessionStorage.getItem(key) || "";
if (location.hash) {
  sessionStorage.setItem(key, token);
  history.replaceState(null, "", location.pathname);
}
let config,
  caps = [],
  sid = null,
  epoch = 0,
  messages = [],
  busy = false,
  run = null,
  status = "",
  error = "",
  selected = -1,
  period = "",
  lifecycle = {},
  recorder = null,
  media = null,
  audioChunks = [],
  recording = false,
  voiceEpoch = 0,
  network = true,
  home = true,
  panelOpen = true;
let auditBundle = null, auditFileName = "", reviewTeam = [], subagents = null, selectedSubagent = "";
let taskControls = false, taskItems = [], taskBusy = false;
const auditMemberMeta = {
  "case-orchestrator": { materials: 11, rules: 4, scope: "校验材料包、分派审核任务并汇总审核发现", evidence: "材料完整性校验、四项受控核验回执" },
  "personal-borrower-qualification": { materials: 4, rules: 2, scope: "核验借款人身份、家庭关系与申请主体信息", evidence: "身份证、户口本、结婚证、贷款申请表" },
  "personal-income-verification": { materials: 4, rules: 3, scope: "交叉比对收入证明、流水、个税与社保", evidence: "收入证明、流水明细、个税完税证明、社保证明", dependency: "由编排器直接分派" },
  "personal-asset-liability": { materials: 3, rules: 2, scope: "核验不动产信息与既有贷款负债情况", evidence: "不动产权证书、不动产查询、贷款结清证明" },
  "personal-repayment-capacity": { materials: 4, rules: 3, scope: "基于受控收入核验回执测算偿债能力", evidence: "贷款申请表、收入核验回执、资产负债核验回执", dependency: "等待收入核验结果" },
};
const auditStatusLabel = status => ({ READY: "等待编排器调度", RUNNING: "系统核验进行中", COMPLETED: "系统核验已完成", NEEDS_HUMAN_REVIEW: "待人工复核", FAILED: "核验未完成" }[status] || status || "等待处理");
const auditMaterialGroups = bundle => {
  const categories = new Set(bundle?.materials?.map(item => item.category) || []);
  return [
    ["身份与申请", ["身份证", "户口本", "结婚证", "贷款申请表"]],
    ["收入交叉验证", ["收入证明", "流水明细", "个人所得税完税证明", "社会保险参保证明"]],
    ["资产与负债", ["不动产权证书", "不动产信息查询结果", "贷款结清证明"]],
  ].map(([name, values]) => ({ name, count: values.filter(value => categories.has(value)).length, total: values.length }));
};
const auditFinished = () => Boolean(subagents?.members?.length) && subagents.members.every(member => !["READY", "RUNNING"].includes(member.status));
// AG-UI deltas are already plain text.  The final evidence event keeps the
// candidate's structured response for the collapsed evidence drawer, so it
// needs the same small, bounded text projection before it can be used as a
// fallback for an interrupted stream.
const responseText = value => {
  if (typeof value === "string") return value;
  if (!value || typeof value !== "object") return "";
  for (const key of ["output", "answer", "content", "response", "result"]) {
    const text = responseText(value[key]);
    if (text) return text;
  }
  return "";
};
async function refreshTasks() {
  if (!taskControls || !sid) return;
  const current = epoch, result = await api('tasks');
  if (current === epoch) taskItems = result.tasks;
}
const taskLabels = {CREATED:'待确认', NEED_USER:'待补充', PROCESSING:'处理中', UNKNOWN:'结果待查证',
  COMPLETED:'已完成', FAILED:'未完成', REJECTED:'已拒绝', NOT_OBSERVED:'待查证'};
function taskPanel() {
  if (!taskControls) return '';
  return `<section class="task-controls"><header><h2>办理记录</h2><button data-task="refresh" title="刷新记录" aria-label="刷新记录">${icon('refresh-cw')}</button></header>${taskItems.map(t => `<article data-task-handle="${esc(t.handle)}"><header><strong>${esc(t.name)}</strong><span>${esc(taskLabels[t.sourceStatus] || t.sourceStatus)}</span></header>${t.terminal?.content ? `<p>${esc(t.terminal.content)}</p>` : ''}<details><summary>办理内容与结果</summary><pre>${esc(JSON.stringify({parameters:t.parameters,...(Object.keys(t.terminal || {}).length ? {result:t.terminal} : {})},null,2))}</pre></details><div>${t.awaitingConfirmation ? `<button data-task="challenge" data-handle="${esc(t.handle)}" ${busy || taskBusy?'disabled':''}>${icon('check')}核对并确认</button><button data-task="decline" data-handle="${esc(t.handle)}" ${busy || taskBusy?'disabled':''}>${icon('x')}拒绝本次确认</button>` : ''}<button data-task="resume" data-handle="${esc(t.handle)}" ${busy || taskBusy?'disabled':''}>${icon('refresh-cw')}查证原任务</button></div></article>`).join('') || '<p>暂无办理记录</p>'}</section>`;
}
async function taskControl(action, handle, nonce) {
  if (taskBusy || busy || !sid) return;
  taskBusy = true; error = ''; const current = epoch; render();
  try {
    if (action === 'refresh') await refreshTasks();
    else if (action === 'step-up') {
      const verificationCode = modal.querySelector('#verification-code')?.value || '';
      modal.replaceChildren(); modal.close();
      await api('step-up', {verificationCode});
      error = '身份核验完成，请重新核对并确认办理内容。';
    } else {
      const result = await api('task-' + action, {handle, ...(action === 'confirm' ? {nonce} : {})});
      if (current !== epoch) return;
      if (action === 'challenge') {
        openDetails(`<pre>${esc(JSON.stringify(result.parameters,null,2))}</pre><p>确认仅对本次冻结内容有效，截止 ${esc(result.expiresAt)}。</p><button class="primary" data-task="confirm" data-handle="${esc(handle)}" data-nonce="${esc(result.nonce)}">确认办理</button>`, '核对办理内容');
      } else { modal.close(); await refreshTasks(); }
    }
  } catch (e) {
    if (current !== epoch) return;
    if (e.code === 'STEP_UP_REQUIRED') {
      openDetails('<label>模拟银行验证码<input id="verification-code" type="password" maxlength="64" autocomplete="off"></label><button class="primary" data-task="step-up">核验身份</button>', '身份核验');
    } else if (['AUTHORIZATION_REJECTED','SESSION_EXPIRED','SESSION_UNAVAILABLE','INSTANCE_CHANGED'].includes(e.code)) fail(e);
    else error = e.code === 'CONFIRMATION_EXPIRED_OR_CHANGED' ? '确认已失效，请重新核对原任务。' : '操作未完成，请查证原任务，不要重复发起。';
  } finally { taskBusy = false; if (current === epoch) render(); }
}
const authHeaders = () => ({ "X-Agent-Token": token });
const errors = {
  AUTHORIZATION_REJECTED: "授权已失效，会话已清除。",
  SESSION_EXPIRED: "本次服务已到期，请重新开始。",
  SESSION_UNAVAILABLE: "会话已结束，请重新开始。",
  INSTANCE_CHANGED: "实例已变化，请返回管理页重新打开。",
  SERVICE_UNAVAILABLE: "连接暂时不可用，请稍后重新开始。",
  RUN_CANCELLED: "本轮已停止，对话内容已清除，可重新提问。",
  ASR_UNAVAILABLE: "语音服务未配置，可继续文字输入。",
  ASR_SERVICE_FAILED: "识别服务暂时不可用，请重试或输入文字。",
  ASR_SERVICE_REJECTED: "识别服务拒绝请求，请联系维护者。",
  ASR_NO_SPEECH: "未识别到语音，请重试。",
  AUDIO_SILENT: "没有检测到声音。",
  AUDIO_DECODE_FAILED: "音频格式无法识别。",
  AUDIO_DURATION_INVALID: "录音需在 0.1 至 30 秒之间。",
};
async function api(action, body = {}) {
  const response = await fetch("./api/" + action, {
    method: "POST",
    headers: { ...authHeaders(), "Content-Type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  const data = await response.json();
  if (!response.ok)
    throw Object.assign(
      new Error(data.error?.code || data.error || "SERVICE_UNAVAILABLE"),
      { code: data.error?.code || data.error },
    );
  return data;
}
function stopMedia() {
  voiceEpoch++;
  recording = false;
  if (recorder?.state === "recording") recorder.stop();
  media?.getTracks().forEach((t) => t.stop());
  media = null;
  audioChunks = [];
}
function clear() {
  epoch++;
  sid = null;
  messages = [];
  taskItems = []; taskBusy = false;
  selected = -1;
  busy = false;
  run = null;
  lifecycle = {};
  period = "";
  home = true;
  auditBundle = null; auditFileName = ""; reviewTeam = []; subagents = null; selectedSubagent = "";
  stopMedia();
  modal.close();
  modal.replaceChildren();
}
const auditScenario = () => scenarioFromCapabilities(caps);
function auditRail() {
  const scenario = auditScenario();
  if (!scenario) return "";
  const validation = auditBundle ? validateBundle(auditBundle, scenario) : { ok: false, missing: [] };
  const demo = scenario === "personal-loan-audit" ? "personal-loan-demo.json" : "enterprise-credit-demo.json";
  const title = scenario === "personal-loan-audit" ? "个人贷款审核" : "企业授信审核";
  const completed = auditFinished();
  const groups = auditMaterialGroups(auditBundle);
  return `<aside class="audit-case-rail"><h2>${esc(title)}</h2><ol class="audit-stages"><li class="${auditBundle ? "complete" : "active"}">材料收件</li><li class="${busy ? "active" : completed ? "complete" : ""}">系统核验</li><li class="${completed ? "active" : ""}">待人工复核交接</li></ol><section class="audit-upload"><strong>一次性上传材料包</strong><a href="./demo-materials/${demo}" download>下载演示材料</a><input id="audit-upload" type="file" accept="application/json" ${busy ? "disabled" : ""}>${auditFileName ? `<p>${esc(auditFileName)} · ${auditBundle.materials.length} / ${auditBundle.materials.length} 份材料</p><section class="audit-material-summary" aria-label="材料解析摘要"><h3>已解析字段</h3>${groups.map(group => `<p><strong>${esc(group.name)}</strong><span>${group.count} / ${group.total} 份</span></p>`).join("")}<small>${validation.ok ? "材料完整性校验通过；未发现缺件。" : "材料包尚未通过完整性校验。"}</small></section><details><summary>原始材料与 OCR 内容</summary><pre>${esc(JSON.stringify(auditBundle.materials, null, 2))}</pre></details>` : "<p>仅支持当前演示材料包（JSON）</p>"}${validation.missing.length ? `<p class="audit-gap">缺少：${esc(validation.missing.join("、"))}</p>` : ""}<button class="primary" data-action="audit-review" ${!validation.ok || busy ? "disabled" : ""}>${completed ? "重新发起智能审核" : "发起智能审核"}</button></section></aside>`;
}
function auditLiveBrief() {
  const members = subagents?.members || [];
  const orchestrator = members.find(member => member.id === "case-orchestrator");
  const running = members.find(member => member.status === "RUNNING" && member.id !== "case-orchestrator");
  if (!auditBundle && !busy) return "";
  const phase = busy
    ? running ? `${running.name}正在执行受控核验` : orchestrator?.summary || "编排 Agent 正在分派审核任务"
    : auditFinished() ? "系统核验已完成，正在等待有权人工复核。" : "材料已收齐，等待发起系统核验。";
  return `<section class="audit-live-brief" role="status"><span class="audit-live-dot" aria-hidden="true"></span><div><strong>${esc(phase)}</strong><small>${busy ? "状态仅代表受控核验过程，不代表授信审批结论。" : "完整工作底稿、规则与原始回执已收起，可按需查看。"}</small></div></section>`;
}
function auditMemberDetail(member) {
  const aliases = {
    "smartaudit.personal.borrower-qualification.query": "personal-borrower-qualification",
    "smartaudit.personal.income-verification.query": "personal-income-verification",
    "smartaudit.personal.asset-liability.query": "personal-asset-liability",
    "smartaudit.personal.repayment-capacity.query": "personal-repayment-capacity",
  };
  return { ...(auditMemberMeta[aliases[member?.id] || member?.id] || {}), ...(member || {}) };
}
function subagentPanel() {
  if (!subagents?.members?.length) return '';
  const running = subagents.members.filter(member => member.status === 'RUNNING');
  const completed = subagents.members.filter(member => member.status !== 'RUNNING' && member.status !== 'READY');
  const waiting = subagents.members.filter(member => member.status === 'READY');
  const row = member => { const meta = auditMemberDetail(member); return `<button class="subagent-row ${esc(member.status.toLowerCase())} ${selectedSubagent === member.id ? 'selected' : ''}" data-subagent="${esc(member.id)}"><span class="subagent-icon" aria-hidden="true">${member.status === 'RUNNING' ? '◉' : member.status === 'COMPLETED' ? '✓' : '○'}</span><span><strong>${esc(member.name)}</strong><small>${esc(member.summary || auditStatusLabel(member.status))}</small><i>${meta.materials ? `${meta.materials} 份材料 · ${meta.rules} 条规则` : "统筹 4 项审核任务"}</i></span><em>${member.elapsedMs ? `${Math.ceil(member.elapsedMs / 1000)}s` : ''}</em></button>`; };
  const group = (title, values) => values.length ? `<section class="subagent-group"><h3>${esc(title)} · ${values.length}</h3>${values.map(row).join('')}</section>` : '';
  const member = subagents.members.find(value => value.id === selectedSubagent);
  const meta = member ? auditMemberDetail(member) : {};
  const detail = member ? `<details class="subagent-detail" open><summary>${esc(member.name)} · 审核详情</summary><dl><dt>状态</dt><dd>${esc(auditStatusLabel(member.status))}</dd><dt>任务范围</dt><dd>${esc(meta.scope || member.summary || '审核任务状态汇总')}</dd><dt>材料数</dt><dd>${meta.materials ? `${meta.materials} 份` : '11 份材料包'}</dd><dt>规则数</dt><dd>${meta.rules ? `${meta.rules} 条` : '4 项审核流程'}</dd><dt>耗时</dt><dd>${member.elapsedMs ? `${Math.ceil(member.elapsedMs / 1000)} 秒` : '运行中'}</dd><dt>依赖</dt><dd>${esc(meta.dependency || '由审核案件编排器分派')}</dd><dt>结论</dt><dd>${esc(member.summary || auditStatusLabel(member.status))}</dd><dt>政策</dt><dd>${esc(subagents.policy?.policyId || '未适用')} · ${esc(subagents.policy?.version || '')}</dd></dl><details class="subagent-evidence"><summary>证据与材料范围</summary><p>${esc(meta.evidence || '查看案件材料包与受控运行回执。')}</p><small>证据仅供有权人工复核人核对，不构成审批结论。</small></details></details>` : '';
  return `<section class="subagent-panel"><header><h2>Subagents</h2><small>${esc(subagents.policy?.label || '')}</small></header>${group('已开启', running)}${group('完成', completed)}${group('等待', waiting)}${detail}</section>`;
}
async function loadAuditBundle(file) {
  const scenario = auditScenario();
  if (!scenario || !file) return;
  try {
    const bundle = JSON.parse(await file.text());
    const validation = validateBundle(bundle, scenario);
    if (!validation.ok) throw new Error(validation.missing.length ? `材料包不完整：${validation.missing.join("、")}` : "材料包与当前审核场景不匹配");
    auditBundle = bundle; auditFileName = file.name; error = ""; render();
  } catch (failure) { auditBundle = null; auditFileName = ""; error = failure.message || "材料包读取失败"; render(); }
}
function fail(e) {
  error = errors[e.code || e.message] || "服务暂时不可用。";
  if (e.code === "RUN_CANCELLED") {
    messages = [];
    selected = -1;
    busy = false;
    run = null;
  } else clear();
  render();
}
const shortcutDefinitions = {
  BALANCE: ["account.balance.query", "查询余额", "landmark"],
  WEALTH: ["wealth.facts.describe", "查看资产", "blocks"],
  CASHFLOW_SUMMARY: ["finance.transaction-summary.query", "收支汇总", "chart-column"],
  CASHFLOW_ANALYSIS: ["finance.spending-analysis.query", "收支分类", "chart-column"],
};
const shortcuts = () => config.shortcuts.filter(s => caps.includes(shortcutDefinitions[s]?.[0]));
const query = (s) => {
  if (s.startsWith('CASHFLOW_')) {
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(period) || period.startsWith('0000')) return null;
    return `请查询 ${period} 的${s === 'CASHFLOW_SUMMARY' ? '收支汇总' : '收支分类分析'}，说明来源口径和信息缺口。`;
  }
  return s === "BALANCE" ? "请查询我的账户余额。" : "请查询我的资产概览，并说明风险测评状态和信息缺口。";
};
function sources() {
  return selected >= 0
    ? messages[selected]?.display
    : messages.findLast((m) => m.display)?.display;
}
function render(forceLatest = false) {
  if (!config) return;
  const scroll = captureScroll(app.querySelector('.messages'), forceLatest);
  const draft = document.querySelector("#query")?.value || "";
  const focused = document.activeElement?.id === 'query';
  const atm = config.terminal === "ATM_WEB",
    desktop = config.terminal === "DESKTOP_WEB";
  const scenarioTitle = auditScenario() === "personal-loan-audit" ? "智能信贷审核" : auditScenario() === "enterprise-credit-audit" ? "企业授信审核" : config.assistantName;
  document.body.className = `experience theme-${config.theme} ${config.terminal.toLowerCase()} ${config.fontSize === "LARGE" ? "font-large" : ""} ${caps.includes('finance.transaction-summary.query') ? 'cashflow-enabled' : ''}`;
  document.title = config.assistantName;
  app.innerHTML = `<header class="experience-header"><div class="identity"><span class="brand-symbol">${icon(config.brandIcon || "landmark")}</span><div><h1>${esc(scenarioTitle)}</h1><p>演示银行 · ${auditScenario() ? "受控审核 · 模拟数据" : "模拟数据"}${atm ? " · 浏览器模拟终端" : ""}</p></div></div><div class="session-actions">${sid ? `<span class="connection-status">${network ? "已连接" : "连接中断"}</span>${!atm ? '<button data-action="end" class="quiet">结束会话</button>' : ""}` : ""}</div></header><main id="main-content">${error ? `<p role="alert" class="error-banner">${esc(error)}</p>` : ""}${
    !sid
      ? `<section class="welcome"><span class="welcome-icon">${icon("shield-check")}</span><h2>${atm ? "欢迎使用" : "您好，有什么可以帮您？"}</h2><p>账户与财富信息，清晰可查。</p><button class="primary" data-action="start" ${busy || !network ? "disabled" : ""}>${busy ? "正在连接" : "开始服务"} ${icon("arrow-up")}</button><small>仅提供已授权的只读查询</small></section>`
      : `${lifecycle.status === "IDLE_WARNING" ? `<div class="idle-warning" role="alert">本次服务将在 ${lifecycle.remainingSeconds} 秒后结束。<button data-action="continue">继续服务</button></div>` : ""}<div class="experience-layout ${auditScenario() ? "audit-three-pane" : ""} ${panelOpen ? "panel-open" : ""}">${auditRail()}<section class="conversation">${auditLiveBrief()}<div class="quick-actions">${shortcuts()
          .map(
            (s) =>
              `<button data-query="${s}" ${busy ? "disabled" : ""}>${icon(shortcutDefinitions[s][2])}${shortcutDefinitions[s][1]}</button>`,
          )
          .join(
            "",
          )}</div><div class="messages" role="log">${messages.map((m, i) => (m.role === "user" ? `<div class="user-message">${esc(m.text)}</div>` : `<article class="assistant-message ${selected === i ? "selected" : ""}" data-message="${i}"><header>金融助手 <small>${esc(m.display?.modelName || "")}</small></header><div class="markdown">${renderMarkdown(m.text || "正在生成审核工作底稿…")}</div>${m.evidence ? `<p class="audit-notice">仅为审核工作底稿，须人工复核，不构成授信审批或放款结论。</p><details><summary>查看规则与证据</summary><pre>${esc(JSON.stringify(m.evidence, null, 2))}</pre></details>` : ""}${m.display?.cards?.length ? `<button class="source-button" data-source="${i}">${icon("file-check-2")}结果与来源</button>` : ""}</article>`)).join("")}${busy ? `<div class="run-progress" role="status">${icon("refresh-cw")}<span id="run-phase">${esc(status || "正在处理")}</span><button data-action="${run ? "cancel" : "cancel-voice"}">${icon("square")}${run ? "停止本轮" : "取消识别"}</button></div>` : ""}</div>${!atm ? `<div class="composer-area"><form id="chat-form"><textarea id="query" rows="2" maxlength="4000" aria-label="消息" placeholder="输入您的问题" ${busy ? "disabled" : ""}></textarea><div class="composer-actions">${config.voiceInput && config.terminal === "MOBILE_WEB" ? `<button type="button" data-action="voice" class="icon-button ${recording ? "recording" : ""}" title="${recording ? "停止录音" : "语音输入"}" aria-label="${recording ? "停止录音" : "语音输入"}" ${busy ? "disabled" : ""}>${icon(recording ? "square" : "mic")}</button>${recording ? '<button type="button" data-action="cancel-voice">取消录音</button><span role="status">录音中，最长 30 秒</span>' : ""}` : ""}<button type="submit" class="primary icon-button" aria-label="发送" title="发送" ${busy ? "disabled" : ""}>${icon("arrow-up")}</button></div></form><small>模型解释与银行事实分开展示；不构成投资建议。</small></div>` : ""}</section>${desktop ? `<aside class="results-panel"><header><h2>审核协作</h2><button class="icon-button" data-action="panel" title="折叠面板">${icon("panel-right-close")}</button></header>${subagentPanel()}<section class="source-results"><h2>结果与来源</h2>${cards(sources(), config) || '<p class="empty-result">选择一条回答，查看对应事实。</p>'}</section></aside>` : ""}</div>`
  }</main>${atm ? `<footer class="atm-footer"><button data-action="back" ${!sid || home || busy ? "disabled" : ""}>${icon("house")}首页</button><button data-action="help">${icon("circle-help")}帮助</button>${sid ? `<span class="time-left">剩余服务 ${Math.max(0, lifecycle.remainingSeconds || 0)} 秒</span><button class="primary" data-action="end">${icon("log-out")}结束服务</button>` : ""}</footer>` : ""}`;
  if (sid && shortcuts().some(s => s.startsWith('CASHFLOW_'))) {
    app.querySelector('.quick-actions').insertAdjacentHTML('beforebegin', `<label class="cashflow-period">查询月份<input id="cashflow-period" type="month" min="0001-01" max="9999-12" value="${esc(period)}" ${busy ? 'disabled' : ''}></label>`);
    app.querySelector('#cashflow-period').addEventListener('change', e => { period = e.target.value; });
  }
  if (sid && taskControls) app.querySelector('.messages').insertAdjacentHTML('beforeend', taskPanel());
  if (taskControls && !sid) app.querySelector('.welcome small').textContent = '仅使用已安装、已授权的金融能力';
  if (atm && sid && home) {
    const layout = app.querySelector('.experience-layout');
    layout.classList.add('atm-home');
    layout.querySelector('.conversation').insertAdjacentHTML('afterbegin', '<h2>请选择服务</h2>');
  }
  if (desktop || atm) {
    app.querySelector('.session-actions').insertAdjacentHTML('beforeend', '<button class="icon-button" data-action="fullscreen"></button><span class="fullscreen-status" role="status"></span>');
    updateFullscreen();
  }
  app.querySelector('.session-actions').insertAdjacentHTML('afterbegin',
    `<button class="quiet home-button" data-action="home" title="返回首页" aria-label="返回首页">${icon('house')}<span>首页</span></button>${location.pathname.startsWith('/experience/') ? `<button class="quiet home-button" data-action="workbench" title="返回工作台" aria-label="返回工作台">${icon('arrow-left')}<span>工作台</span></button>` : ''}`);
  if (document.querySelector("#query"))
    document.querySelector("#query").value = draft;
  if (focused && !busy) document.querySelector('#query')?.focus({preventScroll: true});
  if (taskBusy) app.querySelectorAll('#chat-form textarea, #chat-form button').forEach(n => n.disabled = true);
  app.querySelector('#audit-upload')?.addEventListener('change', event => loadAuditBundle(event.target.files?.[0]));
  restoreScroll(app.querySelector('.messages'), scroll);
}
function updateFullscreen() {
  const button = app.querySelector('[data-action="fullscreen"]');
  if (!button) return;
  const active = Boolean(document.fullscreenElement);
  const label = active ? '退出全屏' : '进入全屏';
  button.title = label;
  button.setAttribute('aria-label', label);
  button.setAttribute('aria-pressed', String(active));
  button.innerHTML = icon(active ? 'minimize-2' : 'maximize-2');
  button.hidden = !document.fullscreenEnabled && !active;
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.documentElement.requestFullscreen();
  } catch {
    const label = app.querySelector('.fullscreen-status');
    if (label) label.textContent = '浏览器暂不支持全屏。';
  }
  updateFullscreen();
}
document.addEventListener('fullscreenchange', updateFullscreen);
const compactDesktop = window.matchMedia('(max-width: 1000px)');
compactDesktop.addEventListener('change', event => {
  if (event.matches && config?.terminal === 'DESKTOP_WEB') {
    panelOpen = false;
    render();
  }
});
function viewportSize() {
  const viewport = window.visualViewport;
  // Use the visible keyboard-reduced viewport, without overriding pinch zoom.
  const height = innerWidth <= 600 && viewport?.scale === 1 ? viewport.height : innerHeight;
  document.documentElement.style.setProperty('--visible-height', `${height}px`);
}
window.visualViewport?.addEventListener('resize', viewportSize);
window.addEventListener('resize', viewportSize);
viewportSize();
function openDetails(content, title) {
  modal.innerHTML = `<header><h2>${title}</h2><button data-close class="icon-button" aria-label="关闭">${icon("x")}</button></header>${content}`;
  modal.showModal();
}
async function start() {
  if (busy) return;
  busy = true;
  error = "";
  render();
  const e = epoch;
  try {
    const r = await api("start");
    if (e !== epoch) return;
    sid = r.sessionId;
    messages = [];
    home = true;
    lifecycle = (await api("snapshot")).lifecycle;
    await refreshTasks();
  } catch (err) {
    fail(err);
  } finally {
    if (e === epoch) {
      busy = false;
      render();
    }
  }
}
async function end() {
  const pending = api("end").catch(() => {
    error = "连接已断开；服务端到期后失效。";
  });
  clear();
  render();
  await pending;
}
async function chat(text, userText = text) {
  if (busy || !sid || !text) return;
  stopMedia();
  home = false;
  busy = true;
  error = "";
  run = crypto.randomUUID();
  const timing = requestTiming();
  const e = epoch,
    r = run;
  messages.push({ role: "user", text: userText });
  status = "正在核验并执行查询";
  render(true);
  let result, streamed = -1,
    finished = false,
    failure;
  // The candidate flushes each AG-UI delta.  Browsers are allowed to process
  // several received SSE frames in one paint, which makes a real stream look
  // like a single replacement.  Keep a tiny render queue so every received
  // delta becomes visible without inventing model output or audit states.
  let deltaQueue = [], deltaTimer = 0, textStreamClosed = false, finishDelivery;
  const deliveryComplete = new Promise(resolve => { finishDelivery = resolve; });
  const flushDelta = () => {
    deltaTimer = 0;
    if (streamed >= 0 && deltaQueue.length) {
      messages[streamed].text += deltaQueue.shift();
      render(true);
      deltaTimer = window.setTimeout(flushDelta, 24);
    } else if (textStreamClosed) finishDelivery();
  };
  const queueDelta = delta => {
    deltaQueue.push(String(delta || ""));
    if (!deltaTimer) flushDelta();
  };
  const receiveCustom = (event) => {
    if (event.name === "finance.reviewTeam") reviewTeam = event.value?.members || [];
    if (event.name === "finance.subagents") subagents = event.value || null;
    if (event.name === "finance.display") result = { ...event.value, evidence: event.value };
    if (event.name === "finance.evidence") {
      const value = event.value?.result || event.value;
      result = { text: responseText(value.response), display: value.display, evidence: value };
    }
  };
  const agent = new HttpAgent({
    url: "./api/ag-ui",
    threadId: sid,
    initialMessages: [],
    headers: authHeaders(),
    fetch: async (url, init) =>
      fetch(url, {
        ...init,
        headers: { ...authHeaders(), "Content-Type": "application/json" },
        body: JSON.stringify({ query: text, runId: r }),
      }),
  });
  try {
    await agent.runAgent(
      { runId: r },
      {
        onEvent: ({ event }) => {
          if (e !== epoch || r !== run) return;
          timing.event(event);
          if (event.type === "STEP_STARTED") {
            status = "正在执行已授权查询";
            const n = document.querySelector("#run-phase");
            if (n) n.textContent = status;
          }
          if (event.type === "TEXT_MESSAGE_START") {
            messages.push({ role: "assistant", text: "", streaming: true });
            streamed = messages.length - 1; render(true);
          }
          if (event.type === "TEXT_MESSAGE_CONTENT" && streamed >= 0) {
            queueDelta(event.delta);
          }
          if (event.type === "TEXT_MESSAGE_END" && streamed >= 0) {
            messages[streamed].streaming = false;
            textStreamClosed = true;
            if (!deltaTimer && !deltaQueue.length) finishDelivery();
          }
          if (event.type === "CUSTOM") { receiveCustom(event); render(true); }
          if (event.type === "RUN_ERROR")
            failure = Object.assign(new Error(event.code), {
              code: event.code,
            });
          if (event.type === "RUN_FINISHED") finished = true;
        },
        onCustomEvent: ({ event }) => { if (e === epoch && r === run) { receiveCustom(event); render(true); } },
      },
    );
    if (e !== epoch || r !== run) return;
    if (failure) throw failure;
    if (!finished || !result) throw new Error("SERVICE_UNAVAILABLE");
    if (streamed >= 0) {
      textStreamClosed = true;
      if (!deltaTimer && !deltaQueue.length) finishDelivery();
      await deliveryComplete;
    }
    if (streamed >= 0) {
      messages[streamed].text = result.text || messages[streamed].text;
      messages[streamed].display = result.display;
      messages[streamed].evidence = result.evidence;
      selected = streamed;
    } else {
      messages.push({ role: "assistant", text: result.text, display: result.display, evidence: result.evidence });
      selected = messages.length - 1;
    }
    await refreshTasks();
    render();
    requestAnimationFrame(() => {
      if (e !== epoch) return;
      timing.displayed();
      const requestId = result.executionObservation?.requestId;
      if (requestId && location.pathname.startsWith('/experience/')) api('client-timing', { requestId, timing: timing.result() }).catch(() => {});
    });
  } catch (err) {
    if (e === epoch && r === run) fail(err);
  } finally {
    if (e === epoch) {
      busy = false;
      run = null;
      render();
    }
  }
}
async function voice() {
  if (recording) {
    recorder.stop();
    media?.getTracks().forEach((t) => t.stop());
    recording = false;
    return;
  }
  if (
    !window.isSecureContext ||
    !navigator.mediaDevices ||
    !window.MediaRecorder
  ) {
    error = "当前浏览器无法录音，请使用 HTTPS 或本机支持的浏览器。";
    render();
    return;
  }
  const e = epoch,
    v = ++voiceEpoch;
  error = "";
  try {
    media = await navigator.mediaDevices.getUserMedia({ audio: true });
    if (e !== epoch || v !== voiceEpoch) {
      media.getTracks().forEach((t) => t.stop());
      return;
    }
    const mime = [
      "audio/webm;codecs=opus",
      "audio/mp4",
      "audio/ogg;codecs=opus",
    ].find((t) => MediaRecorder.isTypeSupported(t));
    if (!mime) throw new Error("AUDIO_INVALID");
    recorder = new MediaRecorder(media, { mimeType: mime });
    audioChunks = [];
    recording = true;
    recorder.ondataavailable = (event) => {
      if (e === epoch && v === voiceEpoch && event.data.size)
        audioChunks.push(event.data);
    };
    recorder.onstop = async () => {
      media?.getTracks().forEach((t) => t.stop());
      recording = false;
      if (e !== epoch || v !== voiceEpoch) return;
      const blob = new Blob(audioChunks, { type: mime });
      audioChunks = [];
      status = "语音识别中";
      busy = true;
      render();
      try {
        const res = await fetch("./api/transcribe", {
          method: "POST",
          headers: { ...authHeaders(), "Content-Type": mime },
          body: blob,
        });
        const data = await res.json();
        if (!res.ok)
          throw Object.assign(
            new Error(data.error?.code || "ASR_SERVICE_FAILED"),
            { code: data.error?.code },
          );
        if (e !== epoch || v !== voiceEpoch) return;
        busy = false;
        render();
        document.querySelector("#query").value = data.text;
        document.querySelector("#query").focus();
      } catch (err) {
        if (e === epoch && v === voiceEpoch) {
          busy = false;
          error =
            errors[err.code || err.message] || "语音识别失败，可继续文字输入。";
          render();
        }
      }
    };
    recorder.start();
    render();
    setTimeout(() => {
      if (e === epoch && v === voiceEpoch && recorder.state === "recording")
        recorder.stop();
    }, 30000);
  } catch (err) {
    stopMedia();
    error =
      err.name === "NotAllowedError"
        ? "麦克风权限未开启，可继续文字输入。"
        : "录音不可用，可继续文字输入。";
    render();
  }
}
app.addEventListener("click", async (event) => {
  const task = event.target.closest('[data-task]');
  if (task) return taskControl(task.dataset.task, task.dataset.handle, task.dataset.nonce);
  const subagent = event.target.closest('[data-subagent]');
  if (subagent) { selectedSubagent = subagent.dataset.subagent; render(); return; }
  const b = event.target.closest("button");
  if (!b) return;
  if (b.dataset.query) {
    const text = query(b.dataset.query);
    if (!text) { error = '请选择明确的查询月份。'; render(); return; }
    return chat(text);
  }
  if (b.dataset.source) {
    selected = Number(b.dataset.source);
    if (config.terminal === "DESKTOP_WEB") {
      panelOpen = true;
      render();
    } else openDetails(cards(messages[selected].display, config), "结果与来源");
    return;
  }
  try {
    switch (b.dataset.action) {
      case "home":
        await end();
        break;
      case "workbench":
        await end();
        location.assign('/');
        break;
      case "start":
        await start();
        break;
      case "audit-review":
        if (auditBundle && validateBundle(auditBundle, auditScenario()).ok)
          await chat(buildAuditPrompt(auditBundle), `已上传 ${auditFileName}，请发起智能审核。`);
        break;
      case "end":
        await end();
        break;
      case "continue":
        lifecycle = (await api("activity")).lifecycle;
        render();
        break;
      case "back":
        home = true;
        selected = -1;
        await api("activity");
        render();
        break;
      case "help":
        openDetails(
          "<p>选择已安装的查询任务查看本人获授权信息。首页可继续选择任务，当前会话仍保留；结束服务会清除本次会话。这里只提供浏览器模拟查询，不读取银行卡或 PIN。</p>",
          "服务帮助",
        );
        break;
      case "panel":
        panelOpen = !panelOpen;
        render();
        if (!panelOpen) app.querySelector(`[data-source="${selected}"]`)?.focus({preventScroll: true});
        break;
      case "fullscreen":
        await toggleFullscreen();
        break;
      case "cancel": {
        const result = await api("cancel", { runId: run });
        status =
          result.status === "CANCEL_REQUESTED"
            ? "停止请求已接收，等待当前调用退出"
            : "本轮已结束";
        const n = document.querySelector("#run-phase");
        if (n) n.textContent = status;
        break;
      }
      case "voice":
        await voice();
        break;
      case "cancel-voice":
        busy = false;
        stopMedia();
        render();
    }
  } catch (err) {
    fail(err);
  }
});
modal.addEventListener("click", (e) => {
  const task = e.target.closest('[data-task]');
  if (task) { taskControl(task.dataset.task, task.dataset.handle, task.dataset.nonce); return; }
  if (e.target.closest("[data-close]")) modal.close();
});
app.addEventListener("submit", (event) => {
  if (event.target.id !== "chat-form") return;
  event.preventDefault();
  const input = document.querySelector("#query"),
    text = input.value.trim();
  input.value = "";
  chat(text);
});
app.addEventListener("keydown", (event) => {
  if (
    event.target.id === "query" &&
    event.key === "Enter" &&
    !event.shiftKey &&
    !event.isComposing
  ) {
    event.preventDefault();
    document.querySelector("#chat-form").requestSubmit();
  }
});
let lastTouch = 0;
const userActivity = () => {
  if (sid && Date.now() - lastTouch > 15000) {
    lastTouch = Date.now();
    api("activity").catch(fail);
  }
};
document.addEventListener("pointerdown", userActivity);
document.addEventListener("keydown", userActivity);
document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    stopMedia();
    if (config?.terminal === "ATM_WEB") end();
  } else if (sid)
    api("snapshot")
      .then((r) => {
        messages = r.messages;
        lifecycle = r.lifecycle;
        render();
      })
      .catch(fail);
});
window.addEventListener("offline", () => {
  network = false;
  api("end").catch(() => {});
  clear();
  error = "网络连接已中断，页面数据已清除。";
  render();
});
window.addEventListener("online", () => {
  network = true;
  error = "连接已恢复，请重新开始服务。";
  render();
});
let polling = false;
setInterval(async () => {
  if (!sid || polling) return;
  polling = true;
  const e = epoch;
  try {
    const r = await api("status");
    if (e !== epoch) return;
    lifecycle = r;
    const n = document.querySelector(".time-left");
    if (n) n.textContent = "剩余服务 " + r.remainingSeconds + " 秒";
    if (r.status === "IDLE_WARNING" && !document.querySelector(".idle-warning"))
      render();
  } catch (err) {
    if (e === epoch) fail(err);
  } finally {
    polling = false;
  }
}, 3000);
(async () => {
  try {
    const r = await api("config");
    config = r.configuration;
    caps = r.capabilities;
    taskControls = r.taskControls === true;
    panelOpen = config.sourcePanelExpanded && !compactDesktop.matches;
    if (config.terminal === "ATM_WEB") {
      await api("reset");
    } else if (r.sessionId) {
      sid = r.sessionId;
      const snapshot = await api("snapshot");
      messages = snapshot.messages;
      lifecycle = snapshot.lifecycle;
      await refreshTasks();
    }
    render();
  } catch (err) {
    app.innerHTML =
      '<p class="error-banner">体验入口已失效，请从管理页重新打开 Agent。</p>';
  }
})();
