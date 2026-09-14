<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as linkApi from '@/api/link'
import { formatDateTime, formatNumber } from '@/utils/format'
import { copyText } from '@/utils/clipboard'

const router = useRouter()

const loading = ref(false)
const records = ref([])
const total = ref(0)

const query = reactive({
  page: 1,
  pageSize: 10,
  keyword: '',
  status: null
})

// ==================== 列表 ====================

async function load() {
  loading.value = true
  try {
    const data = await linkApi.getLinkPage(query)
    records.value = data.records
    total.value = data.total
  } catch {
    // 提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

function onSearch() {
  // 搜索必须回到第 1 页 —— 否则在第 5 页搜一个只有 2 条结果的词，
  // 会因为 page 还是 5 而显示空列表，用户以为"搜不到"
  query.page = 1
  load()
}

function onReset() {
  query.keyword = ''
  query.status = null
  query.page = 1
  load()
}

onMounted(load)

// ==================== 创建 ====================

const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref()

const createForm = reactive({
  originalUrl: '',
  title: '',
  remark: '',
  expireTime: null
})

const createRules = {
  originalUrl: [
    { required: true, message: '请输入原始链接', trigger: 'blur' },
    {
      pattern: /^https?:\/\/.+/,
      message: '只支持 http:// 或 https:// 开头的链接',
      trigger: 'blur'
    }
  ]
}

function openCreate() {
  // 每次打开都清空，避免上次填了一半的内容还留着
  Object.assign(createForm, { originalUrl: '', title: '', remark: '', expireTime: null })
  createVisible.value = true
}

async function submitCreate() {
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return

  creating.value = true
  try {
    const created = await linkApi.createLink(createForm)
    ElMessage.success('创建成功')
    createVisible.value = false
    // 直接跳到详情页，用户马上就能看到二维码
    router.push(`/links/${created.id}`)
  } catch {
    // 忽略
  } finally {
    creating.value = false
  }
}

// ==================== 操作 ====================

async function onCopy(row) {
  const ok = await copyText(row.shortUrl)
  if (ok) {
    ElMessage.success('短链接已复制')
  } else {
    // 剪贴板 API 需要 https 或 localhost。其它情况下给用户手动复制的机会
    ElMessage.warning(`复制失败，请手动复制：${row.shortUrl}`)
  }
}

/**
 * 启用 / 停用。
 *
 * ★ 这里必须把 title / remark / expireTime / qrLogo 一起传回去。
 * 后端的 PUT /api/link/{id} 是"整条覆盖"语义 —— 没传的字段会被写成 null。
 * 只传 status 的话，用户的标题、备注、过期时间会被一起清掉，
 * 而且是"操作成功"之后才发现数据没了。
 */
async function onToggleStatus(row) {
  const nextStatus = row.status === 1 ? 0 : 1
  const action = nextStatus === 1 ? '启用' : '停用'

  try {
    await ElMessageBox.confirm(`确定要${action}「${row.title || row.shortCode}」吗？`, '提示', {
      type: 'warning'
    })
  } catch {
    return
  }

  try {
    await linkApi.updateLink(row.id, {
      title: row.title,
      remark: row.remark,
      expireTime: row.expireTime,
      qrLogo: row.qrLogo,
      status: nextStatus
    })
    ElMessage.success(`已${action}`)
    load()
  } catch {
    // 忽略
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${row.title || row.shortCode}」吗？删除后该短链将无法访问。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }

  try {
    await linkApi.deleteLink(row.id)
    ElMessage.success('已删除')
    // 删掉当前页最后一条时要回退一页，否则会停在空页上
    if (records.value.length === 1 && query.page > 1) {
      query.page -= 1
    }
    load()
  } catch {
    // 忽略
  }
}
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <div class="filters">
          <el-input
            v-model="query.keyword"
            placeholder="搜索标题或短码"
            clearable
            style="width: 220px"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-select
            v-model="query.status"
            placeholder="全部状态"
            clearable
            style="width: 130px"
            @change="onSearch"
          >
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
          <el-button type="primary" @click="onSearch">搜索</el-button>
          <el-button @click="onReset">重置</el-button>
        </div>

        <el-button type="primary" @click="openCreate">创建短链</el-button>
      </div>

      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column label="短链接" min-width="230">
          <template #default="{ row }">
            <div class="short-url">
              <span class="url">{{ row.shortUrl }}</span>
              <el-button link type="primary" size="small" @click="onCopy(row)">
                复制
              </el-button>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="title" label="标题" min-width="140">
          <template #default="{ row }">
            {{ row.title || '-' }}
          </template>
        </el-table-column>

        <el-table-column label="原始链接" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <a :href="row.originalUrl" target="_blank" rel="noopener">{{ row.originalUrl }}</a>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="访问量" width="100">
          <template #default="{ row }">
            {{ formatNumber(row.visitCount) }}
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            {{ formatDateTime(row.createTime) }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="router.push(`/links/${row.id}`)">
              详情
            </el-button>
            <el-button link type="primary" size="small" @click="onToggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="还没有短链，点右上角创建一个" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pagination"
        @current-change="load"
        @size-change="onSearch"
      />
    </el-card>

    <!-- 创建短链 -->
    <el-dialog v-model="createVisible" title="创建短链" width="520px">
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="90px"
      >
        <el-form-item label="原始链接" prop="originalUrl">
          <el-input
            v-model="createForm.originalUrl"
            placeholder="https://example.com/very/long/url"
          />
        </el-form-item>

        <el-form-item label="标题">
          <el-input v-model="createForm.title" placeholder="选填，方便自己认出来" />
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>

        <el-form-item label="过期时间">
          <el-date-picker
            v-model="createForm.expireTime"
            type="datetime"
            placeholder="不填表示永不过期"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.filters {
  display: flex;
  align-items: center;
  gap: 10px;
}

.short-url {
  display: flex;
  align-items: center;
  gap: 6px;
}

.url {
  font-family: 'Consolas', 'Monaco', monospace;
  color: var(--el-color-primary);
  word-break: break-all;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
