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
