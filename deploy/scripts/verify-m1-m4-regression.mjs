import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { closeSync, mkdirSync, openSync, readFileSync, writeSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

// 真实部署补验：只修改本次新建夹具，不重放失败请求、不删除服务器数据、不保存认证信息。
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const argumentsMap = new Map();
const acceptedOptions = new Set(['--base-url', '--project', '--env-file', '--output', '--timeout-seconds']);
for (let index = 2; index < process.argv.length; index += 2) {
  const option = process.argv[index];
  if (option === '--help') {
    console.log('node deploy/scripts/verify-m1-m4-regression.mjs --output <新的 JSON 路径> [--base-url http://127.0.0.1:8088] [--project support-ai-f31-source4] [--env-file deploy/.env] [--timeout-seconds 600]');
    console.log('必需环境变量：SUPPORTAI_F32_ADMIN_PASSWORD、SUPPORTAI_F32_READER_PASSWORD、SUPPORTAI_F32_SUPPORT2_PASSWORD。账号固定为 admin/support1/support2。');
    process.exit(0);
  }
  if (!acceptedOptions.has(option) || !process.argv[index + 1] || argumentsMap.has(option)) {
    throw new Error('参数不完整、重复或未知，请使用 --help。');
  }
  argumentsMap.set(option, process.argv[index + 1]);
}
if (!argumentsMap.has('--output')) throw new Error('必须提供新的 --output 路径。');
const baseUrl = new URL(argumentsMap.get('--base-url') ?? 'http://127.0.0.1:8088');
if (!['http:', 'https:'].includes(baseUrl.protocol) || baseUrl.username || baseUrl.password
    || baseUrl.search || baseUrl.hash || baseUrl.pathname !== '/'
    || !['127.0.0.1', 'localhost', '[::1]'].includes(baseUrl.hostname)) {
  throw new Error('base-url 仅允许本机回环地址，且不能包含凭据、路径、查询或片段。');
}
const project = argumentsMap.get('--project') ?? 'support-ai-f31-source4';
if (!/^[a-z0-9][a-z0-9_-]*$/.test(project)) throw new Error('Compose 项目名不合法。');
const envFile = resolve(root, argumentsMap.get('--env-file') ?? 'deploy/.env');
const timeoutMs = Number(argumentsMap.get('--timeout-seconds') ?? 600) * 1000;
if (!Number.isInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 3600000) {
  throw new Error('timeout-seconds 应为 1 至 3600 的整数。');
}
const output = resolve(root, argumentsMap.get('--output'));
mkdirSync(dirname(output), { recursive: true });
// 独占创建输出；后续只更新本次持有的文件句柄，不能覆盖先前批次。
const outputHandle = openSync(output, 'wx');
let outputLength = 0;
const summary = {
  artifactKind: 'M1_M4_REAL_DEPLOYMENT_REGRESSION', status: 'RUNNING', stage: 'PREFLIGHT',
  startedAt: new Date().toISOString(), finishedAt: null, baseUrl: baseUrl.origin, project,
  scriptSha256: createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex'),
  gitCommit: null, backendSource: null, deployment: null, finalIdentity: null,
  fixtures: [], sessions: [], runs: [], checks: [], requests: [], logout: [],
  limitations: [
    '本批次使用真实 HTTP/SSE、生产 Worker、已配置模型及只读数据库；不包含浏览器点击或人工质量签核。',
    '来源闭包、根引用和历史排除以 message_source 实际登记为据；没有采集供应商 Prompt，不宣称直接观测历史只注入一次。',
    '断流仅终止客户端读取，同一 clientRequestId 只提交一次，随后只 GET 原 run；失败不自动重新提交。',
    '解析故障由非零字节空白 TXT 触发；不冒充真实 Embedding、数据库故障或所有终态竞态已验收。',
    '本次新建服务器夹具全部保留；不删除、停用或撤销既有业务资料和成员。',
    '未提交业务源码以逐文件 SHA-256 和聚合指纹定位，运行环境以容器、镜像及 JAR 摘要定位；脚本不会自动构建镜像。',
  ],
};
const ownedKnowledgeBases = new Set();
const ownedSessions = new Set();
const ownedRuns = new Set();
const ownedDocuments = new Set();
const loggedInClients = [];
const secrets = Object.entries(process.env)
  .filter(([key]) => ['SUPPORTAI_F32_ADMIN_PASSWORD', 'SUPPORTAI_F32_READER_PASSWORD', 'SUPPORTAI_F32_SUPPORT2_PASSWORD'].includes(key))
  .map(([, value]) => value).filter(Boolean);

function save() {
  const content = Buffer.from(`${JSON.stringify(summary, null, 2)}\n`, 'utf8');
  // Windows 文件观察器可能与反复截断冲突；尾部填充合法 JSON 空白，始终更新本次独占文件。
  const buffer = Buffer.alloc(Math.max(outputLength, content.length), 0x20);
  content.copy(buffer);
  let offset = 0;
  while (offset < buffer.length) offset += writeSync(outputHandle, buffer, offset, buffer.length - offset, offset);
  outputLength = buffer.length;
}

function check(name, passed, evidence = null) {
  summary.checks.push({ name, passed: Boolean(passed), evidence });
  save();
  console.log(`${passed ? '通过' : '失败'}：${name}`);
  if (!passed) throw new Error(`语义断言失败：${name}`);
}

function uuid(value) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value ?? '')) {
    throw new Error('接口未返回合法 UUID。');
  }
  return value.toLowerCase();
}

function safeMessage(error) {
  let message = error instanceof Error ? error.message : '未知执行错误';
  for (const secret of secrets) message = message.split(secret).join('[REDACTED]');
  return message;
}

function backendSourceIdentity() {
  const files = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', 'backend'], {
    cwd: root, encoding: 'utf8', windowsHide: true,
  }).split('\0').filter(path => /^backend\/(?:pom\.xml$|[^/]+\/pom\.xml$|[^/]+\/src\/main\/)/.test(path)).sort();
  const entries = [...new Set(files)].map(path => ({ path,
    sha256: createHash('sha256').update(readFileSync(resolve(root, path))).digest('hex') }));
  const sha256 = createHash('sha256').update(entries.map(entry => `${entry.path}\0${entry.sha256}\n`).join('')).digest('hex');
  return { sha256, fileCount: entries.length, scope: 'backend/pom.xml、各模块 pom.xml 和 src/main 全部源码/资源', files: entries };
}

function deploymentIdentity() {
  const options = { cwd: root, encoding: 'utf8', timeout: 30000, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] };
  try {
    const containerId = execFileSync('docker', ['compose', '--project-name', project,
      '--file', resolve(root, 'deploy/compose.yml'), '--env-file', envFile, 'ps', '--quiet', 'app'], options).trim();
    if (!/^[a-f0-9]{12,64}$/.test(containerId)) throw new Error('app 容器不唯一或未启动。');
    // 只读指定身份字段，禁止读取整份 inspect，避免环境变量中的凭据进入输出。
    const inspect = field => JSON.parse(execFileSync('docker', ['inspect', '--format', `{{json ${field}}}`, containerId], options).trim());
    const jarOutput = execFileSync('docker', ['exec', containerId, 'sha256sum', '/opt/support-ai/support-ai.jar'], options).trim();
    const jarSha256 = jarOutput.split(/\s+/)[0];
    if (!/^[a-f0-9]{64}$/.test(jarSha256)) throw new Error('运行 JAR 摘要不可用。');
    return { containerId, imageId: inspect('.Image'), configuredImage: inspect('.Config.Image'),
      startedAt: inspect('.State.StartedAt'), jarSha256 };
  } catch {
    throw new Error('无法读取唯一 app 容器的镜像身份和运行 JAR 摘要。');
  }
}

function rememberCookies(client, response) {
  for (const header of response.headers.getSetCookie()) {
    const [pair] = header.split(';');
    const separator = pair.indexOf('=');
    if (separator < 1) continue;
    const name = pair.slice(0, separator);
    const value = pair.slice(separator + 1);
    if (!value || /max-age=0(?:;|$)/i.test(header)) client.cookies.delete(name);
    else client.cookies.set(name, value);
  }
}

function requestHeaders(client, method, accept = 'application/json') {
  const headers = new Headers({ Accept: accept });
  if (client.cookies.size) headers.set('Cookie', [...client.cookies].map(([key, value]) => `${key}=${value}`).join('; '));
  if (method !== 'GET' && client.csrf) headers.set(client.csrf.headerName, client.csrf.token);
  return headers;
}

async function api(client, method, path, { body, form, expected = [200], requestTimeoutMs = timeoutMs } = {}) {
  const headers = requestHeaders(client, method);
  if (body !== undefined) headers.set('Content-Type', 'application/json; charset=utf-8');
  const response = await fetch(new URL(path, baseUrl), {
    method, headers, body: form ?? (body === undefined ? undefined : JSON.stringify(body)),
    redirect: 'error', signal: AbortSignal.timeout(requestTimeoutMs),
  });
  rememberCookies(client, response);
  summary.requests.push({ actor: client.actor, method, path, status: response.status });
  save();
  if (!expected.includes(response.status)) {
    await response.body?.cancel();
    throw new Error(`${method} ${path} 返回非预期 HTTP ${response.status}。`);
  }
  if (response.status === 204) return null;
  const envelope = await response.json();
  if (response.status >= 400) return { status: response.status, code: envelope.code };
  if (envelope.code !== 'OK') throw new Error(`${method} ${path} 未返回 OK。`);
  return envelope.data;
}

async function login(actor, passwordVariable) {
  const password = process.env[passwordVariable];
  if (!password) throw new Error(`缺少环境变量 ${passwordVariable}。`);
  const client = { actor, cookies: new Map(), csrf: null, user: null };
  client.csrf = await api(client, 'GET', '/api/auth/csrf');
  client.user = await api(client, 'POST', '/api/auth/login', { body: { username: actor, password } });
  loggedInClients.push(client);
  client.csrf = await api(client, 'GET', '/api/auth/csrf');
  check(`${actor} 登录身份正确`, client.user.username === actor, { userId: uuid(client.user.id) });
  return client;
}

function database(sql) {
  try {
    // SQL 只读事务；只输出约定 JSON，数据库 stderr 可能含内部信息，不进入产物。
    const result = execFileSync('docker', ['compose', '--project-name', project,
      '--file', resolve(root, 'deploy/compose.yml'), '--env-file', envFile,
      'exec', '--no-TTY', 'postgres', 'psql', '--quiet', '--no-align', '--tuples-only',
      '--set', 'ON_ERROR_STOP=1', '--username', 'supportai', '--dbname', 'supportai'], {
      input: `BEGIN READ ONLY;\n${sql}\nCOMMIT;\n`, encoding: 'utf8', timeout: 30000,
      maxBuffer: 2 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
    });
    return JSON.parse(result.trim());
  } catch {
    throw new Error('只读数据库核对失败；请检查 Compose 服务和 JSON 查询契约。');
  }
}

function runDatabase(run) {
  if (!ownedRuns.has(run.runId) || !ownedSessions.has(run.sessionId)) throw new Error('拒绝读取非本轮运行。');
  const runId = uuid(run.runId);
  const sessionId = uuid(run.sessionId);
  const requestId = uuid(run.clientRequestId);
  return database(`
-- chat_run/chat_message：核对同一请求唯一性、终态和实际持久化答案。
-- message_source/document_chunk：读取所有实际入模来源（包含未引用项），以及同会话直接根来源。
SELECT json_build_object(
 'runId',r.id,'sessionId',r.session_id,'status',r.status,'answer',m.content,'messageStatus',m.status,
 'requestCount',(SELECT count(*) FROM chat_run WHERE session_id='${sessionId}'::uuid AND client_request_id='${requestId}'::uuid),
 'sources',(SELECT coalesce(json_agg(json_build_object(
   'id',s.id,'originSourceId',s.origin_source_id,'number',s.citation_no,'cited',s.is_cited,
   'knowledgeBaseId',s.kb_id,'chunkId',s.chunk_id,'versionId',c.version_id,
   'rootOriginSourceId',root.origin_source_id,'rootRunId',rm.run_id,'rootSessionId',rm.session_id)
   ORDER BY s.citation_no),'[]'::json)
   FROM message_source s JOIN document_chunk c ON c.id=s.chunk_id
   LEFT JOIN message_source root ON root.id=s.origin_source_id
   LEFT JOIN chat_message rm ON rm.id=root.message_id WHERE s.message_id=m.id))
 FROM chat_run r JOIN chat_message m ON m.run_id=r.id AND m.role='ASSISTANT'
 WHERE r.id='${runId}'::uuid AND r.session_id='${sessionId}'::uuid;`);
}

async function waitJob(client, jobId) {
  const deadline = Date.now() + timeoutMs;
  do {
    const job = await api(client, 'GET', `/api/ingestion-jobs/${uuid(jobId)}`);
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.status)) return job;
    await delay(1000);
  } while (Date.now() < deadline);
  throw new Error(`入库任务 ${jobId} 等待超时。`);
}

async function waitRun(client, runId) {
  const deadline = Date.now() + timeoutMs;
  do {
    const status = await api(client, 'GET', `/api/chat/runs/${uuid(runId)}`);
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(status.status)) return status;
    await delay(1000);
  } while (Date.now() < deadline);
  throw new Error(`运行 ${runId} 回查超时，不自动重放。`);
}

function contentFor(fixture, updated = false) {
  const seconds = updated ? 13 : fixture.seconds;
  return `# ${fixture.subject}处理手册\n\n本手册仅适用于${fixture.subject}。\n故障码 ${fixture.code} 表示${fixture.subject}超时。处理时先核对回调地址与网络，再等待 ${seconds} 秒重试。\n${fixture.subject}的处理口令为${updated ? '紫藤' : fixture.phrase}。该口令是合成验收资料，不是真实业务密码。\n`;
}

async function upload(client, fixture, content, { version = false, expectedRevision } = {}) {
  if (!ownedKnowledgeBases.has(fixture.kbId) || (version && !ownedDocuments.has(fixture.documentId))) {
    throw new Error('拒绝写入非本轮知识库或文档。');
  }
  const form = new FormData();
  form.set('file', new Blob([content], { type: 'text/plain;charset=utf-8' }), `${fixture.label}.txt`);
  if (version) form.set('expectedRevision', String(expectedRevision));
  else form.set('displayName', `M1-M4 ${fixture.label}`);
  const path = version ? `/api/documents/${fixture.documentId}/versions` : `/api/knowledge-bases/${fixture.kbId}/documents`;
  const receipt = await api(client, 'POST', path, { form, expected: [202] });
  ownedDocuments.add(uuid(receipt.documentId));
  return receipt;
}

async function createFixture(admin, client, label, subject, seconds, phrase) {
  const fixture = { label, actor: client.actor, subject, seconds, phrase,
    code: `REG_${label.toUpperCase()}_${randomUUID().slice(0, 8).toUpperCase()}` };
  const kb = await api(admin, 'POST', '/api/knowledge-bases', {
    body: { name: `M1-M4 ${label}-${randomUUID().slice(0, 8)}`, description: '真实阶段补验专属夹具，保留供复核' }, expected: [201],
  });
  fixture.kbId = uuid(kb.id);
  ownedKnowledgeBases.add(fixture.kbId);
  summary.fixtures.push(fixture);
  save();
  await api(admin, 'PUT', `/api/knowledge-bases/${fixture.kbId}/members/${uuid(client.user.id)}`, { body: { permission: 'WRITE' } });
  const receipt = await upload(client, fixture, contentFor(fixture));
  Object.assign(fixture, { documentId: uuid(receipt.documentId), versionId: uuid(receipt.versionId), jobId: uuid(receipt.jobId) });
  save();
  fixture.job = await waitJob(client, fixture.jobId);
  check(`${label} 真实上传并成功发布`, fixture.job.status === 'SUCCEEDED', { ...receipt, status: fixture.job.status, errorCode: fixture.job.errorCode });
  return fixture;
}

async function createSession(client, fixture) {
  const data = await api(client, 'POST', '/api/chat/sessions', { body: { kbIds: [fixture.kbId], mode: 'RAG' }, expected: [201] });
  const session = { sessionId: uuid(data.sessionId), actor: client.actor, knowledgeBaseId: fixture.kbId };
  ownedSessions.add(session.sessionId);
  summary.sessions.push(session);
  save();
  return session;
}

async function ask(client, session, question, label, { disconnect = false } = {}) {
  if (!ownedSessions.has(session.sessionId)) throw new Error('拒绝写入非本轮会话。');
  const run = { label, actor: client.actor, sessionId: session.sessionId, question,
    clientRequestId: randomUUID(), runId: null, events: [], disconnected: false,
    observedPrefix: '', status: null, database: null };
  summary.runs.push(run);
  save();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const started = Date.now();
  const headers = requestHeaders(client, 'POST', 'text/event-stream');
  headers.set('Content-Type', 'application/json; charset=utf-8');
  const path = `/api/chat/sessions/${session.sessionId}/messages`;
  let reader;
  try {
    const response = await fetch(new URL(path, baseUrl), { method: 'POST', headers, redirect: 'error',
      body: JSON.stringify({ clientRequestId: run.clientRequestId, content: question }), signal: controller.signal });
    rememberCookies(client, response);
    summary.requests.push({ actor: client.actor, method: 'POST', path, status: response.status, clientRequestId: run.clientRequestId });
    save();
    if (response.status !== 200 || !response.headers.get('content-type')?.startsWith('text/event-stream')) {
      await response.body?.cancel();
      throw new Error(`问答未进入 SSE，HTTP ${response.status}。`);
    }
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffered = '';
    let terminal = false;
    while (!terminal && !run.disconnected) {
      const part = await reader.read();
      buffered += decoder.decode(part.value, { stream: !part.done });
      let boundary;
      while ((boundary = buffered.search(/\r?\n\r?\n/)) >= 0) {
        const block = buffered.slice(0, boundary);
        const separator = buffered.slice(boundary).match(/^\r?\n\r?\n/)[0];
        buffered = buffered.slice(boundary + separator.length);
        const lines = block.split(/\r?\n/);
        const name = lines.find(line => line.startsWith('event:'))?.slice(6).trim();
        const json = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
        if (!name || !json) continue;
        const data = JSON.parse(json);
        // error 事件仅保留稳定错误码；不保存供应商原始异常或内部响应。
        const evidence = name === 'error' ? { code: data.code, runId: data.runId, traceId: data.traceId } : data;
        run.events.push({ name, elapsedMs: Date.now() - started, data: evidence });
        if (name === 'start') {
          run.runId = uuid(data.runId);
          ownedRuns.add(run.runId);
          if (disconnect) {
            run.beforeDisconnect = await api(client, 'GET', `/api/chat/runs/${run.runId}`);
            run.disconnected = true;
            run.disconnectAt = new Date().toISOString();
            save();
            controller.abort();
            break;
          }
        }
        if (name === 'delta') run.observedPrefix += data.text;
        if (name === 'done' || name === 'error') terminal = true;
      }
      save();
      if (part.done) break;
    }
  } finally {
    clearTimeout(timer);
    await reader?.cancel().catch(() => {});
  }
  check(`${label} 获得唯一 start/runId`, run.events.filter(event => event.name === 'start').length === 1 && run.runId,
    { runId: run.runId, eventNames: run.events.map(event => event.name) });
  run.status = await waitRun(client, run.runId);
  run.database = runDatabase(run);
  run.durationMs = Date.now() - started;
  save();
  check(`${label} 同一请求完成且 HTTP/数据库答案一致`, run.status.status === 'COMPLETED'
    && run.database.status === 'COMPLETED' && run.database.messageStatus === 'COMPLETED'
    && run.status.answer?.length > 0 && run.database.answer === run.status.answer
    && run.database.requestCount === 1 && run.status.runId === run.runId,
  { runId: run.runId, status: run.status.status, errorCode: run.status.errorCode, requestCount: run.database.requestCount });
  if (disconnect) {
    check(`${label} 活跃时主动断流且只回查原运行`, run.disconnected
      && ['QUEUED', 'RUNNING'].includes(run.beforeDisconnect.status)
      && !run.events.some(event => ['done', 'error'].includes(event.name))
      && run.status.answer.startsWith(run.observedPrefix), { beforeStatus: run.beforeDisconnect.status, runId: run.runId });
  } else {
    const names = run.events.map(event => event.name);
    check(`${label} SSE 顺序和持久化增量一致`, names[0] === 'start'
      && names.at(-1) === 'done' && names.filter(name => name === 'done').length === 1
      && !names.includes('error') && names.includes('delta') && names.includes('sources')
      && names.indexOf('sources') > names.lastIndexOf('delta')
      && run.observedPrefix === run.status.answer
      && run.events.at(-1).data.runId === run.runId && run.events.at(-1).data.status === 'COMPLETED', { names, runId: run.runId });
  }
  return run;
}

function containsSeconds(answer, seconds) {
  // 只忽略行内展示标记；保留数值边界，避免 13 秒被误认为 3 秒。
  const text = answer.normalize('NFKC').replace(/[*_`~]/g, '');
  const chinese = new Map([[3, '三'], [7, '七'], [11, '十一'], [13, '十三']]).get(seconds);
  return new RegExp(`(?<![\\d一二三四五六七八九十])(?:${seconds}|${chinese})\\s*秒`).test(text);
}

async function verifyAnswer(client, run, fixture, { versionId = fixture.versionId, seconds = fixture.seconds, phrase = fixture.phrase, forbidden = [] } = {}) {
  const sources = run.status.sources ?? [];
  const allSources = run.database.sources;
  check(`${run.label} 正向回答包含本库事实和有效引用`, run.status.answer.includes(phrase)
    && containsSeconds(run.status.answer, seconds)
    && sources.length > 0 && sources.every(source => source.type === 'DOCUMENT'
      && source.documentId === fixture.documentId && source.versionId === versionId && source.available)
    && forbidden.every(text => !run.status.answer.includes(text)), { answer: run.status.answer, sources });
  check(`${run.label} 全部入模来源限定当前会话知识库`, allSources.length > 0
    && allSources.every(source => source.knowledgeBaseId === fixture.kbId && source.versionId === versionId), allSources);
  for (const source of sources) {
    const preview = await api(client, 'GET', `/api/chunks/${uuid(source.chunkId)}/source`);
    check(`${run.label} 引用 ${source.number} 精确对应文档版本和正文`, preview.chunkId === source.chunkId
      && preview.documentId === fixture.documentId && preview.versionId === versionId
      && preview.content.includes(fixture.code) && preview.content.includes(phrase),
    { chunkId: preview.chunkId, documentId: preview.documentId, versionId: preview.versionId, isCurrent: preview.isCurrent });
  }
}

function verifyRoots(run, first) {
  const firstRoots = new Set(first.database.sources.filter(source => !source.originSourceId).map(source => source.id));
  const inherited = run.database.sources.filter(source => source.originSourceId);
  check(`${run.label} 历史来源追溯同会话第一轮直接根来源`, firstRoots.size > 0
    && inherited.some(source => firstRoots.has(source.originSourceId))
    && inherited.every(source => source.rootOriginSourceId === null && source.rootSessionId === run.sessionId),
  { firstRootIds: [...firstRoots], inherited });
}

async function blankDocument(client, fixture) {
  const receipt = await upload(client, { ...fixture, label: 'blank-nonempty' }, ' \r\n\t \n');
  const blank = { documentId: uuid(receipt.documentId), versionId: uuid(receipt.versionId), jobId: uuid(receipt.jobId) };
  fixture.blank = blank;
  save();
  blank.job = await waitJob(client, blank.jobId);
  const document = await api(client, 'GET', `/api/documents/${blank.documentId}`);
  blank.database = database(`
-- document/document_version/ingestion_job：空白原文件应由真实 PARSE 阶段拒绝，不能激活。
-- document_chunk/vector_store：失败版本不应产生可检索的分块或向量。
SELECT json_build_object('fileSize',v.file_size,'versionStatus',v.status,'jobStatus',j.status,'errorCode',j.error_code,
 'activeVersionId',d.active_version_id,'pendingVersionId',d.pending_version_id,
 'chunkCount',(SELECT count(*) FROM document_chunk c WHERE c.version_id=v.id),
 'vectorCount',(SELECT count(*) FROM vector_store s JOIN document_chunk c ON c.id=s.id WHERE c.version_id=v.id))
 FROM document d JOIN document_version v ON v.document_id=d.id JOIN ingestion_job j ON j.version_id=v.id
 WHERE d.id='${blank.documentId}'::uuid AND v.id='${blank.versionId}'::uuid AND d.kb_id='${uuid(fixture.kbId)}'::uuid;`);
  check('非零字节空白 TXT 经真实 Worker 失败且无有效索引', blank.job.status === 'FAILED'
    && blank.job.errorCode === 'DOCUMENT_EMPTY' && blank.database.fileSize > 0
    && blank.database.versionStatus === 'FAILED' && blank.database.errorCode === 'DOCUMENT_EMPTY'
    && blank.database.chunkCount === 0 && blank.database.vectorCount === 0
    && blank.database.activeVersionId === null && blank.database.pendingVersionId === null
    && document.activeVersion === null && document.pendingVersion === null, blank);
  const retrieval = await api(client, 'POST', `/api/knowledge-bases/${fixture.kbId}/retrieval-tests`, {
    body: { query: `${fixture.code} 重试等待秒数和处理口令`, topK: 6, strategy: 'VECTOR' },
  });
  check('同库成功资料能检索而空白失败资料不参与检索', retrieval.matches.some(item => item.versionId === fixture.versionId)
    && retrieval.matches.every(item => item.versionId !== blank.versionId && item.documentId !== blank.documentId), retrieval.matches);
}

try {
  save();
  for (const variable of ['SUPPORTAI_F32_ADMIN_PASSWORD', 'SUPPORTAI_F32_READER_PASSWORD', 'SUPPORTAI_F32_SUPPORT2_PASSWORD']) {
    if (!process.env[variable]) throw new Error(`缺少环境变量 ${variable}。`);
  }
  summary.gitCommit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8', windowsHide: true }).trim();
  summary.backendSource = backendSourceIdentity();
  summary.deployment = deploymentIdentity();
  save();
  check('目标数据库可只读核对', database('SELECT json_build_object(\'ready\',true);').ready);
  summary.stage = 'LOGIN';
  const admin = await login('admin', 'SUPPORTAI_F32_ADMIN_PASSWORD');
  const userA = await login('support1', 'SUPPORTAI_F32_READER_PASSWORD');
  const userB = await login('support2', 'SUPPORTAI_F32_SUPPORT2_PASSWORD');
  check('三个演示身份相互独立', new Set([admin.user.id, userA.user.id, userB.user.id]).size === 3);
  summary.stage = 'FIXTURES_AND_PARSE_FAILURE';
  const a = await createFixture(admin, userA, 'user-a', '银杉回调', 3, '青竹');
  await blankDocument(userA, a);
  const b = await createFixture(admin, userB, 'user-b', '金桂回调', 7, '白鹭');
  const c = await createFixture(admin, userA, 'session-c', '紫薇回调', 11, '海棠');
  const sessionA = await createSession(userA, a);
  const sessionB = await createSession(userB, b);
  const sessionC = await createSession(userA, c);
  summary.stage = 'TWO_USER_POSITIVE_RAG';
  const firstA = await ask(userA, sessionA, `${a.code} 是什么意思？等待多少秒后重试，处理口令是什么？请给出来源引用。`, '用户 A 第一轮');
  await verifyAnswer(userA, firstA, a, { forbidden: [b.phrase, c.phrase] });
  const firstB = await ask(userB, sessionB, `${b.code} 是什么意思？等待多少秒后重试，处理口令是什么？请给出来源引用。`, '用户 B 正向问答');
  await verifyAnswer(userB, firstB, b, { forbidden: [a.phrase, c.phrase] });
  for (const [client, other, otherSession, otherRun] of [[userA, b, sessionB, firstB], [userB, a, sessionA, firstA]]) {
    const denied = [];
    for (const path of [`/api/knowledge-bases/${other.kbId}`, `/api/documents/${other.documentId}`,
      `/api/chat/sessions/${otherSession.sessionId}/messages`, `/api/chat/runs/${otherRun.runId}`,
      `/api/chunks/${otherRun.status.sources[0].chunkId}/source`]) {
      denied.push({ path, result: await api(client, 'GET', path, { expected: [404] }) });
    }
    check(`${client.actor} 不能读取另一用户知识/会话/运行/来源`, denied.every(item => item.result.status === 404), denied);
  }
  summary.stage = 'THREE_TURN_ROOTS_AND_SESSION_ISOLATION';
  const firstC = await ask(userA, sessionC, `${c.code} 是什么意思？等待多少秒后重试，处理口令是什么？请给出来源引用。`, '同用户另一会话首轮');
  await verifyAnswer(userA, firstC, c, { forbidden: [a.phrase, b.phrase] });
  const followup = '它应等待多少秒后重试，处理口令是什么？请根据本会话资料回答并引用来源。';
  const secondA = await ask(userA, sessionA, followup, '用户 A 第二轮指代');
  await verifyAnswer(userA, secondA, a, { forbidden: [b.phrase, c.phrase] });
  verifyRoots(secondA, firstA);
  const secondC = await ask(userA, sessionC, followup, '同用户另一会话相同指代');
  await verifyAnswer(userA, secondC, c, { forbidden: [a.phrase, b.phrase] });
  verifyRoots(secondC, firstC);
  const thirdA = await ask(userA, sessionA, '请再次确认上面故障的等待秒数和处理口令，引用本会话资料。', '用户 A 第三轮');
  await verifyAnswer(userA, thirdA, a, { forbidden: [b.phrase, c.phrase] });
  verifyRoots(thirdA, firstA);
  summary.stage = 'DISCONNECT_AND_GET_SAME_RUN';
  const disconnectSession = await createSession(userB, b);
  const disconnected = await ask(userB, disconnectSession, `${b.code} 的故障含义、等待秒数、处理口令是什么？请详细说明并引用资料。`, '主动断流后回查', { disconnect: true });
  await verifyAnswer(userB, disconnected, b, { forbidden: [a.phrase, c.phrase] });
  summary.stage = 'PUBLISH_AND_EXCLUDE_OLD_HISTORY';
  const previous = await api(userA, 'GET', `/api/documents/${a.documentId}`);
  const replacement = await upload(userA, a, contentFor(a, true), { version: true, expectedRevision: previous.revision });
  a.replacement = replacement;
  save();
  const replacementJob = await waitJob(userA, replacement.jobId);
  check('真实新版处理成功', replacementJob.status === 'SUCCEEDED', replacementJob);
  const current = await api(userA, 'GET', `/api/documents/${a.documentId}`);
  const oldSource = await api(userA, 'GET', `/api/chunks/${firstA.status.sources[0].chunkId}/source`);
  check('新版发布原子切换且旧引用仍可授权预览', current.activeVersion.versionId === replacement.versionId
    && current.pendingVersion === null && oldSource.versionId === a.versionId && oldSource.isCurrent === false,
  { activeVersionId: current.activeVersion.versionId, oldSourceVersionId: oldSource.versionId, oldSourceCurrent: oldSource.isCurrent });
  const afterReplacement = await ask(userA, sessionA, `${a.code} 当前手册要求等待多少秒，处理口令是什么？仅依据当前版本回答并引用来源。`, '新版发布后同一会话');
  await verifyAnswer(userA, afterReplacement, a, { versionId: replacement.versionId, seconds: 13, phrase: '紫藤', forbidden: [a.phrase, b.phrase, c.phrase] });
  check('三轮旧版本历史未进入新一轮登记来源闭包', afterReplacement.database.sources.length > 0
    && afterReplacement.database.sources.every(source => source.versionId === replacement.versionId && source.originSourceId === null),
  { sources: afterReplacement.database.sources, evidenceBoundary: '以生产 message_source 入模登记及 ConversationContextService 既有契约为据，未采集实际供应商 Prompt。' });
  const finalSource = backendSourceIdentity();
  const finalDeployment = deploymentIdentity();
  summary.finalIdentity = { backendSourceSha256: finalSource.sha256, deployment: finalDeployment };
  check('补验期间业务源码与运行镜像未变化', finalSource.sha256 === summary.backendSource.sha256
    && finalDeployment.containerId === summary.deployment.containerId
    && finalDeployment.imageId === summary.deployment.imageId
    && finalDeployment.jarSha256 === summary.deployment.jarSha256, summary.finalIdentity);
  summary.status = 'PASSED';
  summary.stage = 'COMPLETE';
} catch (error) {
  summary.status = 'FAILED';
  // 只记录本脚本生成的定位信息；认证值即使出现在错误文字中也必须脱敏。
  const message = safeMessage(error);
  summary.failure = { stage: summary.stage, message };
  console.error(`补验失败：${message}`);
  process.exitCode = 1;
} finally {
  // 每个已经登录的本次客户端都尝试退出；清理错误单独留痕，不能抹掉业务首个失败。
  for (const client of loggedInClients) {
    try {
      const requestTimeoutMs = Math.min(timeoutMs, 15000);
      client.csrf = await api(client, 'GET', '/api/auth/csrf', { requestTimeoutMs });
      await api(client, 'POST', '/api/auth/logout', { expected: [204], requestTimeoutMs });
      const denied = await api(client, 'GET', '/api/auth/me', { expected: [401], requestTimeoutMs });
      summary.logout.push({ actor: client.actor, passed: denied.status === 401 });
    } catch (error) {
      summary.logout.push({ actor: client.actor, passed: false, message: safeMessage(error) });
      if (!summary.failure) summary.failure = { stage: 'LOGOUT', message: '业务检查结束，但至少一个验收客户端退出未通过。' };
      summary.status = 'FAILED';
      process.exitCode = 1;
    } finally {
      client.cookies.clear();
      client.csrf = null;
      save();
    }
  }
  summary.finishedAt = new Date().toISOString();
  save();
  closeSync(outputHandle);
  console.log(`本次状态 ${summary.status}；证据 ${output}`);
}
