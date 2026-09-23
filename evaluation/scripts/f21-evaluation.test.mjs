import test from "node:test";
import assert from "node:assert/strict";

import {
  compareRerankResults,
  compareResults,
  compareStrategyResults,
  compareTuningConfigurations,
  summarizeResults,
  validateDataset,
  validateF24Metrics,
} from "./f21-evaluation.mjs";

function f24Configuration() {
  return {
    schemaVersion: "f24-v1",
    mode: "RAG",
    retrieval: { strategy: "VECTOR", topK: 6, candidateMultiplier: 3, rrfK: 60, maxKeywords: 8 },
    rerank: {
      role: "RERANK",
      enabled: false,
      provider: "dashscope",
      model: "qwen3-rerank",
      candidateMultiplier: 3,
      maxCandidates: 50,
      maxEstimatedItemTokens: 3500,
      maxEstimatedRequestTokens: 100000,
      timeoutMs: 5000,
      instruction: "按问题相关性排序",
    },
    answer: {
      maxEvidenceTokens: 4096,
      minimumScores: { vector: -1, fusion: 0, rerank: 0 },
    },
    chat: { role: "CHAT", provider: "ollama", model: "qwen2.5:3b", temperature: 0 },
    embedding: {
      role: "EMBEDDING",
      provider: "ollama",
      model: "bge-m3:latest",
      dimensions: 1024,
    },
    index: {
      configHash: "a".repeat(64),
      parser: { version: "markdown-v1" },
      chunker: {
        version: "heading-token-v1",
        tokenizer: "CL100K_BASE",
        maxTokens: 512,
        maxChunks: 1000,
      },
      embedding: { provider: "ollama", model: "bge-m3:latest", dimensions: 1024 },
    },
    prompt: { version: "rag-answer-v1" },
  };
}

test("正式题集满足 60/40 分割、证据和分类约束", async () => {
  const result = await validateDataset();

  assert.equal(result.totalCount, 100);
  assert.equal(result.developmentCount, 60);
  assert.equal(result.testCount, 40);
  assert.match(result.developmentSha256, /^[0-9a-f]{64}$/);
  assert.match(result.testSha256, /^[0-9a-f]{64}$/);
  assert.ok(result.evidenceCount >= 60);
});

test("汇总只使用人工复核结论并保留真实分母", () => {
  const cases = [
    {
      id: "DEV-001",
      executionMode: "RAG",
      expectedOutcome: "ANSWER",
      evidenceIds: ["E-1"],
      categories: ["ERROR_CODE"],
    },
    {
      id: "DEV-002",
      executionMode: "RAG",
      expectedOutcome: "INSUFFICIENT",
      evidenceIds: [],
    },
  ];
  const results = [
    {
      caseId: "DEV-001",
      status: "COMPLETED",
      noEvidence: false,
      retrievedEvidenceIds: ["E-1"],
      usage: { totalTokens: 20 },
      metrics: { retrievalMs: 10, generationMs: 30, totalMs: 45 },
      humanReview: { citationCorrect: true, evidenceSufficient: false, notes: "人工核对" },
    },
    {
      caseId: "DEV-002",
      status: "COMPLETED",
      noEvidence: true,
      retrievedEvidenceIds: [],
      usage: null,
      metrics: { retrievalMs: 5, generationMs: null, totalMs: 8 },
      humanReview: { citationCorrect: null, evidenceSufficient: null, notes: "确定无资料" },
    },
  ];

  const summary = summarizeResults(cases, results);

  assert.deepEqual(summary.quality.retrievalHit, { passed: 1, total: 1 });
  assert.deepEqual(summary.quality.citationCorrect, { passed: 1, total: 1 });
  assert.deepEqual(summary.quality.evidenceSufficient, { passed: 0, total: 1 });
  assert.deepEqual(summary.quality.refusalCorrect, { passed: 1, total: 1 });
  assert.equal(summary.denominators.usageKnown, 1);
  assert.equal(summary.usage.attemptedRuns, 1);
  assert.equal(summary.usage.noCallRuns, 1);
  assert.equal(summary.usage.unknownRuns, 0);
  assert.equal(summary.embedding.attemptedRuns, 2);
  assert.equal(summary.embedding.usageUnknownRuns, 2);
  assert.equal(summary.embedding.totalTokens, null);
  assert.equal(summary.latencyMs.generation.known, 1);
  assert.equal(summarizeResults([cases[1]], [results[1]]).usage.totalTokens, 0);
  assert.equal(summarizeResults([cases[0]], [{ ...results[0], usage: null }]).usage.totalTokens, null);
});

test("同集策略比较同时报告召回收益、回退、耗时和用量差值", () => {
  const cases = [
    {
      id: "DEV-001",
      executionMode: "RAG",
      expectedOutcome: "ANSWER",
      evidenceIds: ["E-1"],
      categories: ["ERROR_CODE"],
    },
    {
      id: "DEV-002",
      executionMode: "RAG",
      expectedOutcome: "ANSWER",
      evidenceIds: ["E-2"],
      categories: ["CHINESE_PARAPHRASE"],
    },
  ];
  const baseResult = {
    status: "COMPLETED",
    noEvidence: false,
    citedEvidenceIds: [],
    humanReview: { citationCorrect: true, evidenceSufficient: true, notes: "人工核对" },
  };
  const vectorResults = [
    { ...baseResult, caseId: "DEV-001", retrievedEvidenceIds: [], usage: { totalTokens: 10 },
      metrics: { retrievalMs: 10, generationMs: 20, totalMs: 30 } },
    { ...baseResult, caseId: "DEV-002", retrievedEvidenceIds: ["E-2"], usage: { totalTokens: 20 },
      metrics: { retrievalMs: 20, generationMs: 30, totalMs: 50 } },
  ];
  const fusionResults = [
    { ...baseResult, caseId: "DEV-001", retrievedEvidenceIds: ["E-1"], usage: { totalTokens: 15 },
      metrics: { retrievalMs: 15, generationMs: 25, totalMs: 40 } },
    { ...baseResult, caseId: "DEV-002", retrievedEvidenceIds: [], usage: { totalTokens: 25 },
      metrics: { retrievalMs: 30, generationMs: 35, totalMs: 65 } },
  ];

  const comparison = compareResults(cases, vectorResults, fusionResults);

  assert.deepEqual(comparison.changes.retrievalGains, ["DEV-001"]);
  assert.deepEqual(comparison.changes.retrievalRegressions, ["DEV-002"]);
  assert.deepEqual(comparison.changes.changedEvidence, ["DEV-001", "DEV-002"]);
  assert.equal(comparison.quality.retrievalHit.passedDelta, 0);
  assert.equal(comparison.retrievalByCategory.ERROR_CODE.passedDelta, 1);
  assert.equal(comparison.retrievalByCategory.CHINESE_PARAPHRASE.passedDelta, -1);
  assert.equal(comparison.usage.totalTokensDelta, 10);
  assert.equal(comparison.latencyMs.retrieval.p95Delta, 10);
});

test("重排序同集比较区分模型用量、回退状态和阶段耗时", () => {
  const cases = [{
    id: "DEV-001",
    executionMode: "RAG",
    expectedOutcome: "ANSWER",
    evidenceIds: ["E-1"],
    categories: ["CROSS_DOCUMENT"],
  }];
  const baseResult = {
    caseId: "DEV-001",
    status: "COMPLETED",
    noEvidence: false,
    citedEvidenceIds: ["E-1"],
    usage: { totalTokens: 20 },
    humanReview: { citationCorrect: true, evidenceSufficient: true, notes: "人工核对" },
  };
  const disabledResults = [{
    ...baseResult,
    retrievedEvidenceIds: [],
    metrics: {
      retrievalMs: 10,
      rerankStatus: "DISABLED",
      rerankMs: 0,
      rerankTotalTokens: null,
      generationMs: 20,
      totalMs: 30,
    },
  }];
  const enabledResults = [{
    ...baseResult,
    retrievedEvidenceIds: ["E-1"],
    metrics: {
      retrievalMs: 18,
      rerankStatus: "APPLIED",
      rerankMs: 6,
      rerankTotalTokens: 42,
      generationMs: 21,
      totalMs: 41,
    },
  }];

  const comparison = compareRerankResults(cases, disabledResults, enabledResults);

  assert.equal(comparison.quality.retrievalHit.passedDelta, 1);
  assert.deepEqual(comparison.changes.retrievalGains, ["DEV-001"]);
  assert.equal(comparison.usage.rerank.disabled.totalTokens, 0);
  assert.equal(comparison.usage.rerank.enabled.totalTokens, 42);
  assert.equal(comparison.usage.rerank.totalTokensDelta, 42);
  assert.deepEqual(comparison.usage.rerank.enabled.statuses, { APPLIED: 1 });
  assert.equal(comparison.latencyMs.rerank.p95Delta, 6);
});

test("三策略报告保留各组汇总及逐级收益和回退", () => {
  const cases = [{
    id: "DEV-001",
    executionMode: "RAG",
    expectedOutcome: "ANSWER",
    evidenceIds: ["E-1"],
    categories: ["ERROR_CODE"],
  }];
  const result = (retrievedEvidenceIds, rerankStatus) => ({
    caseId: "DEV-001",
    status: "COMPLETED",
    noEvidence: retrievedEvidenceIds.length === 0,
    retrievedEvidenceIds,
    citedEvidenceIds: [],
    usage: { totalTokens: 20 },
    metrics: {
      retrievalMs: 10,
      embeddingTotalTokens: null,
      rerankStatus,
      rerankMs: rerankStatus === "APPLIED" ? 4 : 0,
      rerankTotalTokens: rerankStatus === "APPLIED" ? 12 : null,
      generationMs: 20,
      totalMs: 30,
    },
    humanReview: { citationCorrect: true, evidenceSufficient: true, notes: "人工核对" },
  });

  const report = compareStrategyResults(cases,
    [result([], "DISABLED")],
    [result(["E-1"], "DISABLED")],
    [result(["E-1"], "APPLIED")]);

  assert.equal(report.summaries.vector.quality.retrievalHit.passed, 0);
  assert.equal(report.summaries.fusion.quality.retrievalHit.passed, 1);
  assert.deepEqual(report.comparisons.vectorToFusion.changes.retrievalGains, ["DEV-001"]);
  assert.equal(report.comparisons.fusionToFusionRerank.usage.rerank.totalTokensDelta, 12);
});

test("单因素配置比较拒绝夹带其他配置变化", () => {
  const baseline = f24Configuration();
  const candidate = structuredClone(baseline);
  candidate.retrieval.topK = 8;

  const comparison = compareTuningConfigurations("top-k", baseline, candidate);

  assert.equal(comparison.factor, "top-k");
  assert.equal(comparison.baseline["retrieval.topK"], 6);
  assert.equal(comparison.candidate["retrieval.topK"], 8);

  candidate.prompt.version = "rag-answer-v2";
  assert.throws(() => compareTuningConfigurations("top-k", baseline, candidate),
    /还存在其他配置差异/);

  const leakedConfiguration = structuredClone(baseline);
  leakedConfiguration.retrieval.topK = 8;
  leakedConfiguration.rerank.endpoint = "https://workspace.example/compatible-api/v1/reranks";
  assert.throws(() => compareTuningConfigurations("top-k", baseline, leakedConfiguration),
    /包含未允许字段/);
});

test("F24 运行指标校验证据守恒和未知用量", () => {
  const result = {
    caseId: "DEV-001",
    noEvidence: false,
    retrievedEvidenceIds: ["E-1"],
    citedEvidenceIds: ["E-1"],
    metrics: {
      evidenceCount: 1,
      retrievedEvidenceCount: 2,
      scoreFilteredEvidenceCount: 1,
      budgetFilteredEvidenceCount: 0,
      evidenceTokenCount: 10,
      citedCount: 1,
      noEvidence: false,
      retrievalMs: 5,
      embeddingTotalTokens: null,
      rerankStatus: "DISABLED",
      rerankCandidateCount: 0,
      rerankMs: 0,
      rerankTotalTokens: null,
      generationMs: 20,
      totalMs: 30,
      firstDeltaMs: 8,
    },
  };

  assert.doesNotThrow(() => validateF24Metrics(result, 10));

  const invalidResult = structuredClone(result);
  invalidResult.metrics.retrievedEvidenceCount = 3;
  assert.throws(() => validateF24Metrics(invalidResult, 10), /证据筛选数量无法守恒/);
});
