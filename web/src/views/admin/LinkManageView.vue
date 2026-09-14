<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as adminApi from '@/api/admin'
import { formatDateTime, formatNumber } from '@/utils/format'

const loading = ref(false)
const records = ref([])
const total = ref(0)

const query = reactive({
  page: 1,
  pageSize: 10,
  keyword: '',
  userId: null,
  status: null
})

async function load() {
  loading.value = true
  try {
    const data = await adminApi.getAdminLinkPage(query)
    records.value = data.records
    total.value = data.total
  } catch {
    // 忽略
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.page = 1
  load()
}

function onReset() {
  query.keyword = ''
  query.userId = null
  query.status = null
  query.page = 1
  load()
}

onMounted(load)

async function onForceDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要强制删除「${row.title || row.shortCode}」吗？` +
        `该短链属于 ${row.username || '用户 ' + row.userId}，删除后立即无法访问。`,
      '强制删除',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }

  try {
    await adminApi.forceDeleteLink(row.id)
    ElMessage.success('已删除')
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
        <el-input
          v-model="query.keyword"
          placeholder="搜索标题或短码"
          clearable
          style="width: 200px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <el-input
          v-model="query.userId"
          placeholder="按用户 ID 筛选"
          clearable
          style="width: 160px"
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

      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column label="短链接" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.shortUrl }}</span>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="130">
          <template #default="{ row }">{{ row.title || '-' }}</template>
        </el-table-column>
        <el-table-column label="原始链接" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <a :href="row.originalUrl" target="_blank" rel="noopener">{{ row.originalUrl }}</a>
          </template>
        </el-table-column>
        <el-table-column label="归属用户" width="140">
          <template #default="{ row }">
            {{ row.username || `用户 ${row.userId}` }}
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
          <template #default="{ row }">{{ formatNumber(row.visitCount) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="onForceDelete(row)">
              强制删除
            </el-button>
          </template>
        </el-table-column>
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
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.mono {
  font-family: 'Consolas', 'Monaco', monospace;
  color: var(--el-color-primary);
  word-break: break-all;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
