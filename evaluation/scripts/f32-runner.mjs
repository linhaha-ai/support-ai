import { createHash, randomUUID } from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { readFile, writeFile, rename, mkdir, unlink } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { compareResults, EVALUATION_ROOT, summarizeResults, validateDataset } from "./f21-evaluation.mjs";

const execute = promisify(execFile);
const ROOT = resolve(EVALUATION_ROOT, "..");
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SHA256 = /^[0-9a-f]{64}$/;
const RESULT_FIELDS = new Set(["caseId", "status", "requestId", "runId", "sessionId", "answer", "noEvidence",
  "configSnapshot", "retrievedEvidenceIds", "citedEvidenceIds", "retrievalEvidenceBasis", "sources",
  "citedSourcePreviews", "metrics", "usage", "errorCode", "startedAt", "finishedAt", "streaming", "humanReview", "reason"]);
const TERMINAL = new Set(["COMPLETED", "FAILED", "CANCELLED"]);
const ensure = (condition, message) => { if (!condition) throw new Error(message); };
const now = () => new Date().toISOString();
const delay = (ms) => new Promise((resolveDelay) => setTimeout(resolveDelay, ms));
export const canonical = (value) => Array.isArray(value) ? `[${value.map(canonical).join(",")}]`
  : value && typeof value === "object" ? `{${Object.keys(value).sort()
    .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}` : JSON.stringify(value);
export const hash = (value) => createHash("sha256").update(typeof value === "string" ? value : canonical(value)).digest("hex");
const readJson = async (path) => JSON.parse(await readFile(resolve(path), "utf8"));
async function loadRunnerDataset() {
  const dataset = await validateDataset();
  dataset.evidenceIds = [];
  for (const entry of dataset.manifest.corpus) dataset.evidenceIds.push(...collectEvidenceRanges(
    await readFile(resolve(EVALUATION_ROOT, entry.file), "utf8")).map((item) => item.id));
  return dataset;
}
async function optionalJson(path) {
  try { return await readJson(path); } catch (error) { if (error.code === "ENOENT") return null; throw error; }
}
async function save(path, value) {
  const target = resolve(path);
  ensure(target.endsWith(".json"), "评测产物必须写入 .json 文件，不能覆盖环境配置文件");
  await mkdir(dirname(target), { recursive: true });
  const temporary = `${target}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await rename(temporary, target);
}

async function withFileLock(path, action) {
  const lockPath = `${resolve(path)}.lock`;
  await mkdir(dirname(lockPath), { recursive: true });
  try { await writeFile(lockPath, JSON.stringify({ pid: process.pid, startedAt: now() }), { flag: "wx" }); }
  catch (error) {
    if (error.code !== "EEXIST") throw error;
    const lock = await readJson(lockPath);
    let alive = true;
    try { process.kill(lock.pid, 0); } catch (signalError) { if (signalError.code === "ESRCH") alive = false; }
    ensure(!alive, "相同输出文件已由另一个评测进程使用，禁止并发重复提交");
    await unlink(lockPath);
    return withFileLock(path, action);
  }
  try { return await action(); } finally { await unlink(lockPath); }
}

// 使用增量解码器，避免 UTF-8 汉字或 CRLF 被网络分段截断。
export class SseParser {
  constructor(onEvent) { this.onEvent = onEvent; this.decoder = new TextDecoder(); this.buffer = ""; }
  push(bytes, final = false) {
    this.buffer += this.decoder.decode(bytes, { stream: !final });
    let match;
    while ((match = /\r?\n\r?\n/.exec(this.buffer))) {
      const block = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);
      const data = [];
      let type = "message";
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith("event:")) type = line.slice(6).trim();
        if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
      }
      if (data.length) this.onEvent({ type, data: JSON.parse(data.join("\n")) });
    }
    if (final) ensure(!this.buffer.trim(), "SSE 在完整事件结束前断开");
  }
}

export function normalizeDocument(text) {
  return text.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n")
    .replace(/[\x00-\x08\x0B\x0C\x0E-\x1F]/g, "").replace(/[\t ]+(?=\n|$)/g, "");
}

export function collectEvidenceRanges(content) {
  const normalized = normalizeDocument(content);
  const markers = [...normalized.matchAll(/<!--\s*evidence:([A-Z0-9-]+)\s*-->/g)];
  return markers.map((marker, index) => {
    const sectionStart = marker.index + marker[0].length;
    const sectionEnd = markers[index + 1]?.index ?? normalized.length;
    const section = normalized.slice(sectionStart, sectionEnd);
    const bodyStart = section.search(/^(?!\s*$|\s*#{1,6} )\S/m);
    ensure(bodyStart >= 0, `证据 ${marker[1]} 没有正文`);
    const text = section.slice(bodyStart).trimEnd();
    return { id: marker[1], startOffset: sectionStart + bodyStart,
      endOffset: sectionStart + bodyStart + text.length, text };
  });
}

export function mapEvidence(binding, sources) {
  const ids = [];
  for (const document of Object.values(binding.documents)) {
    const chunkIds = new Set(sources.filter((source) => source.versionId === document.versionId)
      .map((source) => source.chunkId));
    const ranges = document.chunks.filter((chunk) => chunkIds.has(chunk.chunkId))
      .map((chunk) => [chunk.startOffset, chunk.endOffset]).sort((a, b) => a[0] - b[0]);
    for (const evidence of document.evidence) {
      let coveredUntil = evidence.startOffset;
      for (const [start, end] of ranges) {
        if (start > coveredUntil) break;
        if (end > coveredUntil) coveredUntil = end;
      }
      // 仅出现 evidence 注释或标题不算命中，必须覆盖对应的完整事实正文。
      if (coveredUntil >= evidence.endOffset) ids.push(evidence.id);
    }
  }
  return [...new Set(ids)].sort();
}

export function assertFrozen(freeze, actual) {
  for (const key of ["datasetVersion", "datasetSha256", "gitCommit", "environment", "imageId", "runtimeHash",
    "runtimeWithoutStrategyHash", "bindingHash", "sourceTreeHash", "runnerSha256"]) {
    ensure(freeze[key] === actual[key], `冻结后发生漂移：${key}`);
  }
  if (actual.configSnapshot) ensure(canonical(freeze.configSnapshot) === canonical(actual.configSnapshot),
    "冻结后发生漂移：configSnapshot");
}

export function runtimeIdentity(item) {
  const environment = item.Config.Env.toSorted();
  const runtime = { image: item.Image, environment, command: item.Config.Cmd, entrypoint: item.Config.Entrypoint };
  // 策略比较只豁免这一项环境变量；JVM 参数、模型配置和其他变量仍必须完全一致。
  const withoutStrategy = { ...runtime, environment: environment.filter((entry) =>
    !entry.startsWith("SUPPORT_AI_RETRIEVAL_STRATEGY=")) };
  return { imageId: item.Image, runtimeHash: hash(runtime), runtimeWithoutStrategyHash: hash(withoutStrategy) };
}

export function checkpointAction(checkpoint, existingRun) {
  if (checkpoint?.result) return "SKIP";
  if (existingRun) return "COLLECT";
  ensure(!checkpoint?.submittedAt, "该题曾提交但尚未查到运行，禁止盲目重放；请核对运行状态后人工处理");
  return "SUBMIT";
}

class ApiClient {
  constructor(baseUrl) {
    const parsed = new URL(baseUrl);
    ensure(!parsed.username && !parsed.password, "base-url 不得包含认证凭据");
    this.baseUrl = parsed.origin; this.cookies = new Map(); this.csrf = null;
  }
  async request(path, { method = "GET", body, stream = false } = {}) {
    ensure(path.startsWith("/api/"), "仅允许请求当前服务的 /api 路径");
    const headers = { Accept: stream ? "text/event-stream, application/json" : "application/json" };
    if (this.cookies.size) headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    if (method !== "GET" && this.csrf) headers[this.csrf.headerName] = this.csrf.token;
    if (body && !(body instanceof FormData)) { headers["Content-Type"] = "application/json"; body = JSON.stringify(body); }
    const response = await fetch(`${this.baseUrl}${path}`, { method, headers, body,
      signal: AbortSignal.timeout(stream ? 900000 : 120000), redirect: "error" });
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(";", 1)[0]; const separator = pair.indexOf("=");
      this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
    if (!response.ok) {
      const envelope = await response.json().catch(() => ({}));
      throw new Error(`${method} ${path} 返回 HTTP ${response.status}，业务码 ${envelope.code ?? "UNKNOWN"}`);
    }
    if (stream) return response;
    const envelope = await response.json();
    ensure(envelope.code === "OK", `${method} ${path} 返回非 OK 业务码`);
    return envelope.data;
  }
  async login() {
    ensure(process.env.F32_USERNAME && process.env.F32_PASSWORD, "请通过 F32_USERNAME 和 F32_PASSWORD 环境变量提供登录信息");
    this.csrf = await this.request("/api/auth/csrf");
    await this.request("/api/auth/login", { method: "POST", body: {
      username: process.env.F32_USERNAME, password: process.env.F32_PASSWORD } });
    this.csrf = await this.request("/api/auth/csrf");
  }
}

class RuntimeReader {
  constructor(project) { ensure(project && /^[a-z0-9][a-z0-9_-]*$/.test(project), "必须提供合法 --project"); this.project = project; }
  async container(service) {
    const { stdout } = await execute("docker", ["ps", "--filter", `label=com.docker.compose.project=${this.project}`,
      "--filter", `label=com.docker.compose.service=${service}`, "--format", "{{.ID}}"], { cwd: ROOT });
    const ids = stdout.trim().split(/\s+/).filter(Boolean);
    ensure(ids.length === 1, `${this.project} 的 ${service} 必须只有一个运行容器`);
    return ids[0];
  }
  async sql(query) {
    const postgres = await this.container("postgres");
    const { stdout } = await execute("docker", ["exec", postgres, "psql", "-X", "-A", "-t", "-v",
      "ON_ERROR_STOP=1", "-U", "supportai", "-d", "supportai", "-c", query], { cwd: ROOT, maxBuffer: 8 * 1024 * 1024 });
    return stdout.trim() ? JSON.parse(stdout.trim()) : null;
  }
  async identity() {
    const app = await this.container("app");
    const { stdout } = await execute("docker", ["inspect", app], { cwd: ROOT, maxBuffer: 1024 * 1024 });
    const item = JSON.parse(stdout)[0];
    // 整体环境只参与摘要；不输出原值，也覆盖 JAVA_TOOL_OPTIONS 等可改变模型配置的入口。
    return runtimeIdentity(item);
  }
  async version(versionId) {
    ensure(UUID.test(versionId), "非法版本标识");
    return this.sql(`SELECT json_build_object('configHash', config_hash, 'configSnapshot', config_snapshot,
      'embeddingModel', embedding_model, 'dimensions', dimensions) FROM document_version WHERE id='${versionId}'::uuid`);
  }
  async runFor(sessionId, requestId) {
    ensure(UUID.test(sessionId) && UUID.test(requestId), "非法会话或请求标识");
    return this.sql(`SELECT json_build_object('runId', id, 'status', status, 'configSnapshot', config_snapshot)
      FROM chat_run WHERE session_id='${sessionId}'::uuid AND client_request_id='${requestId}'::uuid`);
  }
  async sources(runId) {
    ensure(UUID.test(runId), "非法运行标识");
    return this.sql(`SELECT COALESCE(json_agg(json_build_object('number', s.citation_no, 'cited', s.is_cited,
      'chunkId', s.chunk_id, 'documentId', s.snapshot->>'documentId', 'versionId', s.snapshot->>'versionId',
      'locator', s.locator, 'snapshot', s.snapshot) ORDER BY s.citation_no), '[]'::json)
      FROM message_source s JOIN chat_message m ON m.id=s.message_id
      WHERE m.run_id='${runId}'::uuid AND m.role='ASSISTANT'`);
  }
}

async function waitJob(client, jobId) {
  const deadline = Date.now() + 20 * 60 * 1000;
  while (Date.now() < deadline) {
    const job = await client.request(`/api/ingestion-jobs/${jobId}`);
    if (job.status === "SUCCEEDED") return job;
    ensure(!["FAILED", "CANCELLED"].includes(job.status), `摄取任务 ${jobId} 终止：${job.errorCode ?? job.status}`);
    await delay(2000);
  }
  throw new Error(`等待摄取任务 ${jobId} 超时，可使用相同 state 继续等待`);
}

async function allChunks(client, documentId, versionId) {
  const chunks = []; let cursor;
  do {
    const page = await client.request(`/api/documents/${documentId}/versions/${versionId}/chunks?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`);
    chunks.push(...page.items); cursor = page.nextCursor;
  } while (cursor);
  return chunks;
}

export async function prepare(options) {
  const stateFile = options.state ?? ".local/evaluation/f32-binding.json";
  return withFileLock(stateFile, () => prepareLocked(options, stateFile));
}

async function prepareLocked(options, stateFile) {
  const dataset = await validateDataset();
  const client = new ApiClient(options["base-url"] ?? "http://127.0.0.1:8088");
  const runtime = new RuntimeReader(options.project);
  await client.login();
  let binding = await optionalJson(stateFile);
  if (!binding) {
    const kb = await client.request("/api/knowledge-bases", { method: "POST", body: {
      name: `F32 真实评测 ${now()}`, description: "F21 固定脱敏语料，仅用于真实评测与人工复核" } });
    binding = { schemaVersion: "1.0", createdAt: now(), datasetVersion: dataset.datasetVersion,
      baseUrl: client.baseUrl, project: options.project, knowledgeBaseId: kb.id, documents: {} };
    await save(stateFile, binding);
  }
  ensure(binding.baseUrl === client.baseUrl && binding.project === options.project,
    "state 所属环境不同，请使用独立 state 文件");
  ensure(binding.datasetVersion === dataset.datasetVersion, "state 数据集版本不匹配");
  for (const entry of dataset.manifest.corpus) {
    const key = `${entry.documentKey}@${entry.version}`;
    let document = binding.documents[key];
    if (document?.status === "READY") { console.log(`${key} 已入库，复用`); continue; }
    if (!document?.jobId) {
      ensure(!document?.submittedAt, `${key} 上传状态不明，禁止重复上传，请先人工核对知识库文档`);
      const previous = Object.values(binding.documents).find((item) => item.documentKey === entry.documentKey && item.documentId);
      const form = new FormData();
      const bytes = await readFile(resolve(EVALUATION_ROOT, entry.file));
      form.append("file", new Blob([bytes], { type: "text/markdown" }), basename(entry.file));
      let path = `/api/knowledge-bases/${binding.knowledgeBaseId}/documents`;
      if (previous) {
        const detail = await client.request(`/api/documents/${previous.documentId}`);
        form.append("expectedRevision", String(detail.revision));
        path = `/api/documents/${previous.documentId}/versions`;
      } else form.append("displayName", entry.documentKey);
      binding.documents[key] = { ...entry, submittedAt: now() };
      await save(stateFile, binding);
      const receipt = await client.request(path, { method: "POST", body: form });
      document = { ...binding.documents[key], documentId: receipt.documentId ?? previous?.documentId,
        versionId: receipt.versionId, jobId: receipt.jobId };
      binding.documents[key] = document;
      await save(stateFile, binding);
    }
    await waitJob(client, document.jobId);
    const content = await client.request(`/api/documents/${document.documentId}/versions/${document.versionId}/content`);
    ensure(hash(content.content.replace(/\r\n/g, "\n")) === entry.sha256, `${key} 原文 SHA-256 不匹配`);
    document.chunks = await allChunks(client, document.documentId, document.versionId);
    document.evidence = collectEvidenceRanges(content.content);
    Object.assign(document, await runtime.version(document.versionId));
    document.status = "READY";
    await save(stateFile, binding);
    console.log(`${key} 已入库，${document.chunks.length} 个分块`);
  }
  for (const document of Object.values(binding.documents).filter((item) => item.active)) {
    const detail = await client.request(`/api/documents/${document.documentId}`);
    ensure(detail.activeVersion?.versionId === document.versionId && !detail.pendingVersion,
      `${document.documentKey} 当前生效版本不符合清单`);
  }
  binding.completedAt = now();
  await save(stateFile, binding);
  return { state: stateFile, knowledgeBaseId: binding.knowledgeBaseId, documents: Object.keys(binding.documents).length };
}

function bindingHash(binding) {
  return hash({ knowledgeBaseId: binding.knowledgeBaseId, documents: Object.values(binding.documents)
    .map(({ documentKey, version, versionId, sha256, configHash }) => ({ documentKey, version, versionId, sha256, configHash })) });
}

async function gitCommit() { return (await execute("git", ["rev-parse", "HEAD"], { cwd: ROOT })).stdout.trim(); }

async function sourceIdentity() {
  const scope = ["backend", "frontend", "deploy/app", "deploy/web", "deploy/compose.yml", "deploy/maintenance", ".dockerignore"];
  const { stdout } = await execute("git", ["ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", ...scope],
    { cwd: ROOT, maxBuffer: 4 * 1024 * 1024 });
  const files = [...new Set(stdout.split("\0").filter(Boolean))].sort();
  const entries = [];
  for (const file of files) {
    try { entries.push([file, createHash("sha256").update(await readFile(resolve(ROOT, file))).digest("hex")]); }
    catch (error) { if (error.code === "ENOENT") entries.push([file, "DELETED"]); else throw error; }
  }
  const dirty = await execute("git", ["status", "--porcelain=v1", "--untracked-files=all", "--", ...scope], { cwd: ROOT });
  // 允许带明确记录的未提交业务代码验收；既有 AGENTS 修改不影响运行源码指纹。
  return { sourceTreeHash: hash(entries), sourceDirtyPaths: dirty.stdout.trim().split(/\r?\n/).filter(Boolean),
    runnerSha256: createHash("sha256").update(await readFile(fileURLToPath(import.meta.url))).digest("hex") };
}

export function validateCitedSources(persistedSources, responseSources) {
  const identity = (source) => `${source.number}|${source.documentId}|${source.versionId}|${source.chunkId}`;
  const persisted = persistedSources.filter((source) => source.cited).map(identity).sort();
  const response = responseSources.map(identity).sort();
  ensure(new Set(responseSources.map((source) => source.chunkId)).size === responseSources.length,
    "HTTP 引用来源包含重复分块");
  ensure(canonical(persisted) === canonical(response), "数据库引用与 HTTP 来源不完全一致");
}

export function validateSourcePreview(source, preview) {
  ensure(["chunkId", "documentId", "versionId"].every((key) => source[key] === preview?.[key]),
    "来源预览的分块、文档或版本身份不一致");
  ensure(typeof preview.content === "string" && preview.content.trim(), "来源预览缺少正文");
}

function validateSnapshot(config) {
  ensure(config?.schemaVersion === "f24-v1" && config.mode === "RAG", "缺少真实 f24-v1 RAG 配置快照");
  ensure(config.chat?.role === "CHAT" && typeof config.chat.model === "string"
    && config.embedding?.role === "EMBEDDING" && typeof config.embedding.model === "string", "配置缺少模型角色或名称");
  ensure(["VECTOR", "FUSION"].includes(config.retrieval?.strategy)
    && Number.isInteger(config.retrieval.topK) && config.retrieval.topK > 0, "配置缺少合法检索策略或 TopK");
  ensure(typeof config.rerank?.enabled === "boolean" && SHA256.test(config.index?.configHash)
    && Number.isInteger(config.answer?.maxEvidenceTokens) && config.answer.maxEvidenceTokens > 0,
  "配置缺少重排开关、索引配置摘要或证据预算");
}

export function validateRunRecord(run, dataset, { requireComplete = false, requireCheckpoints = false } = {}) {
  ensure(run?.schemaVersion === "1.0" && run.metadata && Array.isArray(run.results), "运行记录结构无效");
  const metadata = run.metadata;
  ensure(["development", "test"].includes(metadata.split), "运行记录 split 无效");
  ensure(metadata.datasetVersion === dataset.datasetVersion && metadata.datasetSha256 ===
    (metadata.split === "development" ? dataset.developmentSha256 : dataset.testSha256), "运行记录数据集版本或 SHA-256 不匹配");
  ensure(/^[0-9a-f]{40,64}$/.test(metadata.gitCommit) && typeof metadata.environment === "string"
    && metadata.environment.trim(), "运行记录缺少 Git 或环境标识");
  for (const key of ["runtimeHash", "runtimeWithoutStrategyHash", "bindingHash", "sourceTreeHash", "runnerSha256"]) {
    ensure(SHA256.test(metadata[key]), `运行记录缺少 ${key}`);
  }
  ensure(typeof metadata.imageId === "string" && metadata.imageId.trim(), "运行记录缺少实际镜像标识");
  ensure(Number.isFinite(Date.parse(metadata.startedAt)), "运行开始时间无效");
  if (metadata.completedAt) ensure(Number.isFinite(Date.parse(metadata.completedAt))
    && Date.parse(metadata.completedAt) >= Date.parse(metadata.startedAt), "运行结束时间无效");
  const cases = dataset.cases.filter((item) => item.split === metadata.split);
  const caseById = new Map(cases.map((item) => [item.id, item]));
  const evidenceIds = new Set(dataset.evidenceIds ?? dataset.cases.flatMap((item) => item.evidenceIds));
  const seen = new Set(); const runs = new Set(); const requests = new Set(); const sessions = new Set();
  for (const result of run.results) {
    const testCase = caseById.get(result?.caseId);
    ensure(testCase, `运行结果包含未知或范围外题目：${result?.caseId}`);
    ensure(Object.keys(result).every((key) => RESULT_FIELDS.has(key)), `${result.caseId} 包含未知结果字段`);
    ensure(!seen.has(result.caseId), `重复题目：${result.caseId}`); seen.add(result.caseId);
    ensure(["COMPLETED", "FAILED", "CANCELLED", "NOT_RUN"].includes(result.status), `${result.caseId} 的结果状态无效`);
    const special = testCase.executionMode === "CONTRACT_TEST" || testCase.scenario;
    if (special) ensure(result.status === "NOT_RUN", `${result.caseId} 的独立场景不能由普通 runner 标记成功`);
    else ensure(result.status !== "NOT_RUN", `${result.caseId} 不是独立场景，不能以 NOT_RUN 替代真实执行`);
    ensure(result.humanReview?.citationCorrect === null && result.humanReview?.evidenceSufficient === null
      && typeof result.humanReview?.notes === "string", `${result.caseId} 的自动采集记录不得冒充人工评分`);
    for (const key of ["retrievedEvidenceIds", "citedEvidenceIds"]) {
      ensure(Array.isArray(result[key]) && new Set(result[key]).size === result[key].length,
        `${result.caseId} 的 ${key} 缺失或重复`);
      ensure(result[key].every((id) => evidenceIds.has(id)), `${result.caseId} 存在未知证据标识`);
    }
    ensure(result.citedEvidenceIds.every((id) => result.retrievedEvidenceIds.includes(id)), `${result.caseId} 引用了未入模证据`);
    if (result.status === "NOT_RUN") {
      ensure(typeof result.reason === "string" && result.reason.trim() && !result.runId
        && result.retrievedEvidenceIds.length === 0 && result.citedEvidenceIds.length === 0,
      `${result.caseId} 的 NOT_RUN 记录包含伪运行证据`);
    } else {
      for (const [key, values] of [["runId", runs], ["requestId", requests], ["sessionId", sessions]]) {
        ensure(UUID.test(result[key]) && !values.has(result[key]), `${result.caseId} 的 ${key} 无效或重复`);
        values.add(result[key]);
      }
      validateSnapshot(result.configSnapshot);
      ensure(canonical(metadata.configSnapshot) === canonical(result.configSnapshot), `${result.caseId} 配置快照发生漂移`);
      ensure(typeof result.answer === "string" && Array.isArray(result.sources) && Array.isArray(result.citedSourcePreviews),
        `${result.caseId} 缺少回答或来源记录`);
      ensure(new Set(result.sources.map((source) => source.chunkId)).size === result.sources.length,
        `${result.caseId} 包含重复来源`);
      for (const source of result.sources) ensure(UUID.test(source.chunkId) && UUID.test(source.documentId)
        && UUID.test(source.versionId) && typeof source.cited === "boolean", `${result.caseId} 来源标识或引用状态无效`);
      validateCitedSources(result.sources, result.citedSourcePreviews);
      for (const source of result.citedSourcePreviews) validateSourcePreview(source, source.preview);
      if (result.status === "COMPLETED") {
        ensure(typeof result.noEvidence === "boolean" && result.noEvidence === result.metrics?.noEvidence,
          `${result.caseId} 的 noEvidence 不一致`);
        ensure(result.metrics.evidenceCount === result.sources.length
          && result.metrics.citedCount === result.sources.filter((source) => source.cited).length,
        `${result.caseId} 的分块来源数量与运行指标不一致`);
        ensure(result.noEvidence === (result.sources.length === 0), `${result.caseId} 的资料不足状态与入模来源不一致`);
        for (const key of ["retrievalMs", "totalMs"]) ensure(Number.isFinite(result.metrics[key])
          && result.metrics[key] >= 0, `${result.caseId} 缺少真实 ${key}`);
      }
      if (result.usage != null) {
        for (const key of ["promptTokens", "completionTokens", "totalTokens"]) ensure(result.usage[key] == null
          || Number.isInteger(result.usage[key]) && result.usage[key] >= 0, `${result.caseId} Token 用量无效`);
        const { promptTokens, completionTokens, totalTokens } = result.usage;
        if ([promptTokens, completionTokens, totalTokens].every(Number.isInteger)) ensure(promptTokens + completionTokens === totalTokens,
          `${result.caseId} Token 用量不守恒`);
      }
    }
  }
  if (run.results.some((item) => item.status !== "NOT_RUN")) validateSnapshot(metadata.configSnapshot);
  if (requireComplete) {
    ensure(run.results.length === cases.length, `冻结前必须覆盖完整开发集：${run.results.length}/${cases.length}`);
    ensure(metadata.completedAt, "完整运行缺少结束时间");
  }
  if (requireCheckpoints) {
    ensure(run.checkpoints && typeof run.checkpoints === "object", "缺少断点信息，精简导出不能用于续跑");
    for (const [caseId, checkpoint] of Object.entries(run.checkpoints)) {
      ensure(caseById.has(caseId), `断点包含未知题目：${caseId}`);
      const result = run.results.find((item) => item.caseId === caseId);
      ensure(canonical(checkpoint.result) === canonical(result), `${caseId} 断点结果与运行结果不一致`);
      if (checkpoint.result?.status !== "NOT_RUN") {
        ensure(UUID.test(checkpoint.sessionId) && UUID.test(checkpoint.clientRequestId), `${caseId} 断点缺少固定请求标识`);
        if (result) ensure(result.requestId === checkpoint.clientRequestId && result.sessionId === checkpoint.sessionId,
          `${caseId} 断点请求身份与结果不一致`);
      }
    }
    ensure(run.results.every((result) => run.checkpoints[result.caseId]?.result), "运行结果缺少对应断点，禁止重放");
  }
  return cases;
}

export function validateFreezeRecord(frozen, development, dataset) {
  ensure(frozen?.freezeVersion === "1.0" && frozen.split === "test" && frozen.humanReviewStatus === "PENDING",
    "冻结文件格式无效，必须使用 freeze 命令生成");
  ensure(typeof frozen.reason === "string" && frozen.reason.trim() && Number.isFinite(Date.parse(frozen.frozenAt)),
    "冻结文件缺少理由或时间");
  ensure(frozen.sourceDevelopmentSha256 === hash(development), "冻结来源开发记录内容已变化");
  ensure(frozen.configSnapshotSha256 === hash(frozen.configSnapshot), "冻结配置摘要不一致");
  validateRunRecord(development, dataset, { requireComplete: true });
  ensure(development.metadata.split === "development", "冻结来源必须为 development 记录");
  ensure(Date.parse(frozen.frozenAt) >= Date.parse(development.metadata.completedAt), "冻结早于开发集完成时间");
  assertFrozen(frozen, { ...development.metadata, datasetSha256: dataset.testSha256 });
}

async function collectResult(client, runtime, binding, testCase, checkpoint) {
  let run;
  const deadline = Date.now() + 900000;
  while (Date.now() < deadline) {
    run = await client.request(`/api/chat/runs/${checkpoint.runId}`);
    if (TERMINAL.has(run.status)) break;
    await delay(2000);
  }
  ensure(TERMINAL.has(run?.status), `运行 ${checkpoint.runId} 尚未终止，请稍后断点续跑`);
  const persisted = await runtime.runFor(checkpoint.sessionId, checkpoint.clientRequestId);
  ensure(persisted?.runId === checkpoint.runId, "持久化运行与请求标识不匹配");
  const history = await client.request(`/api/chat/sessions/${checkpoint.sessionId}/messages?limit=100`);
  const assistant = history.items.find((item) => item.runId === run.runId && item.role === "ASSISTANT");
  ensure(assistant && assistant.content === run.answer && assistant.status === run.assistantStatus,
    "会话历史与运行状态中的回答不一致");
  const sources = await runtime.sources(run.runId);
  validateCitedSources(sources, run.sources);
  validateCitedSources(sources, assistant.sources);
  const previews = [];
  for (const source of run.sources) {
    ensure(source.available && source.previewPath, "已引用来源无法访问");
    const preview = await client.request(source.previewPath);
    validateSourcePreview(source, preview);
    previews.push({ ...source, preview });
  }
  return { caseId: testCase.id, status: run.status, requestId: checkpoint.clientRequestId,
    runId: run.runId, sessionId: checkpoint.sessionId, answer: run.answer, noEvidence: run.metrics?.noEvidence ?? null,
    configSnapshot: persisted.configSnapshot, retrievedEvidenceIds: mapEvidence(binding, sources),
    citedEvidenceIds: mapEvidence(binding, sources.filter((item) => item.cited)),
    retrievalEvidenceBasis: "PERSISTED_MODEL_INPUT_SOURCES_AFTER_SCORE_AND_TOKEN_FILTERS",
    sources, citedSourcePreviews: previews, metrics: run.metrics, usage: run.usage,
    errorCode: run.errorCode, startedAt: run.startedAt, finishedAt: run.finishedAt,
    streaming: checkpoint.streaming ?? null,
    humanReview: { citationCorrect: null, evidenceSufficient: null, notes: "待人工核对；自动采集不生成主观评分" } };
}

export async function runEvaluation(options) {
  ensure(options.out, "run 必须提供 --out");
  return withFileLock(options.out, () => runEvaluationLocked(options));
}

async function runEvaluationLocked(options) {
  const dataset = await loadRunnerDataset();
  const split = options.split ?? "development";
  ensure(["development", "test"].includes(split), "split 必须为 development 或 test");
  ensure(options.binding && options.out && options.environment, "run 必须提供 --binding、--out、--environment");
  ensure(split !== "test" || options.freeze, "固定测试集运行前必须提供 --freeze");
  const binding = await readJson(options.binding);
  ensure(binding.completedAt && binding.project === options.project, "binding 未完成或 project 不匹配");
  ensure(binding.datasetVersion === dataset.datasetVersion, "binding 与当前数据集版本不匹配");
  for (const entry of dataset.manifest.corpus) {
    const document = binding.documents[`${entry.documentKey}@${entry.version}`];
    ensure(document?.status === "READY" && document.sha256 === entry.sha256,
      `binding 缺少已验证语料 ${entry.documentKey}@${entry.version}`);
  }
  const runtime = new RuntimeReader(options.project);
  const client = new ApiClient(options["base-url"] ?? binding.baseUrl);
  ensure(client.baseUrl === binding.baseUrl, "当前地址与 binding 不匹配");
  await client.login();
  for (const document of Object.values(binding.documents).filter((item) => item.active)) {
    const detail = await client.request(`/api/documents/${document.documentId}`);
    ensure(detail.activeVersion?.versionId === document.versionId && !detail.pendingVersion,
      `${document.documentKey} 的当前版本已变化，请重新准备独立评测语料`);
    const version = await runtime.version(document.versionId);
    ensure(version.configHash === document.configHash, `${document.documentKey} 的处理配置已变化`);
  }
  const identity = await runtime.identity();
  const metadata = { datasetVersion: dataset.datasetVersion,
    datasetSha256: split === "development" ? dataset.developmentSha256 : dataset.testSha256,
    split, gitCommit: await gitCommit(), environment: options.environment, ...identity, ...await sourceIdentity(),
    project: options.project, baseUrl: client.baseUrl,
    bindingHash: bindingHash(binding), startedAt: now(), completedAt: null,
    configSnapshot: null, reviewer: "PENDING_HUMAN_REVIEW" };
  const frozen = options.freeze ? await readJson(options.freeze) : null;
  if (frozen) {
    ensure(typeof frozen.sourceDevelopmentRun === "string", "冻结文件缺少来源开发记录路径");
    validateFreezeRecord(frozen, await readJson(frozen.sourceDevelopmentRun), dataset);
    assertFrozen(frozen, metadata);
    metadata.freezeSha256 = hash(frozen);
  }
  let output = await optionalJson(options.out);
  if (output) {
    validateRunRecord(output, dataset, { requireCheckpoints: true });
    assertFrozen(output.metadata, { ...metadata, configSnapshot: undefined });
    ensure(output.metadata.split === split, "断点文件的 split 不匹配");
    ensure(output.metadata.freezeSha256 === metadata.freezeSha256, "断点与当前冻结文件不一致");
  } else output = { schemaVersion: "1.0", metadata, checkpoints: {}, results: [] };
  const cases = dataset.cases.filter((item) => item.split === split);
  const selected = options.cases ? new Set(options.cases.split(",")) : null;
  if (selected) ensure([...selected].every((id) => cases.some((item) => item.id === id)), "--cases 包含范围外题目");
  let executed = 0;
  for (const testCase of cases) {
    if (selected && !selected.has(testCase.id)) continue;
    let checkpoint = output.checkpoints[testCase.id];
    if (checkpoint?.result) continue;
    if (options.limit && executed >= Number(options.limit)) break;
    if (testCase.executionMode === "CONTRACT_TEST" || testCase.scenario) {
      const result = { caseId: testCase.id, status: "NOT_RUN", reason: "该题需要独立契约或权限夹具证据，普通问答不能替代",
        retrievedEvidenceIds: [], citedEvidenceIds: [], humanReview: { citationCorrect: null,
          evidenceSufficient: null, notes: "待独立场景验收" } };
      output.checkpoints[testCase.id] = { result }; output.results.push(result);
      await save(options.out, output); continue;
    }
    if (!checkpoint) {
      const session = await client.request("/api/chat/sessions", { method: "POST", body: {
        kbIds: [binding.knowledgeBaseId], mode: "RAG" } });
      checkpoint = { sessionId: session.sessionId, clientRequestId: randomUUID() };
      output.checkpoints[testCase.id] = checkpoint;
      await save(options.out, output);
    }
    const existing = await runtime.runFor(checkpoint.sessionId, checkpoint.clientRequestId);
    const action = checkpointAction(checkpoint, existing);
    if (action === "SUBMIT") {
      checkpoint.submittedAt = now(); await save(options.out, output);
      const streamStart = Date.now(); const events = []; let parseError;
      try {
        const response = await client.request(`/api/chat/sessions/${checkpoint.sessionId}/messages`, {
          method: "POST", body: { clientRequestId: checkpoint.clientRequestId, content: testCase.question }, stream: true });
        if ((response.headers.get("content-type") ?? "").includes("application/json")) {
          checkpoint.runId = (await response.json()).data.runId;
        } else {
          const parser = new SseParser((event) => {
            events.push({ type: event.type, elapsedMs: Date.now() - streamStart });
            if (event.type === "start" || event.type === "done") checkpoint.runId = event.data.runId;
          });
          for await (const bytes of response.body) parser.push(bytes);
          parser.push(new Uint8Array(), true);
        }
      } catch (error) { parseError = error.message; }
      checkpoint.streaming = { events, transportError: parseError ?? null };
      await save(options.out, output);
      const confirmed = await runtime.runFor(checkpoint.sessionId, checkpoint.clientRequestId);
      ensure(confirmed, `${testCase.id} 提交后未查到运行，禁止自动重放：${parseError ?? "响应异常"}`);
      checkpoint.runId = confirmed.runId;
    } else checkpoint.runId = existing.runId;
    const result = await collectResult(client, runtime, binding, testCase, checkpoint);
    if (frozen) assertFrozen(frozen, { ...metadata, configSnapshot: result.configSnapshot });
    if (output.metadata.configSnapshot) ensure(canonical(output.metadata.configSnapshot) === canonical(result.configSnapshot),
      "同一运行记录内配置发生漂移，请保持环境不变");
    else output.metadata.configSnapshot = result.configSnapshot;
    checkpoint.result = result; output.results.push(result);
    output.metadata.completedAt = now();
    await save(options.out, output);
    console.log(`${testCase.id} ${result.status}，${result.metrics?.totalMs ?? "未知"} ms，${result.usage?.totalTokens ?? "未知"} tokens`);
    executed += 1;
  }
  output.metadata.completedAt = now();
  validateRunRecord(output, dataset, { requireCheckpoints: true });
  await save(options.out, output);
  return { out: options.out, recorded: output.results.length, expected: cases.length,
    completed: output.results.filter((item) => item.status === "COMPLETED").length, review: "PENDING_HUMAN_REVIEW" };
}

export async function freezeRun(options) {
  ensure(options.run && options.out && options.reason, "freeze 必须提供 --run、--out、--reason");
  ensure(!await optionalJson(options.out), "冻结文件已经存在，禁止覆盖；重新冻结必须使用新的输出路径");
  const run = await readJson(options.run); const dataset = await loadRunnerDataset();
  validateRunRecord(run, dataset, { requireComplete: true });
  ensure(run.metadata.split === "development" && run.metadata.configSnapshot, "必须以已采集真实配置的开发集运行冻结");
  ensure(run.metadata.datasetVersion === dataset.datasetVersion
    && run.metadata.datasetSha256 === dataset.developmentSha256, "开发集运行与当前题集不匹配");
  ensure(run.metadata.gitCommit === await gitCommit(), "开发集运行后 Git 已变化，请先重新核对运行基线");
  const source = await sourceIdentity();
  ensure(run.metadata.sourceTreeHash === source.sourceTreeHash,
    "开发集运行后业务源码已变化，旧 Git SHA 不能代表当前未提交源码");
  ensure(run.metadata.runnerSha256 === source.runnerSha256, "开发集运行后 runnerSha256 已变化，禁止混用评测脚本冻结");
  const runtime = await new RuntimeReader(run.metadata.project).identity();
  ensure(run.metadata.runtimeHash === runtime.runtimeHash && run.metadata.imageId === runtime.imageId
    && run.metadata.runtimeWithoutStrategyHash === runtime.runtimeWithoutStrategyHash,
    "开发集运行后应用镜像或运行环境已变化，禁止冻结旧配置");
  const frozen = { ...run.metadata, freezeVersion: "1.0", split: "test", datasetSha256: dataset.testSha256,
    frozenAt: now(), sourceDevelopmentRun: options.run, sourceDevelopmentSha256: hash(run), reason: options.reason,
    configSnapshotSha256: hash(run.metadata.configSnapshot),
    humanReviewStatus: "PENDING", limitation: "冻结用于一次真实测试采集，不能视为人工质量验收通过" };
  await save(options.out, frozen);
  return { out: options.out, configurationSha256: hash(frozen.configSnapshot), review: frozen.humanReviewStatus };
}

export async function reportRun(options) {
  ensure(options.run && options.out, "report 必须提供 --run、--out");
  const run = await readJson(options.run); const dataset = await loadRunnerDataset();
  const cases = validateRunRecord(run, dataset);
  const resultById = new Map(run.results.map((result) => [result.caseId, result]));
  const worksheet = cases.map((testCase) => ({ caseId: testCase.id, question: testCase.question,
    expectedOutcome: testCase.expectedOutcome, expectedFacts: testCase.expectedFacts, expectedEvidenceIds: testCase.evidenceIds,
    result: resultById.get(testCase.id) ?? { status: "NOT_RUN" } }));
  const report = { status: "DRAFT_PENDING_HUMAN_REVIEW", metadata: run.metadata,
    summary: summarizeResults(cases, run.results), missingCases: cases.filter((item) => !resultById.has(item.id)).map((item) => item.id),
    notRunCases: run.results.filter((item) => item.status === "NOT_RUN").map((item) => item.caseId),
    limitations: ["humanReview 未由脚本填写；汇总不代表人工引用正确率或依据充分性验收通过",
      "retrievedEvidenceIds 基于持久化的实际入模来源，已应用分数与 Token 预算筛选；不是筛选前候选集",
      "refusalCorrect 仅统计 noEvidence=true 的零证据分支与权限拒绝；模型读取无关资料后语义正确拒答仍需人工核对"], worksheet };
  await save(options.out, report);
  return { out: options.out, status: report.status, summary: report.summary };
}

export function compactRun(run, dataset) {
  const cases = validateRunRecord(run, dataset);
  return { schemaVersion: run.schemaVersion, artifactKind: "F32_REAL_HTTP_EVALUATION",
    status: run.results.length === cases.length ? "COMPLETE_CAPTURE_PENDING_HUMAN_REVIEW" : "PARTIAL_CAPTURE_PENDING_HUMAN_REVIEW",
    capturedCases: run.results.length, expectedCases: cases.length, sourceRunSha256: hash(run),
    metadata: run.metadata, results: run.results };
}

export async function exportRun(options) {
  ensure(options.run && options.out && resolve(options.run) !== resolve(options.out),
    "export 必须提供独立的 --run 和 --out，不能覆盖断点原件");
  const compact = compactRun(await readJson(options.run), await loadRunnerDataset());
  await save(options.out, compact);
  return { out: options.out, status: compact.status, capturedCases: compact.capturedCases,
    expectedCases: compact.expectedCases, review: "PENDING_HUMAN_REVIEW" };
}

export function validateComparison(baseline, candidate, dataset) {
  const cases = validateRunRecord(baseline, dataset, { requireComplete: true });
  validateRunRecord(candidate, dataset, { requireComplete: true });
  ensure(baseline.metadata.split === "development" && candidate.metadata.split === "development",
    "策略比较只能使用完整 development 运行记录");
  for (const key of ["datasetVersion", "datasetSha256", "gitCommit", "environment", "bindingHash", "sourceTreeHash",
    "imageId", "runtimeWithoutStrategyHash", "runnerSha256"]) {
    ensure(baseline.metadata[key] === candidate.metadata[key], `策略比较基线不一致：${key}`);
  }
  ensure(baseline.metadata.configSnapshot.retrieval.strategy === "VECTOR"
    && candidate.metadata.configSnapshot.retrieval.strategy === "FUSION", "策略比较必须为 VECTOR 到 FUSION");
  const left = structuredClone(baseline.metadata.configSnapshot);
  const right = structuredClone(candidate.metadata.configSnapshot);
  delete left.retrieval.strategy; delete right.retrieval.strategy;
  ensure(canonical(left) === canonical(right), "策略比较除 retrieval.strategy 外的配置必须一致");
  return cases;
}

export async function compareDraft(options) {
  ensure(options.baseline && options.candidate && options.out, "compare 必须提供 --baseline、--candidate、--out");
  const baseline = await readJson(options.baseline); const candidate = await readJson(options.candidate);
  const cases = validateComparison(baseline, candidate, await loadRunnerDataset());
  const report = { status: "DRAFT_PENDING_HUMAN_REVIEW", baselineMetadata: baseline.metadata,
    candidateMetadata: candidate.metadata, comparison: compareResults(cases, baseline.results, candidate.results),
    limitations: ["引用正确率与依据充分性尚未人工复核；本报告仅比较客观采集结果",
      "refusalCorrect 是零证据或权限自动口径，不是语义拒答评分",
      "特殊契约及权限夹具为 NOT_RUN，不计为场景通过"] };
  await save(options.out, report);
  return { out: options.out, status: report.status, comparison: report.comparison };
}

function parseOptions(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 2) {
    ensure(args[index].startsWith("--") && args[index + 1] && !args[index + 1].startsWith("--"), "参数必须为 --name value");
    options[args[index].slice(2)] = args[index + 1];
  }
  if (options.limit) ensure(/^[1-9]\d*$/.test(options.limit), "limit 必须为正整数");
  return options;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const commands = { prepare, run: runEvaluation, freeze: freezeRun, report: reportRun, export: exportRun, compare: compareDraft };
  const [command, ...args] = process.argv.slice(2);
  Promise.resolve().then(() => {
    ensure(commands[command], "用法：node evaluation/scripts/f32-runner.mjs prepare|run|freeze|report|export|compare --name value");
    return commands[command](parseOptions(args));
  }).then((result) => console.log(JSON.stringify(result, null, 2)))
    .catch((error) => { console.error(error.message); process.exitCode = 1; });
}
