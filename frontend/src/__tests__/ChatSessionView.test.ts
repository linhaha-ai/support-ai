import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ChatSessionView from '../ChatSessionView.vue'

const fetchMock = vi.fn<typeof fetch>()
let wrapper: VueWrapper | undefined
const knowledgeBaseId = '4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'
const sessionId = '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a'

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

function mountView() {
  wrapper = mount(ChatSessionView)
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
    expect(view.text()).not.toContain('发送')
    expect(fetchMock.mock.calls[2]?.[1]?.body).toBe(JSON.stringify({ kbIds: [knowledgeBaseId], mode: 'RAG' }))
  })

  it('没有可访问知识库时不显示会话创建表单', async () => {
    fetchMock.mockResolvedValueOnce(ok([]))
    const view = mountView()
    await flushPromises()

    expect(view.text()).toContain('暂无可用于会话的知识库')
    expect(view.find('.chat-session-form').exists()).toBe(false)
  })

  it('知识库加载失败时仅显示错误和重试入口', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 503 }))
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
