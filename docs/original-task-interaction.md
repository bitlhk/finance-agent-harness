# 受管原托管任务与人工复核

本功能是独立装配入口 `Builder 的 task_interaction_delivery(...)`，Profile 为 `original-task-review-managed`。它不改变现有通用只读装配，不自动开启新的业务写入。具体资格以同一源码与制品的验收记录为准。

## 谁保存什么

- 原服务保存业务指令、资料及真实状态。Workshop 发布固定上游代码闭包，工作进程实际调用原函数。
- 受管原服务按客户/任务隔离原文件，保存调用前意图和原操作回执，复查当前权限及对象/资料版本。
- Harness 提供任务交互领域合同、Provider 和 Java 接线，复用既有 TaskCoordination/ProviderTaskExecution。
- 独立 Host 复用 HostTaskState 保存一次性确认与恢复句柄；部署方私有授权文件不放进制品。
- Builder 只核对来源、生成并构建交付物，运行时不依赖 Builder 进程。页面不拥有授权，也不驱动第二套业务状态机。

## 业务步骤

查询 → 认领 → 补充已登记资料与手续费 → 提交复核 → 人工通过/拒绝 → 单独确认执行。

每步先选择操作，Host 保存待办并生成一次性确认；用户核对任务、参数与资料版本后确认，服务端再次校验，才调用原业务函数。复核通过仅对应原状态 `approved`，只有原执行返回并查证 `executed` 才显示原任务已执行。

模型只安装当前任务的查询工具，不能自行确认、认领、复核或执行。参考界面通过 `FH_TASK_INTERACTION_REFERENCE_UI=1` 显式开启，并仅允许受控本地模型模式；入口票据保存在部署方私有目录 `task-ui.json`，使用一次后换成 HttpOnly/SameSite 会话票据，Host 密钥不进入页面。

## 接线与版本

Builder 使用已固定 SourceAnchor 解析原 `dryrun/aas-system/app.py` 与 requirements，派生方法明确标记为过程说明。原工具发布物包含原 Git 字节；方法来源收据按 UTF-8/LF 固定，两种摘要各按对应规范核对。

`FH_TASK_BINDINGS` 与 `FH_TASK_BINDINGS_SHA256` 固定部署方绑定。Host 私有 `task-authority.json` 绑定当前操作者、客户、原任务、服务端点和当前凭据，不由模型选择。快照与每次操作都复查当前授权，历史和材料读取也一样。

Harness 输入的 `custody.task.action` 只有 `action`、`expectedVersion`、`materialVersion`、`parameters`。原服务动作协议另外包含 schemaVersion、taskRef、requestId、executionConfirmed；这些字段由可信接线补齐，不能由浏览器提交一个 `confirmed=true` 代替真实 Host 确认。

## 取消、重连和未知结果

停止本地等待、放弃本次确认、取消原业务是三个不同操作。前两个不宣称撤销外部业务。页面关闭后可以按原句柄查询同一操作，不新建原任务；待确认操作恢复时仍须核对当前资料。

调用前持久写入意图。同一请求重试返回同一回执；原调用后响应丢失时保留 UNKNOWN，只能查证，禁止自动重发写入。单宿主锁仅约束受管入口，不能保证外部写入、多 Host 或真实银行事务的幂等性。原 execute 是演示服务的状态转换，不是实际银行划款。

## 验证入口和边界

以下三个交付/业务资格命令在 Agent Store 的 community/finance/builder/finance-solution-builder 目录执行。Harness 本仓的固定原工具准备入口是 engineering/prepare_original_task_tool.py（参数 --workspace 与 --worker-python）；准备成功后由 engineering/run_task_interaction_checks.py --inputs 执行输入核对。它们不要求调用真实模型或银行。

- 原工具：`python -m managed_original_task_service.qualification --inputs <固定输入> --report <新报告>`；实际原函数与受管路径比较固定案例。
- 独立制品：`python engineering/check_task_interaction_delivery.py --config <固定配置> --workspace <新目录> --report <报告>`；缺来源/工具链返回阻断，构建失败保留阶段与日志。
- 真实业务前置：`python engineering/qualify_task_interaction_business.py --prerequisites <前置记录> --report <报告>`；本批只检查缺项，真实业务与生产资格保持 BLOCKED。

首次装配需要固定原服务发布物、原 Python 精确依赖环境、Harness 源码选择、受信任务配置及 Java/Maven 工具链。测试使用合成指令和本地固定响应模型；不能据此宣布原邮件/PDF/浏览器全链、专业对账质量、手机银行外部 Agent 或生产系统已经完成。
