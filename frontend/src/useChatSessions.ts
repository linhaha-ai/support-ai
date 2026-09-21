import { computed, onScopeDispose, ref } from 'vue'
import {
  createChatSession,
  fetchDocumentSource,
  submitChatMessage,
  type ChatSessionSummary,
  type ChatSourceReference,
  type ChatStreamEvent,
  type DocumentSource,
} from './chatSession'
import { fetchKnowledgeBaseSummaries, type KnowledgeBaseSummary } from './knowledgeBase'

export interface ChatTranscriptMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: 'COMPLETED' | 'STREAMING' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
  sources: ChatSourceReference[]
  errorMessage: string
}

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
  const messageInput = ref('')
  const messages = ref<ChatTranscriptMessage[]>([])
  const sending = ref(false)
  const sendError = ref('')
  const sendNotice = ref('')
  const sourceDrawerOpen = ref(false)
  const selectedSource = ref<ChatSourceReference | null>(null)
  const sourceDetail = ref<DocumentSource | null>(null)
  const sourceLoading = ref(false)
  const sourceError = ref('')
  let disposed = false
  let loadRequest = 0
  let createRequest = 0
  let messageRequest = 0
  let sourceRequest = 0
  let messageAbortController: AbortController | null = null
  let sourceAbortController: AbortController | null = null

  const canCreate = computed(() => selectedKnowledgeBaseIds.value.length > 0 && !creating.value)
  const canSend = computed(() => createdSession.value !== null && messageInput.value.trim().length > 0
    && !sending.value)

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
    messageAbortController?.abort()
    sourceAbortController?.abort()
    createRequest += 1
    messageRequest += 1
    sourceRequest += 1
    createdSession.value = null
    createError.value = ''
    selectedKnowledgeBaseIds.value = []
    messageInput.value = ''
    messages.value = []
    sending.value = false
    sendError.value = ''
    sendNotice.value = ''
    sourceDrawerOpen.value = false
    selectedSource.value = null
    sourceDetail.value = null
    sourceError.value = ''
  }

  function handleStreamEvent(question: string, event: ChatStreamEvent, assistantIndex: { value: number }) {
    if (event.event === 'start') {
      messages.value.push({
        id: crypto.randomUUID(), role: 'user', content: question, status: 'COMPLETED', sources: [], errorMessage: '',
      })
      messages.value.push({
        id: event.data.assistantMessageId,
        role: 'assistant',
        content: '',
        status: 'STREAMING',
        sources: [],
        errorMessage: '',
      })
      assistantIndex.value = messages.value.length - 1
      messageInput.value = ''
      return
    }
    const assistant = messages.value[assistantIndex.value]
    if (!assistant) return
    if (event.event === 'delta') {
      assistant.content += event.data.text
    } else if (event.event === 'sources') {
      assistant.sources = event.data.sources
    } else if (event.event === 'done') {
      assistant.status = 'COMPLETED'
    } else {
      assistant.status = event.data.status
      assistant.errorMessage = event.data.message
    }
  }

  async function send() {
    const session = createdSession.value
    const question = messageInput.value.trim()
    if (!session || !question || sending.value) return
    const request = ++messageRequest
    const assistantIndex = { value: -1 }
    const controller = new AbortController()
    messageAbortController?.abort()
    messageAbortController = controller
    sending.value = true
    sendError.value = ''
    sendNotice.value = ''
    try {
      const result = await submitChatMessage(session.sessionId, crypto.randomUUID(), question,
        (event) => handleStreamEvent(question, event, assistantIndex), controller.signal)
      if (disposed || request !== messageRequest) return
      if (result.kind === 'reused') {
        sendNotice.value = `该请求已存在，当前状态为 ${result.run.status}。`
      }
    } catch (error) {
      if (disposed || request !== messageRequest || controller.signal.aborted) return
      const assistant = messages.value[assistantIndex.value]
      if (assistant) {
        assistant.status = 'INTERRUPTED'
        assistant.errorMessage = '回答连接中断，已保留接收到的部分内容。'
      }
      sendError.value = messageFor(error)
    } finally {
      if (!disposed && request === messageRequest) sending.value = false
      if (messageAbortController === controller) messageAbortController = null
    }
  }

  async function openSource(source: ChatSourceReference) {
    const request = ++sourceRequest
    const controller = new AbortController()
    sourceAbortController?.abort()
    sourceAbortController = controller
    selectedSource.value = source
    sourceDetail.value = null
    sourceError.value = ''
    sourceLoading.value = true
    sourceDrawerOpen.value = true
    try {
      const detail = await fetchDocumentSource(source.chunkId, controller.signal)
      if (!disposed && request === sourceRequest) sourceDetail.value = detail
    } catch (error) {
      if (!disposed && request === sourceRequest && !controller.signal.aborted) sourceError.value = messageFor(error)
    } finally {
      if (!disposed && request === sourceRequest) sourceLoading.value = false
      if (sourceAbortController === controller) sourceAbortController = null
    }
  }

  function retrySource() {
    if (selectedSource.value) void openSource(selectedSource.value)
  }

  function closeSource() {
    sourceAbortController?.abort()
    sourceRequest += 1
    sourceDrawerOpen.value = false
    sourceLoading.value = false
  }

  onScopeDispose(() => {
    disposed = true
    messageAbortController?.abort()
    sourceAbortController?.abort()
    loadRequest += 1
    createRequest += 1
    messageRequest += 1
    sourceRequest += 1
  })

  return {
    knowledgeBases,
    selectedKnowledgeBaseIds,
    createdSession,
    loading,
    creating,
    loadError,
    createError,
    messageInput,
    messages,
    sending,
    sendError,
    sendNotice,
    sourceDrawerOpen,
    selectedSource,
    sourceDetail,
    sourceLoading,
    sourceError,
    canCreate,
    canSend,
    loadKnowledgeBases,
    create,
    startAnother,
    send,
    openSource,
    retrySource,
    closeSource,
  }
}
