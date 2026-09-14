/**
 * 复制文本到剪贴板。
 *
 * 优先用 navigator.clipboard，它需要"安全上下文"（https 或 localhost）——
 * 部署到 http 的服务器上时会直接不可用，所以要有兜底。
 *
 * 兜底用已经废弃的 document.execCommand('copy')：虽然标准上不推荐，
 * 但它是唯一能在非安全上下文里工作的方案，而且所有浏览器都还支持。
 *
 * @returns {Promise<boolean>} 是否复制成功
 */
export async function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 落到下面的兜底
    }
  }

  try {
    // 创建一个看不见的 textarea，选中它再执行复制
    const textarea = document.createElement('textarea')
    textarea.value = text
    // 放到视口外，避免页面滚动跳动
    textarea.style.position = 'fixed'
    textarea.style.top = '-9999px'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}
