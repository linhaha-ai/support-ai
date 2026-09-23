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
2. 先只运行 development 分割。每题记录 Git 提交、数据集 SHA-256、环境、`chat_run.config_snapshot`、回答、全部检索证据、引用来源、用量和阶段耗时。
3. 人工核对引用正确与依据充分，填写 `humanReview`。模型用量缺失时保留 `null`，不能改写为 0。
4. 使用开发集冻结检索和模型配置后再运行 test 分割；固定测试结果不能反向用于调参。
5. 按运行契约保存 JSON 后执行汇总：

```powershell
node evaluation/scripts/f21-evaluation.mjs summarize <run.json>
```

汇总结果始终同时输出分子与分母，并分别统计检索命中、人工引用正确、人工依据充分、拒答表现、用量已知率，以及检索、生成和总耗时的 P50/P95。

## 当前限制

F18、F19 尚未实现，F20 也未完成全链路验收，当前无法完整构造同一文档的版本切换和删除竞争场景。2026-09-23 本机 Ollama 与后端均不可连接，因此尚未生成真实模型基础结果；详见 `results/f21-baseline-status.json` 和 F21 验收记录。
