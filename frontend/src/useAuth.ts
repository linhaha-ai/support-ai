import { onScopeDispose, ref } from 'vue'

export interface CurrentUser {
  id: string
  username: string
  displayName: string
  role: string
}

interface CsrfData {
  token: string
  headerName: string
  parameterName: string
}

interface ApiEnvelope<T> {
  code: string
  data?: T
  message?: string
  traceId?: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function parseEnvelope<T>(value: unknown): ApiEnvelope<T> | null {
  if (!isRecord(value) || typeof value.code !== 'string') return null
  return {
    code: value.code,
    data: value.data as T | undefined,
    message: typeof value.message === 'string' ? value.message : undefined,
    traceId: typeof value.traceId === 'string' ? value.traceId : undefined,
  }
}

function parseCsrf(value: unknown): CsrfData | null {
  if (!isRecord(value)
    || typeof value.token !== 'string'
    || typeof value.headerName !== 'string'
    || typeof value.parameterName !== 'string') return null
  return {
    token: value.token,
    headerName: value.headerName,
    parameterName: value.parameterName,
  }
}

function parseUser(value: unknown): CurrentUser | null {
  if (!isRecord(value)
    || typeof value.id !== 'string'
    || typeof value.username !== 'string'
    || typeof value.displayName !== 'string'
    || typeof value.role !== 'string') return null
  return {
    id: value.id,
    username: value.username,
    displayName: value.displayName,
    role: value.role,
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

export function useAuth() {
  const user = ref<CurrentUser | null>(null)
  const loading = ref(true)
  const submitting = ref(false)
  const error = ref('')
  let csrf: CsrfData | null = null
  let disposed = false

  async function refreshCsrf(): Promise<boolean> {
    const response = await fetch('/api/auth/csrf', {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
      cache: 'no-store',
    })
    const envelope = parseEnvelope<CsrfData>(await readJson(response))
    const token = envelope?.code === 'OK' ? parseCsrf(envelope.data) : null
    if (!response.ok || !token) {
      csrf = null
      return false
    }
    csrf = token
    return true
  }

  async function loadCurrentUser(): Promise<boolean> {
    const response = await fetch('/api/auth/me', {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
      cache: 'no-store',
    })
    const envelope = parseEnvelope<CurrentUser>(await readJson(response))
    if (response.status === 401) {
      user.value = null
      return true
    }
    const currentUser = response.ok && envelope?.code === 'OK' ? parseUser(envelope.data) : null
    if (!currentUser) return false
    user.value = currentUser
    return true
  }

  async function initialize() {
    loading.value = true
    error.value = ''
    try {
      const csrfReady = await refreshCsrf()
      const identityReady = csrfReady && await loadCurrentUser()
      if (!disposed && !identityReady) {
        error.value = '暂时无法连接认证服务，请确认后端已启动后重试。'
      }
    } catch {
      if (!disposed) error.value = '暂时无法连接认证服务，请确认后端已启动后重试。'
    } finally {
      if (!disposed) loading.value = false
    }
  }

  async function login(username: string, password: string): Promise<boolean> {
    submitting.value = true
    error.value = ''
    try {
      if (!csrf && !await refreshCsrf()) {
        error.value = '请求验证未就绪，请重试。'
        return false
      }
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          [csrf!.headerName]: csrf!.token,
        },
        credentials: 'same-origin',
        cache: 'no-store',
        body: JSON.stringify({ username, password }),
      })
      const envelope = parseEnvelope<CurrentUser>(await readJson(response))
      const currentUser = response.ok && envelope?.code === 'OK' ? parseUser(envelope.data) : null
      if (!currentUser) {
        if (response.status === 403) await refreshCsrf()
        error.value = response.status === 401 ? '用户名或密码不正确。' : '登录未完成，请重试。'
        return false
      }
      if (!await refreshCsrf()) {
        error.value = '登录状态已建立，但请求验证刷新失败，请重新连接。'
        return false
      }
      user.value = currentUser
      return true
    } catch {
      error.value = '暂时无法连接认证服务，请稍后重试。'
      return false
    } finally {
      if (!disposed) submitting.value = false
    }
  }

  async function logout(): Promise<boolean> {
    submitting.value = true
    error.value = ''
    try {
      if (!csrf && !await refreshCsrf()) {
        error.value = '请求验证未就绪，请重试。'
        return false
      }
      const response = await fetch('/api/auth/logout', {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          [csrf!.headerName]: csrf!.token,
        },
        credentials: 'same-origin',
        cache: 'no-store',
      })
      if (response.status !== 204 && response.status !== 401) {
        if (response.status === 403) await refreshCsrf()
        error.value = '退出登录未完成，请重试。'
        return false
      }
      user.value = null
      csrf = null
      if (!await refreshCsrf()) {
        error.value = '已退出登录，但请求验证刷新失败，请重试。'
      }
      return true
    } catch {
      error.value = '暂时无法连接认证服务，请稍后重试。'
      return false
    } finally {
      if (!disposed) submitting.value = false
    }
  }

  onScopeDispose(() => {
    disposed = true
  })

  return { user, loading, submitting, error, initialize, login, logout }
}
