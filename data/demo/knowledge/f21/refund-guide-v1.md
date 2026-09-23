# 退款处理指南

> 脱敏模拟资料，仅用于 SupportAI F21 评测。文档版本：1.0，当前生效版本。

<!-- evidence:REFUND-V1-CREATE -->
## 发起退款

退款接口为 `POST /api/v1/refunds`，必填字段是 `merchantRefundNo`、`orderNo`、`refundAmount` 和 `reason`。

<!-- evidence:REFUND-V1-QUERY -->
## 查询退款

按商户退款号查询使用 `GET /api/v1/refunds/{merchantRefundNo}`，查询接口允许每 2 秒一次，频率超限返回 HTTP 429。

<!-- evidence:REFUND-V1-STATUS -->
## 退款状态

退款状态依次为 `PROCESSING`、`SUCCEEDED` 或 `FAILED`；只有 `FAILED` 且错误被标记为可重试时才能重新提交。

<!-- evidence:REFUND-V1-ERRORS -->
## 退款错误码

`REFUND-3001` 表示可退余额不足，`REFUND-3002` 表示原订单未支付成功，`REFUND-3003` 表示退款请求重复，`REFUND-3004` 表示渠道暂时不可用。

<!-- evidence:REFUND-V1-PARTIAL -->
## 部分退款

一个订单可以多次部分退款，但累计退款金额不能超过订单实付金额，每次退款必须使用不同的 `merchantRefundNo`。

<!-- evidence:REFUND-V1-RETRY -->
## 失败重试

渠道暂时不可用时由平台自动重试 3 次，间隔为 1 分钟、5 分钟和 15 分钟；业务方不要在自动重试期间创建新的退款单。

<!-- evidence:REFUND-V1-MANUAL -->
## 人工复核

退款超过 30 分钟仍为 `PROCESSING` 时，应先查询渠道流水，再由财务人员在工单中登记核对结果，不能直接把状态改为成功。

<!-- evidence:REFUND-V1-CALLBACK -->
## 退款通知

退款终态通过 `POST /callbacks/refund` 通知，通知同样使用 `X-Pay-Sign` 验签，并以 `refundId` 作为幂等键。

<!-- evidence:REFUND-V1-TIMEOUT -->
## 调用超时

发起退款请求超时后必须先按 `merchantRefundNo` 查询，确认不存在才能重试，禁止直接更换退款号重复提交。

<!-- evidence:REFUND-V1-AUDIT -->
## 审计字段

退款审计记录必须包含操作人、订单号、商户退款号、退款金额、原因、结果和时间，用户敏感信息应脱敏。
