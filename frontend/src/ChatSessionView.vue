<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElEmpty, ElSkeleton, ElTag } from 'element-plus'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/checkbox/style/css'
import 'element-plus/es/components/empty/style/css'
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

    <section v-if="sessions.createdSession" class="chat-session-created" aria-label="已创建会话">
      <div class="chat-session-summary">
        <span>会话标识</span>
        <strong>{{ sessions.createdSession.sessionId }}</strong>
        <span>创建于 {{ formatDate(sessions.createdSession.createdAt) }}</span>
      </div>
      <div class="chat-session-scope" aria-label="固定知识库范围">
        <span v-for="knowledgeBase in selectedKnowledgeBases" :key="knowledgeBase.id">{{ knowledgeBase.name }}</span>
      </div>
      <ElButton type="primary" @click="sessions.startAnother">新建会话</ElButton>
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
  </section>
</template>
