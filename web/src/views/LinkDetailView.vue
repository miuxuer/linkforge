<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import * as linkApi from '@/api/link'
import { uploadImage } from '@/api/upload'
import request from '@/api/request'
import { copyText } from '@/utils/clipboard'
import { formatDateTime, formatNumber } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const linkId = route.params.id

const loading = ref(false)
const detail = ref(null)

// ==================== 基本信息 ====================

async function loadDetail() {
  loading.value = true
  try {
    detail.value = await linkApi.getLink(linkId)

    // 详情加载完顺便把编辑表单填上
    Object.assign(editForm, {
      title: detail.value.title,
      remark: detail.value.remark,
      expireTime: detail.value.expireTime,
      qrLogo: detail.value.qrLogo
    })
    await loadQrCode()
  } catch {
    // 短链不存在或不属于当前用户时后端返回 404/403，拦截器已经弹过提示了。
    // 这里回列表页，免得用户停在一个永远空白的详情页上
    router.replace('/links')
  } finally {
    loading.value = false
  }
}

async function onCopy() {
  const ok = await copyText(detail.value.shortUrl)
  ElMessage[ok ? 'success' : 'warning'](
    ok ? '短链接已复制' : `复制失败，请手动复制：${detail.value.shortUrl}`
  )
}

onMounted(loadDetail)

// ==================== 二维码 ====================

const qrSize = ref(300)
const qrImageUrl = ref('')
const qrLoading = ref(false)

/**
 * 加载二维码图片。
 *
 * ★ 不能用 <img :src="/api/link/1/qrcode"> —— 那个接口在 /api/** 下需要登录，
 * 而 <img> 标签发请求时带不上自定义请求头（token 就在自定义头里）。
 * 直接写 src 只会拿到 401。
 *
 * 所以先用 axios 把图片当二进制取回来（拦截器会自动加上 token），
 * 再用 URL.createObjectURL 生成一个本地地址给 <img> 用。
 */
async function loadQrCode() {
  qrLoading.value = true
  try {
    const blob = await request.get(`/link/${linkId}/qrcode`, {
      params: { size: qrSize.value },
      responseType: 'blob'
    })
    // 换尺寸时会生成新的 object URL，旧的必须手动释放 ——
    // 不释放的话每换一次尺寸就有一块内存直到页面关闭才回收
    if (qrImageUrl.value) {
      URL.revokeObjectURL(qrImageUrl.value)
    }
    qrImageUrl.value = URL.createObjectURL(blob)
  } catch {
    qrImageUrl.value = ''
  } finally {
    qrLoading.value = false
  }
}

// 组件卸载时释放，避免离开页面后还留着一块内存
onBeforeUnmount(() => {
  if (qrImageUrl.value) {
    URL.revokeObjectURL(qrImageUrl.value)
  }
})

function onDownload() {
  if (!qrImageUrl.value) return
  const link = document.createElement('a')
  link.href = qrImageUrl.value
  link.download = `linkforge-${detail.value.shortCode}.png`
  link.click()
}

// ==================== 编辑 ====================

const editForm = reactive({
  title: '',
  remark: '',
  expireTime: null,
  qrLogo: ''
})

const saving = ref(false)
const uploading = ref(false)

async function onSave() {
  saving.value = true
  try {
    // ★ 整条覆盖语义：status 也要一起传，否则会被置成 null
    await linkApi.updateLink(linkId, {
      title: editForm.title,
      remark: editForm.remark,
      expireTime: editForm.expireTime,
      qrLogo: editForm.qrLogo,
      status: detail.value.status
    })
    ElMessage.success('保存成功')
    await loadDetail()
  } catch {
    // 忽略
  } finally {
    saving.value = false
  }
}

/** 上传 logo 并设为这条短链的二维码 logo。 */
async function onLogoSelected(file) {
  uploading.value = true
  try {
    const { url } = await uploadImage(file)
    editForm.qrLogo = url
    ElMessage.success('logo 上传成功，保存后生效')
  } catch {
    // 忽略
  } finally {
    uploading.value = false
  }
  // 返回 false 阻止 el-upload 自己发请求（我们已经手动传了）
  return false
}

function onRemoveLogo() {
  // 置空表示"用不带 logo 的纯二维码"
  editForm.qrLogo = ''
}
</script>

<template>
  <div v-loading="loading" class="page">
    <el-page-header content="短链详情" @back="router.push('/links')" />

    <el-row v-if="detail" :gutter="16" class="content">
      <!-- 左边：信息 -->
      <el-col :span="14">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>基本信息</span>
              <el-tag :type="detail.status === 1 ? 'success' : 'info'" size="small">
                {{ detail.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </div>
          </template>

          <el-descriptions :column="1" border>
            <el-descriptions-item label="短链接">
              <div class="inline">
                <span class="mono">{{ detail.shortUrl }}</span>
                <el-button link type="primary" size="small" @click="onCopy">复制</el-button>
              </div>
            </el-descriptions-item>

            <el-descriptions-item label="原始链接">
              <a :href="detail.originalUrl" target="_blank" rel="noopener">
                {{ detail.originalUrl }}
              </a>
            </el-descriptions-item>

            <el-descriptions-item label="标题">
              {{ detail.title || '-' }}
            </el-descriptions-item>

            <el-descriptions-item label="备注">
              {{ detail.remark || '-' }}
            </el-descriptions-item>

            <el-descriptions-item label="访问量">
              {{ formatNumber(detail.visitCount) }}
            </el-descriptions-item>

            <el-descriptions-item label="过期时间">
              {{ detail.expireTime ? formatDateTime(detail.expireTime) : '永不过期' }}
            </el-descriptions-item>

            <el-descriptions-item label="创建时间">
              {{ formatDateTime(detail.createTime) }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card class="edit-card">
          <template #header>编辑</template>

          <el-form :model="editForm" label-width="90px">
            <el-form-item label="标题">
              <el-input v-model="editForm.title" maxlength="100" show-word-limit />
            </el-form-item>

            <el-form-item label="备注">
              <el-input v-model="editForm.remark" type="textarea" :rows="2" maxlength="255" />
            </el-form-item>

            <el-form-item label="过期时间">
              <el-date-picker
                v-model="editForm.expireTime"
                type="datetime"
                placeholder="留空表示永不过期"
                value-format="YYYY-MM-DDTHH:mm:ss"
                clearable
                style="width: 100%"
              />
            </el-form-item>

            <el-form-item label="二维码 logo">
              <div class="logo-row">
                <img v-if="editForm.qrLogo" :src="editForm.qrLogo" class="logo-preview" />
                <el-upload
                  :show-file-list="false"
                  :before-upload="onLogoSelected"
                  accept="image/*"
                >
                  <el-button :loading="uploading" size="small">
                    {{ editForm.qrLogo ? '更换' : '上传' }}
                  </el-button>
                </el-upload>
                <el-button
                  v-if="editForm.qrLogo"
                  link
                  type="danger"
                  size="small"
                  @click="onRemoveLogo"
                >
                  移除
                </el-button>
              </div>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- 右边：二维码 -->
      <el-col :span="10">
        <el-card>
          <template #header>二维码</template>

          <div v-loading="qrLoading" class="qr-box">
            <img v-if="qrImageUrl" :src="qrImageUrl" alt="短链二维码" class="qr-image" />
            <el-empty v-else description="二维码加载失败" :image-size="80" />
          </div>

          <div class="qr-actions">
            <el-radio-group v-model="qrSize" size="small" @change="loadQrCode">
              <el-radio-button :value="200">小</el-radio-button>
              <el-radio-button :value="300">中</el-radio-button>
              <el-radio-button :value="500">大</el-radio-button>
            </el-radio-group>

            <el-button
              type="primary"
              size="small"
              :disabled="!qrImageUrl"
              @click="onDownload"
            >
              下载
            </el-button>
          </div>

          <p class="tip">手机扫码即可跳转到原始链接</p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.content {
  margin-top: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.inline {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mono {
  font-family: 'Consolas', 'Monaco', monospace;
  color: var(--el-color-primary);
  word-break: break-all;
}

.edit-card {
  margin-top: 16px;
}

.logo-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo-preview {
  width: 36px;
  height: 36px;
  object-fit: contain;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
}

.qr-box {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;
}

.qr-image {
  width: 100%;
  max-width: 260px;
}

.qr-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
}

.tip {
  margin: 12px 0 0;
  text-align: center;
  font-size: 12px;
  color: #909399;
}
</style>
