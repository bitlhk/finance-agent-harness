// Presentation only: the server-resolved component references remain authoritative.
export function capabilityChoice(item, parts, {esc, capabilities, checked, enabled, required}) {
  if(!item.tools.length)return '<p class="method-muted">暂无已接入能力</p>';
  const byId=new Map(capabilities.map(value=>[value.id,value]));
  const title=parts.length===1?parts[0].label:item.label;
  const description=parts.length===1?parts[0].description:item.scope;
  return `<label class="capability-choice"><input type="checkbox" data-module-capability="${esc(item.id)}" ${checked?'checked':''} ${enabled?'':'disabled'}>
    <div><strong>${esc(title)}</strong><small>${esc(description)}</small>${required?'<small>已选专业方法依赖</small>':''}</div></label>
    <ul class="business-capability-list">${item.tools.map(tool=>{
      const value=byId.get(tool.capabilityId);
      return `<li><strong>${esc(value?.label||'能力名称待补充')}</strong><p>${esc(value?.description||'业务说明待补充')}</p></li>`;
    }).join('')}</ul>`;
}

export function capabilityDetails(parts, {esc, runtime, connection, method}) {
  const reference = ref => `<code>${esc(ref.id)}${ref.version?' @ '+esc(ref.version):' · 版本未记录'}</code>`;
  const refs = values => values?.length ? values.map(reference).join('<br>') : '未声明';
  return `<section class="component-details"><h3>组件声明与方法选择</h3>${parts.map(part=>{
    const custom=method&&part.capabilityIds.some(id=>method.capabilityIds.includes(id));
    const scopes=[...new Set((part.tools||[]).flatMap(tool=>tool.requiredScopes||[]))];
    return `<section class="component-detail"><h4>${esc(part.label)}</h4><p>${reference(part)}</p>
      <dl class="source-summary"><dt>业务范围</dt><dd>${esc(part.scope)}</dd>
      <dt>工具声明</dt><dd>${part.tools?.length?part.tools.map(tool=>`<code>${esc(tool.capabilityId)}</code><br>${esc(tool.toolName)} · ${esc(tool.operation)}`).join('<br>'):'未核验到工具'}</dd>
      <dt>Capability Pack</dt><dd>${refs(part.capabilityPackRefs)}</dd>
      <dt>所需权限</dt><dd>${scopes.length?scopes.map(scope=>`<code>${esc(scope)}</code>`).join(' · '):'未声明'} · 声明不等于实授</dd>
      <dt>默认 Domain Pack</dt><dd>${part.domainPackRefs?.length?refs(part.domainPackRefs):'无独立 Domain Pack 声明'}</dd>
      <dt>私有 SOP</dt><dd>${custom?`<code>${esc(method.assetId)} @ ${esc(method.version)}</code><br>${esc(method.name)}`:'未选择覆盖方法'}</dd>
      <dt>Provider</dt><dd>${refs(part.providerRefs)}</dd>
      <dt>Profile</dt><dd>${part.profileRef?reference(part.profileRef):'未声明'} · 不授予权限</dd>
      <dt>所选 Runtime</dt><dd>${esc(runtime?.label||'未选择')} ${esc(runtime?.version||'')}</dd>
      <dt>数据连接</dt><dd>${esc(connection||'未选择')} · 运行时仍需身份与授权核验</dd>
      <dt>合同检查</dt><dd>${part.verification?.contract==='INSPECTED'?'已核验工具声明':'未核验'} · 非运行安装证明</dd>
      <dt>最终安装</dt><dd>待新制品 Lock 核验</dd></dl>
      <details><summary>声明来源与依赖</summary><pre>${esc(JSON.stringify({capabilityPackRefs:part.capabilityPackRefs,
        domainPackRefs:part.domainPackRefs,providerRefs:part.providerRefs,profileRef:part.profileRef,
        capabilityBindings:part.capabilityBindings},null,2))}</pre></details></section>`;
  }).join('')}</section>`;
}
