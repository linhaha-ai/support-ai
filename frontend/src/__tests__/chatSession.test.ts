import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  cancelChatRun,
  createChatSession,
  fetchChatRun,
  fetchDocumentSource,
  submitChatMessage,
  type ChatStreamEvent,
} from '../chatSession'

const fetchMock = vi.fn<typeof fetch>()
const knowledgeBaseId = '4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'
const sessionId = '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a'
const runId = '8f5cf7b1-1a5c-4e60-a22a-6e7736e19a2b'
const assistantMessageId = '9f5cf7b1-1a5c-4e60-a22a-6e7736e19a2c'
const chunkId = 'af5cf7b1-1a5c-4e60-a22a-6e7736e19a2d'

function runStatus(overrides: Record<string, unknown> = {}) {
  return {
    runId,
    sessionId,
    userMessageId: '7d5cf7b1-1a5c-4e60-a22a-6e7736e19a2a',
    assistantMessageId,
    status: 'RUNNING',
    assistantStatus: 'STREAMING',
    answer: '已经保存的部分回答。',
    contentReadable: true,
    sources: [],
    usage: null,
    metrics: null,
    errorCode: null,
    errorMessage: null,
    cancellable: true,
    cancelRequestedAt: null,
    startedAt: '2026-09-21T10:00:00+08:00',
    finishedAt: null,
    ...overrides,
  }
}

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

function sseEvent(name: string, data: unknown, newline = '\n') {
  return `event: ${name}${newline}data: ${JSON.stringify(data)}${newline}${newline}`
}

function streamResponse(chunks: Uint8Array[]) {
  return new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(chunk))
      controller.close()
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } })
}

beforeEach(() => vi.stubGlobal('fetch', fetchMock))
afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('会话创建接口', () => {
  it('获取 CSRF 后以固定 RAG 模式创建会话，不发送用户身份', async () => {
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({
        sessionId, title: '支付支持', mode: 'RAG', knowledgeBaseIds: [knowledgeBaseId],
        createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
      }, 201))

    await expect(createChatSession([knowledgeBaseId])).resolves.toMatchObject({ sessionId, mode: 'RAG' })

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/auth/csrf', expect.objectContaining({
      method: 'GET', credentials: 'same-origin',
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/chat/sessions', expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
    }))
    expect(fetchMock.mock.calls[1]?.[1]?.body).toBe(JSON.stringify({ kbIds: [knowledgeBaseId], mode: 'RAG' }))
  })

  it('拒绝非 RAG 或缺少知识库范围的成功响应', async () => {
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({
        sessionId, title: '支付支持', mode: 'ASSISTANT', knowledgeBaseIds: [],
        createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
      }, 201))

    await expect(createChatSession([knowledgeBaseId])).rejects.toMatchObject({ kind: 'invalid-response' })
  })
})

describe('流式问答接口', () => {
  it('按协议解析跨二进制分片、CRLF、同一分片多事件和中文内容', async () => {
    const text = [
      sseEvent('start', { runId, assistantMessageId }, '\r\n'),
      sseEvent('delta', { text: '先核对中文状态。' }),
      sseEvent('sources', { sources: [{
        number: 1,
        type: 'DOCUMENT',
        documentId: 'bf5cf7b1-1a5c-4e60-a22a-6e7736e19a2e',
        versionId: 'cf5cf7b1-1a5c-4e60-a22a-6e7736e19a2f',
        chunkId,
        title: '支付排查手册',
        section: '状态更新',
        isCurrent: true,
        previewPath: `/api/chunks/${chunkId}/source`,
      }] }),
      sseEvent('done', { runId, status: 'COMPLETED', usage: null, metrics: { totalMs: 12 } }),
    ].join('')
    const bytes = new TextEncoder().encode(text)
    const firstChineseByte = bytes.findIndex((value) => value >= 0x80)
    expect(firstChineseByte).toBeGreaterThan(0)
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(streamResponse([
        bytes.slice(0, firstChineseByte + 1),
        bytes.slice(firstChineseByte + 1, firstChineseByte + 9),
        bytes.slice(firstChineseByte + 9),
      ]))
    const events: ChatStreamEvent[] = []

    const result = await submitChatMessage(sessionId, 'request-1', '如何排查？', (event) => events.push(event))

    expect(events.map((event) => event.event)).toEqual(['start', 'delta', 'sources', 'done'])
    expect(events[1]).toMatchObject({ event: 'delta', data: { text: '先核对中文状态。' } })
    expect(result).toMatchObject({ kind: 'stream', terminal: { event: 'done' } })
    expect(fetchMock).toHaveBeenNthCalledWith(2, `/api/chat/sessions/${sessionId}/messages`, expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: expect.objectContaining({ Accept: 'text/event-stream, application/json', 'X-CSRF-TOKEN': 'csrf-token' }),
      body: JSON.stringify({ clientRequestId: 'request-1', content: '如何排查？' }),
    }))
  })

  it('流内 error 是唯一终态，并保留此前收到的增量', async () => {
    const encoder = new TextEncoder()
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(streamResponse([encoder.encode(
        sseEvent('start', { runId, assistantMessageId })
        + sseEvent('delta', { text: '已经生成的部分。' })
        + sseEvent('error', {
          code: 'MODEL_UNAVAILABLE', message: '回答模型暂时不可用', status: 'FAILED', traceId: 'trace-stream',
        }),
      )]))
    const events: ChatStreamEvent[] = []

    const result = await submitChatMessage(sessionId, 'request-2', '继续回答', (event) => events.push(event))

    expect(events).toContainEqual({ event: 'delta', data: { text: '已经生成的部分。' } })
    expect(result).toMatchObject({
      kind: 'stream',
      terminal: { event: 'error', data: { code: 'MODEL_UNAVAILABLE', status: 'FAILED' } },
    })
    expect(events.some((event) => event.event === 'done')).toBe(false)
  })

  it('复用请求按 JSON 响应返回，不尝试读取 SSE', async () => {
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({ runId, status: 'RUNNING', reused: true }))

    await expect(submitChatMessage(sessionId, 'request-3', '重复问题', vi.fn())).resolves.toEqual({
      kind: 'reused', run: { runId, status: 'RUNNING', reused: true },
    })
  })

  it('拒绝没有 start 的增量', async () => {
    const encoder = new TextEncoder()
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(streamResponse([encoder.encode(
        sseEvent('delta', { text: '越序内容' }),
      )]))

    await expect(submitChatMessage(sessionId, 'request-4', '非法流', vi.fn()))
      .rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it('忽略未知事件，并要求 done 前收到 sources 事件', async () => {
    const encoder = new TextEncoder()
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(streamResponse([encoder.encode(
        sseEvent('start', { runId, assistantMessageId })
        + sseEvent('tool', { callId: 'future-tool' })
        + sseEvent('sources', { sources: [] })
        + sseEvent('done', { runId, status: 'COMPLETED', usage: null, metrics: {} }),
      )]))
    const events: ChatStreamEvent[] = []

    await expect(submitChatMessage(sessionId, 'request-5', '兼容事件', (event) => events.push(event)))
      .resolves.toMatchObject({ kind: 'stream', terminal: { event: 'done' } })
    expect(events.map((event) => event.event)).toEqual(['start', 'sources', 'done'])

    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(streamResponse([encoder.encode(
        sseEvent('start', { runId, assistantMessageId })
        + sseEvent('done', { runId, status: 'COMPLETED', usage: null, metrics: {} }),
      )]))
    await expect(submitChatMessage(sessionId, 'request-6', '缺少来源事件', vi.fn()))
      .rejects.toMatchObject({ kind: 'invalid-response' })
  })
})

describe('来源预览接口', () => {
  it('只使用固定同源 chunk 路径读取已授权正文', async () => {
    fetchMock.mockResolvedValueOnce(ok({
      knowledgeBaseId,
      documentId: 'bf5cf7b1-1a5c-4e60-a22a-6e7736e19a2e',
      documentName: '支付排查手册',
      versionId: 'cf5cf7b1-1a5c-4e60-a22a-6e7736e19a2f',
      versionNo: 2,
      chunkId,
      chunkIndex: 1,
      section: '状态更新',
      pageNo: null,
      startOffset: 10,
      endOffset: 30,
      isCurrent: false,
      content: '检查订单状态更新日志。',
    }))

    await expect(fetchDocumentSource(chunkId)).resolves.toMatchObject({
      documentName: '支付排查手册', isCurrent: false,
    })
    expect(fetchMock).toHaveBeenCalledWith(`/api/chunks/${chunkId}/source`, expect.objectContaining({
      method: 'GET', credentials: 'same-origin',
    }))
  })
})

describe('运行状态与停止接口', () => {
  it('严格解析已持久化的运行状态，并只请求同源运行路径', async () => {
    fetchMock.mockResolvedValueOnce(ok(runStatus()))

    await expect(fetchChatRun(runId)).resolves.toMatchObject({
      runId,
      status: 'RUNNING',
      answer: '已经保存的部分回答。',
      cancellable: true,
    })
    expect(fetchMock).toHaveBeenCalledWith(`/api/chat/runs/${runId}`, expect.objectContaining({
      method: 'GET', credentials: 'same-origin', cache: 'no-store',
    }))

    fetchMock.mockResolvedValueOnce(ok(runStatus({ answer: 1 })))
    await expect(fetchChatRun(runId)).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it('停止前获取 CSRF，接受异步取消响应', async () => {
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({ runId, status: 'CANCELLED', newlyCancelled: true }, 202))

    await expect(cancelChatRun(runId)).resolves.toEqual({ runId, status: 'CANCELLED', newlyCancelled: true })
    expect(fetchMock).toHaveBeenNthCalledWith(2, `/api/chat/runs/${runId}/cancel`, expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
    }))
  })
})
