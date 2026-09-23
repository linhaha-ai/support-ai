import test from "node:test";
import assert from "node:assert/strict";

import { compareRerankResults, compareResults, summarizeResults, validateDataset } from "./f21-evaluation.mjs";

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
  assert.equal(summary.usage.unknownRuns, 1);
  assert.equal(summary.latencyMs.generation.known, 1);
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
