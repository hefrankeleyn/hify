/**
 * 知识库模块（L2 能力层）。
 * <p>文档解析、分块、向量化、检索。一期只支持 TXT，固定长度分块。
 * <p>文档元数据在 MySQL，向量在 PostgreSQL + pgvector，<b>跨库</b>。
 * <p>依赖矩阵（CLAUDE.md 2.2）：{@code hify-knowledge → common, provider}（向量化要调 embedding 模型）。
 * <p>🔴 同层禁止：绝不依赖 {@code hify-mcp}。
 * <p>🔴 跨库一致性不做分布式事务：删文档走软删除，检索按 {@code document_id} 过滤，
 * 每日 CronJob 清理孤儿向量行。
 * <p>错误码号段：7000–7999。
 */
package com.hify.knowledge;
