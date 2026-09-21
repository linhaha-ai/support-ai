export type ChatSessionMode = 'RAG'

export interface ChatSessionSummary {
  sessionId: string
  title: string
  mode: ChatSessionMode
  knowledgeBaseIds: string[]
  createdAt: string
  updatedAt: string
}

export interface ChatSessionApiError {
  kind: 'unauthorized' | 'forbidden' | 'not-found' | 'conflict' | 'request' | 'invalid-response'
  message: string
}

interface ApiEnvelope<T> {
  code: string
  data?: T
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
  return { code: value.code, data: value.data as T | undefined }
}

function parseSession(value: unknown): ChatSessionSummary | null {
  if (!isRecord(value)
    || typeof value.sessionId !== 'string'
    || typeof value.title !== 'string'
    || value.mode !== 'RAG'
    || !Array.isArray(value.knowledgeBaseIds)
    || value.knowledgeBaseIds.length === 0
    || !value.knowledgeBaseIds.every((item) => typeof item === 'string')
    || typeof value.createdAt !== 'string'
    || typeof value.updatedAt !== 'string') return null
  return {
    sessionId: value.sessionId,
    title: value.title,
    mode: value.mode,
    knowledgeBaseIds: value.knowledgeBaseIds,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function errorFor(response: Response): ChatSessionApiError {
  if (response.status === 401) return { kind: 'unauthorized', message: '登录状态已失效，请重新登录。' }
  if (response.status === 403) return { kind: 'forbidden', message: '当前账号没有创建会话的权限。' }
  if (response.status === 404) return { kind: 'not-found', message: '所选知识库不可用或无访问权限。' }
  if (response.status === 409) return { kind: 'conflict', message: '会话创建状态已变化，请重新确认知识库范围。' }
  return { kind: 'request', message: '会话暂时无法创建，请稍后重试。' }
}

async function requestCsrf(): Promise<CsrfData> {
  const response = await fetch('/api/auth/csrf', {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = envelope?.code === 'OK' && isRecord(envelope.data)
    && typeof envelope.data.token === 'string' && typeof envelope.data.headerName === 'string'
    ? { token: envelope.data.token, headerName: envelope.data.headerName }
    : null
  if (response.ok && data) return data
  throw errorFor(response)
}

export async function createChatSession(knowledgeBaseIds: string[]): Promise<ChatSessionSummary> {
  const csrf = await requestCsrf()
  const response = await fetch('/api/chat/sessions', {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [csrf.headerName]: csrf.token,
    },
    credentials: 'same-origin',
    cache: 'no-store',
    body: JSON.stringify({ kbIds: knowledgeBaseIds, mode: 'RAG' }),
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = response.status === 201 && envelope?.code === 'OK' ? parseSession(envelope.data) : null
  if (data) return data
  if (!response.ok) throw errorFor(response)
  throw { kind: 'invalid-response', message: '会话创建响应格式无效，请稍后重试。' } satisfies ChatSessionApiError
}
