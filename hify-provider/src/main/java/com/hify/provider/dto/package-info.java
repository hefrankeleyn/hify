/**
 * 模型提供商的数据传输对象。
 * <p>三类：HTTP 入参 {@code XxxCreateRequest} / {@code XxxUpdateRequest} / {@code XxxQueryRequest}、
 * HTTP 出参 {@code XxxResponse} / {@code XxxDetailResponse}、跨模块契约对象（业务名词无后缀）。
 * <p>🔴 属性必须用包装类型，不设任何默认值。
 * <p>🔴 禁止出现：业务方法、MyBatis 注解、注入 Bean。
 * <p>Request 上带校验注解；分页请求的 {@code pageSize} 必须 {@code @Max(100)}，见 8.3。
 * <p>跨模块契约对象用 {@code @Value} + {@code @Builder}（不可变）。
 */
package com.hify.provider.dto;
