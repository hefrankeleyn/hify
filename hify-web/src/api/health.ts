import { get } from '@/utils/request'

/**
 * 健康检查接口。
 *
 * 对应后端 `com.hify.controller.HealthController`，是目前后端唯一已落地的接口，
 * 前端用它验证「浏览器 → Vite 代理 /api → Spring Boot :8080」这条链路是否通。
 *
 * 各业务模块（provider / agent / chat / knowledge / mcp / workflow）的接口文件，
 * 等对应后端 Controller 落地后再在本目录下按模块逐个新增，一个模块一个文件。
 */

/**
 * 探测后端是否存活。
 *
 * ⚠️ axios 的 `baseURL` 只到 `/api`，版本号要自己带，
 * 所以这里写 `/v1/health` → 实际请求 `GET /api/v1/health`。
 *
 * 响应壳由 `utils/request.ts` 的拦截器自动解包，这里拿到的就是 `Result.data` 本身。
 *
 * @returns 后端固定返回的运行文案 `"Hify is running"`
 * @throws ApiError 业务码非 200 时抛出（拦截器已弹过 ElMessage）
 */
export function getHealth(): Promise<string> {
  return get<string>('/v1/health')
}
