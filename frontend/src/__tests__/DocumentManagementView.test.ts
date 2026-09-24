import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import DocumentManagementView from '../DocumentManagementView.vue'

const fetchMock = vi.fn<typeof fetch>()
let wrapper: VueWrapper | undefined
const knowledgeBaseId = '4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'
const documentId = '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a'
const versionId = '43ec5c35-baf0-467f-b671-6ad6e9b8d4a8'

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

function failure(status = 503) {
  return new Response(JSON.stringify({ code: 'DOCUMENT_QUERY_UNAVAILABLE', traceId: 'trace-test' }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

const version = {
  versionId, versionNo: 2, status: 'FAILED', active: false, pending: true,
  originalFilename: 'guide.md', fileSize: 1024, mediaType: 'text/markdown', chunkCount: 0,
  task: {
    jobId: '4e151efd-4c49-4263-8c43-a5589261d52b', versionId, status: 'FAILED', stage: 'PARSE',
    attempt: 1, maxAttempts: 3, completedChunks: 0, totalChunks: 0, progressPercent: 0,
    retryable: true, cancellable: false, errorCode: 'DOCUMENT_READ_FAILED', errorMessage: '原文件暂时无法读取', errorStage: 'PARSE',
    nextRunAt: null, startedAt: '2026-09-21T10:00:00+08:00', finishedAt: '2026-09-21T10:01:00+08:00', updatedAt: '2026-09-21T10:01:00+08:00',
  },
  createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:01:00+08:00',
}

function documentList() {
  return [{
    documentId, name: '产品说明', activeVersion: null, pendingVersion: version,
    revision: 1, createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
  }]
}

function detail() {
  return {
    ...documentList()[0], knowledgeBaseId,
  }
}

function activeDetail() {
  const activeVersion = {
    ...version,
    status: 'READY',
    active: true,
    pending: false,
    task: { ...version.task, status: 'SUCCEEDED', retryable: false, cancellable: false, errorStage: null },
  }
  return {
    documentId, knowledgeBaseId, name: '产品说明', activeVersion, pendingVersion: null,
    revision: 3, createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
  }
}

function mountView(writable = false) {
  wrapper = mount(DocumentManagementView, { props: { knowledgeBaseId, knowledgeBaseName: '产品支持', writable } })
  return wrapper
}

beforeEach(() => vi.stubGlobal('fetch', fetchMock))
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('文档管理视图', () => {
  it('详情读取失败时显示错误和重试，并使用同一文档标识重新请求', async () => {
    fetchMock.mockResolvedValueOnce(ok(documentList()))
      .mockResolvedValueOnce(failure())
      .mockResolvedValueOnce(failure())
    const view = mountView()
    await flushPromises()

    await view.get('.document-list-item').trigger('click')
    await flushPromises()

    expect(view.text()).toContain('文档信息暂时无法读取，请稍后重试。')
    const retry = view.findAll('.document-alert .el-button').find((button) => button.text() === '重试')
    expect(retry).toBeDefined()
    await retry!.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenNthCalledWith(2, `/api/documents/${documentId}`, expect.objectContaining({ method: 'GET' }))
    expect(fetchMock).toHaveBeenNthCalledWith(3, `/api/documents/${documentId}`, expect.objectContaining({ method: 'GET' }))
  })

  it('展示失败版本的处理状态和脱敏任务错误', async () => {
    fetchMock.mockResolvedValueOnce(ok(documentList()))
      .mockResolvedValueOnce(ok(detail()))
      .mockResolvedValueOnce(ok({ items: [version], nextCursor: null }))
      .mockResolvedValueOnce(ok({
        documentId, versionId, versionNo: 2, active: false, pending: true,
        originalFilename: 'guide.md', mediaType: 'text/markdown', content: '# 产品说明',
      }))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
    const view = mountView(true)
    await flushPromises()

    await view.get('.document-list-item').trigger('click')
    await flushPromises()

    expect(view.text()).toContain('处理失败')
    expect(view.text()).toContain('解析阶段终止失败 · DOCUMENT_READ_FAILED · 原文件暂时无法读取')
    expect(view.text()).toContain('重新处理')
    expect(view.text()).toContain('# 产品说明')
  })

  it('只读用户不展示上传新版本入口，待处理版本则禁用入口', async () => {
    fetchMock.mockResolvedValueOnce(ok(documentList()))
      .mockResolvedValueOnce(ok(detail()))
      .mockResolvedValueOnce(ok({ items: [version], nextCursor: null }))
      .mockResolvedValueOnce(ok({
        documentId, versionId, versionNo: 2, active: false, pending: true,
        originalFilename: 'guide.md', mediaType: 'text/markdown', content: '# 产品说明',
      }))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
    const readOnlyView = mountView()
    await flushPromises()
    await readOnlyView.get('.document-list-item').trigger('click')
    await flushPromises()
    expect(readOnlyView.find('.document-version-upload-trigger').exists()).toBe(false)
    expect(readOnlyView.find('.document-delete-trigger').exists()).toBe(false)
    readOnlyView.unmount()

    fetchMock.mockReset()
    fetchMock.mockResolvedValueOnce(ok(documentList()))
      .mockResolvedValueOnce(ok(detail()))
      .mockResolvedValueOnce(ok({ items: [version], nextCursor: null }))
      .mockResolvedValueOnce(ok({
        documentId, versionId, versionNo: 2, active: false, pending: true,
        originalFilename: 'guide.md', mediaType: 'text/markdown', content: '# 产品说明',
      }))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
    const writableView = mountView(true)
    await flushPromises()
    await writableView.get('.document-list-item').trigger('click')
    await flushPromises()
    expect(writableView.get('.document-version-upload-trigger').attributes('disabled')).toBeDefined()
  })

  it('相同内容复用当前版本后保留提示并刷新文档详情', async () => {
    const active = activeDetail()
    const versionReceipt = {
      documentId, versionId, jobId: '93d95cdf-67ac-4ee3-a4f6-f6a2202b533d',
      status: 'SUCCEEDED', revision: 3, reused: true,
    }
    fetchMock.mockResolvedValueOnce(ok([active]))
      .mockResolvedValueOnce(ok(active))
      .mockResolvedValueOnce(ok({ items: [active.activeVersion], nextCursor: null }))
      .mockResolvedValueOnce(ok({
        documentId, versionId, versionNo: 2, active: true, pending: false,
        originalFilename: 'guide.md', mediaType: 'text/markdown', content: '# 产品说明',
      }))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok(versionReceipt))
      .mockResolvedValueOnce(ok({
        jobId: versionReceipt.jobId, versionId: versionReceipt.versionId, status: 'SUCCEEDED', stage: 'PUBLISH',
        attempt: 0, maxAttempts: 3, completedChunks: 0, totalChunks: 0, progressPercent: 0,
        retryable: false, cancellable: false, errorCode: null, errorMessage: null, errorStage: null, nextRunAt: null,
        startedAt: '2026-09-21T10:00:00+08:00', finishedAt: '2026-09-21T10:01:00+08:00', updatedAt: '2026-09-21T10:01:00+08:00',
      }))
      .mockResolvedValueOnce(ok(active))
      .mockResolvedValueOnce(ok({ items: [active.activeVersion], nextCursor: null }))
      .mockResolvedValueOnce(ok({
        documentId, versionId, versionNo: 2, active: true, pending: false,
        originalFilename: 'guide.md', mediaType: 'text/markdown', content: '# 产品说明',
      }))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
    const view = mountView(true)
    await flushPromises()
    await view.get('.document-list-item').trigger('click')
    await flushPromises()
    await view.get('.document-version-upload-trigger').trigger('click')
    const input = view.get('.document-version-file-input')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['# 更新说明'], 'guide-v2.md')] })
    await input.trigger('change')
    await view.get('.document-version-upload-form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(`/api/documents/${documentId}/versions`, expect.objectContaining({ method: 'POST' }))
    const versionRequest = fetchMock.mock.calls.find(([url]) => url === `/api/documents/${documentId}/versions`)?.[1]
    expect((versionRequest?.body as FormData).get('expectedRevision')).toBe('3')
    expect(fetchMock.mock.calls.filter(([url]) => url === `/api/documents/${documentId}`)).toHaveLength(2)
    expect(view.find('.document-version-upload-form').exists()).toBe(false)
    expect(view.text()).toContain('文件内容与当前版本一致，已复用当前可用版本。')
    expect(view.text()).toContain(`任务 ${versionReceipt.jobId}`)
  })

  it('写成员确认删除后返回列表并展示异步清理提示', async () => {
    const active = activeDetail()
    fetchMock.mockResolvedValueOnce(ok([active]))
      .mockResolvedValueOnce(ok(active))
      .mockResolvedValueOnce(ok({ items: [active.activeVersion], nextCursor: null }))
      .mockResolvedValueOnce(ok({
        documentId, versionId, versionNo: 2, active: true, pending: false,
        originalFilename: 'guide.md', mediaType: 'text/markdown', content: '# 产品说明',
      }))
      .mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
      .mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(new Response(null, { status: 202 }))
      .mockResolvedValueOnce(ok([]))
    const view = mountView(true)
    await flushPromises()
    await view.get('.document-list-item').trigger('click')
    await flushPromises()

    expect(view.get('.document-delete-trigger').attributes('aria-label')).toBe('删除文档')
    view.findComponent({ name: 'ElPopconfirm' }).vm.$emit('confirm')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(`/api/documents/${documentId}`, expect.objectContaining({
      method: 'DELETE',
      headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
    }))
    expect(view.text()).toContain('文档已立即退出检索')
    expect(view.find('.document-detail-panel').exists()).toBe(false)
  })
})
