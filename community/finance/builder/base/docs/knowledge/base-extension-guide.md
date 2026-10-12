# Base 与场景扩展指南

## 1. 权威顺序

已接受的 Requirement/Feature 和共享契约决定需求；Base L2 决定公共逻辑边界；本文说明如何应用这些边界。
场景源码是当前实现事实，但不能单独把一个场景的字段变成公共标准。

## 2. 判断能力放在哪里

按顺序回答：

1. 去掉 Intent、Scheduling 等场景名称后，语义是否仍完整？否：留在场景扩展。
2. 是否已有稳定输入、输出、错误和生命周期？否：先在场景中验证。
3. 是否至少有两个真实消费者，或一个真实消费者加可执行的未来扩展契约？否：默认留在场景扩展。
4. 是否明确维护者、版本和兼容策略？否：不得进入公共核心。
5. 提升后是否迫使已有场景改变序列化结果？是：先做兼容评审和迁移方案。

全部满足时，才提出公共契约变更；否则使用 `ExtensionModel` 的有类型、带版本扩展。

## 3. 扩展步骤

1. 在具体 Builder 中定义继承 `ExtensionModel` 的场景模型，使用稳定的 `extension_type` 和
   `schema_version`；
2. 通过组合对象或 Protocol 实现 DataFactory/Evaluation 扩展点，不复制 Base 源码；
3. 添加场景契约测试，证明公共模型、错误和用户语义保持兼容；
4. 新增第二消费者后，再比较两个场景的真实语义，而不是比较字段名；
5. 如果要提升为公共能力，更新 Feature、Base L2、兼容说明和所有消费者测试。

优先从包入口导入公共能力，例如 `finance_builder_base.evaluation.evaluate_rules` 和
`finance_builder_base.runtime.require_path_within`。不要从 `engine.py`、`workspace.py` 导入私有名称，
也不要在 Base 已是必需依赖时保留“Base 不存在”的回退实现。

## 4. 正反例

- 正例：工作区相对路径、越界防护、Manifest、候选动作是领域无关能力，可进入 Base。
- 正例：Intent 分类标签、Scheduling 时区策略分别使用有类型扩展，二者都复用同一个 Builder Task。
- 反例：因为两个 `models.py` 都有 `name` 字段，就把 `name` 及其业务规则整体搬进 Base。
- 反例：在 Scheduling 中复制一份路径安全函数，再用相同函数名宣称兼容。
- 反例：让 Base 根据当前状态固定选择 Developer 或 Evaluation；该决策属于 Orchestrator。

## 5. 专项纪律

- DataFactory：公共层定义 GenerationSpec、DeliveryPackage、ProcessingUnit 和明确的 `unsupported`；默认组合
  是参考实现，场景或 Orchestrator 可以重排相同构件。
- Evaluation：公共层固定快照、执行、指标、规则和报告顺序；场景扩展调用、指标、closed/open expected 与
  Bad Case 维度，不能绕开“证据不足不得达标”和“取消保留部分结果”。
- Runtime：只补 Builder 对 openJiuwen Runtime 的增量能力；Manifest/hash 固定运行身份并标记漂移，不作为
  用户修改产物的准入门槛。宿主目录校验使用 `resolve_directory`，已有根目录下的路径使用
  `require_path_within`；场景可保留薄错误适配，但不得复制路径算法。
- 安全：公共消息只使用逻辑标识、相对路径或受控 URL；不得出现宿主绝对路径、凭据或真实客户身份。

## 6. AI Coding 自检

提交修改前，AI Coding 工具应说明：改动属于 Base 还是场景扩展、复用了哪个公共接口、影响哪些消费者、
如何保持兼容、运行了哪些契约测试。还应比较迁移前后固定输入的输出与错误，并证明纯函数重复执行不会改变
结果或工作区。无法回答任一项时，停止并请求维护者评审。
