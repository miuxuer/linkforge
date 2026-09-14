import request from './request'

/**
 * 上传图片，返回可访问的 URL。
 *
 * @param file File 对象
 */
export function uploadImage(file) {
  const formData = new FormData()
  // 字段名必须是 file —— 后端写的是 @RequestParam("file")
  formData.append('file', file)
  return request.post('/upload', formData, {
    // 不手写 Content-Type：浏览器要自己往 multipart 里塞 boundary，
    // 手写死的那个 Content-Type 少了 boundary，后端解析出来是空的
    headers: { 'Content-Type': 'multipart/form-data' },
    // 上传比普通请求慢，给宽松一点的超时
    timeout: 60000
  })
}
