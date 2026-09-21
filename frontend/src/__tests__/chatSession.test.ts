import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createChatSession } from '../chatSession'

const fetchMock = vi.fn<typeof fetch>()
const knowledgeBaseId = '4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'
const sessionId = '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a'

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
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
