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
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/knowledge-bases', expect.objectContaining({
      method: 'GET', credentials: 'same-origin',
    }))
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
