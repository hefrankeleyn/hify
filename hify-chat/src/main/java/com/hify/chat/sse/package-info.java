/**
 * SSE 连接生命周期管理。
 * <p>🔴 必须用 {@code SseEmitter}，不用阻塞式 {@code @ResponseBody}——
 * 后者每条连接独占一个 Tomcat 工作线程。
 * <p>🔴 超时 300s，配 15s 心跳（注释帧）。心跳同时是探测客户端断连的手段——
 * 往已关闭的连接写会立即抛异常。
 * <p>🔴 {@code onCompletion} / {@code onTimeout} / {@code onError} <b>三个回调都要挂</b>，
 * 缺一个漏一种断连场景。
 * <p>🔴 断连时必须取消上游 LLM 请求（关闭流 + cancel Future），
 * 否则用户关了页面 token 还在烧。
 * <p>🔴 {@code release()} 必须<b>幂等</b>——多个回调可能都触发。
 * <p>🔴 {@code emitter.send()} 抛 {@code IOException} 是<b>正常路径</b>（客户端断了），
 * 用 {@code log.warn}，不要 {@code log.error} 打全栈。
 * <p>🔴 流式输出禁止逐 token 打日志，只在流开始、流结束、异常三处打。
 * <p>事件命名：{@code message}（增量）/ {@code error} / {@code done}；心跳用注释帧。
 * <p>不等式必须成立：Nginx 360s &gt; SseEmitter 300s ≥ 上游整体 300s。
 */
package com.hify.chat.sse;
