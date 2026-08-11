# hify-web

Hify 管理控制台前端。**不进 Maven reactor**，与后端同仓不同构建（`docs/02-架构设计/02-决策/01_Hify代码组织（基准）.md` 9.5）。

## 技术栈

| 项 | 选型 |
|---|---|
| 框架 | Vue 3（`<script setup>` + Composition API） |
| 语言 | TypeScript（`strict` 全开） |
| 构建 | Vite |
| UI | Element Plus（当前**全量引入**，中文 locale） |
| 路由 | vue-router（history 模式） |
| 状态 | Pinia |
| HTTP | axios（**仅非流式接口**；SSE 必须走 fetch，见下） |
| 包管理 | pnpm |

## 命令

```bash
pnpm install          # 安装依赖
pnpm dev              # 开发服务器 http://localhost:5173
pnpm build            # 类型检查（vue-tsc）+ 生产构建 → dist/
pnpm type-check       # 只做类型检查
pnpm preview          # 本地预览 dist/
```

## 目录结构

```
hify-web/
├── .env.example          # 环境变量模板（.env 被仓库 .gitignore 忽略，不入库）
├── index.html
├── vite.config.ts        # 别名、开发代理、构建产物配置
├── tsconfig.json         # 只做聚合，引用下面两个（无 compilerOptions）
├── tsconfig.app.json     # 浏览器侧程序（src/）：有 DOM，无 Node 全局
├── tsconfig.node.json    # Node 侧程序（vite.config.ts）：有 Node 全局，无 DOM
├── env.d.ts              # *.vue 模块声明 + import.meta.env 类型
└── src/
    ├── api/              # 按后端模块分文件的接口调用：health.ts（后续 provider.ts / agent.ts …）
    ├── assets/           # 静态资源
    ├── components/       # 全局通用组件
    ├── composables/      # 组合式函数：useSse.ts
    ├── layouts/          # 布局壳。**当前为空**——只有一种布局，直接写在 App.vue 里了，
    │                     #   等出现第二种（如无侧边栏的登录页）再抽出来
    ├── router/           # 路由表
    ├── stores/           # Pinia：目前只有 index.ts（实例），还没有业务 store
    ├── styles/           # 全局样式
    ├── types/            # TS 类型：result.ts（Result / PageResult / PageQuery）
    ├── utils/            # request.ts（axios 封装）、sse.ts（SSE 解析）
    ├── views/            # 页面，按后端模块分子目录
    ├── App.vue
    └── main.ts
```

## 与后端的三条约定

### 1. 接口一律走相对路径 `/api/v1/**`

开发环境由 `vite.config.ts` 的 `server.proxy` 转发到 `http://localhost:8080`，
生产环境由 Nginx 反代到 `hify-app`。两边行为一致，**不存在跨域，后端不需要开 CORS**。

代理的 `timeout` / `proxyTimeout` 设为 **360s**，语义对齐生产 Nginx 的 `proxy_read_timeout 360s`，
必须大于后端 `SseEmitter` 的 300s（CLAUDE.md 6.1 的不等式），否则开发环境会出现
「后端还在正常生成，代理先掐了连接」。

### 2. 响应体统一是 `Result` 壳

`src/types/result.ts` 与后端 `com.hify.common.result` 一一对应。
`src/utils/request.ts` 的响应拦截器做三件事：业务码 `!== 200` 时统一弹 `ElMessage` 并 reject 一个
`ApiError`；成功时**自动解包出 `Result.data`**，业务代码只写 happy path，也不用手动 `.data.data`。

`baseURL` 是 **`/api`，不含版本号**——api 文件里要自己带上 `/v1`：

```ts
get<string>('/v1/health')     // → 实际请求 /api/v1/health
```

⚠️ 分页请求用 `getPage()` 而不是 `get()`——分页元信息（`total` / `page` / `size`）与 `data` 平级，
自动解包只会留下 `data`，把它们全丢掉。`getPage()` 通过 `rawResult: true` 让拦截器跳过解包。
另注意**请求参数叫 `pageSize`，响应字段叫 `size`**，两侧刻意不同名。

### 3. 流式对话不用 `EventSource`

`src/utils/sse.ts` 用 `fetch` + `ReadableStream` 手工解析 SSE，配 `AbortController` 支持「停止生成」。
原因（CLAUDE.md 7.3）：原生 `EventSource` 只支持 GET、不能带 `Authorization` 头、
且断线会自动重连导致重复生成、重复扣 token。

事件名与后端约定一致：`message`（增量）/ `error` / `done`；以 `:` 开头的心跳注释帧被解析器忽略。

## 为什么 tsconfig 拆成三个

`tsconfig.json` 只做聚合，真正的配置在 `tsconfig.app.json`（浏览器）与 `tsconfig.node.json`（Node）两个 project reference 里。

**合并成一个也能构建成功，但会丢掉一条护栏。** `vite.config.ts` 里的 `import { fileURLToPath } from 'node:url'`
会把 `@types/node` 的**全局声明**拉进整个 program——只要它和 `src/` 同处一个 program，浏览器代码里写
`process.env.X` / `__dirname` 就不再报错。这一点靠 `types: ["vite/client"]` 拦不住：
`types` 数组只控制**自动注入**的全局包，管不了显式 `import` 带进来的。

拆开之后两侧互相看不见对方的全局，实测均在 `pnpm build` 阶段报错、vite 不会执行：

| 写法 | 位置 | 结果 |
|---|---|---|
| `process.env.HOME` | `src/` | `TS2591: Cannot find name 'process'` |
| `document.title` | `vite.config.ts` | `TS2584: Cannot find name 'document'` |

因为用了 project reference，类型检查命令是 `vue-tsc --build`（不是 `--noEmit`）。
增量信息落在 `node_modules/.tmp/`，随 `node_modules` 一起被忽略。

## 当前状态

**只是脚手架。** 目前只有三条业务路由（模型管理 / Agent 管理 / 对话），
对应的三个页面都是**只显示一行页面名的空壳**——后端 Controller 还没落地。
布局（左侧菜单 + 右侧 `router-view`）直接写在 `App.vue` 里。

唯一接了真实接口的是**模型管理页**：进入时自动调一次 `getHealth()`，
连通显示绿色「后端已连接：Hify is running」，不通显示红色「后端未连接」。
这是目前验证前后端链路最快的方式。
`src/api/` 目前只有 `health.ts`（后端唯一已实现的接口），
其余模块的接口文件等后端定下 DTO 后再逐个新增，避免前端先编一套契约。

## 已知取舍

| 取舍 | 说明 |
|---|---|
| Element Plus 全量引入 | 产物 gzip 约 374KB。按需引入需新增 `unplugin-auto-import` + `unplugin-vue-components`，属技术栈外依赖，按 CLAUDE.md 12.5 需先确认 |
| 未装图标包 | `@element-plus/icons-vue` 是独立包，侧边栏暂用纯文字菜单 |
| 未配 ESLint / Prettier | 同上，属技术栈外依赖 |
| TypeScript 锁 `~5.9.3` | TS 7 已不导出 `typescript/lib/tsc`，当前版本的 `vue-tsc` 起不来 |
