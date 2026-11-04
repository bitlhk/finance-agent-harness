# 原生材料接线 0.1.0 候选

复用 openJiuwen Java 0.1.15 的 Agent/Runner、原生工具安装和 Harness Provider，不实现新的 Runtime。受管 Host 通过 ServiceLoader 选择 `financial-material-processing.v1`。

Host私有状态 `material-host-authority.json` 固定owner、当前主体/客户/任务/会话、代次、期限、文档引用和Provider绑定摘要；凭据不进入模型参数。调用前、模型调用前、工具返回和结果披露复核当前授权。撤权、换代、取消和关闭不会显示旧成功。

工程测试使用真实Runtime、原PDF工具及回环固定模型。视觉固定响应只验证协议，不能证明真实图片质量。目录许可和真实视觉条件保留独立BLOCKED。

## 原生测试前置与 CI

原生测试必须取得四项显式 JVM 参数；缺项会失败，不能靠跳过测试获得通过：

| 参数 | 含义 |
|---|---|
| `fh.python` | 材料工具独立的 Python 3.12 解释器，按普通 `uv.lock` 安装 |
| `fh.materialServiceSource` | 固定 Agent Store 提交中的材料服务源码目录 |
| `fh.originalMaterialSource` | 固定上游提交中的原 PDF/凭证工具源码目录 |
| `fh.materialEvidence` | 本轮原生执行及模型请求证据的输出目录 |

CI 在根 `mvn verify` 前运行 `engineering/prepare_original_material_tool.py`，复用既有固定提交获取机制。
Agent Store 固定为 `b948d8060c5410cb657c720b6c9bd32d7ab211e8`，原场景固定为
`0e158eee225c698774aa75671c944624cd36550b`；独立 Python 3.12 工具环境不替换合同测试的 Python 3.10/3.12 矩阵。
准备脚本使用 `uv sync --frozen --no-dev --group test`，包含原锁文件中生成合成 PDF 所需的 ReportLab 测试依赖。
验证原表格模块、测试 PDF 生成器及发布闭包后才导出四项路径，失败即停止。
普通环境不安装 PyMuPDF，不读取模型 Key，不调用真实模型或银行。

本地可将四项路径传给同一原生测试，或先运行准备脚本生成独立环境和路径文件；不要直接把输出文件当 shell 脚本执行。

```bash
python engineering/prepare_original_material_tool.py --workspace /absolute/new-material-ci --github-env /absolute/material-ci-paths.txt
mvn -B -ntp -pl integrations/financial-material-java -am \
  -Dtest=MaterialEvidenceContractTest,FinancialMaterialProviderTest,MaterialProcessingNativeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "-Dfh.python=/absolute/new-material-ci/builder/community/finance/workshop/tools/financial-material-processing/.venv/bin/python" \
  "-Dfh.materialServiceSource=/absolute/new-material-ci/builder/community/finance/workshop/tools/financial-material-processing" \
  "-Dfh.originalMaterialSource=/absolute/new-material-ci/upstream" \
  "-Dfh.materialEvidence=/absolute/new-material-ci/native-evidence" test
```

Windows 使用 `.venv/Scripts/python.exe`。CI 保留 `preparation.json`、原生返回、请求与来源日志；准备通过和真实 Runtime 测试通过分别取证。
