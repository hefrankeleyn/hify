/**
 * 向量存储与检索（PostgreSQL + pgvector）。
 * <p>不走 MyBatis-Plus——向量表在另一个库，且 {@code vector} 类型 MP 不认，
 * 用 {@code JdbcTemplate} 直接写 SQL。
 * <p>🔴 维度固定 <b>1536</b>，一旦落地不可改——改维度要重跑全量向量化。
 * <p>🔴 必须建 HNSW 索引，用 {@code vector_cosine_ops}；
 * 查询运算符必须用 {@code <=>} 与之匹配——<b>不匹配不报错</b>，只是悄悄退化成全表扫。
 * <p>🔴 检索必须加 {@code LIMIT}，禁止全量排序。
 * <p>🔴 建索引前先调 {@code maintenance_work_mem}（默认 64MB，小内存容器里建索引会 OOM）。
 * <p>⚠️ 带过滤的检索要验证召回：HNSW 是先取 {@code ef_search} 个候选再按 {@code WHERE} 过滤，
 * 目标知识库占比小时可能召回不足。
 */
package com.hify.knowledge.vector;
