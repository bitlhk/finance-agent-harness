# Knowledge QUERY Probe v0.1

隔离候选，不是已准入生产 Provider。仅使用合成数据，复用 `ManagedProviderFactoryV01`、
`ManagedProviderFactoriesV01.createBound`、`BoundFinanceProvider`、原有观测包装器及结果封装。
不修改 Core 授权语义，不创建 Runtime。标准输出的 `COMPLETED` 仅指查询完成；
规则 `UNKNOWN` 放在 `data.decision`，不伪造需要 task reference 的顶层 `UNKNOWN`。

## 固定绑定

Host 提供 `knowledge-binding.v0.1` 文件，字段仅有 schemaVersion、productId、manifest（64位摘要）、
businessDate（严格 ISO 日期）。配置文件 SHA-256 进入工厂 configurationDigests；
连接 URI/kind/timeout 进入连接摘要。当前只接受本机 `/query`，拒绝跳转和模型指定 URL。
这不是支持任意知识源的网络策略。正式扩展 HTTPS/远程地址需另行审查，不在本探针开放。

SourceSession 来自 Host 当前授权供应器，包含主体、会话、过期时间和凭据；不来自工具参数。
请求用途来自可信 FinanceContext。业务日期来自每个实例锁定的 Host 配置，不接受对话覆盖。
合成日期不应被描述为真实银行日期服务。当前绑定只能由 Host 重建实例后改变，不随模型切换。

## 工具合同

`knowledge.evidence.search`: `{question: string(1..1000)}`。
`knowledge.rule.evaluate`: `{months?: integer(0..1200)}`，仅为合成36个月上限演示。
未知字段一律拒绝；不接收 productId、Manifest、URL、身份、scopes、业务日期。
两个工具均为 QUERY；范围分别是 knowledge:read 和 knowledge:read + knowledge:rule。
能力包定义、Provider 声明及 Maven 制品是同一候选，不能只导入工具描述就标为已安装。

## 上游合同

POST /query 的 Host-owned JSON: schemaVersion=knowledge-query.v0.1、capabilityId、productId、manifest、
businessDate、requestId、traceId、principalId、subjectRef、sessionRef、purpose、arguments。
Authorization 是 SourceSession 凭据；不得归档其明文。合成服务在检索前验证身份与固定绑定。
HTTP 200 不代表业务成功，必须 code 为整数0；403业务码、缺失 receipt、版本或关联标识错配均拒绝。

成功响应：code、decision、evidence[]、receipt。receipt 包含 knowledge-receipt.v0.1、receiptId、
productId、manifest、businessDate、requestId、traceId。原证据带 sourceId、sourceVersion、locator 和 quote。
返回前再次读取 SourceSession；撤权或过期不披露结果。正式知识服务还必须实施真实来源级授权，
本探针的合成授权不证明 Knowledge Core / RAGFlow 的真实来源权限已接通。

## H0 与 H1

H0 测试通过真实 ServiceLoader 发现工厂，使用受管构造、锁摘要比较和 Host 授权包装器调用真实回环HTTP。
测试 fixture 的配置摘要和连接摘要是受管构造输入，**不是 Agent 安装 Lock**。
H1 必须由现有 Builder 通过准入后生成 Agent、受管安装、归档真实 Lock，并经 Agent 界面调用。
`probe_builder.py` 执行未改动 Builder 的实际准入解析；失败即停止，不人工添加 Host feature 制造成功。

后续最小接线需要一个经评审的知识 Host/连接声明和来源授权供应器，固定实例绑定、受管制品
classpath 与观测资格；通过后才执行构建、安装、UI调用及回收。不能复用银行 source-session 冒充知识授权。

## 复跑

```bash
mvn -B -Dmaven.repo.local=/tmp/knowledge-probe-m2 -pl providers/knowledge-service -am test package
python3 providers/knowledge-service/probe_builder.py --builder /path/to/pinned-builder --output /tmp/knowledge-builder.json
```

合成 HTTP 服务代码在 src/test 的 SyntheticKnowledgeService，端口随机绑定127.0.0.1，测试结束关闭。
无常驻合成服务、无真实凭据、无模型调用，不会启动或停止现有 Agent。
