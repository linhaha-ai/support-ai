import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { EVALUATION_ROOT, summarizeResults, validateDataset } from '../../evaluation/scripts/f21-evaluation.mjs';
import { canonical, collectEvidenceRanges, validateRunRecord } from '../../evaluation/scripts/f32-runner.mjs';

const SHA256 = /^[0-9a-f]{64}$/;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const difference = (left, right) => left.filter(id => !right.includes(id));
const nullableDelta = (left, right) => Number.isFinite(left) && Number.isFinite(right) ? right - left : null;
const argumentNames = ['baseline', 'candidate', 'baseline-runtime', 'candidate-runtime', 'output'];

function parseOptions(args) {
  const options = {};
  assert.equal(args.length, argumentNames.length * 2, '必须提供五组参数：--baseline、--candidate、--baseline-runtime、--candidate-runtime、--output');
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index].slice(2);
    assert(args[index].startsWith('--') && argumentNames.includes(name), `不支持的参数：${args[index]}`);
    assert(args[index + 1] && !args[index + 1].startsWith('--') && !(name in options), `参数缺值或重复：${args[index]}`);
    options[name] = resolve(args[index + 1]);
  }
  assert(options.output.endsWith('.json'), '输出必须为独立的 .json 文件');
  assert(argumentNames.filter(name => name !== 'output').every(name => options[name] !== options.output), '不能覆盖输入原件');
  return options;
}

async function loadArtifact(path) {
  const bytes = await readFile(path);
  return { path, sha256: sha256(bytes), value: JSON.parse(bytes.toString('utf8')) };
}

async function ensureNewOutput(path) {
  try {
    await lstat(path);
  } catch (error) {
    if (error.code === 'ENOENT') return;
    throw error;
  }
  throw new Error('输出已经存在，拒绝覆盖历史产物');
}

function validateRuntime(run, capture, expectedTopK, label) {
  for (const key of ['runtimeHash', 'runtimeWithoutStrategyHash', 'runtimeWithoutTopKHash', 'scriptSha256']) {
    assert(SHA256.test(capture[key]), `${label} 运行指纹缺少有效 ${key}`);
  }
  assert(Number.isFinite(Date.parse(capture.recordedAt)), `${label} 运行指纹记录时间无效`);
  for (const key of ['imageId', 'runtimeHash', 'runtimeWithoutStrategyHash']) {
    assert.equal(capture[key], run.metadata[key], `${label} 运行记录与指纹不匹配：${key}`);
  }
  // 默认值由运行快照确认；候选配置必须通过显式 TopK 环境变量进入容器。
  const allowedTopK = expectedTopK === 6 ? [null, '6'] : ['24'];
  assert(allowedTopK.includes(capture.topKEnvironment), `${label} TopK 环境变量与比较条件不一致`);
}

export function validateTopKComparison(baseline, candidate, baselineRuntime, candidateRuntime, dataset) {
  assert.equal(baseline.metadata?.split, 'development', '基线只能使用 development 记录');
  assert.equal(candidate.metadata?.split, 'development', '候选只能使用 development 记录');
  const cases = validateRunRecord(baseline, dataset, { requireComplete: true });
  validateRunRecord(candidate, dataset, { requireComplete: true });
  assert.equal(cases.length, 60, '本轮必须覆盖完整 60 道开发题');
  for (const key of ['datasetVersion', 'datasetSha256', 'gitCommit', 'sourceTreeHash', 'imageId',
    'bindingHash', 'runnerSha256', 'environment', 'project', 'baseUrl']) {
    assert(typeof baseline.metadata[key] === 'string' && baseline.metadata[key].trim(), `基线缺少 ${key}`);
    assert.equal(baseline.metadata[key], candidate.metadata[key], `TopK 比较基线不一致：${key}`);
  }
  assert.equal(baseline.metadata.configSnapshot.retrieval.topK, 6, '基线 TopK 必须为 6');
  assert.equal(candidate.metadata.configSnapshot.retrieval.topK, 24, '候选 TopK 必须为 24');
  const left = structuredClone(baseline.metadata.configSnapshot);
  const right = structuredClone(candidate.metadata.configSnapshot);
  delete left.retrieval.topK;
  delete right.retrieval.topK;
  assert.equal(canonical(left), canonical(right), '除 retrieval.topK 外的配置快照必须完全一致');
  validateRuntime(baseline, baselineRuntime, 6, '基线');
  validateRuntime(candidate, candidateRuntime, 24, '候选');
  assert.equal(baselineRuntime.scriptSha256, candidateRuntime.scriptSha256, '两批运行指纹采集脚本不一致');
  assert.equal(baselineRuntime.runtimeWithoutTopKHash, candidateRuntime.runtimeWithoutTopKHash,
    '除 SUPPORT_AI_RAG_TOP_K 外的运行镜像、环境、命令或入口存在差异');
  return cases;
}

function objectiveResult(testCase, result) {
  const expected = testCase.evidenceIds;
  const answerable = testCase.executionMode === 'RAG' && testCase.expectedOutcome === 'ANSWER';
  return {
    status: result.status,
    errorCode: result.errorCode ?? null,
    noEvidence: result.noEvidence ?? null,
    reason: result.reason ?? null,
    retrievedEvidenceIds: result.retrievedEvidenceIds,
    citedEvidenceIds: result.citedEvidenceIds,
    missingExpectedRetrievedEvidenceIds: difference(expected, result.retrievedEvidenceIds),
    missingExpectedCitedEvidenceIds: difference(expected, result.citedEvidenceIds),
    completeExpectedRetrievalHit: answerable ? result.status === 'COMPLETED'
      && expected.every(id => result.retrievedEvidenceIds.includes(id)) : null,
    usage: result.usage == null ? null : {
      promptTokens: result.usage.promptTokens ?? null,
      completionTokens: result.usage.completionTokens ?? null,
      totalTokens: result.usage.totalTokens ?? null,
    },
    embeddingTotalTokens: result.metrics?.embeddingTotalTokens ?? null,
    rerankTotalTokens: result.metrics?.rerankTotalTokens ?? null,
    latencyMs: Object.fromEntries(['retrievalMs', 'generationMs', 'totalMs'].map(key => [key, result.metrics?.[key] ?? null])),
  };
}

export function compareObjectiveResults(cases, baseline, candidate) {
  const baselineById = new Map(baseline.results.map(item => [item.caseId, item]));
  const candidateById = new Map(candidate.results.map(item => [item.caseId, item]));
  const rows = cases.map(testCase => {
    const left = objectiveResult(testCase, baselineById.get(testCase.id));
    const right = objectiveResult(testCase, candidateById.get(testCase.id));
    const retrievedAdded = difference(right.retrievedEvidenceIds, left.retrievedEvidenceIds);
    const retrievedRemoved = difference(left.retrievedEvidenceIds, right.retrievedEvidenceIds);
    const citedAdded = difference(right.citedEvidenceIds, left.citedEvidenceIds);
    const citedRemoved = difference(left.citedEvidenceIds, right.citedEvidenceIds);
    return {
      caseId: testCase.id, executionMode: testCase.executionMode, expectedOutcome: testCase.expectedOutcome,
      expectedEvidenceIds: testCase.evidenceIds, baseline: left, candidate: right,
      differences: {
        retrievedAdded, retrievedRemoved, citedAdded, citedRemoved,
        expectedRetrievedAdded: retrievedAdded.filter(id => testCase.evidenceIds.includes(id)),
        expectedRetrievedRemoved: retrievedRemoved.filter(id => testCase.evidenceIds.includes(id)),
        expectedCitedAdded: citedAdded.filter(id => testCase.evidenceIds.includes(id)),
        expectedCitedRemoved: citedRemoved.filter(id => testCase.evidenceIds.includes(id)),
        retrievalOrderChanged: canonical(left.retrievedEvidenceIds) !== canonical(right.retrievedEvidenceIds),
        citationOrderChanged: canonical(left.citedEvidenceIds) !== canonical(right.citedEvidenceIds),
        totalTokensDelta: nullableDelta(left.usage?.totalTokens, right.usage?.totalTokens),
        totalMsDelta: nullableDelta(left.latencyMs.totalMs, right.latencyMs.totalMs),
      },
    };
  });
  const ids = predicate => rows.filter(predicate).map(item => item.caseId);
  const baselineSummary = summarizeResults(cases, baseline.results);
  const candidateSummary = summarizeResults(cases, candidate.results);
  return {
    baselineSummary, candidateSummary,
    changes: {
      completeRetrievalHitImprovedCaseIds: ids(item => item.baseline.completeExpectedRetrievalHit === false
        && item.candidate.completeExpectedRetrievalHit === true),
      completeRetrievalHitRegressedCaseIds: ids(item => item.baseline.completeExpectedRetrievalHit === true
        && item.candidate.completeExpectedRetrievalHit === false),
      expectedRetrievalAddedCaseIds: ids(item => item.differences.expectedRetrievedAdded.length > 0),
      expectedRetrievalRemovedCaseIds: ids(item => item.differences.expectedRetrievedRemoved.length > 0),
      expectedCitationAddedCaseIds: ids(item => item.differences.expectedCitedAdded.length > 0),
      expectedCitationRemovedCaseIds: ids(item => item.differences.expectedCitedRemoved.length > 0),
      completionImprovedCaseIds: ids(item => item.baseline.status !== 'COMPLETED' && item.candidate.status === 'COMPLETED'),
      completionRegressedCaseIds: ids(item => item.baseline.status === 'COMPLETED' && item.candidate.status !== 'COMPLETED'),
      notRunBaselineCaseIds: ids(item => item.baseline.status === 'NOT_RUN'),
      notRunCandidateCaseIds: ids(item => item.candidate.status === 'NOT_RUN'),
    },
    deltas: {
      completeRetrievalHitPassed: candidateSummary.quality.retrievalHit.passed - baselineSummary.quality.retrievalHit.passed,
      totalTokens: nullableDelta(baselineSummary.usage.totalTokens, candidateSummary.usage.totalTokens),
      embeddingTotalTokens: nullableDelta(baselineSummary.embedding.totalTokens, candidateSummary.embedding.totalTokens),
      rerankTotalTokens: nullableDelta(baselineSummary.rerank.totalTokens, candidateSummary.rerank.totalTokens),
    },
    rows,
  };
}

async function main() {
  const options = parseOptions(process.argv.slice(2));
  await ensureNewOutput(options.output);
  const [baseline, candidate, baselineRuntime, candidateRuntime] = await Promise.all(
    ['baseline', 'candidate', 'baseline-runtime', 'candidate-runtime'].map(name => loadArtifact(options[name])));
  const dataset = await validateDataset();
  // 固定测试题仅由共用校验器检查完整性，不进入本次调参比较或输出。
  dataset.evidenceIds = (await Promise.all(dataset.manifest.corpus.map(async entry => collectEvidenceRanges(
    await readFile(resolve(EVALUATION_ROOT, entry.file), 'utf8')).map(item => item.id)))).flat();
  const cases = validateTopKComparison(baseline.value, candidate.value, baselineRuntime.value, candidateRuntime.value, dataset);
  const sourceReference = artifact => ({ path: artifact.path, sha256: artifact.sha256 });
  const report = {
    schemaVersion: '1.0', artifactKind: 'DEVELOPMENT_TOPK_6_TO_24_OBJECTIVE_COMPARISON',
    status: 'DRAFT_PENDING_HUMAN_REVIEW', createdAt: new Date().toISOString(),
    comparisonScriptSha256: sha256(await readFile(fileURLToPath(import.meta.url))),
    inputs: { baseline: sourceReference(baseline), candidate: sourceReference(candidate),
      baselineRuntime: sourceReference(baselineRuntime), candidateRuntime: sourceReference(candidateRuntime) },
    baselineMetadata: baseline.value.metadata, candidateMetadata: candidate.value.metadata,
    runtimeWithoutTopKHash: baselineRuntime.value.runtimeWithoutTopKHash,
    comparison: compareObjectiveResults(cases, baseline.value, candidate.value),
    limitations: [
      '本产物只比较完整开发集；不运行模型，不读取固定测试回答，不修改原件或默认参数。',
      'humanReview 不由本脚本生成；预期证据命中或引用集合变化不等同于引用正确、依据充分或答案质量通过。',
      'retrievedEvidenceIds 是分数及 Token 预算筛选后的实际入模来源，不是筛选前的 TopK 候选集合。',
      '60 行完整采集允许独立契约/权限场景保留 NOT_RUN；NOT_RUN 不算通过，须另行验证。',
      'refusalCorrect 仅为零证据或权限自动口径；语义拒答仍需人工核对。未知 Token 用量及其差值保持 null。',
      '本比较不批准新默认值、人工质量验收或数据集修订，也不改写历史冻结记录。',
    ],
  };
  await writeFile(options.output, `${JSON.stringify(report, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' });
  console.log(JSON.stringify({ output: options.output, status: report.status, comparedCases: cases.length,
    changes: report.comparison.changes }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
