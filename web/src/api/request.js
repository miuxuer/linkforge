import axios from 'axios'
import { ElMessage } from 'element-plus'

import router from '@/router'
import { TOKEN_KEY } from '@/utils/storage'

/**
 * 全局唯一的 axios 实例。
 *
 * 不在各个页面里直接用 axios 的原因：那样每个调用点都要自己加 token、
 * 自己判断 code、自己处理 401 —— 总有几处会漏，而漏掉的那处就是
 * "token 过期了但页面没跳登录页，只是一直空白"。
 */
const request = axios.create({
  // 写相对路径，交给 Vite 的代理转发（生产环境前后端同域，一行都不用改）
  baseURL: '/api',
  // 超时必须有：没有的话请求会一直挂着，用户看到的是一个永远转不完的圈
  timeout: 10000
})

/**
 * 请求拦截器：统一带上 token。
 */
request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    // 请求头名字要和后端 `linkforge.jwt.token-name` 配的一致
    config.headers.token = token
  }
  return config
})

/**
 * 响应拦截器：把 {code, message, data} 拆开，只把 data 交给调用方。
 *
 * 这样页面里写的就是 `const list = await getLinkPage(params)`，
 * 不用每次都 `res.data.data`。
 */
request.interceptors.response.use(
  (response) => {
    const body = response.data

    // 后端约定 code=0 表示成功。注意这里是"HTTP 200 但业务失败"的情况 ——
    // 有些接口（比如导出）会返回 200 但 code 非 0
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body ? body.data : null
  },

  (error) => {
    // 没有 response 说明请求压根没发出去或超时了
    const status = error.response?.status
    const message = error.response?.data?.message

    if (status === 401) {
      // token 无效或过期。清掉本地状态并回登录页。
      // 带上 redirect 参数，登录成功后能回到用户本来想去的页面
      localStorage.removeItem(TOKEN_KEY)
      if (router.currentRoute.value.path !== '/login') {
        router.push({
          path: '/login',
          query: { redirect: router.currentRoute.value.fullPath }
        })
      }
      ElMessage.error(message || '登录已过期，请重新登录')
    } else if (status === 403) {
      // 403 和 401 必须分开处理：401 是"你没登录"，403 是"你登录了但没权限"。
      // 把 403 也当成 401 处理的话，普通用户访问管理端会被反复踢去登录页，
      // 而重新登录一万次也没用
      ElMessage.error(message || '没有访问权限')
    } else if (status === 413) {
      ElMessage.error(message || '上传内容过大')
    } else if (status) {
      ElMessage.error(message || `请求失败（HTTP ${status}）`)
    } else {
      ElMessage.error('网络异常，请检查后端是否已启动')
    }

    return Promise.reject(error)
  }
)

export default request
