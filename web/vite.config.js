import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  server: {
    port: 5173,

    // 开发期把 /api 代理到后端，而不是在后端配 CORS。
    //
    // 为什么用代理：浏览器的同源策略只认"协议+域名+端口"三者完全相同，
    // 5173 和 8080 是跨域，浏览器会先发 OPTIONS 预检。要么后端开 CORS
    // （等于在生产环境的响应头上长期挂一个允许跨域的配置），
    // 要么开发期用代理把两个端口"变成"一个。
    //
    // 代理还有个好处：前端代码里写的是相对路径 /api/xxx，
    // 部署到生产（前端静态文件和后端同域）时一行都不用改。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
