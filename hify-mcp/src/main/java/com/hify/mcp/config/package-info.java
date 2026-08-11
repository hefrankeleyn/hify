/**
 * MCP 工具的模块级配置：读配置、装配 Bean。
 * <p>配置项外化到 {@code application.yml}，前缀 {@code hify.mcp.*}，不硬编码。
 * <p>配置属性类命名 {@code XxxProperties}。
 * <p>🔴 线程池必须显式构造（{@code ThreadPoolTaskExecutor} / {@code ThreadPoolExecutor}），
 * 禁止 {@code Executors} 工厂方法，禁止默认的 {@code SimpleAsyncTaskExecutor}，且必须有有意义的名字前缀。
 */
package com.hify.mcp.config;
