/// <reference types="vite/client" />

/**
 * `.vue` 单文件组件的模块声明。
 * 没有它，TypeScript 无法识别 `import Xxx from './Xxx.vue'`。
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

/**
 * 本项目使用的环境变量声明，与根目录 `.env` 文件一一对应。
 */
interface ImportMetaEnv {
  /** 后端 API 前缀，开发环境经 Vite 代理转发到 localhost:8080 */
  readonly VITE_API_BASE_URL: string
  /** 页面标题，用于浏览器标签与顶栏 */
  readonly VITE_APP_TITLE: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
