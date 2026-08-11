/**
 * 对话编排：上下文装配 → RAG 注入 → 流式调模型 → 工具循环。
 * <p>🔴 <b>本包不开事务</b>。长流程拆成：短事务落库用户消息 → 本包的秒~分钟级编排 → 短事务落库助手消息。
 * <p>🔴 <b>异步线程里 ThreadLocal 是空的</b>——{@code UserContext} / {@code MDC} /
 * {@code RequestContextHolder} 都取不到，进入异步前取出来作为参数显式传入。
 * <p>🔴 <b>异步线程的异常不会进 GlobalExceptionHandler</b>，必须自己兜底，
 * 转成 SSE 的 {@code error} 事件发出去。
 * <p>🔴 {@code ThreadLocal} 用完必须 {@code remove()}——线程池复用线程，不清会串数据。
 * <p>🔴 工具调用轮次上限 <b>5 轮</b>；工具已执行之后的那一轮 LLM 请求，重试上限降为 0，
 * 否则会重复执行有副作用的工具。
 * <p>🔴 向量检索失败降级为不注入知识库，对话仍可用；LLM 不可用则明确报错推给前端。
 * <p>跑在 {@code llm-chat-} 线程池上（core 32 / max 64 / queue 16），
 * 拒绝策略 {@code AbortPolicy}，不用 {@code CallerRunsPolicy}。
 */
package com.hify.chat.runtime;
