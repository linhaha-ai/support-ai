# SupportAI 评测集

`evaluation` 保存可重复的业务题集、数据契约和运行结果口径。当前版本 `f21-2026-09-23-v1` 包含 60 道开发题和 40 道固定测试题，全部业务事实来自 `data/demo/knowledge/f21` 下的脱敏模拟资料，不代表真实商户或生产接口。

## 目录约定

- `datasets/f21-development.jsonl`：开发集，可用于功能调试和检索参数选择。
- `datasets/f21-test.jsonl`：固定测试集，只能在策略冻结后运行，内容由清单中的 SHA-256 锁定。
- `f21-manifest.json`：数据集版本、题数、分类下限、语料版本和内容指纹。
- `schemas/f21-case.schema.json`：单题字段契约。
- `schemas/f21-run.schema.json`：真实运行记录契约。
- `scripts/f21-evaluation.mjs`：结构校验和结果汇总工具。
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

## 当前限制

F18、F19 尚未实现，F20 也未完成全链路验收，当前无法完整构造同一文档的版本切换和删除竞争场景。2026-09-23 本机 Ollama 与后端均不可连接，qwen3-rerank 工作空间地址与密钥也未配置，因此尚未生成真实模型基础结果、三策略结果或单因素调参结果；详见 `results/f21-baseline-status.json`、`results/f22-comparison-status.json`、`results/f23-comparison-status.json`、`results/f24-tuning-status.json` 和对应验收记录。
