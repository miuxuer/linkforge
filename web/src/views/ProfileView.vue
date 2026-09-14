<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import * as userApi from '@/api/user'
import { uploadImage } from '@/api/upload'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()

const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)

const profile = ref(null)

const form = reactive({
  nickname: '',
  avatar: ''
})

async function load() {
  loading.value = true
  try {
    const data = await userApi.getProfile()
    profile.value = data
    form.nickname = data.nickname || ''
    form.avatar = data.avatar || ''
  } catch {
    // 忽略
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 选完文件后立刻上传到 OSS，拿到 URL 之后再保存。 */
async function onAvatarSelected(file) {
  uploading.value = true
  try {
    const { url } = await uploadImage(file)
    form.avatar = url
    ElMessage.success('头像上传成功，点保存生效')
  } catch {
    // 忽略
  } finally {
    uploading.value = false
  }
  // 返回 false 阻止 el-upload 自己再发一次请求
  return false
}

function onRemoveAvatar() {
  // 置空表示清空头像。后端的 PUT 是整条覆盖语义，null 就是"设成 null"，
  // 所以这个操作能生效 —— 换成 updateById 那种"null 表示不修改"的策略就删不掉了
  form.avatar = ''
}

async function onSave() {
  if (!form.nickname.trim()) {
    ElMessage.warning('昵称不能为空')
    return
  }

  saving.value = true
  try {
    const updated = await userApi.updateProfile({
      nickname: form.nickname.trim(),
      // 空串要转成 null，否则会往数据库里存一个长度为 0 的头像地址
      avatar: form.avatar || null
    })

    // 同步到 store —— 不同步的话顶栏还显示旧昵称，用户会以为没保存成功
    userStore.setProfile(updated)
    ElMessage.success('保存成功')
  } catch {
    // 忽略
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="page">
    <el-row :gutter="16">
      <el-col :span="14">
        <el-card>
          <template #header>修改资料</template>

          <el-form :model="form" label-width="80px">
            <el-form-item label="头像">
              <div class="avatar-row">
                <el-avatar :size="64" :src="form.avatar">
                  {{ (form.nickname || '?').slice(0, 1) }}
                </el-avatar>

                <el-upload
                  :show-file-list="false"
                  :before-upload="onAvatarSelected"
                  accept="image/*"
                >
                  <el-button :loading="uploading" size="small">
                    {{ form.avatar ? '更换头像' : '上传头像' }}
                  </el-button>
                </el-upload>

                <el-button
                  v-if="form.avatar"
                  link
                  type="danger"
                  size="small"
                  @click="onRemoveAvatar"
                >
                  移除
                </el-button>
              </div>
              <div class="hint">支持 jpg / png / gif / webp，单张不超过 10MB</div>
            </el-form-item>

            <el-form-item label="昵称">
              <el-input v-model="form.nickname" maxlength="20" show-word-limit />
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card>
          <template #header>账号信息</template>

          <el-descriptions :column="1" border>
            <el-descriptions-item label="用户名">
              {{ profile?.username || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="用户 ID">
              {{ profile?.id ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="角色">
              <el-tag :type="profile?.role === 1 ? 'warning' : 'info'" size="small">
                {{ profile?.role === 1 ? '管理员' : '普通用户' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>

          <p class="note">
            用户名是登录凭据，注册后不能修改。昵称和头像可以随时改。
          </p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.avatar-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.hint {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.note {
  margin: 16px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}
</style>
