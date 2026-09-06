# fraud-mining v0.1 兼容补丁

该补丁生成一个派生镜像，不修改原始 `fraud-mining:v0.1`：

```bash
docker build -t fraud-mining:harness-v0.2 .
```

修复两件事：

1. 单样本预测先执行既有特征工程，再调用羲和模型；
2. 只向检查点发送需要的模型字段，并清理 `NaN` 等不可序列化值；
3. 模型超时、输入错误或推理失败时返回明确的 5xx，不再写入或返回
   `pred_label=0, score=0.0`。

这只是特定服务版本的兼容代码，属于 Provider 周边，不进入 Harness Core。
