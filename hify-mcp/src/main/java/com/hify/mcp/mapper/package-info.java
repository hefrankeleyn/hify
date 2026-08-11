/**
 * MCP 工具的数据访问层。
 * <p>一律 {@code extends BaseMapper<XxxEntity>}，只做单表 CRUD。
 * <p>🔴 禁止出现：业务逻辑、{@code @Transactional}、跨模块 JOIN、跨库 JOIN。
 * <p>🔴 禁止 {@code SELECT *}；禁止 {@code @Select} 注解 SQL——
 * 复杂 SQL 写 {@code resources/mapper/mcp/} 下的 XML，参数用 {@code #{}}，禁止 {@code ${}}。
 * <p>🔴 禁止无 WHERE 无分页的 {@code selectList}。
 * <p>不使用 MyBatis-Plus 的 {@code IService} / {@code ServiceImpl}——
 * 它们会把 CRUD 语义混进业务 Service，诱导 Controller 直接传 Entity。
 */
package com.hify.mcp.mapper;
