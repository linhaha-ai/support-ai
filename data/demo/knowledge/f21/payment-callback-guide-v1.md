# 支付回调处理手册

> 脱敏模拟资料，仅用于 SupportAI F21 评测。文档版本：1.0，已由 2.0 取代。

<!-- evidence:PAY-CB-V1-ENDPOINT -->
## 回调入口

旧版支付回调入口为 `POST /openapi/v1/payment/callback`，商户系统必须在 5 秒内返回字符串 `SUCCESS`。

<!-- evidence:PAY-CB-V1-SIGNATURE -->
## 旧版签名

1.0 版签名串依次拼接 `merchantId`、`orderNo`、`amount`、`timestamp`，使用商户密钥计算 HMAC-SHA256。

<!-- evidence:PAY-CB-V1-RETRY -->
## 旧版重试

1.0 版在未收到成功确认时最多重试 5 次，间隔依次为 10 秒、30 秒、60 秒、300 秒和 600 秒。

<!-- evidence:PAY-CB-V1-WINDOW -->
## 旧版时间窗口

1.0 版允许回调时间戳与服务端时间相差不超过 5 分钟。

<!-- evidence:PAY-CB-V1-CONFIG -->
## 旧版地址配置

1.0 版回调地址在商户控制台的“支付设置”页面维护，保存后只校验 HTTPS 格式。
