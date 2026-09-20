# 文档入口与维护规则

| 文档 | 用途 | 状态 |
| --- | --- | --- |
| [当前架构](current-architecture.md) | 组件职责、开发交付、实际运行接线 | 当前入口；明确固定实现版本 |
| [公开开发指导](public/developer-guide.md) | 从使用、修改方法到接口开发与验证 | V1.2；唯一公开教程正文 |
| [完整开发指导](developer-guide.md) | 内部设计和更多源码上下文 | 内部资料；含历史片段，不能直接匿名发布 |
| [最初 V1 架构](finance-agent-harness-architecture-v1.md) | 保留 9 月 1 日设计决策 | 历史，不代表当前 Builder/Runtime 支持范围 |
| `integration-acceptance/` 与 ADR | 某一阶段的约束、版本、验收 | 按文档日期和提交解读，不外推“全量可用” |

## 一份正文，多种阅读方式

公开内容源为 `docs/public/developer-guide.md`；版本、日期和实现基线位于 `docs/public/edition.json`。图示与已脱敏的真实 UI 截图一起受版本管理。在线、自包含离线 HTML 和 PDF 都由 `engineering/offline-docs/build-public.mjs` 生成；禁止在 release 目录手改正文。

```bash
npm ci --prefix engineering/offline-docs
node --test engineering/offline-docs/test-public-content.mjs engineering/offline-docs/test-public-diagrams.mjs engineering/offline-docs/public-link-policy.test.mjs
node engineering/offline-docs/build-public.mjs "$NEW_DOCS_OUTPUT"
node engineering/offline-docs/verify-public.mjs "$NEW_DOCS_OUTPUT"
```

输出目录必须尚不存在。浏览器检查需 Playwright/Chromium；可通过 `PLAYWRIGHT_MODULE`、`CHROMIUM_PATH` 指定维护者已安装路径。验证生成 PDF 和各尺寸截图，manifest 记录正文、图、截图、HTML 的摘要及 Git 来源。该命令不触发金融业务。

发布前先固定文档提交，检查匿名页面不含内部路径、凭据或客户材料，再按既有网关流程切换指南软链接；内部完整版与工作台登录保持不变。只发布阅读制品，保留旧 release 以回滚；不恢复旧业务数据。

新功能更新规范、教程、截图和对应检查，历史测试数字保持固定版本语境。发布报告必须写清是否已合入主线，不能把某文档分支上线冒充 main 已统一。真人独立接入与 AI 演练分别记录。
