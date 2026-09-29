import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ChatSessionView from '../ChatSessionView.vue'

const fetchMock = vi.fn<typeof fetch>()
let wrapper: VueWrapper | undefined
const knowledgeBaseId = '4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'
const sessionId = '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a'
const runId = '8f5cf7b1-1a5c-4e60-a22a-6e7736e19a2b'
const assistantMessageId = '9f5cf7b1-1a5c-4e60-a22a-6e7736e19a2c'
const chunkId = 'af5cf7b1-1a5c-4e60-a22a-6e7736e19a2d'

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

function knowledgeBases() {
  return [{
    id: knowledgeBaseId, name: '产品支持', description: null, status: 'ACTIVE', permission: 'READ',
    revision: 1, updatedAt: '2026-09-21T10:00:00+08:00',
  }]
}

function sseEvent(name: string, data: unknown) {
  return `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`
}

function pendingStream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const response = new Response(new ReadableStream<Uint8Array>({
    start(currentController) {
      controller = currentController
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
  return { response, controller: () => controller }
}

function mountView() {
  wrapper = mount(ChatSessionView, { global: { stubs: { teleport: true } } })
  return wrapper
}

beforeEach(() => vi.stubGlobal('fetch', fetchMock))
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('会话创建页面', () => {
  it('选择可访问知识库后创建固定 RAG 会话并展示摘要', async () => {
    fetchMock.mockResolvedValueOnce(ok(knowledgeBases()))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({
        sessionId, title: '产品支持会话', mode: 'RAG', knowledgeBaseIds: [knowledgeBaseId],
        createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
      }, 201))
    const view = mountView()
    await flushPromises()

    await view.get('input[type="checkbox"]').setValue(true)
    await view.get('.chat-session-form').trigger('submit')
    await flushPromises()

    expect(view.text()).toContain(sessionId)
    expect(view.text()).toContain('产品支持')
    expect(view.text()).toContain('RAG')
    expect(view.text()).toContain('发送')
    expect(fetchMock.mock.calls[3]?.[1]?.body).toBe(JSON.stringify({ kbIds: [knowledgeBaseId], mode: 'RAG' }))
  })

  it.each([false, true])('创建智能助手会话并展示工单查询时刻和历史标记（继承：%s）', async (inherited) => {
    const encoder = new TextEncoder()
    const stream = new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(
          sseEvent('start', { runId, assistantMessageId })
          + sseEvent('tool', { name: 'getTicketStatus', status: 'STARTED' })
          + sseEvent('tool', { name: 'getTicketStatus', status: 'SUCCEEDED' })
          + sseEvent('delta', { text: '工单仍在处理中。[1]' })
          + sseEvent('sources', { sources: [{
            number: 1,
            type: 'TICKET',
            documentId: null,
            versionId: null,
            chunkId: null,
            title: '支付工单',
            section: '张工',
            available: true,
            isCurrent: !inherited,
            previewPath: null,
            ticketNo: 'T10086',
            ticketStatus: 'IN_PROGRESS',
            ticketUpdatedAt: '2026-09-23T12:00:00Z',
            ticketQueriedAt: '2026-09-23T12:05:00Z',
            inherited,
            originSourceId: inherited ? 'root-source' : null,
            toolCallId: 'tool-current',
          }] })
          + sseEvent('done', { runId, status: 'COMPLETED', usage: null, metrics: {} }),
        ))
        controller.close()
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    fetchMock.mockResolvedValueOnce(ok(knowledgeBases()))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({
        sessionId, title: '智能助手会话', mode: 'ASSISTANT', knowledgeBaseIds: [knowledgeBaseId],
        createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
      }, 201))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(stream)
    const view = mountView()
    await flushPromises()

    await view.get('input[type="radio"][value="ASSISTANT"]').setValue(true)
    await view.get('input[type="checkbox"]').setValue(true)
    await view.get('.chat-session-form').trigger('submit')
    await flushPromises()
    await view.get('textarea').setValue('T10086 当前状态')
    await view.get('.chat-composer').trigger('submit')
    await flushPromises()

    expect(fetchMock.mock.calls[3]?.[1]?.body)
      .toBe(JSON.stringify({ kbIds: [knowledgeBaseId], mode: 'ASSISTANT' }))
    expect(view.text()).toContain('智能助手')
    expect(view.text()).toContain('查询工单 · 已完成')
    expect(view.text()).toContain('[1] T10086 · 处理中')
    expect(view.text()).toContain(inherited ? '先前查询结果' : '本轮查询结果')
    expect(view.text()).toContain('查询于')
    expect(view.text()).toContain('仅代表查询时状态')
  })

  it('逐步追加回答，完成后展示可打开的授权来源', async () => {
    const stream = pendingStream()
    fetchMock.mockResolvedValueOnce(ok(knowledgeBases()))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({
        sessionId, title: '产品支持会话', mode: 'RAG', knowledgeBaseIds: [knowledgeBaseId],
        createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
      }, 201))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(stream.response)
    const view = mountView()
    await flushPromises()
    await view.get('input[type="checkbox"]').setValue(true)
    await view.get('.chat-session-form').trigger('submit')
    await flushPromises()

    await view.get('textarea').setValue('支付状态如何排查？')
    await view.get('.chat-composer').trigger('submit')
    await flushPromises()
    const encoder = new TextEncoder()
    stream.controller().enqueue(encoder.encode(
      sseEvent('start', { runId, assistantMessageId })
      + sseEvent('delta', { text: '先检查回调日志，' }),
    ))
    await flushPromises()

    expect(view.text()).toContain('先检查回调日志，')
    expect(view.text()).toContain('生成中')
    expect(view.text()).toContain('停止')
    const newSessionButton = view.findAll('button').find((button) => button.text().includes('新建会话'))
    expect(newSessionButton?.attributes('disabled')).toBeDefined()

    stream.controller().enqueue(encoder.encode(
      sseEvent('delta', { text: '再核对订单状态。[1]' })
      + sseEvent('sources', { sources: [{
        number: 1,
        type: 'DOCUMENT',
        documentId: 'bf5cf7b1-1a5c-4e60-a22a-6e7736e19a2e',
        versionId: 'cf5cf7b1-1a5c-4e60-a22a-6e7736e19a2f',
        chunkId,
        title: '支付排查手册',
        section: '状态更新',
        available: true,
        isCurrent: true,
        previewPath: `/api/chunks/${chunkId}/source`,
      }] })
      + sseEvent('done', { runId, status: 'COMPLETED', usage: null, metrics: {} }),
    ))
    stream.controller().close()
    await flushPromises()

    expect(view.text()).toContain('先检查回调日志，再核对订单状态。[1]')
    expect(view.text()).toContain('已完成')
    expect(view.text()).toContain('模型 Token 用量未知')
    expect(newSessionButton?.attributes('disabled')).toBeUndefined()
    expect(view.get('.chat-source-button').text()).toContain('[1] 支付排查手册')

    fetchMock.mockResolvedValueOnce(ok({
      knowledgeBaseId,
      documentId: 'bf5cf7b1-1a5c-4e60-a22a-6e7736e19a2e',
      documentName: '支付排查手册',
      versionId: 'cf5cf7b1-1a5c-4e60-a22a-6e7736e19a2f',
      versionNo: 1,
      chunkId,
      chunkIndex: 0,
      section: '状态更新',
      pageNo: null,
      startOffset: 0,
      endOffset: 12,
      isCurrent: true,
      content: '订单状态更新日志正文。',
    }))
    await view.get('.chat-source-button').trigger('click')
    await flushPromises()

    expect(view.text()).toContain('订单状态更新日志正文。')
    expect(fetchMock).toHaveBeenLastCalledWith(`/api/chunks/${chunkId}/source`, expect.objectContaining({
      method: 'GET', credentials: 'same-origin',
    }))
  })

  it('流内失败时保留部分回答并显示未完整状态', async () => {
    const encoder = new TextEncoder()
    const stream = new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(
          sseEvent('start', { runId, assistantMessageId })
          + sseEvent('delta', { text: '已生成的部分回答。' })
          + sseEvent('error', {
            code: 'MODEL_UNAVAILABLE', message: '回答模型暂时不可用', status: 'FAILED', traceId: 'trace-stream',
          }),
        ))
        controller.close()
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    fetchMock.mockResolvedValueOnce(ok(knowledgeBases()))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({
        sessionId, title: '产品支持会话', mode: 'RAG', knowledgeBaseIds: [knowledgeBaseId],
        createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
      }, 201))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(stream)
    const view = mountView()
    await flushPromises()
    await view.get('input[type="checkbox"]').setValue(true)
    await view.get('.chat-session-form').trigger('submit')
    await flushPromises()
    await view.get('textarea').setValue('继续回答')
    await view.get('.chat-composer').trigger('submit')
    await flushPromises()

    expect(view.text()).toContain('已生成的部分回答。')
    expect(view.text()).toContain('未完整')
    expect(view.text()).toContain('回答模型暂时不可用')
    expect(view.text()).toContain('追踪号 trace-stream')
    expect(view.text()).not.toContain('已完成')
  })

  it('重复提交返回 JSON 时立即查询已持久化运行结果，不重新读取模型流', async () => {
    fetchMock.mockResolvedValueOnce(ok(knowledgeBases()))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({
        sessionId, title: '产品支持会话', mode: 'RAG', knowledgeBaseIds: [knowledgeBaseId],
        createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
      }, 201))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok({ runId, status: 'COMPLETED', reused: true }))
      .mockResolvedValueOnce(ok({
        runId,
        sessionId,
        userMessageId: '1f5cf7b1-1a5c-4e60-a22a-6e7736e19a2c',
        assistantMessageId,
        status: 'COMPLETED',
        assistantStatus: 'COMPLETED',
        answer: '来自已持久化运行的回答。',
        contentReadable: true,
        sources: [],
        usage: null,
        metrics: null,
        errorCode: null,
        errorMessage: null,
        cancellable: false,
        cancelRequestedAt: null,
        startedAt: '2026-09-21T10:00:00+08:00',
        finishedAt: '2026-09-21T10:01:00+08:00',
      }))
    const view = mountView()
    await flushPromises()
    await view.get('input[type="checkbox"]').setValue(true)
    await view.get('.chat-session-form').trigger('submit')
    await flushPromises()
    await view.get('textarea').setValue('重复问题')
    await view.get('.chat-composer').trigger('submit')
    await flushPromises()

    expect(view.text()).toContain('来自已持久化运行的回答。')
    expect(view.text()).toContain('已完成')
    expect(fetchMock).toHaveBeenLastCalledWith(`/api/chat/runs/${runId}`, expect.objectContaining({ method: 'GET' }))
  })

  it('没有可访问知识库时不显示会话创建表单', async () => {
    fetchMock.mockResolvedValueOnce(ok([]))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
    const view = mountView()
    await flushPromises()

    expect(view.text()).toContain('暂无可用于会话的知识库')
    expect(view.find('.chat-session-form').exists()).toBe(false)
  })

  it('知识库加载失败时仅显示错误和重试入口', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
      .mockResolvedValueOnce(ok(knowledgeBases()))
    const view = mountView()
    await flushPromises()

    expect(view.text()).toContain('知识库请求未完成')
    expect(view.find('.chat-session-form').exists()).toBe(false)

    await view.get('.chat-session-alert button').trigger('click')
    await flushPromises()

    expect(view.find('.chat-session-form').exists()).toBe(true)
  })
})
