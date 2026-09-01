# ADR-0001：Finance Agent Harness 架构基线

状态：`Proposed`

日期：2026-09-01

## Context

岗位智能体、手机银行、Builder 和 Skill Eval 已经分别实现部分金融 Context、动作控制、确认、证据、专业 Skill 和评测能力。继续在各应用内部独立演进会产生重复且不兼容的实现。

同时，直接建设独立中央服务会过早引入网络、鉴权、部署和版本兼容复杂度，尚不能证明当前抽象真正跨应用。

## Decision

1. Finance Agent Harness 不替代 Agent Runtime；
2. Application 继续直接消费 Agent Runtime；
3. Harness 通过 Runtime Integration 注入金融语义和控制；
4. Harness 通过 Provider Contract / Adapter 连接银行系统；
5. V1 采用 Runtime-neutral Contract + Embedded SDK / Thin Bridge，不建中央服务；
6. V1 以 EA 和手机银行作为两个真实 Testbed；
7. Runtime Plane 与 Engineering Plane 分离；
8. Builder 在构建期运行，不进入生产热路径；
9. Finance Skill Eval 保持独立组件；
10. 公共能力需要两个消费者或等价 Conformance 证明。

## Consequences

正向影响：

- 保留现有应用体验和 Runtime 能力；
- 可以逐步抽取，不需要一次迁移全部代码；
- TS、Java 和 Python 可以消费同一合同；
- 先验证合同，再决定是否服务化；
- Builder、Skill Eval 和未来 Runtime 有清晰接入位置。

代价：

- 需要维护跨语言 SDK 和 Conformance；
- V1 会存在 EA / Mobile Host Adapter；
- 需要严格控制 Core、Profile、Domain Pack 和 Application 的边界；
- 初期不能依赖中央服务解决一致性，必须用合同和 Fixture 保证。

## Alternatives

### 直接从 EA 抽成公共服务

拒绝。EA 不是唯一应用，且会把 EA 身份、Skill、Workspace 和 Trace 形状带入 Core。

### 仅发布一套 TypeScript 库

拒绝。手机银行以 Java 为主，无法证明跨语言和跨应用。

### 先让 Builder 定义 Solution 模型

拒绝。现有 Builder 仍带有 Scheduling / Intent 和 OpenJiuwen / JiuwenSwarm 形状，应作为 Contract 消费者和压力测试者。

### V1 直接支持多个 Runtime

拒绝。先用 OpenJiuwen 两种运行形态证明 Cross-Application 和 Cross-Profile，V2 再证明 Cross-Runtime。
