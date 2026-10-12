# AgentScope Java QUERY 接入候选

本接入复用现有 Finance Studio、Candidate Backend SPI、业务资产和银行 Provider。
它不是第二个工作台。正式可用状态取决于实际制品资格和部署验收；添加目录选项不代表已经安装或验证。

## 当前边界

| 项目 | 范围 |
| --- | --- |
| Runtime | AgentScope Java 2.0.3，JDK 21；原生 ReActAgent 与工具，不经过 OpenJiuwen 执行引擎 |
| 试点资产 | 原余额只读包；原收支包及 cashflow-customer-self-service 方法 |
| 安装单位 | 完整能力包；收支包安装两项查询能力，不静默扩展部分选择 |
| 执行 | QUERY；不支持 TASK、转账确认恢复、SDK 自动重试、备用模型或 token 流 |
| 对话与记录 | 复用现有页面、会话隔离、AG-UI 缓冲交付、历史及快照权限 |
| 已发布方法 | 固定版本引用；导入包的 Runtime 要求重新核验，不修改旧包以扩大兼容范围 |
| 标准 ZIP 交换 | 现有页面方法试跑与导出仍限定 OpenJiuwen Java；AgentScope 方法 ZIP 的试跑、导出与重新导入尚未接通 |
| 旧 Agent | 保持原 Lock 和运行制品，不随目录或模板更新自动升级 |

模型可以选择已安装工具，但方法正文不是强制工作流。工具返回成功也不自动证明复合目标已全部完成，仍需检查目标对应的事实、月份和缺失范围。

## 开发与装配职责

- 业务方法、Capability、领域合同和 Provider 仍归 Harness 资产及实现；本 Backend 不实现余额或收支业务。
- `managed_runtime_support.py` 读取受管 Runtime/Host 声明；`managed_selection.py` 绑定实际 Host、Profile、Provider 与方法版本。
- `candidate_backend/agentscope_java.py` 使用已有 Registry 和生命周期合同，构建前执行真实制品准入。
- `agentscope_service.py` 生成独立宿主工程和来源投影；`managed_launcher.py` 校验锁定文件及显式 classpath。
- `templates/agentscope-query-service` 只提供原生 Runtime 接线，公共身份、历史、HTTP 和会话机制复用共享 Host 源码。
- 生成业务组件 ZIP 仍使用 Harness Packager；生成可运行 Agent 才进入本构建流程。

页面发送 `finance-workbench.selection` 0.10，包括准确的 Runtime、Host 和 Profile 引用。
服务端解析后冻结绑定。调用方不能上传任意主类、命令、模板路径或实授权限。
同一个 Profile 匹配多个 Host 时必须明确选择，不使用“取第一项”回退。

## 四种不同的记录

1. **装配计划**：解析后的能力、方法、Provider 和兼容范围，不证明工具已经注册。
2. **构建 Lock**：实际文件、执行制品与生成来源；编译成功不代表实际模型调用成功。
3. **安装 Lock**：创建原生会话后取得的真实 Runtime 安装结果，按会话和实例版本关联。
4. **运行记录**：实际授权、模型、工具、Provider 及业务结果；正文读取权限与 trace 权限分开。

模板组成摘要与采集器身份摘要不是同一种摘要：

- `runtimeBinding.managedTemplate.digest` 和 `managedTemplateDigest`：冻结后的组合模板文件内容。
- `hostTemplateDigest`：用于采集器资格的源文件清单摘要。

两者分别核验，不互相覆盖。模型“客户端准备请求”快照不能当作 HTTP 已发送证明。
脱敏正文保留 `FILTERED` 状态，不称作未处理的完整原文。

## 准入与运行

受管环境必须提供准确的原生及 Host 资格文件、摘要和已资格验证的准入制品。
缺少、错版本、错摘要或 REQUIRED 观测不满足时，构建前拒绝；不能用其他 Runtime 的资格替代。
候选开发环境可通过 `FH_CANDIDATE_RUNTIME_IDS` 明确开启受管候选选择，但这不会关闭准入或产生 VERIFIED 结果。

环境只绑定模型连接、模拟银行连接和可信授权来源，不把密钥打入业务包或 Agent Lock。
每轮和关键执行边界重新核验实授权限。客户范围来自宿主及银行身份，不来自页面能力勾选或模型参数。
新 Runtime 不复用旧 Java/Python Agent 的编译制品或试跑种子。

### 页面使用

从“自定义客户 Agent”空白方案开始，在业务资产中选择余额或收支，点击“用于创建 Agent”，
再选择 AgentScope Java。此按钮向当前方案添加能力，不清空此前选择。
若原手机银行模板仍选着未经本轮验证的财富能力，页面会保留选择并拒绝该组合；
应由用户明确调整，不能静默删除能力或换回其他 Runtime。

原收支专业方法及私有方法的 `local-read-only` 描述可在当前 QUERY 范围内装配。
这是方法兼容标签，不是权限；仍要求 CUSTOMER、既有用途和当前客户授权。
导入包若明确限制 Runtime，必须遵循原声明，不因标签兼容自动扩大支持范围。

原收支目录资产在 AgentScope 上的验证，不等于现有方法编辑器已提供 AgentScope 试跑。
内部 Backend 的原生方法装配证据也不能代替标准 ZIP 的绑定确认与发布门禁。
旧 OpenJiuwen ZIP 可继续原样导出、导入；明确排除 AgentScope 的版本会被拒绝，
不通过扩大全局 Runtime 白名单或修改旧发布清单来开放它。

### 受限调用结果

Candidate Backend SPI 的 `response_ref` 指向本次实际解析回复，`evidence_refs` 单独保存
调用元数据。不能把文件路径或元数据回执当成模型回答。结果仅允许可信 owner 在
捕获与查看授权均符合要求时读取，并核对实例、来源和摘要。

SPI 调用结果保存还要求管理员显式提供以下有界策略；普通工作台对话没有被替换为此存储：

- `FH_CANDIDATE_RESPONSE_MAX_FILES`：单任务目录文件上限，至少 5。
- `FH_CANDIDATE_RESPONSE_MAX_BYTES`：单任务目录字节上限，须能容纳元数据与正文。
- `FH_CANDIDATE_RESPONSE_RETENTION_SECONDS`：保留时间，最多 31 天。

缺少策略或捕获授权时，在调用前拒绝。尝试调用后的失败不自动重试；
`sessionAttempted/chatAttempted` 和 `sessionResultCode/chatResultCode` 只记录 Jobs 调用及其结果，
不是 HTTP 已发送证明。Jobs 可能本地拒绝，也可能在请求返回后因实例更换返回 409。
历史读取不重新调用模型或银行，也不为缺失正文补造内容。

## 复跑入口

从 Builder `finance-solution-builder` 目录执行。环境变量和资格必须来自本次固定制品的交付记录，不复制旧资格摘要。

```bash
PYTHONPATH=src:../base/src python -m pytest tests/test_managed_runtime_selection.py tests/test_agentscope_backend.py

FH_RUN_AGENTSCOPE_BACKEND=1 FH_AGENTSCOPE_BACKEND_OUTPUT=/absolute/new-output \
  PYTHONPATH=src:../base/src python -m pytest tests/test_agentscope_backend_integration.py

python engineering/verify_agentscope_workbench.py \
  --harness /absolute/fixed-harness \
  --output /absolute/new-browser-output \
  --chromium /absolute/chromium
```

第一项是定向代码回归；第二项使用真实编译、准入和 Runtime，以及隔离的受控模型/银行。
第三项须预先配置现有受管模型与资格，使用真实浏览器和仅本地合成银行，生成新的 Agent。
它从业务资产选择开始，核对构建、启动、原生查询、实际安装、运行记录、正文读取和停止。
三个入口不能互相替代。未提供真实模型时明确阻塞，不以脚本模型冒充 LIVE。

真人独立接入、业务专业质量和生产银行 IAM 验收继续单独记录。
