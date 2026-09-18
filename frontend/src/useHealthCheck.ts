import { onScopeDispose, ref } from 'vue'

export type HealthState = 'loading' | 'up' | 'down' | 'unreachable'
export const HEALTH_CHECK_TIMEOUT_MS = 10_000

export function useHealthCheck() {
  const state = ref<HealthState>('loading')
  const detail = ref('正在确认后端服务是否就绪，请稍候。')
  const lastChecked = ref<Date | null>(null)
  // 取消请求仍可能收到迟到结果，用序号保证只有最近一次检查能更新页面。
  let requestSequence = 0
  let activeController: AbortController | undefined
  let timeout: ReturnType<typeof setTimeout> | undefined

  async function check() {
    const sequence = ++requestSequence
    activeController?.abort()
    clearTimeout(timeout)
    const controller = new AbortController()
    activeController = controller
    state.value = 'loading'
    detail.value = '正在确认后端服务是否就绪，请稍候。'

    const requestTimeout = setTimeout(() => {
      if (sequence !== requestSequence) return
      requestSequence++
      state.value = 'unreachable'
      detail.value = '连接等待超过 10 秒，请确认后端服务已启动后重试。'
      lastChecked.value = new Date()
      controller.abort()
    }, HEALTH_CHECK_TIMEOUT_MS)
    timeout = requestTimeout

    try {
      const response = await fetch('/api/health', {
        method: 'GET',
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        signal: controller.signal,
      })
      const body: unknown = await response.json()
      if (sequence !== requestSequence) return
      const status = typeof body === 'object' && body !== null && 'status' in body
        ? body.status
        : undefined

      if (response.ok && status === 'UP') {
        state.value = 'up'
        detail.value = '后端服务已就绪，应用基础连接正常。'
      } else if (status === 'DOWN') {
        state.value = 'down'
        detail.value = '后端已响应，但当前服务尚未就绪。请检查服务后重试。'
      } else {
        state.value = 'unreachable'
        detail.value = '健康检查未返回有效状态，请确认后端服务后重试。'
      }
      lastChecked.value = new Date()
    } catch {
      if (sequence !== requestSequence) return
      state.value = 'unreachable'
      detail.value = '暂时无法连接后端，请确认服务已启动后重试。'
      lastChecked.value = new Date()
    } finally {
      clearTimeout(requestTimeout)
      if (activeController === controller) activeController = undefined
    }
  }

  onScopeDispose(() => {
    requestSequence++
    clearTimeout(timeout)
    activeController?.abort()
  })

  return { state, detail, lastChecked, check }
}
