import { computed, onScopeDispose, ref } from 'vue'
import { fetchTicket, type TicketDetail } from './ticket'

function messageFor(error: unknown): string {
  return typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string'
    ? error.message
    : '工单查询未完成，请稍后重试。'
}

export function useTicketQuery() {
  const ticketNo = ref('')
  const ticket = ref<TicketDetail | null>(null)
  const loading = ref(false)
  const error = ref('')
  let requestId = 0
  let controller: AbortController | null = null
  let disposed = false

  const canQuery = computed(() => ticketNo.value.trim().length > 0 && !loading.value)

  async function query() {
    const normalizedTicketNo = ticketNo.value.trim()
    if (!normalizedTicketNo || loading.value) return
    const currentRequest = ++requestId
    controller?.abort()
    const currentController = new AbortController()
    controller = currentController
    loading.value = true
    error.value = ''
    ticket.value = null
    try {
      const result = await fetchTicket(normalizedTicketNo, currentController.signal)
      if (disposed || currentRequest !== requestId) return
      ticket.value = result
      ticketNo.value = result.ticketNo
    } catch (queryError) {
      if (!disposed && currentRequest === requestId && !currentController.signal.aborted) {
        error.value = messageFor(queryError)
      }
    } finally {
      if (!disposed && currentRequest === requestId) loading.value = false
      if (controller === currentController) controller = null
    }
  }

  function retry() {
    void query()
  }

  onScopeDispose(() => {
    disposed = true
    requestId += 1
    controller?.abort()
  })

  return { ticketNo, ticket, loading, error, canQuery, query, retry }
}
