import { effectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocumentDetail, DocumentVersionSummary } from '../document'
import { useDocuments } from '../useDocuments'

const fetchMock = vi.fn<typeof fetch>()
const documentId = '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a'
const firstVersionId = '43ec5c35-baf0-467f-b671-6ad6e9b8d4a8'
const secondVersionId = '5f8c21dd-19aa-4b7e-a41c-6e7736e19a2a'

function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function version(versionId: string, versionNo: number): DocumentVersionSummary {
  return {
    versionId, versionNo, status: 'READY', active: versionNo === 1, pending: false,
    originalFilename: 'guide.md', fileSize: 12, mediaType: 'text/markdown', chunkCount: 1, task: null,
    createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
  }
}

const detail: DocumentDetail = {
  documentId, knowledgeBaseId: '4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234', name: '产品说明',
  activeVersion: null, pendingVersion: null, revision: 1,
  createdAt: '2026-09-21T10:00:00+08:00', updatedAt: '2026-09-21T10:00:00+08:00',
}

beforeEach(() => vi.stubGlobal('fetch', fetchMock))
afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('文档检查异步状态', () => {
  it('快速切换版本后以新版本结果收敛，不保留旧请求的加载状态', async () => {
    let resolveOldContent: ((response: Response) => void) | undefined
    let resolveOldChunks: ((response: Response) => void) | undefined
    fetchMock.mockImplementation((path) => {
      const url = String(path)
      if (url.includes(firstVersionId) && url.endsWith('/content')) {
        return new Promise<Response>((resolve) => { resolveOldContent = resolve })
      }
      if (url.includes(firstVersionId) && url.includes('/chunks')) {
        return new Promise<Response>((resolve) => { resolveOldChunks = resolve })
      }
      if (url.includes(secondVersionId) && url.endsWith('/content')) {
        return Promise.resolve(ok({ documentId, versionId: secondVersionId, versionNo: 2, active: false, pending: false, originalFilename: 'guide.md', mediaType: 'text/markdown', content: '新版正文' }))
      }
      return Promise.resolve(ok({ items: [{ chunkId: 'chunk-2', chunkIndex: 0, section: null, pageNo: null, startOffset: 0, endOffset: 4, charCount: 4, tokenCount: 2, content: '新版分块' }], nextCursor: null }))
    })

    const scope = effectScope()
    const documents = scope.run(() => useDocuments(() => detail.knowledgeBaseId))
    if (!documents) throw new Error('未能创建文档状态')
    documents.selectedDocument.value = detail
    const first = version(firstVersionId, 1)
    const second = version(secondVersionId, 2)
    const firstRequest = documents.selectVersion(first)
    await flushPromises()
    await documents.selectVersion(second)

    expect(documents.content.value?.content).toBe('新版正文')
    expect(documents.chunks.value[0]?.content).toBe('新版分块')
    expect(documents.contentLoading.value).toBe(false)
    expect(documents.chunksLoading.value).toBe(false)

    resolveOldContent?.(ok({ documentId, versionId: firstVersionId, versionNo: 1, active: true, pending: false, originalFilename: 'guide.md', mediaType: 'text/markdown', content: '旧版正文' }))
    resolveOldChunks?.(ok({ items: [], nextCursor: null }))
    await firstRequest

    expect(documents.content.value?.versionId).toBe(secondVersionId)
    scope.stop()
  })

  it('删除成功后清空详情、使旧请求失效并重新加载列表', async () => {
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(new Response(null, { status: 202 }))
      .mockResolvedValueOnce(ok([]))
    const scope = effectScope()
    const documents = scope.run(() => useDocuments(() => detail.knowledgeBaseId))
    if (!documents) throw new Error('未能创建文档状态')
    documents.selectedDocumentId.value = documentId
    documents.selectedDocument.value = detail
    documents.versions.value = [version(firstVersionId, 1)]

    await expect(documents.deleteSelectedDocument()).resolves.toBe(true)

    expect(documents.selectedDocument.value).toBeNull()
    expect(documents.versions.value).toEqual([])
    expect(documents.documents.value).toEqual([])
    expect(documents.deleteNotice.value).toContain('立即退出检索')
    expect(fetchMock).toHaveBeenLastCalledWith(
      `/api/knowledge-bases/${detail.knowledgeBaseId}/documents`,
      expect.objectContaining({ method: 'GET' }),
    )
    scope.stop()
  })

  it('删除失败时保留当前详情并允许再次尝试', async () => {
    fetchMock.mockResolvedValueOnce(ok({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
    const scope = effectScope()
    const documents = scope.run(() => useDocuments(() => detail.knowledgeBaseId))
    if (!documents) throw new Error('未能创建文档状态')
    documents.selectedDocumentId.value = documentId
    documents.selectedDocument.value = detail

    await expect(documents.deleteSelectedDocument()).resolves.toBe(false)

    expect(documents.selectedDocument.value?.documentId).toBe(documentId)
    expect(documents.deleteError.value).toBe('文档暂时无法删除，请稍后重试。')
    expect(documents.deleting.value).toBe(false)
    scope.stop()
  })
})
