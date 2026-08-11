/**
 * 模型提供商模块（L1 能力底座）。
 * <p>提供商管理（OpenAI / Claude / Gemini / Ollama）与模型客户端。
 * <p>依赖矩阵（CLAUDE.md 2.2）：{@code hify-provider → common}。
 * <p>🔴 这是最底层的业务模块，<b>不得依赖任何 L2 及以上模块</b>——出现 provider → agent 说明抽象反了。
 * <p>🔴 跨模块只能引用对方的 {@code service}（接口）/ {@code dto} / {@code constant} / {@code exception}，
 * 注入接口、构造器注入、只传 id 与对方 dto。
 * <p>错误码号段：2000–2999。
 */
package com.hify.provider;
