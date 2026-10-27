import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { closeSync, createReadStream, fstatSync, openSync, readFileSync, readSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

// 仅归档现有报告；不重新运行测试，也不读取日志正文以外的环境配置。
const root = process.cwd();
const start = Date.parse('2026-09-30T13:53:00+08:00');
const end = Date.parse('2026-09-30T13:54:00+08:00');
const relative = file => path.relative(root, file).split(path.sep).join('/');
async function fingerprint(file) {
  const info = statSync(file);
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return { path: relative(file), bytes: info.size, modifiedAt: info.mtime.toISOString(), sha256: hash.digest('hex') };
}
function tail(file, limit = 16384) {
  const handle = openSync(file, 'r');
  try {
    const size = fstatSync(handle).size;
    const data = Buffer.alloc(Math.min(size, limit));
    readSync(handle, data, 0, data.length, size - data.length);
    return data.toString('utf8').replaceAll('\r\n', '\n');
  } finally { closeSync(handle); }
}
const originalFile = 'evaluation/results/regression-engineering-20260930.json';
const v8File = 'evaluation/results/regression-engineering-20260930-v8.json';
const v7File = 'evaluation/results/regression-engineering-20260930-final.json';
const v6File = 'evaluation/results/regression-engineering-20260930-v6.json';
const original = JSON.parse(readFileSync(originalFile, 'utf8'));
const v6 = JSON.parse(readFileSync(v6File, 'utf8'));
const suites = [];
const excludedReports = [];
for (const module of readdirSync('backend', { withFileTypes: true }).filter(entry => entry.isDirectory())) {
  const directory = path.join('backend', module.name, 'target', 'surefire-reports');
  let names;
  try { names = readdirSync(directory); } catch (error) { if (error.code === 'ENOENT') continue; throw error; }
  for (const name of names.filter(name => /^TEST-.*\.xml$/.test(name)).sort()) {
    const file = path.join(directory, name);
    const info = statSync(file);
    if (info.mtimeMs < start || info.mtimeMs >= end) {
      excludedReports.push({ path: relative(file), modifiedAt: info.mtime.toISOString(), reason: '不属于本次普通测试执行时间窗' });
      continue;
    }
    const xml = readFileSync(file, 'utf8');
    const header = xml.match(/<testsuite\b([^>]+)>/)?.[1];
    assert(header);
    const attributes = Object.fromEntries([...header.matchAll(/([\w:-]+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
    const suite = { ...await fingerprint(file), module: module.name, name: attributes.name,
      suiteTimestamp: attributes.timestamp ?? null, tests: Number(attributes.tests), failures: Number(attributes.failures),
      errors: Number(attributes.errors), skipped: Number(attributes.skipped), durationSeconds: Number(attributes.time) };
    for (const key of ['tests', 'failures', 'errors', 'skipped']) assert(Number.isInteger(suite[key]) && suite[key] >= 0);
    assert.equal((xml.match(/<testcase\b/g) ?? []).length, suite.tests);
    suites.push(suite);
  }
}
const totals = { suites: suites.length, tests: 0, failures: 0, errors: 0, skipped: 0 };
for (const suite of suites) for (const key of ['tests', 'failures', 'errors', 'skipped']) totals[key] += suite[key];
totals.passed = totals.tests - totals.failures - totals.errors - totals.skipped;
assert.equal(totals.tests, 421);
assert.equal(totals.failures + totals.errors + totals.skipped, 0);
const unitLog = '.local/evaluation/m1-m7-unit-tests-v9-r2.log';
const priorLog = '.local/evaluation/m1-m7-unit-tests-v9.log';
const priorTail = tail(priorLog);
assert(priorTail.includes('BUILD FAILURE'));
assert(priorTail.includes("No plugin found for prefix '.repo.local=E'"));
assert.match(priorTail, /support-ai-bootstrap\s+\.+\s+SKIPPED/);
assert(tail(unitLog).includes('BUILD SUCCESS'));
assert(tail(unitLog).includes('2026-09-30T13:53:48+08:00'));
const buildLog = '.local/evaluation/m1-m7-image-build-v9.log';
assert(tail(buildLog).includes('Image support-ai-f31-source4-app Built'));
// 数据库本轮未重跑；仍存留的原批次 XML 可核对，但不把其时间改写成本轮。
assert.equal(v6.backend.failsafe.totals.tests, 86);
for (const suite of v6.backend.failsafe.suites) assert.equal((await fingerprint(suite.path)).sha256, suite.sha256);
const container = JSON.parse(execFileSync('docker', ['inspect', '--format',
  '{"containerId":"{{.Id}}","imageId":"{{.Image}}","health":"{{.State.Health.Status}}","startedAt":"{{.State.StartedAt}}"}',
  'support-ai-f31-source4-app-1'], { encoding: 'utf8' }).trim());
assert.equal(container.health, 'healthy');
const builtImage = tail(buildLog).match(/exporting manifest list (sha256:[0-9a-f]{64})/)?.[1];
assert(builtImage, '构建日志缺少manifest list镜像标识');
assert.equal(container.imageId, builtImage, '容器实际镜像与本批构建输出不符');
const report = {
  schemaVersion: '1.0', artifactKind: 'M1_M7_ENGINEERING_REGRESSION_V9', status: 'PASSED', recordedAt: new Date().toISOString(),
  baselineCommit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  assistantPromptVersion: 'assistant-agent-v9',
  collector: { ...await fingerprint('evaluation/results/regression-engineering-capture-script.mjs'),
    command: 'node evaluation/results/regression-engineering-capture-script.mjs',
    behavior: '只读采集指定批次的XML、日志摘要和容器镜像，并向标准输出输出JSON；不执行测试、不登录、不调模型。' },
  scope: 'v9普通工程测试与app构建；不是所有里程碑整体验收结论，也不证明实际模型输出质量。',
  backend: {
    reportSelection: { fromInclusive: '2026-09-30T13:53:00+08:00', toExclusive: '2026-09-30T13:54:00+08:00',
      basis: '仅Surefire报告文件mtime；XML没有suite timestamp。数据库明确沿用v6批次，不混入本轮计数。', excludedReports },
    execution: { exitCode: 0, exitCodeBasis: '父执行任务确认终端返回0；归档核对BUILD SUCCESS与各套XML零失败。',
      executable: 'D:/apache-maven-3.8.6/bin/mvn.cmd', javaHome: 'D:/Java/jdk21.0.12.1',
      arguments: ['-B', '-ntp', '-f', 'backend/pom.xml', '-Dmaven.repo.local=E:/mvnrepository', 'test'],
      completedAt: '2026-09-30T13:53:48+08:00', elapsedSeconds: 36.075, log: await fingerprint(unitLog) },
    priorExecution: { status: 'FAILED_BEFORE_TESTS', testsExecuted: false,
      completedAt: '2026-09-30T13:52:42+08:00', elapsedSeconds: 3.943,
      diagnostic: "No plugin found for prefix '.repo.local=E'",
      cause: '父执行任务确认首次PowerShell调用未引用-Dmaven.repo.local参数；全部模块SKIPPED，未进入测试。修正参数引用后独立执行r2，不覆盖首轮日志。',
      log: await fingerprint(priorLog) },
    surefire: { totals, suites, executedInThisV9Batch: true },
    failsafe: { ...v6.backend.failsafe, executedInThisV9Batch: false, reusedFrom: v6File,
      sourceReportSelection: v6.backend.reportSelection, sourceExecution: v6.backend.execution,
      sourceWarnings: v6.backend.warnings,
      note: '数据库86/86沿用同日13:05至13:08的v6全量执行。本轮仅修改assistant工具结果序列化、通用事实约束与测试，没有schema/SQL变化；不声称再次运行数据库测试。' },
    liveProviderTestsExecuted: false
  },
  frontend: { ...original.frontend, executedInThisV9Batch: false, reusedFrom: originalFile,
    note: '前端未变，沿用原r2批次98/98和web构建，本次未重跑。' },
  evaluation: { ...original.evaluation, executedInThisV9Batch: false, reusedFrom: originalFile,
    note: '沿用原批次29/29及v1数据集结构校验，本次未重跑脚本或模型题集。' },
  applicationBuild: { status: 'PASSED', exitCode: 0, promptVersion: 'assistant-agent-v9',
    command: 'docker compose --project-name support-ai-f31-source4 --file deploy/compose.yml --env-file deploy/.env build app',
    log: await fingerprint(buildLog),
    deploymentObservation: { observedAt: new Date().toISOString(), containerName: 'support-ai-f31-source4-app-1', ...container,
      imageIdentityBasis: 'docker inspect的Container .Image；不是构建输出的内部config摘要。健康状态不替代业务回归。' } },
  historicalBatches: [await fingerprint(originalFile), await fingerprint(v6File), await fingerprint(v7File), await fingerprint(v8File)],
  limitations: [
    '此前四份JSON与日志均保留；旧Surefire XML路径已被后续测试覆盖，其历史SHA表示当时快照，不据当前XML否定或删除旧证据。',
    '前端、评测脚本、数据库为标记了来源批次的既有证据，不与本轮普通测试累加成业务通过数。',
    '本地忽略目录中的XML仅提取测试汇总及指纹，不归档可能含环境属性或凭据的properties。',
    '数据库退出期告警保留在v6来源记录中，不伪称是本次普通测试新发生的告警。'
  ]
};
console.log(JSON.stringify(report, null, 2));
