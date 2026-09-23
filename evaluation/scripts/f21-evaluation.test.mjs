import test from "node:test";
import assert from "node:assert/strict";

import { summarizeResults, validateDataset } from "./f21-evaluation.mjs";

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
