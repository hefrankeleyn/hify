<script setup lang="ts">
/**
 * Agent 管理页（空壳）。
 * 待 `hify-agent` 的 REST 接口落地后再实现列表、创建、模型与工具绑定。
 */
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div>
    <PageHeader title="Agent 管理" description="创建 Agent，绑定模型、知识库与工具" />
    <div class="hify-card">
      <el-text type="info">Agent 列表即将上线</el-text>
    </div>
  </div>
</template>
