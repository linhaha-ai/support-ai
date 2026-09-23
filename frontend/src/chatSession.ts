export type ChatSessionMode = 'RAG'

export interface ChatSessionSummary {
  sessionId: string
  title: string
  mode: ChatSessionMode
  knowledgeBaseIds: string[]
  contentReadable: boolean
  createdAt: string
  updatedAt: string
}

export interface ChatSessionPage {
  items: ChatSessionSummary[]
  nextCursor: string | null
}

export interface ChatHistoryMessage {
  messageId: string
  runId: string | null
  sequence: number
  role: 'USER' | 'ASSISTANT'
  content: string | null
  status: ChatAssistantStatus
  sources: ChatSourceReference[]
  createdAt: string
  updatedAt: string
}

export interface ChatMessagePage {
  sessionId: string
  contentReadable: boolean
  items: ChatHistoryMessage[]
  nextCursor: string | null
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
  available: boolean
  isCurrent: boolean
  previewPath: string | null
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

export type ChatAssistantStatus = 'STREAMING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface ChatRunStatusResponse {
  runId: string
  sessionId: string
  userMessageId: string
  assistantMessageId: string
  status: ChatRunStatus
  assistantStatus: ChatAssistantStatus
  answer: string | null
  contentReadable: boolean
  sources: ChatSourceReference[]
  usage: Record<string, unknown> | null
  metrics: Record<string, unknown> | null
  errorCode: string | null
  errorMessage: string | null
  cancellable: boolean
  cancelRequestedAt: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface ChatRunCancelResponse {
  runId: string
  status: ChatRunStatus
  newlyCancelled: boolean
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

function parseSession(value: unknown, allowMissingContentReadable = false): ChatSessionSummary | null {
  if (!isRecord(value)
    || typeof value.sessionId !== 'string'
    || typeof value.title !== 'string'
    || value.mode !== 'RAG'
    || !Array.isArray(value.knowledgeBaseIds)
    || value.knowledgeBaseIds.length === 0
    || !value.knowledgeBaseIds.every((item) => typeof item === 'string')
    || (!allowMissingContentReadable && typeof value.contentReadable !== 'boolean')
    || typeof value.createdAt !== 'string'
    || typeof value.updatedAt !== 'string') return null
  return {
    sessionId: value.sessionId,
    title: value.title,
    mode: value.mode,
    knowledgeBaseIds: value.knowledgeBaseIds,
    contentReadable: typeof value.contentReadable === 'boolean' ? value.contentReadable : true,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  }
}

function parseCursor(value: unknown): string | null | undefined {
  return typeof value === 'string' || value === null ? value : undefined
}

function parseSessionPage(value: unknown): ChatSessionPage | null {
  if (!isRecord(value) || !Array.isArray(value.items)) return null
  const nextCursor = parseCursor(value.nextCursor)
  const items = value.items.map((item) => parseSession(item))
  if (nextCursor === undefined || !items.every((item): item is ChatSessionSummary => item !== null)) return null
  return { items, nextCursor }
}

function parseRunStatus(value: unknown): ChatRunStatus | null {
  return value === 'QUEUED' || value === 'RUNNING' || value === 'COMPLETED'
    || value === 'FAILED' || value === 'CANCELLED' ? value : null
}

function parseAssistantStatus(value: unknown): ChatAssistantStatus | null {
  return value === 'STREAMING' || value === 'COMPLETED' || value === 'FAILED' || value === 'CANCELLED'
    ? value : null
}

function parseOptionalTimestamp(value: unknown): string | null {
  return typeof value === 'string' || value === null ? value : null
}

function parseOptionalRecord(value: unknown): Record<string, unknown> | null {
  return isRecord(value) || value === null ? value : null
}

function parseReuse(value: unknown): ChatRunReuse | null {
  if (!isRecord(value) || typeof value.runId !== 'string' || value.reused !== true) return null
  const status = parseRunStatus(value.status)
  return status ? { runId: value.runId, status, reused: true } : null
}

function parseChatRunStatus(value: unknown): ChatRunStatusResponse | null {
  if (!isRecord(value)
    || typeof value.runId !== 'string'
    || typeof value.sessionId !== 'string'
    || typeof value.userMessageId !== 'string'
    || typeof value.assistantMessageId !== 'string'
    || !(typeof value.answer === 'string' || value.answer === null)
    || typeof value.contentReadable !== 'boolean'
    || !Array.isArray(value.sources)
    || !(typeof value.errorCode === 'string' || value.errorCode === null)
    || !(typeof value.errorMessage === 'string' || value.errorMessage === null)
    || typeof value.cancellable !== 'boolean') return null
  const status = parseRunStatus(value.status)
  const assistantStatus = parseAssistantStatus(value.assistantStatus)
  const sources = value.sources.map(parseSourceReference)
  const usage = parseOptionalRecord(value.usage)
  const metrics = parseOptionalRecord(value.metrics)
  const cancelRequestedAt = parseOptionalTimestamp(value.cancelRequestedAt)
  const startedAt = parseOptionalTimestamp(value.startedAt)
  const finishedAt = parseOptionalTimestamp(value.finishedAt)
  if (!status || !assistantStatus
    || !sources.every((source): source is ChatSourceReference => source !== null)
    || usage === null && value.usage !== null
    || metrics === null && value.metrics !== null
    || cancelRequestedAt === null && value.cancelRequestedAt !== null
    || startedAt === null && value.startedAt !== null
    || finishedAt === null && value.finishedAt !== null) return null
  return {
    runId: value.runId,
    sessionId: value.sessionId,
    userMessageId: value.userMessageId,
    assistantMessageId: value.assistantMessageId,
    status,
    assistantStatus,
    answer: value.answer,
    contentReadable: value.contentReadable,
    sources,
    usage,
    metrics,
    errorCode: value.errorCode,
    errorMessage: value.errorMessage,
    cancellable: value.cancellable,
    cancelRequestedAt,
    startedAt,
    finishedAt,
  }
}

function parseChatRunCancel(value: unknown): ChatRunCancelResponse | null {
  if (!isRecord(value) || typeof value.runId !== 'string' || typeof value.newlyCancelled !== 'boolean') return null
  const status = parseRunStatus(value.status)
  return status ? { runId: value.runId, status, newlyCancelled: value.newlyCancelled } : null
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
    || typeof value.available !== 'boolean'
    || typeof value.isCurrent !== 'boolean'
    || !(typeof value.previewPath === 'string' || value.previewPath === null)
    || value.available && value.previewPath !== expectedPreviewPath(value.chunkId)
    || !value.available && (value.previewPath !== null || value.isCurrent)) return null
  return {
    number: value.number as number,
    type: 'DOCUMENT',
    documentId: value.documentId,
    versionId: value.versionId,
    chunkId: value.chunkId,
    title: value.title,
    section: value.section,
    available: value.available,
    isCurrent: value.isCurrent,
    previewPath: value.previewPath,
  }
}

function parseHistoryMessage(value: unknown): ChatHistoryMessage | null {
  if (!isRecord(value)
    || typeof value.messageId !== 'string'
    || !(typeof value.runId === 'string' || value.runId === null)
    || !Number.isInteger(value.sequence) || (value.sequence as number) < 1
    || (value.role !== 'USER' && value.role !== 'ASSISTANT')
    || !(typeof value.content === 'string' || value.content === null)
    || !Array.isArray(value.sources)
    || typeof value.createdAt !== 'string'
    || typeof value.updatedAt !== 'string') return null
  const status = parseAssistantStatus(value.status)
  const sources = value.sources.map(parseSourceReference)
  if (!status || !sources.every((source): source is ChatSourceReference => source !== null)) return null
  return {
    messageId: value.messageId,
    runId: value.runId,
    sequence: value.sequence as number,
    role: value.role,
    content: value.content,
    status,
    sources,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  }
}

function parseMessagePage(value: unknown): ChatMessagePage | null {
  if (!isRecord(value)
    || typeof value.sessionId !== 'string'
    || typeof value.contentReadable !== 'boolean'
    || !Array.isArray(value.items)) return null
  const nextCursor = parseCursor(value.nextCursor)
  const items = value.items.map(parseHistoryMessage)
  if (nextCursor === undefined || !items.every((item): item is ChatHistoryMessage => item !== null)) return null
  return { sessionId: value.sessionId, contentReadable: value.contentReadable, items, nextCursor }
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

function errorFor(response: Response, action: 'create' | 'message' | 'source' | 'run' | 'session' | 'delete-session'): ChatSessionApiError {
  if (response.status === 401) return { kind: 'unauthorized', message: '登录状态已失效，请重新登录。' }
  if (response.status === 403) return { kind: 'forbidden', message: '当前账号没有执行此操作的权限。' }
  if (response.status === 404) return { kind: 'not-found', message: action === 'source' ? '来源已不可访问。' : action === 'run' ? '问答运行不存在或已不可访问。' : '会话或知识范围不可用。' }
  if (response.status === 409) return {
    kind: 'conflict',
    message: action === 'delete-session' ? '会话正在生成回答，请先停止生成再删除。' : '当前会话状态已变化，请稍后重试。',
  }
  if (response.status === 429) return { kind: 'rate-limit', message: '当前请求较多，请稍后再试。' }
  const label = action === 'create' || action === 'session' || action === 'delete-session'
    ? '会话' : action === 'message' ? '回答' : action === 'source' ? '来源' : '运行状态'
  return { kind: 'request', message: `${label}请求未完成，请稍后重试。` }
}

async function requestCsrf(action: 'create' | 'message' | 'run' | 'session', signal?: AbortSignal): Promise<CsrfData> {
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
  const data = response.status === 201 && envelope?.code === 'OK' ? parseSession(envelope.data, true) : null
  if (data) return data
  if (!response.ok) throw errorFor(response, 'create')
  throw { kind: 'invalid-response', message: '会话创建响应格式无效，请稍后重试。' } satisfies ChatSessionApiError
}

function queryPath(path: string, cursor?: string | null, limit = 20): string {
  const query = new URLSearchParams({ limit: String(limit) })
  if (cursor) query.set('cursor', cursor)
  return `${path}?${query.toString()}`
}

export async function fetchChatSessions(cursor?: string | null, signal?: AbortSignal): Promise<ChatSessionPage> {
  const response = await fetch(queryPath('/api/chat/sessions', cursor), {
    method: 'GET', headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store', signal,
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const page = response.ok && envelope?.code === 'OK' ? parseSessionPage(envelope.data) : null
  if (page) return page
  if (!response.ok) throw errorFor(response, 'session')
  throw { kind: 'invalid-response', message: '会话列表响应格式无效，请稍后重试。' } satisfies ChatSessionApiError
}

export async function fetchChatMessages(
  sessionId: string,
  cursor?: string | null,
  signal?: AbortSignal,
): Promise<ChatMessagePage> {
  const response = await fetch(queryPath(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`, cursor), {
    method: 'GET', headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store', signal,
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const page = response.ok && envelope?.code === 'OK' ? parseMessagePage(envelope.data) : null
  if (page && page.sessionId === sessionId) return page
  if (!response.ok) throw errorFor(response, 'session')
  throw { kind: 'invalid-response', message: '历史消息响应格式无效，请稍后重试。' } satisfies ChatSessionApiError
}

export async function renameChatSession(sessionId: string, title: string, signal?: AbortSignal): Promise<ChatSessionSummary> {
  const csrf = await requestCsrf('session', signal)
  const response = await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'PATCH',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    credentials: 'same-origin', cache: 'no-store', body: JSON.stringify({ title }), signal,
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const session = response.ok && envelope?.code === 'OK' ? parseSession(envelope.data) : null
  if (session) return session
  if (!response.ok) throw errorFor(response, 'session')
  throw { kind: 'invalid-response', message: '会话重命名响应格式无效，请稍后重试。' } satisfies ChatSessionApiError
}

export async function deleteChatSession(sessionId: string, signal?: AbortSignal): Promise<void> {
  const csrf = await requestCsrf('session', signal)
  const response = await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE', headers: { Accept: 'application/json', [csrf.headerName]: csrf.token },
    credentials: 'same-origin', cache: 'no-store', signal,
  })
  if (response.status === 204) return
  await readJson(response)
  throw errorFor(response, 'delete-session')
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

export async function fetchChatRun(runId: string, signal?: AbortSignal): Promise<ChatRunStatusResponse> {
  const response = await fetch(`/api/chat/runs/${encodeURIComponent(runId)}`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
    signal,
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const run = response.ok && envelope?.code === 'OK' ? parseChatRunStatus(envelope.data) : null
  if (run) return run
  if (!response.ok) throw errorFor(response, 'run')
  throw { kind: 'invalid-response', message: '运行状态响应格式无效，请稍后重试。' } satisfies ChatSessionApiError
}

export async function cancelChatRun(runId: string, signal?: AbortSignal): Promise<ChatRunCancelResponse> {
  const csrf = await requestCsrf('run', signal)
  const response = await fetch(`/api/chat/runs/${encodeURIComponent(runId)}/cancel`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      [csrf.headerName]: csrf.token,
    },
    credentials: 'same-origin',
    cache: 'no-store',
    signal,
  })
  const envelope = parseEnvelope<unknown>(await readJson(response))
  const result = (response.status === 200 || response.status === 202) && envelope?.code === 'OK'
    ? parseChatRunCancel(envelope.data)
    : null
  if (result) return result
  if (!response.ok) throw errorFor(response, 'run')
  throw { kind: 'invalid-response', message: '停止回答响应格式无效，请稍后重试。' } satisfies ChatSessionApiError
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
