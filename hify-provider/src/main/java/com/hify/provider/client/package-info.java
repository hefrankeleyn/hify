/**
 * LLM HTTP 客户端：请求构造、SSE 解析、重试、错误映射。
 * <p>使用 JDK 的 {@code java.net.http.HttpClient}，不引三方 HTTP 库，
 * 也不用 {@code WebClient}（会把 WebFlux 拖进来，违反七节红线）。
 * <p>🔴 <b>四道超时闸缺一不可</b>：连接 3s、首字节 30s（Ollama 90s）、
 * 流间隔 30s（Ollama 60s）、整体 300s（Ollama 600s）。
 * <p>🔴 第 3 道闸<b>必须自己实现</b>——标准 HTTP 客户端的 read timeout 只覆盖第一个字节，
 * 流开始后上游卡死十分钟也不会超时。要在读流循环里算「距上一个 chunk 多久」。
 * <p>🔴 <b>首字节到达之后一律不重试</b>：用户屏幕上已有半句话，重试会把两段不连贯的文本拼在一起。
 * 退避 500ms → 1500ms（±30% 抖动），最多 2 次，总预算 60s。
 * <p>🔴 熔断器<b>按 providerId 分实例</b>，用时间窗不用计数窗，
 * {@code ignoreExceptions(BizException.class)}——业务错误不计入。
 * <p>🔴 每个 provider 一个信号量（舱壁）：Ollama 上限 3，云 API 20。
 * <p>🔴 API Key 一律不进日志，必须打时用掩码（保留首尾各 4 位）。
 */
package com.hify.provider.client;
