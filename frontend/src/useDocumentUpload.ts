import { onScopeDispose, reactive } from 'vue'
import { uploadKnowledgeDocument, type DocumentUploadReceipt } from './knowledgeBase'

const MAX_DOCUMENT_SIZE_BYTES = 5 * 1024 * 1024
const SUPPORTED_DOCUMENT_EXTENSIONS = ['.md', '.markdown', '.txt']

export interface DocumentUploadState {
  displayName: string
  file: File | null
  submitting: boolean
  error: string
  receipt: DocumentUploadReceipt | null
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
  let disposed = false

  function stateFor(knowledgeBaseId: string): DocumentUploadState {
    if (!states[knowledgeBaseId]) {
      states[knowledgeBaseId] = {
        displayName: '',
        file: null,
        submitting: false,
        error: '',
        receipt: null,
      }
    }
    return states[knowledgeBaseId]
  }

  function selectFile(knowledgeBaseId: string, file: File | null): boolean {
    const state = stateFor(knowledgeBaseId)
    state.error = ''
    state.receipt = null
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

  async function submit(knowledgeBaseId: string): Promise<DocumentUploadReceipt | null> {
    const state = stateFor(knowledgeBaseId)
    if (state.submitting) return null
    if (!state.file) {
      state.error = '请选择 Markdown 或 TXT 文件后再上传。'
      return null
    }
    state.submitting = true
    state.error = ''
    state.receipt = null
    try {
      const receipt = await uploadKnowledgeDocument(knowledgeBaseId, state.file, state.displayName.trim())
      if (!disposed) {
        state.receipt = receipt
        state.file = null
        state.displayName = ''
      }
      return receipt
    } catch (error) {
      if (!disposed) state.error = messageFor(error)
      return null
    } finally {
      if (!disposed) state.submitting = false
    }
  }

  onScopeDispose(() => {
    disposed = true
  })

  return {
    states,
    stateFor,
    selectFile,
    submit,
  }
}
