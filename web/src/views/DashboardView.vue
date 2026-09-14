<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'

import * as statApi from '@/api/stat'
import { formatNumber } from '@/utils/format'

// ★ ECharts 用按需引入，不是 `import * as echarts from 'echarts'`。
// 全量引入会把所有图表类型（地图、树图、桑基图……）都打进来，
// 包体积能差好几百 KB。这里只用到折线图和柱状图。
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const loading = ref(false)

const overview = ref({ totalLinks: 0, totalVisits: 0, todayVisits: 0 })
const topLinks = ref([])
const trendDays = ref(7)

// 图表实例用 shallowRef：ECharts 实例内部结构很复杂，
// 让它变成响应式对象既没必要（我们不会去改它的内部字段）又很费性能
const trendChartRef = ref()
const trendChart = shallowRef(null)

const topChartRef = ref()
const topChart = shallowRef(null)

// ==================== 数据 ====================

async function load() {
  loading.value = true
  try {
    // 三个接口互不依赖，并发请求。串行的话用户要多等两个来回
    const [overviewData, trendData, topData] = await Promise.all([
      statApi.getOverview(),
      statApi.getTrend(trendDays.value),
      statApi.getTop(10)
    ])

    overview.value = overviewData
    topLinks.value = topData

    await nextTick()
    renderTrend(trendData)
    renderTop(topData)
  } catch {
    // 提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

// ==================== 趋势折线图 ====================

function renderTrend(trendData) {
  if (!trendChartRef.value) return

  // 第一次渲染时创建实例；后续只更新数据，避免每次重画都重建实例
  if (!trendChart.value) {
    trendChart.value = echarts.init(trendChartRef.value)
  }

  trendChart.value.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 24, top: 24, bottom: 36 },
    xAxis: {
      type: 'category',
      // 后端返回的是 2026-09-14，图上只显示 09-14 更清爽
      data: trendData.map((item) => item.visitDate.slice(5)),
      boundaryGap: false
    },
    yAxis: {
      type: 'value',
      // 访问量必须是整数，不加这个的话会出现 "0.5 次访问" 这种刻度
      minInterval: 1
    },
    series: [
      {
        name: '访问量',
        type: 'line',
        smooth: true,
        // 没有访问的日期也要画出来是 0，不能断线 ——
        // 后端已经补过零了，这里直接用
        data: trendData.map((item) => item.visitCount),
        areaStyle: { opacity: 0.15 },
        itemStyle: { color: '#409eff' }
      }
    ]
  })
}

async function onDaysChange() {
  // 只重新拉趋势数据，总览和 Top N 跟天数无关
  const trendData = await statApi.getTrend(trendDays.value)
  renderTrend(trendData)
}

// ==================== Top N 柱状图 ====================

function renderTop(topData) {
  if (!topChartRef.value) return

  if (!topChart.value) {
    topChart.value = echarts.init(topChartRef.value)
  }

  // 横向柱状图要倒着放：y 轴是从下往上画的，不倒序的话访问量最高的会显示在最下面
  const reversed = [...topData].reverse()

  topChart.value.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 100, right: 40, top: 16, bottom: 24 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: reversed.map((item) => item.title || item.shortCode)
    },
    series: [
      {
        name: '访问量',
        type: 'bar',
        data: reversed.map((item) => item.visitCount),
        itemStyle: { color: '#67c23a' },
        // 柱子末端直接显示数值，不用非得悬停才看得到
        label: { show: true, position: 'right' }
      }
    ]
  })
}

// ==================== 生命周期 ====================

/**
 * 窗口大小变化时让图表跟着重画。
 *
 * ECharts 不会自动响应容器尺寸变化 —— 不监听的话，用户把窗口拉宽，
 * 图表还是原来的宽度，右边留一大片空白。
 */
function handleResize() {
  trendChart.value?.resize()
  topChart.value?.resize()
}

onMounted(() => {
  load()
  window.addEventListener('resize', handleResize)
})

/**
 * 组件卸载时必须销毁 ECharts 实例。
 *
 * 不销毁的话，实例还挂在 DOM 上、还占着 canvas 和事件监听 ——
 * 反复进出这个页面就是持续的内存泄漏。
 */
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  trendChart.value?.dispose()
  topChart.value?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="page">
    <!-- 总览 -->
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card class="stat-card">
          <div class="stat-label">短链总数</div>
          <div class="stat-value">{{ formatNumber(overview.totalLinks) }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card class="stat-card">
          <div class="stat-label">累计访问量</div>
          <div class="stat-value">{{ formatNumber(overview.totalVisits) }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card class="stat-card">
          <div class="stat-label">今日访问量</div>
          <div class="stat-value today">{{ formatNumber(overview.todayVisits) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 趋势 -->
    <el-card class="chart-card">
      <template #header>
        <div class="card-header">
          <span>访问趋势</span>
          <el-radio-group v-model="trendDays" size="small" @change="onDaysChange">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <div ref="trendChartRef" class="chart"></div>
    </el-card>

    <!-- Top N -->
    <el-card class="chart-card">
      <template #header>访问量 Top 10</template>

      <div v-show="topLinks.length" ref="topChartRef" class="chart"></div>
      <el-empty v-if="!topLinks.length" description="还没有访问数据" :image-size="80" />
    </el-card>
  </div>
</template>

<style scoped>
.stat-card {
  text-align: center;
}

.stat-label {
  font-size: 13px;
  color: #909399;
}

.stat-value {
  margin-top: 8px;
  font-size: 30px;
  font-weight: 600;
  color: #303133;
}

.stat-value.today {
  color: var(--el-color-primary);
}

.chart-card {
  margin-top: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chart {
  height: 300px;
}
</style>
