/**
 * MCP 工具的业务实现。
 * <p>业务规则、事务边界、编排都在这里。
 * <p>🔴 {@code @Transactional} <b>只允许出现在本包的 public 方法上</b>，
 * 写方法一律 {@code (rollbackFor = Exception.class)}，只读方法不加。
 * <p>🔴 事务方法内禁止四件事：调 LLM / MCP / 任何 HTTP；调其它模块的<b>写</b>方法；
 * {@code Thread.sleep} 或等锁等 Future；创建 {@code SseEmitter} 或向其 send。
 * <p>🔴 禁止出现 HTTP 概念（{@code HttpServletRequest}、{@code Result}）与手写 SQL。
 * <p>可以调 {@code mapper}、{@code entity}、{@code dto}，以及其它模块 {@code service} 包下的接口。
 * <p>跨模块调用不共享事务：被调方自己管事务，不要指望对方能被自己回滚。
 */
package com.hify.mcp.service.impl;
