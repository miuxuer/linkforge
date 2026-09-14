import request from './request'

/** 创建短链。 */
export function createLink(data) {
  return request.post('/link', data)
}

/** 我的短链分页。params: { page, pageSize, keyword, status } */
export function getLinkPage(params) {
  return request.get('/link/page', { params })
}

/** 修改短链（整条覆盖语义，没传的字段会被清空）。 */
export function updateLink(id, data) {
  return request.put(`/link/${id}`, data)
}

/** 删除短链（逻辑删除）。 */
export function deleteLink(id) {
  return request.delete(`/link/${id}`)
}

/**
 * 二维码图片的地址。
 *
 * 不是接口调用，是给 <img src> 用的。因为要带 token，
 * 不能直接放 URL —— 详见 LinkDetailView 里的处理。
 */
export function getQrCodeUrl(id, size = 300) {
  return `/api/link/${id}/qrcode?size=${size}`
}
