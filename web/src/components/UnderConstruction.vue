<script setup>
/**
 * 占位页。
 *
 * 路由先接好、页面后补的时候用它 —— 比让路由指向一个不存在的组件好：
 * 后者在开发时会直接报模块找不到，整个应用都跑不起来。
 */
defineProps({
  title: { type: String, required: true },
  hint: { type: String, default: '' }
})
</script>

<template>
  <el-card>
    <el-empty :description="title">
      <p v-if="hint" class="hint">{{ hint }}</p>
    </el-empty>
  </el-card>
</template>

<style scoped>
.hint {
  color: #909399;
  font-size: 13px;
}
</style>
