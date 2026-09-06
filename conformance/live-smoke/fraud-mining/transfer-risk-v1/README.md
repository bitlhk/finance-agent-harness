# fraud-mining transfer risk live smoke

通过 SSH 隧道访问部署在反诈服务器上的 Harness 兼容服务：

```bash
ssh -N -L 18001:127.0.0.1:18001 \
  -i ~/.ssh/KeyPair-ECS-03.pem root@113.44.127.99

FRAUD_MINING_LIVE=true \
FRAUD_MINING_BASE_URL=http://127.0.0.1:18001 \
mvn -q -pl providers/fraud-mining -am test
```

测试读取已知涉诈账号 `202276804025`，要求返回 `BLOCK`，并在模块的
`target/live-smoke/evidence-receipt.json` 生成标准证据回执。
