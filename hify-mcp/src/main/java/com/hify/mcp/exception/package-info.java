/**
 * MCP 工具的模块自有异常类型。
 * <p>⚠️ <b>错误码不放这里</b>——全项目共用 {@code com.hify.common.exception.ErrorCode} 单个枚举，
 * 本模块的码落在 <b>5000–5999</b> 号段内，见 CLAUDE.md 8.5。
 * <p>🔴 只抛 {@code BizException}，禁止 {@code throw new RuntimeException} / {@code new Exception}。
 * <p>🔴 禁止吞异常——catch 块必须至少有 {@code log.warn}/{@code log.error}，否则重抛。
 * <p>本包通常是空的。只有需要携带额外结构化上下文时，才在此定义 {@code BizException} 的子类。
 */
package com.hify.mcp.exception;
