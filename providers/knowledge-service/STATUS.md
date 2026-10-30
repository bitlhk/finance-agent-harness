# Knowledge Provider 最小装配探针：H0 完成，H1 阻塞

日期：2026-10-30。独立候选分支 `feat/knowledge-provider-probe`。禁止将本报告解读为已上线或完成 Agent 装配。

## 固定基线

| 对象 | 已核实基线 |
|---|---|
| Harness origin/main | `e911c71bdd3404f450bf775f32ebe028f90fb475` |
| Builder origin/finance | `9d33295f74ff663c16579febd120b586c791280f` |
| OpenJiuwen Java 只读 Host | `templates/local-readonly-service`，SDK 0.1.15 |
| AgentScope Java 只读 Host | `templates/agentscope-query-service`，SDK 2.0.3 |

两份 Host 声明的实际 SHA、Runtime integration 坐标与拒绝结果保存在 `evidence/builder-admission.json`。
这是当前拉取远端基线中的可检查代码，不是对线上部署版本的声明；本轮没有读取或改动生产服务。

## 已实现及实测

- 新增知识只读包两个 QUERY 工具、ServiceLoader 工厂、一个通用 Provider、合成双产品 Manifest 和 HTTP 服务 fixture。
- 工厂配置摘要及连接摘要由既有 `createBound` 校验；原有 Host grant 与 SourceSession 仍须同时满足。
- 70项 Core 回归 + 21项 Provider 定向测试通过；真实回环 HTTP，未调用模型。
- 证据查询、两产品复用、36/37个月边界与缺失月份 UNKNOWN（业务数据层）均验证。
- 来源撤权、HTTP200内业务403、Manifest错配、requestId错配、过大响应、越权参数、缺授权/
  缺scope/会话过期、缺可信上下文、返回前撤权、非法日期/布尔月份、配置及连接锁摘要不符均拒绝。
- 真实查询保留 requestId、traceId、receiptId、Manifest、固定业务日期、Host绑定摘要和观测事件。
  观测是本地内存 sink 的实际记录，**不是部署采集器资格通过**。
- 31 Schema / 131 fixtures与状态探针通过；组件声明校验通过（37份声明）。
- Jar实际构建、摘要及单元测试计数保存在 `evidence/h0-summary.json`。
- 原始脱敏HTTP回执在 `evidence/http-records.json`，未记录 Authorization、token 或真实身份。

## H1 真实阻塞

使用上述未修改 Builder 的 `ComponentAssembly.select()`，选择本分支新增能力包和Provider。
两个 Host 均返回 **ASSEMBLY_PROVIDER_CONNECTION_MISMATCH**：
Host 只接受 synthetic-bank-http.v1，候选 Provider 要求 knowledge-http.v0.1。

不仅是名字不同：`templates/local-readonly-service/.../ReadOnlyProviders.java` 从
`BankAccess` 的 bankSessionId/customerRef 构造 SourceSession，不能直接用作知识服务授权。
仅修改 connectionKinds 列表会造成虚假准入。本分支没有这样修改，也未绕过观测或安装校验。

因此没有进入 Agent 编译/安装/启动，没有安装 Lock，没有 Agent 界面查询。
H0 的配置/连接摘要不是安装 Lock；未知 capability 被拒绝不等价于真实 Agent 的“未安装工具”验收。
后者、真实 Host 观测资格、Agent UI查询及回收仍属于 H1 待完成项。

## 最小后续适配（待评审）

1. 在 Builder 的受管 Host 扩展边界增加独立知识连接配置及授权供应器，明确 knowledge-binding.v0.1 特征。
   从可信服务端获得主体、用途和业务日期，固定产品/Manifest；不复制银行session作为知识凭据。
2. 仅将新 Provider 与依赖制品加入该 Host 的受管 classpath；保留当前银行 Host 不变。
   将配置/连接摘要纳入既有装配证据和真正安装 Lock，并完成对应采集器资格验证。
3. 通过准入后，用现有 Builder 创建隔离 Agent，在已有 Agent 页面查询；验证工具未安装、授权撤回、
   版本错配与终止回收。这些不能用本次21项接口测试替代。

## 改动边界

业务代码仅在 providers/knowledge-service 和 capability-packs/knowledge-readonly。
公共文件只有 Maven 模块登记、资产目录候选登记/修订号，以及引用目录摘要的示例提案同步。
未改 Core、Runtime 接线、授权、现有 Host、Builder 页面或其他 Codex worktree。
目录登记不是发布或准入；候选名称标明未完成 Host 准入。

## 后续复跑

参见 CONTRACT.md。用 record_evidence.py 生成当前测试回执；勿覆盖历史 M1 评分。
本候选单独 Draft PR，不合并、不部署；Knowledge Factory UI 与 M1 语义质量分别验收。
