/**
 * 工作流模块（L3 编排层）。
 * <p>JSON 定义解析与节点执行，线性 + 条件分支，不做可视化拖拽。
 * <p>依赖矩阵（CLAUDE.md 2.2）：{@code hify-workflow → common, provider, knowledge, mcp}。
 * <p>🔴 <b>同层禁止：绝不依赖 hify-chat。</b>
 * <p>🔴 也不依赖 {@code hify-agent}：工作流节点直接指定模型，不复用 Agent 配置。
 * <p>错误码号段：6000–6999。
 */
package com.hify.workflow;
