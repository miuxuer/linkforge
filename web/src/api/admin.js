import request from './request'

/** 用户分页。params: { page, pageSize, keyword, status } */
export function getUserPage(params) {
  return request.get('/admin/user/page', { params })
}

/** 启用 / 禁用用户。status: 0=禁用 1=启用 */
export function updateUserStatus(id, status) {
  return request.put(`/admin/user/${id}/status`, null, { params: { status } })
}

/** 全部短链分页。params: { page, pageSize, keyword, userId, status } */
export function getAdminLinkPage(params) {
  return request.get('/admin/link/page', { params })
}

/** 强制删除违规短链。 */
export function forceDeleteLink(id) {
  return request.delete(`/admin/link/${id}`)
}

/** 操作日志分页。params: { page, pageSize, operateUser, status, keyword } */
export function getLogPage(params) {
  return request.get('/admin/log/page', { params })
}
