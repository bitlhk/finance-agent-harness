export const preferredKinds={methods:['SKILL'],capabilities:['CAPABILITY'],contracts:['CONTRACT','DECLARATION'],providers:['EXECUTION_ARTIFACT'],requirements:['REQUIREMENTS','OBSERVATION'],validation:['VALIDATION_SUMMARY','CASE']};
export function readingGroups(value){
  const graph=value.composition;
  return graph.readingGroups||[{id:'other',label:'资产与引用',icon:'package',memberIds:graph.nodes.map(n=>n.id)}];
}
export function membersOf(value,group){return group.memberIds.map(id=>value.composition.nodes.find(n=>n.id===id)).filter(Boolean);}
export function primaryMembers(value,group){
  const members=membersOf(value,group),preferred=preferredKinds[group.id];
  return preferred?members.filter(n=>preferred.includes(n.kind)):members;
}
export function groupSummary(value,group){
  const primary=primaryMembers(value,group),all=membersOf(value,group);
  const labels=primary.map(n=>n.label);
  const names=labels.slice(0,2).join('、')+(labels.length>2?`，另 ${labels.length-2} 项`:'');
  const uncertain=all.some(n=>n.unresolvedTools?.length||!['AVAILABLE','DECLARED','REFERENCE_ONLY','PLATFORM_SUPPORT'].includes(n.status));
  const count=primary.length;
  let summary=names,note='';
  if(group.id==='methods'){const referenced=all.some(n=>n.kind==='DOMAIN_PACK');summary=names||(referenced?'未读取到独立方法正文':'不包含独立方法');note=count?`${count} 项方法 · 展开查看所属包与资料`:referenced?'保留方法包引用，不推断正文或宿主行为':'查询能力可以独立使用';}
  else if(group.id==='capabilities'){summary=names||'未读取到业务能力声明';note=`${count} 项业务操作 · 查看工具与所属包`;}
  else if(group.id==='contracts'){summary=value.bindings.length?`${value.bindings.length} 项能力的输入与输出引用`:names||'未读取到接口约定';note='逐项核对业务口径；通用结果外壳不等于领域合同';}
  else if(group.id==='providers'){summary=names||'未明确引用银行适配器';note=count?`${count} 项实现引用 · 实际制品以安装 Lock 核对`:'需要在目标环境解析实现，不推断默认来源';}
  else if(group.id==='requirements'){summary='执行条件与适用环境';note=all.some(n=>n.kind==='OBSERVATION')?'已读取观测声明，采集覆盖需运行记录核对':'未读取到独立观测定义；不据此认定已采集';}
  else if(group.id==='validation'){summary=all.some(n=>n.kind==='CASE')?'案例与验证摘要':'未读取到专属案例文件';note=value.validation?.status==='PASS'?'此版本受控验证通过，专业质量另行核对':'尚无此资产专属试跑通过证据';}
  else if(group.id==='other'){summary=`${all.length} 项清单或其他引用`;note='保留原始类型、版本与来源';}
  else if(group.id==='platform'){summary='公共合同、接线与安装校验';note='由平台提供，不需要逐项选择';}
  return {summary,note,count,attention:uncertain};
}
export function relatedGroups(value,nodeId){
  const related=new Set(value.composition.relations.filter(r=>r.source===nodeId||r.target===nodeId).flatMap(r=>[r.source,r.target]));
  return new Set(readingGroups(value).filter(g=>g.memberIds.some(id=>related.has(id))).map(g=>g.id));
}
export function relationText(edge,current){
  const outgoing=edge.source===current;
  const label=edge.label;
  if(label==='使用 · 声明引用')return outgoing?'声明使用':'被方法声明使用';
  if(label==='声明可用能力')return outgoing?'声明可用能力范围':'属于方法包声明范围';
  if(label==='使用范围 · 发布绑定')return outgoing?'发布包绑定范围':'发布包绑定的方法';
  if(label==='实现')return outgoing?'实现的业务能力':'由此适配器实现';
  if(label==='声明')return outgoing?'声明的业务能力':'来自能力包';
  if(label==='输入定义位于')return outgoing?'输入定义位于':'此文件包含该能力的输入定义';
  if(label==='包含')return outgoing?'包含的成员':'来自指定版本';
  if(label.startsWith('遵循'))return outgoing?label:'使用此约定的能力';
  return label;
}
