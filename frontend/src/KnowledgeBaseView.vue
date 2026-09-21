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
import type { KnowledgeBaseManagement, KnowledgeBaseMember, KnowledgeBasePermission, KnowledgeBaseStatus } from './knowledgeBase'
import type { CurrentUser } from './useAuth'
import { useDocumentUpload } from './useDocumentUpload'
import { useKnowledgeBases } from './useKnowledgeBases'
import { useKnowledgeBaseMembers } from './useKnowledgeBaseMembers'

const props = defineProps<{ user: CurrentUser }>()

const isAdmin = computed(() => props.user.role === 'ADMIN')
const knowledgeBases = reactive(useKnowledgeBases(() => isAdmin.value))
const memberManagement = reactive(useKnowledgeBaseMembers())
const documentUpload = useDocumentUpload()
const editorVisible = ref(false)
const editingId = ref<string | null>(null)
const editor = reactive({
  name: '',
  description: '',
  status: 'ACTIVE' as KnowledgeBaseStatus,
  revision: 0,
})
const memberForm = reactive({
  userId: '',
  permission: 'READ' as KnowledgeBasePermission,
})
const pendingRevokeUserId = ref<string | null>(null)

const editorTitle = computed(() => editingId.value ? '编辑知识库' : '新建知识库')
const canSubmit = computed(() => editor.name.trim().length > 0 && !knowledgeBases.saving)
const managedKnowledgeBase = computed(() => memberManagement.knowledgeBaseId
  ? knowledgeBases.managementItems.find((item) => item.id === memberManagement.knowledgeBaseId) ?? null
  : null)
const canSubmitMember = computed(() => memberForm.userId.length > 0 && !memberManagement.saving)

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

function resetMemberForm() {
  memberForm.userId = ''
  memberForm.permission = 'READ'
}

async function openMemberManagement(item: KnowledgeBaseManagement) {
  resetMemberForm()
  pendingRevokeUserId.value = null
  await memberManagement.open(item.id)
}

function closeMemberManagement() {
  if (memberManagement.saving) return
  memberManagement.close()
  resetMemberForm()
  pendingRevokeUserId.value = null
}

function selectMember(member: KnowledgeBaseMember) {
  if (!member.enabled) return
  memberForm.userId = member.userId
  memberForm.permission = member.permission
}

function updateSelectedUser(userId: string) {
  memberForm.userId = userId
  const member = memberManagement.members.find((item) => item.userId === userId)
  if (member) memberForm.permission = member.permission
}

async function submitMember() {
  if (!canSubmitMember.value) return
  const saved = await memberManagement.grant(memberForm.userId, memberForm.permission)
  if (saved) resetMemberForm()
}

async function revokeMember(member: KnowledgeBaseMember) {
  const revoked = await memberManagement.revoke(member.userId)
  if (revoked) {
    if (memberForm.userId === member.userId) resetMemberForm()
    pendingRevokeUserId.value = null
  }
}

function requestRevoke(member: KnowledgeBaseMember) {
  if (memberManagement.saving) return
  pendingRevokeUserId.value = member.userId
}

function cancelRevoke() {
  pendingRevokeUserId.value = null
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

function selectDocumentFile(knowledgeBaseId: string, event: Event) {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) return
  const selected = documentUpload.selectFile(knowledgeBaseId, input.files?.[0] ?? null)
  if (!selected) input.value = ''
}

async function submitDocument(knowledgeBaseId: string, event: Event) {
  const form = event.currentTarget instanceof HTMLFormElement ? event.currentTarget : null
  const receipt = await documentUpload.submit(knowledgeBaseId)
  if (receipt) form?.reset()
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

    <section v-if="memberManagement.visible" class="member-manager" aria-labelledby="member-manager-heading">
      <div class="section-heading">
        <div>
          <h2 id="member-manager-heading">成员管理</h2>
          <span>{{ managedKnowledgeBase?.name ?? '知识库' }}</span>
        </div>
        <ElButton text :disabled="memberManagement.saving" @click="closeMemberManagement">关闭</ElButton>
      </div>

      <ElAlert
        v-if="memberManagement.error"
        class="member-alert"
        type="error"
        :closable="false"
        :title="memberManagement.error"
        show-icon
      >
        <template #default>
          <ElButton text type="primary" @click="memberManagement.load">重试</ElButton>
        </template>
      </ElAlert>

      <section v-if="memberManagement.loading" class="member-loading" aria-live="polite" aria-busy="true">
        <ElSkeleton :rows="3" animated />
      </section>

      <template v-else>
        <form class="member-form" @submit.prevent="submitMember">
          <label for="member-user">账号</label>
          <ElSelect
            id="member-user"
            :model-value="memberForm.userId"
            filterable
            placeholder="选择账号"
            :disabled="memberManagement.saving"
            @update:model-value="updateSelectedUser($event)"
          >
            <ElOption
              v-for="account in memberManagement.users"
              :key="account.id"
              :label="`${account.displayName}（${account.username}）${account.enabled ? '' : ' - 已停用'}`"
              :value="account.id"
              :disabled="!account.enabled"
            />
          </ElSelect>
          <label for="member-permission">权限</label>
          <ElSelect id="member-permission" v-model="memberForm.permission" :disabled="memberManagement.saving">
            <ElOption label="只读" value="READ" />
            <ElOption label="可写" value="WRITE" />
          </ElSelect>
          <div class="member-form-actions">
            <ElButton type="primary" native-type="submit" :loading="memberManagement.saving" :disabled="!canSubmitMember">
              授权或更新权限
            </ElButton>
            <ElButton :disabled="memberManagement.saving" @click="resetMemberForm">重置</ElButton>
          </div>
        </form>

        <ElEmpty
          v-if="memberManagement.members.length === 0 && !memberManagement.error"
          class="member-empty"
          description="暂未授权成员"
        />
        <div v-else class="member-list" role="list" aria-label="知识库成员">
          <article v-for="member in memberManagement.members" :key="member.userId" class="member-item" role="listitem">
            <div>
              <div class="member-name">
                <h3>{{ member.displayName }}</h3>
                <ElTag size="small" :type="member.permission === 'WRITE' ? 'success' : 'info'">
                  {{ member.permission === 'WRITE' ? '可写' : '只读' }}
                </ElTag>
                <ElTag v-if="!member.enabled" size="small" type="warning">已停用</ElTag>
              </div>
              <p>{{ member.username }} · {{ member.role }} · 授权于 {{ formatDate(member.createdAt) }}</p>
            </div>
            <div class="member-actions">
              <ElButton text type="primary" :disabled="!member.enabled || memberManagement.saving" @click="selectMember(member)">
                修改权限
              </ElButton>
              <template v-if="pendingRevokeUserId === member.userId">
                <ElButton text type="danger" :loading="memberManagement.saving" @click="revokeMember(member)">确认撤销</ElButton>
                <ElButton text :disabled="memberManagement.saving" @click="cancelRevoke">取消</ElButton>
              </template>
              <ElButton v-else text type="danger" :disabled="memberManagement.saving" @click="requestRevoke(member)">撤销</ElButton>
            </div>
          </article>
        </div>
      </template>
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
          <ElButton text type="primary" @click="openMemberManagement(item)">成员管理</ElButton>
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
        <section v-if="item.permission === 'WRITE'" class="document-upload" :aria-labelledby="`document-upload-${item.id}`">
          <div class="document-upload-heading">
            <div>
              <h3 :id="`document-upload-${item.id}`">上传文档</h3>
              <p>仅支持 UTF-8 Markdown 或 TXT，单个文件不超过 5 MB。</p>
            </div>
            <ElTag v-if="documentUpload.stateFor(item.id).receipt" size="small" type="warning">处理中</ElTag>
          </div>
          <form class="document-upload-form" @submit.prevent="submitDocument(item.id, $event)">
            <label :for="`document-file-${item.id}`">文档文件</label>
            <input
              :id="`document-file-${item.id}`"
              class="document-file-input"
              type="file"
              accept=".md,.markdown,.txt,text/markdown,text/plain"
              :disabled="documentUpload.stateFor(item.id).submitting"
              @change="selectDocumentFile(item.id, $event)"
            >
            <span class="document-file-name">{{ documentUpload.stateFor(item.id).file?.name ?? '尚未选择文件' }}</span>
            <label :for="`document-display-name-${item.id}`">展示名称（可选）</label>
            <ElInput
              :id="`document-display-name-${item.id}`"
              v-model="documentUpload.stateFor(item.id).displayName"
              maxlength="255"
              placeholder="默认使用文件名"
              :disabled="documentUpload.stateFor(item.id).submitting"
            />
            <div class="document-upload-actions">
              <ElButton
                type="primary"
                native-type="submit"
                :loading="documentUpload.stateFor(item.id).submitting"
                :disabled="!documentUpload.stateFor(item.id).file || documentUpload.stateFor(item.id).submitting"
              >
                {{ documentUpload.stateFor(item.id).error ? '重新提交' : '提交上传' }}
              </ElButton>
            </div>
          </form>
          <p v-if="documentUpload.stateFor(item.id).error" class="document-upload-error" role="alert">
            {{ documentUpload.stateFor(item.id).error }}
          </p>
          <div v-if="documentUpload.stateFor(item.id).receipt" class="document-upload-receipt" role="status">
            <strong>文档已接收，正在处理中。</strong>
            <span>文档 {{ documentUpload.stateFor(item.id).receipt?.documentId }}</span>
            <span>版本 {{ documentUpload.stateFor(item.id).receipt?.versionId }}</span>
            <span>任务 {{ documentUpload.stateFor(item.id).receipt?.jobId }}</span>
          </div>
        </section>
      </article>
    </section>
  </section>
</template>
