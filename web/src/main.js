import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { createPinia } from 'pinia'

import App from './App.vue'
import './styles/main.css'

const app = createApp(App)

// Pinia 要在 Router 之前注册：路由守卫里会用到 store，
// 顺序反了的话守卫执行时 store 还没装好
app.use(createPinia())
app.use(ElementPlus)

app.mount('#app')
