# 中文可执行 SOP 候选

三个业务单元不变：余额、财富、收支。只替换新构建默认方法的语言，不增加能力、不改 UI。

- 余额：Host `account-balance.md` 0.1.1。
- 财富：Host `wealth-facts.md` 0.1.1，加 Harness `wealth-shared-facts` 0.1.1。
- 收支：Harness `cashflow-customer-self-service` 0.1.3，仍绑定两个只读工具，保留上游原方法的摘要后分类顺序与六个月周期。

Host catalog 保持 `finance-host-methods.v0.1`，每个方法增加可选 version；旧文件无 version 时
来源记录仍使用 content-addressed，旧 Host 忽略该可选元数据。正文摘要仍参与制品 Lock。
Domain Pack、配方、目录 fixture 和导读的摘要引用同步升级。导读仍只是说明，不替代执行正文。
业务详情派生入口与“从示例新建”读取同一批正文，不使用另写的中文简述充当完整 SOP。

## 接入与验证

配套 Harness 必须包含本次中文方法与 Catalog 0.2.3。旧目录会被现有版本检查拒绝，不能默默回退。

```sh
PYTHONPATH=src:../base/src python engineering/verify_chinese_methods.py \
  --harness /path/to/finance-agent-harness --output /path/to/evidence/source-checks.json
PYTHONPATH=src:../base/src python -m pytest tests
python engineering/verify_chinese_candidate.py --candidate /path/to/new-all-unit-candidate \
  --harness /path/to/finance-agent-harness --output /path/to/new-controlled-evidence
```

第一条检查 39 项原文对照、机器标识、来源摘要和定制副本，明确不等于 LLM 遵循性评测。
第三条对包含三个业务单元的新制品执行原生 Runtime 受控 HTTP 回归，并检查四份完整正文
确实出现在受控模型端收到的消息中；它不连接真实模型或银行，不评价自然语言工具选择质量。
新生成 Java/Python 使用同一份 Host 方法、Domain Pack 和公共工具边界；不要改旧制品来冒充新生成。

旧客户 SOP、发布版本、生成 Agent 和旧 Lock 不自动升级。已发布方法继续使用其冻结正文；
若用户要采用新默认中文方法，应重新创建副本或明确编辑并发布新版本。
旧派生草稿的来源变更按原检查拒绝，不静默改写内容；继续使用旧工程/资产快照或显式新建副本。

本轮仅候选代码，不合并、不部署。真实模型语言质量、业务 Owner 确认、真人独立操作另行验收。

## 首轮验证（2026-09-13，收支 0.1.2）

固定实现：Harness `a21745e47265bc53731505f076db46ae6574f60e`，Builder
`28960ca60ca47efa6e7e293dd2a0df17de5dafac`。后续提交仅补本文和可复跑捕获脚本，不改生成模板。
两份新制品均记录上述干净源码。Builder 770 通过、1 live Codex Smoke 跳过；
Harness 243 项中 236 通过、7 真实环境 Smoke 跳过。新 Host 的 Java 单测 31 项通过。
39 项来源约束对照通过；受控原生 Java 41、Python 58 项检查各复跑一次通过，
包括月份、缺数、冲突、撤权、会话隔离和零业务写入；四份方法正文均在模型端请求中实际观测。
不是实际模型质量或真实银行验收，不修改现有 Agent。

维护者证据目录：`/home/ubuntu/outputs/chinese-sop-ui-v01`。
Java Lock `9979d4cb437db75329bdd1e17de69bd5cb9ca50303761bde57bfed587130c845`；
Python Lock `db613275ae4c6e45dbb35ac040a93529d0967913c94142af2eb0c3cbc608466d`。
初次全量因未安装 esbuild 有 4 项失败，安装锁定依赖后通过；目录升级时提案示例旧摘要曾触发 Schema 拒绝，
同步示例后通过。失败见 HISTORY.md/builder-first.xml，不把早期失败隐藏为最终全绿。

## 来源修订

Harness `docs/readonly-method-provenance-v01.md` 为唯一详细来源表：按实际沉淀上游 SHA
`4652b28` 找到 `spending-analysis-journey@0.3.0`，保留原方法，而不是按另一个提交中未找到 Skill
推断从来不存在。0.1.3 保留原查询顺序、绝对月跳过对时、六个月周期；只替换工具接线和必要的固定演示月防错约束。
来源原文与 Apache-2.0 信息保留，原文 blob 和九项保留规则纳入校验。余额 Host 方法及财富共同事实方法
是项目新增方法，不宣称上游同名 Skill 已被同事验证。简单工具无需为了打包而创建独立 Skill。
上述首轮制品仍保留，不用新版本覆盖旧记录；0.1.3 需重新生成并独立记录。

## 来源修订后验收（收支 0.1.3）

已重新生成两份全单元候选，固定干净实现：Harness
`4d769f10792945820276e96ac84f3483ba4b5ced`，Builder
`1c0c632e2c753006fb6131c6d611110f38fddb43`。本文后续提交不改变实现。
Builder 全量重新执行 770 通过、1 跳过；Harness 243 项中 236 通过、7 Smoke 跳过。
39 项英文约束 + 9 项上游保留规则及原文 Git blob 校验通过。
Java 41、Python 58 项受控原生检查重新通过，四份完整正文在模型端消息中核对通过。
来源修订初次规则检查因把“再查进出”抄成“先查进出”失败；修正对照表后通过，原文未改。

证据为 `/home/ubuntu/outputs/chinese-sop-ui-v01/provenance/` 下各自的 build-report.json、
`java-checks/method-installation.json`、`python-checks/method-installation.json`。
Java Lock `43035460e50513681e83bb44083fee0d34c29570cf93dc8596f5560a8cdfe441`；
Python Lock `da32c3ccf333ea76fd56035857added91e8a90093c8476289675adb0f0eb7687`。
受控模型按测试协议选择工具，不宣称真实模型自然语言路由或专业质量通过。未部署，未合并，未升级旧 Agent。
