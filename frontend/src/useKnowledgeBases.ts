import { computed, onScopeDispose, ref } from 'vue'
import {
  createKnowledgeBase,
  fetchKnowledgeBaseManagement,
  fetchKnowledgeBaseSummaries,
  updateKnowledgeBase,
  type CreateKnowledgeBaseInput,
  type KnowledgeBaseApiError,
  type KnowledgeBaseDetail,
  type KnowledgeBaseManagement,
  type KnowledgeBaseStatus,
  type KnowledgeBaseSummary,
  type UpdateKnowledgeBaseInput,
} from './knowledgeBase'

export type KnowledgeBaseViewMode = 'personal' | 'management'

function messageFor(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string') {
    return error.message
  }
  return '知识库服务暂时不可用，请稍后重试。'
}

function kindFor(error: unknown): KnowledgeBaseApiError['kind'] | null {
  return typeof error === 'object' && error !== null && 'kind' in error
    && typeof error.kind === 'string'
    ? error.kind as KnowledgeBaseApiError['kind']
    : null
}

export function useKnowledgeBases(isAdmin: () => boolean) {
  const mode = ref<KnowledgeBaseViewMode>('personal')
  const summaries = ref<KnowledgeBaseSummary[]>([])
  const managementItems = ref<KnowledgeBaseManagement[]>([])
  const statusFilter = ref<KnowledgeBaseStatus | ''>('')
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  const conflict = ref(false)
  let disposed = false
  let requestVersion = 0

  const isManagement = computed(() => mode.value === 'management' && isAdmin())
  const items = computed(() => isManagement.value ? managementItems.value : summaries.value)

  async function load() {
    const version = ++requestVersion
    loading.value = true
    error.value = ''
    conflict.value = false
    try {
      if (isManagement.value) {
        managementItems.value = await fetchKnowledgeBaseManagement(statusFilter.value)
      } else {
        summaries.value = await fetchKnowledgeBaseSummaries()
      }
    } catch (loadError) {
      if (!disposed && version === requestVersion) error.value = messageFor(loadError)
    } finally {
      if (!disposed && version === requestVersion) loading.value = false
    }
  }

  async function switchMode(nextMode: KnowledgeBaseViewMode) {
    if (nextMode === 'management' && !isAdmin()) return
    if (mode.value !== nextMode) {
      mode.value = nextMode
      await load()
    }
  }

  async function changeStatusFilter(status: KnowledgeBaseStatus | '') {
    statusFilter.value = status
    if (isManagement.value) await load()
  }

  async function create(input: CreateKnowledgeBaseInput): Promise<KnowledgeBaseDetail | null> {
    saving.value = true
    error.value = ''
    conflict.value = false
    try {
      const created = await createKnowledgeBase(input)
      await load()
      return created
    } catch (saveError) {
      if (!disposed) {
        error.value = messageFor(saveError)
        conflict.value = kindFor(saveError) === 'conflict'
      }
      return null
    } finally {
      if (!disposed) saving.value = false
    }
  }

  async function update(id: string, input: UpdateKnowledgeBaseInput): Promise<KnowledgeBaseManagement | null> {
    saving.value = true
    error.value = ''
    conflict.value = false
    try {
      const updated = await updateKnowledgeBase(id, input)
      if (isManagement.value) await load()
      return updated
    } catch (saveError) {
      if (!disposed) {
        error.value = messageFor(saveError)
        conflict.value = kindFor(saveError) === 'conflict'
      }
      return null
    } finally {
      if (!disposed) saving.value = false
    }
  }

  onScopeDispose(() => {
    disposed = true
    requestVersion += 1
  })

  return {
    mode,
    summaries,
    managementItems,
    statusFilter,
    loading,
    saving,
    error,
    conflict,
    isManagement,
    items,
    load,
    switchMode,
    changeStatusFilter,
    create,
    update,
  }
}
