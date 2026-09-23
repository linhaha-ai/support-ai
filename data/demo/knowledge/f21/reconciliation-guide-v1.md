# 支付对账指南

> 脱敏模拟资料，仅用于 SupportAI F21 评测。文档版本：1.0，当前生效版本。

<!-- evidence:RECON-V1-FILE -->
## 对账文件

每日对账文件名为 `bill_yyyyMMdd.csv`，平台在次日 02:00 后生成，文件采用 UTF-8 编码并以逗号分隔。

<!-- evidence:RECON-V1-CHECKSUM -->
## 文件完整性

下载响应头 `X-File-Sha256` 是文件内容的 SHA-256；校验失败时必须重新下载，不能处理不完整文件。

<!-- evidence:RECON-V1-CUTOFF -->
## 账务日边界

对账账务日按 Asia/Shanghai 时区的自然日计算，日切边界为 00:00，不按商户服务器本地时区计算。

<!-- evidence:RECON-V1-DIFFERENCE -->
## 差异类型

差异分为平台单边、商户单边、金额不一致和状态不一致四类，每条差异必须保留平台订单号与商户订单号。

<!-- evidence:RECON-V1-PROCESS -->
## 差异处理顺序

处理差异时先核对支付回调与订单状态，再查询渠道流水，最后才创建调账工单；未经渠道结果确认不得直接调账。

<!-- evidence:RECON-V1-REFUND -->
## 退款对账

退款记录使用 `REFUND` 类型，原支付和退款分别成行；净额不能替代两条原始流水。

<!-- evidence:RECON-V1-RETENTION -->
## 保存期限

对账文件和差异处理记录至少保存 180 天，访问日志至少保存 90 天。

<!-- evidence:RECON-V1-REPROCESS -->
## 重跑约束

同一账务日重跑时使用账务日作为幂等键，覆盖前必须保留上一批次编号和汇总结果。

<!-- evidence:RECON-V1-SUMMARY -->
## 汇总核对

汇总必须分别核对交易笔数、支付总额、退款笔数和退款总额，四项都一致才能关闭对账任务。

<!-- evidence:RECON-V1-ESCALATION -->
## 差异升级

金额差异超过 1000 元或持续两个账务日未解决时升级为 P1 工单，并通知支付值班人与财务负责人。
