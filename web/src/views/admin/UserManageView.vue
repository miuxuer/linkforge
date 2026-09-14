<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as adminApi from '@/api/admin'
import { useUserStore } from '@/store/user'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const records = ref([])
const total = ref(0)

const query = reactive({
  page: 1,
  pageSize: 10,
  keyword: '',
  status: null
})

async function load() {
  loading.value = true
  try {
    const data = await adminApi.getUserPage(query)
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
  query.status = null
  query.page = 1
  load()
}

onMounted(load)

async function onToggleStatus(row) {
  const nextStatus = row.status === 1 ? 0 : 1
  const action = nextStatus === 1 ? '启用' : '禁用'

  try {
    await ElMessageBox.confirm(
      nextStatus === 0
        ? `确定要禁用「${row.username}」吗？禁用后该用户将无法登录，已登录的也会立即失效。`
        : `确定要启用「${row.username}」吗？`,
      `${action}确认`,
      { type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await adminApi.updateUserStatus(row.id, nextStatus)
    ElMessage.success(`已${action}`)
    load()
  } catch {
    // 比如"不能修改自己的账号状态"，提示已经弹过了
  }
}

/** 是不是自己。后端会拒绝改自己的状态，前端提前灰掉按钮，免得用户白点一次。 */
function isSelf(row) {
  return row.id === userStore.profile?.id
}
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索用户名或昵称"
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
          <el-option label="禁用" :value="0" />
        </el-select>
        <el-button type="primary" @click="onSearch">搜索</el-button>
        <el-button @click="onReset">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="username" label="用户名" min-width="130" />
        <el-table-column label="昵称" min-width="120">
          <template #default="{ row }">{{ row.nickname || '-' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag :type="row.role === 1 ? 'warning' : 'info'" size="small">
              {{ row.role === 1 ? '管理员' : '普通用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <!-- 自己的那条不显示按钮：后端会返回 400，前端提前避免无效操作 -->
            <el-button
              v-if="!isSelf(row)"
              link
              :type="row.status === 1 ? 'danger' : 'primary'"
              size="small"
              @click="onToggleStatus(row)"
            >
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
            <span v-else class="self-tip">当前账号</span>
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

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.self-tip {
  font-size: 12px;
  color: #c0c4cc;
}
</style>
