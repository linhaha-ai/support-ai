import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import TicketQueryView from '../TicketQueryView.vue'

const fetchMock = vi.fn<typeof fetch>()
let wrapper: VueWrapper | undefined

const ticket = {
  ticketNo: 'T10086',
  title: '订单关闭结果未同步',
  description: '支付成功后的订单状态仍未同步',
  status: 'IN_PROGRESS',
  ownerName: '支持工程师1',
  knowledgeBaseId: '25000000-0000-4000-8000-000000000001',
  knowledgeBaseName: '模拟订单系统工单',
  updatedAt: '2026-09-23T01:30:00Z',
  queriedAt: '2026-09-23T02:00:00Z',
}

function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function failure(status: number) {
  return new Response(JSON.stringify({
    code: status === 404 ? 'RESOURCE_NOT_FOUND' : 'INTERNAL_ERROR',
    message: status === 404 ? '资源不存在或不可访问' : '服务暂时不可用',
    traceId: 'trace-test',
  }), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('工单查询视图', () => {
  it('查询成功后展示当前状态、知识库和查询时间', async () => {
    fetchMock.mockResolvedValueOnce(ok(ticket))
    wrapper = mount(TicketQueryView)

    await wrapper.get('input[name="ticketNo"]').setValue(' T10086 ')
    await wrapper.get('.ticket-search').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/tickets/T10086', expect.objectContaining({
      method: 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      signal: expect.any(AbortSignal),
    }))
    expect(wrapper.get('.ticket-card').text()).toContain('T10086')
    expect(wrapper.get('.ticket-card').text()).toContain('IN_PROGRESS · 处理中')
    expect(wrapper.get('.ticket-card').text()).toContain('模拟订单系统工单')
    expect(wrapper.get('.ticket-card').text()).toContain('支持工程师1')
    expect(wrapper.get('.ticket-card').text()).toContain('状态更新时间')
    expect(wrapper.get('.ticket-card').text()).toContain('本次查询时间')
  })

  it('不存在和无权工单使用同一提示并可重试', async () => {
    fetchMock.mockResolvedValueOnce(failure(404)).mockResolvedValueOnce(ok(ticket))
    wrapper = mount(TicketQueryView)

    await wrapper.get('input[name="ticketNo"]').setValue('T10086')
    await wrapper.get('.ticket-search').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.ticket-alert').text()).toContain('未找到该工单，或当前账号无权查看。')
    expect(wrapper.find('.ticket-card').exists()).toBe(false)

    await wrapper.get('.ticket-alert .el-button').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.ticket-card').text()).toContain('T10086')
  })

  it('成功状态但响应结构不完整时拒绝展示', async () => {
    const { queriedAt: ignored, ...invalidTicket } = ticket
    expect(ignored).toBeTruthy()
    fetchMock.mockResolvedValueOnce(ok(invalidTicket))
    wrapper = mount(TicketQueryView)

    await wrapper.get('input[name="ticketNo"]').setValue('T10086')
    await wrapper.get('.ticket-search').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.ticket-alert').text()).toContain('工单查询响应格式无效')
    expect(wrapper.find('.ticket-card').exists()).toBe(false)
  })

  it('页面卸载时取消仍在等待的查询', async () => {
    fetchMock.mockReturnValueOnce(new Promise(() => {}))
    wrapper = mount(TicketQueryView)

    await wrapper.get('input[name="ticketNo"]').setValue('T10086')
    await wrapper.get('.ticket-search').trigger('submit')
    const signal = fetchMock.mock.calls[0]?.[1]?.signal
    wrapper.unmount()
    wrapper = undefined

    expect(signal?.aborted).toBe(true)
  })
})
