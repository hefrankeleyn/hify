import { ElMessageBox } from 'element-plus'
import { notifySuccess } from '@/utils/notify'

/**
 * 删除确认 composable：弹二次确认框 → 用户确认后调用 API → 成功后提示。
 *
 * 设计上刻意让返回的 Promise **永不 reject**——不管是用户点了取消，还是 API 调用失败，
 * 都收敛成 `false`，调用方因此可以只写一行代码，不需要额外包 try-catch：
 * ```ts
 * if (await useConfirm('确定删除吗？', () => deleteProvider(row.id))) {
 *   tableRef.value?.refresh()
 * }
 * ```
 * API 失败时不重复弹错误提示：真实接口的失败提示已经由 `utils/request.ts` 的响应拦截器统一弹过，
 * 这里再弹一次会让用户看到同一个错误提示两次；只在控制台记一条日志便于排查。
 *
 * @param message        确认框文案，如「确定删除提供商「xxx」吗？删除后不可恢复。」
 * @param apiCall        用户确认后要执行的异步操作（通常是删除接口）
 * @param successMessage 成功后的提示文案，默认「操作成功」
 * @returns 用户确认且 API 调用成功时 resolve(true)；用户取消或 API 调用失败时 resolve(false)
 */
export async function useConfirm(
  message: string,
  apiCall: () => Promise<unknown>,
  successMessage = '操作成功',
): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    // 用户点击取消 / 点击关闭 / 按 ESC，不算错误，静默返回
    return false
  }

  try {
    await apiCall()
  } catch (error) {
    console.warn('[useConfirm] API 调用失败', error)
    return false
  }

  notifySuccess(successMessage)
  return true
}
