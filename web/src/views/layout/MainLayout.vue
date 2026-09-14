<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import { useUserStore } from '@/store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 侧边菜单。管理端那几项按角色过滤掉。 */
const menus = computed(() => {
  const items = [
    { path: '/dashboard', title: '数据看板', icon: 'DataLine' },
    { path: '/links', title: '我的短链', icon: 'Link' },
    { path: '/profile', title: '个人中心', icon: 'User' }
  ]

  if (userStore.isAdmin) {
    items.push(
      { path: '/admin/users', title: '用户管理', icon: 'UserFilled' },
      { path: '/admin/links', title: '短链管理', icon: 'Management' },
      { path: '/admin/logs', title: '操作日志', icon: 'Document' }
    )
  }

  return items
})

/** 当前高亮的菜单项。详情页要归到"我的短链"下面。 */
const activeMenu = computed(() => {
  if (route.path.startsWith('/links/')) return '/links'
  return route.path
})

/** 顶栏显示的称呼：优先昵称，没有就用用户名。 */
const displayName = computed(
  () => userStore.profile?.nickname || userStore.profile?.username || ''
)

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
  } catch {
    // 用户点了取消，什么都不做
    return
  }
  userStore.logout()
  ElMessage.success('已退出登录')
  router.replace('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">LinkForge</div>

      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          {{ item.title }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="title">{{ route.meta.title }}</div>

        <div class="user">
          <!-- 有头像显示头像，没有就显示昵称首字 —— 比一个默认灰头像好看 -->
          <el-avatar :size="30" :src="userStore.profile?.avatar">
            {{ displayName.slice(0, 1) }}
          </el-avatar>
          <span class="name">{{ displayName }}</span>
          <el-tag v-if="userStore.isAdmin" type="warning" size="small">管理员</el-tag>

          <el-button link type="danger" @click="onLogout">退出</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <!-- 子路由渲染在这里 -->
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background-color: #ffffff;
  border-right: 1px solid #e4e7ed;
}

.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 1px;
  border-bottom: 1px solid #e4e7ed;
}

.menu {
  border-right: none;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #ffffff;
  border-bottom: 1px solid #e4e7ed;
}

.title {
  font-size: 16px;
  font-weight: 500;
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
}

.name {
  font-size: 14px;
  color: #606266;
}

.main {
  background-color: #f5f7fa;
}
</style>
