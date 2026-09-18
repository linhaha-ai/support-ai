import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useAuth } from '../useAuth'

const fetchMock = vi.fn<typeof fetch>()

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function failure(code: string, status: number) {
  return new Response(JSON.stringify({ code, message: 'ignored', traceId: 'trace-test' }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const csrf = { token: 'csrf-token', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' }
const user = { id: '8511ca5f-7785-4bde-970e-371b9d9c4e2e', username: 'admin', displayName: '系统管理员', role: 'ADMIN' }

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  fetchMock.mockReset()
})

function createAuth() {
  const scope = effectScope()
  const auth = scope.run(() => useAuth())!
  return { auth, dispose: () => scope.stop() }
}

describe('认证状态', () => {
  it('初始化先请求 CSRF，再以相对地址读取当前身份', async () => {
    fetchMock.mockResolvedValueOnce(ok(csrf)).mockResolvedValueOnce(failure('UNAUTHORIZED', 401))
    const { auth, dispose } = createAuth()
    try {
      await auth.initialize()

      expect(auth.loading.value).toBe(false)
      expect(auth.user.value).toBeNull()
      expect(auth.error.value).toBe('')
      expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/auth/csrf', expect.objectContaining({
        method: 'GET', credentials: 'same-origin',
      }))
      expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/auth/me', expect.objectContaining({
        method: 'GET', credentials: 'same-origin',
      }))
    } finally {
      dispose()
    }
  })

  it('登录动态携带 CSRF 请求头，并在成功后刷新令牌', async () => {
    fetchMock.mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(failure('UNAUTHORIZED', 401))
      .mockResolvedValueOnce(ok(user))
      .mockResolvedValueOnce(ok({ ...csrf, token: 'rotated-csrf-token' }))
    const { auth, dispose } = createAuth()
    try {
      await auth.initialize()
      await expect(auth.login('admin', 'not-recorded')).resolves.toBe(true)

      expect(auth.user.value).toEqual(user)
      expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/auth/login', expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
        credentials: 'same-origin',
      }))
      expect(fetchMock.mock.calls[2]?.[1]?.body).toBe(JSON.stringify({ username: 'admin', password: 'not-recorded' }))
      expect(fetchMock).toHaveBeenNthCalledWith(4, '/api/auth/csrf', expect.objectContaining({ method: 'GET' }))
    } finally {
      dispose()
    }
  })

  it('错误密码不会建立身份，也不会展示服务端错误内容', async () => {
    fetchMock.mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(failure('UNAUTHORIZED', 401))
      .mockResolvedValueOnce(failure('INVALID_CREDENTIALS', 401))
    const { auth, dispose } = createAuth()
    try {
      await auth.initialize()
      await expect(auth.login('admin', 'wrong-password')).resolves.toBe(false)

      expect(auth.user.value).toBeNull()
      expect(auth.error.value).toBe('用户名或密码不正确。')
      expect(auth.error.value).not.toContain('ignored')
    } finally {
      dispose()
    }
  })

  it('登录后的 CSRF 刷新失败时不进入工作台', async () => {
    fetchMock.mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(failure('UNAUTHORIZED', 401))
      .mockResolvedValueOnce(ok(user))
      .mockResolvedValueOnce(failure('SERVICE_UNAVAILABLE', 503))
    const { auth, dispose } = createAuth()
    try {
      await auth.initialize()
      await expect(auth.login('admin', 'not-recorded')).resolves.toBe(false)

      expect(auth.user.value).toBeNull()
      expect(auth.error.value).toBe('登录状态已建立，但请求验证刷新失败，请重新连接。')
    } finally {
      dispose()
    }
  })

  it('退出后清除身份并重新请求 CSRF 令牌', async () => {
    fetchMock.mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(ok(user))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(ok({ ...csrf, token: 'post-logout-token' }))
    const { auth, dispose } = createAuth()
    try {
      await auth.initialize()
      await expect(auth.logout()).resolves.toBe(true)

      expect(auth.user.value).toBeNull()
      expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/auth/logout', expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
      }))
      expect(fetchMock).toHaveBeenNthCalledWith(4, '/api/auth/csrf', expect.objectContaining({ method: 'GET' }))
    } finally {
      dispose()
    }
  })

  it('退出失败时保留当前身份并提供错误提示', async () => {
    fetchMock.mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(ok(user))
      .mockResolvedValueOnce(failure('SERVICE_UNAVAILABLE', 503))
    const { auth, dispose } = createAuth()
    try {
      await auth.initialize()
      await expect(auth.logout()).resolves.toBe(false)

      expect(auth.user.value).toEqual(user)
      expect(auth.error.value).toBe('退出登录未完成，请重试。')
    } finally {
      dispose()
    }
  })
})
