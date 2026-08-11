/**
 * 工作流的 HTTP 适配层。
 * <p>职责只有四件：收参、{@code @Valid} 校验、调 {@code service}、包 {@code Result}。
 * <p>🔴 方法体不超过 3 行（日志 + 调用 + 返回）。超过说明业务逻辑漏到了 web 层。
 * <p>🔴 禁止出现：业务判断、{@code @Transactional}、Entity、Mapper、{@code service.impl}。
 * <p>🔴 禁止写 try-catch，异常统一交给 {@code com.hify.common.exception.GlobalExceptionHandler}。
 * <p>路径形如 {@code /api/v1/资源复数名}，非 CRUD 操作用动词，见 CLAUDE.md 8.1。
 */
package com.hify.workflow.controller;
