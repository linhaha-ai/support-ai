import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useChatSessions } from '../useChatSessions'

const fetchMock = vi.fn<typeof fetch>()
const sessionId = '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a'
const runId = '8f5cf7b1-1a5c-4e60-a22a-6e7736e19a2b'
const assistantMessageId = '9f5cf7b1-1a5c-4e60-a22a-6e7736e19a2c'
const userMessageId = '1f5cf7b1-1a5c-4e60-a22a-6e7736e19a2c'

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function streamResponse() {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  return {
    response: new Response(new ReadableStream<Uint8Array>({
      start(currentController) {
        controller = currentController
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }),
    controller: () => controller,
  }
}

function runStatus(status: 'RUNNING' | 'COMPLETED' | 'CANCELLED', cancellable = status === 'RUNNING') {
  return {
    runId,
    sessionId,
    userMessageId,
    assistantMessageId,
    status,
    assistantStatus: status === 'COMPLETED' ? 'COMPLETED' : status === 'CANCELLED' ? 'CANCELLED' : 'STREAMING',
    answer: status === 'COMPLETED' ? '数据库中的完整回答。' : '数据库中的部分回答。',
    contentReadable: true,
    sources: [],
    usage: null,
    metrics: null,
    errorCode: null,
    errorMessage: null,
    cancellable,
    cancelRequestedAt: status === 'CANCELLED' ? '2026-09-21T10:02:00+08:00' : null,
    startedAt: '2026-09-21T10:00:00+08:00',
    finishedAt: status === 'RUNNING' ? null : '2026-09-21T10:03:00+08:00',
  }
}

function startEvent() {
  return new TextEncoder().encode(`event: start\ndata: ${JSON.stringify({ runId, assistantMessageId })}\n\n`)
}

beforeEach(() => vi.stubGlobal('fetch', fetchMock))
afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('问答运行状态', () => {
  it('流连接异常后查询持久化状态，并只展示一个后台处理中提示', async () => {
    const stream = streamResponse()
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(stream.response)
      .mockResolvedValueOnce(ok(runStatus('RUNNING')))
    const scope = effectScope()
    const sessions = scope.run(() => useChatSessions())!
    sessions.createdSession.value = {
      sessionId,
      title: '测试会话',
      mode: 'RAG',
      knowledgeBaseIds: ['4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'],
      createdAt: '2026-09-21T10:00:00+08:00',
      updatedAt: '2026-09-21T10:00:00+08:00',
    }
    sessions.messageInput.value = '断线后查询的问题'

    const sending = sessions.send()
    await Promise.resolve()
    stream.controller().enqueue(startEvent())
    await vi.waitFor(() => expect(sessions.activeRunId.value).toBe(runId))
    stream.controller().error(new Error('连接中断'))
    await sending

    expect(sessions.backgroundProcessing.value).toBe(true)
    expect(sessions.sendNotice.value).toBe('')
    expect(sessions.messages.value.at(-1)).toMatchObject({
      id: assistantMessageId,
      status: 'BACKGROUND',
      content: '数据库中的部分回答。',
    })
    expect(fetchMock).toHaveBeenLastCalledWith(`/api/chat/runs/${runId}`, expect.objectContaining({ method: 'GET' }))
    scope.stop()
  })

  it('取消本地流后以数据库终态覆盖部分回答，完成竞争保持完成结果', async () => {
    const stream = streamResponse()
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(stream.response)
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({ runId, status: 'COMPLETED', newlyCancelled: false }))
      .mockResolvedValueOnce(ok(runStatus('COMPLETED')))
    const scope = effectScope()
    const sessions = scope.run(() => useChatSessions())!
    sessions.createdSession.value = {
      sessionId,
      title: '测试会话',
      mode: 'RAG',
      knowledgeBaseIds: ['4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'],
      createdAt: '2026-09-21T10:00:00+08:00',
      updatedAt: '2026-09-21T10:00:00+08:00',
    }
    sessions.messageInput.value = '需要停止的问题'

    const sending = sessions.send()
    await Promise.resolve()
    stream.controller().enqueue(startEvent())
    await vi.waitFor(() => expect(sessions.activeRunId.value).toBe(runId))

    await sessions.cancelRun()
    stream.controller().close()
    await sending

    expect(sessions.activeRunId.value).toBeNull()
    expect(sessions.messages.value.at(-1)).toMatchObject({
      id: assistantMessageId,
      status: 'COMPLETED',
      content: '数据库中的完整回答。',
    })
    expect(fetchMock).toHaveBeenLastCalledWith(`/api/chat/runs/${runId}`, expect.objectContaining({ method: 'GET' }))
    scope.stop()
  })

  it('流连接和状态查询同时失败时保留后台恢复入口', async () => {
    const stream = streamResponse()
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(stream.response)
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
    const scope = effectScope()
    const sessions = scope.run(() => useChatSessions())!
    sessions.createdSession.value = {
      sessionId,
      title: '测试会话',
      mode: 'RAG',
      knowledgeBaseIds: ['4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'],
      createdAt: '2026-09-21T10:00:00+08:00',
      updatedAt: '2026-09-21T10:00:00+08:00',
    }
    sessions.messageInput.value = '无法立即恢复的问题'

    const sending = sessions.send()
    await Promise.resolve()
    stream.controller().enqueue(startEvent())
    await vi.waitFor(() => expect(sessions.activeRunId.value).toBe(runId))
    stream.controller().error(new Error('连接中断'))
    await sending

    expect(sessions.activeRunId.value).toBe(runId)
    expect(sessions.backgroundProcessing.value).toBe(true)
    expect(sessions.backgroundStatusUnknown.value).toBe(true)
    expect(sessions.messages.value.at(-1)).toMatchObject({
      status: 'BACKGROUND',
      errorMessage: '回答连接中断，暂时无法确认后台运行状态。',
    })
    expect(sessions.sendError.value).not.toBe('')
    scope.stop()
  })

  it('以后端返回的 cancellable 控制停止入口', async () => {
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({ runId, status: 'RUNNING', reused: true }))
      .mockResolvedValueOnce(ok(runStatus('RUNNING', false)))
    const scope = effectScope()
    const sessions = scope.run(() => useChatSessions())!
    sessions.createdSession.value = {
      sessionId,
      title: '测试会话',
      mode: 'RAG',
      knowledgeBaseIds: ['4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'],
      createdAt: '2026-09-21T10:00:00+08:00',
      updatedAt: '2026-09-21T10:00:00+08:00',
    }
    sessions.messageInput.value = '重复请求'

    await sessions.send()

    expect(sessions.activeRunId.value).toBe(runId)
    expect(sessions.activeRunCancellable.value).toBe(false)
    expect(sessions.canCancelRun.value).toBe(false)
    scope.stop()
  })

  it('停止请求失败后保留错误，并继续允许恢复或重试', async () => {
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(ok(runStatus('RUNNING')))
    const scope = effectScope()
    const sessions = scope.run(() => useChatSessions())!
    sessions.activeRunId.value = runId
    sessions.activeRunCancellable.value = true

    await sessions.cancelRun()

    expect(sessions.activeRunId.value).toBe(runId)
    expect(sessions.canCancelRun.value).toBe(true)
    expect(sessions.sendError.value).toBe('运行状态请求未完成，请稍后重试。')
    scope.stop()
  })

  it('活跃运行期间不能丢弃当前会话', () => {
    const scope = effectScope()
    const sessions = scope.run(() => useChatSessions())!
    sessions.createdSession.value = {
      sessionId,
      title: '测试会话',
      mode: 'RAG',
      knowledgeBaseIds: ['4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'],
      createdAt: '2026-09-21T10:00:00+08:00',
      updatedAt: '2026-09-21T10:00:00+08:00',
    }
    sessions.activeRunId.value = runId
    sessions.activeRunCancellable.value = true

    sessions.startAnother()

    expect(sessions.createdSession.value?.sessionId).toBe(sessionId)
    expect(sessions.activeRunId.value).toBe(runId)
    expect(sessions.canStartAnother.value).toBe(false)
    scope.stop()
  })
})
