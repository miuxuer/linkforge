/**
 * localStorage 的 key 集中在这里。
 *
 * 散落成字符串字面量的话，改名字时漏改一处就会出现
 * "登录明明成功了，但下一个请求没带上 token" 这种莫名其妙的问题。
 */
export const TOKEN_KEY = 'linkforge_token'
