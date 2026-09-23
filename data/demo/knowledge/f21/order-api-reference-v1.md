# 订单接口参考

> 脱敏模拟资料，仅用于 SupportAI F21 评测。文档版本：1.0，当前生效版本。

<!-- evidence:ORDER-V1-CREATE -->
## 创建订单

创建订单接口为 `POST /api/v1/orders`，必填字段是 `merchantId`、`merchantOrderNo`、`amount`、`currency` 和 `notifyUrl`。

<!-- evidence:ORDER-V1-QUERY -->
## 查询订单

按商户订单号查询使用 `GET /api/v1/orders/{merchantOrderNo}`；响应中的 `status` 是订单当前状态，`paidAt` 仅在支付成功后返回。

<!-- evidence:ORDER-V1-STATUS -->
## 订单状态

正常支付状态流转为 `WAIT_PAY -> PAYING -> PAID`；`WAIT_PAY` 可以转为 `CLOSED`，`PAID` 不能回退为 `WAIT_PAY`。

<!-- evidence:ORDER-V1-IDEMPOTENCY -->
## 创建幂等

创建请求头 `X-Idempotency-Key` 在同一商户下 24 小时内唯一；相同键和相同请求体返回原订单，相同键但请求体不同返回 HTTP 409。

<!-- evidence:ORDER-V1-ERRORS -->
## 订单错误码

`ORDER-2001` 表示商户订单号重复，`ORDER-2002` 表示金额不是正整数，`ORDER-2003` 表示订单不存在，`ORDER-2004` 表示当前状态不允许该操作。

<!-- evidence:ORDER-V1-CLOSE -->
## 关闭订单

关闭订单使用 `POST /api/v1/orders/{merchantOrderNo}/close`；只有 `WAIT_PAY` 状态可以关闭，正在支付的订单应先查询最终支付结果。

<!-- evidence:ORDER-V1-CURRENCY -->
## 币种与金额

`amount` 使用最小货币单位的正整数，人民币的 `currency` 固定为 `CNY`，接口不接受小数金额或负数金额。

<!-- evidence:ORDER-V1-EXPIRY -->
## 支付有效期

订单默认 30 分钟未支付自动关闭；调用方可传 `expireMinutes`，允许范围为 5 到 120 分钟。

<!-- evidence:ORDER-V1-PAGINATION -->
## 订单列表

订单列表接口为 `GET /api/v1/orders`，`page` 从 1 开始，`pageSize` 范围为 1 到 100，并可按 `status` 和创建时间区间过滤。

<!-- evidence:ORDER-V1-WEBHOOK -->
## 通知关联

支付回调中的 `orderNo` 对应订单响应里的平台订单号，`merchantOrderNo` 是商户侧编号，两者不能互换。
