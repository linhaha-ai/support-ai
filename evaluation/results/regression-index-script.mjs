import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// 只汇总本轮独立产物；不读取环境文件、Cookie、旧固定题答案，不替代正式验收签核。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const resultsDirectory = path.join(root, 'evaluation/results');
const options = new Map();
let checkLinks = false;
for (let index = 2; index < process.argv.length; index++) {
  const key = process.argv[index];
  if (key === '--check-links') {
    if (checkLinks) throw new Error('--check-links 不得重复。');
    checkLinks = true;
  } else if (key === '--output' && process.argv[index + 1] && !options.has(key)) {
    options.set(key, process.argv[++index]);
  } else if (key === '--help') {
    console.log('node evaluation/results/regression-index-script.mjs --output <新的 JSON 路径> [--check-links]');
    console.log('仅扫描 evaluation/results 下 regression-*.json/.png/.mjs；输出已存在时拒绝覆盖。');
    process.exit(0);
  } else {
    throw new Error('参数未知、缺失或重复，请使用 --help。');
  }
}
if (!options.has('--output')) throw new Error('必须显式提供 --output。');
const output = path.resolve(root, options.get('--output'));
const outputRelative = path.relative(root, output);
if (!outputRelative || outputRelative.startsWith('..') || path.isAbsolute(outputRelative) || !output.endsWith('.json')) {
  throw new Error('输出必须是仓库内新的 JSON 文件。');
}
if (existsSync(output)) throw new Error('输出已存在，禁止覆盖已有验收记录。');
if (!existsSync(resultsDirectory) || !statSync(resultsDirectory).isDirectory()) {
  throw new Error('实际产物目录 evaluation/results 不存在，停止生成索引。');
}

function git(args) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8', windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'] });
}

function relative(file) {
  return path.relative(root, file).split(path.sep).join('/');
}

function digest(file) {
  const bytes = readFileSync(file);
  return { path: relative(file), bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
}

function field(object, name) {
  if (!object || typeof object !== 'object' || Array.isArray(object)) return undefined;
  const key = Object.keys(object).find(candidate => candidate.toLowerCase() === name.toLowerCase());
  return key === undefined ? undefined : object[key];
}

function text(value) {
  return typeof value === 'string' ? value : null;
}

function state(value, depth = 0) {
  if (value === true) return 'PASSED';
  if (value === false) return 'FAILED';
  if (typeof value === 'string') return value.trim() ? value.trim().toUpperCase() : 'UNKNOWN';
  if (depth >= 3) return 'UNKNOWN';
  const raw = field(value, 'status') ?? field(value, 'passed') ?? field(value, 'runStatus')
    ?? field(value, 'generationStatus') ?? field(value, 'result') ?? field(value, 'run');
  return raw === undefined ? 'UNKNOWN' : state(raw, depth + 1);
}

function countStates(items) {
  const values = Array.isArray(items) ? items
    : items && typeof items === 'object' ? Object.values(items) : [];
  const counts = {};
  for (const item of values) {
    const name = state(item);
    counts[name] = (counts[name] ?? 0) + 1;
  }
  return { total: values.length, states: counts };
}

function summarizeJson(file) {
  try {
    const value = JSON.parse(readFileSync(file, 'utf8'));
    const metadata = field(value, 'metadata');
    const results = field(value, 'results');
    const result = {
      parseStatus: 'VALID', artifactKind: text(field(value, 'artifactKind')),
      status: text(field(value, 'status')) ?? text(field(metadata, 'status')),
      checks: countStates(field(value, 'checks')),
      metadata: metadata ? { status: text(field(metadata, 'status')), artifactKind: text(field(metadata, 'artifactKind')) } : null,
      results: countStates(results), runs: countStates(field(value, 'runs')),
      scriptSha256: text(field(value, 'scriptSha256')),
      declaredGitCommit: text(field(value, 'gitCommit')) ?? text(field(value, 'baselineCommit')),
      startedAt: text(field(value, 'startedAt')), finishedAt: text(field(value, 'finishedAt')),
    };
    // 仅提取明确的状态/计数，不复制 evidence、answer、请求体、配置或认证字段。
    const components = {};
    for (const name of ['backend', 'frontend', 'evaluation']) {
      const component = field(value, name);
      if (component && typeof component === 'object') {
        components[name] = { status: text(field(component, 'status')),
          checks: countStates(field(component, 'checks')), results: countStates(field(component, 'results')) };
      }
    }
    if (Object.keys(components).length) result.components = components;
    if (Array.isArray(results)) {
      // 逐题产物有时把终态放在 result/run 子对象，仅统计状态，不读取或输出题目答案。
      result.results.nestedRunStates = countStates(results.map(item => field(item, 'run') ?? field(item, 'result') ?? item));
    }
    return result;
  } catch (error) {
    // 进行中的半写文件或损坏 JSON 必须显式呈现，不能伪造为通过。
    return { parseStatus: 'INVALID', artifactKind: null, status: 'UNREADABLE_JSON',
      errorKind: error instanceof Error ? error.name : 'UnknownError' };
  }
}

const artifactPaths = readdirSync(resultsDirectory, { withFileTypes: true })
  .filter(entry => entry.isFile() && /^regression-.*\.(json|png|mjs)$/i.test(entry.name))
  .map(entry => path.join(resultsDirectory, entry.name))
  .filter(file => file.toLowerCase() !== output.toLowerCase()).sort();
if (!artifactPaths.length) throw new Error('evaluation/results 中没有 regression-* 本轮产物，停止生成空索引。');
const artifacts = artifactPaths.map(file => ({ ...digest(file),
  ...(file.endsWith('.json') ? { json: summarizeJson(file) } : {}) }));

const executionScriptPaths = ['deploy/scripts/verify-m1-m4-regression.mjs',
  'deploy/scripts/verify-m6-regression.mjs', 'deploy/scripts/verify-m7-browser.mjs'];
const executionScripts = executionScriptPaths.map(file => {
  const absolute = path.join(root, file);
  return existsSync(absolute) ? { ...digest(absolute), availability: 'PRESENT' }
    : { path: file, availability: 'MISSING', bytes: null, sha256: null };
});
const knownScripts = [...executionScripts.filter(file => file.sha256), ...artifacts.filter(file => file.path.endsWith('.mjs'))];
for (const artifact of artifacts) {
  if (!artifact.json?.scriptSha256) continue;
  const matches = knownScripts.filter(file => file.sha256 === artifact.json.scriptSha256).map(file => file.path);
  artifact.json.scriptFingerprint = { status: matches.length ? 'MATCHED_BYTES' : 'NO_MATCHING_SOURCE_SNAPSHOT', matches,
    note: matches.length ? '仅确认所列源码字节匹配该产物声明的脚本摘要。' : '保留原批次结果，不将当前脚本追认为当时执行源码。' };
}

const changedFiles = [...new Set([
  ...git(['diff', '--name-only', '-z', '--diff-filter=ACMR', 'HEAD', '--', 'backend', 'frontend', 'README.md', 'doc', 'evaluation/results/README.md']).split('\0'),
  ...git(['ls-files', '--others', '--exclude-standard', '-z', '--', 'backend', 'frontend', 'README.md', 'doc', 'evaluation/results/README.md']).split('\0'),
].filter(Boolean))].sort();
const productionPaths = changedFiles.filter(file =>
  /^backend\/(?:pom\.xml$|[^/]+\/pom\.xml$|[^/]+\/src\/main\/)/.test(file)
  || (/^frontend\/src\//.test(file) && !/(?:\/__tests__\/|\.(?:test|spec)\.)/.test(file)));
const productionSources = productionPaths.map(file => digest(path.join(root, file)));
const productionSourceSha256 = createHash('sha256')
  .update(productionSources.map(file => `${file.path}\0${file.sha256}\n`).join('')).digest('hex');

function validateChangedMarkdownLinks() {
  const documents = changedFiles.filter(file => file.toLowerCase().endsWith('.md'));
  const references = [];
  for (const file of documents) {
    const content = readFileSync(path.join(root, file), 'utf8');
    const pattern = /!?\[[^\]]*\]\(\s*(?:<([^>]+)>|([^\s)]+))(?:\s+["'][^)]*)?\s*\)/g;
    for (const match of content.matchAll(pattern)) {
      const target = match[1] ?? match[2];
      if (!target || target.startsWith('#') || target.startsWith('/') || /^[a-z][a-z0-9+.-]*:/i.test(target)) continue;
      let localTarget;
      try { localTarget = decodeURIComponent(target.split('#')[0]); } catch { localTarget = target.split('#')[0]; }
      if (!localTarget) continue;
      const resolved = path.resolve(root, path.dirname(file), localTarget);
      // 文档可链接本次即将独占创建的索引；其他相对文件必须已存在。
      const generatedIndex = resolved.toLowerCase() === output.toLowerCase();
      references.push({ document: file, line: content.slice(0, match.index).split('\n').length,
        target, exists: generatedIndex || existsSync(resolved), generatedIndex });
    }
  }
  return { scope: '仅当前修改的 Markdown 内联相对文件链接；本次生成索引在独占写入成功后存在，不检查外链、锚点或引用式链接。',
    documents, checked: references.length, missing: references.filter(item => !item.exists),
    status: references.some(item => !item.exists) ? 'BROKEN_LINKS' : 'PASSED' };
}

const index = {
  schemaVersion: 1, artifactKind: 'M1_M7_REGRESSION_ARTIFACT_INDEX', recordedAt: new Date().toISOString(),
  status: 'NOT_PASSED', gitCommit: git(['rev-parse', 'HEAD']).trim(),
  acceptancePending: [
    { id: 'LOCAL_ANSWER_QUALITY', status: 'OPEN', reason: '开发集默认TopK6仍漏DEV-002/013/033预期证据，DEV-014首查步骤不正确；TopK24虽补召回却有语义退步与成本上升，已恢复6。v9助手参数/时间改善，但部分样本仍扩大操作条件，未关闭质量缺口。' },
    { id: 'CLOUD_PROVIDER', status: 'PENDING', reason: '原 F01 云端 Chat、流式、Embedding 和向量链路尚需独立实测；不得以本地结果代替或自动缩减范围。' },
    { id: 'HUMAN_QUALITY_REVIEW', status: 'PENDING_APPROVAL', reason: '引用正确、依据充分、拒答及已记录质量问题仍需正式人工评审签核；自动采集不代签。' },
    { id: 'REAL_RERANK_COMPARISON', status: 'PENDING', reason: '真实重排序服务及相同开发集关闭/开启质量、成本比较尚待验证，关闭状态不等于通过。' },
    { id: 'TEST039_SCOPE', status: 'PENDING_APPROVAL', reason: 'TEST-039 旧/新版本预期范围冲突需在新数据集版本中审定；不修改旧固定题、答案或分数凑通过。' },
  ],
  selection: { directory: 'evaluation/results', pattern: 'regression-*.json/.png/.mjs', recursive: false,
    excludesOutput: true, artifactCount: artifacts.length },
  artifacts, executionScripts,
  changedProductionSources: { scope: 'HEAD 对比中的已跟踪修改及未忽略新文件，仅生产源码/资源，不包含测试。',
    count: productionSources.length, sha256: productionSourceSha256, files: productionSources },
  markdownLinks: checkLinks ? validateChangedMarkdownLinks() : { status: 'NOT_RUN' },
  limitations: [
    '本索引只读取 regression-* 当轮产物的字节和有限状态字段；未读取旧 fixed 题目、答案或人工评分。',
    '通过、失败及部分运行保持各自原批次，不把多个批次成功项拼成一次整体通过。',
    '源码指纹来自生成时工作区；必须结合每份原始报告中的镜像/运行身份判断适用范围。',
    '脚本摘要匹配仅证明源码字节一致，不把没有原始快照的历史批次追认为可复现。',
    '仅对选中文件和当前修改的生产源码计算摘要，不读取环境文件、Cookie 或认证配置值。',
  ],
};
const invalidJson = artifacts.filter(file => file.json?.parseStatus === 'INVALID').map(file => file.path);
const runningArtifacts = artifacts.filter(file => ['RUNNING', 'PENDING', 'SUBMITTING'].includes(file.json?.status?.toUpperCase())).map(file => file.path);
index.integrity = { status: invalidJson.length || runningArtifacts.length ? 'INCOMPLETE' : 'COMPLETE', invalidJson, runningArtifacts };
mkdirSync(path.dirname(output), { recursive: true });
// 最终才独占创建文件；扫描期间出现同名输出时仍拒绝覆盖。
writeFileSync(output, `${JSON.stringify(index, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' });
JSON.parse(readFileSync(output, 'utf8'));
console.log(JSON.stringify({ output: relative(output), status: index.status, artifacts: artifacts.length,
  integrity: index.integrity.status, productionSources: productionSources.length, markdownLinks: index.markdownLinks.status }));
if (index.integrity.status !== 'COMPLETE' || index.markdownLinks.status === 'BROKEN_LINKS') process.exitCode = 1;
