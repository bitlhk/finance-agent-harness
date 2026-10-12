# 业务组件打包与复用 V0.1

## 开发者最终会得到什么

工作台把一份金融专业方法与已准入能力绑定成版本化业务组件包。发布后可以直接带入现有
“创建 Agent”页面，也可以下载 ZIP 到另一个受控环境重新导入。创建页、运行宿主和业务页面
不因每个新方法重复开发。

## 三种开发路径

### 1. 组合已有能力

选择已发布组件包，组合模型、Runtime 和 Provider 连接，执行构建前检查。开发者主要维护
场景选择和验证案例，不修改 Core、公共宿主或前端业务分支。

### 2. 修改专业方法

1. 在“业务资产”选择“导入 Skill”，上传 `SKILL.md` 或受限 ZIP。
2. 查看“声明与引用检查”、正文引用和源文件行号。显式但未知的工具显示“待接入”，不会因为同名而可执行；该检查不是完整语义依赖分析。
3. 明确选择能力，保存草稿，添加正常、缺数和拒绝案例。
4. 运行结构校验与隔离受控试用。真实模型预览是可选项，不能代替运行边界测试。
5. 查看工作台根据实际试跑和资产目录生成的绑定计划，核对工具含义、输入输出合同、能力包版本、Runtime 与执行协议，发布不可变版本。
6. 点击“使用此包创建 Agent”或下载组件包。

发布版本改变需要新版本号。草稿正文、资源、绑定或案例变化后，旧试用结果失效。旧 Agent
继续使用原 Lock，不自动升级。

### 3. 新增银行能力

业务组件包不携带 Provider 代码。开发者需在 Harness 扩展中：

1. 定义或复用稳定的 Capability 合同；
2. 实现 `FinanceProvider`/受管理 Provider 工厂，并映射银行权威状态；
3. 在能力包和资产目录登记版本、兼容 Runtime、Provider 与观测说明；
4. 通过 Conformance、权限负例和 Provider 故障测试；
5. 安装到目标环境后，再让 Skill 绑定该能力。

这一路径需要代码评审。上传 ZIP 不会在工作台执行任意 Provider 或脚本代码。

## 可重跑入口

维护者可以先用现有财富事实方法验证完整交付链路。命令会在全新的私有数据目录中完成：
页面等价的 Skill 上传与编辑、结构校验、来源受控试用与发布、ZIP 导出、另一 owner 重导入、
再次试用和发布，并只使用目标 owner 的发布版本完成 Java Agent 装配与执行。

```bash
cd community/finance/builder/finance-solution-builder
PYTHONPATH=src:../base/src uv run --project . \
  python engineering/verify_business_package_publish.py \
  --harness /path/to/finance-agent-harness \
  --data /tmp/finance-business-package-acceptance
```

成功结果写入 `business-package-acceptance.json`。其中真实模型、真实银行授权和真人独立接入
分别记录，未执行时保持 `NOT_RUN`，不能由受控试用代替。

V0.1 的打包器是 Builder 进程内执行的外部代码，因此 `--harness` 必须指向
`https://github.com/bitlhk/finance-agent-harness` 的干净 Git 工作树，并固定在
`b8f15ff91343cd1db367eb181287d6b69b29ccbd`。Builder 会在加载前同时校验远端地址、
完整提交号、已跟踪文件状态、打包器摘要和包 Schema 摘要；任一项不一致都会停止导入或
导出。验收报告会记录这组已验证身份，便于后续复现。

## Builder 与 Skill Eval 分工

- Builder：草稿、目标环境依赖匹配、受控运行、不可变发布、目录选择与 Agent 构建；打包和 ZIP 校验调用 Harness 的版本化 Packager，不复制包规范实现。
- Finance Skill Eval：Skill 迁移、候选版本比较、专业质量评测和优化。
- Harness：包合同、能力合同、Provider SPI、执行约束及公共验证。

Builder 的静态分析只定位声明和正文中的工具/资源。它不评价专业质量。需要质量评测时，
先在 Skill Eval 生成并复核候选，再将导出的 `SKILL.md` 导入此流程；评测结果只有明确关联到
当前内容摘要和版本时，才可进入发布摘要。

## 当前候选边界

- V0.1 只准入已有 Java 只读能力的业务组件包。
- 一个业务组件包可绑定多个已有能力包；当前创建页每个 Agent 只选择一个专业方法组件包。
  多个专业方法包的冲突合并尚未实现，不能通过重复安装绕过。
- 当前服务器是单管理员 owner 隔离，不代表团队账号和项目权限已经完成。
- 受控试用证明安装和执行边界，不自动证明真实授权、业务口径或专业效果。
