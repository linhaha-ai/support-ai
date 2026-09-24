import { computed, onScopeDispose, ref } from 'vue'
import {
  cancelIngestionJob,
  deleteKnowledgeDocument,
  fetchDocument,
  fetchDocumentChunks,
  fetchDocumentContent,
  fetchDocuments,
  fetchDocumentVersions,
  retryIngestionJob,
  type DocumentChunk,
  type DocumentContent,
  type DocumentDetail,
  type DocumentIngestionJob,
  type DocumentSummary,
  type DocumentVersionSummary,
} from './document'

function messageFor(error: unknown): string {
  return typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string'
    ? error.message
    : '文档信息暂时无法读取，请稍后重试。'
}

export function useDocuments(knowledgeBaseId: () => string) {
  const documents = ref<DocumentSummary[]>([])
  const selectedDocumentId = ref<string | null>(null)
  const selectedDocument = ref<DocumentDetail | null>(null)
  const versions = ref<DocumentVersionSummary[]>([])
  const selectedVersion = ref<DocumentVersionSummary | null>(null)
  const content = ref<DocumentContent | null>(null)
  const chunks = ref<DocumentChunk[]>([])
  const documentsLoading = ref(false)
  const detailLoading = ref(false)
  const versionsLoading = ref(false)
  const contentLoading = ref(false)
  const chunksLoading = ref(false)
  const documentsError = ref('')
  const detailError = ref('')
  const contentError = ref('')
  const chunksError = ref('')
  const taskActionError = ref('')
  const taskAction = ref<'retry' | 'cancel' | null>(null)
  const taskActionJobId = ref<string | null>(null)
  const deleting = ref(false)
  const deleteError = ref('')
  const deleteNotice = ref('')
  const nextVersionCursor = ref<string | null>(null)
  const nextChunkCursor = ref<string | null>(null)
  let disposed = false
  let documentsRequest = 0
  let detailRequest = 0
  let versionRequest = 0
  let contentRequest = 0
  let chunkRequest = 0
  let taskActionRequest = 0

  const canLoadMoreVersions = computed(() => nextVersionCursor.value !== null && !versionsLoading.value)
  const canLoadMoreChunks = computed(() => nextChunkCursor.value !== null && !chunksLoading.value)

  function resetSelection() {
    selectedDocumentId.value = null
    selectedDocument.value = null
    versions.value = []
    selectedVersion.value = null
    content.value = null
    chunks.value = []
    detailError.value = ''
    contentError.value = ''
    chunksError.value = ''
    taskActionError.value = ''
    nextVersionCursor.value = null
    nextChunkCursor.value = null
    detailRequest += 1
    versionRequest += 1
    contentRequest += 1
    chunkRequest += 1
    taskActionRequest += 1
    taskAction.value = null
    taskActionJobId.value = null
  }

  async function loadDocuments() {
    const request = ++documentsRequest
    documentsLoading.value = true
    documentsError.value = ''
    try {
      const items = await fetchDocuments(knowledgeBaseId())
      if (!disposed && request === documentsRequest) documents.value = items
    } catch (error) {
      if (!disposed && request === documentsRequest) documentsError.value = messageFor(error)
    } finally {
      if (!disposed && request === documentsRequest) documentsLoading.value = false
    }
  }

  async function selectDocument(documentId: string) {
    const request = ++detailRequest
    selectedDocumentId.value = documentId
    detailLoading.value = true
    detailError.value = ''
    deleteError.value = ''
    deleteNotice.value = ''
    taskActionError.value = ''
    selectedDocument.value = null
    versions.value = []
    selectedVersion.value = null
    content.value = null
    chunks.value = []
    nextVersionCursor.value = null
    nextChunkCursor.value = null
    versionRequest += 1
    contentRequest += 1
    chunkRequest += 1
    try {
      const detail = await fetchDocument(documentId)
      if (disposed || request !== detailRequest) return
      selectedDocument.value = detail
      await loadVersions(true)
    } catch (error) {
      if (!disposed && request === detailRequest) detailError.value = messageFor(error)
    } finally {
      if (!disposed && request === detailRequest) detailLoading.value = false
    }
  }

  async function loadVersions(reset = false) {
    const documentId = selectedDocument.value?.documentId
    if (!documentId || !reset && versionsLoading.value) return
    const request = ++versionRequest
    const cursor = reset ? null : nextVersionCursor.value
    if (!reset && !cursor) return
    versionsLoading.value = true
    try {
      const page = await fetchDocumentVersions(documentId, 20, cursor)
      if (disposed || request !== versionRequest || selectedDocument.value?.documentId !== documentId) return
      versions.value = reset ? page.items : [...versions.value, ...page.items]
      nextVersionCursor.value = page.nextCursor
      if (reset && page.items.length > 0) await selectVersion(page.items[0])
    } catch (error) {
      if (!disposed && request === versionRequest) detailError.value = messageFor(error)
    } finally {
      if (!disposed && request === versionRequest) versionsLoading.value = false
    }
  }

  async function selectVersion(version: DocumentVersionSummary) {
    const documentId = selectedDocument.value?.documentId
    if (!documentId) return
    selectedVersion.value = version
    content.value = null
    chunks.value = []
    contentError.value = ''
    chunksError.value = ''
    taskActionError.value = ''
    nextChunkCursor.value = null
    const selection = `${documentId}:${version.versionId}`
    contentRequest += 1
    chunkRequest += 1
    await Promise.all([loadContent(selection), loadChunks(true, selection)])
  }

  async function loadContent(selection = selectedDocument.value && selectedVersion.value
    ? `${selectedDocument.value.documentId}:${selectedVersion.value.versionId}` : '') {
    const documentId = selectedDocument.value?.documentId
    const versionId = selectedVersion.value?.versionId
    if (!documentId || !versionId) return
    const request = ++contentRequest
    contentLoading.value = true
    contentError.value = ''
    try {
      const item = await fetchDocumentContent(documentId, versionId)
      if (!disposed && request === contentRequest && selection === `${selectedDocument.value?.documentId}:${selectedVersion.value?.versionId}`) content.value = item
    } catch (error) {
      if (!disposed && request === contentRequest) contentError.value = messageFor(error)
    } finally {
      if (!disposed && request === contentRequest) contentLoading.value = false
    }
  }

  async function loadChunks(reset = false, selection = selectedDocument.value && selectedVersion.value
    ? `${selectedDocument.value.documentId}:${selectedVersion.value.versionId}` : '') {
    const documentId = selectedDocument.value?.documentId
    const versionId = selectedVersion.value?.versionId
    if (!documentId || !versionId || !reset && chunksLoading.value) return
    const cursor = reset ? null : nextChunkCursor.value
    if (!reset && !cursor) return
    const request = ++chunkRequest
    chunksLoading.value = true
    chunksError.value = ''
    try {
      const page = await fetchDocumentChunks(documentId, versionId, 50, cursor)
      if (disposed || request !== chunkRequest || selection !== `${selectedDocument.value?.documentId}:${selectedVersion.value?.versionId}`) return
      chunks.value = reset ? page.items : [...chunks.value, ...page.items]
      nextChunkCursor.value = page.nextCursor
    } catch (error) {
      if (!disposed && request === chunkRequest) chunksError.value = messageFor(error)
    } finally {
      if (!disposed && request === chunkRequest) chunksLoading.value = false
    }
  }

  async function deleteSelectedDocument(): Promise<boolean> {
    const documentId = selectedDocument.value?.documentId
    if (!documentId || deleting.value) return false
    deleting.value = true
    deleteError.value = ''
    deleteNotice.value = ''
    try {
      await deleteKnowledgeDocument(documentId)
      if (disposed) return false
      if (selectedDocument.value?.documentId === documentId) resetSelection()
      deleteNotice.value = '文档已立即退出检索，关联文件和正文正在受控清理。'
      await loadDocuments()
      return true
    } catch (error) {
      if (!disposed && selectedDocument.value?.documentId === documentId) deleteError.value = messageFor(error)
      return false
    } finally {
      if (!disposed) deleting.value = false
    }
  }

  function applyTaskAction(version: DocumentVersionSummary, job: DocumentIngestionJob,
    action: 'retry' | 'cancel') {
    const pending = action === 'retry'
    const status = action === 'cancel' ? 'CANCELLED' : job.stage === 'PARSE' ? 'QUEUED' : 'BUILDING'
    const updatedVersion: DocumentVersionSummary = {
      ...version,
      status,
      pending,
      task: job,
      updatedAt: job.updatedAt,
    }
    versions.value = versions.value.map((item) => item.versionId === version.versionId ? updatedVersion : item)
    if (selectedVersion.value?.versionId === version.versionId) selectedVersion.value = updatedVersion

    const currentDocument = selectedDocument.value
    if (!currentDocument) return
    const documentId = currentDocument.documentId
    const updateDocument = <T extends DocumentSummary | DocumentDetail>(document: T): T => {
      const pointerChanges = action === 'retry'
        ? document.pendingVersion?.versionId !== version.versionId
        : document.pendingVersion?.versionId === version.versionId
      const pendingVersion = action === 'retry'
        ? updatedVersion
        : document.pendingVersion?.versionId === version.versionId ? null : document.pendingVersion
      return {
        ...document,
        pendingVersion,
        revision: document.revision + (pointerChanges ? 1 : 0),
        updatedAt: job.updatedAt,
      }
    }
    selectedDocument.value = updateDocument(currentDocument)
    documents.value = documents.value.map((document) => document.documentId === documentId
      ? updateDocument(document)
      : document)
  }

  async function runTaskAction(version: DocumentVersionSummary, action: 'retry' | 'cancel'): Promise<boolean> {
    const job = version.task
    if (!job || taskAction.value !== null) return false
    const request = ++taskActionRequest
    taskAction.value = action
    taskActionJobId.value = job.jobId
    taskActionError.value = ''
    try {
      const updatedJob = action === 'retry'
        ? await retryIngestionJob(job.jobId)
        : await cancelIngestionJob(job.jobId)
      if (disposed || request !== taskActionRequest) return false
      applyTaskAction(version, updatedJob, action)
      return true
    } catch (error) {
      if (!disposed && request === taskActionRequest) taskActionError.value = messageFor(error)
      return false
    } finally {
      if (!disposed && request === taskActionRequest) {
        taskAction.value = null
        taskActionJobId.value = null
      }
    }
  }

  function retryTask(version: DocumentVersionSummary): Promise<boolean> {
    return runTaskAction(version, 'retry')
  }

  function cancelTask(version: DocumentVersionSummary): Promise<boolean> {
    return runTaskAction(version, 'cancel')
  }

  onScopeDispose(() => {
    disposed = true
    documentsRequest += 1
    detailRequest += 1
    versionRequest += 1
    contentRequest += 1
    chunkRequest += 1
    taskActionRequest += 1
  })

  return {
    documents, selectedDocumentId, selectedDocument, versions, selectedVersion, content, chunks,
    documentsLoading, detailLoading, versionsLoading, contentLoading, chunksLoading,
    documentsError, detailError, contentError, chunksError, taskActionError, taskAction, taskActionJobId,
    deleting, deleteError, deleteNotice,
    canLoadMoreVersions, canLoadMoreChunks,
    loadDocuments, selectDocument, resetSelection, loadVersions, selectVersion, loadContent, loadChunks,
    deleteSelectedDocument, retryTask, cancelTask,
  }
}
