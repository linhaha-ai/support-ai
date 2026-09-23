export type DocumentVersionStatus = 'QUEUED' | 'BUILDING' | 'READY' | 'FAILED' | 'CANCELLED'
export type IngestionJobStatus = 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type IngestionJobStage = 'PARSE' | 'CHUNK' | 'EMBED' | 'PUBLISH'

export interface DocumentApiError {
  kind: 'unauthorized' | 'forbidden' | 'not-found' | 'request' | 'invalid-response'
  message: string
}

export interface DocumentIngestionJob {
  jobId: string
  versionId: string
  status: IngestionJobStatus
  stage: IngestionJobStage
  attempt: number
  maxAttempts: number
  completedChunks: number | null
  totalChunks: number | null
  progressPercent: number | null
  retryable: boolean
  cancellable: boolean
  errorCode: string | null
  errorMessage: string | null
  errorStage: IngestionJobStage | null
  nextRunAt: string | null
  startedAt: string | null
  finishedAt: string | null
  updatedAt: string
}

export interface DocumentVersionSummary {
  versionId: string
  versionNo: number
  status: DocumentVersionStatus
  active: boolean
  pending: boolean
  originalFilename: string
  fileSize: number
  mediaType: string
  chunkCount: number
  task: DocumentIngestionJob | null
  createdAt: string
  updatedAt: string
}

export interface DocumentSummary {
  documentId: string
  name: string
  activeVersion: DocumentVersionSummary | null
  pendingVersion: DocumentVersionSummary | null
  revision: number
  createdAt: string
  updatedAt: string
}

export interface DocumentDetail extends DocumentSummary {
  knowledgeBaseId: string
}

export interface DocumentVersionPage {
  items: DocumentVersionSummary[]
  nextCursor: string | null
}

export interface DocumentContent {
  documentId: string
  versionId: string
  versionNo: number
  active: boolean
  pending: boolean
  originalFilename: string
  mediaType: string
  content: string
}

export interface DocumentChunk {
  chunkId: string
  chunkIndex: number
  section: string | null
  pageNo: number | null
  startOffset: number
  endOffset: number
  charCount: number
  tokenCount: number | null
  content: string | null
}

export interface DocumentChunkPage {
  items: DocumentChunk[]
  nextCursor: string | null
}

interface ApiEnvelope<T> {
  code: string
  data?: T
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function parseEnvelope<T>(value: unknown): ApiEnvelope<T> | null {
  if (!isRecord(value) || typeof value.code !== 'string') return null
  return { code: value.code, data: value.data as T | undefined }
}

function parseNullableString(value: unknown): string | null | undefined {
  return typeof value === 'string' || value === null ? value : undefined
}

function parseNullableNumber(value: unknown): number | null | undefined {
  return value === null || typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function parseVersionStatus(value: unknown): DocumentVersionStatus | null {
  return value === 'QUEUED' || value === 'BUILDING' || value === 'READY' || value === 'FAILED' || value === 'CANCELLED' ? value : null
}

function parseJobStatus(value: unknown): IngestionJobStatus | null {
  return value === 'QUEUED' || value === 'RUNNING' || value === 'RETRY_WAIT'
    || value === 'SUCCEEDED' || value === 'FAILED' || value === 'CANCELLED' ? value : null
}

function parseJobStage(value: unknown): IngestionJobStage | null {
  return value === 'PARSE' || value === 'CHUNK' || value === 'EMBED' || value === 'PUBLISH' ? value : null
}

function parseNullableJobStage(value: unknown): IngestionJobStage | null | undefined {
  if (value === null) return null
  return parseJobStage(value) ?? undefined
}

function parseJob(value: unknown): DocumentIngestionJob | null {
  const status = isRecord(value) ? parseJobStatus(value.status) : null
  const stage = isRecord(value) ? parseJobStage(value.stage) : null
  const completedChunks = isRecord(value) ? parseNullableNumber(value.completedChunks) : undefined
  const totalChunks = isRecord(value) ? parseNullableNumber(value.totalChunks) : undefined
  const progressPercent = isRecord(value) ? parseNullableNumber(value.progressPercent) : undefined
  const errorCode = isRecord(value) ? parseNullableString(value.errorCode) : undefined
  const errorMessage = isRecord(value) ? parseNullableString(value.errorMessage) : undefined
  const errorStage = isRecord(value) ? parseNullableJobStage(value.errorStage) : undefined
  const nextRunAt = isRecord(value) ? parseNullableString(value.nextRunAt) : undefined
  const startedAt = isRecord(value) ? parseNullableString(value.startedAt) : undefined
  const finishedAt = isRecord(value) ? parseNullableString(value.finishedAt) : undefined
  if (!isRecord(value)
    || typeof value.jobId !== 'string'
    || typeof value.versionId !== 'string'
    || !status || !stage
    || typeof value.attempt !== 'number'
    || typeof value.maxAttempts !== 'number'
    || completedChunks === undefined || totalChunks === undefined || progressPercent === undefined
    || typeof value.retryable !== 'boolean' || typeof value.cancellable !== 'boolean'
    || errorCode === undefined || errorMessage === undefined || errorStage === undefined || nextRunAt === undefined
    || startedAt === undefined || finishedAt === undefined || typeof value.updatedAt !== 'string') return null
  return {
    jobId: value.jobId, versionId: value.versionId, status, stage,
    attempt: value.attempt, maxAttempts: value.maxAttempts,
    completedChunks, totalChunks, progressPercent,
    retryable: value.retryable, cancellable: value.cancellable,
    errorCode, errorMessage, errorStage, nextRunAt, startedAt, finishedAt, updatedAt: value.updatedAt,
  }
}

function parseVersion(value: unknown): DocumentVersionSummary | null {
  const status = isRecord(value) ? parseVersionStatus(value.status) : null
  const chunkCount = isRecord(value) && typeof value.chunkCount === 'number' && Number.isFinite(value.chunkCount)
    ? value.chunkCount
    : undefined
  const taskValue = isRecord(value) ? value.task : undefined
  const task = taskValue === null ? null : parseJob(taskValue)
  if (!isRecord(value)
    || typeof value.versionId !== 'string' || typeof value.versionNo !== 'number' || !status
    || typeof value.active !== 'boolean' || typeof value.pending !== 'boolean'
    || typeof value.originalFilename !== 'string' || typeof value.fileSize !== 'number'
    || typeof value.mediaType !== 'string' || chunkCount === undefined || task === null && taskValue !== null
    || typeof value.createdAt !== 'string' || typeof value.updatedAt !== 'string') return null
  return {
    versionId: value.versionId, versionNo: value.versionNo, status,
    active: value.active, pending: value.pending, originalFilename: value.originalFilename,
    fileSize: value.fileSize, mediaType: value.mediaType, chunkCount, task,
    createdAt: value.createdAt, updatedAt: value.updatedAt,
  }
}

function parseSummary(value: unknown): DocumentSummary | null {
  const activeValue = isRecord(value) ? value.activeVersion : undefined
  const pendingValue = isRecord(value) ? value.pendingVersion : undefined
  const activeVersion = activeValue === null ? null : parseVersion(activeValue)
  const pendingVersion = pendingValue === null ? null : parseVersion(pendingValue)
  if (!isRecord(value)
    || typeof value.documentId !== 'string' || typeof value.name !== 'string'
    || activeVersion === null && activeValue !== null || pendingVersion === null && pendingValue !== null
    || typeof value.revision !== 'number' || typeof value.createdAt !== 'string' || typeof value.updatedAt !== 'string') return null
  return { documentId: value.documentId, name: value.name, activeVersion, pendingVersion, revision: value.revision, createdAt: value.createdAt, updatedAt: value.updatedAt }
}

function parseDetail(value: unknown): DocumentDetail | null {
  const summary = parseSummary(value)
  if (!summary || !isRecord(value) || typeof value.knowledgeBaseId !== 'string') return null
  return { ...summary, knowledgeBaseId: value.knowledgeBaseId }
}

function parseVersionPage(value: unknown): DocumentVersionPage | null {
  if (!isRecord(value) || !Array.isArray(value.items) || parseNullableString(value.nextCursor) === undefined) return null
  const items = value.items.map(parseVersion)
  if (!items.every((item): item is DocumentVersionSummary => item !== null)) return null
  return { items, nextCursor: parseNullableString(value.nextCursor) ?? null }
}

function parseContent(value: unknown): DocumentContent | null {
  if (!isRecord(value)
    || typeof value.documentId !== 'string' || typeof value.versionId !== 'string'
    || typeof value.versionNo !== 'number' || typeof value.active !== 'boolean' || typeof value.pending !== 'boolean'
    || typeof value.originalFilename !== 'string' || typeof value.mediaType !== 'string'
    || typeof value.content !== 'string') return null
  return {
    documentId: value.documentId,
    versionId: value.versionId,
    versionNo: value.versionNo,
    active: value.active,
    pending: value.pending,
    originalFilename: value.originalFilename,
    mediaType: value.mediaType,
    content: value.content,
  }
}

function parseChunk(value: unknown): DocumentChunk | null {
  const section = isRecord(value) ? parseNullableString(value.section) : undefined
  const pageNo = isRecord(value) ? parseNullableNumber(value.pageNo) : undefined
  const startOffset = isRecord(value) && typeof value.startOffset === 'number' && Number.isFinite(value.startOffset)
    ? value.startOffset
    : undefined
  const endOffset = isRecord(value) && typeof value.endOffset === 'number' && Number.isFinite(value.endOffset)
    ? value.endOffset
    : undefined
  const charCount = isRecord(value) && typeof value.charCount === 'number' && Number.isFinite(value.charCount)
    ? value.charCount
    : undefined
  const tokenCount = isRecord(value) ? parseNullableNumber(value.tokenCount) : undefined
  const content = isRecord(value) ? parseNullableString(value.content) : undefined
  if (!isRecord(value) || typeof value.chunkId !== 'string' || typeof value.chunkIndex !== 'number'
    || section === undefined || pageNo === undefined || startOffset === undefined || endOffset === undefined
    || charCount === undefined || tokenCount === undefined || content === undefined) return null
  return { chunkId: value.chunkId, chunkIndex: value.chunkIndex, section, pageNo, startOffset, endOffset, charCount, tokenCount, content }
}

function parseChunkPage(value: unknown): DocumentChunkPage | null {
  if (!isRecord(value) || !Array.isArray(value.items) || parseNullableString(value.nextCursor) === undefined) return null
  const items = value.items.map(parseChunk)
  if (!items.every((item): item is DocumentChunk => item !== null)) return null
  return { items, nextCursor: parseNullableString(value.nextCursor) ?? null }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function errorFor(response: Response, control = false): DocumentApiError {
  if (response.status === 401) return { kind: 'unauthorized', message: '登录状态已失效，请重新登录。' }
  if (response.status === 403) return {
    kind: 'forbidden',
    message: control ? '当前账号没有管理此任务的权限。' : '当前账号没有查看此文档的权限。',
  }
  if (response.status === 404) return {
    kind: 'not-found',
    message: control ? '处理任务不存在或已不可访问。' : '文档或版本不存在，可能已被更新。',
  }
  if (control && response.status === 409) {
    return { kind: 'request', message: '任务状态已变化，请刷新后重试。' }
  }
  return {
    kind: 'request',
    message: control ? '任务操作未完成，请稍后重试。' : '文档信息暂时无法读取，请稍后重试。',
  }
}

async function get<T>(path: string, parser: (value: unknown) => T | null, invalidMessage: string): Promise<T> {
  const response = await fetch(path, {
    method: 'GET', headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store',
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.ok && envelope?.code === 'OK' ? parser(envelope.data) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: invalidMessage } satisfies DocumentApiError
}

function withPage(path: string, limit: number, cursor?: string | null): string {
  const params = new URLSearchParams({ limit: String(limit) })
  if (cursor) params.set('cursor', cursor)
  return `${path}?${params.toString()}`
}

interface CsrfData {
  token: string
  headerName: string
}

function parseCsrf(value: unknown): CsrfData | null {
  if (!isRecord(value) || typeof value.token !== 'string' || typeof value.headerName !== 'string') return null
  return { token: value.token, headerName: value.headerName }
}

async function requestCsrf(): Promise<CsrfData> {
  const response = await fetch('/api/auth/csrf', {
    method: 'GET', headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store',
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const csrf = response.ok && envelope?.code === 'OK' ? parseCsrf(envelope.data) : null
  if (csrf) return csrf
  if (!response.ok) throw errorFor(response, true)
  throw { kind: 'invalid-response', message: '安全令牌响应格式无效，请刷新后重试。' } satisfies DocumentApiError
}

async function postIngestionJobAction(jobId: string, action: 'retry' | 'cancel'): Promise<DocumentIngestionJob> {
  const csrf = await requestCsrf()
  const response = await fetch(`/api/ingestion-jobs/${encodeURIComponent(jobId)}/${action}`, {
    method: 'POST',
    headers: { Accept: 'application/json', [csrf.headerName]: csrf.token },
    credentials: 'same-origin',
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const job = response.ok && envelope?.code === 'OK' ? parseJob(envelope.data) : null
  if (job) return job
  if (!response.ok) throw errorFor(response, true)
  throw { kind: 'invalid-response', message: '任务操作响应格式无效，请刷新后重试。' } satisfies DocumentApiError
}

export function fetchDocuments(knowledgeBaseId: string): Promise<DocumentSummary[]> {
  return get(`/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`, (value) => {
    if (!Array.isArray(value)) return null
    const items = value.map(parseSummary)
    return items.every((item): item is DocumentSummary => item !== null) ? items : null
  }, '文档列表响应格式无效，请刷新后重试。')
}

export function fetchDocument(documentId: string): Promise<DocumentDetail> {
  return get(`/api/documents/${encodeURIComponent(documentId)}`, parseDetail, '文档详情响应格式无效，请刷新后重试。')
}

export function fetchDocumentVersions(documentId: string, limit = 20, cursor?: string | null): Promise<DocumentVersionPage> {
  return get(withPage(`/api/documents/${encodeURIComponent(documentId)}/versions`, limit, cursor), parseVersionPage, '文档版本响应格式无效，请刷新后重试。')
}

export function fetchDocumentContent(documentId: string, versionId: string): Promise<DocumentContent> {
  return get(`/api/documents/${encodeURIComponent(documentId)}/versions/${encodeURIComponent(versionId)}/content`, parseContent, '文档原文响应格式无效，请刷新后重试。')
}

export function fetchDocumentChunks(documentId: string, versionId: string, limit = 50, cursor?: string | null): Promise<DocumentChunkPage> {
  return get(withPage(`/api/documents/${encodeURIComponent(documentId)}/versions/${encodeURIComponent(versionId)}/chunks`, limit, cursor), parseChunkPage, '文档分块响应格式无效，请刷新后重试。')
}

export function retryIngestionJob(jobId: string): Promise<DocumentIngestionJob> {
  return postIngestionJobAction(jobId, 'retry')
}

export function cancelIngestionJob(jobId: string): Promise<DocumentIngestionJob> {
  return postIngestionJobAction(jobId, 'cancel')
}
