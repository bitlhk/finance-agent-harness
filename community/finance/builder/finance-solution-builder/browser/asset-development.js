const rules = [
  ['Skill / SOP', '定制专业方法；增加工具引用时重新绑定，不能只改文字。', '现有方法草稿、SKILL.md、参考资料和案例', '核对 allowed-tools、缺能力处理和输出要求；试跑确认方法加载。'],
  ['Capability / Tool', '定义业务范围、输入、查询或办理类型及所需权限。', '能力包 pack.yaml；安装依赖见 assembly.v0.1.json（如已声明）', '参数正反例、工具安装清单、未安装工具拒绝；requiredScopes 不授予权限。'],
  ['Contract', '明确金额单位、统计范围、缺失含义、结果与状态。', '当前工具的输入声明与输出合同；领域合同按真实引用定位', '零值与缺失、精度、坏响应、版本兼容；公共结果外壳不替代领域字段定义。'],
  ['Provider', '实现企业接口与字段映射，接入可信源身份和权威业务状态。', '受管理的 Provider 源码工程与工厂注册；不在网页上传执行代码', '本人/错主体、撤权、缺数、超时；办理另测确认、参数冻结与 UNKNOWN 查证。'],
  ['验证资产', '为新增行为交付案例、执行入口和预期结果。', '组件案例及其工程测试；未声明的入口需由开发者补充', '确认新增案例被执行；故意破坏映射后对应测试应失败。仓库基线通过不代表新组件通过。'],
  ['Trace / 观测', '复用公共工具记录；补来源操作名、路由模板和特殊业务阶段。', 'Integrator / 公共观测包装器；Provider 的观测与名称扩展点', '检查请求、工具、来源与版本关联；尝试不等于已发送，返回不等于业务成功。禁止记录密钥和隐藏思考。'],
];

export function developmentRelations(value, esc, fileButton) {
  const skills=value.development?.skills||[];
  return `<section class="asset-development-relations"><h3>方法如何使用工具</h3>
  <p>以下仅核对文件中的声明，不代表模型已调用工具或业务已完成。</p>
  ${skills.length?skills.map(s=>`<section>${fileButton(s.fileId||s.path,'查看 Skill')}<p>${s.status==='DECLARED'?'声明引用：'+esc(s.declaredTools.join('、')||'空列表'):'未取得有效 allowed-tools 声明；需开发者核对，不从正文猜测依赖。'}</p>${s.unresolvedTools.length?`<p role="status">未在本组件绑定中找到：${esc(s.unresolvedTools.join('、'))}。需核对其他依赖或补充绑定，不能据此宣称可执行。</p>`:''}</section>`).join(''):'<p>没有可读取的独立 Skill 文件；宿主方法或外部引用需另行核对。</p>'}
  <ol class="asset-relation-chain"><li>Skill 声明工具</li><li>能力声明约束输入与操作</li><li>Provider 按合同接企业接口</li><li>实际运行产生结果与记录</li></ol></section>`;
}

export function developmentRules(value, esc, fileButton) {
  return `<section class="asset-development"><h3>开发一个组件，从哪里开始</h3>
  <p>Skill 可以调用工具；Harness 扩展还要接好工具合同、执行实现和可验证的边界。并非每个组件都需要新增代码。</p>
  <details open><summary>只改专业方法</summary><ol><li>定制当前 SOP，保留不变的能力引用。</li><li>核对新增问法与工具依赖，增加一个自己的案例。</li><li>保存、检查与试跑、发布新版本，再用该版本创建 Agent。</li></ol></details>
  <details><summary>新增企业接口或业务能力</summary><ol><li>先固定接口版本、字段含义与可信身份来源。</li><li>开发 Provider 和必要的能力/领域合同；复用现有执行协议。</li><li>添加正常、拒绝、缺数与故障测试，编译执行制品。</li><li>由维护者准入 Provider 及目录，再绑定方法、验证并发布。</li></ol><p>只有受管理资产才可装配；上传 Skill ZIP 不会自动安装 Java 代码。</p></details>
  <h3>开发责任与验收清单</h3><div class="asset-rule-list">${rules.map(([name,task,where,check])=>`<details><summary>${esc(name)}</summary><dl><dt>负责什么</dt><dd>${esc(task)}</dd><dt>修改位置</dt><dd>${esc(where)}</dd><dt>验收要求</dt><dd>${esc(check)}</dd></dl></details>`).join('')}</div>
  <h3>本版本可查看的验证文件</h3>${value.files.filter(f=>['CASE','VALIDATION_SUMMARY'].includes(f.role)).map(f=>fileButton(f.fileId||f.path,f.path)).join('')||'<p>本组件未包含可浏览的专属案例或验证摘要。请由开发者提供测试源码和命令，不能用目录准入替代业务验收。</p>'}
  <h3>公共机制与扩展责任</h3><p>身份授权、工具边界和基础观测由已接线的宿主、Integrator 与公共包装器承担。Provider 仍需验证源身份并补充来源调用观测；是否生效以该 Agent 的实际安装与执行记录为准，不因页面展示此清单就视为已验证。</p>
  <h3>固定版本源码与接入入口</h3><p>以下为本环境实际存在的公共参考实现，不是当前组件已经通过测试的证明。访问私仓需要开发者自己的权限。</p><ul>${(value.development?.sourceReferences||[]).map(r=>`<li><a href="${esc(r.url)}" target="_blank" rel="noopener noreferrer">${esc(r.label)}</a><small> · ${esc(r.commit.slice(0,8))}</small></li>`).join('')||'<li>当前环境无法提供公共源码引用，请向维护者获取固定版本开发包。</li>'}</ul>
  <details><summary>新特性交付前自查</summary><ul><li>已说明用户任务、复用范围和新增协议。</li><li>新增文件、工具、合同与制品有真实版本引用。</li><li>新增测试有明确命令、非零执行数及失败反例。</li><li>已区分公共观测与来源专属观测，缺口明确记录。</li><li>受管准入后新建 Agent 核对安装，旧版本不覆盖。</li><li>独立接入成本由实际开发者记录，不以 AI 自测替代。</li></ul><p>完整步骤与统一交付卡见公开开发指导第 16 章；接入工作仍受当前协议与运行环境支持范围约束。</p><a href="https://finance-harness.demo.linggan.top/developer-guide/" target="_blank" rel="noopener noreferrer">打开开发指导</a></details>
  <p>打包器形成资产 ZIP；编译工具生成 JAR；Builder 构建 Agent；Assembler 核验与安装。安装 Lock、Trace 属于具体实例和请求，不是 Skill 的静态文件。</p>
  </section>`;
}
