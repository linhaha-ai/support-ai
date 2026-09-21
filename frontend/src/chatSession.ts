export type ChatSessionMode = 'RAG'

export interface ChatSessionSummary {
  sessionId: string
  title: string
  mode: ChatSessionMode
  knowledgeBaseIds: string[]
  createdAt: string
  updatedAt: string
}

export type ChatRunStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface ChatSourceReference {
  number: number
  type: 'DOCUMENT'
  documentId: string
  versionId: string
  chunkId: string
  title: string
  section: string | null
  isCurrent: boolean
  previewPath: string
}

export interface DocumentSource {
  knowledgeBaseId: string
  documentId: string
  documentName: string
  versionId: string
  versionNo: number
  chunkId: string
  chunkIndex: number
  section: string | null
  pageNo: number | null
  startOffset: number
  endOffset: number
  isCurrent: boolean
  content: string
}

export type ChatStreamEvent =
  | { event: 'start', data: { runId: string, assistantMessageId: string } }
  | { event: 'delta', data: { text: string } }
  | { event: 'sources', data: { sources: ChatSourceReference[] } }
  | { event: 'done', data: { runId: string, status: 'COMPLETED', usage: unknown, metrics: unknown } }
  | { event: 'error', data: { code: string, message: string, status: 'FAILED' | 'CANCELLED', traceId: string } }

export interface ChatRunReuse {
  runId: string
  status: ChatRunStatus
  reused: true
}

export type SubmitChatMessageResult =
  | { kind: 'stream', terminal: Extract<ChatStreamEvent, { event: 'done' | 'error' }> }
  | { kind: 'reused', run: ChatRunReuse }

export interface ChatSessionApiError {
  kind: 'unauthorized' | 'forbidden' | 'not-found' | 'conflict' | 'rate-limit' | 'request' | 'invalid-response'
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

interface ParsedSseFrame {
  event: string
  data: string
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

function parseRunStatus(value: unknown): ChatRunStatus | null {
  return value === 'QUEUED' || value === 'RUNNING' || value === 'COMPLETED'
    || value === 'FAILED' || value === 'CANCELLED' ? value : null
}

function parseReuse(value: unknown): ChatRunReuse | null {
  if (!isRecord(value) || typeof value.runId !== 'string' || value.reused !== true) return null
  const status = parseRunStatus(value.status)
  return status ? { runId: value.runId, status, reused: true } : null
}

function expectedPreviewPath(chunkId: string): string {
  return `/api/chunks/${encodeURIComponent(chunkId)}/source`
}

function parseSourceReference(value: unknown): ChatSourceReference | null {
  if (!isRecord(value)
    || !Number.isInteger(value.number) || (value.number as number) < 1
    || value.type !== 'DOCUMENT'
    || typeof value.documentId !== 'string'
    || typeof value.versionId !== 'string'
    || typeof value.chunkId !== 'string'
    || typeof value.title !== 'string' || value.title.trim() === ''
    || !(typeof value.section === 'string' || value.section === null)
    || typeof value.isCurrent !== 'boolean'
    || value.previewPath !== expectedPreviewPath(value.chunkId)) return null
  return {
    number: value.number as number,
    type: 'DOCUMENT',
    documentId: value.documentId,
    versionId: value.versionId,
    chunkId: value.chunkId,
    title: value.title,
    section: value.section,
    isCurrent: value.isCurrent,
    previewPath: value.previewPath,
  }
}

function parseDocumentSource(value: unknown): DocumentSource | null {
  if (!isRecord(value)
    || typeof value.knowledgeBaseId !== 'string'
    || typeof value.documentId !== 'string'
    || typeof value.documentName !== 'string'
    || typeof value.versionId !== 'string'
    || !Number.isInteger(value.versionNo)
    || typeof value.chunkId !== 'string'
    || !Number.isInteger(value.chunkIndex)
    || !(typeof value.section === 'string' || value.section === null)
    || !(Number.isInteger(value.pageNo) || value.pageNo === null)
    || !Number.isInteger(value.startOffset)
    || !Number.isInteger(value.endOffset)
    || typeof value.isCurrent !== 'boolean'
    || typeof value.content !== 'string') return null
  return value as unknown as DocumentSource
}

function invalidStream(message = '回答流格式无效，请稍后重试。'): ChatSessionApiError {
  return { kind: 'invalid-response', message }
}

function parseStreamEvent(eventName: string, json: unknown): ChatStreamEvent | null {
  if (!['start', 'delta', 'sources', 'done', 'error'].includes(eventName)) return null
  if (!isRecord(json)) throw invalidStream()
  if (eventName === 'start' && typeof json.runId === 'string' && typeof json.assistantMessageId === 'string') {
    return { event: 'start', data: { runId: json.runId, assistantMessageId: json.assistantMessageId } }
  }
  if (eventName === 'delta' && typeof json.text === 'string' && json.text.length > 0) {
    return { event: 'delta', data: { text: json.text } }
  }
  if (eventName === 'sources' && Array.isArray(json.sources)) {
    const sources = json.sources.map(parseSourceReference)
    if (sources.every((source): source is ChatSourceReference => source !== null)) {
      return { event: 'sources', data: { sources } }
    }
  }
  if (eventName === 'done' && typeof json.runId === 'string' && json.status === 'COMPLETED') {
    return { event: 'done', data: { runId: json.runId, status: 'COMPLETED', usage: json.usage, metrics: json.metrics } }
  }
  if (eventName === 'error'
    && typeof json.code === 'string'
    && typeof json.message === 'string'
    && (json.status === 'FAILED' || json.status === 'CANCELLED')
    && typeof json.traceId === 'string') {
    return {
      event: 'error',
      data: { code: json.code, message: json.message, status: json.status, traceId: json.traceId },
    }
  }
  throw invalidStream()
}

function parseSseFrame(frame: string): ParsedSseFrame | null {
  let event = ''
  const data: string[] = []
  for (const line of frame.replaceAll('\r\n', '\n').split('\n')) {
    if (line === '' || line.startsWith(':')) continue
    if (line.startsWith('event:')) event = line.slice(6).trimStart()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (!event && data.length === 0) return null
  if (!event || data.length === 0) throw invalidStream()
  return { event, data: data.join('\n') }
}

function nextFrame(buffer: string): { frame: string, rest: string } | null {
  const boundary = /\r?\n\r?\n/.exec(buffer)
  if (!boundary || boundary.index === undefined) return null
  return {
    frame: buffer.slice(0, boundary.index),
    rest: buffer.slice(boundary.index + boundary[0].length),
  }
}

function assertEventOrder(event: ChatStreamEvent, state: { started: boolean, sourced: boolean, terminal: boolean }) {
  if (state.terminal || (event.event !== 'start' && !state.started)) throw invalidStream()
  if (event.event === 'start') {
    if (state.started) throw invalidStream()
    state.started = true
  } else if (event.event === 'delta') {
    if (state.sourced) throw invalidStream()
  } else if (event.event === 'sources') {
    if (state.sourced) throw invalidStream()
    state.sourced = true
  } else {
    if (event.event === 'done' && !state.sourced) throw invalidStream()
    state.terminal = true
  }
}

export async function consumeChatEventStream(
  response: Response,
  onEvent: (event: ChatStreamEvent) => void,
): Promise<Extract<ChatStreamEvent, { event: 'done' | 'error' }>> {
  if (!response.body) throw invalidStream('回答流不可读取，请稍后重试。')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const state = { started: false, sourced: false, terminal: false }
  let terminal: Extract<ChatStreamEvent, { event: 'done' | 'error' }> | null = null
  let buffer = ''

  const processFrames = () => {
    let next = nextFrame(buffer)
    while (next) {
      buffer = next.rest
      const parsed = parseSseFrame(next.frame)
      if (parsed && ['start', 'delta', 'sources', 'done', 'error'].includes(parsed.event)) {
        let json: unknown
        try {
          json = JSON.parse(parsed.data)
        } catch {
          throw invalidStream()
        }
        const event = parseStreamEvent(parsed.event, json)
        if (event) {
          assertEventOrder(event, state)
          onEvent(event)
          if (event.event === 'done' || event.event === 'error') terminal = event
        }
      }
      next = nextFrame(buffer)
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    processFrames()
  }
  buffer += decoder.decode()
  if (buffer.trim()) {
    buffer += '\n\n'
    processFrames()
  }
  if (buffer.trim() || !terminal) throw invalidStream('回答流意外中断，请稍后重试。')
  return terminal
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function errorFor(response: Response, action: 'create' | 'message' | 'source'): ChatSessionApiError {
  if (response.status === 401) return { kind: 'unauthorized', message: '登录状态已失效，请重新登录。' }
  if (response.status === 403) return { kind: 'forbidden', message: '当前账号没有执行此操作的权限。' }
  if (response.status === 404) return { kind: 'not-found', message: action === 'source' ? '来源已不可访问。' : '会话或知识范围不可用。' }
  if (response.status === 409) return { kind: 'conflict', message: '当前会话状态已变化，请稍后重试。' }
  if (response.status === 429) return { kind: 'rate-limit', message: '当前请求较多，请稍后再试。' }
  const label = action === 'create' ? '会话' : action === 'message' ? '回答' : '来源'
  return { kind: 'request', message: `${label}请求未完成，请稍后重试。` }
}

async function requestCsrf(action: 'create' | 'message', signal?: AbortSignal): Promise<CsrfData> {
  const response = await fetch('/api/auth/csrf', {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
    signal,
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const data = envelope?.code === 'OK' && isRecord(envelope.data)
    && typeof envelope.data.token === 'string' && typeof envelope.data.headerName === 'string'
    ? { token: envelope.data.token, headerName: envelope.data.headerName }
    : null
  if (response.ok && data) return data
  throw errorFor(response, action)
}

export async function createChatSession(knowledgeBaseIds: string[]): Promise<ChatSessionSummary> {
  const csrf = await requestCsrf('create')
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
  if (!response.ok) throw errorFor(response, 'create')
  throw { kind: 'invalid-response', message: '会话创建响应格式无效，请稍后重试。' } satisfies ChatSessionApiError
}

export async function submitChatMessage(
  sessionId: string,
  clientRequestId: string,
  content: string,
  onEvent: (event: ChatStreamEvent) => void,
  signal?: AbortSignal,
): Promise<SubmitChatMessageResult> {
  const csrf = await requestCsrf('message', signal)
  const response = await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream, application/json',
      'Content-Type': 'application/json',
      [csrf.headerName]: csrf.token,
    },
    credentials: 'same-origin',
    cache: 'no-store',
    body: JSON.stringify({ clientRequestId, content }),
    signal,
  })
  if (!response.ok) {
    await readJson(response)
    throw errorFor(response, 'message')
  }

  const contentType = response.headers.get('Content-Type')?.split(';', 1)[0]?.trim().toLowerCase()
  if (contentType === 'application/json') {
    const envelope = parseEnvelope<unknown>(await readJson(response))
    const run = envelope?.code === 'OK' ? parseReuse(envelope.data) : null
    if (!run) throw invalidStream('重复请求响应格式无效，请稍后重试。')
    return { kind: 'reused', run }
  }
  if (contentType !== 'text/event-stream') throw invalidStream()
  return { kind: 'stream', terminal: await consumeChatEventStream(response, onEvent) }
}

export async function fetchDocumentSource(chunkId: string, signal?: AbortSignal): Promise<DocumentSource> {
  const response = await fetch(expectedPreviewPath(chunkId), {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
    signal,
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const source = response.ok && envelope?.code === 'OK' ? parseDocumentSource(envelope.data) : null
  if (source) return source
  if (!response.ok) throw errorFor(response, 'source')
  throw { kind: 'invalid-response', message: '来源响应格式无效，请稍后重试。' } satisfies ChatSessionApiError
}
