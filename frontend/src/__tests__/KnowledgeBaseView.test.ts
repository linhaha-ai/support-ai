import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import KnowledgeBaseView from '../KnowledgeBaseView.vue'

const fetchMock = vi.fn<typeof fetch>()
let wrapper: VueWrapper | undefined

const ordinaryUser = {
  id: '7dfbb1ea-4129-452c-92de-3a5d1ad1f68a',
  username: 'support1',
  displayName: '技术支持一号',
  role: 'USER',
}
const adminUser = { ...ordinaryUser, id: '9f7bf95e-e7e5-4d09-ac60-43e03c16a2c3', username: 'admin', role: 'ADMIN' }
const summary = {
  id: '4f8ac4b4-1c89-452c-a5be-f3a8cb0d1234', name: '产品支持', description: '产品使用说明', status: 'ACTIVE', permission: 'READ', revision: 1, updatedAt: '2026-09-21T10:00:00+08:00',
}
const writableSummary = { ...summary, permission: 'WRITE' as const }
const management = {
  ...summary, ownerId: adminUser.id, createdAt: '2026-09-20T10:00:00+08:00',
}
const detail = {
  ...management, currentPermission: 'WRITE', documentCount: 0,
}
const supportUser = {
  id: ordinaryUser.id,
  username: ordinaryUser.username,
  displayName: ordinaryUser.displayName,
  role: ordinaryUser.role,
  enabled: true,
}
const disabledUser = {
  id: 'c8403366-2c8f-43d8-b9c7-1c615e89f82d',
  username: 'disabled-support',
  displayName: '已停用支持账号',
  role: 'USER',
  enabled: false,
}
const member = {
  userId: supportUser.id,
  username: supportUser.username,
  displayName: supportUser.displayName,
  role: supportUser.role,
  enabled: true,
  permission: 'WRITE',
  grantedBy: adminUser.id,
  createdAt: '2026-09-21T10:00:00+08:00',
}
const csrf = { token: 'csrf-token', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' }

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'OK', data, traceId: 'trace-test' }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function failure(code: string, status: number) {
  return new Response(JSON.stringify({ code, traceId: 'trace-test' }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function queuedReceipt() {
  return {
    documentId: '7f5cf7b1-1a5c-4e60-a22a-6e7736e19a2a',
    versionId: '43ec5c35-baf0-467f-b671-6ad6e9b8d4a8',
    jobId: '4e151efd-4c49-4263-8c43-a5589261d52b',
    status: 'QUEUED',
  }
}

function ingestionJob(
  status: 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' = 'QUEUED',
  progressKnown = true,
) {
  return {
    jobId: queuedReceipt().jobId,
    versionId: queuedReceipt().versionId,
    status,
    stage: status === 'RUNNING' ? 'EMBED' : 'PARSE',
    attempt: status === 'RETRY_WAIT' ? 1 : 0,
    maxAttempts: 3,
    completedChunks: status === 'RUNNING' && progressKnown ? 4 : status === 'RUNNING' ? null : 0,
    totalChunks: status === 'RUNNING' && progressKnown ? 10 : status === 'RUNNING' ? null : 0,
    progressPercent: status === 'RUNNING' && progressKnown ? 40 : status === 'RUNNING' ? null : 0,
    retryable: status === 'FAILED' || status === 'RETRY_WAIT',
    cancellable: status === 'QUEUED' || status === 'RUNNING' || status === 'RETRY_WAIT',
    errorCode: status === 'FAILED' ? 'DOCUMENT_READ_FAILED' : null,
    errorMessage: status === 'FAILED' ? '原文件暂时无法读取' : null,
    errorStage: status === 'FAILED' ? 'PARSE' : null,
    nextRunAt: null,
    startedAt: status === 'RUNNING' ? '2026-09-21T10:00:00+08:00' : null,
    finishedAt: status === 'FAILED' ? '2026-09-21T10:01:00+08:00' : null,
    updatedAt: '2026-09-21T10:00:00+08:00',
  }
}

async function selectDocument(view: VueWrapper, file: File) {
  const input = view.get('.document-file-input')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
}

function mountView(user = ordinaryUser) {
  wrapper = mount(KnowledgeBaseView, { props: { user } })
  return wrapper
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('知识库视图', () => {
  it('普通用户仅加载可访问知识库，不展示管理控件', async () => {
    fetchMock.mockResolvedValueOnce(ok([summary]))
    const view = mountView()
    await flushPromises()

    expect(view.text()).toContain('产品支持')
    expect(view.text()).toContain('权限 READ')
    expect(view.text()).not.toContain('管理全部')
    expect(view.text()).not.toContain('新建知识库')
    expect(view.find('.document-upload').exists()).toBe(false)
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/knowledge-bases', expect.objectContaining({
      method: 'GET', credentials: 'same-origin',
    }))
  })

  it('可写知识库使用 CSRF 提交 multipart，并展示处理中回执', async () => {
    const receipt = queuedReceipt()
    fetchMock.mockResolvedValueOnce(ok([writableSummary]))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(ok(receipt, 202))
      .mockResolvedValueOnce(ok(ingestionJob()))
    const view = mountView()
    await flushPromises()

    await selectDocument(view, new File(['# 产品说明'], 'guide.md', { type: 'text/markdown' }))
    await view.get('.document-upload-form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/auth/csrf', expect.objectContaining({
      method: 'GET', credentials: 'same-origin',
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(3, `/api/knowledge-bases/${writableSummary.id}/documents`, expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: expect.objectContaining({ Accept: 'application/json', 'X-CSRF-TOKEN': 'csrf-token' }),
    }))
    const request = fetchMock.mock.calls[2]?.[1]
    expect(request?.body).toBeInstanceOf(FormData)
    expect((request?.body as FormData).get('file')).toBeInstanceOf(File)
    expect((request?.headers as Record<string, string>)['Content-Type']).toBeUndefined()
    expect(view.text()).toContain(receipt.documentId)
    expect(view.text()).toContain(receipt.versionId)
    expect(view.text()).toContain(receipt.jobId)
    expect(view.text()).toContain('QUEUED · 排队')
    expect(view.text()).toContain('任务 QUEUED，正在排队。')
    expect(view.text()).not.toContain('可问答')
  })

  it('文档任务运行时展示中文阶段与进度', async () => {
    fetchMock.mockResolvedValueOnce(ok([writableSummary]))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(ok(queuedReceipt(), 202))
      .mockResolvedValueOnce(ok(ingestionJob('RUNNING')))
    const view = mountView()
    await flushPromises()

    await selectDocument(view, new File(['# 产品说明'], 'guide.md', { type: 'text/markdown' }))
    await view.get('.document-upload-form').trigger('submit')
    await flushPromises()

    expect(view.text()).toContain('RUNNING · 正在向量化')
    expect(view.text()).toContain('任务 RUNNING，正在向量化。')
    expect(view.text()).toContain('进度 40%，已完成 4/10 个分块')
  })

  it('文档任务失败时展示脱敏错误与错误代码', async () => {
    fetchMock.mockResolvedValueOnce(ok([writableSummary]))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(ok(queuedReceipt(), 202))
      .mockResolvedValueOnce(ok(ingestionJob('FAILED')))
    const view = mountView()
    await flushPromises()

    await selectDocument(view, new File(['# 产品说明'], 'guide.md', { type: 'text/markdown' }))
    await view.get('.document-upload-form').trigger('submit')
    await flushPromises()

    expect(view.text()).toContain('FAILED · 处理失败')
    expect(view.text()).toContain('原文件暂时无法读取')
    expect(view.text()).toContain('错误代码 DOCUMENT_READ_FAILED')
  })

  it('文档任务运行但进度未知时仅展示中文阶段', async () => {
    fetchMock.mockResolvedValueOnce(ok([writableSummary]))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(ok(queuedReceipt(), 202))
      .mockResolvedValueOnce(ok(ingestionJob('RUNNING', false)))
    const view = mountView()
    await flushPromises()

    await selectDocument(view, new File(['# 产品说明'], 'guide.md', { type: 'text/markdown' }))
    await view.get('.document-upload-form').trigger('submit')
    await flushPromises()

    expect(view.text()).toContain('RUNNING · 正在向量化')
    expect(view.text()).toContain('任务 RUNNING，正在向量化。')
    expect(view.text()).not.toContain('进度 null')
    expect(view.text()).not.toContain('已完成 null')
  })

  it('任务状态查询失败时显示重新查询入口', async () => {
    fetchMock.mockResolvedValueOnce(ok([writableSummary]))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(ok(queuedReceipt(), 202))
      .mockResolvedValueOnce(failure('INGESTION_JOB_UNAVAILABLE', 503))
    const view = mountView()
    await flushPromises()

    await selectDocument(view, new File(['# 产品说明'], 'guide.md', { type: 'text/markdown' }))
    await view.get('.document-upload-form').trigger('submit')
    await flushPromises()

    expect(view.text()).toContain('知识库请求未完成，请稍后重试。')
    expect(view.text()).toContain('重新查询')
  })

  it('只读知识库不展示文档上传入口', async () => {
    fetchMock.mockResolvedValueOnce(ok([summary]))
    const view = mountView()
    await flushPromises()

    expect(view.find('.document-upload').exists()).toBe(false)
    expect(view.text()).not.toContain('提交上传')
  })

  it('文档上传失败后保留错误和文件，允许重新提交', async () => {
    fetchMock.mockResolvedValueOnce(ok([writableSummary]))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(failure('DOCUMENT_FILE_TOO_LARGE', 413))
    const view = mountView()
    await flushPromises()

    await selectDocument(view, new File(['# 产品说明'], 'guide.txt', { type: 'text/plain' }))
    await view.get('.document-upload-form').trigger('submit')
    await flushPromises()

    expect(view.text()).toContain('文件超过 5 MB 限制，请压缩或拆分后重试。')
    expect(view.text()).toContain('guide.txt')
    expect(view.get('.document-upload-actions .el-button').attributes('disabled')).toBeUndefined()
  })

  it('同一知识库上传期间阻止重复提交', async () => {
    let resolveCsrf: ((response: Response) => void) | undefined
    const csrfPending = new Promise<Response>((resolve) => {
      resolveCsrf = resolve
    })
    fetchMock.mockResolvedValueOnce(ok([writableSummary]))
      .mockImplementationOnce(() => csrfPending)
      .mockResolvedValueOnce(ok(queuedReceipt(), 202))
      .mockResolvedValueOnce(ok(ingestionJob()))
    const view = mountView()
    await flushPromises()

    await selectDocument(view, new File(['# 产品说明'], 'guide.md', { type: 'text/markdown' }))
    const form = view.get('.document-upload-form')
    await form.trigger('submit')
    await form.trigger('submit')

    expect(fetchMock).toHaveBeenCalledTimes(2)
    resolveCsrf?.(ok(csrf))
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(fetchMock.mock.calls.filter(([url]) => url === `/api/knowledge-bases/${writableSummary.id}/documents`)).toHaveLength(1)
  })

  it('管理员可切换到管理列表并携带状态筛选参数', async () => {
    fetchMock.mockResolvedValueOnce(ok([summary])).mockResolvedValueOnce(ok([management]))
    const view = mountView(adminUser)
    await flushPromises()

    await view.get('.knowledge-view-switch .el-button:last-child').trigger('click')
    await flushPromises()

    expect(view.text()).toContain(`所有者 ${adminUser.id}`)
    expect(view.get('.knowledge-item-actions').text()).toContain('编辑')
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/admin/knowledge-bases', expect.objectContaining({
      method: 'GET', credentials: 'same-origin',
    }))
  })

  it('管理员新建知识库前读取 CSRF 并携带动态请求头', async () => {
    fetchMock.mockResolvedValueOnce(ok([]))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(ok(detail))
      .mockResolvedValueOnce(ok([summary]))
    const view = mountView(adminUser)
    await flushPromises()

    await view.get('.create-knowledge-button').trigger('click')
    await view.get('#knowledge-name').setValue('  产品支持  ')
    await view.get('#knowledge-description').setValue('常见问题')
    await view.get('.knowledge-form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/auth/csrf', expect.objectContaining({
      method: 'GET', credentials: 'same-origin',
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/knowledge-bases', expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
    }))
    expect(fetchMock.mock.calls[2]?.[1]?.body).toBe(JSON.stringify({ name: '产品支持', description: '常见问题' }))
  })

  it('管理员编辑知识库时提交版本，并在 409 时保留表单内容', async () => {
    fetchMock.mockResolvedValueOnce(ok([summary]))
      .mockResolvedValueOnce(ok([management]))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(failure('KNOWLEDGE_BASE_CONFLICT', 409))
    const view = mountView(adminUser)
    await flushPromises()

    await view.get('.knowledge-view-switch .el-button:last-child').trigger('click')
    await flushPromises()
    await view.get('.knowledge-item-actions .el-button').trigger('click')
    await view.get('#knowledge-name').setValue('产品支持新版')
    await view.get('.knowledge-form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenNthCalledWith(4, `/api/knowledge-bases/${management.id}`, expect.objectContaining({
      method: 'PATCH',
      headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
      credentials: 'same-origin',
    }))
    expect(fetchMock.mock.calls[3]?.[1]?.body).toBe(JSON.stringify({
      name: '产品支持新版', description: '产品使用说明', status: 'ACTIVE', revision: 1,
    }))
    expect((view.get('#knowledge-name').element as HTMLInputElement).value).toBe('产品支持新版')
    expect(view.text()).toContain('版本已变化，表单内容已保留')
  })

  it('管理员可以加载成员、更新权限并撤销授权，停用账号不可选择', async () => {
    const updatedMember = { ...member, permission: 'READ' }
    fetchMock.mockResolvedValueOnce(ok([summary]))
      .mockResolvedValueOnce(ok([management]))
      .mockResolvedValueOnce(ok([supportUser, disabledUser]))
      .mockResolvedValueOnce(ok([member]))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(ok(updatedMember))
      .mockResolvedValueOnce(ok(csrf))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    const view = mountView(adminUser)
    await flushPromises()

    await view.get('.knowledge-view-switch .el-button:last-child').trigger('click')
    await flushPromises()
    await view.get('.knowledge-item-actions .el-button:last-child').trigger('click')
    await flushPromises()

    expect(view.text()).toContain('技术支持一号')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/users', expect.objectContaining({ method: 'GET' }))
    expect(fetchMock).toHaveBeenCalledWith(`/api/knowledge-bases/${management.id}/members`, expect.objectContaining({ method: 'GET' }))
    const disabledOption = view.findAllComponents({ name: 'ElOption' })
      .find((option) => option.props('value') === disabledUser.id)
    expect(disabledOption?.props('disabled')).toBe(true)

    await view.get('.member-actions .el-button:first-child').trigger('click')
    await view.get('.member-form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/knowledge-bases/${management.id}/members/${supportUser.id}`,
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
      }),
    )
    expect(fetchMock.mock.calls[5]?.[1]?.body).toBe(JSON.stringify({ permission: 'WRITE' }))

    const revokeButton = view.findAll('.member-actions .el-button')
      .find((button) => button.text() === '撤销')
    expect(revokeButton).toBeDefined()
    await revokeButton!.trigger('click')
    await flushPromises()

    expect(view.text()).toContain('确认撤销')
    expect(fetchMock).toHaveBeenCalledTimes(6)
    const confirmRevokeButton = view.findAll('.member-actions .el-button')
      .find((button) => button.text() === '确认撤销')
    expect(confirmRevokeButton).toBeDefined()
    await confirmRevokeButton!.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/knowledge-bases/${management.id}/members/${supportUser.id}`,
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-token' }),
      }),
    )
    expect(view.text()).toContain('暂未授权成员')
  })

  it('成员接口不可用时显示独立错误并允许重试', async () => {
    fetchMock.mockResolvedValueOnce(ok([summary]))
      .mockResolvedValueOnce(ok([management]))
      .mockResolvedValueOnce(ok([supportUser]))
      .mockResolvedValueOnce(failure('KNOWLEDGE_BASE_NOT_FOUND', 404))
    const view = mountView(adminUser)
    await flushPromises()

    await view.get('.knowledge-view-switch .el-button:last-child').trigger('click')
    await flushPromises()
    await view.get('.knowledge-item-actions .el-button:last-child').trigger('click')
    await flushPromises()

    expect(view.text()).toContain('知识库请求未完成，请稍后重试。')
    expect(view.get('.member-alert').text()).toContain('重试')
  })
})
