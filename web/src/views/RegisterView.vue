<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { register } from '@/api/user'

const router = useRouter()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: ''
})

/**
 * 校验规则必须和后端 UserRegisterDTO 上的注解一致。
 *
 * 不一致的后果是：前端放行了、后端返回 400，用户看到一句
 * "用户名长度需在 4-20 位之间"，却明明填的是 3 位 —— 因为他看的是前端的规则。
 * 改后端注解时记得同步这里。
 */
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 20, message: '用户名长度需在 4-20 位之间', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z0-9_]+$/,
      message: '用户名只能包含字母、数字和下划线',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度需在 6-32 位之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      // 自定义校验：两次密码要一致。
      // 这条只能在前端做 —— 后端只收到一个密码，没法判断"两次输得一样吗"
      validator: (rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  nickname: [{ max: 20, message: '昵称最长 20 位', trigger: 'blur' }]
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await register({
      username: form.username,
      password: form.password,
      // 空字符串会让后端的 @Size(max=20) 通过，但存进去是个空昵称。
      // 传 undefined 让它走"不填时用用户名兜底"的逻辑
      nickname: form.nickname || undefined
    })
    ElMessage.success('注册成功，请登录')
    await router.replace('/login')
  } catch {
    // 提示已在拦截器里统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <div class="brand">
        <h1>注册账号</h1>
        <p>注册后即可创建和管理短链</p>
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
          <el-input v-model="form.username" placeholder="4-20 位字母、数字或下划线" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="6-32 位"
            show-password
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="再输入一次"
            show-password
          />
        </el-form-item>

        <el-form-item label="昵称（选填）" prop="nickname">
          <el-input v-model="form.nickname" placeholder="不填就用用户名" />
        </el-form-item>

        <el-button
          type="primary"
          class="submit"
          size="large"
          :loading="loading"
          @click="onSubmit"
        >
          注册
        </el-button>
      </el-form>

      <div class="footer">
        已经有账号了？
        <router-link to="/login">返回登录</router-link>
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
  font-size: 26px;
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
