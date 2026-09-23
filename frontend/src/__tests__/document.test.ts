import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchDocument,
  fetchDocumentChunks,
  fetchDocumentContent,
  fetchDocuments,
  fetchDocumentVersions,
  retryIngestionJob,
} from '../document'

const fetchMock = vi.fn<typeof fetch>()
const knowledgeBaseId = '4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234'
const documentId = '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a'
const versionId = '43ec5c35-baf0-467f-b671-6ad6e9b8d4a8'

function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

const task = {
  jobId: '4e151efd-4c49-4263-8c43-a5589261d52b', versionId, status: 'RUNNING', stage: 'CHUNK',
  attempt: 1, maxAttempts: 3, completedChunks: 2, totalChunks: 5, progressPercent: 40,
  retryable: false, cancellable: true, errorCode: null, errorMessage: null, errorStage: null, nextRunAt: null,
  startedAt: '2026-09-21T10:00:00+08:00', finishedAt: null, updatedAt: '2026-09-21T10:00:00+08:00',
}
const version = {
  versionId, versionNo: 2, status: 'BUILDING', active: false, pending: true,
  originalFilename: 'guide.md', fileSize: 1024, mediaType: 'text/markdown', chunkCount: 5,
  task, createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
}

beforeEach(() => vi.stubGlobal('fetch', fetchMock))
afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('文档查询接口', () => {
  it('按既定地址读取列表、详情、版本、原文和分块游标', async () => {
    fetchMock.mockResolvedValueOnce(ok([{
      documentId, name: '产品说明', activeVersion: null, pendingVersion: version,
      revision: 1, createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
    }]))
      .mockResolvedValueOnce(ok({
        documentId, knowledgeBaseId, name: '产品说明', activeVersion: null, pendingVersion: version,
        revision: 1, createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
      }))
      .mockResolvedValueOnce(ok({ items: [version], nextCursor: 'next-version' }))
      .mockResolvedValueOnce(ok({
        documentId, versionId, versionNo: 2, active: false, pending: true,
        originalFilename: 'guide.md', mediaType: 'text/markdown', content: '# 产品说明',
      }))
      .mockResolvedValueOnce(ok({ items: [{
        chunkId: '9c1c5536-4cb1-4017-b978-cbac518a00f4', chunkIndex: 0, section: '概览', pageNo: null,
        startOffset: 0, endOffset: 6, charCount: 6, tokenCount: 3, content: '产品说明正文',
      }], nextCursor: 'next-chunk' }))

    const list = await fetchDocuments(knowledgeBaseId)
    const detail = await fetchDocument(documentId)
    const versions = await fetchDocumentVersions(documentId, 20, 'cursor-value')
    const content = await fetchDocumentContent(documentId, versionId)
    const chunks = await fetchDocumentChunks(documentId, versionId, 50, 'chunk-cursor')

    expect(list[0]?.pendingVersion?.status).toBe('BUILDING')
    expect(detail.knowledgeBaseId).toBe(knowledgeBaseId)
    expect(versions.nextCursor).toBe('next-version')
    expect(content.pending).toBe(true)
    expect(chunks.items[0]?.chunkIndex).toBe(0)
    expect(fetchMock).toHaveBeenNthCalledWith(1, `/api/knowledge-bases/${knowledgeBaseId}/documents`, expect.objectContaining({ method: 'GET' }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, `/api/documents/${documentId}`, expect.objectContaining({ method: 'GET' }))
    expect(fetchMock).toHaveBeenNthCalledWith(3, `/api/documents/${documentId}/versions?limit=20&cursor=cursor-value`, expect.objectContaining({ method: 'GET' }))
    expect(fetchMock).toHaveBeenNthCalledWith(4, `/api/documents/${documentId}/versions/${versionId}/content`, expect.objectContaining({ method: 'GET' }))
    expect(fetchMock).toHaveBeenNthCalledWith(5, `/api/documents/${documentId}/versions/${versionId}/chunks?limit=50&cursor=chunk-cursor`, expect.objectContaining({ method: 'GET' }))
  })

  it('拒绝版本状态和分块字段不符合契约的成功响应', async () => {
    fetchMock.mockResolvedValueOnce(ok({ items: [{ ...version, status: 'PROCESSING' }], nextCursor: null }))
    await expect(fetchDocumentVersions(documentId)).rejects.toMatchObject({ kind: 'invalid-response' })

    fetchMock.mockResolvedValueOnce(ok({ items: [{ chunkId: 'chunk', index: 0 }], nextCursor: null }))
    await expect(fetchDocumentChunks(documentId, versionId)).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it('携带最新 CSRF 令牌提交人工重试并校验任务响应', async () => {
    const retriedTask = { ...task, status: 'QUEUED', cancellable: true }
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(ok(retriedTask))

    const result = await retryIngestionJob(task.jobId)

    expect(result.status).toBe('QUEUED')
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/auth/csrf', expect.objectContaining({ method: 'GET' }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, `/api/ingestion-jobs/${task.jobId}/retry`, expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
    }))
  })
})
