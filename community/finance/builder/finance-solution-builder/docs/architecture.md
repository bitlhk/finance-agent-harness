# 架构边界

创建页按 [模板与业务模块 V0.1](template-modules-v01.md) 的0.5契约组织：模板内选业务模块，再单选底层框架。
身份前提保留在模板及可信宿主中，不由用户标签授予权限。目录是场景扩展，不进入 Base 或 Harness Core；
模块映射到既有有限配方，目录快照、构建记录与实际安装 Lock 分开。旧0.4双入口 API 保持兼容。

Finance Solution Builder 不定义财富业务事实，也不替代 Finance Harness。它只负责把声明式 Solution 输入解析成可运行候选产物，并留下可复核证据。

```text
solution.yaml（期望组合）
        │
        ├── Finance Harness Schema / Pack / Provider / Integration
        ├── OpenJiuwen Java Runtime Template
        └── Evaluation Pack
        │
        ▼
solution-lock.json（实际解析到的资产闭包）
candidate/（装配后的 Java 候选产物）
build-report.json（构建与启动验证事实）
```

三个文件各自回答不同问题：

- `solution.yaml`：想构建什么，由 Solution 所有者维护；
- `solution-lock.json`：本次实际用了什么版本和内容摘要，由 Builder 确定性生成；
- `build-report.json`：哪些验证真正执行并通过，哪些因外部条件缺失未执行。

## V1 职责

Builder 负责 Schema 校验、资产解析、版本锁定、模板装配、Skill 安装、Provider/Integration 接线、编译、启动和健康检查。Finance Harness 负责统一合同、财富 Pack、Provider 与自动测试；部署环境负责凭据、网络和真实 Employee Agent 服务。

V1 不把不可访问的资产伪装成成功：只有显式传入 `--allow-template-fallback` 才允许使用仓内参考 Host，并在锁文件中标为 `fallback`。评测报告区分冻结证据回放与当前 Candidate 在线评测；前者通过不能替代后者。
