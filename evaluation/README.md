# SupportAI 评测集

`evaluation` 保存可重复的业务题集、数据契约和运行结果口径。当前版本 `f21-2026-09-23-v1` 包含 60 道开发题和 40 道固定测试题，全部业务事实来自 `data/demo/knowledge/f21` 下的脱敏模拟资料，不代表真实商户或生产接口。

## 目录约定

- `datasets/f21-development.jsonl`：开发集，可用于功能调试和检索参数选择。
- `datasets/f21-test.jsonl`：固定测试集，只能在策略冻结后运行，内容由清单中的 SHA-256 锁定。
- `f21-manifest.json`：数据集版本、题数、分类下限、语料版本和内容指纹。
- `schemas/f21-case.schema.json`：单题字段契约。
- `schemas/f21-run.schema.json`：真实运行记录契约。
- `scripts/f21-evaluation.mjs`：结构校验和结果汇总工具。
- `scripts/f32-runner.mjs`：真实语料导入、HTTP/SSE 采集、断点恢复、配置冻结和待复核报告。
- `results/f21-baseline-status.json`：最近一次基础结果执行状态，不保存伪造分数。
- `results/f22-comparison-status.json`：关键词融合对比执行状态，不保存伪造分数。
- `results/f23-comparison-status.json`：重排序开启/关闭对比执行状态，不保存伪造分数。
- `results/f24-tuning-status.json`：默认策略与单因素调参执行状态，不保存伪造分数。

演示语料中的 `<!-- evidence:... -->` 是稳定证据标识。可回答题的每条 `expectedFacts` 必须逐字存在于其 `evidenceIds` 指向的证据段；资料不足、权限拒绝和契约题不得挂接虚构证据。

## 数据集规则

1. F12-Q01 至 F12-Q20 及其同事实改写全部位于开发集；校验器会拒绝它们进入固定测试集。
2. `canonicalFactId` 在开发集和固定测试集之间不能重复，避免用固定测试事实选择参数。
3. 固定测试集的内容指纹写入 `f21-manifest.json`；确需升级时必须创建新数据集版本并重新建立基线。
4. 可回答题必须绑定具体演示文档版本和证据段，资料不足题必须写明拒答原因。
5. 引用正确与依据充分由人工核对，运行记录不得写入或使用 `modelScore` 代替人工结论。

## 校验命令

在仓库根目录执行：

```powershell
npm.cmd --prefix evaluation test
npm.cmd --prefix evaluation run validate
```

校验会检查 60/40 分割、F12 来源映射、同事实泄漏、分类覆盖、语料和固定测试集指纹，以及预期事实是否能在证据原文中逐字找到。

## 真实基线流程

1. 按 `f21-manifest.json` 的版本顺序上传 `data/demo/knowledge/f21` 语料，并在本机 `.local/evaluation/` 保存逻辑文档、实际 `documentId/versionId/chunkId` 的绑定；该目录不会提交到 Git。
2. 先只运行 development 分割。每题记录 Git 提交、数据集 SHA-256、环境、`chat_run.config_snapshot`、回答、实际入模的检索证据、引用来源、用量和阶段耗时。
3. 人工核对引用正确与依据充分，填写 `humanReview`。模型用量缺失时保留 `null`，不能改写为 0。
4. 使用开发集冻结检索和模型配置后再运行 test 分割；固定测试结果不能反向用于调参。
5. 按运行契约保存 JSON 后执行汇总：

```powershell
node evaluation/scripts/f21-evaluation.mjs summarize <run.json>
```

汇总结果始终同时输出分子与分母，并分别统计检索命中、人工引用正确、人工依据充分、拒答表现、Chat/Embedding/重排序用量已知率，以及检索、重排序、生成和总耗时的 P50/P95。确定未调用的模型按 0 汇总；只要任一实际调用没有返回用量，对应总量就必须保留 `null`，不能只汇总已知部分或推算未知值。

Embedding 尝试也包含生成失败前已确认的检索调用；前置失败若无法确认是否调用，单列为 `embedding.attemptUnknownRuns`，不能视为确定未调用或已知零用量。只要存在调用不明或已确认调用的用量未知，Embedding 总 Token 保持 `null`。

F22 比较必须在同一 Git 提交、环境、开发集 SHA-256、TopK、模型、融合参数和提示词配置下各运行一次，只允许 `retrieval.strategy` 分别为 `VECTOR` 和 `FUSION`：

```powershell
node evaluation/scripts/f21-evaluation.mjs compare <vector-run.json> <fusion-run.json>
```

比较结果会列出召回提升题、召回回退题、证据集合变化题，按错误码、接口名、中文改写等分类给出召回分子/分母，并给出整体质量、token 总量和 P50/P95 耗时差值。没有两份真实完整运行记录时不得据此切换默认策略。

F23 比较继续使用同一 development 分割、Git 提交、环境、检索策略、TopK、模型和提示词，只允许 `rerank.enabled` 分别为 `false` 和 `true`。两组完成的 RAG 题必须包含重排序状态、候选数、耗时和可空 Token 用量：

```powershell
node evaluation/scripts/f21-evaluation.mjs compare-rerank <disabled-run.json> <enabled-run.json>
```

比较结果会同时报告整体与分类质量、收益/回退题、Chat 和重排序 Token、重排序状态分布以及检索总耗时、重排序子阶段、生成和总耗时差值。`rerankMs` 已包含在 `retrievalMs` 中，不能相加；确定未调用模型的题按 0 个重排序 Token 统计，已经尝试调用但提供方未返回用量时保留 `null`，不能推算为 0。

## F24 配置冻结与单因素比较

F24 运行记录必须使用 `f24-v1` 配置快照，明确记录 Chat、Embedding、Rerank 的角色、提供方和模型，以及 TopK、证据 Token 预算、三种最终分数阈值、提示词版本和索引完整配置哈希。每道已完成 RAG 题还需记录授权复核后的证据数、分数过滤数、预算过滤数、实际入模证据数和正文 Token 数；校验器会检查数量守恒及预算上限。

纯向量、融合、融合加重排序三组结果必须来自同一 development 数据集、Git 提交和环境，除检索策略与重排序开关外配置完全一致：

```powershell
node evaluation/scripts/f21-evaluation.mjs compare-strategies `
  <vector-run.json> <fusion-run.json> <fusion-rerank-run.json>
```

逐项调参使用 `compare-factor`，可选因素为 `top-k`、`evidence-budget`、`minimum-vector-score`、`minimum-fusion-score`、`minimum-rerank-score`、`chunking`、`embedding` 和 `prompt`。命令会拒绝夹带其他配置变化；分块或 Embedding 变化还必须使用新的索引配置哈希。除提示词外，两次运行必须使用同一 Git 提交；提示词来自代码常量，比较时允许使用两个提交，但必须人工确认提交差异只包含提示词及其版本：

```powershell
node evaluation/scripts/f21-evaluation.mjs compare-factor `
  <factor> <baseline-run.json> <candidate-run.json>
```

分块或 Embedding 配置改变后，应重新执行文档版本导入和向量构建，不能将旧索引结果标记为新配置。固定测试集不允许参与参数选择，只在开发集冻结默认配置后用于完整产品验收。

## F32 真实采集与阶段归档

在运行中的 Compose 部署上，通过环境变量 `F32_USERNAME` / `F32_PASSWORD` 提供验收账号。执行器只向指定入口提交固定脱敏语料，数据库读取用于核对持久化运行快照和实际入模来源；要求本机可访问 Docker，`--project` 必须对应该入口的部署项目。

```powershell
node evaluation/scripts/f32-runner.mjs prepare --base-url http://127.0.0.1:8088 --project support-ai-f31-source4 --state .local/evaluation/f32-binding.json
node evaluation/scripts/f32-runner.mjs run --split development --project support-ai-f31-source4 --binding .local/evaluation/f32-binding.json --out .local/evaluation/f32-development-vector.json --environment "本机 Docker/Ollama"
node evaluation/scripts/f32-runner.mjs report --run .local/evaluation/f32-development-vector.json --out .local/evaluation/f32-development-report.json
```

`run` 可用 `--limit 3` 先验证少量开发题，再去掉该参数续跑相同输出文件。每题在提交前持久化 `sessionId/clientRequestId`，中断后只收集原运行；已提交但无法确认的请求不会盲目重放。文件锁防止同时写入同一批次。来源按原文偏移映射稳定证据编号，必须覆盖证据的完整事实正文，单独出现标记不算命中。HTTP、数据库和会话历史的引用集合及来源预览身份均需一致。

普通执行器将契约和特殊权限夹具题明确记录为 `NOT_RUN`，不得用普通问答代替；对应证据与缺口见 [特殊场景映射](../doc/验收记录/F32特殊场景映射.md)。开发集全 60 题必须有记录后才能冻结，其中普通 RAG 题必须有真实终态。冻结保存题集、Git、业务源码、镜像/环境、语料绑定及实际配置指纹；固定测试拒绝漂移。先完成开发集比较，再恢复选择的部署配置并冻结：

```powershell
node evaluation/scripts/f32-runner.mjs compare --baseline .local/evaluation/f32-development-vector.json --candidate .local/evaluation/f32-development-fusion.json --out .local/evaluation/f32-comparison.json
node evaluation/scripts/f32-runner.mjs freeze --run .local/evaluation/f32-development-vector.json --out .local/evaluation/f32-freeze.json --reason "记录开发集取舍依据及尚待复核的限制"
node evaluation/scripts/f32-runner.mjs run --split test --project support-ai-f31-source4 --binding .local/evaluation/f32-binding.json --freeze .local/evaluation/f32-freeze.json --out .local/evaluation/f32-test.json --environment "本机 Docker/Ollama"
node evaluation/scripts/f32-runner.mjs report --run .local/evaluation/f32-test.json --out .local/evaluation/f32-test-report.json
node evaluation/scripts/f32-runner.mjs export --run .local/evaluation/f32-test.json --out evaluation/results/f32-test.json
```

`compare` 仅允许完整开发集之间改变检索策略，其余配置、源码、应用镜像、采集脚本和语料必须一致。运行环境另计算 `runtimeWithoutStrategyHash`，只排除 `SUPPORT_AI_RETRIEVAL_STRATEGY`，JVM 参数、其他环境变量和启动命令仍参与校验。冻结及断点续跑同时锁定 `runnerSha256`；修改脚本后必须重新采集，不得拼接不同执行器生成的记录。

`export` 去除恢复检查点，保留逐题回答、来源、配置、用量和耗时，不能用导出件续跑。已引用来源必须与预览集合完全一致，预览还须匹配分块、文档、版本并包含正文；缺失预览不能通过完整性校验。绑定和原始检查点保留在忽略目录，精简后的脱敏语料结果可以提交。早期 `f32-preflight.json` 保留修复前空回答的历史证据，不具备最终执行器要求的新指纹字段，不能用于最终策略比较或冻结。

F32 自动采集的 `humanReview` 始终为 `null`；`report/compare` 输出 `DRAFT_PENDING_HUMAN_REVIEW`，不能称为人工引用正确率或依据充分性验收通过。旧 `f21-evaluation.mjs summarize/compare` 的严格人工复核门槛保持不变，不应直接用于这些待复核记录。`refusalCorrect` 沿用零证据/权限自动口径：看到无关资料后仍正确拒答的语义质量需另行核对。来源命中统计基于经过分数和 Token 预算筛选后的实际入模材料，不代表原始候选召回。

## 历史状态与当前限制

2026-09-24 复核及修复：F18/F19 的文档版本更新与删除能力已经实现，F20 和 M4 的真实场景仍未完整验收。共享 RAG 批量来源登记的 UUID 类型错误已修复，Mapper 数据库 6/6 覆盖多来源登记、非法批次回滚和根来源继承；聚合普通测试 361/361、选定数据库测试 21/21 通过。下一步仍需统一提交基线、补齐实际语料绑定和模型环境，再建立真实评测基线。

2026-09-24 的题集结构与指纹校验、7 项比较/汇总脚本测试通过；当时四份 `results/*-status.json` 为 `NOT_RUN`，尚无真实运行绑定。这些状态文件保留历史事实，不用于代表当前环境。2026-09-29 已完成本地真实 qwen3.5:9b / bge-m3:567m 接入、开发集 VECTOR/FUSION 比较、冻结后固定 40 题及真实恢复演练。最终脚本测试 29/29，题集与指纹校验通过；结果、汇总与冻结记录统一见 [产物目录](results/README.md)，验证边界见 [F32 验收记录](../doc/验收记录/F32验收记录.md)。原始采集基于提交 2d929cd；后续仅修正 Embedding 尝试汇总并重新生成摘要，未改原始运行或冻结记录。真实云端/重排序服务与人工复核仍须独立完成。

状态 JSON 保留 2026-09-23 的 `recordedAt` 和历史事实，其中前置“未实现”及环境不可连接的描述不代表当前状态；[M5 阶段复核](../doc/验收记录/M5阶段复核.md)也保留当时结论。本轮比较未观察到 FUSION 的完整预期证据命中收益，默认保留 `VECTOR` 且关闭重排序；单批次耗时和未知 Embedding 用量不能证明稳定成本收益。固定题中的 TEST-039 暴露预期要求旧/新双版本与 active-only 检索的冲突，原题、结果和 38/39 指标保持不变，须在新数据集版本解决。
