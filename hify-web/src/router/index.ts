import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表。
 *
 * 布局在 `App.vue` 里，所以这里的业务路由都是平铺的一级路由，不做嵌套。
 * 页面组件一律懒加载（`() => import(...)`），首屏只加载当前路由那一个。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    // 打开站点默认进对话页——它是这个平台的主场景
    redirect: '/chat',
  },
  {
    path: '/providers',
    name: 'provider-list',
    component: () => import('@/views/provider/ProviderList.vue'),
    meta: { title: '模型管理' },
  },
  {
    path: '/agents',
    name: 'agent-list',
    component: () => import('@/views/agent/AgentList.vue'),
    meta: { title: 'Agent 管理' },
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/chat/ChatIndex.vue'),
    meta: { title: '对话' },
  },
  {
    // 兜底 404，必须放在最后。没有它的话地址写错会渲染出空白内容区 + 一条控制台告警
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFound.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  // history 模式；生产环境需要 Nginx 的 try_files fallback 到 index.html（CLAUDE.md 7.2）
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

/**
 * 应用标题的默认值。
 * `.env` 被仓库 .gitignore 忽略，新克隆可能没有这个文件，所以这里必须兜底，
 * 值与 `.env.example` 保持一致。
 */
const DEFAULT_APP_TITLE = 'Hify 管理控制台'

// 按路由 meta.title 设置浏览器标签标题
router.afterEach((to) => {
  const appTitle = import.meta.env.VITE_APP_TITLE ?? DEFAULT_APP_TITLE
  const pageTitle = to.meta.title as string | undefined
  document.title = pageTitle ? `${pageTitle} - ${appTitle}` : appTitle
})

export default router
