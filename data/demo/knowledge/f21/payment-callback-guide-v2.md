# 支付回调处理手册

> 脱敏模拟资料，仅用于 SupportAI F21 评测。文档版本：2.0，当前生效版本。

<!-- evidence:PAY-CB-V2-ENDPOINT -->
## 当前回调入口

2.0 版支付回调入口为 `POST /openapi/v2/payment/callback`，商户系统必须在 3 秒内返回字符串 `SUCCESS`。

<!-- evidence:PAY-CB-V2-INVESTIGATION -->
## 回调成功但订单未更新

排查顺序固定为：先确认回调接收日志和 HTTP 200，再核对签名校验结果，然后查询支付流水，最后检查订单事件是否进入消息队列并被消费。

<!-- evidence:PAY-CB-V2-SIGNATURE -->
## 签名字段

签名原文按字段名升序包含 `amount`、`merchantId`、`nonce`、`orderNo`、`timestamp`，空值字段不得参与，最终使用当前商户密钥计算 HMAC-SHA256，并与请求头 `X-Pay-Sign` 比较。

<!-- evidence:PAY-CB-V2-IDEMPOTENCY -->
## 回调幂等

回调以 `transactionId` 作为幂等键；同一交易号重复到达时返回首次处理结果，不得重复更新订单、发放权益或发送业务事件。

<!-- evidence:PAY-CB-V2-RETRY -->
## 回调重试

2.0 版在未收到成功确认时最多重试 6 次，间隔依次为 10 秒、30 秒、60 秒、180 秒、600 秒和 1800 秒；业务处理完成后应返回字符串 `SUCCESS`。

<!-- evidence:PAY-CB-V2-CONFIG -->
## 回调地址配置

回调地址在商户控制台的“开发配置 > 支付回调”中维护；保存后先执行 HTTPS 连通性检测，再发送一笔签名测试通知，只有测试返回 `SUCCESS` 才能启用。

<!-- evidence:PAY-CB-V2-ERRORS -->
## 回调错误码

`PAY-CB-1001` 表示签名不匹配，`PAY-CB-1002` 表示时间戳超出 3 分钟窗口，`PAY-CB-1003` 表示重复交易已按幂等结果处理，`PAY-CB-1004` 表示回调金额与订单应付金额不一致。

<!-- evidence:PAY-CB-V2-ACK -->
## 确认响应

只有订单状态和业务事件都提交成功后才能返回 `SUCCESS`；临时故障应返回 HTTP 503，参数永久无效应返回 HTTP 400，不能先确认再异步丢弃。

<!-- evidence:PAY-CB-V2-OBSERVABILITY -->
## 回调观测字段

回调日志必须记录 `traceId`、`merchantId`、`orderNo`、`transactionId`、验签结果和处理耗时，但不得记录商户密钥、完整签名或用户银行卡号。

<!-- evidence:PAY-CB-V2-SECURITY -->
## 来源校验

生产回调必须同时通过 HTTPS、签名和时间窗口校验；IP 白名单只作为附加控制，不能替代签名校验。

<!-- evidence:PAY-CB-V2-AMOUNT -->
## 金额校验

回调金额以分为单位并使用整数传输；处理前必须同时核对币种和应付金额，任何一项不一致都不得把订单改为已支付。
