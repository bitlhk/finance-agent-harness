export const sameAsset = (a,b) => Boolean(a && b && a.id === b.id && a.version === b.version);
export const assetRef = value => ({id:value.id, version:value.version});

export function developerPaths(){
  return `<div class="package-developer-paths"><section><h4>修改专业方法</h4><p>修改 SKILL.md、参考资料与案例；可在页面草稿编辑，无需编译 Provider。</p><p>重新绑定、检查、试跑，再发布业务包。</p></section><section><h4>组合已有能力</h4><p>修改 Solution 的包引用和绑定配置，不复制工具代码。</p><p>校验兼容范围，构建后核对实际安装 Lock。</p></section><section><h4>开发新的银行接口</h4><p>在外部工程修改能力合同、Provider 与测试，需要编译代码。</p><p>按样例运行正反例，提交固定制品准入目录后再使用。</p></section></div><p class="package-action-guide">下载的是固定源码开发包，解压后按 README 和 source-lock.json 操作，不是可直接导入的业务 ZIP。当前页面发布流程面向方法组件包；查询能力不必添加 Skill 才能创建 Agent。</p>`;
}

export function catalogCapabilities(capabilities, entries) {
  const values=new Map(capabilities.map(value=>[value.id,{...value}]));
  for(const entry of entries)for(const [index,capability] of entry.capabilities.entries()){
    const existing=values.get(capability.capabilityId);
    values.set(capability.capabilityId,{...existing,id:capability.capabilityId,
      label:capability.label||(entry.capabilities.length===1?entry.label:existing?.label)||`${entry.label} · 业务操作 ${index+1}`,
      description:capability.description||existing?.description||'业务说明未提供'});
  }
  return [...values.values()];
}

export function componentEntries(components, entries) {
  return entries.filter(entry => components.some(part => (part.packBindings || []).some(binding => sameAsset(binding.pack,entry.pack))));
}

export function bindingCapabilities(bindings, entries) {
  return [...new Set(bindings.flatMap(binding => entries.find(entry => sameAsset(entry.pack,binding.pack))?.capabilities.map(c => c.capabilityId) || []))].sort();
}

export function bindingModules(bindings, directory) {
  return directory.modules.filter(module => directory.components.some(part => module.componentIds.includes(part.id) &&
    (part.packBindings || []).some(ref => bindings.some(binding => sameAsset(binding.pack,ref.pack))))).map(module => module.id);
}

export function packChoices(parts, entries, bindings, {esc, capabilities=[], moduleId}) {
  return componentEntries(parts,entries).map(entry => {
    const binding=bindings.find(value=>sameAsset(value.pack,entry.pack));
    const related=parts.filter(part=>(part.packBindings||[]).some(value=>sameAsset(value.pack,entry.pack)));
    const descriptions=[entry.description,...related.map(part=>part.description),
      ...entry.capabilities.map(capability=>capability.description||capabilities.find(value=>value.id===capability.capabilityId)?.description)];
    const description=descriptions.find(value=>typeof value==='string'&&/[\u4e00-\u9fff]/.test(value)&&value.trim()!==entry.label?.trim())||'业务说明未提供';
    return `<section class="pack-choice" data-pack-choice="${esc(entry.pack.id)}">
      <label class="pack-choice-heading"><input type="checkbox" data-pack-id="${esc(entry.pack.id)}" data-pack-version="${esc(entry.pack.version)}" data-pack-module="${esc(moduleId)}" ${binding?'checked':''}><span><strong>${esc(entry.label)}</strong>${description?`<small>${esc(description.trim())}</small>`:''}</span></label>
      <button type="button" class="text-button pack-detail-button" data-pack-detail="${esc(entry.pack.id)}" data-pack-version="${esc(entry.pack.version)}" data-pack-module="${esc(moduleId)}" aria-label="${esc(entry.label)} 查看详情">查看详情</button>
    </section>`;
  }).join('') || '<p class="method-muted">暂无已接入能力包</p>';
}

export function businessAssetsView({directory, capabilities, selected, query, domain, methods=[], esc, icon, contentsHtml=null}) {
  const command=(action,label,id='')=>`<button class="${action==='use-business'?'primary-button':'secondary-button'}" data-method-action="${action}" data-component="${esc(id)}">${label}</button>`;
  const part=directory?.components.find(p=>p.id===selected);
  if(part){
    const ready=part.status==='AVAILABLE';
    const customizable=ready&&part.methodCustomizationSupported===true;
    const related=methods.filter(item=>item.componentId===part.id&&item.status!=='ARCHIVED');
    if(contentsHtml!==null)return `<div class="asset-detail-back">${command('business-home',icon('arrow-left')+'业务资产')}</div><div class="page-title-row asset-detail-header"><div><h1>${esc(part.label)}</h1><p>${esc(part.description)}</p></div><div class="method-actions">${ready?command('use-business','用于创建 Agent',part.id):''}${customizable?command('customize-business','定制方法',part.id):''}</div></div>${contentsHtml}<details><summary>关联的私有 SOP · ${related.length}</summary>${related.map(item=>`<p><button class="text-button" data-method-action="open" data-asset="${esc(item.assetId)}">${esc(item.name)}</button></p>`).join('')||'<p>暂无关联方法</p>'}</details>`;
    return `<div class="page-title-row"><div>${command('business-home',icon('arrow-left')+'业务资产')}<h1>${esc(part.label)}</h1></div>
      <div class="method-actions">${ready?command('use-business','用于创建 Agent',part.id):'<span>依赖尚未就绪</span>'}${customizable?command('customize-business','定制业务方法',part.id):''}</div></div>
      <div class="business-asset-detail">
      <section><h2>业务概览</h2><p>${esc(part.description)}</p><p>${esc(part.scope)}</p>
      ${part.methodGuide?.groupNote?`<span class="business-tag">${esc(part.methodGuide.groupNote)}</span>`:''}</section>
      <section><h2>业务方法</h2>
      ${methodGuideView(part.methodGuide,esc)}
      <div class="method-actions">${customizable?command('read-original','查看完整 SOP',part.id)+command('customize-business','定制此方法',part.id)+`<label class="secondary-button method-upload">导入已有 SOP<input type="file" data-method-import data-component="${esc(part.id)}" accept=".zip,.md" hidden></label>`:''}</div>
      <p>${customizable?'定制生成私有副本，内置方法保持不变。':'此业务暂不支持私有 SOP 定制。'}</p>
      <h3>我的 SOP</h3>${related.map(item=>`<p><button class="text-button" data-method-action="open" data-asset="${esc(item.assetId)}">${esc(item.name)}</button> · ${item.versions.map(v=>'v'+esc(v.version)).join(' · ')||'草稿'}</p>`).join('')||'<p>暂无关联方法</p>'}</section>
      <section><h2>工具与依赖</h2><ul>${part.capabilityIds.map(id=>`<li>${esc(capabilities.find(c=>c.id===id)?.label||id)}</li>`).join('')}</ul>${plannedCapabilities([part],esc)}
      <details><summary>技术依赖与版本</summary><p>${part.methodKind==='DOMAIN_PACK'?'已声明专业方法包':'宿主内置方法 · 无独立 Domain Pack'}</p><dl class="source-summary"><dt>能力包</dt><dd>${part.capabilityPackRefs.map(r=>`${esc(r.id)} @ ${esc(r.version)}`).join('<br>')}</dd>
      <dt>数据适配</dt><dd>${part.providerRefs.map(r=>`${esc(r.id)} @ ${esc(r.version)}`).join('<br>')}</dd><dt>角色与用途</dt><dd>${esc(part.profileRef?.id||'未声明')} · 不授予权限</dd></dl>
      <pre>${esc(JSON.stringify({packages:part.capabilityPackRefs,methods:part.domainPackRefs,
      providers:part.providerRefs,bindings:part.capabilityBindings,tools:part.tools},null,2))}</pre></details></section>
      <section><h2>验证与版本</h2><p>${ready?'目录与依赖已核验':'依赖未通过检查'} · 当前环境需构建预检</p>
      <p>实际安装：创建 Agent 后核对</p><p>方法受控检查：Java；Python 生成验证单列</p>
      <p>模型专业质量、真实授权：尚需独立验证</p>
      <details><summary>目录引用</summary><pre>${esc(JSON.stringify({revision:directory.revision,catalog:part.catalogBinding},null,2))}</pre></details></section></div>`;
  }
  const values=(directory?.components||[]).filter(p=>(!domain||p.domain===domain)&&
    [p.label,p.description,...p.capabilityIds.map(id=>capabilities.find(c=>c.id===id)?.label||id)].join(' ').toLowerCase().includes(query.toLowerCase()));
  return `<div class="page-title-row"><div><h1>业务资产</h1><p>管理可复用的业务组件包、专业方法和已准入依赖</p></div><div class="method-actions"><label class="secondary-button method-upload">${icon('package-open')}导入组件包<input type="file" data-package-import accept=".zip" hidden></label><label class="secondary-button method-upload">${icon('upload')}导入 Skill<input type="file" data-method-import accept=".zip,.md" hidden></label>${command('private-methods','我的草稿与版本')}</div></div>
    <div class="package-developer-entry">${command('download-developer-kit',icon('download')+'下载开发样例')} ${command('copy-wealth','从方法样例开始')}<a class="text-button" href="/developer-guide/" target="_blank" rel="noopener noreferrer">开发与准入规范</a></div>
    <div class="business-asset-filters"><label>搜索业务<input data-business-query value="${esc(query)}" placeholder="名称或业务能力"></label>
    <label>领域<select data-business-domain><option value="">全部领域</option>${(directory?.domains||[]).map(d=>`<option value="${esc(d.id)}" ${domain===d.id?'selected':''}>${esc(d.label)}</option>`).join('')}</select></label></div>
    <div class="business-asset-grid">${values.map(p=>`<article class="business-asset-card"><span class="business-asset-icon">${icon(p.icon||'blocks')}</span>
      <h2>${esc(p.label)}</h2><p>${esc(p.description)}</p>
      <ul>${p.capabilityIds.map(id=>`<li>${esc(capabilities.find(c=>c.id===id)?.label||id)}</li>`).join('')}</ul>
      <small>${p.status==='AVAILABLE'?'已登记 · 构建前检查环境':'依赖未就绪'} · v${esc(p.version)}</small>
      ${command('business-detail','查看业务资产',p.id)}</article>`).join('')||'<p>没有匹配业务</p>'}</div>`;
}

export function plannedCapabilities(parts,esc){
  const items=new Map(parts.flatMap(part=>part.plannedCapabilities||[]).map(item=>[item.id,item]));
  return [...items.values()].map(item=>`<label class="capability-choice"><input type="checkbox" disabled><div><strong>${esc(item.label)}</strong><small>待接入 · 不会安装</small></div></label>`).join('');
}

export function methodGuideView(guide,esc){
  if(!guide)return '<p>自定义方法，请核对正文与验证案例。</p>';
  return `<div class="method-reading-guide"><p>以下为内置方法说明；实际执行以发布的 SOP 为准。</p>
    ${guide.status!=='SOURCE_MATCHED'?'<p>来源已变化，说明待复核。</p>':''}<ol>${guide.steps.map(step=>`<li>${esc(step)}</li>`).join('')}</ol>
    <details><summary>说明依据与版本</summary><p>阅读说明 v${esc(guide.version)} · ${guide.status==='SOURCE_MATCHED'?'已核对源文件':'待复核'}；不是当前草稿摘要或可执行译文。</p><pre>${esc(JSON.stringify(guide.sources,null,2))}</pre></details></div>`;
}
