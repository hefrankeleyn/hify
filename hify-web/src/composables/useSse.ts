import { readonly, ref } from 'vue'
import {
  SSE_EVENT_DONE,
  SSE_EVENT_ERROR,
  SSE_EVENT_MESSAGE,
  openSseStream,
  type SseEvent,
} from '@/utils/sse'

/**
 * 对话流式响应的组合式封装。
 *
 * 把 `utils/sse.ts` 的裸事件流翻译成页面直接可绑定的响应式状态，
 * 并按 CLAUDE.md 7.1 约定的三个事件名（message / error / done）解释语义。
 */

/**
 * 后端通过 `error` 事件下发的错误负载（CLAUDE.md 6.5）。
 */
export interface SseErrorPayload {
  /** 业务错误码 */
  code?: number
  /** 展示给用户的文案 */
  message: string
  /** 是否可重试，决定前端要不要显示「重试」按钮 */
  retryable?: boolean
  /** 建议的后续动作：check_provider_config（去改配置）/ new_conversation（新建会话） */
  actionHint?: string
}

/**
 * 建立一条对话 SSE 流并维护其状态。
 *
 * @returns 响应式状态与 start / stop 两个操作
 */
export function useSse() {
  /** 是否正在接收流（建连中也算） */
  const streaming = ref(false)
  /** 已累积的增量文本 */
  const content = ref('')
  /** 失败信息；成功路径下始终为 null */
  const error = ref<SseErrorPayload | null>(null)

  /** 当前流的中断控制器，仅在 streaming 期间非空 */
  let controller: AbortController | null = null

  /**
   * 解释单条事件。
   *
   * @param event 已解析的 SSE 事件
   */
  function handleEvent(event: SseEvent): void {
    switch (event.event) {
      case SSE_EVENT_MESSAGE:
        // 🔴 增量事件量极大，这里绝不能打日志（对应后端「流式输出禁止逐 token 打日志」）
        content.value += event.data
        break
      case SSE_EVENT_ERROR:
        error.value = parseErrorPayload(event.data)
        console.error('[useSse] 收到错误事件', error.value)
        break
      case SSE_EVENT_DONE:
        console.info('[useSse] 收到结束事件')
        break
      default:
        console.warn('[useSse] 未知事件名', event.event)
        break
    }
  }

  /**
   * 把 error 事件的 data 解析成结构化负载；后端只发了纯文本时退化为 message。
   *
   * @param data error 事件的原始 data
   * @returns 错误负载
   */
  function parseErrorPayload(data: string): SseErrorPayload {
    try {
      const parsed = JSON.parse(data) as SseErrorPayload
      return typeof parsed?.message === 'string' ? parsed : { message: data }
    } catch {
      return { message: data }
    }
  }

  /**
   * 发起一次流式对话。
   *
   * 调用前会清空上一轮的内容与错误；同一时刻只允许一条流，重复调用会先中断上一条。
   *
   * @param url  完整请求路径，如 `/api/v1/chat/completions`
   * @param body 请求体
   */
  async function start(url: string, body: unknown): Promise<void> {
    if (streaming.value) {
      console.warn('[useSse] 上一条流仍在进行，先中断它')
      stop()
    }

    content.value = ''
    error.value = null
    streaming.value = true
    controller = new AbortController()

    try {
      await openSseStream({ url, body, signal: controller.signal, onEvent: handleEvent })
    } catch (e) {
      // 建连失败 / 读流异常：转成用户可见的错误状态，不向上抛（页面不需要 try-catch）
      const message = e instanceof Error ? e.message : '对话连接异常'
      error.value = { message, retryable: true }
      console.error('[useSse] 流失败', url, e)
    } finally {
      streaming.value = false
      controller = null
    }
  }

  /**
   * 中断当前流（「停止生成」）。
   * 已经收到的内容保留——首字节之后不重试是后端规矩，前端同样不清空已渲染文本。
   */
  function stop(): void {
    if (!controller) {
      return
    }
    console.info('[useSse] 主动停止生成')
    controller.abort()
  }

  return {
    streaming: readonly(streaming),
    content: readonly(content),
    error: readonly(error),
    start,
    stop,
  }
}
