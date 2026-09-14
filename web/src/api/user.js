import request from './request'

/** 注册。 */
export function register(data) {
  return request.post('/user/register', data)
}

/** 登录，返回 { id, username, nickname, avatar, role, token }。 */
export function login(data) {
  return request.post('/user/login', data)
}

/** 查当前登录用户资料。 */
export function getProfile() {
  return request.get('/user/profile')
}

/** 修改当前登录用户资料（昵称、头像）。 */
export function updateProfile(data) {
  return request.put('/user/profile', data)
}
