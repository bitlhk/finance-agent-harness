# Java Core

Java 是简化版 V1 的唯一 Core 实现语言。

首版包含：

- 四个合同的 Java 类型和严格校验；
- `FinanceProvider` SPI；
- Query/Task 调用辅助；
- `EvidenceReceiptV1` 生成；
- Contract 与 Adapter 测试。

Java Core 不依赖 OpenJiuwen；OpenJiuwen 类型只允许出现在 `integrations/openjiuwen-java`。

当前实现位于 `core` 模块，使用 Java 21。仓库根目录执行 `mvn test` 可运行合同验证。
