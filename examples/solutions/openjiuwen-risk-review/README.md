# OpenJiuwen 转账风险审核助手

这个 Solution 演示一个 Agent 通过 Finance Harness 同时装配账户 Provider 和反诈 Provider。

`solution.yaml` 对每项能力显式指定 Provider。装配器会验证能力支持关系，并在 `solution-lock.json` 中记录实际绑定。风险规则保留在银行反诈系统，审核方法保留在 Domain Pack，Harness Core 不包含具体风控业务逻辑。

## 两种装配

- `solution-without-domain.yaml`：只安装账户、反诈能力和对应 Provider，用于基线测试。
- `solution.yaml`：在相同能力与 Provider 上增加 `transfer-risk-review` Domain Pack。

两个 Candidate 使用同一 OpenJiuwen Runtime、模型和受控 Provider 数据，唯一业务变量是是否安装风险审核方法。可通过三个固定任务验证：

```text
demo/known-fraud-hit.txt
demo/no-known-hit.txt
demo/risk-service-unavailable.txt
```

## 单次运行

先设置 OpenAI 兼容模型环境变量，再执行：

```bash
mvn -pl examples/solutions/openjiuwen-risk-review -am package -DskipTests

java -jar examples/solutions/openjiuwen-risk-review/target/finance-example-openjiuwen-risk-review-0.1.0-SNAPSHOT.jar \
  --variant=with-domain \
  --case-id=RR-VALUE-001 \
  --prompt-file=examples/solutions/openjiuwen-risk-review/demo/known-fraud-hit.txt \
  --output-file=examples/solutions/openjiuwen-risk-review/target/demo-result.json
```

完整 A/B 评测由相邻的 `finance-skill-eval` 仓执行。它会实际启动左右 Candidate、匿名调用 Judge，并生成 JSON/HTML 报告。
