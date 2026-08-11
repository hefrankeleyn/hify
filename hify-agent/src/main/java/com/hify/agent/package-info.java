/**
 * Agent 定义与配置模块（L2 配置层）。
 * <p>持有 {@code agent_knowledge_base}、{@code agent_tool} 两张绑定中间表。
 * <p>依赖矩阵（CLAUDE.md 2.2）：{@code hify-agent → common}。
 * <p>🔴 <b>刻意只依赖 common</b>：Agent 绑定模型 / 知识库 / MCP 工具时只存 ID，
 * 管理面保存时<b>不校验对方是否存在</b>（4.3）——校验会引出 agent → knowledge、agent → mcp
 * 两条同层依赖，违反 2.2 的「同层禁止」。存在性校验由 {@code hify-chat} 在运行时装配配置时完成。
 * <p>🔴 中间表只存两个 ID，绝不 JOIN 对方主表。
 * <p>错误码号段：3000–3999。
 */
package com.hify.agent;
