<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElAlert, ElButton, ElEmpty, ElIcon, ElPopconfirm, ElSkeleton, ElTag } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/popconfirm/style/css'
import 'element-plus/es/components/skeleton/style/css'
import 'element-plus/es/components/tag/style/css'
import type { DocumentChunk, DocumentIngestionJob, DocumentVersionStatus } from './document'
import { useDocumentUpload } from './useDocumentUpload'
import { useDocuments } from './useDocuments'

const props = defineProps<{
  knowledgeBaseId: string
  knowledgeBaseName: string
  writable: boolean
}>()

const emit = defineEmits<{ close: [] }>()
const documents = reactive(useDocuments(() => props.knowledgeBaseId))
const documentUpload = useDocumentUpload()
const activeTab = ref<'content' | 'chunks'>('content')
const versionUploadVisible = ref(false)

const detailTitle = computed(() => documents.selectedDocument?.name ?? '文档详情')
const selectedTask = computed(() => documents.selectedVersion?.task ?? null)
const versionUploadState = computed(() => {
  const documentId = documents.selectedDocument?.documentId
  return documentId ? documentUpload.stateFor(documentId) : null
})

function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

function formatSize(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function versionStatusLabel(status: DocumentVersionStatus): string {
  if (status === 'READY') return '已就绪'
  if (status === 'FAILED') return '处理失败'
  if (status === 'BUILDING') return '处理中'
  if (status === 'CANCELLED') return '已取消'
  return '等待处理'
}

function versionStatusType(status: DocumentVersionStatus): 'info' | 'warning' | 'success' | 'danger' {
  if (status === 'READY') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  if (status === 'BUILDING') return 'warning'
  return 'info'
}

function stageLabel(stage: DocumentIngestionJob['stage']): string {
  if (stage === 'PARSE') return '解析'
  if (stage === 'CHUNK') return '分块'
  if (stage === 'EMBED') return '向量生成'
  return '发布'
}

function jobSummary(job: DocumentIngestionJob | null): string {
  if (!job) return ''
  const errorStage = stageLabel(job.errorStage ?? job.stage)
  const error = [job.errorCode, job.errorMessage].filter(Boolean).join(' · ')
  if (job.status === 'FAILED') return `${errorStage}阶段终止失败${error ? ` · ${error}` : ''}`
  if (job.status === 'RETRY_WAIT') return `${errorStage}阶段失败，等待自动重试${error ? ` · ${error}` : ''}`
  if (job.status === 'RUNNING') return `${stageLabel(job.stage)}阶段处理中${job.progressPercent === null ? '' : `，${job.progressPercent}%`}`
  if (job.status === 'QUEUED') return `${stageLabel(job.stage)}阶段排队中`
  if (job.status === 'CANCELLED') return '任务已取消'
  return '任务已完成'
}

function canRetryVersion(): boolean {
  const version = documents.selectedVersion
  const document = documents.selectedDocument
  if (!props.writable || !version?.task?.retryable || !document) return false
  const pendingVersion = document.pendingVersion
  const activeVersion = document.activeVersion
  return (!pendingVersion || pendingVersion.versionId === version.versionId)
    && (!activeVersion || activeVersion.versionNo <= version.versionNo)
}

function canCancelVersion(): boolean {
  return props.writable && Boolean(documents.selectedVersion?.task?.cancellable)
}

function retrySelectedTask() {
  const version = documents.selectedVersion
  if (version) void documents.retryTask(version)
}

function cancelSelectedTask() {
  const version = documents.selectedVersion
  if (version) void documents.cancelTask(version)
}

function chunkLocation(chunk: DocumentChunk): string {
  const parts: string[] = []
  if (chunk.section) parts.push(chunk.section)
  if (chunk.pageNo !== null) parts.push(`第 ${chunk.pageNo} 页`)
  parts.push(`字符 ${chunk.startOffset}-${chunk.endOffset}`)
  return parts.join(' · ') || '未标注位置'
}

function selectDocument(documentId: string) {
  activeTab.value = 'content'
  void documents.selectDocument(documentId)
}

function selectVersion(versionId: string) {
  const version = documents.versions.find((item) => item.versionId === versionId)
  if (version) void documents.selectVersion(version)
}

function retryDetail() {
  const documentId = documents.selectedDocumentId
  if (documentId) void documents.selectDocument(documentId)
}

function selectVersionFile(event: Event) {
  const input = event.target
  const documentId = documents.selectedDocument?.documentId
  if (!(input instanceof HTMLInputElement) || !documentId) return
  const selected = documentUpload.selectFile(documentId, input.files?.[0] ?? null)
  if (!selected) input.value = ''
}

async function submitVersion(event: Event) {
  const document = documents.selectedDocument
  if (!document || document.pendingVersion) return
  const form = event.currentTarget instanceof HTMLFormElement ? event.currentTarget : null
  const receipt = await documentUpload.submitVersion(document.documentId, document.revision)
  if (!receipt) return
  form?.reset()
  versionUploadVisible.value = false
  await documents.selectDocument(document.documentId)
}

function versionUploadSummary(): string {
  const state = versionUploadState.value
  if (!state?.receipt) return ''
  if ('reused' in state.receipt && state.receipt.reused) return '文件内容与当前版本一致，已复用当前可用版本。'
  if (!state.job) return '新版本已受理，正在读取任务状态。'
  return jobSummary(state.job) || '新版本已受理。'
}

function closeDetail() {
  documents.resetSelection()
}

async function deleteSelectedDocument() {
  if (await documents.deleteSelectedDocument()) versionUploadVisible.value = false
}

onMounted(documents.loadDocuments)
</script>

<template>
  <section class="document-management" aria-labelledby="document-management-heading">
    <div class="section-heading document-management-heading">
      <div>
        <span class="eyebrow">DOCUMENT INSPECTION</span>
        <h2 id="document-management-heading">{{ documents.selectedDocument ? detailTitle : `${knowledgeBaseName} · 文档` }}</h2>
        <p>{{ documents.selectedDocument ? '查看版本、原文与分块结果。' : '查看已上传文档及其处理状态。' }}</p>
      </div>
      <div class="document-management-actions">
        <ElButton v-if="documents.selectedDocument" text @click="closeDetail">返回文档列表</ElButton>
        <ElButton v-else text @click="emit('close')">收起</ElButton>
      </div>
    </div>

    <ElAlert
      v-if="documents.documentsError"
      class="document-alert"
      type="error"
      :closable="false"
      :title="documents.documentsError"
      show-icon
    >
      <template #default><ElButton text type="primary" @click="documents.loadDocuments">重试</ElButton></template>
    </ElAlert>

    <ElAlert
      v-if="documents.detailError"
      class="document-alert"
      type="error"
      :closable="false"
      :title="documents.detailError"
      show-icon
    >
      <template #default><ElButton text type="primary" @click="retryDetail">重试</ElButton></template>
    </ElAlert>

    <ElAlert
      v-if="documents.deleteError"
      class="document-alert"
      type="error"
      :closable="false"
      :title="documents.deleteError"
      show-icon
    />

    <ElAlert
      v-if="documents.deleteNotice"
      class="document-alert"
      type="success"
      :closable="false"
      :title="documents.deleteNotice"
      show-icon
    />

    <section v-if="!documents.selectedDocument" class="document-list-panel" aria-label="文档列表">
      <ElSkeleton v-if="documents.documentsLoading || documents.detailLoading" :rows="4" animated />
      <ElEmpty v-else-if="documents.documents.length === 0 && !documents.documentsError" description="当前知识库尚无文档" />
      <div v-else class="document-list" role="list">
        <button
          v-for="document in documents.documents"
          :key="document.documentId"
          class="document-list-item"
          type="button"
          role="listitem"
          @click="selectDocument(document.documentId)"
        >
          <span class="document-list-name">{{ document.name }}</span>
          <span class="document-list-meta">
            <ElTag v-if="document.activeVersion" size="small" type="success">当前 V{{ document.activeVersion.versionNo }}</ElTag>
            <ElTag v-if="document.pendingVersion" size="small" type="warning">待处理 V{{ document.pendingVersion.versionNo }}</ElTag>
            <span>更新于 {{ formatDate(document.updatedAt) }}</span>
          </span>
        </button>
      </div>
    </section>

    <section v-else class="document-detail-panel" aria-label="文档详情">
      <ElSkeleton v-if="documents.detailLoading" :rows="5" animated />
      <template v-else-if="documents.selectedDocument">
        <div class="document-detail-meta">
          <span>修订 {{ documents.selectedDocument.revision }}</span>
          <span>创建于 {{ formatDate(documents.selectedDocument.createdAt) }}</span>
          <span>更新于 {{ formatDate(documents.selectedDocument.updatedAt) }}</span>
          <div v-if="writable" class="document-detail-actions">
            <ElButton
              class="document-version-upload-trigger"
              type="primary"
              :disabled="Boolean(documents.selectedDocument.pendingVersion) || documents.deleting"
              @click="versionUploadVisible = !versionUploadVisible"
            >上传新版本</ElButton>
            <ElPopconfirm
              title="删除后资料会立即退出检索，历史来源仅保留失效标记，确认删除吗？"
              confirm-button-text="删除文档"
              cancel-button-text="取消"
              width="320"
              @confirm="deleteSelectedDocument"
            >
              <template #reference>
                <ElButton
                  class="document-delete-trigger"
                  circle
                  plain
                  type="danger"
                  title="删除文档"
                  aria-label="删除文档"
                  :loading="documents.deleting"
                  :disabled="documents.deleting"
                >
                  <ElIcon><Delete /></ElIcon>
                </ElButton>
              </template>
            </ElPopconfirm>
          </div>
        </div>

        <section v-if="writable && versionUploadVisible" class="document-version-upload" aria-label="上传文档新版本">
          <div class="document-upload-heading">
            <div>
              <h3>上传新版本</h3>
              <p>仅支持 UTF-8 Markdown 或 TXT，单个文件不超过 5 MB。发布前将保留当前可用版本。</p>
            </div>
            <ElTag v-if="versionUploadState?.receipt" size="small" :type="versionUploadState.job?.status === 'SUCCEEDED' ? 'success' : 'warning'">
              {{ versionUploadState.job?.status ?? versionUploadState.receipt.status }}
            </ElTag>
          </div>
          <form class="document-version-upload-form" @submit.prevent="submitVersion">
            <label :for="`document-version-file-${documents.selectedDocument.documentId}`">文档文件</label>
            <input
              :id="`document-version-file-${documents.selectedDocument.documentId}`"
              class="document-version-file-input"
              type="file"
              accept=".md,.markdown,.txt,text/markdown,text/plain"
              :disabled="versionUploadState?.submitting"
              @change="selectVersionFile"
            >
            <span class="document-version-file-name">{{ versionUploadState?.file?.name ?? '尚未选择文件' }}</span>
            <div class="document-version-upload-actions">
              <ElButton
                type="primary"
                native-type="submit"
                :loading="versionUploadState?.submitting"
                :disabled="!versionUploadState?.file || versionUploadState.submitting"
              >{{ versionUploadState?.error ? '重新提交' : '提交新版本' }}</ElButton>
              <ElButton :disabled="versionUploadState?.submitting" @click="versionUploadVisible = false">取消</ElButton>
            </div>
          </form>
          <p v-if="versionUploadState?.error" class="document-upload-error" role="alert">{{ versionUploadState.error }}</p>
          <p v-if="versionUploadState?.jobError" class="document-upload-error" role="alert">
            {{ versionUploadState.jobError }}
            <ElButton text type="primary" :loading="versionUploadState.jobLoading" @click="documentUpload.refreshJob(documents.selectedDocument.documentId)">重新查询</ElButton>
          </p>
        </section>

        <div v-if="writable && versionUploadState?.receipt" class="document-upload-receipt document-version-upload-receipt" role="status">
          <strong>{{ versionUploadSummary() }}</strong>
          <span>版本 {{ versionUploadState.receipt.versionId }}</span>
          <span>任务 {{ versionUploadState.receipt.jobId }}</span>
        </div>

        <ElAlert
          v-else-if="writable && documents.selectedDocument.pendingVersion"
          class="document-alert"
          type="warning"
          :closable="false"
          title="当前已有待处理版本，请等待发布完成；失败版本需通过后续重试或取消流程处理。"
          show-icon
        />

        <div class="document-inspection-grid">
          <aside class="document-version-list" aria-label="文档版本">
            <div class="document-version-heading"><h3>版本</h3></div>
            <ElEmpty v-if="documents.versions.length === 0 && !documents.versionsLoading" description="暂无可查看版本" :image-size="52" />
            <button
              v-for="version in documents.versions"
              :key="version.versionId"
              class="document-version-item"
              :class="{ active: documents.selectedVersion?.versionId === version.versionId }"
              type="button"
              @click="selectVersion(version.versionId)"
            >
              <span class="document-version-title">V{{ version.versionNo }} · {{ version.originalFilename }}</span>
              <span class="document-version-meta">
                <ElTag size="small" :type="versionStatusType(version.status)">{{ versionStatusLabel(version.status) }}</ElTag>
                <ElTag v-if="version.active" size="small" type="success">当前</ElTag>
                <ElTag v-if="version.pending" size="small" type="warning">待处理</ElTag>
              </span>
              <span class="document-version-info">{{ formatSize(version.fileSize) }} · {{ formatDate(version.updatedAt) }}</span>
              <span v-if="jobSummary(version.task)" class="document-version-job">{{ jobSummary(version.task) }}</span>
            </button>
            <ElButton
              v-if="documents.canLoadMoreVersions"
              class="document-load-more"
              text
              type="primary"
              :loading="documents.versionsLoading"
              @click="documents.loadVersions()"
            >加载更多版本</ElButton>
            <ElSkeleton v-if="documents.versionsLoading" :rows="2" animated />
          </aside>

          <section class="document-preview" aria-label="版本内容">
            <template v-if="documents.selectedVersion">
              <div class="document-preview-heading">
                <div>
                  <h3>V{{ documents.selectedVersion.versionNo }} · {{ documents.selectedVersion.originalFilename }}</h3>
                  <span>{{ documents.selectedVersion.mediaType }} · {{ documents.selectedVersion.chunkCount ?? 0 }} 个分块</span>
                </div>
                <div class="document-tabs" role="tablist" aria-label="内容视图">
                  <ElButton :type="activeTab === 'content' ? 'primary' : 'default'" @click="activeTab = 'content'">原文</ElButton>
                  <ElButton :type="activeTab === 'chunks' ? 'primary' : 'default'" @click="activeTab = 'chunks'">分块</ElButton>
                </div>
              </div>

              <div v-if="selectedTask" class="document-task-control" aria-live="polite">
                <div>
                  <strong>{{ jobSummary(selectedTask) }}</strong>
                  <span>当前阶段已执行 {{ selectedTask.attempt }}/{{ selectedTask.maxAttempts }} 次</span>
                </div>
                <div v-if="canRetryVersion() || canCancelVersion()" class="document-task-actions">
                  <ElButton
                    v-if="canRetryVersion()"
                    type="primary"
                    plain
                    :loading="documents.taskAction === 'retry' && documents.taskActionJobId === selectedTask.jobId"
                    :disabled="documents.taskAction !== null"
                    @click="retrySelectedTask"
                  >重新处理</ElButton>
                  <ElPopconfirm
                    v-if="canCancelVersion()"
                    title="确认取消当前文档处理任务？"
                    confirm-button-text="确认取消"
                    cancel-button-text="返回"
                    @confirm="cancelSelectedTask"
                  >
                    <template #reference>
                      <ElButton
                        type="danger"
                        plain
                        :loading="documents.taskAction === 'cancel' && documents.taskActionJobId === selectedTask.jobId"
                        :disabled="documents.taskAction !== null"
                      >取消处理</ElButton>
                    </template>
                  </ElPopconfirm>
                </div>
              </div>
              <ElAlert
                v-if="documents.taskActionError"
                class="document-alert"
                type="error"
                :closable="false"
                :title="documents.taskActionError"
                show-icon
              />

              <ElAlert v-if="documents.contentError && activeTab === 'content'" class="document-alert" type="error" :closable="false" :title="documents.contentError" show-icon>
                <template #default><ElButton text type="primary" @click="documents.loadContent()">重试</ElButton></template>
              </ElAlert>
              <ElAlert v-if="documents.chunksError && activeTab === 'chunks'" class="document-alert" type="error" :closable="false" :title="documents.chunksError" show-icon>
                <template #default><ElButton text type="primary" @click="documents.loadChunks(true)">重试</ElButton></template>
              </ElAlert>

              <ElSkeleton v-if="activeTab === 'content' && documents.contentLoading" :rows="8" animated />
              <pre v-else-if="activeTab === 'content' && documents.content" class="document-content">{{ documents.content.content }}</pre>
              <ElEmpty v-else-if="activeTab === 'content' && !documents.contentError" description="当前版本没有可显示的原文" :image-size="72" />

              <template v-if="activeTab === 'chunks'">
                <ElSkeleton v-if="documents.chunksLoading && documents.chunks.length === 0" :rows="6" animated />
                <ElEmpty v-else-if="documents.chunks.length === 0 && !documents.chunksError" description="当前版本尚未生成分块" :image-size="72" />
                <div v-else class="document-chunk-list" role="list">
                  <article v-for="chunk in documents.chunks" :key="chunk.chunkId" class="document-chunk-item" role="listitem">
                    <div class="document-chunk-heading">
                      <strong>分块 {{ chunk.chunkIndex }}</strong>
                      <span>{{ chunkLocation(chunk) }}</span>
                    </div>
                    <p v-if="chunk.content">{{ chunk.content }}</p>
                    <p v-else class="document-chunk-empty">分块正文暂不可用。</p>
                    <span class="document-chunk-meta">
                      {{ chunk.charCount }} 字符 · {{ chunk.tokenCount ?? 0 }} Token
                    </span>
                  </article>
                </div>
                <ElButton
                  v-if="documents.canLoadMoreChunks"
                  class="document-load-more"
                  text
                  type="primary"
                  :loading="documents.chunksLoading"
                  @click="documents.loadChunks()"
                >加载更多分块</ElButton>
              </template>
            </template>
            <ElEmpty v-else description="请选择一个版本" :image-size="72" />
          </section>
        </div>
      </template>
    </section>
  </section>
</template>
