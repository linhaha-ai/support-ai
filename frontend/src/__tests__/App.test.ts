import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { effectScope } from 'vue'
import App from '../App.vue'
import DashboardView from '../DashboardView.vue'
import LoginView from '../LoginView.vue'
import { HEALTH_CHECK_TIMEOUT_MS, useHealthCheck } from '../useHealthCheck'

const fetchMock = vi.fn<typeof fetch>()
let wrapper: VueWrapper | undefined

const user = {
  id: 'a2e1ef2a-c7e2-4e57-9b2b-fca57e20d720',
  username: 'admin',
  displayName: '系统管理员',
  role: 'ADMIN',
}

function mountDashboard(authError = '') {
  return mount(DashboardView, { props: { user, loggingOut: false, authError } })
}

function response(status: string, httpStatus = 200) {
  return new Response(JSON.stringify({ status }), {
    status: httpStatus,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
})

describe('运行概览', () => {
  it('首次请求显示加载状态，并仅请求相对健康检查接口', () => {
    fetchMock.mockReturnValue(new Promise(() => {}))
    wrapper = mountDashboard()

    expect(wrapper.get('[role="status"]').attributes('aria-busy')).toBe('true')
    expect(wrapper.text()).toContain('正在连接后端')
    expect(wrapper.get('.check-button').attributes('disabled')).toBeDefined()
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/health', expect.objectContaining({
      method: 'GET',
      signal: expect.any(AbortSignal),
    }))
  })

  it('UP 显示连接成功，同时保持模型待验收说明', async () => {
    fetchMock.mockResolvedValue(response('UP'))
    wrapper = mountDashboard()
    await flushPromises()

    expect(wrapper.text()).toContain('后端连接成功')
    expect(wrapper.text()).toContain('服务健康状态不代表模型已完成验收')
    expect(wrapper.text()).toContain('身份会话')
    expect(wrapper.get('.check-button').text()).toContain('重新检查')
    expect(wrapper.get('[role="status"]').attributes('aria-busy')).toBe('false')
  })

  it('退出失败时在工作台显示认证错误', async () => {
    fetchMock.mockResolvedValue(response('UP'))
    wrapper = mountDashboard('退出登录未完成，请重试。')
    await flushPromises()

    expect(wrapper.get('.workspace-auth-error').text()).toBe('退出登录未完成，请重试。')
  })

  it('HTTP 503 的 DOWN 响应显示未就绪', async () => {
    fetchMock.mockResolvedValue(response('DOWN', 503))
    wrapper = mountDashboard()
    await flushPromises()

    expect(wrapper.text()).toContain('后端尚未就绪')
    expect(wrapper.text()).toContain('后端已响应')
    expect(wrapper.get('.check-button').text()).toContain('重试连接')
  })

  it('网络失败后可重试并恢复成功状态', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(response('UP'))
    wrapper = mountDashboard()
    await flushPromises()
    expect(wrapper.text()).toContain('暂时无法连接后端')

    await wrapper.get('.check-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('后端连接成功')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('响应格式无效时不会误报健康', async () => {
    fetchMock.mockResolvedValue(new Response('<html>proxy error</html>', { status: 502 }))
    wrapper = mountDashboard()
    await flushPromises()
    expect(wrapper.text()).toContain('暂时无法连接')
    expect(wrapper.text()).not.toContain('后端连接成功')
  })

  it.each([
    ['UNKNOWN', 200],
    ['UP', 500],
  ])('状态 %s、HTTP %i 不会误报健康', async (status, httpStatus) => {
    fetchMock.mockResolvedValue(response(status, httpStatus))
    wrapper = mountDashboard()
    await flushPromises()
    expect(wrapper.text()).toContain('健康检查未返回有效状态')
  })

  it('请求超过 10 秒主动取消，显示重试入口', async () => {
    vi.useFakeTimers()
    fetchMock.mockReturnValue(new Promise(() => {}))
    wrapper = mountDashboard()
    const signal = fetchMock.mock.calls[0]?.[1]?.signal
    await vi.advanceTimersByTimeAsync(HEALTH_CHECK_TIMEOUT_MS)

    expect(signal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('连接等待超过 10 秒')
    expect(wrapper.get('.check-button').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('.check-button').text()).toContain('重试连接')
  })

  it('页面卸载时取消仍在等待的请求', () => {
    fetchMock.mockReturnValue(new Promise(() => {}))
    wrapper = mountDashboard()
    const signal = fetchMock.mock.calls[0]?.[1]?.signal
    wrapper.unmount()
    wrapper = undefined
    expect(signal?.aborted).toBe(true)
  })

  it('重新检查后旧请求的延迟响应不会覆盖新结果', async () => {
    let resolvePrevious!: (value: Response) => void
    fetchMock.mockReturnValueOnce(new Promise((resolve) => { resolvePrevious = resolve }))
      .mockResolvedValueOnce(response('UP'))
    const scope = effectScope()
    const health = scope.run(() => useHealthCheck())!
    try {
      const previous = health.check()
      await health.check()
      resolvePrevious(response('DOWN', 503))
      await previous
      expect(health.state.value).toBe('up')
    } finally {
      scope.stop()
    }
  })
})

describe('登录入口', () => {
  it('应用初始化为未登录状态时显示登录表单', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({
      code: 'OK',
      data: { token: 'csrf-token', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' },
      traceId: 'trace-test',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: 'UNAUTHORIZED', message: '请先登录', traceId: 'trace-test',
      }), { status: 401, headers: { 'Content-Type': 'application/json' } }))

    wrapper = mount(App)
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('登录工作空间')
    expect(wrapper.get('input[name="username"]')).toBeTruthy()
    expect(wrapper.get('input[name="password"]')).toBeTruthy()
  })

  it('用户名只有空格时禁止提交，并在有效提交时裁剪用户名', async () => {
    wrapper = mount(LoginView, { props: { submitting: false, error: '' } })
    await wrapper.get('input[name="username"]').setValue('   ')
    await wrapper.get('input[name="password"]').setValue('secret')
    expect(wrapper.get('.login-button').attributes('disabled')).toBeDefined()

    await wrapper.get('input[name="username"]').setValue(' admin ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toEqual([['admin', 'secret']])
  })
})
