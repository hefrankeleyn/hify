import { ElMessage } from 'element-plus'

/**
 * 统一消息提示封装。
 *
 * 只覆盖「主动提示」场景（如新增 / 删除成功后的一次性反馈）。
 * 请求失败的提示已经由 `utils/request.ts` 的响应拦截器统一弹出，业务代码不要再用这里的
 * `notifyError` 重复提示同一个错误——那会导致用户看到两条一样的消息。
 */

/** 统一时长（毫秒），比 Element Plus 默认的 3000ms 略长，给用户留出看清文案的时间 */
const DEFAULT_DURATION = 2500

/**
 * 成功提示。
 * @param message 提示文案
 */
export function notifySuccess(message: string): void {
  ElMessage({ message, type: 'success', duration: DEFAULT_DURATION, showClose: true })
}

/**
 * 错误提示。
 *
 * 仅用于**不经过** `utils/request.ts`（如本地校验、mock 接口）产生的失败；
 * 经过 axios 的真实接口失败不要调这个，拦截器已经弹过了。
 *
 * @param message 提示文案
 */
export function notifyError(message: string): void {
  ElMessage({ message, type: 'error', duration: DEFAULT_DURATION, showClose: true })
}

/**
 * 警告提示。
 * @param message 提示文案
 */
export function notifyWarning(message: string): void {
  ElMessage({ message, type: 'warning', duration: DEFAULT_DURATION, showClose: true })
}
