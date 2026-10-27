# 阶段评测与验收产物目录

## 2026-09-30 M1—M7 回归

基于 `aca30bb` 加工作区修复，使用本机 `qwen3.5:9b` / `bge-m3:567m`。逐项判定及剩余前置见 [M1—M7 回归处理记录](../../doc/验收记录/M1-M7回归处理记录.md)。完整验收仍为 **NOT_PASSED**：云端、真实重排序及人工质量尚未签核，TEST-039 范围修订未获批准。功能通过不替代回答依据与语义质量审查。

| 产物 | 内容与边界 |
| --- | --- |
| [本轮索引](regression-index-20260930.json) | 全部 regression 产物和生产变更摘要；保留失败及不同批次身份，不累计通过数 |
| [M1—M4 r3](regression-m1-m4-20260930-r3.json) | 67/67；8 次真实 RAG，包含实际空白文档解析失败、私有资料正反向、三轮来源及版本更新 |
| [v8 工程](regression-engineering-20260930-v8.json) | 普通后端 419/419；数据库 86/86、前端 98/98、评测 29/29 各自明确引用既有批次 |
| [最终 v9 工程](regression-engineering-20260930-v9.json)、[执行采集脚本](regression-engineering-capture-script.mjs) | 79 套普通后端 421/421；数据库/前端/评测沿用批次，首次 PowerShell 参数错误也保留 |
| [最终 v9 助手 r5](regression-m6-20260930-r5.json)、[浏览器 r4](regression-browser-20260930-r4.json) | 助手 14/14（15 runs：11完成、3预期失败、1取消）；页面 13/13，分别独立计数 |
| [最终运行配置](regression-runtime-final-20260930.json) | v9 实际镜像及配置指纹，临时 TopK24 已撤下，采用默认 6 |
| [v8 助手 r4](regression-m6-20260930-r4.json) | 14/14 功能检查；15 次运行含预期失败及取消，不把这些运行状态全部改成 COMPLETED |
| [v8 浏览器 r3](regression-browser-20260930-r3.json) | 13/13 页面功能检查，真实来源、工单新鲜性及退出；附同名前缀截图 |
| [v8 助手语义问题](regression-assistant-quality-v8-20260930.json) | 原文没有指数间隔，回答补造参数；时间戳来自工具默认序列化。独立质量证据不改写功能结果 |
| [v9 助手辅助复核](regression-assistant-quality-v9-20260930.json) | 5 个真实样本的参数/时间改善；仍存在操作条件扩大，非正式人审，不能宣称全面消除无依据扩展 |
| [开发集 TopK6](regression-development-topk6-20260930.json)、[TopK24](regression-development-topk24-20260930.json) | 各 48 COMPLETED + 12 NOT_RUN；同源同镜像，仅检索数量变化 |
| [TopK 客观比较](regression-topk-comparison-20260930.json) | 完整预期证据入模 37/40→40/40，Chat Token 50,766→126,731，p95 5,132→9,363 ms；不自动批准默认参数或人工质量 |
| [开发集辅助复核](regression-development-review-20260930.json) | 9 道问题题及 8 道不足资料题的原文/回答摘录与指纹，保留未修复、改善和退步，人工字段未填 |
| [TopK6 运行指纹](regression-runtime-topk6-20260930.json)、[TopK24 运行指纹](regression-runtime-topk24-20260930.json) | 只输出经过筛选的运行摘要，不输出容器环境变量或密钥原文 |
| [索引脚本](regression-index-script.mjs)、[比较脚本](regression-topk-comparison-script.mjs)、[运行采集脚本](regression-runtime-capture-script.mjs) | 此轮实际使用源码；拒绝覆盖已有输出，文件指纹可核对 |

`regression-m1-m4-*-r1/r2`、`regression-m6-*-r1/r2`、`regression-browser-*-r1/r2` 均保留原失败事实；阶段记录区分产品缺陷、采集器错误与语义问题。`regression-m6-*-r3` 等中间成功批次也保留，不能用成功片段拼成另一个整批结果。旧工程 XML 路径可能已被后续测试覆盖，历史 JSON 中的摘要是当时报告指纹，不将当前文件追认成旧快照。

TopK24 实验未采用：召回改善同时出现 DEV-004/021/027/053 语义退步，DEV-014 首查步骤仍错。默认 TopK6 的 DEV-002/013/033 漏召回及回答质量仍未闭合，完整结论不只等待形式签字。v8 开发集比较与最终 v9 助手回归有各自源码和镜像身份；v9 仅修改助手，不声称重跑了完整开发集。

## M7 补验

本轮以 `30dfcc3` 为基线，保持线上 F32 应用镜像与原评测证据不变。完整结论见 [M7 阶段复核](../../doc/验收记录/M7阶段复核.md)：功能/工程通过项与人工质量、外部模型等未闭合项分别记录，不能把本目录的局部 PASSED 当作完整 M7 已通过。

| 产物 | 内容与边界 |
| --- | --- |
| [M7 总索引与指纹](m7-artifact-index.json) | 完整结论 NOT_PASSED；各补验批次分开计数，归档文件与截图 SHA-256 |
| [后端与数据库回归](m7-backend-db.json) | 普通 382/382；数据库 85/85 与单独升级兼容 1/1，共 86/86；含新增 DEV-019/039 时序，保留新报告时间及哈希 |
| [前端与评测工具](m7-engineering.json) | 前端 97/97 与构建；评测 29/29、100 题及指纹校验；不替代浏览器与人审 |
| [运行环境核对](m7-runtime.json) | 同一应用镜像、健康 UP、V15/16 表、11 份 F32 证据哈希；外部服务只记录配置存在性 |
| [旧引用生命周期](m7-lifecycle.json) | 最终 r3 完整 16/16；同一旧来源在新版、停用/恢复、撤权/恢复、删除后的行为 |
| [浏览器完整批次](m7-browser.json) | 无头 Edge 真实页面，10 项通过、助手组合 1 项失败；剩余两项未执行，不是整批通过 |
| [助手失败诊断](m7-assistant-failure.json)、[DB 二次核对](m7-browser-failure-detail.json) | 两工具成功、3 来源，但必需工单未被引用，最终 AGENT_INVALID_CITATION / REQUIRED_TICKET_NOT_CITED；180 秒页面等待不是业务超时 |
| [只读页面补验](m7-browser-review.json)、[稳定原文截图](m7-browser-review-source-stable.png) | 5/5，来源/稳定截图/退出/页面错误检查；零模型请求，不改原完整批次失败 |
| [完整批次执行源码](m7-browser-executed-script.mjs) | 与 m7-browser.json 的采集脚本 SHA 严格匹配；当前脚本新增稳定截图/只读模式，对应补验报告的另一指纹 |
| [生命周期 r1](m7-lifecycle-r1-failed.json)、[r2](m7-lifecycle-r2-failed.json) | 验收脚本 SQL 列名/202 空响应兼容错误，保留 FAILED 原件，与最终 r3 分开 |
| [浏览器 r1](m7-browser-r1.json)、[r2](m7-browser-r2.json) | 定位器/流式调试采集失败；不混同最终批次真实助手失败 |

`m7-*.json` 与评测脚本通过 `.gitattributes` 保持指纹稳定。M7 补验使用新输出文件，失败不会覆盖或改写为成功；原始 F32 批次继续独立保存。

## F32 基线

记录日期：2026-09-29。最终真实采集基线为 `2d929cd5038b349ef2b82328df2fcad2e06ddc22`，使用本机真实 `qwen3.5:9b` / `bge-m3:567m`；业务源码、镜像、运行环境、语料和采集脚本指纹随记录保存。

本目录保存脱敏合成资料的实际结果。自动质量报告为 `DRAFT_PENDING_HUMAN_REVIEW`，`humanReview` 均未填写；Codex 辅助复核不替代人工评分。正式 F32/M7 尚未全部验收，完整结论见 [F32 验收记录](../../doc/验收记录/F32验收记录.md)。

| 产物 | 内容与结果 |
| --- | --- |
| [客观汇总与文件指纹](f32-summary.json) | 三组完整采集的摘要；11 份证据文件 SHA-256 与汇总脚本指纹；不是人工质量报告 |
| [真实业务](f32-business.json) | 最终 r6 批次 49/49 断言；35 个业务操作、16 张表及关键关系 |
| [独立权限](f32-permissions.json) | 4 个场景、24/24 断言；不替换普通 runner 的特殊场景 NOT_RUN |
| [开发集 VECTOR](f32-development-vector.json) | 60 条记录：48 COMPLETED、12 NOT_RUN；完整预期证据命中 37/40 |
| [开发集 FUSION](f32-development-fusion.json) | 60 条记录：47 COMPLETED、1 FAILED、12 NOT_RUN；完整预期证据命中 36/40 |
| [策略比较](f32-strategy-comparison.json) | 仅改变检索策略，其他配置/源码/镜像一致；提供方失败不能直接解释为检索退化 |
| [配置冻结](f32-freeze.json) | 开发集比较后保留 VECTOR，23:00:39 +08:00 冻结；引用原始开发批次指纹 |
| [固定 40 题](f32-test.json) | 40 COMPLETED，完整预期证据命中 38/39；原始答案与来源预览保留 |
| [真实恢复演练](f32-restore.json) | 14 项检查通过，恢复后真实流式追问；备份编号 20260929T150409Z-8ee8aecf |
| [业务失败历史](f32-business-history.json) | r1—r5 的失败原因、标识和断言摘要；最终通过不覆盖早期失败 |
| [提供方独立复测](f32-fusion-provider-recheck.json) | FUSION DEV-042 单题复测成功；不替换原批次 HTTP 500 失败，不拼接成功数 |
| [早期接入失败](f32-preflight.json) | 空正文问题修复前的历史记录，不具备最终新增指纹，不能用于比较或冻结 |

开发集判断见 [策略取舍与辅助复核](../../doc/验收记录/F32开发集取舍.md)，固定题逐题检查见 [固定题辅助复核](../../doc/验收记录/F32固定题复核.md)。接口、数据表及 F01—F31 对应关系见 [覆盖矩阵](../../doc/验收记录/F32覆盖矩阵.md)，12 道特殊题的分层证据与缺口见 [特殊场景映射](../../doc/验收记录/F32特殊场景映射.md)。

最终三份采集导出只移除恢复检查点，保留 `sourceRunSha256` 供核对原文件。原始检查点、实际绑定、凭据、Cookie、数据库备份和原文件包留在忽略目录，不进入 Git。导出文件不能直接续跑；新代码、镜像或语料必须建立新基线，不能复用本次冻结。

`.gitattributes` 对 F32 证据 JSON 保留原始字节，对评测脚本固定 LF 换行，避免跨平台检出改变上述 SHA-256。核对文件指纹时直接读取文件字节，不要先格式化 JSON 或转换换行。

归档时修正了“检索成功后生成失败”遗漏 Embedding 尝试的问题，FUSION 现计 48 次已确认调用、0 次调用不明。三批 Embedding Token 均未知，总量保持 `null`；重排序关闭，确定未调用为 0。摘要与比较按修正后的汇总脚本重新生成，原始运行、冻结、人工评分和失败记录均未改变；`summarizerSha256` 区分采集基线与归档汇总实现。

TEST-039 的原预期同时要求旧 v1 和新 v2 证据，但新版本未保留旧值且检索仅接受 active 版本；实际回答给出了题面要求的新路径和 3 秒。保留原题及原指标，在后续独立数据集版本修订范围，不据固定题回调参数或改写本次成绩。TEST-033 的事实缺漏、TEST-031 的范围引文及其余待复核问题也保留在逐题记录中。

目录中的 `f21-baseline-status.json`、`f22-comparison-status.json`、`f23-comparison-status.json`、`f24-tuning-status.json` 是早期 NOT_RUN 历史状态，不代表本次实际环境。后续 M7 已补齐记录中的检索后撤权时序、旧引用生命周期与真实页面 RAG/来源检查；助手组合页面本轮实际失败，真实云端/重排序与人工评分也尚未闭合。原特定异常引文仍按分层契约解释，不冒充新增部署贯穿记录。复现方式见 [评测使用说明](../README.md)及本页 M7 入口。
