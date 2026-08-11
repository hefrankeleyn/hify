/**
 * 对话引擎模块（L3 编排层）。
 * <p>SSE 生命周期、上下文装配、RAG 注入、工具循环。
 * <p>依赖矩阵（CLAUDE.md 2.2）：{@code hify-chat → common, provider, agent, knowledge, mcp}。
 * <p>🔴 <b>同层禁止：绝不依赖 hify-workflow。</b>
 * <p>🔴 本模块承担运行时校验职责：Agent 绑定的模型 / 知识库 / 工具是否仍然有效，在这里查（4.3）。
 * <p>🔴 长流程拆短事务：短事务落库 → 无事务的秒级/分钟级编排 → 短事务落库。
 * <p>{@code message} 与 {@code message_reference} 是全项目仅有的两张 L2 大表（&gt; 500 万行），
 * 查询只有一种形态：按会话游标分页，且不查 {@code COUNT}，见 5.5 / 5.6。
 * <p>错误码号段：4000–4999。
 */
package com.hify.chat;
