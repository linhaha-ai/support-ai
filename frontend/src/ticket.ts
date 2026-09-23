export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'

export interface TicketDetail {
  ticketNo: string
  title: string | null
  description: string | null
  status: TicketStatus
  ownerName: string | null
  knowledgeBaseId: string
  knowledgeBaseName: string
  updatedAt: string
  queriedAt: string
}

export interface TicketApiError {
  kind: 'unauthorized' | 'not-found' | 'invalid-request' | 'invalid-response' | 'request'
  message: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNullableString(value: unknown): value is string | null {
  return typeof value === 'string' || value === null
}

function isDateTime(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== '' && !Number.isNaN(Date.parse(value))
}

function parseTicket(value: unknown): TicketDetail | null {
  if (!isRecord(value)
    || typeof value.ticketNo !== 'string' || value.ticketNo.trim() === ''
    || !isNullableString(value.title)
    || !isNullableString(value.description)
    || !['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'].includes(String(value.status))
    || !isNullableString(value.ownerName)
    || typeof value.knowledgeBaseId !== 'string' || value.knowledgeBaseId.trim() === ''
    || typeof value.knowledgeBaseName !== 'string' || value.knowledgeBaseName.trim() === ''
    || !isDateTime(value.updatedAt)
    || !isDateTime(value.queriedAt)) return null
  return value as unknown as TicketDetail
}

function parseEnvelope(value: unknown): { code: string, data: unknown } | null {
  if (!isRecord(value) || typeof value.code !== 'string' || !('data' in value)) return null
  return { code: value.code, data: value.data }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function requestError(response: Response): TicketApiError {
  if (response.status === 401) return { kind: 'unauthorized', message: '登录状态已失效，请重新登录。' }
  if (response.status === 404) {
    return { kind: 'not-found', message: '未找到该工单，或当前账号无权查看。' }
  }
  if (response.status === 400) return { kind: 'invalid-request', message: '工单编号格式不正确。' }
  return { kind: 'request', message: '工单查询未完成，请稍后重试。' }
}

export async function fetchTicket(ticketNo: string, signal?: AbortSignal): Promise<TicketDetail> {
  const response = await fetch(`/api/tickets/${encodeURIComponent(ticketNo)}`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
    signal,
  })
  const envelope = parseEnvelope(await readJson(response))
  const ticket = response.ok && envelope?.code === 'OK' ? parseTicket(envelope.data) : null
  if (ticket) return ticket
  if (!response.ok) throw requestError(response)
  throw { kind: 'invalid-response', message: '工单查询响应格式无效，请稍后重试。' } satisfies TicketApiError
}
