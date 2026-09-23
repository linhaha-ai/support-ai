import { onScopeDispose, reactive } from 'vue'
import {
  fetchIngestionJob,
  uploadDocumentVersion,
  uploadKnowledgeDocument,
  type DocumentVersionUploadReceipt,
  type DocumentUploadReceipt,
  type IngestionJobDetail,
} from './knowledgeBase'

const MAX_DOCUMENT_SIZE_BYTES = 5 * 1024 * 1024
const SUPPORTED_DOCUMENT_EXTENSIONS = ['.md', '.markdown', '.txt']
export const INGESTION_JOB_POLL_INTERVAL_MS = 2_000

export interface DocumentUploadState {
  displayName: string
  file: File | null
  submitting: boolean
  error: string
  receipt: DocumentUploadReceipt | DocumentVersionUploadReceipt | null
  job: IngestionJobDetail | null
  jobLoading: boolean
  jobError: string
}

function messageFor(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string') {
    return error.message
  }
  return '文档上传未完成，请稍后重试。'
}

function isSupportedDocument(file: File): boolean {
  const filename = file.name.toLowerCase()
  return SUPPORTED_DOCUMENT_EXTENSIONS.some((extension) => filename.endsWith(extension))
}

export function useDocumentUpload() {
  const states = reactive<Record<string, DocumentUploadState>>({})
  const pollTimers = new Map<string, ReturnType<typeof setTimeout>>()
  const pollingJobKeys = new Set<string>()
  const pollVersions = new Map<string, number>()
  let disposed = false

  function stateFor(knowledgeBaseId: string): DocumentUploadState {
    if (!states[knowledgeBaseId]) {
      states[knowledgeBaseId] = {
        displayName: '',
        file: null,
        submitting: false,
        error: '',
        receipt: null,
        job: null,
        jobLoading: false,
        jobError: '',
      }
    }
    return states[knowledgeBaseId]
  }

  function selectFile(knowledgeBaseId: string, file: File | null): boolean {
    const state = stateFor(knowledgeBaseId)
    state.error = ''
    if (!file) {
      state.file = null
      return false
    }
    if (!isSupportedDocument(file)) {
      state.file = null
      state.error = '仅支持 Markdown（.md、.markdown）或 TXT（.txt）文件。'
      return false
    }
    if (file.size > MAX_DOCUMENT_SIZE_BYTES) {
      state.file = null
      state.error = '文件超过 5 MB 限制，请压缩或拆分后重试。'
      return false
    }
    state.file = file
    return true
  }

  function isTerminalJob(job: IngestionJobDetail): boolean {
    return job.status === 'SUCCEEDED' || job.status === 'FAILED' || job.status === 'CANCELLED'
  }

  function clearPollTimer(knowledgeBaseId: string) {
    const timer = pollTimers.get(knowledgeBaseId)
    if (timer) clearTimeout(timer)
    pollTimers.delete(knowledgeBaseId)
  }

  function stopPolling(knowledgeBaseId: string) {
    clearPollTimer(knowledgeBaseId)
    pollVersions.set(knowledgeBaseId, (pollVersions.get(knowledgeBaseId) ?? 0) + 1)
  }

  function schedulePoll(knowledgeBaseId: string, jobId: string, version: number) {
    clearPollTimer(knowledgeBaseId)
    if (disposed || pollVersions.get(knowledgeBaseId) !== version) return
    pollTimers.set(knowledgeBaseId, setTimeout(() => {
      pollTimers.delete(knowledgeBaseId)
      void refreshJob(knowledgeBaseId, jobId, version)
    }, INGESTION_JOB_POLL_INTERVAL_MS))
  }

  async function refreshJob(knowledgeBaseId: string, jobId?: string, pollVersion?: number) {
    const state = stateFor(knowledgeBaseId)
    const currentJobId = jobId ?? state.receipt?.jobId
    const pollingJobKey = currentJobId ? `${knowledgeBaseId}:${currentJobId}` : ''
    if (!currentJobId || pollingJobKeys.has(pollingJobKey)) return

    const version = pollVersion ?? pollVersions.get(knowledgeBaseId) ?? 0
    if (pollVersions.get(knowledgeBaseId) !== version) return
    pollingJobKeys.add(pollingJobKey)
    state.jobLoading = true
    state.jobError = ''
    try {
      const job = await fetchIngestionJob(currentJobId)
      if (disposed || pollVersions.get(knowledgeBaseId) !== version || state.receipt?.jobId !== currentJobId) return
      state.job = job
      if (!isTerminalJob(job)) schedulePoll(knowledgeBaseId, currentJobId, version)
    } catch (error) {
      if (!disposed && pollVersions.get(knowledgeBaseId) === version && state.receipt?.jobId === currentJobId) {
        state.jobError = messageFor(error)
      }
    } finally {
      pollingJobKeys.delete(pollingJobKey)
      if (!disposed && pollVersions.get(knowledgeBaseId) === version && state.receipt?.jobId === currentJobId) {
        state.jobLoading = false
      }
    }
  }

  function startPolling(knowledgeBaseId: string, receipt: DocumentUploadReceipt | DocumentVersionUploadReceipt) {
    stopPolling(knowledgeBaseId)
    const version = pollVersions.get(knowledgeBaseId) ?? 0
    const state = stateFor(knowledgeBaseId)
    state.job = null
    state.jobError = ''
    void refreshJob(knowledgeBaseId, receipt.jobId, version)
  }

  async function submitFile<T extends DocumentUploadReceipt | DocumentVersionUploadReceipt>(
    scopeId: string,
    request: (file: File, state: DocumentUploadState) => Promise<T>,
  ): Promise<T | null> {
    const state = stateFor(scopeId)
    if (state.submitting) return null
    const file = state.file
    if (!file) {
      state.error = '请选择 Markdown 或 TXT 文件后再上传。'
      return null
    }
    state.submitting = true
    state.error = ''
    state.receipt = null
    stopPolling(scopeId)
    state.job = null
    state.jobError = ''
    try {
      const receipt = await request(file, state)
      if (!disposed) {
        state.receipt = receipt
        state.file = null
        state.displayName = ''
        startPolling(scopeId, receipt)
      }
      return receipt
    } catch (error) {
      if (!disposed) state.error = messageFor(error)
      return null
    } finally {
      if (!disposed) state.submitting = false
    }
  }

  function submit(knowledgeBaseId: string): Promise<DocumentUploadReceipt | null> {
    return submitFile(knowledgeBaseId, (file, state) => uploadKnowledgeDocument(
      knowledgeBaseId,
      file,
      state.displayName.trim(),
    ))
  }

  function submitVersion(documentId: string, expectedRevision: number): Promise<DocumentVersionUploadReceipt | null> {
    return submitFile(documentId, (file) => uploadDocumentVersion(documentId, file, expectedRevision))
  }

  onScopeDispose(() => {
    disposed = true
    for (const timer of pollTimers.values()) clearTimeout(timer)
    pollTimers.clear()
    pollVersions.clear()
  })

  return {
    states,
    stateFor,
    selectFile,
    submit,
    submitVersion,
    refreshJob,
  }
}
