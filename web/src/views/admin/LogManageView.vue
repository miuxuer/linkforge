<script setup>
import { onMounted, reactive, ref } from 'vue'

import * as adminApi from '@/api/admin'
import { formatDateTime } from '@/utils/format'

const loading = ref(false)
const records = ref([])
const total = ref(0)

/** 详情弹窗。参数和返回值可能很长，表格里放不下。 */
const detailVisible = ref(false)
const current = ref(null)

const query = reactive({
  page: 1,
  pageSize: 20,
  keyword: '',
  operateUser: null,
  status: null
})

async function load() {
  loading.value = true
  try {
    const data = await adminApi.getLogPage(query)
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
  query.operateUser = null
  query.status = null
  query.page = 1
  load()
}

function onShowDetail(row) {
  current.value = row
  detailVisible.value = true
}

/**
 * 格式化 JSON 字符串，让它可读。
 *
 * 后端存的是 JSON 字符串，直接显示是一整行。解析失败时原样返回 ——
 * 有些参数（比如文件流）序列化失败后存的是 toString 的结果，本来就不是 JSON。
 */
function prettyJson(text) {
  if (!text) return '-'
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索类名或方法名"
          clearable
          style="width: 220px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <el-input
          v-model="query.operateUser"
          placeholder="按操作人 ID 筛选"
          clearable
          style="width: 170px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <el-select
          v-model="query.status"
          placeholder="全部结果"
          clearable
          style="width: 130px"
          @change="onSearch"
        >
          <el-option label="成功" :value="0" />
          <el-option label="失败" :value="1" />
        </el-select>
        <el-button type="primary" @click="onSearch">搜索</el-button>
        <el-button @click="onReset">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="id" label="ID" width="180" show-overflow-tooltip />
        <el-table-column label="操作人" width="100">
          <template #default="{ row }">{{ row.operateUser ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="方法" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.className?.split('.').pop() }}#{{ row.methodName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'danger'" size="small">
              {{ row.status === 0 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ row.costTime }} ms</template>
        </el-table-column>
        <el-table-column label="异常信息" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ error: row.errorMsg }">{{ row.errorMsg || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.operateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onShowDetail(row)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pagination"
        @current-change="load"
        @size-change="onSearch"
      />
    </el-card>

    <el-dialog v-model="detailVisible" title="操作详情" width="720px">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="操作人">
            {{ current.operateUser ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="耗时">{{ current.costTime }} ms</el-descriptions-item>
          <el-descriptions-item label="类名" :span="2">
            {{ current.className }}
          </el-descriptions-item>
          <el-descriptions-item label="方法名" :span="2">
            {{ current.methodName }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="section">
          <div class="section-title">入参</div>
          <!--
            注意：密码之类的敏感字段在写入日志时就已经被切面脱敏成 ****** 了，
            所以这里可以放心展示
          -->
          <pre class="code">{{ prettyJson(current.methodParams) }}</pre>
        </div>

        <div class="section">
          <div class="section-title">返回值</div>
          <pre class="code">{{ prettyJson(current.returnValue) }}</pre>
        </div>

        <div v-if="current.errorMsg" class="section">
          <div class="section-title error">异常</div>
          <pre class="code error">{{ current.errorMsg }}</pre>
        </div>
      </template>
    </el-dialog>
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
  font-size: 12px;
}

.error {
  color: var(--el-color-danger);
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.section {
  margin-top: 16px;
}

.section-title {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
  color: #606266;
}

.code {
  max-height: 220px;
  overflow: auto;
  margin: 0;
  padding: 10px;
  background-color: #f5f7fa;
  border-radius: 4px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
