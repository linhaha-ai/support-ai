export type KnowledgeBaseStatus = 'ACTIVE' | 'DISABLED'
export type KnowledgeBasePermission = 'READ' | 'WRITE'

export interface KnowledgeBaseSummary {
  id: string
  name: string
  description: string | null
  status: KnowledgeBaseStatus
  permission: KnowledgeBasePermission
  revision: number
  updatedAt: string
}

export interface KnowledgeBaseManagement {
  id: string
  name: string
  description: string | null
  ownerId: string
  status: KnowledgeBaseStatus
  revision: number
  createdAt: string
  updatedAt: string
}

export interface KnowledgeBaseDetail extends KnowledgeBaseManagement {
  currentPermission: KnowledgeBasePermission
  documentCount: number
}

export interface AdminUser {
  id: string
  username: string
  displayName: string
  role: string
  enabled: boolean
}

export interface KnowledgeBaseMember {
  userId: string
  username: string
  displayName: string
  role: string
  enabled: boolean
  permission: KnowledgeBasePermission
  grantedBy: string
  createdAt: string
}

export interface UpdateKnowledgeBaseMemberInput {
  permission: KnowledgeBasePermission
}

export interface CreateKnowledgeBaseInput {
  name: string
  description: string
}

export interface UpdateKnowledgeBaseInput extends CreateKnowledgeBaseInput {
  status: KnowledgeBaseStatus
  revision: number
}

export interface DocumentUploadReceipt {
  documentId: string
  versionId: string
  jobId: string
  status: 'QUEUED'
}

export type IngestionJobStatus = 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type IngestionJobStage = 'PARSE' | 'CHUNK' | 'EMBED' | 'PUBLISH'

export interface IngestionJobDetail {
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
  nextRunAt: string | null
  startedAt: string | null
  finishedAt: string | null
  updatedAt: string
}

export interface KnowledgeBaseApiError {
  kind: 'unauthorized' | 'forbidden' | 'conflict' | 'request' | 'invalid-response'
  message: string
}

interface ApiEnvelope<T> {
  code: string
  data?: T
  message?: string
  traceId?: string
}

interface CsrfData {
  token: string
  headerName: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function parseEnvelope<T>(value: unknown): ApiEnvelope<T> | null {
  if (!isRecord(value) || typeof value.code !== 'string') return null
  return {
    code: value.code,
    data: value.data as T | undefined,
    message: typeof value.message === 'string' ? value.message : undefined,
    traceId: typeof value.traceId === 'string' ? value.traceId : undefined,
  }
}

function parseStatus(value: unknown): KnowledgeBaseStatus | null {
  return value === 'ACTIVE' || value === 'DISABLED' ? value : null
}

function parsePermission(value: unknown): KnowledgeBasePermission | null {
  return value === 'READ' || value === 'WRITE' ? value : null
}

function parseSummary(value: unknown): KnowledgeBaseSummary | null {
  const status = isRecord(value) ? parseStatus(value.status) : null
  const permission = isRecord(value) ? parsePermission(value.permission) : null
  if (!isRecord(value)
    || typeof value.id !== 'string'
    || typeof value.name !== 'string'
    || (typeof value.description !== 'string' && value.description !== null)
    || !status
    || !permission
    || typeof value.revision !== 'number'
    || typeof value.updatedAt !== 'string') return null
  return {
    id: value.id,
    name: value.name,
    description: value.description,
    status,
    permission,
    revision: value.revision,
    updatedAt: value.updatedAt,
  }
}

function parseManagement(value: unknown): KnowledgeBaseManagement | null {
  const status = isRecord(value) ? parseStatus(value.status) : null
  if (!isRecord(value)
    || typeof value.id !== 'string'
    || typeof value.name !== 'string'
    || (typeof value.description !== 'string' && value.description !== null)
    || typeof value.ownerId !== 'string'
    || !status
    || typeof value.revision !== 'number'
    || typeof value.createdAt !== 'string'
    || typeof value.updatedAt !== 'string') return null
  return {
    id: value.id,
    name: value.name,
    description: value.description,
    ownerId: value.ownerId,
    status,
    revision: value.revision,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  }
}

function parseDetail(value: unknown): KnowledgeBaseDetail | null {
  const management = parseManagement(value)
  const permission = isRecord(value) ? parsePermission(value.currentPermission) : null
  if (!management || !permission || !isRecord(value) || typeof value.documentCount !== 'number') return null
  return {
    ...management,
    currentPermission: permission,
    documentCount: value.documentCount,
  }
}

function parseAdminUser(value: unknown): AdminUser | null {
  if (!isRecord(value)
    || typeof value.id !== 'string'
    || typeof value.username !== 'string'
    || typeof value.displayName !== 'string'
    || typeof value.role !== 'string'
    || typeof value.enabled !== 'boolean') return null
  return {
    id: value.id,
    username: value.username,
    displayName: value.displayName,
    role: value.role,
    enabled: value.enabled,
  }
}

function parseMember(value: unknown): KnowledgeBaseMember | null {
  const permission = isRecord(value) ? parsePermission(value.permission) : null
  if (!isRecord(value)
    || typeof value.userId !== 'string'
    || typeof value.username !== 'string'
    || typeof value.displayName !== 'string'
    || typeof value.role !== 'string'
    || typeof value.enabled !== 'boolean'
    || !permission
    || typeof value.grantedBy !== 'string'
    || typeof value.createdAt !== 'string') return null
  return {
    userId: value.userId,
    username: value.username,
    displayName: value.displayName,
    role: value.role,
    enabled: value.enabled,
    permission,
    grantedBy: value.grantedBy,
    createdAt: value.createdAt,
  }
}

function parseDocumentUploadReceipt(value: unknown): DocumentUploadReceipt | null {
  if (!isRecord(value)
    || typeof value.documentId !== 'string'
    || typeof value.versionId !== 'string'
    || typeof value.jobId !== 'string'
    || value.status !== 'QUEUED') return null
  return {
    documentId: value.documentId,
    versionId: value.versionId,
    jobId: value.jobId,
    status: value.status,
  }
}

function parseIngestionJobStatus(value: unknown): IngestionJobStatus | null {
  return value === 'QUEUED' || value === 'RUNNING' || value === 'RETRY_WAIT'
    || value === 'SUCCEEDED' || value === 'FAILED' || value === 'CANCELLED'
    ? value
    : null
}

function parseIngestionJobStage(value: unknown): IngestionJobStage | null {
  return value === 'PARSE' || value === 'CHUNK' || value === 'EMBED' || value === 'PUBLISH' ? value : null
}

function parseNullableString(value: unknown): string | null | undefined {
  return typeof value === 'string' || value === null ? value : undefined
}

function parseNullableNumber(value: unknown): number | null | undefined {
  if (value === null) return null
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function parseIngestionJobDetail(value: unknown): IngestionJobDetail | null {
  const status = isRecord(value) ? parseIngestionJobStatus(value.status) : null
  const stage = isRecord(value) ? parseIngestionJobStage(value.stage) : null
  const completedChunks = isRecord(value) ? parseNullableNumber(value.completedChunks) : undefined
  const totalChunks = isRecord(value) ? parseNullableNumber(value.totalChunks) : undefined
  const progressPercent = isRecord(value) ? parseNullableNumber(value.progressPercent) : undefined
  if (!isRecord(value)
    || typeof value.jobId !== 'string'
    || typeof value.versionId !== 'string'
    || !status
    || !stage
    || typeof value.attempt !== 'number'
    || typeof value.maxAttempts !== 'number'
    || completedChunks === undefined
    || totalChunks === undefined
    || progressPercent === undefined
    || typeof value.retryable !== 'boolean'
    || typeof value.cancellable !== 'boolean'
    || parseNullableString(value.errorCode) === undefined
    || parseNullableString(value.errorMessage) === undefined
    || parseNullableString(value.nextRunAt) === undefined
    || parseNullableString(value.startedAt) === undefined
    || parseNullableString(value.finishedAt) === undefined
    || typeof value.updatedAt !== 'string') return null
  return {
    jobId: value.jobId,
    versionId: value.versionId,
    status,
    stage,
    attempt: value.attempt,
    maxAttempts: value.maxAttempts,
    completedChunks,
    totalChunks,
    progressPercent,
    retryable: value.retryable,
    cancellable: value.cancellable,
    errorCode: parseNullableString(value.errorCode) ?? null,
    errorMessage: parseNullableString(value.errorMessage) ?? null,
    nextRunAt: parseNullableString(value.nextRunAt) ?? null,
    startedAt: parseNullableString(value.startedAt) ?? null,
    finishedAt: parseNullableString(value.finishedAt) ?? null,
    updatedAt: value.updatedAt,
  }
}

function parseList<T>(value: unknown, parser: (item: unknown) => T | null): T[] | null {
  if (!Array.isArray(value)) return null
  const items = value.map(parser)
  return items.every((item): item is T => item !== null) ? items : null
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function errorFor(response: Response): KnowledgeBaseApiError {
  if (response.status === 401) return { kind: 'unauthorized', message: '登录状态已失效，请重新登录。' }
  if (response.status === 403) return { kind: 'forbidden', message: '当前账号没有执行此操作的权限。' }
  if (response.status === 409) return { kind: 'conflict', message: '知识库已被其他操作更新，请刷新后再保存。' }
  return { kind: 'request', message: '知识库请求未完成，请稍后重试。' }
}

function uploadErrorFor(response: Response, code?: string): KnowledgeBaseApiError {
  if (response.status === 413 || code === 'DOCUMENT_FILE_TOO_LARGE') {
    return { kind: 'request', message: '文件超过 5 MB 限制，请压缩或拆分后重试。' }
  }
  if (response.status === 415 || code === 'DOCUMENT_FILE_TYPE_UNSUPPORTED') {
    return { kind: 'request', message: '仅支持 UTF-8 编码的 Markdown 或 TXT 文件。' }
  }
  if (code === 'DOCUMENT_FILE_ENCODING_INVALID') {
    return { kind: 'request', message: '文件不是有效的 UTF-8 编码，请转换编码后重试。' }
  }
  if (code === 'DOCUMENT_FILE_REQUIRED' || code === 'DOCUMENT_FILE_EMPTY') {
    return { kind: 'request', message: '请选择包含内容的 Markdown 或 TXT 文件。' }
  }
  if (code === 'DOCUMENT_NAME_INVALID') {
    return { kind: 'request', message: '文档名称不合法，请缩短名称或移除控制字符。' }
  }
  if (code === 'DOCUMENT_STORAGE_UNAVAILABLE' || code === 'DOCUMENT_UPLOAD_FAILED') {
    return { kind: 'request', message: '文档暂时无法受理，请稍后重试。' }
  }
  return errorFor(response)
}

async function requestCsrf(): Promise<CsrfData> {
  const response = await fetch('/api/auth/csrf', {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  const envelope = parseEnvelope<CsrfData>(await readJson(response))
  const data = envelope?.code === 'OK' && isRecord(envelope.data)
    && typeof envelope.data.token === 'string' && typeof envelope.data.headerName === 'string'
    ? { token: envelope.data.token, headerName: envelope.data.headerName }
    : null
  if (!response.ok || !data) throw errorFor(response)
  return data
}

export async function fetchKnowledgeBaseSummaries(): Promise<KnowledgeBaseSummary[]> {
  const response = await fetch('/api/knowledge-bases', {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.ok && envelope?.code === 'OK' ? parseList(envelope.data, parseSummary) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: '知识库响应格式无效，请刷新后重试。' } satisfies KnowledgeBaseApiError
}

export async function fetchKnowledgeBaseManagement(status = ''): Promise<KnowledgeBaseManagement[]> {
  const query = status ? `?status=${encodeURIComponent(status)}` : ''
  const response = await fetch(`/api/admin/knowledge-bases${query}`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.ok && envelope?.code === 'OK' ? parseList(envelope.data, parseManagement) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: '知识库响应格式无效，请刷新后重试。' } satisfies KnowledgeBaseApiError
}

export async function fetchAdminUsers(): Promise<AdminUser[]> {
  const response = await fetch('/api/admin/users', {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.ok && envelope?.code === 'OK' ? parseList(envelope.data, parseAdminUser) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: '成员账号响应格式无效，请刷新后重试。' } satisfies KnowledgeBaseApiError
}

export async function fetchKnowledgeBaseMembers(id: string): Promise<KnowledgeBaseMember[]> {
  const response = await fetch(`/api/knowledge-bases/${encodeURIComponent(id)}/members`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.ok && envelope?.code === 'OK' ? parseList(envelope.data, parseMember) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: '成员列表响应格式无效，请刷新后重试。' } satisfies KnowledgeBaseApiError
}

export async function createKnowledgeBase(input: CreateKnowledgeBaseInput): Promise<KnowledgeBaseDetail> {
  const csrf = await requestCsrf()
  const response = await fetch('/api/knowledge-bases', {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [csrf.headerName]: csrf.token,
    },
    credentials: 'same-origin',
    cache: 'no-store',
    body: JSON.stringify(input),
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.ok && envelope?.code === 'OK' ? parseDetail(envelope.data) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: '创建知识库的响应格式无效，请刷新后重试。' } satisfies KnowledgeBaseApiError
}

export async function uploadKnowledgeDocument(
  knowledgeBaseId: string,
  file: File,
  displayName: string,
): Promise<DocumentUploadReceipt> {
  const csrf = await requestCsrf()
  const body = new FormData()
  body.append('file', file)
  if (displayName) body.append('displayName', displayName)

  const response = await fetch(`/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      [csrf.headerName]: csrf.token,
    },
    credentials: 'same-origin',
    cache: 'no-store',
    body,
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.status === 202 && envelope?.code === 'OK'
    ? parseDocumentUploadReceipt(envelope.data)
    : null
  if (data) return data
  if (!response.ok) throw uploadErrorFor(response, envelope?.code)
  throw { kind: 'invalid-response', message: '上传文档的响应格式无效，请稍后重试。' } satisfies KnowledgeBaseApiError
}

export async function fetchIngestionJob(jobId: string): Promise<IngestionJobDetail> {
  const response = await fetch(`/api/ingestion-jobs/${encodeURIComponent(jobId)}`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.ok && envelope?.code === 'OK' ? parseIngestionJobDetail(envelope.data) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: '文档处理任务响应格式无效，请稍后重试。' } satisfies KnowledgeBaseApiError
}

export async function updateKnowledgeBase(id: string, input: UpdateKnowledgeBaseInput): Promise<KnowledgeBaseManagement> {
  const csrf = await requestCsrf()
  const response = await fetch(`/api/knowledge-bases/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [csrf.headerName]: csrf.token,
    },
    credentials: 'same-origin',
    cache: 'no-store',
    body: JSON.stringify(input),
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.ok && envelope?.code === 'OK' ? parseManagement(envelope.data) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: '更新知识库的响应格式无效，请刷新后重试。' } satisfies KnowledgeBaseApiError
}

export async function updateKnowledgeBaseMember(
  knowledgeBaseId: string,
  userId: string,
  input: UpdateKnowledgeBaseMemberInput,
): Promise<KnowledgeBaseMember> {
  const csrf = await requestCsrf()
  const response = await fetch(
    `/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/members/${encodeURIComponent(userId)}`,
    {
      method: 'PUT',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        [csrf.headerName]: csrf.token,
      },
      credentials: 'same-origin',
      cache: 'no-store',
      body: JSON.stringify(input),
    },
  )
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.ok && envelope?.code === 'OK' ? parseMember(envelope.data) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: '成员权限响应格式无效，请刷新后重试。' } satisfies KnowledgeBaseApiError
}

export async function removeKnowledgeBaseMember(knowledgeBaseId: string, userId: string): Promise<void> {
  const csrf = await requestCsrf()
  const response = await fetch(
    `/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/members/${encodeURIComponent(userId)}`,
    {
      method: 'DELETE',
      headers: {
        Accept: 'application/json',
        [csrf.headerName]: csrf.token,
      },
      credentials: 'same-origin',
      cache: 'no-store',
    },
  )
  if (response.status === 204) return
  throw errorFor(response)
}
