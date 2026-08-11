import { ref, type Ref } from 'vue'

/** `useRequest` 的返回值 */
export interface UseRequestResult<T, Args extends unknown[]> {
  /** 最近一次成功请求的返回数据；请求前 / 失败时为 undefined */
  data: Ref<T | undefined>
  /** 请求进行中标志 */
  loading: Ref<boolean>
  /** 最近一次失败的错误；成功后自动清空 */
  error: Ref<Error | null>
  /** 触发请求；参数原样透传给传入的 API 方法 */
  execute: (...args: Args) => Promise<T | undefined>
}

/**
 * 请求状态管理 composable：包一层 loading / error / data 三态，避免每个页面重复写 try-catch-finally。
 *
 * 不在这里弹错误提示——真实接口失败已经由 `utils/request.ts` 的响应拦截器统一弹了 `ElMessage`，
 * 这里重复弹会导致用户看到两条一样的消息。这里只把 `error` 存下来，页面要不要用它渲染错误态自己决定。
 *
 * @param apiFn 返回 Promise 的 API 方法
 * @example
 * ```ts
 * const { data, loading, error, execute } = useRequest(getProviderDetail)
 * onMounted(() => execute(providerId))
 * ```
 */
export function useRequest<T, Args extends unknown[] = []>(
  apiFn: (...args: Args) => Promise<T>,
): UseRequestResult<T, Args> {
  const data = ref<T>() as Ref<T | undefined>
  const loading = ref(false)
  const error = ref<Error | null>(null)

  async function execute(...args: Args): Promise<T | undefined> {
    loading.value = true
    error.value = null
    try {
      const result = await apiFn(...args)
      data.value = result
      return result
    } catch (err) {
      error.value = err instanceof Error ? err : new Error(String(err))
      return undefined
    } finally {
      loading.value = false
    }
  }

  return { data, loading, error, execute }
}
