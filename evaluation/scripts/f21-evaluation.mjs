import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
export const EVALUATION_ROOT = resolve(SCRIPT_DIR, "..");
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const GIT_COMMIT_PATTERN = /^[0-9a-f]{7,64}$/;
const CASE_ID_PATTERNS = {
  development: /^DEV-\d{3}$/,
  test: /^TEST-\d{3}$/,
};
const OUTCOMES = new Set(["ANSWER", "INSUFFICIENT", "DENIED", "CONTRACT"]);
const EXECUTION_MODES = new Set(["RAG", "CONTRACT_TEST"]);
const RERANK_STATUSES = new Set([
  "NOT_REACHED",
  "DISABLED",
  "SKIPPED_NO_CANDIDATES",
  "SKIPPED_INSUFFICIENT_CANDIDATES",
  "SKIPPED_INPUT_LIMIT",
  "APPLIED",
  "FALLBACK_UNAVAILABLE",
  "FALLBACK_INVALID_RESPONSE",
]);
const CATEGORIES = new Set([
  "ERROR_CODE",
  "API_NAME",
  "CHINESE_PARAPHRASE",
  "CROSS_DOCUMENT",
  "INSUFFICIENT",
  "PERMISSION",
  "VERSION_CHANGE",
]);
const CASE_FIELDS = new Set([
  "id",
  "split",
  "origin",
  "variantOf",
  "canonicalFactId",
  "question",
  "expectedOutcome",
  "executionMode",
  "categories",
  "expectedFacts",
  "evidenceIds",
  "rationale",
  "expectedAssertions",
  "scenario",
]);

function ensure(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function sha256(content) {
  const normalized = typeof content === "string" ? content.replace(/\r\n/g, "\n") : content;
  return createHash("sha256").update(normalized).digest("hex");
}

async function readJson(file) {
  try {
    return JSON.parse(await readFile(file, "utf8"));
  } catch (error) {
    throw new Error(`无法读取 JSON：${file}；${error.message}`);
  }
}

async function readJsonLines(file) {
  const content = await readFile(file, "utf8");
  const lines = content.split(/\r?\n/).filter((line) => line.trim().length > 0);
  const values = lines.map((line, index) => {
    try {
      return JSON.parse(line);
    } catch (error) {
      throw new Error(`${file}:${index + 1} 不是有效 JSON：${error.message}`);
    }
  });
  return { content, values };
}

function collectEvidence(content, document) {
  const matches = [...content.matchAll(/<!--\s*evidence:([A-Z0-9-]+)\s*-->/g)];
  ensure(matches.length > 0, `${document.file} 没有 evidence 标识`);
  const evidence = [];
  for (let index = 0; index < matches.length; index += 1) {
    const match = matches[index];
    const start = match.index + match[0].length;
    const end = index + 1 < matches.length ? matches[index + 1].index : content.length;
    const text = content.slice(start, end).trim();
    ensure(text.length > 0, `${document.file} 的 ${match[1]} 没有正文`);
    evidence.push({
      id: match[1],
      documentKey: document.documentKey,
      version: document.version,
      active: document.active,
      file: document.file,
      text,
    });
  }
  return evidence;
}

async function loadCorpus(manifest, root) {
  const evidenceById = new Map();
  const activeByDocument = new Map();
  for (const document of manifest.corpus ?? []) {
    ensure(typeof document.documentKey === "string" && document.documentKey.length > 0,
      "语料 documentKey 不能为空");
    ensure(typeof document.version === "string" && document.version.length > 0,
      `${document.documentKey} 的版本不能为空`);
    ensure(typeof document.active === "boolean", `${document.documentKey}@${document.version} 缺少 active`);
    ensure(SHA256_PATTERN.test(document.sha256), `${document.file} 的 SHA-256 未锁定`);
    const file = resolve(root, document.file);
    const content = await readFile(file, "utf8");
    ensure(sha256(content) === document.sha256, `${document.file} 的 SHA-256 与清单不一致`);
    if (document.active) {
      ensure(!activeByDocument.has(document.documentKey), `${document.documentKey} 存在多个当前版本`);
      activeByDocument.set(document.documentKey, document.version);
    }
    for (const evidence of collectEvidence(content, document)) {
      ensure(!evidenceById.has(evidence.id), `证据标识重复：${evidence.id}`);
      evidenceById.set(evidence.id, evidence);
    }
  }
  ensure(manifest.corpus.length > 0, "语料清单不能为空");
  for (const documentKey of new Set(manifest.corpus.map((item) => item.documentKey))) {
    ensure(activeByDocument.has(documentKey), `${documentKey} 缺少当前生效版本`);
  }
  return evidenceById;
}

function validateCaseShape(testCase, split, evidenceById) {
  ensure(testCase && typeof testCase === "object" && !Array.isArray(testCase), `${split} 存在非对象题目`);
  for (const field of Object.keys(testCase)) {
    ensure(CASE_FIELDS.has(field), `${testCase.id ?? split} 包含未知字段 ${field}`);
  }
  ensure(CASE_ID_PATTERNS[split].test(testCase.id), `${testCase.id ?? split} 的编号格式错误`);
  ensure(testCase.split === split, `${testCase.id} 的 split 应为 ${split}`);
  ensure(typeof testCase.canonicalFactId === "string" && /^[a-z0-9.-]+$/.test(testCase.canonicalFactId),
    `${testCase.id} 的 canonicalFactId 无效`);
  ensure(typeof testCase.question === "string" && testCase.question.trim().length > 0
      && testCase.question.length <= 2000, `${testCase.id} 的问题为空或超过 2000 字符`);
  ensure(OUTCOMES.has(testCase.expectedOutcome), `${testCase.id} 的 expectedOutcome 无效`);
  ensure(EXECUTION_MODES.has(testCase.executionMode), `${testCase.id} 的 executionMode 无效`);
  ensure(Array.isArray(testCase.categories) && testCase.categories.length > 0,
    `${testCase.id} 至少需要一个分类`);
  ensure(new Set(testCase.categories).size === testCase.categories.length, `${testCase.id} 的分类重复`);
  for (const category of testCase.categories) {
    ensure(CATEGORIES.has(category), `${testCase.id} 包含未知分类 ${category}`);
  }
  ensure(Array.isArray(testCase.expectedFacts), `${testCase.id} 缺少 expectedFacts`);
  ensure(Array.isArray(testCase.evidenceIds), `${testCase.id} 缺少 evidenceIds`);
  ensure(new Set(testCase.expectedFacts).size === testCase.expectedFacts.length,
    `${testCase.id} 的 expectedFacts 重复`);
  ensure(new Set(testCase.evidenceIds).size === testCase.evidenceIds.length,
    `${testCase.id} 的 evidenceIds 重复`);

  if (testCase.expectedOutcome === "ANSWER") {
    ensure(testCase.executionMode === "RAG", `${testCase.id} 的可回答题必须使用 RAG`);
    ensure(testCase.expectedFacts.length > 0 && testCase.evidenceIds.length > 0,
      `${testCase.id} 的可回答题必须提供事实和证据`);
  } else if (testCase.expectedOutcome === "CONTRACT") {
    ensure(testCase.executionMode === "CONTRACT_TEST", `${testCase.id} 的契约题必须使用 CONTRACT_TEST`);
    ensure(Array.isArray(testCase.expectedAssertions) && testCase.expectedAssertions.length > 0,
      `${testCase.id} 的契约题缺少断言`);
    ensure(testCase.expectedFacts.length === 0 && testCase.evidenceIds.length === 0,
      `${testCase.id} 的契约题不得伪装为语料问答`);
  } else {
    ensure(testCase.executionMode === "RAG", `${testCase.id} 的拒答或拒绝题必须使用 RAG`);
    ensure(testCase.expectedFacts.length === 0 && testCase.evidenceIds.length === 0,
      `${testCase.id} 的拒答或拒绝题不应绑定答案证据`);
    ensure(typeof testCase.rationale === "string" && testCase.rationale.length > 0,
      `${testCase.id} 的拒答或拒绝题缺少原因`);
  }

  const evidenceText = testCase.evidenceIds.map((evidenceId) => {
    const evidence = evidenceById.get(evidenceId);
    ensure(evidence, `${testCase.id} 引用了不存在的证据 ${evidenceId}`);
    return evidence.text;
  }).join("\n");
  for (const fact of testCase.expectedFacts) {
    ensure(typeof fact === "string" && fact.length > 0, `${testCase.id} 包含空预期事实`);
    ensure(evidenceText.includes(fact), `${testCase.id} 的预期事实无法在指定证据中逐字核对：${fact}`);
  }
}

function validateF12DevelopmentCoverage(development, test) {
  const originals = new Map();
  const variants = new Map();
  for (const testCase of development) {
    const originalMatch = /^F12-Q(\d{2})$/.exec(testCase.origin ?? "");
    const variantMatch = /^F12-Q(\d{2})-P1$/.exec(testCase.origin ?? "");
    if (originalMatch) {
      originals.set(originalMatch[1], testCase);
    }
    if (variantMatch) {
      variants.set(variantMatch[1], testCase);
    }
  }
  for (let number = 1; number <= 20; number += 1) {
    const key = String(number).padStart(2, "0");
    ensure(originals.has(key), `开发集缺少 F12-Q${key}`);
    ensure(variants.has(key), `开发集缺少 F12-Q${key} 的同事实改写`);
    const original = originals.get(key);
    const variant = variants.get(key);
    ensure(variant.variantOf === original.id, `${variant.id} 的 variantOf 未指向 ${original.id}`);
    ensure(variant.canonicalFactId === original.canonicalFactId,
      `${variant.id} 与 ${original.id} 的 canonicalFactId 不一致`);
  }
  ensure(test.every((testCase) => !String(testCase.origin ?? "").startsWith("F12-Q") && !testCase.variantOf),
    "固定测试集不得包含 F12 功能题或其改写");
}

function validateCategoryCoverage(cases, minimums) {
  const counts = Object.fromEntries([...CATEGORIES].map((category) => [category, 0]));
  for (const testCase of cases) {
    for (const category of testCase.categories) {
      counts[category] += 1;
    }
  }
  for (const [category, minimum] of Object.entries(minimums ?? {})) {
    ensure(CATEGORIES.has(category), `清单包含未知分类 ${category}`);
    ensure(Number.isInteger(minimum) && minimum > 0, `${category} 的最小题数无效`);
    ensure(counts[category] >= minimum, `${category} 只有 ${counts[category]} 题，少于要求的 ${minimum} 题`);
  }
  return counts;
}

export async function validateDataset(root = EVALUATION_ROOT) {
  const manifestFile = resolve(root, "f21-manifest.json");
  const manifest = await readJson(manifestFile);
  ensure(manifest.schemaVersion === "1.0", "不支持的数据清单版本");
  ensure(typeof manifest.datasetVersion === "string" && manifest.datasetVersion.length > 0,
    "datasetVersion 不能为空");
  ensure(SHA256_PATTERN.test(manifest.test?.sha256), "固定测试集 SHA-256 未锁定");
  const evidenceById = await loadCorpus(manifest, root);
  const developmentFile = resolve(root, manifest.development.file);
  const testFile = resolve(root, manifest.test.file);
  const developmentData = await readJsonLines(developmentFile);
  const testData = await readJsonLines(testFile);
  ensure(developmentData.values.length === manifest.development.expectedCount,
    `开发集应为 ${manifest.development.expectedCount} 题，实际为 ${developmentData.values.length} 题`);
  ensure(testData.values.length === manifest.test.expectedCount,
    `固定测试集应为 ${manifest.test.expectedCount} 题，实际为 ${testData.values.length} 题`);
  ensure(sha256(testData.content) === manifest.test.sha256, "固定测试集 SHA-256 与清单不一致");

  for (const testCase of developmentData.values) {
    validateCaseShape(testCase, "development", evidenceById);
  }
  for (const testCase of testData.values) {
    validateCaseShape(testCase, "test", evidenceById);
  }
  const cases = [...developmentData.values, ...testData.values];
  const ids = new Set();
  const questions = new Set();
  for (const testCase of cases) {
    ensure(!ids.has(testCase.id), `题目编号重复：${testCase.id}`);
    ensure(!questions.has(testCase.question), `题面重复：${testCase.question}`);
    ids.add(testCase.id);
    questions.add(testCase.question);
  }
  for (const testCase of developmentData.values.filter((item) => item.variantOf)) {
    const original = developmentData.values.find((item) => item.id === testCase.variantOf);
    ensure(original, `${testCase.id} 指向不存在的原题 ${testCase.variantOf}`);
    ensure(original.canonicalFactId === testCase.canonicalFactId,
      `${testCase.id} 与原题的 canonicalFactId 不一致`);
  }
  const developmentFacts = new Set(developmentData.values.map((item) => item.canonicalFactId));
  const leakedFacts = testData.values
    .map((item) => item.canonicalFactId)
    .filter((factId) => developmentFacts.has(factId));
  ensure(leakedFacts.length === 0, `开发集事实泄漏到固定测试集：${leakedFacts.join(", ")}`);
  validateF12DevelopmentCoverage(developmentData.values, testData.values);
  const categoryCounts = validateCategoryCoverage(cases, manifest.categoryMinimums);
  return {
    datasetVersion: manifest.datasetVersion,
    totalCount: cases.length,
    developmentCount: developmentData.values.length,
    testCount: testData.values.length,
    developmentSha256: sha256(developmentData.content),
    testSha256: manifest.test.sha256,
    evidenceCount: evidenceById.size,
    categoryCounts,
    manifest,
    cases,
  };
}

function percentile(values, percentileValue) {
  if (values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.max(0, Math.ceil(percentileValue * sorted.length) - 1);
  return sorted[index];
}

function numericValues(results, selector) {
  return results.map(selector).filter((value) => Number.isFinite(value) && value >= 0);
}

export function summarizeResults(cases, results) {
  const resultByCaseId = new Map(results.map((result) => [result.caseId, result]));
  const caseById = new Map(cases.map((testCase) => [testCase.id, testCase]));
  const ragCases = cases.filter((testCase) => testCase.executionMode === "RAG");
  const answerCases = ragCases.filter((testCase) => testCase.expectedOutcome === "ANSWER");
  const refusalCases = ragCases.filter((testCase) => testCase.expectedOutcome !== "ANSWER");
  const completedAnswers = answerCases.filter((testCase) => resultByCaseId.get(testCase.id)?.status === "COMPLETED");
  const reviewedCitations = completedAnswers.filter((testCase) =>
    typeof resultByCaseId.get(testCase.id)?.humanReview?.citationCorrect === "boolean");
  const reviewedEvidence = completedAnswers.filter((testCase) =>
    typeof resultByCaseId.get(testCase.id)?.humanReview?.evidenceSufficient === "boolean");
  const retrievalHits = completedAnswers.filter((testCase) => {
    const actual = new Set(resultByCaseId.get(testCase.id)?.retrievedEvidenceIds ?? []);
    return testCase.evidenceIds.every((evidenceId) => actual.has(evidenceId));
  });
  const correctRefusals = refusalCases.filter((testCase) => {
    const result = resultByCaseId.get(testCase.id);
    if (testCase.expectedOutcome === "DENIED") {
      return result?.status === "DENIED";
    }
    return result?.status === "COMPLETED" && result.noEvidence === true;
  });
  const attemptedChatRuns = results.filter((result) =>
    caseById.get(result.caseId)?.executionMode === "RAG"
      && Number.isFinite(result.metrics?.generationMs));
  const knownUsage = attemptedChatRuns.filter((result) => Number.isFinite(result.usage?.totalTokens));
  const totalTokens = knownUsage.reduce((sum, result) => sum + result.usage.totalTokens, 0);
  const completedRagRuns = results.filter((result) =>
    caseById.get(result.caseId)?.executionMode === "RAG" && result.status === "COMPLETED");
  const knownEmbeddingUsage = completedRagRuns.filter((result) =>
    Number.isFinite(result.metrics?.embeddingTotalTokens));
  const embeddingTotalTokens = knownEmbeddingUsage.reduce(
    (sum, result) => sum + result.metrics.embeddingTotalTokens, 0);
  const retrievalMs = numericValues(results, (result) => result.metrics?.retrievalMs);
  const rerankMs = numericValues(results, (result) => result.metrics?.rerankMs);
  const generationMs = numericValues(results, (result) => result.metrics?.generationMs);
  const totalMs = numericValues(results, (result) => result.metrics?.totalMs);
  const attemptedRerankStatuses = new Set(["APPLIED", "FALLBACK_UNAVAILABLE", "FALLBACK_INVALID_RESPONSE"]);
  const attemptedRerankRuns = results.filter((result) =>
    attemptedRerankStatuses.has(result.metrics?.rerankStatus));
  const knownRerankUsage = attemptedRerankRuns.filter((result) =>
    Number.isFinite(result.metrics?.rerankTotalTokens));
  const rerankTotalTokens = knownRerankUsage.reduce(
    (sum, result) => sum + result.metrics.rerankTotalTokens, 0);
  const rerankStatuses = {};
  for (const result of results) {
    const status = result.metrics?.rerankStatus;
    if (typeof status === "string" && status.length > 0) {
      rerankStatuses[status] = (rerankStatuses[status] ?? 0) + 1;
    }
  }
  return {
    denominators: {
      allCases: cases.length,
      ragCases: ragCases.length,
      answerableCases: answerCases.length,
      refusalCases: refusalCases.length,
      completedAnswers: completedAnswers.length,
      citationHumanReviewed: reviewedCitations.length,
      evidenceHumanReviewed: reviewedEvidence.length,
      usageKnown: knownUsage.length,
    },
    quality: {
      retrievalHit: { passed: retrievalHits.length, total: answerCases.length },
      citationCorrect: {
        passed: reviewedCitations.filter((testCase) =>
          resultByCaseId.get(testCase.id).humanReview.citationCorrect).length,
        total: reviewedCitations.length,
      },
      evidenceSufficient: {
        passed: reviewedEvidence.filter((testCase) =>
          resultByCaseId.get(testCase.id).humanReview.evidenceSufficient).length,
        total: reviewedEvidence.length,
      },
      refusalCorrect: { passed: correctRefusals.length, total: refusalCases.length },
    },
    usage: {
      attemptedRuns: attemptedChatRuns.length,
      noCallRuns: results.length - attemptedChatRuns.length,
      knownRuns: knownUsage.length,
      unknownRuns: attemptedChatRuns.length - knownUsage.length,
      totalTokens: attemptedChatRuns.length === 0
        ? 0
        : (knownUsage.length === attemptedChatRuns.length ? totalTokens : null),
    },
    embedding: {
      attemptedRuns: completedRagRuns.length,
      usageKnownRuns: knownEmbeddingUsage.length,
      usageUnknownRuns: completedRagRuns.length - knownEmbeddingUsage.length,
      totalTokens: completedRagRuns.length === 0
        ? 0
        : (knownEmbeddingUsage.length === completedRagRuns.length ? embeddingTotalTokens : null),
    },
    rerank: {
      statuses: rerankStatuses,
      attemptedRuns: attemptedRerankRuns.length,
      noCallRuns: results.length - attemptedRerankRuns.length,
      usageKnownRuns: knownRerankUsage.length,
      usageUnknownRuns: attemptedRerankRuns.length - knownRerankUsage.length,
      totalTokens: attemptedRerankRuns.length === 0
        ? 0
        : (knownRerankUsage.length === attemptedRerankRuns.length ? rerankTotalTokens : null),
    },
    latencyMs: {
      retrieval: { known: retrievalMs.length, p50: percentile(retrievalMs, 0.5), p95: percentile(retrievalMs, 0.95) },
      rerank: { known: rerankMs.length, p50: percentile(rerankMs, 0.5), p95: percentile(rerankMs, 0.95) },
      generation: { known: generationMs.length, p50: percentile(generationMs, 0.5), p95: percentile(generationMs, 0.95) },
      total: { known: totalMs.length, p50: percentile(totalMs, 0.5), p95: percentile(totalMs, 0.95) },
    },
  };
}

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

const TUNING_FACTOR_PATHS = Object.freeze({
  "top-k": [["retrieval", "topK"]],
  "evidence-budget": [["answer", "maxEvidenceTokens"]],
  "minimum-vector-score": [["answer", "minimumScores", "vector"]],
  "minimum-fusion-score": [["answer", "minimumScores", "fusion"]],
  "minimum-rerank-score": [["answer", "minimumScores", "rerank"]],
  chunking: [["index", "configHash"], ["index", "chunker"]],
  embedding: [["embedding"], ["index", "configHash"], ["index", "embedding"]],
  prompt: [["prompt", "version"]],
});

function valueAtPath(value, path) {
  return path.reduce((current, key) => current?.[key], value);
}

function withoutPaths(value, paths) {
  const comparable = JSON.parse(JSON.stringify(value));
  for (const path of paths) {
    const parent = path.slice(0, -1).reduce((current, key) => current?.[key], comparable);
    if (parent && typeof parent === "object") {
      delete parent[path.at(-1)];
    }
  }
  return comparable;
}

function nonEmptyString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function validateObjectFields(value, fields, name) {
  ensure(value && typeof value === "object" && !Array.isArray(value),
    `运行记录的 ${name} 必须为对象`);
  const allowedFields = new Set(fields);
  ensure(fields.every((field) => Object.hasOwn(value, field)),
    `运行记录的 ${name} 缺少必需字段`);
  ensure(Object.keys(value).every((field) => allowedFields.has(field)),
    `运行记录的 ${name} 包含未允许字段`);
}

function validatePositiveInteger(value, name) {
  ensure(Number.isInteger(value) && value > 0, `运行记录的 ${name} 无效`);
}

function validateScore(value, minimum, name) {
  ensure(Number.isFinite(value) && value >= minimum && value <= 1,
    `运行记录的 ${name} 无效`);
}

function validateF24Configuration(config) {
  validateObjectFields(config,
    ["schemaVersion", "mode", "retrieval", "rerank", "answer", "chat", "embedding", "index", "prompt"],
    "配置快照");
  ensure(config.schemaVersion === "f24-v1", "运行记录必须使用 f24-v1 配置快照");
  ensure(config.mode === "RAG", "运行记录的配置快照模式必须为 RAG");
  validateObjectFields(config.chat, ["role", "provider", "model", "temperature"], "Chat 配置");
  ensure(config.chat?.role === "CHAT" && nonEmptyString(config.chat?.provider)
      && nonEmptyString(config.chat?.model),
    "运行记录缺少 Chat 角色、提供方或模型");
  ensure(config.chat.temperature == null || Number.isFinite(config.chat.temperature),
    "运行记录的 Chat temperature 无效");
  validateObjectFields(config.embedding, ["role", "provider", "model", "dimensions"], "Embedding 配置");
  ensure(config.embedding?.role === "EMBEDDING" && nonEmptyString(config.embedding?.provider)
      && nonEmptyString(config.embedding?.model) && Number.isInteger(config.embedding?.dimensions)
      && config.embedding.dimensions > 0,
    "运行记录缺少 Embedding 角色、提供方、模型或维度");
  validateObjectFields(config.retrieval,
    ["strategy", "topK", "candidateMultiplier", "rrfK", "maxKeywords"], "检索配置");
  ensure(["VECTOR", "FUSION"].includes(config.retrieval?.strategy)
      && Number.isInteger(config.retrieval?.topK) && config.retrieval.topK > 0,
    "运行记录缺少有效的检索策略或 TopK");
  validatePositiveInteger(config.retrieval.candidateMultiplier, "检索候选倍数");
  validatePositiveInteger(config.retrieval.rrfK, "RRF 常量");
  validatePositiveInteger(config.retrieval.maxKeywords, "最大关键词数");
  validateObjectFields(config.rerank,
    ["role", "enabled", "provider", "model", "candidateMultiplier", "maxCandidates",
      "maxEstimatedItemTokens", "maxEstimatedRequestTokens", "timeoutMs", "instruction"],
    "Rerank 配置");
  ensure(config.rerank?.role === "RERANK" && typeof config.rerank?.enabled === "boolean"
      && nonEmptyString(config.rerank?.provider) && nonEmptyString(config.rerank?.model),
    "运行记录缺少 Rerank 角色、开关、提供方或模型");
  validatePositiveInteger(config.rerank.candidateMultiplier, "重排序候选倍数");
  validatePositiveInteger(config.rerank.maxCandidates, "重排序最大候选数");
  validatePositiveInteger(config.rerank.maxEstimatedItemTokens, "重排序单项 Token 上限");
  validatePositiveInteger(config.rerank.maxEstimatedRequestTokens, "重排序请求 Token 上限");
  validatePositiveInteger(config.rerank.timeoutMs, "重排序超时");
  ensure(nonEmptyString(config.rerank.instruction), "运行记录缺少重排序指令");
  validateObjectFields(config.answer, ["maxEvidenceTokens", "minimumScores"], "回答配置");
  validatePositiveInteger(config.answer.maxEvidenceTokens, "证据 Token 预算");
  validateObjectFields(config.answer.minimumScores, ["vector", "fusion", "rerank"], "回答阈值");
  validateScore(config.answer?.minimumScores?.vector, -1, "向量拒答阈值");
  validateScore(config.answer?.minimumScores?.fusion, 0, "融合拒答阈值");
  validateScore(config.answer?.minimumScores?.rerank, 0, "重排序拒答阈值");
  validateObjectFields(config.index, ["configHash", "parser", "chunker", "embedding"], "索引配置");
  ensure(SHA256_PATTERN.test(config.index?.configHash), "运行记录缺少可追溯的索引配置哈希");
  validateObjectFields(config.index.parser, ["version"], "索引解析配置");
  ensure(nonEmptyString(config.index.parser.version), "运行记录缺少索引解析器版本");
  validateObjectFields(config.index.chunker,
    ["version", "tokenizer", "maxTokens", "maxChunks"], "索引分块配置");
  ensure(nonEmptyString(config.index.chunker.version) && nonEmptyString(config.index.chunker.tokenizer),
    "运行记录缺少索引分块器或 Tokenizer 版本");
  validatePositiveInteger(config.index.chunker.maxTokens, "索引分块 Token 上限");
  validatePositiveInteger(config.index.chunker.maxChunks, "索引最大分块数");
  validateObjectFields(config.index.embedding, ["provider", "model", "dimensions"], "索引 Embedding 配置");
  ensure(canonicalJson(config.index.embedding) === canonicalJson({
    provider: config.embedding.provider,
    model: config.embedding.model,
    dimensions: config.embedding.dimensions,
  }), "运行记录的查询 Embedding 与索引 Embedding 不一致");
  validateObjectFields(config.prompt, ["version"], "提示词配置");
  ensure(nonEmptyString(config.prompt?.version), "运行记录缺少提示词版本");
}

export function compareTuningConfigurations(factor, baselineConfig, candidateConfig) {
  const paths = TUNING_FACTOR_PATHS[factor];
  ensure(paths, `不支持的调参因素 ${factor}；可选值：${Object.keys(TUNING_FACTOR_PATHS).join(", ")}`);
  validateF24Configuration(baselineConfig);
  validateF24Configuration(candidateConfig);
  const baselineValues = paths.map((path) => valueAtPath(baselineConfig, path));
  const candidateValues = paths.map((path) => valueAtPath(candidateConfig, path));
  ensure(canonicalJson(baselineValues) !== canonicalJson(candidateValues),
    `两次运行的 ${factor} 配置没有变化`);
  ensure(canonicalJson(withoutPaths(baselineConfig, paths))
      === canonicalJson(withoutPaths(candidateConfig, paths)),
  `两次运行除 ${factor} 外还存在其他配置差异`);
  if (factor === "chunking" || factor === "embedding") {
    ensure(baselineConfig.index.configHash !== candidateConfig.index.configHash,
      `${factor} 变化后必须使用新的索引配置哈希`);
  }
  if (factor === "chunking") {
    ensure(canonicalJson(baselineConfig.index.chunker) !== canonicalJson(candidateConfig.index.chunker),
      "分块因素比较必须实际改变分块配置");
  }
  if (factor === "embedding") {
    ensure(canonicalJson(baselineConfig.embedding) !== canonicalJson(candidateConfig.embedding),
      "Embedding 因素比较必须实际改变查询与索引模型配置");
  }
  if (factor === "minimum-vector-score") {
    ensure(baselineConfig.retrieval.strategy === "VECTOR" && baselineConfig.rerank.enabled === false,
      "向量阈值只能在 VECTOR 且关闭重排序时比较");
  }
  if (factor === "minimum-fusion-score") {
    ensure(baselineConfig.retrieval.strategy === "FUSION" && baselineConfig.rerank.enabled === false,
      "融合阈值只能在 FUSION 且关闭重排序时比较");
  }
  if (factor === "minimum-rerank-score") {
    ensure(baselineConfig.rerank.enabled === true,
      "重排序阈值只能在开启重排序时比较");
  }
  return {
    factor,
    baseline: Object.fromEntries(paths.map((path, index) => [path.join("."), baselineValues[index]])),
    candidate: Object.fromEntries(paths.map((path, index) => [path.join("."), candidateValues[index]])),
  };
}

function withoutRetrievalStrategy(configSnapshot) {
  const comparable = JSON.parse(JSON.stringify(configSnapshot));
  delete comparable.retrieval.strategy;
  return comparable;
}

function withoutRerankEnabled(configSnapshot) {
  const comparable = JSON.parse(JSON.stringify(configSnapshot));
  delete comparable.rerank.enabled;
  return comparable;
}

function validateRerankMetrics(run, expectedStatus) {
  const caseById = new Map(run.cases.map((testCase) => [testCase.id, testCase]));
  for (const result of run.results) {
    const testCase = caseById.get(result.caseId);
    if (testCase?.executionMode !== "RAG" || result.status !== "COMPLETED") {
      continue;
    }
    ensure(RERANK_STATUSES.has(result.metrics?.rerankStatus),
      `${result.caseId} 缺少有效的重排序状态`);
    ensure(Number.isInteger(result.metrics?.rerankCandidateCount)
        && result.metrics.rerankCandidateCount >= 0,
      `${result.caseId} 缺少有效的重排序候选数`);
    ensure(Number.isFinite(result.metrics?.rerankMs) && result.metrics.rerankMs >= 0,
      `${result.caseId} 缺少有效的重排序耗时`);
    ensure(result.metrics.rerankTotalTokens == null
        || (Number.isInteger(result.metrics.rerankTotalTokens) && result.metrics.rerankTotalTokens >= 0),
      `${result.caseId} 的重排序 Token 用量无效`);
    if (expectedStatus === "DISABLED") {
      ensure(result.metrics.rerankStatus === "DISABLED",
        `${result.caseId} 的关闭组仍执行了重排序`);
    } else {
      ensure(!["NOT_REACHED", "DISABLED"].includes(result.metrics.rerankStatus),
        `${result.caseId} 的开启组未进入重排序阶段`);
    }
  }
}

export function validateF24Metrics(result, maximumEvidenceTokens) {
  validateObjectFields(result.metrics,
    ["evidenceCount", "retrievedEvidenceCount", "scoreFilteredEvidenceCount",
      "budgetFilteredEvidenceCount", "evidenceTokenCount", "citedCount", "noEvidence", "retrievalMs",
      "embeddingTotalTokens", "rerankStatus", "rerankCandidateCount", "rerankMs", "rerankTotalTokens",
      "generationMs", "totalMs", "firstDeltaMs"],
    `${result.caseId} 的 RAG 指标`);
  const countFields = [
    "evidenceCount",
    "retrievedEvidenceCount",
    "scoreFilteredEvidenceCount",
    "budgetFilteredEvidenceCount",
    "evidenceTokenCount",
  ];
  for (const field of countFields) {
    ensure(Number.isInteger(result.metrics?.[field]) && result.metrics[field] >= 0,
      `${result.caseId} 缺少有效的 ${field}`);
  }
  ensure(result.metrics.retrievedEvidenceCount === result.metrics.evidenceCount
      + result.metrics.scoreFilteredEvidenceCount + result.metrics.budgetFilteredEvidenceCount,
  `${result.caseId} 的证据筛选数量无法守恒`);
  ensure(result.metrics.evidenceTokenCount <= maximumEvidenceTokens,
    `${result.caseId} 的证据 Token 数超过配置预算`);
  ensure(result.metrics.evidenceCount === result.retrievedEvidenceIds.length,
    `${result.caseId} 的入模证据数量与证据列表不一致`);
  ensure(result.metrics.citedCount === result.citedEvidenceIds.length,
    `${result.caseId} 的引用数量与引用列表不一致`);
  ensure(result.noEvidence === (result.metrics.evidenceCount === 0),
    `${result.caseId} 的 noEvidence 与实际入模证据数量不一致`);
  ensure(result.metrics.evidenceCount > 0 || result.metrics.evidenceTokenCount === 0,
    `${result.caseId} 没有入模证据时 Token 数必须为 0`);
  ensure(Number.isInteger(result.metrics.citedCount) && result.metrics.citedCount >= 0,
    `${result.caseId} 缺少有效的 citedCount`);
  ensure(Number.isInteger(result.metrics.retrievalMs) && result.metrics.retrievalMs >= 0,
    `${result.caseId} 缺少有效的 retrievalMs`);
  ensure(result.metrics.embeddingTotalTokens == null
      || (Number.isInteger(result.metrics.embeddingTotalTokens)
        && result.metrics.embeddingTotalTokens >= 0),
    `${result.caseId} 的 Embedding Token 用量无效`);
  ensure(RERANK_STATUSES.has(result.metrics.rerankStatus),
    `${result.caseId} 缺少有效的重排序状态`);
  ensure(Number.isInteger(result.metrics.rerankCandidateCount)
      && result.metrics.rerankCandidateCount >= 0,
    `${result.caseId} 缺少有效的重排序候选数`);
  ensure(Number.isInteger(result.metrics.rerankMs) && result.metrics.rerankMs >= 0,
    `${result.caseId} 缺少有效的重排序耗时`);
  ensure(result.metrics.rerankTotalTokens == null
      || (Number.isInteger(result.metrics.rerankTotalTokens) && result.metrics.rerankTotalTokens >= 0),
    `${result.caseId} 的重排序 Token 用量无效`);
  ensure(result.metrics.generationMs == null
      || (Number.isInteger(result.metrics.generationMs) && result.metrics.generationMs >= 0),
    `${result.caseId} 的生成耗时无效`);
  ensure((result.metrics.evidenceCount === 0) === (result.metrics.generationMs == null),
    `${result.caseId} 的生成耗时与入模证据数量不一致`);
  ensure(Number.isInteger(result.metrics.totalMs) && result.metrics.totalMs >= 0,
    `${result.caseId} 缺少有效的 totalMs`);
  ensure(result.metrics.firstDeltaMs == null
      || (Number.isInteger(result.metrics.firstDeltaMs) && result.metrics.firstDeltaMs >= 0),
    `${result.caseId} 的首增量耗时无效`);
}

function validateChatUsage(result) {
  if (result.usage == null) {
    return;
  }
  validateObjectFields(result.usage, ["promptTokens", "completionTokens", "totalTokens"],
    `${result.caseId} 的 Chat Token 用量`);
  for (const field of ["promptTokens", "completionTokens", "totalTokens"]) {
    ensure(result.usage[field] == null
        || (Number.isInteger(result.usage[field]) && result.usage[field] >= 0),
      `${result.caseId} 的 ${field} 无效`);
  }
  if (result.usage.promptTokens != null && result.usage.completionTokens != null
      && result.usage.totalTokens != null) {
    ensure(result.usage.totalTokens === result.usage.promptTokens + result.usage.completionTokens,
      `${result.caseId} 的 Chat Token 总量无法守恒`);
  }
}

function retrievalHit(testCase, result) {
  if (testCase.executionMode !== "RAG" || testCase.expectedOutcome !== "ANSWER"
      || result?.status !== "COMPLETED") {
    return false;
  }
  const actual = new Set(result.retrievedEvidenceIds ?? []);
  return testCase.evidenceIds.every((evidenceId) => actual.has(evidenceId));
}

function metricComparison(vectorMetric, fusionMetric) {
  return {
    vector: vectorMetric,
    fusion: fusionMetric,
    passedDelta: fusionMetric.passed - vectorMetric.passed,
    totalDelta: fusionMetric.total - vectorMetric.total,
  };
}

function nullableDelta(vectorValue, fusionValue) {
  return Number.isFinite(vectorValue) && Number.isFinite(fusionValue)
    ? fusionValue - vectorValue
    : null;
}

function latencyComparison(vectorLatency, fusionLatency) {
  return {
    vector: vectorLatency,
    fusion: fusionLatency,
    p50Delta: nullableDelta(vectorLatency.p50, fusionLatency.p50),
    p95Delta: nullableDelta(vectorLatency.p95, fusionLatency.p95),
  };
}

function retrievalMetric(cases, resultByCaseId) {
  const answerCases = cases.filter((testCase) =>
    testCase.executionMode === "RAG" && testCase.expectedOutcome === "ANSWER");
  return {
    passed: answerCases.filter((testCase) => retrievalHit(testCase, resultByCaseId.get(testCase.id))).length,
    total: answerCases.length,
  };
}

export function compareResults(cases, vectorResults, fusionResults) {
  const vectorByCaseId = new Map(vectorResults.map((result) => [result.caseId, result]));
  const fusionByCaseId = new Map(fusionResults.map((result) => [result.caseId, result]));
  const answerCases = cases.filter((testCase) =>
    testCase.executionMode === "RAG" && testCase.expectedOutcome === "ANSWER");
  const retrievalGains = [];
  const retrievalRegressions = [];
  const changedEvidence = [];
  for (const testCase of answerCases) {
    const vectorResult = vectorByCaseId.get(testCase.id);
    const fusionResult = fusionByCaseId.get(testCase.id);
    const vectorHit = retrievalHit(testCase, vectorResult);
    const fusionHit = retrievalHit(testCase, fusionResult);
    if (!vectorHit && fusionHit) {
      retrievalGains.push(testCase.id);
    } else if (vectorHit && !fusionHit) {
      retrievalRegressions.push(testCase.id);
    }
    if (canonicalJson(vectorResult?.retrievedEvidenceIds ?? [])
        !== canonicalJson(fusionResult?.retrievedEvidenceIds ?? [])) {
      changedEvidence.push(testCase.id);
    }
  }

  const vectorSummary = summarizeResults(cases, vectorResults);
  const fusionSummary = summarizeResults(cases, fusionResults);
  const categories = [...new Set(cases.flatMap((testCase) => testCase.categories ?? []))].sort();
  const retrievalByCategory = Object.fromEntries(categories.map((category) => {
    const categoryCases = cases.filter((testCase) => testCase.categories?.includes(category));
    return [category, metricComparison(
      retrievalMetric(categoryCases, vectorByCaseId), retrievalMetric(categoryCases, fusionByCaseId))];
  }));
  return {
    quality: {
      retrievalHit: metricComparison(vectorSummary.quality.retrievalHit, fusionSummary.quality.retrievalHit),
      citationCorrect: metricComparison(vectorSummary.quality.citationCorrect,
        fusionSummary.quality.citationCorrect),
      evidenceSufficient: metricComparison(vectorSummary.quality.evidenceSufficient,
        fusionSummary.quality.evidenceSufficient),
      refusalCorrect: metricComparison(vectorSummary.quality.refusalCorrect, fusionSummary.quality.refusalCorrect),
    },
    retrievalByCategory,
    changes: { retrievalGains, retrievalRegressions, changedEvidence },
    usage: {
      vector: vectorSummary.usage,
      fusion: fusionSummary.usage,
      totalTokensDelta: nullableDelta(vectorSummary.usage.totalTokens, fusionSummary.usage.totalTokens),
    },
    embeddingUsage: {
      vector: vectorSummary.embedding,
      fusion: fusionSummary.embedding,
      totalTokensDelta: nullableDelta(vectorSummary.embedding.totalTokens,
        fusionSummary.embedding.totalTokens),
    },
    latencyMs: {
      retrieval: latencyComparison(vectorSummary.latencyMs.retrieval, fusionSummary.latencyMs.retrieval),
      generation: latencyComparison(vectorSummary.latencyMs.generation, fusionSummary.latencyMs.generation),
      total: latencyComparison(vectorSummary.latencyMs.total, fusionSummary.latencyMs.total),
    },
  };
}

function rerankMetricComparison(comparison) {
  return {
    disabled: comparison.vector,
    enabled: comparison.fusion,
    passedDelta: comparison.passedDelta,
    totalDelta: comparison.totalDelta,
  };
}

function rerankLatencyComparison(comparison) {
  return {
    disabled: comparison.vector,
    enabled: comparison.fusion,
    p50Delta: comparison.p50Delta,
    p95Delta: comparison.p95Delta,
  };
}

export function compareRerankResults(cases, disabledResults, enabledResults) {
  const baseComparison = compareResults(cases, disabledResults, enabledResults);
  const disabledSummary = summarizeResults(cases, disabledResults);
  const enabledSummary = summarizeResults(cases, enabledResults);
  return {
    quality: Object.fromEntries(Object.entries(baseComparison.quality)
      .map(([name, comparison]) => [name, rerankMetricComparison(comparison)])),
    retrievalByCategory: Object.fromEntries(Object.entries(baseComparison.retrievalByCategory)
      .map(([name, comparison]) => [name, rerankMetricComparison(comparison)])),
    changes: baseComparison.changes,
    usage: {
      chat: {
        disabled: disabledSummary.usage,
        enabled: enabledSummary.usage,
        totalTokensDelta: nullableDelta(disabledSummary.usage.totalTokens, enabledSummary.usage.totalTokens),
      },
      rerank: {
        disabled: disabledSummary.rerank,
        enabled: enabledSummary.rerank,
        totalTokensDelta: nullableDelta(disabledSummary.rerank.totalTokens,
          enabledSummary.rerank.totalTokens),
      },
      embedding: {
        disabled: disabledSummary.embedding,
        enabled: enabledSummary.embedding,
        totalTokensDelta: nullableDelta(disabledSummary.embedding.totalTokens,
          enabledSummary.embedding.totalTokens),
      },
    },
    latencyMs: {
      retrieval: rerankLatencyComparison(baseComparison.latencyMs.retrieval),
      rerank: {
        disabled: disabledSummary.latencyMs.rerank,
        enabled: enabledSummary.latencyMs.rerank,
        p50Delta: nullableDelta(disabledSummary.latencyMs.rerank.p50,
          enabledSummary.latencyMs.rerank.p50),
        p95Delta: nullableDelta(disabledSummary.latencyMs.rerank.p95,
          enabledSummary.latencyMs.rerank.p95),
      },
      generation: rerankLatencyComparison(baseComparison.latencyMs.generation),
      total: rerankLatencyComparison(baseComparison.latencyMs.total),
    },
  };
}

export function compareStrategyResults(cases, vectorResults, fusionResults, fusionRerankResults) {
  return {
    summaries: {
      vector: summarizeResults(cases, vectorResults),
      fusion: summarizeResults(cases, fusionResults),
      fusionRerank: summarizeResults(cases, fusionRerankResults),
    },
    comparisons: {
      vectorToFusion: compareResults(cases, vectorResults, fusionResults),
      fusionToFusionRerank: compareRerankResults(cases, fusionResults, fusionRerankResults),
      vectorToFusionRerank: compareResults(cases, vectorResults, fusionRerankResults),
    },
  };
}

async function loadValidatedRun(runFile) {
  const dataset = await validateDataset();
  const run = await readJson(resolve(process.cwd(), runFile));
  const metadata = run.metadata ?? {};
  ensure(metadata.datasetVersion === dataset.datasetVersion, "运行记录的数据集版本不匹配");
  ensure(metadata.split === "development" || metadata.split === "test", "运行记录的 split 无效");
  const selectedCases = dataset.cases.filter((testCase) => testCase.split === metadata.split);
  const expectedDatasetSha256 = metadata.split === "development"
    ? dataset.developmentSha256
    : dataset.testSha256;
  ensure(metadata.datasetSha256 === expectedDatasetSha256, "运行记录的数据集 SHA-256 不匹配");
  ensure(GIT_COMMIT_PATTERN.test(metadata.gitCommit), "运行记录缺少可追溯的 Git 提交");
  ensure(Number.isFinite(Date.parse(metadata.startedAt)) && Number.isFinite(Date.parse(metadata.completedAt)),
    "运行记录缺少有效的开始或结束时间");
  ensure(Date.parse(metadata.startedAt) <= Date.parse(metadata.completedAt), "运行记录结束时间早于开始时间");
  ensure(typeof metadata.environment === "string" && metadata.environment.length > 0,
    "运行记录缺少环境说明");
  ensure(metadata.configSnapshot && typeof metadata.configSnapshot === "object",
    "运行记录缺少模型与检索配置快照");
  validateF24Configuration(metadata.configSnapshot);
  ensure(typeof metadata.reviewer === "string" && metadata.reviewer.length > 0,
    "运行记录缺少人工复核人");
  ensure(Array.isArray(run.results), "运行记录缺少 results");
  ensure(run.results.length === selectedCases.length,
    `运行记录应包含 ${selectedCases.length} 个结果，实际为 ${run.results.length} 个`);
  const expectedIds = new Set(selectedCases.map((testCase) => testCase.id));
  const caseById = new Map(selectedCases.map((testCase) => [testCase.id, testCase]));
  const resultIds = new Set();
  for (const result of run.results) {
    ensure(result && typeof result === "object" && !Array.isArray(result), "运行记录包含非对象结果");
    ensure(expectedIds.has(result.caseId), `运行记录包含范围外题目 ${result.caseId}`);
    ensure(!resultIds.has(result.caseId), `运行记录题目重复 ${result.caseId}`);
    ensure(!JSON.stringify(result).includes("\"modelScore\""),
      `${result.caseId} 不得使用模型自评分`);
    ensure(["COMPLETED", "DENIED", "FAILED", "CONTRACT_PASSED", "CONTRACT_FAILED"].includes(result.status),
      `${result.caseId} 的运行状态无效`);
    ensure(Array.isArray(result.retrievedEvidenceIds) && Array.isArray(result.citedEvidenceIds),
      `${result.caseId} 缺少检索或引用证据列表`);
    ensure(new Set(result.retrievedEvidenceIds).size === result.retrievedEvidenceIds.length,
      `${result.caseId} 的检索证据重复`);
    ensure(new Set(result.citedEvidenceIds).size === result.citedEvidenceIds.length,
      `${result.caseId} 的引用证据重复`);
    ensure(result.humanReview && typeof result.humanReview === "object",
      `${result.caseId} 缺少人工复核记录`);
    ensure(typeof result.humanReview.notes === "string", `${result.caseId} 缺少人工复核说明`);
    validateChatUsage(result);
    const testCase = caseById.get(result.caseId);
    if (testCase.executionMode === "CONTRACT_TEST") {
      ensure(result.status === "CONTRACT_PASSED" || result.status === "CONTRACT_FAILED",
        `${result.caseId} 的契约题状态无效`);
    } else if (result.status === "COMPLETED") {
      ensure(typeof result.noEvidence === "boolean", `${result.caseId} 缺少 noEvidence`);
      ensure(result.metrics && Number.isFinite(result.metrics.retrievalMs)
          && Number.isFinite(result.metrics.totalMs), `${result.caseId} 缺少检索或总耗时`);
      validateF24Metrics(result, metadata.configSnapshot.answer.maxEvidenceTokens);
      ensure(result.metrics.generationMs != null || result.usage == null,
        `${result.caseId} 未调用 Chat 模型却记录了用量`);
      if (testCase.expectedOutcome === "ANSWER") {
        ensure(Number.isFinite(result.metrics.generationMs), `${result.caseId} 缺少生成耗时`);
        ensure(typeof result.humanReview.citationCorrect === "boolean"
            && typeof result.humanReview.evidenceSufficient === "boolean",
          `${result.caseId} 的可回答题缺少人工引用或依据复核`);
      }
    }
    resultIds.add(result.caseId);
  }
  return { metadata, summary: summarizeResults(selectedCases, run.results), results: run.results, cases: selectedCases };
}

export async function summarizeRun(runFile) {
  const { metadata, summary } = await loadValidatedRun(runFile);
  return { metadata, summary };
}

function validateComparableDevelopmentRuns(runs, taskName, requireSameGitCommit = true) {
  ensure(runs.every((run) => run.metadata.split === "development"),
    `${taskName} 只能使用 development 分割`);
  const [first, ...remaining] = runs;
  ensure(remaining.every((run) => run.metadata.datasetVersion === first.metadata.datasetVersion
      && run.metadata.datasetSha256 === first.metadata.datasetSha256),
  `${taskName} 必须使用同一开发集版本和 SHA-256`);
  if (requireSameGitCommit) {
    ensure(remaining.every((run) => run.metadata.gitCommit === first.metadata.gitCommit),
      `${taskName} 必须使用同一 Git 提交`);
  }
  ensure(remaining.every((run) => run.metadata.environment === first.metadata.environment),
    `${taskName} 必须使用同一环境说明`);
}

export async function compareRuns(vectorRunFile, fusionRunFile) {
  const vector = await loadValidatedRun(vectorRunFile);
  const fusion = await loadValidatedRun(fusionRunFile);
  ensure(vector.metadata.split === "development" && fusion.metadata.split === "development",
    "F22 策略比较只能使用 development 分割");
  ensure(vector.metadata.datasetVersion === fusion.metadata.datasetVersion
      && vector.metadata.datasetSha256 === fusion.metadata.datasetSha256,
    "两次运行必须使用同一开发集版本和 SHA-256");
  ensure(vector.metadata.gitCommit === fusion.metadata.gitCommit,
    "两次运行必须使用同一 Git 提交");
  ensure(vector.metadata.environment === fusion.metadata.environment,
    "两次运行必须使用同一环境说明");
  ensure(vector.metadata.configSnapshot.retrieval.strategy === "VECTOR",
    "第一份运行记录的检索策略必须为 VECTOR");
  ensure(fusion.metadata.configSnapshot.retrieval.strategy === "FUSION",
    "第二份运行记录的检索策略必须为 FUSION");
  const comparableVectorConfig = withoutRetrievalStrategy(vector.metadata.configSnapshot);
  const comparableFusionConfig = withoutRetrievalStrategy(fusion.metadata.configSnapshot);
  ensure(canonicalJson(comparableVectorConfig) === canonicalJson(comparableFusionConfig),
    "两次运行除检索策略外的模型、TopK、融合参数和提示词配置必须一致");
  return {
    datasetVersion: vector.metadata.datasetVersion,
    datasetSha256: vector.metadata.datasetSha256,
    gitCommit: vector.metadata.gitCommit,
    environment: vector.metadata.environment,
    vectorRun: vectorRunFile,
    fusionRun: fusionRunFile,
    comparison: compareResults(vector.cases, vector.results, fusion.results),
  };
}

export async function compareRerankRuns(disabledRunFile, enabledRunFile) {
  const disabled = await loadValidatedRun(disabledRunFile);
  const enabled = await loadValidatedRun(enabledRunFile);
  ensure(disabled.metadata.split === "development" && enabled.metadata.split === "development",
    "F23 重排序比较只能使用 development 分割");
  ensure(disabled.metadata.datasetVersion === enabled.metadata.datasetVersion
      && disabled.metadata.datasetSha256 === enabled.metadata.datasetSha256,
    "两次运行必须使用同一开发集版本和 SHA-256");
  ensure(disabled.metadata.gitCommit === enabled.metadata.gitCommit,
    "两次运行必须使用同一 Git 提交");
  ensure(disabled.metadata.environment === enabled.metadata.environment,
    "两次运行必须使用同一环境说明");
  ensure(disabled.metadata.configSnapshot.rerank?.enabled === false,
    "第一份运行记录必须关闭重排序");
  ensure(enabled.metadata.configSnapshot.rerank?.enabled === true,
    "第二份运行记录必须开启重排序");
  validateRerankMetrics(disabled, "DISABLED");
  validateRerankMetrics(enabled, "ENABLED");
  const comparableDisabledConfig = withoutRerankEnabled(disabled.metadata.configSnapshot);
  const comparableEnabledConfig = withoutRerankEnabled(enabled.metadata.configSnapshot);
  ensure(canonicalJson(comparableDisabledConfig) === canonicalJson(comparableEnabledConfig),
    "两次运行除重排序开关外的检索、模型和提示词配置必须一致");
  return {
    datasetVersion: disabled.metadata.datasetVersion,
    datasetSha256: disabled.metadata.datasetSha256,
    gitCommit: disabled.metadata.gitCommit,
    environment: disabled.metadata.environment,
    disabledRun: disabledRunFile,
    enabledRun: enabledRunFile,
    comparison: compareRerankResults(disabled.cases, disabled.results, enabled.results),
  };
}

export async function compareStrategyRuns(vectorRunFile, fusionRunFile, fusionRerankRunFile) {
  const vector = await loadValidatedRun(vectorRunFile);
  const fusion = await loadValidatedRun(fusionRunFile);
  const fusionRerank = await loadValidatedRun(fusionRerankRunFile);
  validateComparableDevelopmentRuns([vector, fusion, fusionRerank], "F24 三策略比较");
  ensure(vector.metadata.configSnapshot.retrieval.strategy === "VECTOR"
      && vector.metadata.configSnapshot.rerank.enabled === false,
  "第一份运行记录必须使用 VECTOR 且关闭重排序");
  ensure(fusion.metadata.configSnapshot.retrieval.strategy === "FUSION"
      && fusion.metadata.configSnapshot.rerank.enabled === false,
  "第二份运行记录必须使用 FUSION 且关闭重排序");
  ensure(fusionRerank.metadata.configSnapshot.retrieval.strategy === "FUSION"
      && fusionRerank.metadata.configSnapshot.rerank.enabled === true,
  "第三份运行记录必须使用 FUSION 且开启重排序");
  validateRerankMetrics(vector, "DISABLED");
  validateRerankMetrics(fusion, "DISABLED");
  validateRerankMetrics(fusionRerank, "ENABLED");
  const ignoredPaths = [["retrieval", "strategy"], ["rerank", "enabled"]];
  const comparableVector = withoutPaths(vector.metadata.configSnapshot, ignoredPaths);
  const comparableFusion = withoutPaths(fusion.metadata.configSnapshot, ignoredPaths);
  const comparableFusionRerank = withoutPaths(fusionRerank.metadata.configSnapshot, ignoredPaths);
  ensure(canonicalJson(comparableVector) === canonicalJson(comparableFusion)
      && canonicalJson(comparableVector) === canonicalJson(comparableFusionRerank),
  "三次运行除检索策略和重排序开关外的模型、索引、回答与提示词配置必须一致");
  return {
    datasetVersion: vector.metadata.datasetVersion,
    datasetSha256: vector.metadata.datasetSha256,
    gitCommit: vector.metadata.gitCommit,
    environment: vector.metadata.environment,
    runs: {
      vector: vectorRunFile,
      fusion: fusionRunFile,
      fusionRerank: fusionRerankRunFile,
    },
    report: compareStrategyResults(vector.cases, vector.results, fusion.results, fusionRerank.results),
  };
}

export async function compareTuningRuns(factor, baselineRunFile, candidateRunFile) {
  const baseline = await loadValidatedRun(baselineRunFile);
  const candidate = await loadValidatedRun(candidateRunFile);
  validateComparableDevelopmentRuns([baseline, candidate], "F24 单因素比较", factor !== "prompt");
  const configuration = compareTuningConfigurations(factor,
    baseline.metadata.configSnapshot, candidate.metadata.configSnapshot);
  validateRerankMetrics(baseline,
    baseline.metadata.configSnapshot.rerank.enabled ? "ENABLED" : "DISABLED");
  validateRerankMetrics(candidate,
    candidate.metadata.configSnapshot.rerank.enabled ? "ENABLED" : "DISABLED");
  return {
    datasetVersion: baseline.metadata.datasetVersion,
    datasetSha256: baseline.metadata.datasetSha256,
    gitCommits: {
      baseline: baseline.metadata.gitCommit,
      candidate: candidate.metadata.gitCommit,
    },
    environment: baseline.metadata.environment,
    baselineRun: baselineRunFile,
    candidateRun: candidateRunFile,
    configuration,
    comparison: compareResults(baseline.cases, baseline.results, candidate.results),
  };
}

async function main() {
  const [command, ...argumentsList] = process.argv.slice(2);
  if (command === "validate") {
    const result = await validateDataset();
    console.log(JSON.stringify({
      datasetVersion: result.datasetVersion,
      totalCount: result.totalCount,
      developmentCount: result.developmentCount,
      testCount: result.testCount,
      developmentSha256: result.developmentSha256,
      testSha256: result.testSha256,
      evidenceCount: result.evidenceCount,
      categoryCounts: result.categoryCounts,
    }, null, 2));
    return;
  }
  if (command === "summarize" && argumentsList.length === 1) {
    console.log(JSON.stringify(await summarizeRun(argumentsList[0]), null, 2));
    return;
  }
  if (command === "compare" && argumentsList.length === 2) {
    console.log(JSON.stringify(await compareRuns(argumentsList[0], argumentsList[1]), null, 2));
    return;
  }
  if (command === "compare-rerank" && argumentsList.length === 2) {
    console.log(JSON.stringify(await compareRerankRuns(argumentsList[0], argumentsList[1]), null, 2));
    return;
  }
  if (command === "compare-strategies" && argumentsList.length === 3) {
    console.log(JSON.stringify(await compareStrategyRuns(
      argumentsList[0], argumentsList[1], argumentsList[2]), null, 2));
    return;
  }
  if (command === "compare-factor" && argumentsList.length === 3) {
    console.log(JSON.stringify(await compareTuningRuns(
      argumentsList[0], argumentsList[1], argumentsList[2]), null, 2));
    return;
  }
  throw new Error("用法：node evaluation/scripts/f21-evaluation.mjs validate | summarize <run.json>"
    + " | compare <vector-run.json> <fusion-run.json>"
    + " | compare-rerank <disabled-run.json> <enabled-run.json>"
    + " | compare-strategies <vector-run.json> <fusion-run.json> <fusion-rerank-run.json>"
    + " | compare-factor <factor> <baseline-run.json> <candidate-run.json>");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
