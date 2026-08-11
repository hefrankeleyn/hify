/**
 * SSE 流式响应解析。
 *
 * 🔴 为什么不用原生 `EventSource`（CLAUDE.md 7.3）：
 * 1. 它只支持 GET，对话要传 messages，塞不进 URL；
 * 2. 它不能带 Authorization 头；
 * 3. 它断线会自动重连，会导致重复生成、重复扣 token。
 *
 * 因此这里用 `fetch` + `ReadableStream` 手工解析 SSE 报文，配 `AbortController` 支持「停止生成」。
 */

/** 后端约定的事件名（CLAUDE.md 7.1）：增量内容 */
export const SSE_EVENT_MESSAGE = 'message'
/** 后端约定的事件名：错误 */
export const SSE_EVENT_ERROR = 'error'
/** 后端约定的事件名：正常结束 */
export const SSE_EVENT_DONE = 'done'

/** 解析出的单条 SSE 事件 */
export interface SseEvent {
  /** 事件名。报文未写 `event:` 字段时按 SSE 规范默认为 `message` */
  event: string
  /** 事件数据，多行 `data:` 已按规范用 `\n` 拼接 */
  data: string
  /** 事件 id，后端未下发时为空字符串 */
  id: string
}

/** 打开 SSE 流的参数 */
export interface SseOptions {
  /** 完整请求路径，如 `/api/v1/chat/completions` */
  url: string
  /** 请求体，会被 JSON 序列化；不传则发空 POST */
  body?: unknown
  /** 附加请求头，如 Authorization */
  headers?: Record<string, string>
  /** 中断信号，用于「停止生成」；中断后 `openSseStream` 正常返回，不抛异常 */
  signal?: AbortSignal
  /** 每解析出一条事件回调一次 */
  onEvent: (event: SseEvent) => void
}

/**
 * 把一段完整的 SSE 报文块（两个换行之间的内容）解析成事件对象。
 *
 * @param chunk 单个事件块的原始文本，不含结尾的空行
 * @returns 解析结果；整块都是注释（心跳帧）时返回 null
 */
function parseEventChunk(chunk: string): SseEvent | null {
  let event = SSE_EVENT_MESSAGE
  let id = ''
  const dataLines: string[] = []

  for (const rawLine of chunk.split('\n')) {
    // 去掉 CRLF 换行残留的 \r
    const line = rawLine.replace(/\r$/, '')

    // 以冒号开头的是注释帧——后端 15s 一次的心跳就走这里，直接忽略（CLAUDE.md 7.1）
    if (line.length === 0 || line.startsWith(':')) {
      continue
    }

    const colonIndex = line.indexOf(':')
    // 没有冒号时，整行是字段名、值为空串（SSE 规范）
    const field = colonIndex === -1 ? line : line.slice(0, colonIndex)
    let value = colonIndex === -1 ? '' : line.slice(colonIndex + 1)
    // 规范规定字段值前的单个空格要去掉
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }

    switch (field) {
      case 'event':
        event = value
        break
      case 'data':
        dataLines.push(value)
        break
      case 'id':
        id = value
        break
      case 'retry':
        // 本项目不做自动重连（见文件头第 3 条），retry 字段直接忽略
        break
      default:
        // 未知字段按规范忽略，但打日志便于发现前后端约定漂移
        console.debug('[sse] 忽略未知字段', field)
        break
    }
  }

  // 整块只有心跳注释，没有任何 data，不产生事件
  if (dataLines.length === 0) {
    return null
  }
  return { event, data: dataLines.join('\n'), id }
}

/**
 * 打开一条 SSE 流并持续解析，直到流结束或被中断。
 *
 * 使用方通过 `onEvent` 拿到每一条事件；本函数不解释事件语义（哪个是内容、哪个是错误），
 * 那属于业务层，放在 `composables/useSse.ts`。
 *
 * @param options 请求与回调配置
 * @returns 流正常结束或被主动中断时 resolve
 * @throws Error 建连失败、HTTP 状态非 2xx、或响应体为空时抛出
 */
export async function openSseStream(options: SseOptions): Promise<void> {
  const { url, body, headers, signal, onEvent } = options

  console.info('[sse] 建立连接', url)
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  })

  // 非 2xx：后端在建流之前就失败了（如参数校验不过），此时响应体是普通的 Result JSON
  if (!response.ok) {
    const text = await response.text()
    console.error('[sse] 建连失败', url, response.status, text)
    throw new Error(`SSE 建连失败（HTTP ${response.status}）`)
  }
  if (!response.body) {
    console.error('[sse] 响应体为空', url)
    throw new Error('SSE 响应体为空')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  // 跨 chunk 的半条报文缓冲区——网络分片不保证落在事件边界上
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      // stream: true 保证多字节 UTF-8 字符被切断时不会解码出乱码
      buffer += decoder.decode(value, { stream: true })

      // 事件之间以空行分隔；兼容 \n\n 与 \r\n\r\n 两种换行
      let separatorIndex = buffer.search(/\r?\n\r?\n/)
      while (separatorIndex !== -1) {
        const matched = /\r?\n\r?\n/.exec(buffer.slice(separatorIndex))
        const separatorLength = matched ? matched[0].length : 2
        const chunk = buffer.slice(0, separatorIndex)
        buffer = buffer.slice(separatorIndex + separatorLength)

        const parsed = parseEventChunk(chunk)
        if (parsed) {
          onEvent(parsed)
        }
        separatorIndex = buffer.search(/\r?\n\r?\n/)
      }
    }

    // 流结束时缓冲区可能还剩最后一条没有以空行收尾的事件
    const tail = parseEventChunk(buffer)
    if (tail) {
      onEvent(tail)
    }
    console.info('[sse] 流正常结束', url)
  } catch (error) {
    // 主动中断（用户点「停止生成」）是正常路径，不当作错误
    if (signal?.aborted) {
      console.info('[sse] 流被主动中断', url)
      return
    }
    console.error('[sse] 读流异常', url, error)
    throw error
  } finally {
    // 无论何种路径都要释放读锁，否则连接不会被浏览器回收
    reader.releaseLock()
  }
}
