<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useUserStore } from '@/store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

// Element Plus 的表单校验规则。前端校验只是为了少一次白跑的请求，
// 真正的校验在后端 —— 请求可以绕过前端直接构造
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function onSubmit() {
  // validate() 校验不通过会 reject，这里必须接住，否则控制台一堆未捕获的报错
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login(form)
    ElMessage.success('登录成功')

    // 回到用户本来想去的页面（被守卫拦下来时记在 query 里）。
    // 没有的话就去数据看板
    await router.replace(route.query.redirect || '/dashboard')
  } catch {
    // 错误提示已经在 axios 拦截器里统一弹过了，这里不用重复弹
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <div class="brand">
        <h1>LinkForge</h1>
        <p>短链管理平台</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="onSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
          />
        </el-form-item>

        <el-button
          type="primary"
          class="submit"
          size="large"
          :loading="loading"
          @click="onSubmit"
        >
          登录
        </el-button>
      </el-form>

      <div class="footer">
        还没有账号？
        <router-link to="/register">立即注册</router-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.auth-card {
  width: 400px;
  padding: 8px;
}

.brand {
  text-align: center;
  margin-bottom: 24px;
}

.brand h1 {
  margin: 0;
  font-size: 28px;
  letter-spacing: 1px;
}

.brand p {
  margin: 6px 0 0;
  color: #909399;
  font-size: 13px;
}

.submit {
  width: 100%;
}

.footer {
  margin-top: 18px;
  text-align: center;
  font-size: 13px;
  color: #606266;
}

.footer a {
  color: var(--el-color-primary);
}
</style>
