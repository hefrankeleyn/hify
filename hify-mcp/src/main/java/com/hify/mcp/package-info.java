/**
 * MCP 工具模块（L2 能力层）。
 * <p>MCP Server 配置、工具发现与调用。
 * <p>依赖矩阵（CLAUDE.md 2.2）：{@code hify-mcp → common}。
 * <p>🔴 同层禁止：绝不依赖 {@code hify-knowledge}。
 * <p>降级策略（6.5）：MCP Server 不可用时工具清单走缓存兜底（数据库做二级兜底）；
 * 调用失败作为工具结果交回模型，<b>不中断对话</b>。
 * <p>错误码号段：5000–5999。
 */
package com.hify.mcp;
