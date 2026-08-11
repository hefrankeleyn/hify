<script setup lang="ts">
/**
 * 应用根组件，同时承担全局布局：左侧菜单 + 右侧内容区。
 *
 * 布局直接放在这里而不是单独的 `layouts/` 组件，是因为目前只有一种布局形态。
 * 等出现第二种（比如不带侧边栏的登录页）再抽出去，现在抽属于过度设计。
 */
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="layout__aside">
      <div class="layout__logo">Hify</div>
      <!-- router 模式下 el-menu 的 index 直接作为跳转路径；default-active 跟随当前路由高亮 -->
      <el-menu :default-active="route.path" router class="layout__menu">
        <el-menu-item index="/providers">模型管理</el-menu-item>
        <el-menu-item index="/agents">Agent 管理</el-menu-item>
        <el-menu-item index="/chat">对话</el-menu-item>
      </el-menu>
    </el-aside>

    <el-main class="layout__main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.layout__aside {
  background-color: var(--el-bg-color-page);
  border-right: 1px solid var(--el-border-color-light);
}

.layout__logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  font-size: 18px;
  font-weight: 600;
  color: var(--el-color-primary);
  border-bottom: 1px solid var(--el-border-color-light);
}

.layout__menu {
  border-right: none;
}
</style>
