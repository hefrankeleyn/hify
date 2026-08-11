import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 构建与开发服务器配置。
 *
 * 关键约定：
 * 1. `@` 别名指向 `src`，与 tsconfig.json 的 paths 保持一致（改一处必须同步改另一处）。
 * 2. 开发环境把 `/api` 全部反代到本地后端 http://localhost:8080，
 *    这样前端代码里永远只写相对路径 `/api/v1/xxx`，开发与生产（Nginx 反代）行为一致，
 *    也就不存在跨域，不需要后端开 CORS。
 * 3. 对话接口是 SSE 长连接（CLAUDE.md 七），代理层不能缓冲、不能提前超时，见下方 proxy 注释。
 */
export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  server: {
    // 监听所有网卡，方便同局域网设备访问开发机
    host: '0.0.0.0',
    port: 5173,
    // 端口被占用时直接失败，而不是静默换端口——否则代理地址与预期不符很难排查
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        // 重写 Host 头为目标地址，避免后端按 Host 做判断时拿到 localhost:5173
        changeOrigin: true,
        // 前端不使用 WebSocket（SSE 走普通 HTTP，见 CLAUDE.md 7.3），显式关掉协议升级
        ws: false,
        // ⚠️ SSE 相关：以下两个超时必须大于后端 SseEmitter 的 300s，
        // 语义对齐生产环境 Nginx 的 proxy_read_timeout 360s（CLAUDE.md 7.2），
        // 否则开发环境会出现「后端还在正常生成，代理先掐了连接」。
        timeout: 360_000,
        proxyTimeout: 360_000,
      },
    },
  },

  build: {
    outDir: 'dist',
    // 产物交给 Nginx 托管，生产环境不需要 sourcemap
    sourcemap: false,
    // Element Plus 全量引入后单 chunk 偏大，放宽告警阈值避免每次构建刷屏
    chunkSizeWarningLimit: 1500,
  },
})
