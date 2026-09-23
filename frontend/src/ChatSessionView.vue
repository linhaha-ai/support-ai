<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElCheckboxGroup,
  ElDrawer,
  ElEmpty,
  ElIcon,
  ElInput,
  ElPopconfirm,
  ElRadioButton,
  ElRadioGroup,
  ElSkeleton,
  ElTag,
  ElTooltip,
} from 'element-plus'
import { Delete, EditPen, Plus, VideoPause } from '@element-plus/icons-vue'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/checkbox/style/css'
import 'element-plus/es/components/drawer/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/popconfirm/style/css'
import 'element-plus/es/components/radio-button/style/css'
import 'element-plus/es/components/radio-group/style/css'
import 'element-plus/es/components/skeleton/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/tooltip/style/css'
import { useChatSessions } from './useChatSessions'

const sessions = reactive(useChatSessions())
const editingTitle = ref(false)
const titleInput = ref('')
const selectedKnowledgeBases = computed(() => sessions.knowledgeBases.filter((item) =>
  sessions.createdSession?.knowledgeBaseIds.includes(item.id)))

function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

function statusLabel(status: string): string {
  if (status === 'STREAMING') return '生成中'
  if (status === 'BACKGROUND') return '后台处理中'
  if (status === 'COMPLETED') return '已完成'
  if (status === 'CANCELLED') return '已停止'
  return '未完整'
}

function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'STREAMING' || status === 'BACKGROUND') return 'info'
  if (status === 'CANCELLED') return 'warning'
  return 'danger'
}

function modeLabel(mode: string): string {
  return mode === 'ASSISTANT' ? '智能助手' : 'RAG'
}

function toolLabel(name: string): string {
  return name === 'getTicketStatus' ? '查询工单' : '检索知识'
}

function toolStatusLabel(status: string): string {
  if (status === 'STARTED') return '执行中'
  if (status === 'SUCCEEDED') return '已完成'
  return '失败'
}

function beginRename() {
  if (!sessions.createdSession || !sessions.canManageSession) return
  titleInput.value = sessions.createdSession.title
  editingTitle.value = true
}

function cancelRename() {
  editingTitle.value = false
  titleInput.value = ''
}

async function saveRename() {
  await sessions.renameCurrentSession(titleInput.value)
  if (!sessions.renaming) cancelRename()
}

onMounted(() => {
  void sessions.loadKnowledgeBases()
  void sessions.loadSessions()
})
</script>

<template>
  <section class="chat-session-page" aria-labelledby="chat-session-heading">
    <div class="page-heading chat-session-heading">
      <div>
        <span class="eyebrow">CHAT SESSION</span>
        <div class="chat-session-title-row">
          <h1 id="chat-session-heading">{{ sessions.createdSession ? sessions.createdSession.title : '新建会话' }}</h1>
          <template v-if="sessions.createdSession">
            <ElTooltip content="重命名会话" placement="top">
              <ElButton circle text aria-label="重命名会话" :disabled="!sessions.canManageSession" @click="beginRename">
                <ElIcon><EditPen /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElPopconfirm
              title="删除后无法恢复，确认删除此会话吗？"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="sessions.deleteCurrentSession"
            >
              <template #reference>
                <ElButton circle text type="danger" title="删除会话" aria-label="删除会话" :loading="sessions.deleting" :disabled="!sessions.canManageSession">
                  <ElIcon><Delete /></ElIcon>
                </ElButton>
              </template>
            </ElPopconfirm>
          </template>
        </div>
        <p>{{ sessions.createdSession ? '知识库范围与会话模式已固定。' : '选择会话模式和可使用的知识库。' }}</p>
      </div>
      <ElTag type="info" effect="plain">{{ modeLabel(sessions.createdSession?.mode || sessions.selectedMode) }}</ElTag>
    </div>

    <div class="chat-workspace">
      <aside class="chat-session-sidebar" aria-label="历史会话">
        <div class="chat-session-sidebar-heading">
          <span>历史会话</span>
          <ElTooltip content="新建会话" placement="top">
            <ElButton circle type="primary" aria-label="新建会话" :disabled="!sessions.canStartAnother" @click="sessions.startAnother">
              <ElIcon><Plus /></ElIcon>
            </ElButton>
          </ElTooltip>
        </div>
        <ElAlert v-if="sessions.sessionsError" type="error" :closable="false" :title="sessions.sessionsError" show-icon>
          <template #default><ElButton text type="primary" @click="sessions.loadSessions()">重试</ElButton></template>
        </ElAlert>
        <ElSkeleton v-else-if="sessions.sessionsLoading" :rows="5" animated />
        <ElEmpty v-else-if="sessions.sessionItems.length === 0" :image-size="60" description="暂无历史会话" />
        <nav v-else class="chat-session-list">
          <button
            v-for="session in sessions.sessionItems"
            :key="session.sessionId"
            type="button"
            class="chat-session-list-item"
            :class="{ active: session.sessionId === sessions.createdSession?.sessionId }"
            :disabled="!sessions.canStartAnother"
            @click="sessions.selectSession(session)"
          >
            <strong>{{ session.title }}</strong>
            <span>{{ formatDate(session.updatedAt) }}</span>
            <ElTag size="small" effect="plain">{{ modeLabel(session.mode) }}</ElTag>
            <ElTag v-if="!session.contentReadable" size="small" type="warning" effect="plain">已受限</ElTag>
          </button>
          <ElButton v-if="sessions.canLoadMoreSessions" text type="primary" :loading="sessions.sessionsLoadingMore" @click="sessions.loadSessions(true)">加载更多</ElButton>
        </nav>
      </aside>

      <section class="chat-session-main">
        <form v-if="editingTitle" class="chat-title-editor" @submit.prevent="saveRename">
          <ElInput v-model="titleInput" maxlength="200" show-word-limit aria-label="会话标题" :disabled="sessions.renaming" />
          <ElButton type="primary" native-type="submit" :loading="sessions.renaming" :disabled="!titleInput.trim()">保存</ElButton>
          <ElButton :disabled="sessions.renaming" @click="cancelRename">取消</ElButton>
        </form>

    <section v-if="sessions.createdSession" class="chat-conversation" aria-label="当前会话">
      <div class="chat-session-created">
        <div class="chat-session-summary">
          <span>会话标识</span>
          <strong>{{ sessions.createdSession.sessionId }}</strong>
          <span>创建于 {{ formatDate(sessions.createdSession.createdAt) }}</span>
        </div>
        <div class="chat-session-scope" aria-label="固定知识库范围">
          <span v-for="knowledgeBase in selectedKnowledgeBases" :key="knowledgeBase.id">{{ knowledgeBase.name }}</span>
        </div>
        <ElButton :disabled="!sessions.canStartAnother" @click="sessions.startAnother">新建会话</ElButton>
      </div>

      <ElAlert
        v-if="!sessions.historyContentReadable"
        class="chat-session-alert"
        type="warning"
        :closable="false"
        title="当前会话的知识库授权已变化，历史消息正文和来源已受限。"
        show-icon
      />
      <ElAlert
        v-if="sessions.historyError"
        class="chat-session-alert"
        type="error"
        :closable="false"
        :title="sessions.historyError"
        show-icon
      >
        <template #default><ElButton text type="primary" @click="sessions.retryHistory">重试</ElButton></template>
      </ElAlert>

      <ElAlert
        v-if="sessions.sendError"
        class="chat-session-alert"
        type="error"
        :closable="false"
        :title="sessions.sendError"
        show-icon
      />
      <ElAlert
        v-if="sessions.backgroundProcessing"
        class="chat-session-alert"
        type="info"
        :closable="false"
        :title="sessions.backgroundStatusUnknown
          ? '回答连接已断开，暂时无法确认后台状态。'
          : '回答连接已断开，后台仍在处理中。'"
        show-icon
      >
        <template #default>
          <ElButton text type="primary" :loading="sessions.refreshingRun" @click="sessions.refreshRunStatus()">刷新状态</ElButton>
        </template>
      </ElAlert>
      <ElAlert
        v-if="sessions.sendNotice"
        class="chat-session-alert"
        type="info"
        :closable="false"
        :title="sessions.sendNotice"
        show-icon
      />

      <div class="chat-transcript" aria-live="polite" aria-label="问答消息">
        <div v-if="sessions.historyLoading" class="chat-history-loading"><ElSkeleton :rows="4" animated /></div>
        <ElEmpty v-else-if="sessions.messages.length === 0" description="还没有消息" />
        <article
          v-for="message in sessions.messages"
          :key="message.id"
          class="chat-message"
          :class="`chat-message-${message.role}`"
        >
          <header class="chat-message-heading">
            <strong>{{ message.role === 'user' ? '你' : 'SupportAI' }}</strong>
            <ElTag
              v-if="message.role === 'assistant'"
              size="small"
              effect="plain"
              :type="statusType(message.status)"
            >
              {{ statusLabel(message.status) }}
            </ElTag>
          </header>
          <p v-if="message.content" class="chat-message-content">{{ message.content }}</p>
          <p v-else-if="message.status === 'STREAMING'" class="chat-message-pending">正在生成回答...</p>
          <p v-else-if="!sessions.historyContentReadable" class="chat-message-pending">内容已受限</p>
          <p v-if="message.errorMessage" class="chat-message-error">{{ message.errorMessage }}</p>
          <div v-if="message.tools.length > 0" class="chat-tools" aria-label="工具执行过程">
            <ElTag
              v-for="(tool, index) in message.tools"
              :key="`${tool.name}-${index}`"
              size="small"
              effect="plain"
              :type="tool.status === 'FAILED' ? 'danger' : tool.status === 'SUCCEEDED' ? 'success' : 'info'"
            >
              {{ toolLabel(tool.name) }} · {{ toolStatusLabel(tool.status) }}
            </ElTag>
          </div>
          <div v-if="message.sources.length > 0" class="chat-sources" aria-label="回答来源">
            <template
              v-for="source in message.sources"
              :key="source.number"
            >
              <button
                v-if="source.type === 'DOCUMENT'"
                class="chat-source-button"
                :class="{ 'chat-source-button-unavailable': !source.available }"
                type="button"
                :disabled="!source.available"
                @click="sessions.openSource(source)"
              >
                [{{ source.number }}] {{ source.title }}{{ source.available ? '' : ' · 已失效' }}
              </button>
              <span v-else class="chat-ticket-source">
                [{{ source.number }}] {{ source.ticketNo }} · {{ source.ticketStatus }}
              </span>
            </template>
          </div>
        </article>
        <ElButton v-if="sessions.canLoadMoreMessages" class="chat-history-more" text type="primary" :loading="sessions.historyLoadingMore" @click="sessions.loadMoreMessages">加载更多消息</ElButton>
      </div>

      <form class="chat-composer" @submit.prevent="sessions.send">
        <ElInput
          v-model="sessions.messageInput"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 6 }"
          resize="none"
          placeholder="输入你的问题"
          aria-label="问题内容"
          :disabled="sessions.sending || !sessions.historyContentReadable"
        />
        <div class="chat-composer-actions">
          <ElPopconfirm
            v-if="sessions.canCancelRun"
            title="停止后将保留已生成的内容，确认停止吗？"
            confirm-button-text="停止生成"
            cancel-button-text="继续生成"
            @confirm="sessions.cancelRun"
          >
            <template #reference>
              <ElButton class="chat-stop-button" type="warning" :loading="sessions.cancellingRun">
                <ElIcon><VideoPause /></ElIcon>
                停止
              </ElButton>
            </template>
          </ElPopconfirm>
          <ElButton type="primary" native-type="submit" :loading="sessions.sending" :disabled="!sessions.canSend">
            发送
          </ElButton>
        </div>
      </form>
    </section>

    <template v-else>
      <ElAlert
        v-if="sessions.loadError"
        class="chat-session-alert"
        type="error"
        :closable="false"
        :title="sessions.loadError"
        show-icon
      >
        <template #default><ElButton text type="primary" @click="sessions.loadKnowledgeBases">重试</ElButton></template>
      </ElAlert>
      <ElAlert
        v-if="sessions.createError"
        class="chat-session-alert"
        type="error"
        :closable="false"
        :title="sessions.createError"
        show-icon
      >
        <template #default><ElButton text type="primary" :loading="sessions.creating" @click="sessions.create">重试</ElButton></template>
      </ElAlert>

      <ElSkeleton v-if="sessions.loading" :rows="4" animated />
      <ElEmpty v-else-if="sessions.knowledgeBases.length === 0 && !sessions.loadError" description="暂无可用于会话的知识库" />
      <form v-else-if="sessions.knowledgeBases.length > 0" class="chat-session-form" @submit.prevent="sessions.create">
        <ElRadioGroup v-model="sessions.selectedMode" class="chat-mode-selector" aria-label="会话模式">
          <ElRadioButton value="RAG">知识问答</ElRadioButton>
          <ElRadioButton value="ASSISTANT">智能助手</ElRadioButton>
        </ElRadioGroup>
        <ElCheckboxGroup v-model="sessions.selectedKnowledgeBaseIds" class="chat-session-options" aria-label="会话知识库范围">
          <ElCheckbox v-for="knowledgeBase in sessions.knowledgeBases" :key="knowledgeBase.id" :value="knowledgeBase.id">
            <span class="chat-session-option-name">{{ knowledgeBase.name }}</span>
            <span class="chat-session-option-meta">{{ knowledgeBase.permission === 'WRITE' ? '可写' : '只读' }}</span>
          </ElCheckbox>
        </ElCheckboxGroup>
        <div class="chat-session-actions">
          <ElButton type="primary" native-type="submit" :loading="sessions.creating" :disabled="!sessions.canCreate">创建会话</ElButton>
        </div>
      </form>
    </template>
      </section>
    </div>

    <ElDrawer
      v-model="sessions.sourceDrawerOpen"
      class="chat-source-drawer"
      :title="sessions.sourceDetail?.documentName || sessions.selectedSource?.title || '回答来源'"
      direction="rtl"
      size="min(520px, 92vw)"
      @closed="sessions.closeSource"
    >
      <ElSkeleton v-if="sessions.sourceLoading" :rows="6" animated />
      <ElAlert
        v-else-if="sessions.sourceError"
        type="error"
        :closable="false"
        :title="sessions.sourceError"
        show-icon
      >
        <template #default><ElButton text type="primary" @click="sessions.retrySource">重试</ElButton></template>
      </ElAlert>
      <div v-else-if="sessions.sourceDetail" class="chat-source-detail">
        <div class="chat-source-metadata">
          <ElTag size="small" effect="plain">版本 {{ sessions.sourceDetail.versionNo }}</ElTag>
          <ElTag size="small" effect="plain" :type="sessions.sourceDetail.isCurrent ? 'success' : 'warning'">
            {{ sessions.sourceDetail.isCurrent ? '当前版本' : '历史版本' }}
          </ElTag>
          <span v-if="sessions.sourceDetail.section">{{ sessions.sourceDetail.section }}</span>
          <span v-if="sessions.sourceDetail.pageNo !== null">第 {{ sessions.sourceDetail.pageNo }} 页</span>
        </div>
        <pre class="chat-source-content">{{ sessions.sourceDetail.content }}</pre>
      </div>
    </ElDrawer>
  </section>
</template>
