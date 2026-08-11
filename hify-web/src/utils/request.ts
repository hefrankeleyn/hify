import axios, { AxiosError, type AxiosRequestConfig, type AxiosInstance } from 'axios'
import { ElMessage } from 'element-plus'
import { SUCCESS_CODE, type PageData, type PageResult, type Result } from '@/types/result'

/**
 * 统一 HTTP 请求封装。
 *
 * 职责边界：
 * - 这里只处理**普通 JSON 接口**。对话流式接口走 `utils/sse.ts`（SSE 必须用 fetch，见 CLAUDE.md 7.3）。
 * - 响应拦截器统一做三件事：判业务码、失败弹 `ElMessage` 并 reject、成功时把 `Result.data` 自动解包，
 *   业务代码只写 happy path，不用每个页面 try 一遍、也不用手动 `.data.data`。
 */

/** 非流式接口的默认超时（毫秒）。后端同步调用上限 60s（CLAUDE.md 6.1），前端留一点余量 */
const DEFAULT_TIMEOUT = 65_000

/**
 * 后端 API 前缀的默认值。
 * `.env` 被仓库 .gitignore 忽略，新克隆可能没有这个文件，所以这里必须兜底，
 * 值与 `.env.example` 保持一致。
 *
 * ⚠️ 只到 `/api`，**不含版本号**。因此 `api/*.ts` 里要自己带上 `/v1`，
 * 例如 `get('/v1/health')` → 实际请求 `/api/v1/health`。
 */
const DEFAULT_API_BASE_URL = '/api'

declare module 'axios' {
  interface AxiosRequestConfig {
    /**
     * 跳过自动解包，让拦截器把完整的 `Result` 壳交出来。
     *
     * 只有分页请求需要：`total` / `page` / `size` 与 `data` 平级，
     * 解包成 `data` 会把它们丢掉（CLAUDE.md 8.2）。业务代码不要直接用这个字段，用 `getPage()`。
     */
    rawResult?: boolean
  }
}

/**
 * 业务错误。
 *
 * 后端返回了合法的 `Result` 但 `code !== 200` 时抛出，
 * 页面可以按 `code` 做差异化处理（如 401 跳登录、上下文超长引导新建会话）。
 */
export class ApiError extends Error {
  /** 后端 ErrorCode 枚举值，四位数字，按模块分号段（CLAUDE.md 8.5） */
  readonly code: number

  /**
   * @param code    业务错误码
   * @param message 后端返回的提示文案，可直接展示给用户
   */
  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/** axios 实例。baseURL 取自 .env，开发环境经 Vite 代理转发到 localhost:8080 */
const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL,
  timeout: DEFAULT_TIMEOUT,
  headers: { 'Content-Type': 'application/json' },
})

/**
 * 把 axios 抛出的各类网络异常翻译成用户看得懂的中文。
 *
 * @param error axios 异常对象
 * @returns 展示用文案
 */
function describeNetworkError(error: AxiosError): string {
  if (error.code === 'ECONNABORTED') {
    return '请求超时，请稍后重试'
  }
  // 没有 response 说明连接层就失败了（后端没起、代理不通、被浏览器拦截）
  if (!error.response) {
    return '无法连接服务器，请确认后端服务已启动'
  }
  const status = error.response.status
  if (status >= 500) {
    return `服务器异常（HTTP ${status}）`
  }
  return `请求失败（HTTP ${status}）`
}

// 响应拦截器：判业务码 → 失败提示并 reject → 成功自动解包出 Result.data
http.interceptors.response.use(
  (response) => {
    const body = response.data as Result<unknown>

    // 后端约定所有接口都回 Result 壳；拿不到 code 说明打到了非本系统的地址（如代理配错）
    if (body === null || typeof body !== 'object' || typeof body.code !== 'number') {
      const message = '响应格式不符合约定，请检查接口地址与代理配置'
      console.error('[request] 非法响应体', response.config.url, response.data)
      ElMessage.error(message)
      return Promise.reject(new ApiError(-1, message))
    }

    if (body.code !== SUCCESS_CODE) {
      console.warn('[request] 业务失败', response.config.url, body.code, body.message)
      ElMessage.error(body.message)
      return Promise.reject(new ApiError(body.code, body.message))
    }

    // 分页请求要保留与 data 平级的 total / page / size，原样交出整个壳
    if (response.config.rawResult === true) {
      return response
    }

    // 自动解包：调用方直接拿到业务数据，不用再写 .data.data
    response.data = body.data
    return response
  },
  (error: AxiosError) => {
    const message = describeNetworkError(error)
    console.error('[request] 网络异常', error.config?.url, error.message)
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

/**
 * GET 请求。
 *
 * @param url    相对路径，**需自带版本号**，如 `/v1/providers/1`
 * @param params query 参数
 * @param config 额外的 axios 配置（如单独放宽超时）
 * @returns 已解包的 `Result.data`
 * @throws ApiError 业务码非 200 时抛出
 */
export function get<T>(url: string, params?: object, config?: AxiosRequestConfig): Promise<T> {
  return http.get<T, { data: T }>(url, { params, ...config }).then((res) => res.data)
}

/**
 * POST 请求。
 *
 * @param url    相对路径，**需自带版本号**，如 `/v1/providers`
 * @param data   请求体
 * @param config 额外的 axios 配置
 * @returns 已解包的 `Result.data`；无返回值的写接口为 null
 * @throws ApiError 业务码非 200 时抛出
 */
export function post<T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T> {
  return http.post<T, { data: T }>(url, data, config).then((res) => res.data)
}

/**
 * PUT 请求。
 *
 * @param url    相对路径，**需自带版本号**，如 `/v1/providers/1`
 * @param data   请求体
 * @param config 额外的 axios 配置
 * @returns 已解包的 `Result.data`
 * @throws ApiError 业务码非 200 时抛出
 */
export function put<T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T> {
  return http.put<T, { data: T }>(url, data, config).then((res) => res.data)
}

/**
 * DELETE 请求（后端为软删除）。
 *
 * @param url    相对路径，**需自带版本号**，如 `/v1/providers/1`
 * @param config 额外的 axios 配置
 * @returns 已解包的 `Result.data`
 * @throws ApiError 业务码非 200 时抛出
 */
export function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return http.delete<T, { data: T }>(url, config).then((res) => res.data)
}

/**
 * GET 分页请求。
 *
 * 🔴 分页**必须**用这个方法，不能用 `get()`。
 * 后端 `PageResult` 把 `total` / `page` / `size` 拍平到与 `data` 同级（CLAUDE.md 8.2），
 * 自动解包只会留下 `data`，分页元信息全丢。这里通过 `rawResult` 让拦截器跳过解包。
 *
 * @param url    相对路径，**需自带版本号**，如 `/v1/providers`
 * @param params 分页与过滤参数（页码字段叫 `pageSize`，注意与响应侧的 `size` 区分）
 * @returns 列表 + 分页元信息
 * @throws ApiError 业务码非 200 时抛出
 */
export function getPage<T>(url: string, params?: object): Promise<PageData<T>> {
  return http.get<PageResult<T>>(url, { params, rawResult: true }).then((res) => {
    const body = res.data
    return {
      // 后端保证空列表返回 []，这里再兜一层，页面拿到的永远是数组
      list: body.data ?? [],
      total: body.total ?? 0,
      page: body.page ?? 1,
      size: body.size ?? 0,
    }
  })
}

export default http
