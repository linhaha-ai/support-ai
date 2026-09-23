import { effectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { INGESTION_JOB_POLL_INTERVAL_MS, useDocumentUpload } from '../useDocumentUpload'

const fetchMock = vi.fn<typeof fetch>()

const knowledgeBaseId = '4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'
const receipt = {
  documentId: '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a',
  versionId: '43ec5c35-baf0-467f-b671-6ad6e9b8d4a8',
  jobId: '4e151efd-4c49-4263-8c43-a5589261d52b',
  status: 'QUEUED' as const,
}

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function queuedJob() {
  return {
    jobId: receipt.jobId,
    versionId: receipt.versionId,
    status: 'QUEUED',
    stage: 'PARSE',
    attempt: 0,
    maxAttempts: 3,
    completedChunks: 0,
    totalChunks: 0,
    progressPercent: 0,
    retryable: false,
    cancellable: true,
    errorCode: null,
    errorMessage: null,
    errorStage: null,
    nextRunAt: null,
    startedAt: null,
    finishedAt: null,
    updatedAt: '2026-09-21T10:00:00+08:00',
  }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('文档上传任务轮询', () => {
  it('组件作用域销毁后清理非终态任务的下一次轮询', async () => {
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok(receipt, 202))
      .mockResolvedValueOnce(ok(queuedJob()))

    const scope = effectScope()
    const documentUpload = scope.run(() => useDocumentUpload())
    if (!documentUpload) throw new Error('未能创建文档上传状态')
    documentUpload.selectFile(knowledgeBaseId, new File(['# 产品说明'], 'guide.md', { type: 'text/markdown' }))

    await documentUpload.submit(knowledgeBaseId)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(documentUpload.stateFor(knowledgeBaseId).job?.status).toBe('QUEUED')

    scope.stop()
    await vi.advanceTimersByTimeAsync(INGESTION_JOB_POLL_INTERVAL_MS)

    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})
