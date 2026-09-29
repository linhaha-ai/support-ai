import assert from "node:assert/strict";
import test from "node:test";
import { assertFrozen, checkpointAction, collectEvidenceRanges, compactRun, hash, mapEvidence, normalizeDocument,
  runtimeIdentity, SseParser, validateCitedSources, validateComparison, validateFreezeRecord, validateRunRecord,
  validateSourcePreview } from "./f32-runner.mjs";

test("SSE 支持逐字节 UTF-8 和 CRLF 分段，保留事件顺序", () => {
  const received = [];
  const parser = new SseParser((event) => received.push(event));
  const bytes = new TextEncoder().encode('event: start\r\ndata: {"runId":"one"}\r\n\r\nevent: delta\r\ndata: {"text":"你好支付"}\r\n\r\nevent: done\r\ndata: {"status":"COMPLETED"}\r\n\r\n');
  for (const byte of bytes) parser.push(new Uint8Array([byte]));
  parser.push(new Uint8Array(), true);
  assert.deepEqual(received.map((item) => item.type), ["start", "delta", "done"]);
  assert.equal(received[1].data.text, "你好支付");
});

test("SSE 注释不算事件，支持多行 data 并拒绝截断末尾", () => {
  const received = [];
  const parser = new SseParser((event) => received.push(event));
  parser.push(new TextEncoder().encode(': heartbeat\n\nevent: delta\ndata: {\ndata: "text":"片段"}\n\n'));
  assert.deepEqual(received, [{ type: "delta", data: { text: "片段" } }]);
  parser.push(new TextEncoder().encode('event: done\ndata: {"status":"COMPLETED"}'));
  assert.throws(() => parser.push(new Uint8Array(), true), /完整事件/);
});

test("来源映射要求完整正文，前一分块仅带下一 evidence 标记不算下一事实", () => {
  const content = '# 手册\n\n<!-- evidence:FIRST -->\n## 入口\n\n调用接口 A。\n\n<!-- evidence:SECOND -->\n## 时限\n\n必须三秒应答。\n';
  const evidence = collectEvidenceRanges(content);
  assert.deepEqual(evidence.map((item) => item.text), ["调用接口 A。", "必须三秒应答。"]);
  const secondHeading = content.indexOf("## 时限");
  const binding = { documents: { one: { versionId: "v1", evidence,
    chunks: [{ chunkId: "c1", startOffset: 0, endOffset: secondHeading },
      { chunkId: "c2", startOffset: secondHeading, endOffset: content.length }] } } };
  assert.deepEqual(mapEvidence(binding, [{ versionId: "v1", chunkId: "c1" }]), ["FIRST"]);
  assert.deepEqual(mapEvidence(binding, [{ versionId: "v1", chunkId: "c2" }]), ["SECOND"]);
  assert.deepEqual(mapEvidence(binding, [{ versionId: "other", chunkId: "c1" }]), []);
});

test("同一事实跨分块时必须覆盖全部区间，空洞不能算命中", () => {
  const binding = { documents: { one: { versionId: "v1", evidence: [{ id: "FACT", startOffset: 10, endOffset: 30 }],
    chunks: [{ chunkId: "a", startOffset: 0, endOffset: 20 }, { chunkId: "b", startOffset: 20, endOffset: 40 },
      { chunkId: "gap", startOffset: 21, endOffset: 40 }] } } };
  const source = (chunkId) => ({ versionId: "v1", chunkId });
  assert.deepEqual(mapEvidence(binding, [source("a")]), []);
  assert.deepEqual(mapEvidence(binding, [source("a"), source("gap")]), []);
  assert.deepEqual(mapEvidence(binding, [source("a"), source("b")]), ["FACT"]);
});

test("文本规范化与后端偏移规则一致", () => {
  assert.equal(normalizeDocument("\uFEFF# 标题  \r\n正文\u0001 \t\r结束\t"), "# 标题\n正文\n结束");
});

const frozen = { datasetVersion: "one", datasetSha256: "hash", gitCommit: "commit", environment: "local",
  runtimeHash: "runtime", runtimeWithoutStrategyHash: "strategyIndependent", imageId: "image", runnerSha256: "runner",
  bindingHash: "binding", sourceTreeHash: "source", configSnapshot: { retrieval: { topK: 5, strategy: "VECTOR" } } };

test("冻结校验拒绝代码、题集、运行环境、语料和真实配置漂移", () => {
  assert.doesNotThrow(() => assertFrozen(frozen, structuredClone(frozen)));
  for (const key of ["datasetVersion", "datasetSha256", "gitCommit", "environment", "runtimeHash", "bindingHash", "sourceTreeHash",
    "imageId", "runtimeWithoutStrategyHash", "runnerSha256"]) {
    assert.throws(() => assertFrozen(frozen, { ...frozen, [key]: "changed" }), new RegExp(key));
  }
  assert.throws(() => assertFrozen(frozen, { ...frozen,
    configSnapshot: { retrieval: { topK: 6, strategy: "VECTOR" } } }), /configSnapshot/);
  assert.equal(hash({ b: 2, a: 1 }), hash({ a: 1, b: 2 }));
});

test("断点续跑不重复成功题，已提交题只收集原运行，未知提交状态拒绝重放", () => {
  assert.equal(checkpointAction({ result: { status: "COMPLETED" } }, null), "SKIP");
  assert.equal(checkpointAction({ result: { status: "FAILED" } }, null), "SKIP");
  assert.equal(checkpointAction({ submittedAt: "today" }, { runId: "existing", status: "RUNNING" }), "COLLECT");
  assert.equal(checkpointAction({ clientRequestId: "persisted" }, null), "SUBMIT");
  assert.throws(() => checkpointAction({ submittedAt: "today" }, null), /禁止盲目重放/);
});

function sample() {
  const digest = "a".repeat(64);
  const configuration = { schemaVersion: "f24-v1", mode: "RAG", chat: { role: "CHAT", model: "real" },
    embedding: { role: "EMBEDDING", model: "embedding" }, retrieval: { strategy: "VECTOR", topK: 5 },
    rerank: { enabled: false }, index: { configHash: digest }, answer: { maxEvidenceTokens: 1000 } };
  const dataset = { datasetVersion: "one", developmentSha256: digest, testSha256: "b".repeat(64), evidenceIds: ["FACT"],
    cases: [{ id: "DEV-001", split: "development", executionMode: "RAG", evidenceIds: ["FACT"] },
      { id: "DEV-002", split: "development", executionMode: "CONTRACT_TEST", evidenceIds: [] }] };
  const result = { caseId: "DEV-001", status: "COMPLETED", requestId: "00000000-0000-4000-8000-000000000001",
    runId: "00000000-0000-4000-8000-000000000002", sessionId: "00000000-0000-4000-8000-000000000003",
    answer: "资料不足", noEvidence: true, configSnapshot: configuration, retrievedEvidenceIds: [], citedEvidenceIds: [],
    sources: [], citedSourcePreviews: [], metrics: { noEvidence: true, evidenceCount: 0, citedCount: 0, retrievalMs: 5, totalMs: 10 },
    usage: null, humanReview: { citationCorrect: null, evidenceSufficient: null, notes: "待人工" } };
  const skipped = { caseId: "DEV-002", status: "NOT_RUN", reason: "独立契约夹具", retrievedEvidenceIds: [], citedEvidenceIds: [],
    humanReview: { citationCorrect: null, evidenceSufficient: null, notes: "待人工" } };
  const run = { schemaVersion: "1.0", metadata: { split: "development", datasetVersion: "one", datasetSha256: digest,
    gitCommit: "c".repeat(40), environment: "local", runtimeHash: digest, bindingHash: digest, sourceTreeHash: digest,
    runtimeWithoutStrategyHash: digest, runnerSha256: digest, imageId: `sha256:${digest}`,
    startedAt: "2026-09-29T00:00:00Z", completedAt: "2026-09-29T00:01:00Z", configSnapshot: configuration },
    results: [result, skipped], checkpoints: {
      "DEV-001": { sessionId: result.sessionId, clientRequestId: result.requestId, result }, "DEV-002": { result: skipped } } };
  return { dataset, run };
}

test("冻结要求覆盖完整开发集，特殊契约题仅能保持 NOT_RUN", () => {
  const { run, dataset } = sample();
  assert.doesNotThrow(() => validateRunRecord(run, dataset, { requireComplete: true, requireCheckpoints: true }));
  const partial = structuredClone(run); partial.results.pop();
  assert.throws(() => validateRunRecord(partial, dataset, { requireComplete: true }), /完整开发集/);
  const disguised = structuredClone(run); disguised.results[1].status = "COMPLETED";
  assert.throws(() => validateRunRecord(disguised, dataset), /独立场景/);
});

test("报告拒绝错题集、重复或未知题目、未知字段与未知证据", () => {
  const { run, dataset } = sample();
  const mutations = [
    [(copy) => { copy.metadata.datasetSha256 = "bad"; }, /数据集/],
    [(copy) => { copy.results.push(copy.results[0]); }, /重复题目/],
    [(copy) => { copy.results[0].caseId = "TEST-999"; }, /未知或范围外/],
    [(copy) => { copy.results[0].status = "PASSED"; }, /状态无效/],
    [(copy) => { copy.results[0].password = "unexpected"; }, /未知结果字段/],
    [(copy) => { copy.results[0].retrievedEvidenceIds = ["UNKNOWN"]; }, /未知证据/],
    [(copy) => { copy.results[0].humanReview.citationCorrect = true; }, /冒充人工评分/],
  ];
  for (const [mutate, expected] of mutations) {
    const copy = structuredClone(run); mutate(copy);
    assert.throws(() => validateRunRecord(copy, dataset), expected);
  }
});

test("断点校验拒绝结果与检查点不一致和丢失的幂等身份", () => {
  const { run, dataset } = sample();
  const changed = structuredClone(run);
  changed.checkpoints["DEV-001"] = structuredClone(changed.checkpoints["DEV-001"]);
  changed.checkpoints["DEV-001"].result.answer = "另一回答";
  assert.throws(() => validateRunRecord(changed, dataset, { requireCheckpoints: true }), /断点结果/);
  const missing = structuredClone(run); delete missing.checkpoints["DEV-001"];
  assert.throws(() => validateRunRecord(missing, dataset, { requireCheckpoints: true }), /缺少对应断点/);
  const identity = structuredClone(run); identity.checkpoints["DEV-001"].clientRequestId = "invalid";
  assert.throws(() => validateRunRecord(identity, dataset, { requireCheckpoints: true }), /固定请求标识/);
});

test("阶段导出移除检查点并完整保留回答来源配置与 null 复核值", () => {
  const { run, dataset } = sample();
  const artifact = compactRun(run, dataset);
  assert.equal(artifact.status, "COMPLETE_CAPTURE_PENDING_HUMAN_REVIEW");
  assert.equal("checkpoints" in artifact, false);
  assert.deepEqual(artifact.results, run.results);
  assert.equal(artifact.results[0].humanReview.citationCorrect, null);
  assert.match(artifact.sourceRunSha256, /^[a-f0-9]{64}$/);
  assert.throws(() => validateRunRecord(artifact, dataset, { requireCheckpoints: true }), /精简导出不能用于续跑/);
  const partial = structuredClone(run); partial.results.pop();
  assert.equal(compactRun(partial, dataset).status, "PARTIAL_CAPTURE_PENDING_HUMAN_REVIEW");
});

test("草稿比较只允许检索策略变化，拒绝同时更换模型、数据或源码", () => {
  const { run, dataset } = sample();
  const candidate = structuredClone(run);
  candidate.metadata.configSnapshot.retrieval.strategy = "FUSION";
  assert.doesNotThrow(() => validateComparison(run, candidate, dataset));
  const modelChanged = structuredClone(candidate); modelChanged.metadata.configSnapshot.chat.model = "different";
  assert.throws(() => validateComparison(run, modelChanged, dataset), /除 retrieval.strategy/);
  const sourceChanged = structuredClone(candidate); sourceChanged.metadata.sourceTreeHash = "b".repeat(64);
  assert.throws(() => validateComparison(run, sourceChanged, dataset), /sourceTreeHash/);
  for (const key of ["imageId", "runtimeWithoutStrategyHash", "runnerSha256"]) {
    const changed = structuredClone(candidate); changed.metadata[key] = "b".repeat(64);
    assert.throws(() => validateComparison(run, changed, dataset), new RegExp(key));
  }
});

test("引用核对要求精确集合相等，拒绝 HTTP 额外、缺失、重复或版本错配", () => {
  const first = { number: 1, documentId: "doc", versionId: "v1", chunkId: "a", cited: true };
  const uncited = { number: 2, documentId: "doc", versionId: "v1", chunkId: "b", cited: false };
  assert.doesNotThrow(() => validateCitedSources([first, uncited], [first]));
  assert.throws(() => validateCitedSources([first, uncited], [first, uncited]), /不完全一致/);
  assert.throws(() => validateCitedSources([first], []), /不完全一致/);
  assert.throws(() => validateCitedSources([first], [first, first]), /重复分块/);
  assert.throws(() => validateCitedSources([first], [{ ...first, versionId: "v2" }]), /不完全一致/);
});

test("来源预览核对分块、文档、版本和正文，拒绝错误资源的成功响应", () => {
  const source = { documentId: "doc", versionId: "v1", chunkId: "a" };
  assert.doesNotThrow(() => validateSourcePreview(source, { ...source, content: "真实正文" }));
  for (const key of ["documentId", "versionId", "chunkId"]) {
    assert.throws(() => validateSourcePreview(source, { ...source, [key]: "other", content: "正文" }), /身份不一致/);
  }
  assert.throws(() => validateSourcePreview(source, { ...source, content: "  " }), /缺少正文/);
});

test("冻结文件必须关联完整开发运行与准确配置，拒绝修改原件或伪装普通metadata", () => {
  const { run, dataset } = sample();
  const freeze = { ...run.metadata, split: "test", datasetSha256: dataset.testSha256,
    freezeVersion: "1.0", humanReviewStatus: "PENDING", reason: "维持当前策略", frozenAt: "2026-09-29T00:02:00Z",
    sourceDevelopmentSha256: hash(run), configSnapshotSha256: hash(run.metadata.configSnapshot) };
  assert.doesNotThrow(() => validateFreezeRecord(freeze, run, dataset));
  assert.throws(() => validateFreezeRecord(run.metadata, run, dataset), /冻结文件格式/);
  const edited = structuredClone(run); edited.results[0].answer = "修改过的答案";
  assert.throws(() => validateFreezeRecord(freeze, edited, dataset), /开发记录内容已变化/);
  assert.throws(() => validateFreezeRecord({ ...freeze, configSnapshotSha256: "wrong" }, run, dataset), /配置摘要/);
  const partial = structuredClone(run); partial.results.pop();
  assert.throws(() => validateFreezeRecord({ ...freeze, sourceDevelopmentSha256: hash(partial) }, partial, dataset), /完整开发集/);
});

test("运行环境比较仅剔除策略变量，仍检测镜像、JVM、命令和其他环境变化", () => {
  const baseline = { Image: "image-a", Config: { Env: ["JAVA_TOOL_OPTIONS=-Xmx1g", "SUPPORT_AI_RETRIEVAL_STRATEGY=VECTOR",
    "SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=model-a"], Cmd: ["java", "-jar", "app.jar"], Entrypoint: null } };
  const candidate = structuredClone(baseline);
  candidate.Config.Env[1] = "SUPPORT_AI_RETRIEVAL_STRATEGY=FUSION";
  assert.notEqual(runtimeIdentity(baseline).runtimeHash, runtimeIdentity(candidate).runtimeHash);
  assert.equal(runtimeIdentity(baseline).runtimeWithoutStrategyHash, runtimeIdentity(candidate).runtimeWithoutStrategyHash);
  const changes = [
    (copy) => { copy.Image = "image-b"; },
    (copy) => { copy.Config.Env[0] = "JAVA_TOOL_OPTIONS=-Xmx2g"; },
    (copy) => { copy.Config.Env[2] = "SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=model-b"; },
    (copy) => { copy.Config.Cmd.push("--debug"); },
    (copy) => { copy.Config.Env.push("SPRING_AI_RETRIEVAL_STRATEGY=FUSION"); },
  ];
  for (const change of changes) {
    const changed = structuredClone(candidate); change(changed);
    assert.notEqual(runtimeIdentity(baseline).runtimeWithoutStrategyHash, runtimeIdentity(changed).runtimeWithoutStrategyHash);
  }
});

test("已引用来源的归档必须保留对应预览，清空、重复和资源错配均拒绝", () => {
  const { run, dataset } = sample();
  const result = run.results[0];
  const source = { number: 1, chunkId: "00000000-0000-4000-8000-000000000011",
    documentId: "00000000-0000-4000-8000-000000000012", versionId: "00000000-0000-4000-8000-000000000013", cited: true };
  result.noEvidence = false;
  result.metrics.noEvidence = false; result.metrics.evidenceCount = 1; result.metrics.citedCount = 1;
  result.sources = [source];
  result.citedSourcePreviews = [{ ...source, preview: { ...source, content: "来源原文" } }];
  assert.doesNotThrow(() => compactRun(run, dataset));
  const missing = structuredClone(run); missing.results[0].citedSourcePreviews = [];
  assert.throws(() => compactRun(missing, dataset), /不完全一致/);
  const duplicate = structuredClone(run); duplicate.results[0].citedSourcePreviews.push(duplicate.results[0].citedSourcePreviews[0]);
  assert.throws(() => compactRun(duplicate, dataset), /重复分块/);
  const mismatch = structuredClone(run); mismatch.results[0].citedSourcePreviews[0].preview.versionId = "other-version";
  assert.throws(() => compactRun(mismatch, dataset), /身份不一致/);
});
