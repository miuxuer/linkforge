import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as userApi from '@/api/user'
import { TOKEN_KEY } from '@/utils/storage'

/**
 * 登录用户的状态。
 *
 * token 放 localStorage 而不是内存里：刷新页面内存就清空了，
 * 用户会发现"每刷新一次就要重新登录一次"。
 * （localStorage 的代价是 XSS 能读到它 —— 防 XSS 靠框架本身的转义，
 * 而不是靠换个存储位置。）
 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const profile = ref(null)

  /** 是否已登录（只看本地有没有 token，不保证 token 还有效）。 */
  const isLoggedIn = computed(() => Boolean(token.value))

  /** 是不是管理员。后端返回 role: 1 表示管理员。 */
  const isAdmin = computed(() => profile.value?.role === 1)

  function setToken(value) {
    token.value = value
    localStorage.setItem(TOKEN_KEY, value)
  }

  /** 清空登录状态。401 和主动退出都走这里。 */
  function clear() {
    token.value = ''
    profile.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  /** 登录：拿到 token 存起来，顺便取一次资料。 */
  async function login(form) {
    const data = await userApi.login(form)
    setToken(data.token)
    // 登录接口本身就返回了资料，不用再多请求一次
    profile.value = {
      id: data.id,
      username: data.username,
      nickname: data.nickname,
      avatar: data.avatar,
      role: data.role
    }
    return data
  }

  /**
   * 拉取当前用户资料。
   *
   * 路由守卫用它来判断"这个 token 还有效吗" —— token 过期或账号被禁用时
   * 后端返回 401/403，axios 拦截器会跳登录页。
   */
  async function loadProfile() {
    profile.value = await userApi.getProfile()
    return profile.value
  }

  /** 改完资料之后同步本地状态，免得页面还显示旧的昵称。 */
  function setProfile(value) {
    profile.value = value
  }

  function logout() {
    clear()
  }

  return {
    token,
    profile,
    isLoggedIn,
    isAdmin,
    setToken,
    setProfile,
    login,
    loadProfile,
    logout
  }
})
