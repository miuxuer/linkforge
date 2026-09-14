<script setup>
// 工程骨架阶段的占位页面。
// 下一步接上 vue-router 之后，这里会换成 <router-view />，
// 由路由决定显示哪个页面。
import { ref } from 'vue'

const backendStatus = ref('未检测')
const checking = ref(false)

/**
 * 探一下后端通不通。
 *
 * 这里故意打一个需要登录的接口：不带 token 时后端会返回 401，
 * 而 401 恰恰说明"后端是活的、只是没登录" —— 比返回 200 更有信息量。
 * 如果连不上，vite 的代理会报错，说明后端没启动。
 */
async function checkBackend() {
  checking.value = true
  try {
    const response = await fetch('/api/user/profile')
    backendStatus.value =
      response.status === 401
        ? '已连通（未登录，符合预期）'
        : `已连通（HTTP ${response.status}）`
  } catch (error) {
    backendStatus.value = '连不上，后端可能没启动'
  } finally {
    checking.value = false
  }
}
</script>

<template>
  <div class="shell">
    <el-card class="card">
      <h1>LinkForge</h1>
      <p class="subtitle">短链管理平台</p>

      <el-divider />

      <p class="hint">
        前端工程已经跑起来了。下一步会接上路由和页面 ——
        登录注册、短链管理、二维码、数据看板。
      </p>

      <div class="actions">
        <el-button type="primary" :loading="checking" @click="checkBackend">
          检测后端连通性
        </el-button>
        <el-tag :type="backendStatus.includes('连不上') ? 'danger' : 'info'">
          {{ backendStatus }}
        </el-tag>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.card {
  width: 460px;
  text-align: center;
  padding: 12px;
}

h1 {
  margin: 0;
  font-size: 32px;
  letter-spacing: 1px;
}

.subtitle {
  margin: 8px 0 0;
  color: #909399;
}

.hint {
  color: #606266;
  line-height: 1.7;
  text-align: left;
}

.actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 8px;
}
</style>
