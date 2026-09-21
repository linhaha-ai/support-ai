<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElAlert, ElButton, ElEmpty, ElInput, ElOption, ElSelect, ElSkeleton, ElTag } from 'element-plus'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/option/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/skeleton/style/css'
import 'element-plus/es/components/tag/style/css'
import type { KnowledgeBaseManagement, KnowledgeBaseStatus } from './knowledgeBase'
import type { CurrentUser } from './useAuth'
import { useKnowledgeBases } from './useKnowledgeBases'

const props = defineProps<{ user: CurrentUser }>()

const isAdmin = computed(() => props.user.role === 'ADMIN')
const knowledgeBases = reactive(useKnowledgeBases(() => isAdmin.value))
const editorVisible = ref(false)
const editingId = ref<string | null>(null)
const editor = reactive({
  name: '',
  description: '',
  status: 'ACTIVE' as KnowledgeBaseStatus,
  revision: 0,
})

const editorTitle = computed(() => editingId.value ? '编辑知识库' : '新建知识库')
const canSubmit = computed(() => editor.name.trim().length > 0 && !knowledgeBases.saving)

function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

function statusLabel(status: KnowledgeBaseStatus): string {
  return status === 'ACTIVE' ? '启用中' : '已停用'
}

function resetEditor() {
  editingId.value = null
  editor.name = ''
  editor.description = ''
  editor.status = 'ACTIVE'
  editor.revision = 0
}

function openCreate() {
  resetEditor()
  editorVisible.value = true
}

function openEdit(item: KnowledgeBaseManagement) {
  editingId.value = item.id
  editor.name = item.name
  editor.description = item.description ?? ''
  editor.status = item.status
  editor.revision = item.revision
  editorVisible.value = true
}

function closeEditor() {
  if (knowledgeBases.saving) return
  editorVisible.value = false
  resetEditor()
}

async function submit() {
  if (!canSubmit.value) return
  const input = { name: editor.name.trim(), description: editor.description.trim() }
  if (editingId.value) {
    const updated = await knowledgeBases.update(editingId.value, {
      ...input,
      status: editor.status,
      revision: editor.revision,
    })
    if (updated) closeEditor()
    return
  }
  const created = await knowledgeBases.create(input)
  if (created) closeEditor()
}

onMounted(knowledgeBases.load)
</script>

<template>
  <section class="knowledge-page" aria-labelledby="knowledge-heading">
    <div class="page-heading knowledge-heading">
      <div>
        <span class="eyebrow">KNOWLEDGE BASE</span>
        <h1 id="knowledge-heading">知识库</h1>
        <p>{{ knowledgeBases.isManagement ? '管理全部知识库的名称、状态与版本。' : '查看你有权限访问的知识库。' }}</p>
      </div>
      <div class="knowledge-actions">
        <ElButton
          v-if="isAdmin"
          class="create-knowledge-button"
          type="primary"
          @click="openCreate"
        >
          新建知识库
        </ElButton>
      </div>
    </div>

    <div v-if="isAdmin" class="knowledge-toolbar" aria-label="知识库视图">
      <div class="knowledge-view-switch" role="group" aria-label="知识库范围">
        <ElButton :type="knowledgeBases.mode === 'personal' ? 'primary' : 'default'" @click="knowledgeBases.switchMode('personal')">
          我的知识库
        </ElButton>
        <ElButton :type="knowledgeBases.mode === 'management' ? 'primary' : 'default'" @click="knowledgeBases.switchMode('management')">
          管理全部
        </ElButton>
      </div>
      <label v-if="knowledgeBases.isManagement" class="knowledge-filter">
        <span>状态</span>
        <ElSelect
          :model-value="knowledgeBases.statusFilter"
          aria-label="按状态筛选知识库"
          @update:model-value="knowledgeBases.changeStatusFilter($event)"
        >
          <ElOption label="全部" value="" />
          <ElOption label="启用中" value="ACTIVE" />
          <ElOption label="已停用" value="DISABLED" />
        </ElSelect>
      </label>
    </div>

    <ElAlert
      v-if="knowledgeBases.error"
      class="knowledge-alert"
      type="error"
      :closable="false"
      :title="knowledgeBases.error"
      show-icon
    >
      <template #default>
        <ElButton text type="primary" @click="knowledgeBases.load">重试</ElButton>
      </template>
    </ElAlert>

    <section v-if="editorVisible" class="knowledge-editor" :aria-labelledby="'knowledge-editor-heading'">
      <div class="section-heading">
        <div>
          <h2 id="knowledge-editor-heading">{{ editorTitle }}</h2>
          <span v-if="editingId">当前版本 {{ editor.revision }}</span>
        </div>
        <ElButton text @click="closeEditor">关闭</ElButton>
      </div>
      <form class="knowledge-form" @submit.prevent="submit">
        <label for="knowledge-name">名称</label>
        <ElInput id="knowledge-name" v-model="editor.name" maxlength="120" show-word-limit :disabled="knowledgeBases.saving" />
        <label for="knowledge-description">描述</label>
        <ElInput id="knowledge-description" v-model="editor.description" type="textarea" :rows="3" :disabled="knowledgeBases.saving" />
        <template v-if="editingId">
          <label for="knowledge-status">状态</label>
          <ElSelect id="knowledge-status" v-model="editor.status" :disabled="knowledgeBases.saving">
            <ElOption label="启用中" value="ACTIVE" />
            <ElOption label="已停用" value="DISABLED" />
          </ElSelect>
        </template>
        <div class="knowledge-form-actions">
          <ElButton type="primary" native-type="submit" :loading="knowledgeBases.saving" :disabled="!canSubmit">保存</ElButton>
          <ElButton :disabled="knowledgeBases.saving" @click="closeEditor">取消</ElButton>
          <span v-if="knowledgeBases.conflict" class="revision-conflict" role="alert">版本已变化，表单内容已保留。请刷新列表后重新编辑。</span>
        </div>
      </form>
    </section>

    <section v-if="knowledgeBases.loading" class="knowledge-loading" aria-live="polite" aria-busy="true">
      <ElSkeleton :rows="4" animated />
    </section>

    <section v-else-if="knowledgeBases.isManagement" class="knowledge-list" aria-label="全部知识库">
      <ElEmpty v-if="knowledgeBases.managementItems.length === 0 && !knowledgeBases.error" description="当前筛选条件下没有知识库" />
      <article v-for="item in knowledgeBases.managementItems" :key="item.id" class="knowledge-item">
        <div class="knowledge-item-title">
          <div>
            <h2>{{ item.name }}</h2>
            <p>{{ item.description || '暂无描述' }}</p>
          </div>
          <ElTag size="small" :type="item.status === 'ACTIVE' ? 'success' : 'info'">{{ statusLabel(item.status) }}</ElTag>
        </div>
        <div class="knowledge-metadata">
          <span>所有者 {{ item.ownerId }}</span>
          <span>版本 {{ item.revision }}</span>
          <span>更新于 {{ formatDate(item.updatedAt) }}</span>
        </div>
        <div class="knowledge-item-actions">
          <ElButton text type="primary" @click="openEdit(item)">编辑</ElButton>
        </div>
      </article>
    </section>

    <section v-else class="knowledge-list" aria-label="我的知识库">
      <ElEmpty v-if="knowledgeBases.summaries.length === 0 && !knowledgeBases.error" description="暂未获得知识库访问权限" />
      <article v-for="item in knowledgeBases.summaries" :key="item.id" class="knowledge-item">
        <div class="knowledge-item-title">
          <div>
            <h2>{{ item.name }}</h2>
            <p>{{ item.description || '暂无描述' }}</p>
          </div>
          <ElTag size="small" :type="item.status === 'ACTIVE' ? 'success' : 'info'">{{ statusLabel(item.status) }}</ElTag>
        </div>
        <div class="knowledge-metadata">
          <span>权限 {{ item.permission }}</span>
          <span>更新于 {{ formatDate(item.updatedAt) }}</span>
        </div>
      </article>
    </section>
  </section>
</template>
