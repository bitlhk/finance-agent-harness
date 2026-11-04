# 原金融材料处理接线 v1（候选）

本切片把固定上游的 PDF 表格、凭证提取与定位接到原生 openJiuwen Java 0.1.15。它提供共同工程能力，不迁移完整场景，不代表材料真实、审核通过、真实视觉质量或生产部署资格。

## 职责与数据归属

- 原材料服务：保存不可变原文件、原返回、处理记录和追加修正，复用原算法及 Prompt。
- 材料领域：`packages/java/financial-material` 提供文件、位置、处理单元、原值和修正语义；四份 JSON Schema 位于 `contracts/json-schema/financial-material/`。
- Provider：`providers/financial-material` 只开放 `financial.material.extract`、`financial.material.locate`。当前主体、客户、任务、会话、服务与模式来自私有连接。工具参数不能指定文件系统路径、客户、端点、凭据或脚本。
- Integrator：`integrations/financial-material-java` 复用既有 Scenario Extension、工具安装、方法安装及 Agent/Runner。Core、Execution 与原 openJiuwen Integrator SPI 不变。
- Host：绑定当前授权，提交文件、读取原文、发起修正和取消。UI只提交用户意图；Builder只在构建时装配，不进入业务调用链。

## 文件与执行流程

1. 获准 Host 上传 PDF/PNG/JPEG。服务检查魔数、大小、页数/像素和任务限额，产生独立文件引用；同内容的两个客户文件也不是共享读取权限。
2. 上传选中当前任务文件。Provider用当前私有凭据读取 `/v1/material/current`，再发起有限 `/v1/material/extract`；模型不决定客户或服务器路径。固定算法、依赖和授权不因换文件而改变。
3. 提取记录保留原返回、方法/引擎版本、文件摘要和原文位置。选择修订号防止处理期间换文件后仍展示旧结果；空表格只记未确认。
4. 原文、Range、历史和修正读取均再次核对当前授权。取消使在途结果失效；PDF子进程在等待时检查取消并由所属调用回收。
5. 人工修正采用期望修订号和请求幂等键，只追加原值、新值、可信操作者、时间和理由，不覆盖原返回，也不代替审批。

## 私有部署输入

Provider绑定文件 `financial-material-bindings.v1` 固定主体/客户/会话/任务、提取模式及原工具发布摘要，使用 `FH_MATERIAL_BINDINGS` 与 `FH_MATERIAL_BINDINGS_SHA256` 准入。文档本身在运行期由获准上传选择。

Host私有 `material-host-authority.json` 保存owner、来源服务地址、绑定摘要及有期限的租约，记录generation与范围。来源服务单独维护 `financial-material-authority.v1`；凭据不得进入构建资产或模型。参考仅绑定回环接口。

服务调用包括原文提交、当前选择、有限提取、原文/原返回读取、修正和本任务取消。上传和修正需要对应范围；取消只影响该租约自己的执行，不是银行交易。服务不开放原MCP的Prompt同步、激活或任意模块注册入口。

## 状态、位置与兼容

COMPLETED仅表示选定处理单元取得结果；PARTIAL映射为需要人工核对，BLOCKED/FAILED/CANCELED不会变为成功事实。来源空集合无法证明解析完整。内部页码零起点、界面一基准，保留原基准；合法区域采用PERMILLE/TOP_LEFT；没有坐标就只定位到页。

本切片使用独立领域版本与显式Profile，旧JSON/文本 MaterialSet、旧构建与历史不自动升级。目录和原工具实际入口保持固定来源，不重写原算法。凭证使用原 `CredentialExtractor.extract(params)` 类入口；定位客户端的尝试次数为1，避免原客户端把0解释成不发请求。

## 验证与前置

工程验证使用合成文件、真实Runtime、原函数与回环固定模型，分别记录构建、安装、执行和原返回。两个最小消费者为 fintable-extract、smart-audit；实际视觉固定响应只证明协议。

PyMuPDF不在可安装/交付锁中，目录许可未明确时保持BLOCKED。真实视觉端点、材料/Prompt传输与质量未授权时不调用、不标通过。完整 Feature 资格与代码、关联PR、合并、发布分别记录。

参考持久化采用SQLite和私有目录，保留每任务限额；生产IAM、总存储配额、备份/清理策略、Windows权限与生产负载需要单独验证，不能据此声称完成。
