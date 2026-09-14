import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)

// 顺序有讲究：
// - Pinia 要在 router 之前，因为路由守卫里会用到 store
// - Element Plus 的 locale 要用中文，默认是英文（分页器会显示 "Go to"）
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
