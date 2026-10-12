import {verificationRows} from './asset-verification.js';
const labels={AVAILABLE:'已提供适用案例',CASES_REQUIRED:'请补充验证案例',NOT_DECLARED:'未提供组件验证资料',SCOPE_UNRESOLVED:'请先确定验证环境',SCOPE_MISMATCH:'当前环境没有适用案例',UNAVAILABLE:'验证资料不可用'};
const environmentLabels={runtimeId:'运行框架',integratorId:'接入桥',profileId:'使用范围',hostId:'宿主',testDataId:'测试数据'};
const missingLabels={'scope.hostId':'宿主版本','scope.testDataId':'测试数据版本',executedAt:'实际执行时刻','trial.createdAt':'记录时间'};

function methodReports(support,esc){
  const records=support.derivedMethodVerifications||[],scan=support.reportScan;
  return `<section class="component-method-verifications"><h4>基于此组件的已发布方法验证 · ${records.length}</h4>
    <p>以下报告只属于列出的定制方法及运行范围，不代表原组件或其他 Runtime 已通过。</p>
    ${records.map(r=>{const ref=r.assetRef||{},runtime=r.actualRuntime||{};
      const time=verificationRows({verification:{createdAt:r.createdAt}})[0][1];
      return `<details><summary>${esc(r.name||ref.assetId)} · v${esc(ref.version)}</summary><dl class="package-verification">
        <div><dt>核验结论</dt><dd>${esc(r.validation?.reason||'运行证据待核对')}</dd></div>
        <div><dt>实际框架</dt><dd>${esc([runtime.family,runtime.language,runtime.version].filter(Boolean).join(' · ')||'未记录')}</dd></div>
        <div><dt>报告记录时间</dt><dd>${esc(time)}</dd></div>
        <div><dt>与当前组件的关联</dt><dd>${esc(r.baseMatch==='MATCHED'?'基础引用版本与摘要一致':r.baseMatch==='VERSION_MISMATCH'?'基础引用属于其他版本':r.baseMatch==='DIGEST_MISMATCH'?'基础引用摘要不一致':'基础引用证据不足')}</dd></div>
      </dl>${r.missingFields?.length?`<p>未记录：${esc(r.missingFields.map(f=>missingLabels[f]||f).join('、'))}。这些信息无法从旧记录补证。</p>`:''}
      ${ref.assetId&&ref.version&&ref.releaseDigest?`<button type="button" class="text-button" data-method-action="verification-version" data-asset="${esc(ref.assetId)}" data-version="${esc(ref.version)}" data-digest="${esc(ref.releaseDigest)}">查看对应版本与报告</button>`:''}
      <details><summary>版本、安装和报告引用</summary><pre>${esc(JSON.stringify(r,null,2))}</pre></details></details>`;}).join('')||'<p>当前工作空间尚无可关联的已发布方法报告。</p>'}
    ${scan&&scan.status!=='COMPLETE'?`<p role="status">${scan.truncated?'本次有界读取未覆盖全部历史报告':'部分历史报告不可读取'}；未读取内容不计为验证通过。</p>`:''}</section>`;
}

export function supportView(support, esc) {
  if (!support) return '';
  const cases=support.methodCases||[],refs=support.caseRefs||[],commands=support.commands||[];
  const validation=support.validation||{status:'NOT_RUN',reason:'尚无绑定此版本的运行结果'};
  return `<section class="package-checks component-development-support"><h3>本组件的验证入口</h3>
    <p role="status">${esc(labels[support.status]||support.message||'验证范围待核对')} · ${cases.length} 项方法案例</p>
    <dl class="package-verification"><div><dt>被测资产</dt><dd>${(support.subjects||[]).map(s=>esc(s.id)+' @ '+esc(s.version)).join('；')||'版本未记录'}</dd></div>
    <div><dt>组件本体验证</dt><dd>${esc(validation.reason||'未取得匹配的运行结果')}</dd></div>
    <div><dt>专业质量</dt><dd>未由这些结构与执行检查确认</dd></div>
    ${Object.entries(environmentLabels).map(([key,label])=>support.scope?.[key]?`<div><dt>${label}</dt><dd>${esc(support.scope[key])}</dd></div>`:'').join('')}</dl>
    ${(support.warnings||[]).map(w=>`<p>${esc(w.message)}</p>`).join('')}
    ${cases.length?`<details open><summary>默认方法案例</summary><ol>${cases.map(c=>`<li><strong>${esc(c.name)}</strong><p>${esc(c.query)}</p>${c.expectedTerms?.length?`<details><summary>评测侧预期，不装入 Agent 提示</summary><p>${esc(c.expectedTerms.join('、'))}</p></details>`:''}</li>`).join('')}</ol></details>`:''}
    ${refs.length?`<details><summary>其他验证案例 · ${refs.length}</summary><ul>${refs.map(r=>`<li><strong>${esc(r.name||r.format)}</strong><p>${esc(r.source)}</p><small>${esc(r.format)} · ${esc(r.digest)}</small></li>`).join('')}</ul></details>`:''}
    ${commands.length?`<details><summary>开发环境验证命令 · ${commands.length}</summary><p>在固定版本的开发工程中运行；页面不执行上传的命令或脚本。</p>${commands.map(c=>`<section><strong>${esc(c.name)}</strong><pre><code>${esc(c.command)}</code></pre><p>工作目录：${esc(c.workingDirectory)}<br>源码提交：${esc(c.sourceCommit)}</p></section>`).join('')}</details>`:''}
    <p>需验证自己的方法时，使用上方“定制方法”，保存后检查、试跑。结果归属于该草稿与发布版本，不把平台回归或原组件案例视为新方法通过。</p>
    ${methodReports(support,esc)}
    <details><summary>声明来源、摘要与运行记录</summary><pre>${esc(JSON.stringify({subjects:support.subjects,sources:support.sources,casesDigest:support.casesDigest,scope:support.scope,validation},null,2))}</pre></details>
  </section>`;
}
