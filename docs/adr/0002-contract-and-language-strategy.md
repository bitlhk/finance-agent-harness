# ADR-0002：合同与语言策略

状态：`Proposed`

日期：2026-09-01

## Context

当前应用和 Runtime 涉及三种主要语言：

- Employee Agent Host：TypeScript；
- Mobile Banking：Java；
- OpenJiuwen 企业版 Runtime Bridge：Python。

如果选择某一种语言实现中央 Core 并要求其他应用远程调用，会在 V1 引入不必要的服务化复杂度。如果三种语言分别定义对象，又会产生语义漂移。

## Decision

1. Canonical Contract 使用 JSON Schema 2020-12；
2. 远程 Provider API 使用 OpenAPI 3.1；
3. 标准化摘要使用 RFC 8785 JSON Canonicalization Scheme + SHA-256；
4. TypeScript 提供 V1 Core 参考实现和 EA SDK；
5. Java 提供一等 SDK 和手机银行 Host Adapter；
6. Python 只提供 OpenJiuwen Runtime Bridge 和必要生成类型；
7. 所有语言消费同一 Schema Bundle 和 Digest Vector；
8. V1 不建立中央 Core HTTP 服务；
9. Promptfoo、OpenJiuwen 或具体框架对象不得成为公共 Contract。

## Consequences

- Contract 先于实现；
- 跨语言差异由 Conformance Fixture 暴露；
- TypeScript 参考实现不具有修改合同的特权；
- Java SDK 与 TypeScript 必须在稳定合同发布时同步；
- Python Bridge 保持薄，减少第三套业务规则；
- 未来可以在不改变合同的情况下增加 CLI 或远程 Service Hosting。

## Versioning

- Schema 使用语义版本；
- 新增可选字段为 Minor；
- 删除字段、修改含义或收紧枚举为 Major；
- Patch 只允许文档、实现或不改变接受集合的修正；
- `schemaVersion` 必须进入 Evidence 和 SolutionLock；
- Stable Schema 不允许未经 ADR 的破坏性修改。
