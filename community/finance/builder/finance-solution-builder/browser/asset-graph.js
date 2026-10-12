import ELK from 'elkjs/lib/elk.bundled.js';
import {nodeFileKeys,resolveFile} from './asset-files.js';

const primaryKinds=new Set(['SKILL','CAPABILITY','EXECUTION_ARTIFACT']);
const contractKinds=new Set(['CONTRACT','DECLARATION']);
const kinds={SKILL:'专业方法',CAPABILITY:'业务能力',EXECUTION_ARTIFACT:'银行接入',CONTRACT:'数据约定',DECLARATION:'声明文件'};
const symbols={SKILL:'book-open',CAPABILITY:'blocks',EXECUTION_ARTIFACT:'plug',CONTRACT:'file-check-2',DECLARATION:'file-text'};
export function edgeLabel(edge){
  return ({'使用 · 声明引用':'声明使用','遵循 · 输入声明':'遵循输入声明','遵循 · 结果外壳':'遵循结果外壳','使用范围 · 发布绑定':'发布绑定范围'})[edge.label]||edge.label;
}

// IDs and source relations are retained; this is a view, never a dependency resolver.
export function graphProjection(value,focus=null){
  const source=value.composition||{nodes:[],relations:[]};
  const byId=new Map(source.nodes.map(n=>[n.id,n]));
  const ids=new Set(source.nodes.filter(n=>primaryKinds.has(n.kind)).map(n=>n.id));
  for(const e of source.relations){
    if(ids.has(e.source)&&contractKinds.has(byId.get(e.target)?.kind))ids.add(e.target);
    if(ids.has(e.target)&&contractKinds.has(byId.get(e.source)?.kind))ids.add(e.source);
  }
  const all=source.nodes.filter(n=>ids.has(n.id));
  const allEdges=source.relations.filter(e=>ids.has(e.source)&&ids.has(e.target));
  if(focus&&ids.has(focus)){
    const visible=new Set([focus]);
    for(const e of allEdges)if(e.source===focus||e.target===focus){visible.add(e.source);visible.add(e.target);}
    // Keep a method's capabilities and their direct implementation/contracts together.
    const caps=new Set([...visible].filter(id=>byId.get(id)?.kind==='CAPABILITY'));
    for(const e of allEdges)if(caps.has(e.source)||caps.has(e.target)){visible.add(e.source);visible.add(e.target);}
    for(const id of ids)if(!visible.has(id))ids.delete(id);
  }
  return {nodes:all.filter(n=>ids.has(n.id)),edges:allEdges.filter(e=>ids.has(e.source)&&ids.has(e.target)),
    all,hidden:all.filter(n=>!ids.has(n.id)).length,auxiliary:source.nodes.filter(n=>!all.includes(n)),
    excludedEdges:source.relations.length-allEdges.filter(e=>ids.has(e.source)&&ids.has(e.target)).length};
}

export async function graphLayout(projection){
  if(!projection.nodes.length)return {id:'asset',width:0,height:0,children:[],edges:[]};
  const elk=new ELK();
  return elk.layout({id:'asset',layoutOptions:{
    'elk.algorithm':'layered','elk.direction':'RIGHT','elk.edgeRouting':'ORTHOGONAL',
    'elk.padding':'[top=22,left=18,bottom=22,right=18]',
    'elk.spacing.nodeNode':'46','elk.layered.spacing.nodeNodeBetweenLayers':'46',
    'elk.spacing.edgeNode':'18','elk.spacing.edgeEdge':'16',
    'elk.layered.spacing.edgeNodeBetweenLayers':'16',
    'elk.layered.mergeEdges':'false',
  },children:projection.nodes.map(n=>({id:n.id,width:180,height:n.definitionCount?154:140})),
  edges:projection.edges.map((e,i)=>({id:'relation-'+i,sources:[e.source],targets:[e.target],
    labels:[{text:edgeLabel(e),width:Math.max(36,[...edgeLabel(e)].length*13+12),height:26}]}))});
}

export function versionOf(node,value){
  const ref=node.reference||{};
  if(ref.version)return 'v'+ref.version;
  const versions=new Set();
  for(const key of nodeFileKeys(node)){
    try{const version=resolveFile(value,key)?.reference?.version;if(version)versions.add(version);}
    catch{return '来源有歧义，请核对引用';}
  }
  return versions.size===1?'v'+[...versions][0]:versions.size>1?'多个版本，见具体引用':'版本见引用';
}
export function graphView({value,projection,layout,selected,zoom,esc,icon}){
  const connected=new Set([selected]);
  for(const e of projection.edges)if(e.source===selected||e.target===selected){connected.add(e.source);connected.add(e.target);}
  const nodeButton=(n,extra='')=>`<button type="button" class="asset-graph-node ${selected===n.id?'is-selected':''} ${selected&&connected.has(n.id)?'is-associated':''}" data-asset-node="${esc(n.id)}" aria-pressed="${selected===n.id}" ${extra}><span class="asset-graph-kind">${icon(symbols[n.kind]||'package')}${esc(kinds[n.kind]||n.kind)}</span><strong>${esc(n.label)}</strong>${n.definitionCount?`<small>包含 ${n.definitionCount} 项能力<br/>各自的输入定义</small>`:''}<small>${esc(versionOf(n,value))}${n.status==='REFERENCE_ONLY'?' · 制品引用':!['AVAILABLE','DECLARED'].includes(n.status)?' · '+esc(n.status):''}</small></button>`;
  const lines=layout.edges.map((edge,i)=>{
    const raw=projection.edges[i],active=selected&&(raw.source===selected||raw.target===selected);
    return `<g class="asset-graph-edge ${active?'is-selected':''}" data-graph-source="${esc(raw.source)}" data-graph-target="${esc(raw.target)}" data-graph-relation="${esc(raw.label)}">${(edge.sections||[]).map(s=>{const pts=[s.startPoint,...s.bendPoints||[],s.endPoint];return `<path d="${pts.map((p,j)=>`${j?'L':'M'}${p.x},${p.y}`).join(' ')}" marker-end="url(#asset-arrow${active?'-selected':''})"/>`;}).join('')}${(edge.labels||[]).map(l=>`<g class="asset-edge-label"><rect x="${l.x}" y="${l.y}" width="${l.width}" height="${l.height}" rx="4"/><text x="${l.x+l.width/2}" y="${l.y+16}" text-anchor="middle">${esc(l.text)}</text></g>`).join('')}<title>${esc(`${value.composition.nodes.find(n=>n.id===raw.source)?.label} ${edgeLabel(raw)} ${value.composition.nodes.find(n=>n.id===raw.target)?.label}`)}</title></g>`;
  }).join('');
  const svg=`<svg class="asset-graph-svg" viewBox="0 0 ${layout.width} ${layout.height}" aria-label="实际资产依赖关系图"><defs>${['','-selected'].map(s=>`<marker id="asset-arrow${s}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="${s?'#ba243c':'#63768c'}"/></marker>`).join('')}</defs>${lines}${layout.children.map(box=>{const n=projection.nodes.find(n=>n.id===box.id);return `<foreignObject x="${box.x}" y="${box.y}" width="${box.width}" height="${box.height}"><div xmlns="http://www.w3.org/1999/xhtml" class="asset-graph-node-wrap">${nodeButton(n)}</div></foreignObject>`;}).join('')}</svg>`;
  const ranks={SKILL:0,EXECUTION_ARTIFACT:1,CAPABILITY:2};
  const sources=[...new Set(projection.edges.map(e=>e.source))].sort((a,b)=>(ranks[projection.nodes.find(n=>n.id===a)?.kind]??3)-(ranks[projection.nodes.find(n=>n.id===b)?.kind]??3));
  const mobile=sources.map(id=>{const n=projection.nodes.find(n=>n.id===id);return `<section class="asset-mobile-relation">${nodeButton(n)}<ul>${projection.edges.filter(e=>e.source===id).map(e=>{const target=projection.nodes.find(n=>n.id===e.target);return `<li data-mobile-source="${esc(id)}" data-mobile-target="${esc(e.target)}"><span class="asset-mobile-verb">${icon('corner-down-right')}${esc(edgeLabel(e))}</span><button type="button" data-asset-node="${esc(target.id)}" class="asset-mobile-target" aria-pressed="${selected===target.id}"><strong>${esc(target.label)}</strong>${target.definitionCount?`<small>包含 ${target.definitionCount} 项能力各自的输入定义</small>`:''}<small>${esc(kinds[target.kind]||target.kind)} · ${esc(versionOf(target,value))}</small></button></li>`;}).join('')}</ul></section>`;}).join('');
  const isolated=projection.nodes.filter(n=>!projection.edges.some(e=>e.source===n.id||e.target===n.id));
  return `<div class="asset-graph-toolbar"><div><h3>组成与依赖关系</h3><span>${projection.nodes.length} 个对象 · ${projection.edges.length} 条关系${projection.hidden?` · 隐藏 ${projection.hidden} 个对象`:''}</span></div><div class="asset-graph-controls"><button type="button" data-graph-zoom="out" title="缩小" aria-label="缩小关系图">${icon('zoom-out')}</button><button type="button" data-graph-zoom="in" title="放大" aria-label="放大关系图">${icon('zoom-in')}</button><button type="button" data-graph-zoom="fit" title="适配视图" aria-label="适配关系图">${icon('maximize-2')}</button></div></div>
    <p class="asset-graph-subtitle">箭头表示声明、实现与约定，不表示调用顺序。</p>
    ${!projection.nodes.some(n=>n.kind==='SKILL')?'<p class="asset-graph-notice">不包含独立方法，直接展示能力与接入依赖。</p>':''}
    <div class="asset-graph-desktop" tabindex="0" aria-label="可滚动关系图">${svg}</div><div class="asset-graph-mobile">${mobile}${isolated.map(n=>`<section class="asset-mobile-relation">${nodeButton(n)}<p>未记录与其他对象的明确关系。</p></section>`).join('')}</div>
    <details class="asset-graph-focus"><summary>按方法或能力聚焦${projection.hidden?` · ${projection.hidden} 个对象未显示`:''}</summary><button type="button" data-asset-focus="">全部关系</button>${projection.all.filter(n=>['SKILL','CAPABILITY'].includes(n.kind)).map(n=>`<button type="button" data-asset-focus="${esc(n.id)}">${esc(n.label)}</button>`).join('')}</details>`;
}
