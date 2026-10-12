# 手机银行反诈转账构建输入

本目录记录第三套 Solution 构建输入的目标资产闭包。它当前是可校验的规划输入，尚不能生成可验收 Candidate。

Builder 会在下载和编译前明确拒绝该组合，直到以下三项补齐：

1. 同时路由 `fraud-mining` Query 与 `non-ai-banking` Task 的 Host Adapter；
2. `fraud.transfer-risk.assess` 的 Candidate Capability Catalog 绑定；
3. 独立的反诈转账 Eval Pack。

不得以财富管理 Eval Pack 代替反诈评测，也不得只因 Candidate 能编译就报告该 Solution 已完成。
