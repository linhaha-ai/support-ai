import { computed, onScopeDispose, ref } from 'vue'
import {
  cancelChatRun,
  createChatSession,
  deleteChatSession,
  fetchChatMessages,
  fetchChatRun,
  fetchChatSessions,
  fetchDocumentSource,
  renameChatSession,
  submitChatMessage,
  type ChatAssistantStatus,
  type ChatRunStatusResponse,
  type ChatSessionMode,
  type ChatSessionSummary,
  type ChatHistoryMessage,
  type ChatSourceReference,
  type ChatStreamEvent,
  type ChatToolEvent,
  type DocumentSource,
} from './chatSession'
import { fetchKnowledgeBaseSummaries, type KnowledgeBaseSummary } from './knowledgeBase'

export interface ChatTranscriptMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: 'COMPLETED' | 'STREAMING' | 'BACKGROUND' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
  sources: ChatSourceReference[]
  tools: ChatToolEvent[]
  errorMessage: string
}

function transcriptMessageFor(message: ChatHistoryMessage, contentReadable: boolean): ChatTranscriptMessage {
  const isAssistant = message.role === 'ASSISTANT'
  return {
    id: message.messageId,
    role: isAssistant ? 'assistant' : 'user',
    content: contentReadable ? message.content ?? '' : '',
    status: isAssistant ? message.status : 'COMPLETED',
    sources: contentReadable && isAssistant ? message.sources : [],
    tools: [],
    errorMessage: contentReadable || !isAssistant ? '' : '当前资料授权已变化，回答正文不可读取。',
  }
}

function messageFor(error: unknown): string {
  return typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string'
    ? error.message
    : '会话服务暂时不可用，请稍后重试。'
}

export function useChatSessions() {
  const knowledgeBases = ref<KnowledgeBaseSummary[]>([])
  const selectedKnowledgeBaseIds = ref<string[]>([])
  const selectedMode = ref<ChatSessionMode>('RAG')
  const createdSession = ref<ChatSessionSummary | null>(null)
  const sessionItems = ref<ChatSessionSummary[]>([])
  const sessionsLoading = ref(false)
  const sessionsLoadingMore = ref(false)
  const sessionsError = ref('')
  const sessionsNextCursor = ref<string | null>(null)
  const historyLoading = ref(false)
  const historyLoadingMore = ref(false)
  const historyError = ref('')
  const historyNextCursor = ref<string | null>(null)
  const historyContentReadable = ref(true)
  const renaming = ref(false)
  const deleting = ref(false)
  const loading = ref(false)
  const creating = ref(false)
  const loadError = ref('')
  const createError = ref('')
  const messageInput = ref('')
  const messages = ref<ChatTranscriptMessage[]>([])
  const sending = ref(false)
  const sendError = ref('')
  const sendNotice = ref('')
  const activeRunId = ref<string | null>(null)
  const activeRunCancellable = ref(false)
  const backgroundProcessing = ref(false)
  const backgroundStatusUnknown = ref(false)
  const refreshingRun = ref(false)
  const cancellingRun = ref(false)
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
  let sessionListRequest = 0
  let historyRequest = 0
  let messageAbortController: AbortController | null = null
  let sourceAbortController: AbortController | null = null
  let sessionListAbortController: AbortController | null = null
  let historyAbortController: AbortController | null = null
  let activeQuestion = ''

  const canCreate = computed(() => selectedKnowledgeBaseIds.value.length > 0 && !creating.value)
  const canSend = computed(() => createdSession.value !== null && messageInput.value.trim().length > 0
    && historyContentReadable.value && !historyLoading.value && !historyLoadingMore.value
    && historyError.value === '' && !sending.value && !renaming.value && !deleting.value
    && historyNextCursor.value === null && activeRunId.value === null)
  const canStartAnother = computed(() => activeRunId.value === null && !sending.value && !cancellingRun.value
    && !creating.value && !renaming.value && !deleting.value)
  const canCancelRun = computed(() => activeRunId.value !== null && activeRunCancellable.value
    && !cancellingRun.value)
  const canLoadMoreSessions = computed(() => sessionsNextCursor.value !== null && !sessionsLoadingMore.value)
  const canLoadMoreMessages = computed(() => historyNextCursor.value !== null && !historyLoadingMore.value)
  const canManageSession = computed(() => createdSession.value !== null && canStartAnother.value && !renaming.value && !deleting.value)

  function messageStatusFor(run: ChatRunStatusResponse, isBackground: boolean): ChatTranscriptMessage['status'] {
    if (run.status === 'QUEUED' || run.status === 'RUNNING') return isBackground ? 'BACKGROUND' : 'STREAMING'
    const status: ChatAssistantStatus = run.assistantStatus
    return status
  }

  function clearActiveRun() {
    activeRunId.value = null
    activeRunCancellable.value = false
    activeQuestion = ''
    backgroundProcessing.value = false
    backgroundStatusUnknown.value = false
  }

  function applyRunStatus(run: ChatRunStatusResponse, question = activeQuestion, isBackground = false) {
    const existingAssistantIndex = messages.value.findIndex((message) => message.id === run.assistantMessageId)
    let assistantIndex = existingAssistantIndex
    if (assistantIndex < 0) {
      if (!messages.value.some((message) => message.id === run.userMessageId)) {
        messages.value.push({
          id: run.userMessageId,
          role: 'user',
          content: question || '已提交的问题',
          status: 'COMPLETED',
          sources: [],
          tools: [],
          errorMessage: '',
        })
      }
      messages.value.push({
        id: run.assistantMessageId,
        role: 'assistant',
        content: '',
        status: 'STREAMING',
        sources: [],
        tools: [],
        errorMessage: '',
      })
      assistantIndex = messages.value.length - 1
    }
    const assistant = messages.value[assistantIndex]
    if (!assistant) return
    assistant.content = run.contentReadable ? run.answer ?? '' : ''
    assistant.sources = run.contentReadable ? run.sources : []
    assistant.status = messageStatusFor(run, isBackground)
    assistant.errorMessage = run.contentReadable
      ? run.errorMessage ?? ''
      : '当前资料授权已变化，回答正文不可读取。'
    if (run.status === 'QUEUED' || run.status === 'RUNNING') {
      activeRunId.value = run.runId
      activeRunCancellable.value = run.cancellable
      activeQuestion = question || activeQuestion
      backgroundProcessing.value = isBackground
      backgroundStatusUnknown.value = false
    } else {
      clearActiveRun()
    }
  }

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

  function replaceSession(session: ChatSessionSummary) {
    const index = sessionItems.value.findIndex((item) => item.sessionId === session.sessionId)
    if (index >= 0) sessionItems.value.splice(index, 1, session)
    else sessionItems.value.unshift(session)
    sessionItems.value.sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
  }

  function redactLoadedMessages() {
    messages.value = messages.value.map((message) => ({
      ...message,
      content: '',
      sources: [],
      tools: [],
      errorMessage: message.role === 'assistant' ? '当前资料授权已变化，回答正文不可读取。' : '',
    }))
  }

  async function loadSessions(loadMore = false) {
    if (loadMore && (!sessionsNextCursor.value || sessionsLoadingMore.value)) return
    const request = ++sessionListRequest
    const controller = new AbortController()
    sessionListAbortController?.abort()
    sessionListAbortController = controller
    if (loadMore) sessionsLoadingMore.value = true
    else {
      sessionsLoading.value = true
      sessionsError.value = ''
    }
    try {
      const page = await fetchChatSessions(loadMore ? sessionsNextCursor.value : null, controller.signal)
      if (disposed || request !== sessionListRequest) return
      sessionItems.value = loadMore
        ? [...sessionItems.value, ...page.items.filter((item) => !sessionItems.value.some((current) => current.sessionId === item.sessionId))]
        : page.items
      sessionsNextCursor.value = page.nextCursor
    } catch (error) {
      if (!disposed && request === sessionListRequest && !controller.signal.aborted) sessionsError.value = messageFor(error)
    } finally {
      if (!disposed && request === sessionListRequest) {
        sessionsLoading.value = false
        sessionsLoadingMore.value = false
      }
      if (sessionListAbortController === controller) sessionListAbortController = null
    }
  }

  async function selectSession(session: ChatSessionSummary, forceReload = false) {
    if (!canStartAnother.value || (!forceReload && createdSession.value?.sessionId === session.sessionId)) return
    const request = ++historyRequest
    const controller = new AbortController()
    historyAbortController?.abort()
    historyAbortController = controller
    sourceAbortController?.abort()
    sourceRequest += 1
    sourceDrawerOpen.value = false
    selectedSource.value = null
    sourceDetail.value = null
    sourceError.value = ''
    createdSession.value = session
    messages.value = []
    historyNextCursor.value = null
    historyContentReadable.value = session.contentReadable
    historyError.value = ''
    sendError.value = ''
    sendNotice.value = ''
    historyLoading.value = true
    try {
      const page = await fetchChatMessages(session.sessionId, null, controller.signal)
      if (disposed || request !== historyRequest || createdSession.value?.sessionId !== session.sessionId) return
      historyContentReadable.value = page.contentReadable
      messages.value = page.items.map((item) => transcriptMessageFor(item, page.contentReadable))
      historyNextCursor.value = page.nextCursor
      const selected = { ...session, contentReadable: page.contentReadable }
      createdSession.value = selected
      replaceSession(selected)
    } catch (error) {
      if (!disposed && request === historyRequest && !controller.signal.aborted) historyError.value = messageFor(error)
    } finally {
      if (!disposed && request === historyRequest) historyLoading.value = false
      if (historyAbortController === controller) historyAbortController = null
    }
  }

  async function retryHistory() {
    const session = createdSession.value
    if (!session) return
    await selectSession(session, true)
  }

  async function loadMoreMessages() {
    const session = createdSession.value
    if (!session || !historyNextCursor.value || historyLoadingMore.value || activeRunId.value) return
    const request = ++historyRequest
    const controller = new AbortController()
    historyAbortController?.abort()
    historyAbortController = controller
    historyLoadingMore.value = true
    historyError.value = ''
    try {
      const page = await fetchChatMessages(session.sessionId, historyNextCursor.value, controller.signal)
      if (disposed || request !== historyRequest || createdSession.value?.sessionId !== session.sessionId) return
      historyContentReadable.value = page.contentReadable
      if (!page.contentReadable) {
        redactLoadedMessages()
        const restrictedSession = { ...session, contentReadable: false }
        createdSession.value = restrictedSession
        replaceSession(restrictedSession)
      }
      messages.value.push(...page.items.map((item) => transcriptMessageFor(item, page.contentReadable)))
      historyNextCursor.value = page.nextCursor
    } catch (error) {
      if (!disposed && request === historyRequest && !controller.signal.aborted) historyError.value = messageFor(error)
    } finally {
      if (!disposed && request === historyRequest) historyLoadingMore.value = false
      if (historyAbortController === controller) historyAbortController = null
    }
  }

  async function create() {
    if (!canCreate.value) return
    const request = ++createRequest
    creating.value = true
    createError.value = ''
    const knowledgeBaseIds = [...new Set(selectedKnowledgeBaseIds.value)]
    try {
      const session = await createChatSession(knowledgeBaseIds, selectedMode.value)
      if (!disposed && request === createRequest) {
        createdSession.value = session
        historyContentReadable.value = true
        replaceSession(session)
      }
    } catch (error) {
      if (!disposed && request === createRequest) createError.value = messageFor(error)
    } finally {
      if (!disposed && request === createRequest) creating.value = false
    }
  }

  function startAnother() {
    if (!canStartAnother.value) return
    messageAbortController?.abort()
    sourceAbortController?.abort()
    historyAbortController?.abort()
    createRequest += 1
    messageRequest += 1
    sourceRequest += 1
    historyRequest += 1
    createdSession.value = null
    createError.value = ''
    selectedKnowledgeBaseIds.value = []
    selectedMode.value = 'RAG'
    messageInput.value = ''
    messages.value = []
    sending.value = false
    sendError.value = ''
    sendNotice.value = ''
    clearActiveRun()
    refreshingRun.value = false
    cancellingRun.value = false
    sourceDrawerOpen.value = false
    selectedSource.value = null
    sourceDetail.value = null
    sourceError.value = ''
    historyError.value = ''
    historyNextCursor.value = null
    historyContentReadable.value = true
  }

  async function renameCurrentSession(title: string) {
    const session = createdSession.value
    const normalizedTitle = title.trim()
    if (!session || !normalizedTitle || !canManageSession.value) return
    renaming.value = true
    try {
      const updated = await renameChatSession(session.sessionId, normalizedTitle)
      if (disposed || createdSession.value?.sessionId !== session.sessionId) return
      createdSession.value = updated
      replaceSession(updated)
    } catch (error) {
      if (!disposed) historyError.value = messageFor(error)
    } finally {
      if (!disposed) renaming.value = false
    }
  }

  async function deleteCurrentSession() {
    const session = createdSession.value
    if (!session || !canManageSession.value) return
    deleting.value = true
    try {
      await deleteChatSession(session.sessionId)
      if (disposed || createdSession.value?.sessionId !== session.sessionId) return
      sessionItems.value = sessionItems.value.filter((item) => item.sessionId !== session.sessionId)
      deleting.value = false
      startAnother()
    } catch (error) {
      if (!disposed) historyError.value = messageFor(error)
    } finally {
      if (!disposed) deleting.value = false
    }
  }

  function handleStreamEvent(question: string, event: ChatStreamEvent, assistantIndex: { value: number }) {
    if (event.event === 'start') {
      messages.value.push({
        id: crypto.randomUUID(), role: 'user', content: question, status: 'COMPLETED', sources: [], tools: [],
        errorMessage: '',
      })
      messages.value.push({
        id: event.data.assistantMessageId,
        role: 'assistant',
        content: '',
        status: 'STREAMING',
        sources: [],
        tools: [],
        errorMessage: '',
      })
      assistantIndex.value = messages.value.length - 1
      activeRunId.value = event.data.runId
      activeRunCancellable.value = true
      activeQuestion = question
      backgroundProcessing.value = false
      backgroundStatusUnknown.value = false
      messageInput.value = ''
      return
    }
    const assistant = messages.value[assistantIndex.value]
    if (!assistant) return
    if (event.event === 'tool') {
      const runningIndex = assistant.tools.map((tool) => tool.name === event.data.name
        && tool.status === 'STARTED').lastIndexOf(true)
      if (event.data.status !== 'STARTED' && runningIndex >= 0) assistant.tools.splice(runningIndex, 1, event.data)
      else assistant.tools.push(event.data)
    } else if (event.event === 'delta') {
      assistant.content += event.data.text
    } else if (event.event === 'sources') {
      assistant.sources = event.data.sources
    } else if (event.event === 'done') {
      assistant.status = 'COMPLETED'
      clearActiveRun()
    } else {
      assistant.status = event.data.status
      assistant.errorMessage = event.data.message
      clearActiveRun()
    }
  }

  async function refreshRunStatus(isBackground = true, resetError = true): Promise<ChatRunStatusResponse | null> {
    const runId = activeRunId.value
    if (!runId || refreshingRun.value) return null
    refreshingRun.value = true
    if (resetError) sendError.value = ''
    try {
      const run = await fetchChatRun(runId)
      if (disposed || activeRunId.value !== runId) return null
      applyRunStatus(run, activeQuestion, isBackground)
      if (run.status === 'QUEUED' || run.status === 'RUNNING') {
        sendNotice.value = ''
      } else if (run.status === 'CANCELLED') {
        sendNotice.value = '回答已停止，已展示已保存的结果。'
      } else if (run.status === 'COMPLETED') {
        sendNotice.value = '回答已完成，已同步已保存的结果。'
      }
      return run
    } catch (error) {
      if (!disposed && activeRunId.value === runId) {
        sendError.value = messageFor(error)
        backgroundProcessing.value = true
        backgroundStatusUnknown.value = true
      }
      return null
    } finally {
      if (!disposed) refreshingRun.value = false
    }
  }

  async function send() {
    const session = createdSession.value
    const question = messageInput.value.trim()
    if (!session || !question || sending.value || activeRunId.value !== null) return
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
        activeRunId.value = result.run.runId
        activeQuestion = question
        messageInput.value = ''
        const run = await refreshRunStatus(true)
        if (run === null && !disposed && activeRunId.value === result.run.runId) {
          sendNotice.value = `该请求已存在，当前状态为 ${result.run.status}。`
        }
      }
    } catch (error) {
      if (disposed || request !== messageRequest || controller.signal.aborted) return
      if (activeRunId.value) {
        const recovered = await refreshRunStatus(true)
        if (recovered) return
        if (activeRunId.value) {
          backgroundProcessing.value = true
          backgroundStatusUnknown.value = true
          const assistant = messages.value[assistantIndex.value]
          if (assistant) {
            assistant.status = 'BACKGROUND'
            assistant.errorMessage = '回答连接中断，暂时无法确认后台运行状态。'
          }
          return
        }
      }
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

  async function cancelRun() {
    const runId = activeRunId.value
    if (!runId || !activeRunCancellable.value || cancellingRun.value) return
    const wasCancellable = activeRunCancellable.value
    activeRunCancellable.value = false
    cancellingRun.value = true
    messageAbortController?.abort()
    messageAbortController = null
    messageRequest += 1
    sending.value = false
    sendError.value = ''
    sendNotice.value = '正在停止生成并同步运行状态。'
    let cancelError = ''
    try {
      await cancelChatRun(runId)
    } catch (error) {
      cancelError = messageFor(error)
      if (!disposed && activeRunId.value === runId) sendError.value = cancelError
    } finally {
      let refreshed: ChatRunStatusResponse | null = null
      if (!disposed && activeRunId.value === runId) refreshed = await refreshRunStatus(true, false)
      if (!disposed && cancelError) {
        sendError.value = cancelError
        if (!refreshed && activeRunId.value === runId) activeRunCancellable.value = wasCancellable
      }
      if (!disposed) cancellingRun.value = false
    }
  }

  async function openSource(source: ChatSourceReference) {
    if (source.type !== 'DOCUMENT' || !source.available || !source.previewPath) return
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
    sessionListAbortController?.abort()
    historyAbortController?.abort()
    loadRequest += 1
    createRequest += 1
    messageRequest += 1
    sourceRequest += 1
    sessionListRequest += 1
    historyRequest += 1
  })

  return {
    knowledgeBases,
    selectedKnowledgeBaseIds,
    selectedMode,
    createdSession,
    sessionItems,
    sessionsLoading,
    sessionsLoadingMore,
    sessionsError,
    sessionsNextCursor,
    historyLoading,
    historyLoadingMore,
    historyError,
    historyNextCursor,
    historyContentReadable,
    renaming,
    deleting,
    loading,
    creating,
    loadError,
    createError,
    messageInput,
    messages,
    sending,
    sendError,
    sendNotice,
    activeRunId,
    activeRunCancellable,
    backgroundProcessing,
    backgroundStatusUnknown,
    refreshingRun,
    cancellingRun,
    sourceDrawerOpen,
    selectedSource,
    sourceDetail,
    sourceLoading,
    sourceError,
    canCreate,
    canSend,
    canStartAnother,
    canCancelRun,
    canLoadMoreSessions,
    canLoadMoreMessages,
    canManageSession,
    loadKnowledgeBases,
    loadSessions,
    selectSession,
    retryHistory,
    loadMoreMessages,
    create,
    startAnother,
    renameCurrentSession,
    deleteCurrentSession,
    send,
    refreshRunStatus,
    cancelRun,
    openSource,
    retrySource,
    closeSource,
  }
}
