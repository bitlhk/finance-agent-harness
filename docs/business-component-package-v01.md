# 业务组件包 V0.1

业务组件包用于移交一份已经绑定既有 Finance Harness 能力的方法资产。它不是 JAR、Provider
制品、权限凭证或 Agent 运行镜像。

## 包内内容

ZIP 根目录必须包含 `finance-business-package.json`，其余文件只允许：

- `skill/SKILL.md`：专业方法；
- `references/`：方法明确引用的文本或结构化资料；
- `cases/*.json`：代表性验证案例；
- `validation-summary.json`：对该候选版本和环境有效的验证摘要。

清单冻结能力包版本、工具名、QUERY/TASK 类型、输入输出合同引用、执行与观测要求。
Provider 实现、模型配置、银行连接、密钥、客户数据、模型请求/响应正文和安装 Lock 不进入包。

## 生命周期

1. 导入或编辑 Skill，只形成维护者私有草稿。
2. 分析工具声明和正文引用；未知工具保持待接入。
3. 维护者核对工具语义、合同、版本与协议，运行候选校验和隔离试用。
4. 发布不可变版本；同版本不能覆盖。
5. 同环境发布后进入受管理目录。跨环境导入重新核验摘要、路径、大小和依赖；缺依赖只保存为未准入草稿。
6. Builder 使用精确版本创建新 Agent，并在实际安装时生成 Lock。旧 Agent 不自动升级。

`requiredScopes` 只是能力要求。客户当前是否授权、主体是否匹配和权限是否撤销，仍由宿主与
银行权威系统在运行时检查。

## 公开工具

```bash
python engineering/business_package.py build \
  --source /path/to/package-source \
  --output /tmp/business-component.zip

python engineering/business_package.py inspect \
  --package /tmp/business-component.zip
```

`package-source.json` 使用 `finance-business-component-package-source.v0.1`，字段与发布清单一致，
但 `files` 是待打包相对路径数组。工具计算每个文件摘要并生成确定性 ZIP。它不运行 Skill 中的
脚本，也不解析网络引用。V0.1 限制 ZIP 与解压后业务文件总量不超过 512 KiB、单文件不超过
256 KiB，页面导入和公开工具采用相同上限。

Builder 和其他受信任装配器应调用本工具模块的 `build` / `read`，或调用上述 CLI；不得另行实现
ZIP 路径、摘要和清单一致性规则。目标环境对 Runtime、协议、能力版本及合同引用的准入检查仍由
装配器负责，因为这些条件取决于该环境实际安装的资产。

V0.1 是候选合同。完成三个真实业务单元的发布、重导入与装配验收后，再评审是否冻结。
