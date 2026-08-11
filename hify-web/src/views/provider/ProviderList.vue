<script setup lang="ts">
/**
 * 模型提供商管理页（空壳）。
 * 待 `hify-provider` 的 REST 接口落地后再实现列表、创建、连通性测试。
 *
 * 目前只在进入页面时探一次后端健康检查，用来确认「浏览器 → Vite 代理 → Spring Boot」这条链路是否打通。
 */
import { onMounted, ref } from 'vue'
import { getHealth } from '@/api/health'

/** 后端连通状态：checking 检测中 / online 已连接 / offline 未连接 */
const status = ref<'checking' | 'online' | 'offline'>('checking')

/** 后端返回的运行文案，仅 status 为 online 时有值 */
const healthText = ref('')

/**
 * 探测后端是否存活，把结果落到 status 上。
 *
 * 失败的用户提示由 `utils/request.ts` 的拦截器统一弹 `ElMessage`，
 * 这里只负责把状态渲染到页面上，不重复提示。
 */
async function loadHealth(): Promise<void> {
  try {
    healthText.value = await getHealth()
    status.value = 'online'
  } catch (error) {
    // 拦截器已经弹过提示，这里只记一条日志便于排查
    status.value = 'offline'
    console.warn('[ProviderList] 后端健康检查失败', error)
  }
}

// 进入页面即探测一次；不做轮询，需要重测就刷新页面
onMounted(loadHealth)
</script>

<template>
  <div>
    <div>模型提供商管理</div>

    <div class="health">
      <el-text v-if="status === 'checking'" type="info">正在检测后端连接…</el-text>
      <el-text v-else-if="status === 'online'" type="success">后端已连接：{{ healthText }}</el-text>
      <el-text v-else type="danger">后端未连接</el-text>
    </div>
  </div>
</template>

<style scoped>
.health {
  margin-top: 12px;
}
</style>
