# 业务资产第一阶段

本轮实际结果与待验收项见 [候选验证报告](business-assets-phase1-verification.md)。
后续体验整改、发布方法的真实生成与对话结果见 [体验整改验收](business-assets-ux-verification.md)；前一轮缺配置状态保留为历史记录。

## 使用入口

体验整改：详情提供版本化中文阅读说明和完整 SOP 原文，说明按源文件摘要核对，
不作为可执行译文。源文件变化后说明标为待复核。显示名统一为余额查询、财富概览、收支分析；
收支两项能力仍成组装配并按需调用，技术 ID 不变。

定制页分为编辑方法、检查与试用、发布使用；默认阅读，可切换编辑原文。
可修改步骤、解释、输出和文本资源，不能修改工具实现或授权机制。
首页提供导入 SOP 与我的定制方法；归档是列表筛选。
业务详情内导入保留服务端基础绑定，并单独保存导入来源与原始文件，不伪造为内置正文。
通用导入仍不自动归属业务单元，不支持完整 Package 上传。

创建页集中显示模型、银行和能力组合阻断项，连接管理只读，由管理员服务端配置。
返回保留已选业务及方法。保存草稿与 Java 受控检查不依赖真实模型配置。

业务资产显示余额、财富、收支三个业务单元；收支包含两个只读能力，但只安装一个包。
“我的方法与归档”保留全部私有 SOP，包括未关联业务单元的旧方法。
详情四区为业务概览、业务方法、工具与依赖、验证与版本。
“用于创建 Agent”只增加兼容业务，不启动构建；已有选择保留。不同私有方法替换需确认。

创建页的模块详情可打开同一个业务资产；两页使用 catalog.directory 投影及固定 recipe。
目录检查不是环境就绪：实际创建继续走现有 preflight；安装状态必须看制品 Lock。
Provider、工具、授权范围与观测配置只读，网页不能编辑执行代码或授予权限。

## 定制步骤

1. 选择业务单元，点击“定制业务方法”，只产生浏览器预览。
2. 编辑方法/文本资源与案例，点击保存才生成私有草稿。
3. 校验依赖、运行 Java 受控检查。当前不提供 Python 方法试跑；Python 生成后的执行证据单列。
4. 发布不可变版本，返回创建页携带 methodRef 和能力范围；不自动构建。
5. 实际生成/运行后核对方法引用、安装 Lock 与工具调用。受控加载通过不等于模型专业质量通过。

## 合同与兼容

公共派生定义位于 Harness finance-method-derivation.v0.1.schema.json。
新业务派生 source.kind=BUSINESS_DERIVED，绑定能力包与复制来源、版本、内容摘要、
目录 revision、目标 scope；来源由宿主生成，不接受前端直接提交 provenance。
保存、试跑、试跑完成、发布、新构建与队列执行前重新核对当前基础。
方法内容发生变化使旧 trial 失效；基础变化需重新从业务目录定制并验证。
旧方法可读/编辑/归档，显示基础关系未记录，不补写旧 release 或重算历史摘要。
新派生 release.v0.2 保持旧 methodRef 三字段；旧 release.v0.1 原样消费。
已有 Agent 保留原 Lock/方法，不被草稿或目录升级改变。
新版本仍只选一个私有方法，不合并多方法，不导入任意可执行包。

## 开发与测试

实现属于 Finance Solution Builder 扩展，不修改 Base/Runtime。有限目录检查仅用于现有
local-read-only recipe，不改变 AssetCatalog 对其他 Host 的单 Provider 边界。
测试目录含 Harness 候选 Schema/catalog 的受控快照；升级时与锁定 Harness 文件核对，
不得以快照测试替代实际 Harness/Assembler 检查。

```bash
PYTHONPATH=src:../base/src python -m pytest tests/test_business_assets.py tests/test_directory.py tests/test_method_assets.py -q
npm ci --ignore-scripts
node browser/build-ui.mjs
PYTHONPATH=src:../base/src python -m finance_solution_builder.workbench --harness-repo <matched-harness> --data-dir <private-candidate-dir> --port 18578
```

需要包含本轮登记和派生 Schema 的 Harness。只读用例可用受控源运行；没有真实模型配置
不能标为真实模型验收。转账、通用包上传、线上部署及真人独立接入不属于本轮自动交付。
