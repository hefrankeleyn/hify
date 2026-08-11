<script setup lang="ts">
/**
 * 应用根组件，同时承担全局布局：左侧侧边栏（品牌 + 导航 + 折叠）+ 顶栏（面包屑 + 用户信息）+ 内容区。
 *
 * 布局直接放在这里而不是单独的 `layouts/` 组件，是因为目前只有一种布局形态。
 * 等出现第二种（比如不带侧边栏的登录页）再抽出去，现在抽属于过度设计。
 */
import { computed, ref, watch, type Component } from 'vue'
import { useRoute } from 'vue-router'
import { ChatDotRound, Expand, Fold, Setting, User } from '@element-plus/icons-vue'
import { useMediaQuery } from '@/composables/useMediaQuery'

/** 版本号需要和 package.json 的 version 手动保持一致；一期没必要为了这一行展示文案引入构建期变量注入 */
const APP_VERSION = 'v0.0.1'

/** 侧边栏导航项：path 同时是路由地址和 el-menu 的 index */
interface MenuItem {
  path: string
  label: string
  icon: Component
}

const menuItems: MenuItem[] = [
  { path: '/providers', label: '模型管理', icon: Setting },
  { path: '/agents', label: 'Agent 管理', icon: User },
  { path: '/chat', label: '对话', icon: ChatDotRound },
]

const route = useRoute()

/** 侧边栏折叠状态。纯 UI 展示状态，不需要跨会话持久化，组件内 ref 就够 */
const collapsed = ref(false)

function toggleCollapsed(): void {
  collapsed.value = !collapsed.value
}

/**
 * 视口宽度 < 1200px 时自动折叠成图标模式（和 ProviderList 表格隐藏次要列共用同一个断点）。
 * 只在跨越断点的那一刻强制设置一次，折叠之后用户仍然可以用底部按钮手动展开——
 * 这是「窄屏下的默认收起」，不是锁死不让展开。
 */
const isNarrowScreen = useMediaQuery('(max-width: 1199px)')
watch(
  isNarrowScreen,
  (narrow) => {
    collapsed.value = narrow
  },
  { immediate: true },
)

const asideWidth = computed(() =>
  collapsed.value ? 'var(--hify-sidebar-width-collapsed)' : 'var(--hify-sidebar-width)',
)

/** 面包屑当前页标题，取自路由 meta.title（见 router/index.ts） */
const currentTitle = computed(() => (route.meta.title as string | undefined) ?? '')
</script>

<template>
  <el-container class="layout">
    <el-aside :width="asideWidth" class="layout__aside">
      <div class="layout__logo">
        <span class="layout__logo-mark">H</span>
        <div v-show="!collapsed" class="layout__logo-text">
          <span class="layout__logo-title">Hify</span>
          <span class="layout__logo-subtitle">AI Agent Platform</span>
        </div>
      </div>

      <!-- router 模式下 el-menu 的 index 直接作为跳转路径；default-active 跟随当前路由高亮 -->
      <el-menu :default-active="route.path" :collapse="collapsed" router class="layout__menu">
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.label }}</template>
        </el-menu-item>
      </el-menu>

      <div class="layout__sidebar-footer">
        <button type="button" class="layout__collapse-btn" @click="toggleCollapsed">
          <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
          <span v-show="!collapsed">收起菜单</span>
        </button>
        <div v-show="!collapsed" class="layout__version">{{ APP_VERSION }}</div>
      </div>
    </el-aside>

    <el-container class="layout__body">
      <el-header height="56px" class="layout__header">
        <!-- 路由是平铺的一级结构（见 router/index.ts），面包屑只有「Hify」根节点 + 当前页两级 -->
        <el-breadcrumb separator="/" class="layout__breadcrumb">
          <el-breadcrumb-item>Hify</el-breadcrumb-item>
          <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
        </el-breadcrumb>

        <!-- 用户信息目前是占位：项目暂无用户 / 权限模块（CLAUDE.md 明确不做多租户），头像和用户名先静态展示 -->
        <div class="layout__user">
          <el-avatar :size="28" class="layout__user-avatar">
            <el-icon><User /></el-icon>
          </el-avatar>
          <span class="layout__user-name">管理员</span>
        </div>
      </el-header>

      <el-main class="layout__main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

/* ---------- 侧边栏 ---------- */

/* 侧边栏固定深色底，与浅色内容区形成品牌反差；用的是 tokens.css 里独立的 --hify-sidebar-* 一档，
   不是全局深色模式，只这一块区域是深色。 */
.layout__aside {
  display: flex;
  flex-direction: column;
  background-color: var(--hify-sidebar-bg);
  border-right: 1px solid var(--hify-sidebar-border);
  overflow: hidden;
  transition: width var(--hify-duration-slower) var(--hify-ease-emphasized);
}

.layout__logo {
  display: flex;
  align-items: center;
  gap: var(--hify-space-3);
  height: 64px;
  padding: 0 var(--hify-space-4);
  border-bottom: 1px solid var(--hify-sidebar-border);
  flex-shrink: 0;
}

.layout__logo-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: var(--hify-radius-sm);
  background-image: linear-gradient(135deg, var(--hify-primary-400) 0%, var(--hify-primary-600) 100%);
  /* 折叠态下 Logo 只剩这个方块，用品牌光晕阴影补一点存在感,呼应展开态渐变字的科技感 */
  box-shadow: var(--hify-shadow-glow-primary);
  color: var(--hify-text-inverse);
  font-weight: var(--hify-font-weight-bold);
  font-size: var(--hify-font-size-md);
}

.layout__logo-text {
  display: flex;
  flex-direction: column;
  line-height: var(--hify-line-height-tight);
  overflow: hidden;
  white-space: nowrap;
}

.layout__logo-title {
  font-size: var(--hify-font-size-lg);
  font-weight: var(--hify-font-weight-bold);
  /* 主色渐变文字：唯一的品牌重点，其余侧边栏元素刻意保持克制 */
  background-image: linear-gradient(135deg, var(--hify-primary-300) 0%, var(--hify-primary-500) 100%);
  background-clip: text;
  -webkit-background-clip: text;
  color: transparent;
}

.layout__logo-subtitle {
  font-size: var(--hify-font-size-xs);
  color: var(--hify-sidebar-text-muted);
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.layout__menu {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  border-right: none;
  background-color: transparent;
  padding: var(--hify-space-2) 0;
}

/* el-menu 内部结构不在本组件的 scoped 样式作用域内，需要 :deep() 才能覆盖到菜单项 */
.layout__menu :deep(.el-menu-item) {
  position: relative;
  height: 44px;
  color: var(--hify-sidebar-text);
  background-color: transparent;
  transition: var(--hify-transition-colors);
}

.layout__menu :deep(.el-menu-item .el-icon) {
  color: inherit;
}

.layout__menu :deep(.el-menu-item:hover) {
  color: var(--hify-sidebar-text-hover);
  background-color: var(--hify-sidebar-bg-hover);
}

.layout__menu :deep(.el-menu-item.is-active) {
  color: var(--hify-sidebar-text-active);
  background-color: var(--hify-sidebar-bg-active);
  font-weight: var(--hify-font-weight-medium);
}

/* 选中态左侧 3px 竖线，是深色侧边栏里除渐变 Logo 外唯一的强调色用法 */
.layout__menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 3px;
  border-radius: 0 var(--hify-radius-xs) var(--hify-radius-xs) 0;
  background-color: var(--hify-sidebar-accent-bar);
}

.layout__sidebar-footer {
  flex-shrink: 0;
  border-top: 1px solid var(--hify-sidebar-border);
  padding: var(--hify-space-3) var(--hify-space-4);
}

.layout__collapse-btn {
  display: flex;
  align-items: center;
  gap: var(--hify-space-2);
  width: 100%;
  padding: var(--hify-space-2);
  border: none;
  border-radius: var(--hify-radius-sm);
  background: transparent;
  color: var(--hify-sidebar-text);
  font-size: var(--hify-font-size-sm);
  font-family: inherit;
  cursor: pointer;
  transition: var(--hify-transition-colors);
}

.layout__collapse-btn:hover {
  color: var(--hify-sidebar-text-hover);
  background-color: var(--hify-sidebar-bg-hover);
}

.layout__version {
  margin-top: var(--hify-space-2);
  padding: 0 var(--hify-space-2);
  font-family: var(--hify-font-mono);
  font-size: var(--hify-font-size-xs);
  color: var(--hify-sidebar-text-muted);
}

/* ---------- 顶栏 ---------- */

.layout__body {
  min-width: 0;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--hify-space-6);
  background-color: var(--hify-bg-surface);
  border-bottom: 1px solid var(--hify-border-subtle);
}

.layout__user {
  display: flex;
  align-items: center;
  gap: var(--hify-space-2);
}

.layout__user-avatar {
  background-color: var(--hify-primary-100);
  color: var(--hify-color-primary);
}

.layout__user-name {
  font-size: var(--hify-font-size-sm);
  color: var(--hify-text-body);
}

/* ---------- 内容区 ---------- */

.layout__main {
  background-color: var(--hify-bg-canvas);
  padding: var(--hify-space-6);
  overflow-y: auto;
}
</style>
