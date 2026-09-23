<script setup lang="ts">
import { ElAlert, ElButton, ElInput, ElSkeleton, ElTag } from 'element-plus'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/skeleton/style/css'
import 'element-plus/es/components/tag/style/css'
import type { TicketStatus } from './ticket'
import { useTicketQuery } from './useTicketQuery'

const ticketQuery = useTicketQuery()

function statusLabel(status: TicketStatus): string {
  if (status === 'OPEN') return '待处理'
  if (status === 'IN_PROGRESS') return '处理中'
  if (status === 'RESOLVED') return '已解决'
  return '已关闭'
}

function statusType(status: TicketStatus): 'primary' | 'success' | 'warning' | 'info' {
  if (status === 'OPEN') return 'primary'
  if (status === 'IN_PROGRESS') return 'warning'
  if (status === 'RESOLVED') return 'success'
  return 'info'
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}
</script>

<template>
  <section class="ticket-page" aria-labelledby="ticket-heading">
    <div class="page-heading ticket-heading">
      <div>
        <span class="eyebrow">TICKET STATUS</span>
        <h1 id="ticket-heading">工单查询</h1>
        <p>工单状态与当前处理信息</p>
      </div>
      <span class="phase-badge">F25 <span>只读业务查询</span></span>
    </div>

    <form class="ticket-search" role="search" @submit.prevent="ticketQuery.query">
      <label for="ticket-number">工单编号</label>
      <div class="ticket-search-row">
        <ElInput
          id="ticket-number"
          v-model="ticketQuery.ticketNo.value"
          name="ticketNo"
          maxlength="32"
          autocomplete="off"
          placeholder="T10086"
          :disabled="ticketQuery.loading.value"
        />
        <ElButton
          type="primary"
          native-type="submit"
          :loading="ticketQuery.loading.value"
          :disabled="!ticketQuery.canQuery.value"
        >
          查询
        </ElButton>
      </div>
    </form>

    <ElAlert
      v-if="ticketQuery.error.value"
      class="ticket-alert"
      type="error"
      :closable="false"
      :title="ticketQuery.error.value"
      show-icon
    >
      <template #default>
        <ElButton text type="primary" :disabled="ticketQuery.loading.value" @click="ticketQuery.retry">
          重试
        </ElButton>
      </template>
    </ElAlert>

    <section v-if="ticketQuery.loading.value" class="ticket-loading" aria-live="polite" aria-busy="true">
      <ElSkeleton :rows="5" animated />
    </section>

    <article v-else-if="ticketQuery.ticket.value" class="ticket-card" aria-live="polite">
      <header class="ticket-card-header">
        <div>
          <span class="ticket-number">{{ ticketQuery.ticket.value.ticketNo }}</span>
          <h2>{{ ticketQuery.ticket.value.title || '未提供标题' }}</h2>
        </div>
        <ElTag :type="statusType(ticketQuery.ticket.value.status)">
          {{ ticketQuery.ticket.value.status }} · {{ statusLabel(ticketQuery.ticket.value.status) }}
        </ElTag>
      </header>
      <p class="ticket-description">{{ ticketQuery.ticket.value.description || '未提供问题说明' }}</p>
      <dl class="ticket-facts">
        <div>
          <dt>所属知识库</dt>
          <dd>{{ ticketQuery.ticket.value.knowledgeBaseName }}</dd>
        </div>
        <div>
          <dt>当前处理人</dt>
          <dd>{{ ticketQuery.ticket.value.ownerName || '暂未分配' }}</dd>
        </div>
        <div>
          <dt>状态更新时间</dt>
          <dd>{{ formatDateTime(ticketQuery.ticket.value.updatedAt) }}</dd>
        </div>
        <div>
          <dt>本次查询时间</dt>
          <dd>{{ formatDateTime(ticketQuery.ticket.value.queriedAt) }}</dd>
        </div>
      </dl>
    </article>

    <section v-else-if="!ticketQuery.error.value" class="ticket-empty" aria-live="polite">
      <span aria-hidden="true">#</span>
      <p>尚未查询工单</p>
    </section>
  </section>
</template>
