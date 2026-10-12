# Builder 冷启动与持续改进设计

## 目标

Builder 的冷启动不是从空白提示词生成一套 Agent，而是从已验证资产中选择一个最接近目标的组合，构造可运行 Candidate，再用证据闭环补齐差距。

```text
业务目标
  -> 选择参考 Solution
  -> 解析 Profile / Pack / Provider / Integration / Runtime / Eval
  -> 确定性预检
  -> 锁定资产闭包
  -> 差异装配 Candidate
  -> 离线验证
  -> 授权在线评测
  -> 诊断归因
  -> 修正对应资产并重建
```

## 冷启动机制

1. **参考 Solution**：财富面客和员工辅助是当前两个种子组合。新场景优先复用已验证组合，不从代码模板猜测业务结构。
2. **组合预检**：Schema 合法不等于 Builder 能装配。Builder 在下载和编译前检查 Profile、Pack、Provider 与 Host Adapter 是否形成受支持闭包；不支持的组合必须明确失败。
3. **分层缓存**：固定提交的 Runtime 模板和 Eval Pack在 Builder 级缓存复用；输出目录可以删除，不影响资产缓存。Candidate 只施加本轮 Solution 的差异层。
4. **分级验证**：依次执行输入校验、资产锁定、Harness 测试、Candidate 编译、资产测试、健康检查、冻结证据回放和授权在线评测。未执行在线评测时只能是 `passed_with_risk`。
5. **可复核输出**：`solution.yaml/json` 表达目标，`solution-lock.json` 记录实际资产版本与摘要，`build-report.json` 只记录真正执行的验证。

## 自优化闭环

Builder 不直接修改银行事实、权限规则或风险阈值。Evaluation 产生结构化 Bad Case 后，先将问题归因到资产所有者：

| 诊断结果 | 优先修正对象 |
|---|---|
| 意图或任务识别错误 | Runtime Prompt / Capability Catalog |
| 专业步骤、边界或表达不足 | Domain Pack / Skill |
| 字段映射、错误语义或幂等异常 | Integration / Provider Adapter |
| 数据缺失或证据不可用 | Provider / 测试环境 |
| 指标不能反映业务质量 | Eval Pack |
| 组合引用错误或版本不兼容 | Solution Manifest / Compatibility Metadata |

修正后必须在同一 Eval Pack 和同一授权数据条件下重建 Candidate，并比较旧、新 Lock。只有指标提升且既有门禁不回退，才形成新的资产版本；否则保留诊断和失败报告，不覆盖已验证版本。

## 可维护与扩展边界

- Harness 维护合同、Pack、Provider、Integration 和参考输入；Builder 维护解析、兼容性检查、装配、锁定与报告。
- Runtime 模板只提供宿主能力；业务差异通过薄 Host Adapter 和资产安装进入 Candidate。
- 当前两个财富组合仍由 Builder 内兼容性表显式支持。出现第三个成功组合后，应把该表迁移为 Harness 提供的机器可读装配元数据，避免在 Builder 中持续增加场景分支。
- 新 Domain Pack 至少需要：Pack 描述、Capability Catalog 映射、Provider、Integration/Host Binding、Eval Pack 和一套参考 Solution。缺任一项都不能报告为可交付组合。

## V1.1 实测结论

财富面客和财富经理访前两个 Candidate 均已基于 Harness `9ca56226` 和 OpenJiuwen 模板 `f89c9fb` 完成编译、定向测试、启动、健康检查及冻结评测回放。

固定资产缓存修复后，两套增量构建的资产解析从数十秒降至约 10 毫秒，完整构建约 88 秒。当前主要耗时已转为 Maven 编译和运行验证，而不是重复下载。

手机银行反诈转账的规划输入已经形成，但仍缺 Task 型多 Provider Host Adapter、反诈 Capability Catalog 绑定和独立 Eval Pack，因此当前应在预检阶段拒绝，不能用财富组合伪装成功。
