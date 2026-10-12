import {businessAssetsView,methodGuideView,developerPaths} from './business-assets.js';
import {visibleMethods,methodListFilters} from './method-list.js';
import {assetContents} from './asset-contents.js';
export function methodAssets({api, esc, icon, markdown, rerender, models, capabilities=()=>[], directory=()=>null, useBusiness=()=>{}, choose, refreshChoices}) {
  const s = {items: [], editor: null, creation: null, showArchived:false, dirty: false, busy: false, error: '', trial: null, preview: null, model: '', query: '', version: '1.0.0', generation:0, bindingConfirmed:false, packagePlan:null};
  Object.assign(s,{privateView:false,business:null,search:'',domain:'',step:'edit',mode:'read',original:null,filter:'ALL',analysis:null});
  const contents=assetContents({api,esc,icon,rerender,actions:assetActions});
  const loadComponent=id=>contents.open('/api/v1/assets/components/'+encodeURIComponent(id)+'/'+encodeURIComponent(directory().revision)+'/contents');
  const status = {DRAFT:'草稿', ARCHIVED:'已归档', PUBLISHED:'已发布', UNPUBLISHED_CHANGES:'有未发布修改', QUEUED:'排队中', RUNNING:'执行中', COMPLETED:'已完成', FAILED:'失败', INTERRUPTED:'已中断'};
  const errors = {ASSET_REQUIRES_ARCHIVE:'该方法已有引用、版本或验证记录，请使用归档。',
    ASSET_BASE_CHANGED:'基础包、方法来源或依赖范围已变化，请从业务目录重新定制并验证。',
    DIRECTORY_CHANGED:'业务目录已更新，请刷新目录后重新定制。',
    ASSET_BASE_UNAVAILABLE:'基础资产尚未就绪，无法定制。',
    ASSET_TRIAL_ACTIVE:'验证仍在运行，结束后再归档或删除。',ASSET_ARCHIVED:'该方法已归档，请先恢复。',
    ASSET_REVISION_CONFLICT:'资产已发生变化，请重新打开后操作。',
    BUSINESS_PACKAGE_BINDING_CONFIRMATION_REQUIRED:'请先核对并确认工具绑定。',
    ASSET_PACKAGE_DEPENDENCIES_UNAVAILABLE:'目标环境缺少包所需的能力、版本或 Runtime 支持。',
    BUSINESS_PACKAGE_DIGEST_MISMATCH:'组件包内容与摘要不一致，未导入。',
    BUSINESS_PACKAGE_ARCHIVE_INVALID:'组件包结构不安全或无法读取，未导入。'};
  const names = () => Object.fromEntries(capabilities().map(c=>[c.id,c.label]));
  const issueHelp={ASSET_CASE_REQUIRED:['cases','补充至少一个验证案例'],ASSET_CAPABILITY_UNSUPPORTED:['dependencies','选择已准入且支持的方法能力'],ASSET_INSTRUCTIONS_REQUIRED:['content','补充可执行的专业方法说明'],ASSET_REFERENCE_MISSING:['resources','导入缺少的参考资料'],ASSET_REFERENCE_EMPTY:['resources','补充参考资料正文'],ASSET_REFERENCE_NOT_SELECTED:['resources','核对并选择要装入方法的资料'],ASSET_PATH_INVALID:['resources','检查参考资料路径'],ASSET_SCRIPT_EXECUTION_UNSUPPORTED:['content','脚本不能在上传后执行，请按外部 Provider 工程接入']};
  const issueView=i=>{const [location,help]=issueHelp[i.code]||['dependencies','核对声明与目标环境支持范围'];return `<p class="method-issue"><button type="button" class="text-button" data-method-locate="${location}">${esc(help)}${i.resource?'：'+esc(i.resource):''}</button><small>${esc(i.code)}</small></p>`;};
  const cmd = (action, label, primary=false, disabled=false) => `<button type="button" data-method-action="${action}" class="${primary?'primary-button':'secondary-button'}" ${disabled||s.busy?'disabled':''}>${label}</button>`;
  function assetActions(value){
    const button=(action,label,extra='',primary=false)=>`<button type="button" class="${primary?'primary-button':'secondary-button'}" data-method-action="${action}" ${extra} ${s.busy?'disabled':''}>${label}</button>`;
    if(value.kind==='PUBLISHED_PACKAGE'){
      const ref=`data-version="${esc(value.version)}" data-digest="${esc(value.identity.releaseDigest)}"`;
      return `<div class="package-actions">${button('export','下载业务包',ref)}${button('choose','使用此版本创建 Agent',ref,true)}${button('fork-version','基于此版本创建草稿',ref)}</div><p class="package-action-guide">新草稿从该版本 ZIP 导入，不读取当前编辑中的方法。来源记录保留，验证和发布须重新执行。</p>`;
    }
    const part=directory()?.components.find(p=>p.id===s.business);
    return `<div class="package-actions">${part?.methodCustomizationSupported&&part.status==='AVAILABLE'?button('customize-business','定制方法',`data-component="${esc(part.id)}"`):''}${part?.status==='AVAILABLE'?button('use-business','用于创建 Agent',`data-component="${esc(part.id)}"`,true):'<span role="status">目录依赖尚未就绪，请先核对引用。</span>'}${button('download-developer-kit',icon('download')+'下载开发样例')}</div>${developerPaths()}`;
  }
  const field = (key, label, value, textarea=false) => `<label class="method-field"><span>${label}</span>${textarea?`<textarea data-method-field="${key}" rows="8">${esc(value)}</textarea>`:`<input data-method-field="${key}" value="${esc(value)}" maxlength="100">`}</label>`;
  const text = value => {if(typeof value==='string')return value;for(const key of ['output','answer','content','response','result'])if(value?.[key])return text(value[key]);return JSON.stringify(value,null,2);};
  const analysis = () => !s.analysis?'':`<details class="skill-analysis" open><summary>Skill 声明与引用检查 · 未自动绑定</summary>
    ${(s.analysis.dependencies||[]).map(item=>`<p><strong>${esc(item.toolName)}</strong> · ${esc(item.status==='MATCHED'?'匹配 '+item.capabilityId:'存在多个候选，需确认')}<small>${(item.locations||[]).map(p=>`${esc(p.file)}:${p.line}`).join(' · ')}</small></p>`).join('')}
    ${(s.analysis.unknownTools||[]).map(item=>`<p class="method-issue"><strong>${esc(item.toolName)}</strong> · 待接入，不会因为同名而获得执行能力<small>${(item.locations||[]).map(p=>`${esc(p.file)}:${p.line}`).join(' · ')}</small></p>`).join('')}
    ${(s.analysis.uncertainties||[]).map(item=>`<p class="method-muted">${esc(item)}</p>`).join('')}
    <p class="method-muted">专业质量：未评测。可在 Finance Skill Eval 中迁移、评测和优化，再导回新版本。</p></details>`;
  const packagePlan = () => !s.packagePlan?'':`<section class="package-plan"><h3>本次将发布的组件边界</h3>
    <p class="package-flow"><span>原始 Skill</span><b>→</b><span>工具绑定</span><b>→</b><span>执行要求</span><b>→</b><span>验证摘要</span></p>
    ${(s.packagePlan.bindings||[]).map(item=>`<article><strong>${esc(names()[item.capabilityId]||item.capabilityId)}</strong><dl>
      <dt>工具</dt><dd>${esc(item.toolName)}</dd><dt>能力包</dt><dd>${esc(item.capabilityPack.id)} @ ${esc(item.capabilityPack.version)}</dd>
      <dt>执行类型</dt><dd>${esc(item.operation)}</dd><dt>协议</dt><dd>${esc(item.protocol)}</dd>
      <dt>输入合同</dt><dd>${esc(item.inputSchemaRef)}</dd><dt>输出合同</dt><dd>${esc(item.outputSchemaRef)}</dd></dl></article>`).join('')}
    <dl class="package-requirements"><dt>已验证 Runtime</dt><dd>${esc((s.packagePlan.requirements.runtimeFamilies||[]).join(', '))} · ${esc((s.packagePlan.requirements.runtimeLanguages||[]).join(', '))}</dd>
      <dt>执行要求</dt><dd>${esc((s.packagePlan.requirements.execution||[]).join(', '))}</dd><dt>运行时所需权限</dt><dd>${esc((s.packagePlan.requirements.requiredScopes||[]).join(', ')||'无额外声明')}</dd>
      <dt>公共观测</dt><dd>${esc((s.packagePlan.requirements.observation||[]).join(', '))}</dd>
      <dt>验证范围</dt><dd>受控运行 ${s.packagePlan.evidence.runtimeVerified?'已核验':'未核验'} · 专业质量 ${esc(s.packagePlan.evidence.professionalQuality)}</dd></dl></section>`;
  const path = () => '/api/v1/assets/' + s.editor.assetId;
  async function refresh() {s.items = (await api('/api/v1/assets')).assets; refreshChoices(s.items.filter(a=>a.status!=='ARCHIVED'));}
  function newDraft(data, creation={path:'/api/v1/assets',body:{}}, validation={status:'NOT_RUN',issues:[]}, analysis=null) {
    s.editor={data:{kind:'METHOD',maintainer:'',resources:{},requiredResources:[],capabilityIds:[],cases:[],...data},
      revision:0,versions:[],validation};
    s.creation=creation;s.dirty=true;s.trial=null;s.preview=null;s.version='1.0.0';s.query=data.cases?.[0]?.query||'';s.bindingConfirmed=false;s.packagePlan=null;s.analysis=analysis;
    s.step='edit';s.mode='read';s.bindingConfirmed=false;
    s.model=models().find(m=>m.bindingStatus==='CONFIGURED_UNTESTED')?.modelRef||'';
  }
  async function open(id, generation=s.generation) {
    const editor=await api('/api/v1/assets/'+id), trial=await api('/api/v1/assets/'+id+'/trial'), preview=await api('/api/v1/assets/'+id+'/preview');
    if(generation!==s.generation)return;
    s.editor=editor;s.creation=null;s.trial=trial;s.preview=preview;s.dirty=false;s.error='';s.packagePlan=null;s.bindingConfirmed=false;
    s.step='edit';s.mode='read';
    const versions = s.editor.versions.map(v=>v.version.split('.').map(Number)).sort((a,b)=>a[0]-b[0]||a[1]-b[1]||a[2]-b[2]);
    const last = versions.at(-1); s.version = last ? `${last[0]}.${last[1]}.${last[2]+1}` : '1.0.0';
    s.query = s.editor.data.cases[0]?.query || '';
    s.model = models().find(m=>m.bindingStatus==='CONFIGURED_UNTESTED')?.modelRef || '';
    rerender();
  }
  async function save() {
    if (s.dirty) {
      const generation=s.generation, id=s.editor.assetId;
      const submitted=structuredClone(s.editor.data);
      const updated=s.creation?await api(s.creation.path,{...s.creation.body,data:submitted}):
        await api(path()+'/save', {revision:s.editor.revision, data:submitted});
      if(generation!==s.generation||s.editor?.assetId!==id)throw Error('编辑对象已变化');
      s.creation=null;
      if(JSON.stringify(s.editor.data)!==JSON.stringify(submitted)){
        s.editor={...updated,data:s.editor.data};s.dirty=true;throw Error('保存期间还有新修改，请再次保存');
      }
      s.editor=updated;
      s.dirty = false; await refresh();
    }
  }
  async function poll(kind, id, generation) {
    if (s.editor?.assetId !== id||generation!==s.generation) return;
    try {
      const result=await api('/api/v1/assets/'+id+'/'+kind);
      if(s.editor?.assetId!==id||generation!==s.generation)return;
      s[kind]=result;
      if (['QUEUED','RUNNING'].includes(s[kind].status)) setTimeout(()=>poll(kind,id,generation),1500);
      else if (kind==='trial') {
        const actual = await api('/api/v1/assets/'+id);
        if (!s.dirty && s.editor?.assetId===id&&generation===s.generation) s.editor=actual;
      }
      if (s.editor?.assetId===id&&generation===s.generation) rerender();
    } catch(e) {if(s.editor?.assetId===id&&generation===s.generation){s.error=e.message;rerender();}}
  }
  function preview() {
    if (!s.preview || s.preview.status==='NOT_RUN') return '';
    const value = s.preview.preview;
    return `<div class="method-report"><h3>模型预览 · ${esc(status[s.preview.status]||s.preview.status)}</h3>${s.preview.error?`<p role="alert">${esc(s.preview.error)}</p>`:''}${value?`<p>草稿 r${value.revision} · ${esc(value.modelRef)} · 合成银行数据 · 待人工评价${s.dirty||value.revision!==s.editor.revision?' · 已有新修改':''}</p><div class="method-comparison">${[['baseline','默认方法'],['candidate','当前方法']].map(([k,label])=>{const c=value.comparison[k].cases[0];return `<section><h4>${label}</h4><div class="markdown-body">${markdown(text(c.response))}</div><details><summary>事实与来源</summary><pre>${esc(JSON.stringify({facts:c.facts,sourceCalls:c.sourceCalls},null,2))}</pre></details><details><summary>安装与执行证据</summary><pre>${esc(JSON.stringify({runtimeLock:c.runtimeLock,modelRequests:c.modelRequests,toolCount:c.toolCount},null,2))}</pre></details></section>`;}).join('')}</div>`:''}</div>`;
  }
  function editor() {
    const d=s.editor.data, versions=s.editor.versions, valid=s.editor.validation.status==='VALID';
    if(s.editor.status==='ARCHIVED')return `<div class="page-title-row"><div><button class="text-button" data-method-action="back">${icon('arrow-left')}业务资产</button><h1>${esc(d.name)}</h1><p>已归档 · ${versions.length} 个保留版本</p></div>${cmd('restore',icon('rotate-ccw')+'恢复资产')}</div><pre>${esc(d.content)}</pre>`;
    const isNew=!s.editor.assetId;
    const trial=s.trial?.trial, current=trial?.revision===s.editor.revision && !s.dirty;
    const pending=isNew||['QUEUED','RUNNING'].includes(s.trial?.status)||['QUEUED','RUNNING'].includes(s.preview?.status);
    const guide=directory()?.components.find(p=>p.id===s.editor.source?.binding?.componentId)?.methodGuide;
    return `<div class="page-title-row"><div><button class="text-button" data-method-action="back">${icon('arrow-left')}业务资产</button><h1>${esc(d.name)}</h1></div><div class="method-actions"><span>${s.dirty?'未保存':'草稿 r'+s.editor.revision}</span>${!isNew?cmd('archive',icon('archive')+'归档',false,pending):''}${!isNew&&!versions.length?cmd('delete',icon('trash-2')+'删除',false,pending):''}${cmd('save',icon('check')+'保存草稿',true)}</div></div>
      <nav class="method-steps" aria-label="方法定制阶段">${[['edit','1 编辑方法'],['check','2 检查与试用'],['publish','3 发布使用']].map(([id,label])=>`<button data-method-action="step-${id}" aria-current="${s.step===id?'step':'false'}">${label}</button>`).join('')}</nav>
      <p class="method-step-status" role="status">${s.dirty?'草稿未保存；修改后必须检查和试跑，旧结果不能用于本次发布。':current&&trial?.report.status==='PASS'?'当前草稿执行检查已通过，可以发布。':'草稿已保存；发布前仍需完成当前版本执行检查。'}</p>
      <div class="method-action-progress"><span>保存 ${s.dirty?'· 待完成':'· 已保存'}</span><span>→ 依赖 ${!s.dirty&&valid?'· 结构通过':'· 待检查'}</span><span>→ 试跑 ${current&&trial?.report.status==='PASS'?'· 当前版本通过':'· 待验证'}</span><span>→ 发布 ${versions.length?'· '+versions.length+' 个保留版本':'· 未发布'}</span><small>草稿状态不代表已发布版本状态。试跑是受控执行检查，专业回答质量需另行确认。</small></div>
      <div class="method-editor method-step-layout" data-stage="${s.step}"><section class="method-main" ${s.step==='publish'?'hidden':''}>
      <div ${s.step!=='edit'?'hidden':''}>
      <div class="method-field-grid">${field('name','名称',d.name)}${field('maintainer','维护人',d.maintainer)}</div>
      <details><summary>基础版本与派生来源 · ${s.editor.source?.kind==='BUSINESS_DERIVED'?'已绑定':'未记录，可从业务目录重新定制'}</summary><pre>${esc(JSON.stringify(s.editor.source||{},null,2))}</pre></details>
      <div class="method-mode" role="group" aria-label="正文模式">${['read','edit'].map(id=>`<button data-method-action="mode-${id}" aria-pressed="${s.mode===id}">${id==='read'?'阅读':'编辑原文'}</button>`).join('')}</div>
      <p class="method-muted">可修改步骤、解释、输出约定与文本资料；工具实现和强制身份权限检查不可修改。</p>
      ${s.mode==='read'?`${methodGuideView(guide,esc)}<details><summary>当前草稿完整原文</summary><div class="markdown-body">${markdown(d.content.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/,''))}</div><pre>${esc(d.content)}</pre></details>`:field('content','专业方法原文 · SKILL.md',d.content,true)}
      <div class="section-head"><h2>参考资料</h2><label class="secondary-button method-upload">${icon('plus')}添加文本<input type="file" data-method-resource accept=".md,.txt" hidden></label></div>
      ${Object.entries(d.resources).map(([name,body])=>`<section class="method-resource"><header><code>${esc(name)}</code><label><input type="checkbox" data-method-required="${esc(name)}" ${d.requiredResources.includes(name)?'checked':''}>装入方法</label><button class="icon-button" data-method-action="remove-resource" data-resource="${esc(name)}" aria-label="删除 ${esc(name)}" title="删除资料">${icon('trash-2')}</button></header><textarea aria-label="${esc(name)}" data-method-resource-text="${esc(name)}" rows="4">${esc(body)}</textarea></section>`).join('')||'<p class="method-muted">暂无参考资料</p>'}
      <div class="method-next">${cmd('continue-check',s.dirty?'保存草稿并继续':'下一步：检查与试用',true)}</div></div>
      <div ${s.step!=='check'?'hidden':''}><div class="section-head"><h2>验证案例</h2>${cmd('add-case',icon('plus')+'添加案例',false,d.cases.length>=8)}</div>
      ${d.cases.map((c,i)=>`<section class="method-case"><header><strong>案例 ${i+1}</strong><button class="icon-button" data-method-action="remove-case" data-case="${i}" title="删除案例" aria-label="删除案例 ${i+1}">${icon('trash-2')}</button></header><label class="method-field"><span>名称</span><input data-method-case="${i}" data-key="name" value="${esc(c.name)}"></label><label class="method-field"><span>用户问题</span><textarea data-method-case="${i}" data-key="query" rows="2">${esc(c.query)}</textarea></label><label class="method-field"><span>预期要点（逗号分隔，仅用于核验）</span><input data-method-case="${i}" data-key="expectedTerms" value="${esc(c.expectedTerms.join(', '))}"></label></section>`).join('')}
      <section class="method-preview"><h2>真实模型预览（可选）</h2><p class="method-muted">默认方法与当前方法 · 合成银行数据 · 内容发送至所选模型</p><div class="method-field-grid"><label class="method-field"><span>已配置模型</span><select data-method-meta="model"><option value="">选择模型</option>${models().filter(m=>m.bindingStatus==='CONFIGURED_UNTESTED').map(m=>`<option value="${esc(m.modelRef)}" ${s.model===m.modelRef?'selected':''}>${esc(m.displayName)}</option>`).join('')}</select></label><label class="method-field"><span>预览问题</span><input data-method-meta="query" value="${esc(s.query)}" maxlength="1000"></label></div>${cmd('preview','运行模型预览',false,!s.model||pending)}${!s.model?'<p class="method-muted">请先配置并选择模型；不影响保存草稿和 Java 受控检查。<button class="text-button" data-nav="connections">查看连接管理</button></p>':isNew?'<p class="method-muted">请先保存草稿。</p>':''}${preview()}</section></div>
      </section><aside class="method-side"><section ${s.step!=='edit'?'hidden':''}><h2>依赖能力</h2>${analysis()}${Object.entries(names()).map(([id,label])=>`<label class="method-cap"><input type="checkbox" data-method-cap="${esc(id)}" ${s.editor.source?.kind==='BUSINESS_DERIVED'?'disabled':''} ${d.capabilityIds.includes(id)?'checked':''}>${esc(label)}</label>`).join('')}<p class="method-muted">分析只提供建议；选择能力后仍需核对绑定。身份与实授权限检查始终启用。</p></section>
      <section ${s.step!=='check'?'hidden':''}><h2>装配与执行检查</h2><div class="method-actions">${cmd('validate','校验依赖',false,isNew)}${cmd('trial','运行受控检查',true,pending)}</div>${isNew?'<p>请先保存草稿，再运行检查；无需配置真实模型。</p>':pending?'<p>检查正在执行，请等待当前任务结束。</p>':''}<p class="method-muted">真实 Java Runtime · 受控银行与脚本模型 · 非专业质量评价</p>${s.dirty?'<p>修改尚未校验；保存后重新检查，不能沿用旧结果。</p>':`<p class="${valid?'state-success':'state-warning'}">${valid?'结构校验通过':'结构校验未通过'}</p>`}${s.editor.validation.issues.map(issueView).join('')}${s.trial?.status&&s.trial.status!=='NOT_RUN'?`<p>${esc(status[s.trial.status]||s.trial.status)}${trial&&!current?' · 非当前草稿':''}</p><p class="method-issue">${esc(s.trial.error||'')}</p>${trial?`<details data-method-trial-result><summary>查看结果：实际安装与执行记录</summary><p>草稿 r${trial.revision} · ${current?'对应当前已保存内容':'旧验证，不能用于发布当前修改'}</p><pre>${esc(JSON.stringify(trial.report,null,2))}</pre></details>`:''}`:''}${cmd('continue-publish',s.dirty?'保存草稿并继续':'下一步：发布使用')}</section>
      <section ${s.step!=='publish'?'hidden':''}><h2>发布业务组件包</h2><p>版本发布后内容固定；已有 Agent 不自动更新。</p>${packagePlan()}<label class="method-field"><span>版本号</span><input data-method-meta="version" value="${esc(s.version)}"></label><label class="method-cap binding-confirm"><input type="checkbox" data-method-binding-confirm ${s.bindingConfirmed?'checked':''}>我已核对上方工具含义、输入输出合同、版本和执行协议</label><p class="method-muted">能力声明不会授予客户权限；运行时仍检查可信身份与实授权限。</p>${cmd('publish','发布不可变版本',true,!current||trial?.report.status!=='PASS'||pending||!s.bindingConfirmed||!s.packagePlan)}${!current||trial?.report.status!=='PASS'?'<p class="method-muted">先保存当前草稿，并完成该版本的执行检查。</p>':''}<div class="method-versions">${versions.map(v=>`<div><span>v${esc(v.version)}<small>草稿 r${v.revision}</small></span><span>${v.businessPackageReady?`<button class="text-button" data-method-action="export" data-version="${esc(v.version)}" data-digest="${esc(v.releaseDigest)}">下载组件包</button>`:'<small>旧方法版本 · 未确认组件绑定</small>'}<button class="text-button" data-method-action="choose" data-version="${esc(v.version)}">使用此包创建 Agent</button></span></div>`).join('')||'<p class="method-muted">尚未发布</p>'}</div></section></aside></div>`;
  }
  function html() {
    if(s.packageView)return `<div class="method-workspace"><div class="asset-detail-back">${cmd('package-contents-close',icon('arrow-left')+'返回方法')}</div><div class="page-title-row asset-detail-header"><div><h1>${esc(contents.value?.name||'发布包内容')}</h1><p>固定发布版本 · 修改须创建新草稿</p></div></div>${s.error?`<p role="alert">${esc(s.error)}</p>`:''}${contents.html()}</div>`;
    if(s.original)return `<div class="method-workspace"><div class="page-title-row"><h1>完整 SOP · ${esc(s.original.name)}</h1>${cmd('close-original','返回业务详情')}</div><p>当前基础方法原文 · 只读查看，不会创建草稿。</p><pre class="method-original">${esc(s.original.data.content)}</pre><details><summary>版本与来源</summary><pre>${esc(JSON.stringify(s.original.source,null,2))}</pre></details></div>`;
    if(!s.editor&&!s.privateView)return `<div class="method-workspace">${s.error?`<p role="alert">${esc(s.error)}</p>`:''}${businessAssetsView({directory:directory(),capabilities:capabilities(),selected:s.business,query:s.search,domain:s.domain,methods:s.items,esc,icon,contentsHtml:s.business?contents.html():null})}</div>`;
    if(s.editor)return `<div class="method-workspace">${s.error?`<div role="alert">${esc(s.error)}</div>`:''}${editor()}<section class="asset-published-links"><h2>查看已发布包的组成</h2>${s.editor.versions.filter(v=>v.businessPackageReady).map(v=>`<button class="secondary-button" data-method-action="package-contents" data-version="${esc(v.version)}" data-digest="${esc(v.releaseDigest)}">${icon('book-open')}查看 v${esc(v.version)} 的文件与依赖</button>`).join('')||'<p>发布组件包后，可在这里查看该版本的真实文件。</p>'}</section></div>`;
    return `<div class="method-workspace">${s.error?`<div class="live-error" role="alert">${esc(s.error)}</div>`:''}${s.editor?editor():`<div class="page-title-row"><div>${cmd('business-home',icon('arrow-left')+'业务目录')}<h1>我的 SOP</h1><p>查看导入、保存和发布的方法</p></div><div class="method-actions"><label class="secondary-button method-upload">${icon('upload')}导入 SOP<input type="file" data-method-import accept=".zip,.md" hidden></label>${cmd('create',icon('plus')+'新建方法',true)}</div></div><div class="method-presets"><span>从示例新建</span>${cmd('copy-wealth','财富核验')}${cmd('copy-balance','账户核验')}</div>${methodListFilters(s.filter,s.showArchived)}<div class="method-table"><div class="method-table-head"><span>专业方法 / SOP</span><span>状态</span><span>版本</span><span>维护人</span></div>${visibleMethods(s.items,s.filter,s.showArchived).map(a=>`<button class="method-row" data-method-action="open" data-asset="${a.assetId}"><span><strong>${esc(a.name)}</strong><small>${esc(a.assetId)}</small></span><span>${esc(status[a.status]||a.status)}</span><span>${a.versions.map(v=>'v'+esc(v.version)).join(' · ')||'未发布'}</span><span>${esc(a.maintainer)||'未填写'}</span></button>`).join('')||'<div class="empty-state"><h2>暂无专业方法</h2></div>'}</div>`}</div>`;
  }
  async function act(e) {
    if(contents.act(e))return;
    const location=e.target.closest('[data-method-locate]')?.dataset.methodLocate;
    if(location&&s.editor){
      s.step=location==='cases'?'check':'edit';if(location==='content')s.mode='edit';rerender();
      const selector={cases:'[data-method-action="add-case"]',content:'[data-method-field="content"]',dependencies:'[data-method-cap]',resources:'[data-method-resource-text],.method-upload'}[location];
      const target=document.querySelector(selector);target?.scrollIntoView({block:'center'});target?.focus();return;
    }
    const filter=e.target.closest('[data-method-filter]');
    if(filter&&['ALL','DRAFT','PUBLISHED'].includes(filter.dataset.methodFilter)){s.filter=filter.dataset.methodFilter;rerender();return;}
    const b=e.target.closest('[data-method-action]'); if (!b) return;
    if(s.busy)return; s.busy=true; s.error='';
    const action=b.dataset.methodAction;
    const generation=['open','back','create','copy-wealth','copy-balance'].includes(action)?++s.generation:s.generation;
    rerender();
    try {
      const a=b.dataset.methodAction;
      if(a==='download-developer-kit'){
        const result=await api('/api/v1/assets/developer-kit');
        const bytes=Uint8Array.from(atob(result.base64),c=>c.charCodeAt(0));
        const digest='sha256:'+Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
        if(digest!==result.sha256)throw Error('开发样例摘要不一致，已停止下载');
        const url=URL.createObjectURL(new Blob([bytes],{type:'application/zip'}));const link=document.createElement('a');link.href=url;link.download=result.filename;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
      }
      else if(a==='continue-check'||a==='continue-publish'){
        if(s.dirty)await save();
        if(a==='continue-publish'){
          const trial=s.trial?.trial;
          if(!trial||trial.revision!==s.editor.revision||trial.contentDigest!==s.editor.contentDigest||trial.report.status!=='PASS')throw Error('请先完成当前草稿的装配与执行检查');
          s.packagePlan=await api(path()+'/package-plan',{revision:s.editor.revision,trialId:trial.trialId});s.bindingConfirmed=false;
        }
        s.step=a==='continue-check'?'check':'publish';
      }
      else if(a.startsWith('step-'))s.step=a.slice(5);
      else if(a.startsWith('mode-'))s.mode=a.slice(5);
      else if(a==='close-original')s.original=null;
      else if(a==='read-original'){
        const part=directory().components.find(p=>p.id===b.dataset.component);
        const result=await api('/api/v1/assets/customize-preview',{componentId:part.id,directoryRevision:directory().revision,name:part.label});
        s.original={...result,name:part.label};
      }
      else if(a==='business-home'){s.business=null;s.privateView=false;s.packageView=false;contents.clear();}
      else if(a==='business-detail'){const component=b.dataset.component;s.business=component;s.privateView=false;await refresh();if(generation!==s.generation)return;await loadComponent(component);}
      else if(a==='package-contents'){s.packageView=true;await contents.open(path()+'/packages/'+b.dataset.version+'/'+b.dataset.digest+'/contents');}
      else if(a==='verification-version'){
        if(s.dirty&&!confirm('当前编辑有未保存修改。放弃修改并查看已发布验证版本？'))return;
        await open(b.dataset.asset,generation);if(generation!==s.generation)return;
        s.business=null;s.privateView=true;s.packageView=true;
        await contents.open(path()+'/packages/'+encodeURIComponent(b.dataset.version)+'/'+encodeURIComponent(b.dataset.digest)+'/contents');
      }
      else if(a==='package-contents-close'){s.packageView=false;}
      else if(a==='fork-version'){
        if(s.dirty&&!confirm('当前编辑有未保存修改。放弃这些修改，并从所选发布版本创建新草稿？'))return;
        const archive=await api(path()+'/packages/'+b.dataset.version+'/'+b.dataset.digest);
        const body={filename:archive.filename,base64:archive.base64,name:(contents.value?.name||s.editor.data.name)+' · 新草稿'};
        const preview=await api('/api/v1/assets/package-preview',body);
        newDraft(preview.data,{path:'/api/v1/assets/package-import',body},preview.validation);
        s.editor.source=preview.source;s.packageView=false;s.privateView=true;
        if(preview.dependencyStatus?.status!=='ADMISSIBLE')s.error='来源包依赖尚未准入；补齐兼容依赖后再验证，不能沿用来源验证结果。';
      }
      else if(a==='private-methods'){s.privateView=true;s.business=null;await refresh();}
      else if(a==='use-business'){useBusiness(b.dataset.component);}
      else if(a==='customize-business'){
        const part=directory().components.find(p=>p.id===b.dataset.component);
        const body={componentId:part.id,directoryRevision:directory().revision,name:part.label+' · 定制方法'};
        const result=await api('/api/v1/assets/customize-preview',body);
        newDraft(result.data,{path:'/api/v1/assets/customize',body},result.validation);
        s.editor.source=result.source;
      }
      else if(a==='open')await open(b.dataset.asset);
      else if(a==='back'){if(!s.dirty||confirm('放弃未保存的修改？')){s.editor=null;await refresh();}}
      else if(a==='create'||a.startsWith('copy-')) {
        if(a==='create')newDraft({name:'新的专业方法',content:'# 工作方法\n'});
        else {
          const body={name:a==='copy-wealth'?'财富核验方法':'账户核验方法',capabilityId:a==='copy-wealth'?'wealth.facts.describe':'account.balance.query'};
          const result=await api('/api/v1/assets/example-preview',body);
          if(generation===s.generation)newDraft(result.data,{path:'/api/v1/assets/copy-default',body},result.validation);
        }
      } else if(a==='save')await save();
      else if(['archive','delete','restore'].includes(a)){
        const message=a==='delete'?'删除这个未发布的草稿？':'archive'===a?'归档后不再用于新 Agent，已有版本与记录将保留。':'恢复这个资产？';
        if(confirm(message)){await api(path()+'/'+a,{revision:s.editor.revision});s.editor=null;s.creation=null;s.dirty=false;await refresh();}
      }
      else if(a==='validate'){await save();s.editor.validation=await api(path()+'/validate',{revision:s.editor.revision});}
      else if(a==='trial'||a==='preview'){
        await save();const id=s.editor.assetId;
        const result=await api(path()+'/'+a,{revision:s.editor.revision,...(a==='preview'?{modelRef:s.model,query:s.query}:{})});
        if(generation!==s.generation||s.editor?.assetId!==id)return;
        s[a]=result;setTimeout(()=>poll(a,id,generation),1000);
      } else if(a==='publish'){
        const trial=s.trial?.trial;
        if(s.dirty||!trial||trial.revision!==s.editor.revision||trial.contentDigest!==s.editor.contentDigest||trial.report.status!=='PASS')
          throw Error('请先保存当前修改，并重新完成装配与执行检查');
        if(!s.bindingConfirmed)throw Error('请先核对并确认工具绑定');
        await api(path()+'/publish',{revision:s.editor.revision,version:s.version,trialId:s.trial.trial.trialId,bindingConfirmations:[...s.editor.data.capabilityIds].sort()});await refresh();await open(s.editor.assetId);s.step='publish';
      } else if(a==='export'){
        const value=await api(path()+'/packages/'+b.dataset.version+'/'+b.dataset.digest);
        const raw=atob(value.base64), bytes=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);
        const url=URL.createObjectURL(new Blob([bytes],{type:'application/zip'})), link=document.createElement('a');
        link.href=url;link.download=value.filename;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
      } else if(a==='choose'){
        const v=s.editor.versions.find(v=>v.version===b.dataset.version);
        if(v&&choose(v)!==false){s.editor=null;s.business=null;s.privateView=false;s.packageView=false;s.dirty=false;}
      } else if(a==='add-case'){s.editor.data.cases.push({name:'新案例',query:'',expectedTerms:[]});s.dirty=true;}
      else if(a==='remove-case'){s.editor.data.cases.splice(Number(b.dataset.case),1);s.dirty=true;}
      else if(a==='remove-resource'){delete s.editor.data.resources[b.dataset.resource];s.editor.data.requiredResources=s.editor.data.requiredResources.filter(r=>r!==b.dataset.resource);s.dirty=true;}
    } catch(error){if(generation===s.generation)s.error=errors[error.message]||error.message;}finally{if(generation===s.generation){s.busy=false;rerender();}}
  }
  function input(e) {
    const el=e.target, d=s.editor?.data;
    if(el.hasAttribute?.('data-business-query')){s.search=el.value;rerender();const node=document.querySelector('[data-business-query]');node?.focus();node?.setSelectionRange(s.search.length,s.search.length);return;}
    if(el.hasAttribute?.('data-method-binding-confirm')){s.bindingConfirmed=el.checked;rerender();return;}
    if(el.dataset.methodMeta){s[el.dataset.methodMeta]=el.value;return;}
    if(!d)return;
    if(el.dataset.methodField){d[el.dataset.methodField]=el.value;s.dirty=true;s.packagePlan=null;s.bindingConfirmed=false;}
    if(el.dataset.methodResourceText){d.resources[el.dataset.methodResourceText]=el.value;s.dirty=true;}
    if(el.dataset.methodCase!==undefined){const c=d.cases[Number(el.dataset.methodCase)];c[el.dataset.key]=el.dataset.key==='expectedTerms'?el.value.split(',').map(t=>t.trim()).filter(Boolean):el.value;s.dirty=true;}
    if(s.dirty){const publish=globalThis.document?.querySelector('[data-method-action="publish"]');if(publish)publish.disabled=true;}
  }
  async function change(e) {
    const el=e.target, d=s.editor?.data;
    if(el.hasAttribute('data-business-domain')){s.domain=el.value;rerender();return;}
    const importing=el.hasAttribute('data-method-import')||el.hasAttribute('data-package-import');
    const generation=importing?++s.generation:s.generation, id=s.editor?.assetId;
    try {
      if(importing){
        const file=el.files[0];if(!file)return;if(file.size>512*1024)throw Error('文件超过 512 KiB');
        const bytes=new Uint8Array(await file.arrayBuffer());let raw='';for(const n of bytes)raw+=String.fromCharCode(n);
        if(generation!==s.generation)return;
        const body={filename:file.name,base64:btoa(raw),name:file.name.replace(/\.(zip|md)$/i,'')};
        const packageImport=el.hasAttribute('data-package-import');
        const result=await api(packageImport?'/api/v1/assets/package-preview':'/api/v1/assets/import-preview',body);
        if(generation!==s.generation)return;
        if(packageImport){
          newDraft(result.data,{path:'/api/v1/assets/package-import',body},result.validation);s.editor.source=result.source;
          if(result.dependencyStatus?.status!=='ADMISSIBLE')s.error='组件包依赖尚未准入；可保存草稿，补齐依赖后再验证发布。';
        }else if(el.dataset.component){
          const part=directory().components.find(p=>p.id===el.dataset.component);
          const bound={...body,componentId:part.id,directoryRevision:directory().revision};
          const base=await api('/api/v1/assets/customize-import-preview',bound);
          if(generation!==s.generation)return;
          newDraft(base.data,{path:'/api/v1/assets/customize-import',body:bound},base.validation);
          s.editor.source=base.source;
        }else newDraft(result.data,{path:'/api/v1/assets/import',body},result.validation,result.analysis);
      } else if(el.hasAttribute('data-method-archived')){
        s.showArchived=el.checked;
      } else if(el.hasAttribute('data-method-resource')&&d){
        const file=el.files[0];if(!file)return;if(file.size>64*1024)throw Error('单份资料超过 64 KiB');
        const name='references/'+file.name;if(Object.hasOwn(d.resources,name))throw Error('同名资料已存在');
        const content=await file.text();if(generation!==s.generation||s.editor?.assetId!==id)return;
        d.resources[name]=content;d.requiredResources.push(name);s.dirty=true;
      } else if(el.dataset.methodRequired&&d){
        const set=new Set(d.requiredResources);el.checked?set.add(el.dataset.methodRequired):set.delete(el.dataset.methodRequired);d.requiredResources=[...set];s.dirty=true;
      } else if(el.dataset.methodCap&&d){
        const set=new Set(d.capabilityIds);el.checked?set.add(el.dataset.methodCap):set.delete(el.dataset.methodCap);d.capabilityIds=[...set];s.dirty=true;
      } else if(el.dataset.methodMeta){
        s[el.dataset.methodMeta]=el.value;
        // Text blur can precede a button click; retain that button's DOM target.
        if(el.dataset.methodMeta!=='model')return;
      }
      else return;
    }catch(error){s.error=error.message;}rerender();
  }
  return {html,refresh,act,input,change,afterRender:contents.afterRender,showBusiness:id=>{s.business=id;s.privateView=false;s.editor=null;s.original=null;s.packageView=false;loadComponent(id);},leave:()=>{if(s.dirty&&!confirm('放弃未保存的修改？'))return false;s.generation++;s.editor=null;s.original=null;s.packageView=false;s.business=null;s.privateView=false;contents.clear();s.dirty=false;s.busy=false;return true;}};
}
