<script setup lang="ts">
/**
 * 对话页（空壳）。
 * 待 `hify-chat` 的 SSE 接口落地后再实现会话列表与流式对话。
 */
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div>
    <PageHeader title="对话" description="与已配置的 Agent 进行多轮对话" />
    <div class="hify-card">
      <el-text type="info">对话功能即将上线</el-text>
    </div>
  </div>
</template>
