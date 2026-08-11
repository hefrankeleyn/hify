/**
 * 文档处理链：解析 → 分块 → 向量化。
 * <p>一期只支持 TXT，固定长度分块。
 * <p>跑在 {@code kb-index-} 线程池上（core 2 / max 4 / queue 64），
 * 与对话用的 {@code llm-chat-} 池<b>严格隔离，绝不共用</b>。
 */
package com.hify.knowledge.pipeline;
