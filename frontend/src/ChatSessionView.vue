<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue'
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
  ElSkeleton,
  ElTag,
} from 'element-plus'
import { VideoPause } from '@element-plus/icons-vue'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/checkbox/style/css'
import 'element-plus/es/components/drawer/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/popconfirm/style/css'
import 'element-plus/es/components/skeleton/style/css'
import 'element-plus/es/components/tag/style/css'
import { useChatSessions } from './useChatSessions'

const sessions = reactive(useChatSessions())
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

onMounted(sessions.loadKnowledgeBases)
</script>

<template>
  <section class="chat-session-page" aria-labelledby="chat-session-heading">
    <div class="page-heading chat-session-heading">
      <div>
        <span class="eyebrow">CHAT SESSION</span>
        <h1 id="chat-session-heading">{{ sessions.createdSession ? sessions.createdSession.title : '新建会话' }}</h1>
        <p>{{ sessions.createdSession ? '知识库范围已固定。' : '选择本次会话可使用的知识库。' }}</p>
      </div>
      <ElTag type="info" effect="plain">RAG</ElTag>
    </div>

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
        <ElEmpty v-if="sessions.messages.length === 0" description="还没有消息" />
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
          <p v-if="message.errorMessage" class="chat-message-error">{{ message.errorMessage }}</p>
          <div v-if="message.sources.length > 0" class="chat-sources" aria-label="回答来源">
            <button
              v-for="source in message.sources"
              :key="source.number"
              class="chat-source-button"
              type="button"
              @click="sessions.openSource(source)"
            >
              [{{ source.number }}] {{ source.title }}
            </button>
          </div>
        </article>
      </div>

      <form class="chat-composer" @submit.prevent="sessions.send">
        <ElInput
          v-model="sessions.messageInput"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 6 }"
          resize="none"
          placeholder="输入你的问题"
          aria-label="问题内容"
          :disabled="sessions.sending"
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
