/**
 * MCP 协议客户端：工具清单发现与缓存、工具调用。
 * <p>使用 JDK 的 {@code java.net.http.HttpClient}，不引三方 SDK。
 * <p>🔴 所有外部调用必须有超时，无一例外。
 * <p>🔴 MCP 鉴权信息一律不进日志。
 * <p>工具清单缓存在 Redis，数据库做二级兜底（6.5）。
 */
package com.hify.mcp.client;
