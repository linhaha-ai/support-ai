import { computed, onScopeDispose, ref } from 'vue'
import { createChatSession, type ChatSessionSummary } from './chatSession'
import { fetchKnowledgeBaseSummaries, type KnowledgeBaseSummary } from './knowledgeBase'

function messageFor(error: unknown): string {
  return typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string'
    ? error.message
    : '会话服务暂时不可用，请稍后重试。'
}

export function useChatSessions() {
  const knowledgeBases = ref<KnowledgeBaseSummary[]>([])
  const selectedKnowledgeBaseIds = ref<string[]>([])
  const createdSession = ref<ChatSessionSummary | null>(null)
  const loading = ref(false)
  const creating = ref(false)
  const loadError = ref('')
  const createError = ref('')
  let disposed = false
  let loadRequest = 0
  let createRequest = 0

  const canCreate = computed(() => selectedKnowledgeBaseIds.value.length > 0 && !creating.value)

  async function loadKnowledgeBases() {
    const request = ++loadRequest
    loading.value = true
    loadError.value = ''
    try {
      const items = await fetchKnowledgeBaseSummaries()
      if (disposed || request !== loadRequest) return
      knowledgeBases.value = items
      const allowedIds = new Set(items.map((item) => item.id))
      selectedKnowledgeBaseIds.value = selectedKnowledgeBaseIds.value.filter((id) => allowedIds.has(id))
    } catch (error) {
      if (!disposed && request === loadRequest) loadError.value = messageFor(error)
    } finally {
      if (!disposed && request === loadRequest) loading.value = false
    }
  }

  async function create() {
    if (!canCreate.value) return
    const request = ++createRequest
    creating.value = true
    createError.value = ''
    const knowledgeBaseIds = [...new Set(selectedKnowledgeBaseIds.value)]
    try {
      const session = await createChatSession(knowledgeBaseIds)
      if (!disposed && request === createRequest) createdSession.value = session
    } catch (error) {
      if (!disposed && request === createRequest) createError.value = messageFor(error)
    } finally {
      if (!disposed && request === createRequest) creating.value = false
    }
  }

  function startAnother() {
    createRequest += 1
    createdSession.value = null
    createError.value = ''
    selectedKnowledgeBaseIds.value = []
  }

  onScopeDispose(() => {
    disposed = true
    loadRequest += 1
    createRequest += 1
  })

  return {
    knowledgeBases,
    selectedKnowledgeBaseIds,
    createdSession,
    loading,
    creating,
    loadError,
    createError,
    canCreate,
    loadKnowledgeBases,
    create,
    startAnother,
  }
}
