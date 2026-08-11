<script setup lang="ts">
/**
 * 404 页面。
 * 布局在 `App.vue` 里，所以它和业务页一样渲染在右侧内容区，左侧菜单仍在。
 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="404" sub-title="页面不存在">
    <template #extra>
      <el-button type="primary" @click="router.replace('/')">返回首页</el-button>
    </template>
  </el-result>
</template>
