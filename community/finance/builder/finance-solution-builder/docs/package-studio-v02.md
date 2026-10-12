# 业务资产图形化与开发接入 V0.2

## 从哪里开始

在工作台打开 **业务资产**。目录卡片的“查看业务资产”打开三个页签：

1. **组成与依赖**：查看本包内容、引用依赖和平台提供；点击组件查看使用、声明、合同和实现关系。
2. **文件与修改**：查看此版本真实文件、用途和修改位置。已发布包只读，不能覆盖。
3. **检查与生成**：区分结构、兼容、环境、执行试跑与专业质量；查找引用此资产的 Agent。

这是组成图，不是工作流。没有独立 Skill 的查询包仍然可以使用；未读取到正文不代表宿主提供了方法。
通用结果外壳不能替代完整业务字段合同。Provider 制品未读取时只展示引用，不补造 JAR 摘要。

## 一次完整使用

1. 选择“下载开发样例”，解压后阅读顶层 README 和 source-lock.json。该下载是固定源码开发包，**不是可导入的业务组件 ZIP**。
2. 修改方法样例，或准备自己的 SKILL.md，点击“导入 Skill”。读取声明与引用检查，手工绑定已准入能力。
3. 保存草稿，在“检查与试用”补充正常及负向案例，执行检查、受控试跑。需要模型预览时选择受管模型；专业质量仍需人工确认。
4. 在“发布使用”核对工具、合同、执行要求和验证范围，发布不可变版本。
5. 点击“查看该版本的文件与依赖”，核对包内文件；可下载标准业务 ZIP，或“使用此版本创建 Agent”。
6. Builder 固定该版本构建。启动后核对实际安装 Lock，再在运行记录查看实际调用、身份检查、来源和缺口。

目录内容不等于已发布 ZIP；业务包不固定唯一安装 Lock。同一版本可以被多个 Agent 使用，安装证据分别属于具体实例。
修改 Skill 后必须重新检查、试跑和发布；新增工具依赖不能继承旧验证结论。旧 Agent 不自动升级。

## 三种开发边界

| 需求 | 修改位置 | 验证及准入 |
| --- | --- | --- |
| 定制专业方法 | 页面草稿或 SKILL.md、案例与参考资料 | 检查绑定、受控试跑、模型预览、发布版本 |
| 组合已有能力 | 场景配置和已准入包引用 | 依赖、Runtime 兼容及安装检查；组合不复制工具实现 |
| 新银行接口或能力 | 外部 Provider 工程、合同与声明 | 编译、正常与负向测试、固定制品与目录准入，再被 Builder 选择 |

下载包共享现有 Harness 工程，包含固定依赖、真实实现和可执行命令。第一个样例不依赖 Builder 源码目录。
不是提供新编译器或在线 IDE。Java/Python 编译与依赖工具负责代码制品，Harness Packager 负责业务 ZIP，Builder 生成 Agent，Assembler 核验安装。

## 事实来源与兼容

- 现有 `finance-asset-contents.v0.1` 增加可选 `composition: asset-composition.v0.2`，旧客户端原字段保留。
- 发布包正文仍通过唯一 Harness Packager 获取并校验；目录资产读取 Git 固定提交，并用 Git clean filter 核对工作区文件的对象 ID（允许 CRLF 等等价检出），不读取用户传入路径。
- 关系只来自声明的工具与能力绑定；没有 `allowed-tools` 时不把自然语言提及冒充已声明关系。
- 依赖版本不唯一时保留未解析引用，不随便选一个 Provider；中文名称来自已有目录/元数据，不在页面增加业务 ID 分支。
- `/api/v1/assets/developer-kit` 沿现有登录与 owner 路由保护提供源码白名单归档，不包含工作区未提交内容、凭据、运行目录或依赖缓存。
- 当前模板支持范围不变，不扩大 Python TASK；客户权限、银行连接与密钥仍由目标环境提供。
- Pack 中声明权限不等于实授，声明事件不等于实现埋点，SOP 步骤不是强制策略。

## 验收入口

```bash
PYTHONPATH=.:src:../base/src pytest tests/test_asset_composition.py tests/test_developer_kit.py tests/test_asset_contents.py
npm ci
npm run build:ui
node engineering/verify_package_studio.mjs http://127.0.0.1:PORT /tmp/package-ui-evidence
```

浏览器需要 Playwright 和 Chromium，可用 `PLAYWRIGHT_MODULE`、`CHROMIUM_PATH` 指定现有安装。
完整真实模型试跑使用 `engineering/verify_package_studio_lifecycle.py --harness PATH --output NEW_DIRECTORY --chromium CHROMIUM_PATH`，
仅在隔离环境设置 `FH_MODEL_URL/FH_MODEL_KEY/FH_MODEL_NAME`；该命令创建合成银行并消耗模型额度。
它通过真实页面导入、发布、下载，然后用同一发布版本创建 Java/Python Agent；记录模型响应和银行读取，结束后停止所建实例。
此自动化不替代专业质量评审或非原作者真人独立接入。

异步操作开始时立即禁用重复及后续动作，完成后恢复；公网延迟不能导致用户点击“运行受控检查”被静默忽略。
