import {developmentRules} from './asset-development.js';
import {verificationRows,runtimeVerificationText} from './asset-verification.js';
import {readingGroups,membersOf,relationText} from './asset-reading.js';
import {graphProjection,graphLayout,graphView} from './asset-graph.js';
import {fileKey,nodeFileKeys,resolveFile,fileSource} from './asset-files.js';
import {supportView} from './asset-support.js';
const statuses={AVAILABLE:'内容可查看',DECLARED:'已声明',REFERENCE_ONLY:'仅引用',PLATFORM_SUPPORT:'公共支撑'};
const types={SKILL:'专业方法',CAPABILITY:'业务能力',CONTRACT:'合同',EXECUTION_ARTIFACT:'Provider',ASSEMBLY:'装配要求',OBSERVATION:'观测要求',DECLARATION:'能力声明',MANIFEST:'包清单',CASE:'验证案例',REFERENCE:'参考资料',DOMAIN_PACK:'方法包',CAPABILITY_PACK:'能力包',REQUIREMENTS:'执行要求',PLATFORM:'平台支撑',VALIDATION_SUMMARY:'验证摘要'};
const icons={SKILL:'book-open',CAPABILITY:'blocks',EXECUTION_ARTIFACT:'plug',CONTRACT:'file-check-2',PLATFORM:'shield-check'};
const origins={PACKAGE_FILE:'包内文件',REFERENCED_ASSET:'引用资产',GENERATED_RECORD:'生成记录'};

export function assetContents({api,esc,icon,rerender,actions=()=>''}) {
  let value=null,error='',loading=false,tab='composition',selected=null,nodeId=null,returnNode=null,returnIndex=-1,returnScroll=0,generation=0;
  let projection=null,layout=null,layoutError='',focus=null,zoom=1,layoutGeneration=0,fileLocation=null,fileError='';
  const previewFile=key=>{try{return resolveFile(value,key);}catch{return null;}};
  async function arrange(){
    const current=++layoutGeneration,asset=value;layout=null;layoutError='';
    projection=graphProjection(value,focus);
    try{const result=await graphLayout(projection);if(current===layoutGeneration&&asset===value)layout=result;}
    catch(e){if(current===layoutGeneration&&asset===value)layoutError='关系布局暂不可用，请通过文件与引用核对；未改变依赖数据。';}
  }
  async function open(url){
    const current=++generation;layoutGeneration++;value=null;layout=null;error='';loading=true;tab='composition';selected=null;nodeId=null;returnNode=null;focus=null;zoom=1;fileLocation=null;fileError='';rerender();
    try{const result=await api(url);if(result?.schemaVersion!=='finance-asset-contents.v0.1'||!Array.isArray(result.files)||!Array.isArray(result.bindings)||!Array.isArray(result.dependencies)||!result.identity)throw Error('ASSET_CONTENT_RESPONSE_UNAVAILABLE');if(current===generation){value=result;await arrange();}}
    catch(e){if(current===generation){const detail=e.data?.error;error=detail?.message?detail.message+(detail.locations?.length?'（'+detail.locations.map(l=>l.path).join('、')+'）':''):e.message;}}finally{if(current===generation){loading=false;rerender();}}
  }
  const fileButton=(key,label)=>{const file=previewFile(key);return `<button type="button" class="text-button package-file-link" data-asset-file="${esc(key)}">${icon('file-text')}<span>查看文件 · ${esc(label||file?.guidance?.title||file?.path||key)}<small>${esc(file?.path||key)}${file?' · '+esc(fileSource(file)):''}</small></span>${icon('arrow-up-right')}</button>`;};
  const origin=node=>node.id.startsWith('file:')?origins[previewFile(nodeFileKeys(node)[0])?.origin]||'来源未记录':node.group==='contents'?'本包提供':node.group==='platform'?'平台提供':'引用依赖';
  const memberButton=node=>`<button type="button" class="package-member" data-asset-node="${esc(node.id)}" aria-pressed="${node.id===nodeId}">${icon(icons[node.kind]||'file-text')}<span>${esc(node.label)}<small>${esc(origin(node))} · ${esc(types[node.kind]||node.kind)}</small></span></button>`;
  function composition(){
    const graph=value.composition;if(!graph)return '<p>此服务未提供组成关系，请查看实际文件与依赖引用。</p>';
    const groups=readingGroups(value),nodes=graph.nodes,current=nodes.find(n=>n.id===nodeId);
    const relations=graph.relations.filter(r=>r.source===current?.id||r.target===current?.id);
    const support=groups.filter(g=>['requirements','validation','platform'].includes(g.id));
    return `<div class="package-studio-board package-graph-board"><section class="package-studio-composition">${layout?graphView({value,projection,layout,selected:nodeId,zoom,esc,icon}):`<p role="status">${esc(layoutError||'正在排列真实关系…')}</p>`}
      <details class="asset-graph-index"><summary>全部对象与原始关系 · ${nodes.length} 个对象 / ${graph.relations.length} 条关系</summary>${groups.map(g=>`<details><summary>${esc(g.label)} · ${g.memberIds.length}</summary>${membersOf(value,g).map(memberButton).join('')||'<p>当前没有对应声明。</p>'}</details>`).join('')}</details>
      <div class="asset-graph-support">${support.map(g=>`<details><summary>${icon(g.icon)}${esc(g.label)} · ${g.memberIds.length}</summary>${membersOf(value,g).map(memberButton).join('')||'<p>尚无此资产专属验证证据。</p>'}</details>`).join('')}</div></section>
      <aside class="package-inspector ${current?'has-selection':''}" aria-label="组件详情" tabindex="-1"><button type="button" class="text-button package-back" data-asset-return>${icon('arrow-left')}返回关系图</button><small class="package-inspector-eyebrow">${current?esc(types[current.kind]||current.kind):'对象详情'}</small><h3>${current?esc(current.label):'查看组件与来源'}</h3>
      ${current?`<section class="package-member-detail"><span class="package-origin">${esc(origin(current))}</span><p>${esc(current.purpose)}</p>
      ${current.descriptionSource?'<small>业务说明来自当前目录；原始声明见技术详情及文件。</small>':''}
      <h4>关系与依赖</h4>${relations.length?relations.map(r=>{const other=nodes.find(n=>n.id===(r.source===current.id?r.target:r.source));return other?`<button type="button" class="package-relation" data-asset-node="${esc(other.id)}"><small>${esc(relationText(r,current.id))}</small>${esc(other.label)}</button>`:'';}).join(''):'<p class="method-muted">没有声明到具体成员的关系，保留引用，不推测。</p>'}
      ${current.unresolvedTools?.length?`<p role="status">未解析工具：${esc(current.unresolvedTools.join('、'))}。请选择已准入能力，或按 Provider 样例开发并登记实现。</p>`:''}
      <h4>对应文件</h4>${nodeFileKeys(current).map(p=>fileButton(p)).join('')||`<p>${esc(current.reason||'没有可浏览正文；保留引用，实际制品摘要请核对 Agent 安装 Lock。')}</p>`}
      <h4>如何修改与验证</h4><p>${esc(current.modify)}</p><details><summary>版本、来源与技术标识</summary><pre>${esc(JSON.stringify({id:current.id,kind:current.kind,status:current.status,reference:current.reference,originalDescription:current.originalDescription,declarationLocation:current.declarationLocation,digest:current.digest,files:current.files},null,2))}</pre></details></section>`:`<div class="asset-inspector-empty">${icon('mouse-pointer-2')}<p>选择图中的方法、能力或接入，查看对应文件、版本与修改位置。</p><dl><dt>当前关系</dt><dd>来自此版本的组成声明</dd><dt>执行证据</dt><dd>需查看具体 Agent 的安装 Lock 与运行记录</dd></dl></div>`}</aside></div>`;
  }
  function files(){
    const current=selected?previewFile(selected):fileError?null:value.files[0];selected=current?fileKey(current):null;
    const location=(fileLocation?.fileId?fileLocation.fileId===current?.fileId:fileLocation?.path===current?.path)?fileLocation:null;
    const body=(current?.content||'').split('\n').map((line,i)=>`<span data-file-line="${i+1}" class="${location&&i+1>=location.startLine&&i+1<=location.endLine?'is-declaration':''}">${esc(line)}</span>`).join('\n');
    return `${fileError?`<p role="alert">${esc(fileError)}</p>`:''}<div class="asset-file-browser"><nav class="asset-file-tree" aria-label="版本文件">${Object.entries(origins).map(([kind,label])=>{const group=value.files.filter(f=>f.origin===kind);return group.length?`<h3>${label} · ${group.length}</h3><ul>${group.map(f=>`<li><button type="button" data-asset-file="${esc(fileKey(f))}" aria-pressed="${selected===fileKey(f)}">${icon(f.role==='SKILL'?'book-open':'file-check-2')}<span><strong>${esc(f.guidance?.title||types[f.role]||'文件')}</strong><small>${esc(f.path)}</small><small>${esc(fileSource(f))}</small></span></button></li>`).join('')}</ul>`:'';}).join('')}</nav>
    <div class="asset-file-preview">${current?`<div class="package-group-heading"><h3>${esc(current.guidance?.title||types[current.role]||'文件正文')}</h3><span class="business-tag">只读</span></div><p>${esc(current.guidance?.purpose||'按实际声明核对用途。')}</p><p class="package-file-path">${esc(current.path)}</p><p>${esc(origins[current.origin])} · ${current.size} 字节</p><p>${esc(current.guidance?.modify||'在来源工程或新草稿中修改；重新验证。')}</p>
    <p>${esc(fileSource(current))} · ${esc(current.identityMessage||'来源待核对')}</p>${location?`<p class="asset-declaration-location">已定位 ${esc(location.capabilityId)} · 第 ${location.startLine}–${location.endLine} 行</p>`:''}${current.previewStatus==='AVAILABLE'?`<button type="button" class="text-button" data-asset-download-file>${icon('download')}下载此文件</button><pre class="asset-file-code" tabindex="0" aria-label="文件正文"><code>${body}</code></pre>`:`<p>正文不可预览：${esc(current.previewStatus)}。不以其他内容替代。</p>`}<details><summary>摘要与版本来源</summary><pre>${esc(JSON.stringify({fileId:current.fileId,sha256:current.sha256,reference:current.reference},null,2))}</pre></details>`:'<h3>选择一个文件查看正文与修改方式</h3><p>已发布版本不可覆盖。方法修改创建新草稿；Provider 代码在外部工程开发并准入。</p>'}</div></div>`;
  }
  function checks(){
    const released=value.kind==='PUBLISHED_PACKAGE',valid=value.validation;
    const rows=[['内容与依赖',released?'已读取发布清单与 ZIP 内容':'已读取固定版本目录引用'],['合同兼容',released?'目标环境仍需重新核验':'目录已解析；以构建与安装检查为准'],['环境与执行要求','创建 Agent 时检查 Runtime、Provider 与连接；运行时检查实授权限'],['隔离试跑',runtimeVerificationText(value)],['专业回答质量',valid.professionalQuality==='NOT_EVALUATED'?'尚未评测；不由结构通过代替':valid.professionalQuality||'未记录人工确认'],['发布版本',released?'当前版本只读；变更须发布新版本':'目录组合不是 ZIP；定制方法后沿草稿流程发布']];
    return `<section class="package-checks"><h3>${released?'此版本的验证与使用':'选择下一步'}</h3>${actions(value,'checks')}${rows.map(([title,description],i)=>`<div class="package-check"><span class="package-check-number">${i+1}</span><strong>${title}</strong><p>${esc(description)}</p></div>`).join('')}${released?`<dl class="package-verification">${verificationRows(value).map(([label,text])=>`<div><dt>${esc(label)}</dt><dd>${esc(text)}</dd></div>`).join('')}</dl>`:''}<details><summary>原始验证依据</summary><pre>${esc(JSON.stringify({validation:valid,verification:value.verification,requirements:value.requirements},null,2))}</pre></details><p class="package-note">业务包由 Harness Packager 生成；Builder 另行构建 Agent。安装 Lock 属于具体实例。权限声明不授予实权，观测声明不代表采集。</p></section>
    ${supportView(value.developmentSupport,esc)}<section class="package-checks package-usages"><h3>引用此资产的 Agent</h3><p>按创建时的版本引用关联，不代表已安装成功；打开实例核对独立 Lock 与运行记录。</p>${value.usages.length?`<ul>${value.usages.map(u=>`<li><a href="${esc(u.url)}">${esc(u.displayName||'未记录名称的 Agent')}</a> · ${esc(u.status)}<details><summary>实例标识与名称来源</summary><p>${esc(u.buildId)} · ${u.displayName?'构建配置中的名称':'名称未记录'}</p></details></li>`).join('')}</ul>`:'<p>当前工作空间没有匹配记录。</p>'}<details><summary>开发与验证指引</summary>${developmentRules(value,esc,fileButton)}</details></section>`;
  }
  function html(){
    if(loading)return '<p role="status">正在读取指定版本…</p>';if(error)return `<p role="alert">此版本不可读取：${esc(error)}。未用其他版本替代。</p>`;if(!value)return '<p>选择业务资产查看具体组成。</p>';
    const tabs=[['composition','组成与依赖'],['files','文件与修改'],['checks','检查与生成']];
    return `<div class="asset-composition"><section class="asset-composition-summary"><div><span class="business-tag">${value.kind==='PUBLISHED_PACKAGE'?'已发布组件包':'目录组件'}</span><strong>v${esc(value.version)}</strong><span>方法 ${value.files.filter(f=>f.role==='SKILL').length} · 能力 ${value.bindings.length} · 引用 ${value.dependencies.length}</span><details><summary>版本与来源</summary>${value.kind!=='PUBLISHED_PACKAGE'?'<p>此组件按目录引用组合，不是已发布 ZIP；可直接用于创建 Agent。</p>':''}<pre>${esc(JSON.stringify(value.identity,null,2))}</pre></details></div></section><div class="asset-content-tabs" role="tablist" aria-label="资产详情">${tabs.map(([id,label])=>`<button type="button" role="tab" id="asset-tab-${id}" aria-controls="asset-panel" aria-selected="${tab===id}" data-asset-tab="${id}">${label}</button>`).join('')}</div><section id="asset-panel" role="tabpanel" aria-labelledby="asset-tab-${tab}">${tab==='composition'?composition():tab==='files'?files():checks()}</section></div>`;
  }
  function act(event){
    const target=event.target.closest('[data-asset-tab],[data-asset-node],[data-asset-file],[data-asset-download-file],[data-asset-return],[data-graph-zoom],[data-asset-focus]');if(!target||!value)return false;
    if(target.hasAttribute('data-asset-focus')){focus=target.dataset.assetFocus||null;arrange().then(rerender);rerender();return true;}
    if(target.dataset.graphZoom){zoom=target.dataset.graphZoom==='fit'?1:Math.min(2,Math.max(.85,zoom+(target.dataset.graphZoom==='in'?.15:-.15)));rerender();document.querySelector(`[data-graph-zoom="${target.dataset.graphZoom}"]`)?.focus();return true;}
    if(target.dataset.assetTab){tab=target.dataset.assetTab;rerender();document.querySelector(`[data-asset-tab="${tab}"]`)?.focus();}
    else if(target.hasAttribute('data-asset-return')){const buttons=[...document.querySelectorAll('[data-asset-node]')],original=buttons[returnIndex];const button=original?.dataset.assetNode===returnNode?original:buttons.find(n=>n.dataset.assetNode===returnNode&&n.getClientRects().length);button?.focus({preventScroll:true});window.scrollTo({top:returnScroll,behavior:'instant'});}
    else if(target.dataset.assetNode){
      const viewport=document.querySelector('.asset-graph-desktop'),scroll=viewport?.scrollLeft||0;
      if(!target.closest('.package-inspector')){returnScroll=window.scrollY;returnNode=target.dataset.assetNode;returnIndex=[...document.querySelectorAll('[data-asset-node]')].indexOf(target);}
      nodeId=target.dataset.assetNode;
      rerender();const next=document.querySelector('.asset-graph-desktop');if(next)next.scrollLeft=scroll;
      const panel=document.querySelector('.package-inspector');panel?.focus({preventScroll:true});if(innerWidth<1050)panel?.scrollIntoView({block:'start',behavior:'instant'});
    }
    else if(target.dataset.assetFile){fileError='';try{const file=resolveFile(value,target.dataset.assetFile);selected=file?fileKey(file):null;if(!file)fileError='此引用没有可读取文件，请核对来源版本；未用其他文件替代。';}catch(e){selected=null;fileError=e.message;}fileLocation=target.closest('.package-inspector')?value.composition?.nodes.find(n=>n.id===nodeId)?.declarationLocation:null;tab='files';rerender();(document.querySelector('.asset-file-code .is-declaration')||document.querySelector('.asset-file-preview h3'))?.scrollIntoView({block:'nearest'});}
    else{const f=previewFile(selected);if(f?.previewStatus==='AVAILABLE'){const url=URL.createObjectURL(new Blob([f.content],{type:'text/plain;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download=f.path.split('/').at(-1);a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}}return true;
  }
  function afterRender(){
    const svg=document.querySelector('.asset-graph-svg');
    if(svg&&layout){svg.style.width=zoom*100+'%';svg.style.minWidth=Math.round(layout.width*.85*zoom)+'px';}
  }
  return{open,html,act,afterRender,clear(){generation++;layoutGeneration++;value=null;layout=null;projection=null;loading=false;error='';},get value(){return value;}};
}
