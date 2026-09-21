import { computed, onScopeDispose, ref } from 'vue'
import {
  fetchAdminUsers,
  fetchKnowledgeBaseMembers,
  removeKnowledgeBaseMember,
  updateKnowledgeBaseMember,
  type AdminUser,
  type KnowledgeBaseMember,
  type KnowledgeBasePermission,
} from './knowledgeBase'

function messageFor(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string') {
    return error.message
  }
  return '成员权限服务暂时不可用，请稍后重试。'
}

export function useKnowledgeBaseMembers() {
  const knowledgeBaseId = ref<string | null>(null)
  const users = ref<AdminUser[]>([])
  const members = ref<KnowledgeBaseMember[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  let disposed = false
  let requestVersion = 0

  const visible = computed(() => knowledgeBaseId.value !== null)

  async function load() {
    if (!knowledgeBaseId.value) return
    const id = knowledgeBaseId.value
    const version = ++requestVersion
    loading.value = true
    error.value = ''
    try {
      const [loadedUsers, loadedMembers] = await Promise.all([
        fetchAdminUsers(),
        fetchKnowledgeBaseMembers(id),
      ])
      if (!disposed && version === requestVersion && knowledgeBaseId.value === id) {
        users.value = loadedUsers
        members.value = loadedMembers
      }
    } catch (loadError) {
      if (!disposed && version === requestVersion) error.value = messageFor(loadError)
    } finally {
      if (!disposed && version === requestVersion) loading.value = false
    }
  }

  async function open(id: string) {
    if (knowledgeBaseId.value !== id) {
      users.value = []
      members.value = []
    }
    knowledgeBaseId.value = id
    await load()
  }

  function close() {
    requestVersion += 1
    knowledgeBaseId.value = null
    users.value = []
    members.value = []
    error.value = ''
  }

  async function grant(userId: string, permission: KnowledgeBasePermission): Promise<boolean> {
    if (!knowledgeBaseId.value || saving.value) return false
    saving.value = true
    error.value = ''
    try {
      const updated = await updateKnowledgeBaseMember(knowledgeBaseId.value, userId, { permission })
      if (!disposed) {
        const index = members.value.findIndex((member) => member.userId === updated.userId)
        if (index >= 0) members.value.splice(index, 1, updated)
        else members.value.push(updated)
      }
      return true
    } catch (saveError) {
      if (!disposed) error.value = messageFor(saveError)
      return false
    } finally {
      if (!disposed) saving.value = false
    }
  }

  async function revoke(userId: string): Promise<boolean> {
    if (!knowledgeBaseId.value || saving.value) return false
    saving.value = true
    error.value = ''
    try {
      await removeKnowledgeBaseMember(knowledgeBaseId.value, userId)
      if (!disposed) members.value = members.value.filter((member) => member.userId !== userId)
      return true
    } catch (saveError) {
      if (!disposed) error.value = messageFor(saveError)
      return false
    } finally {
      if (!disposed) saving.value = false
    }
  }

  onScopeDispose(() => {
    disposed = true
    requestVersion += 1
  })

  return {
    knowledgeBaseId,
    users,
    members,
    loading,
    saving,
    error,
    visible,
    load,
    open,
    close,
    grant,
    revoke,
  }
}
