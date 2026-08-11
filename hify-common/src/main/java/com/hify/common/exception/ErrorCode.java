package com.hify.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全项目错误码枚举。
 * <p>四位数字，按模块分段（CLAUDE.md 8.5）：
 * <pre>
 * 1000–1999  通用
 * 2000–2999  Provider
 * 3000–3999  Agent
 * 4000–4999  Chat
 * 5000–5999  MCP
 * 6000–6999  Workflow
 * 7000–7999  Knowledge
 * </pre>
 *
 * <p>⚠️ <b>本项目刻意把全部号段集中在 common 的单个枚举里</b>，而不是每个模块各建一个
 * {@code XxxErrorCode}。这是一个明确的取舍：
 * <ul>
 *   <li>换来的：{@link BizException} 只认一种类型，编译期就能拦住硬编码错误码；
 *       全项目错误码一屏看完，不会撞号。</li>
 *   <li>付出的：{@code hify-common} 从此知道各业务模块的概念，
 *       L0 不再是纯技术层。新增模块错误码要回来改 common。</li>
 * </ul>
 * 因此新增枚举值时<b>必须落在本模块对应的号段内</b>，不要图近就近插。
 *
 * <p>🔴 抛异常一律 {@code throw new BizException(ErrorCode.XXX)}，
 * 禁止硬编码错误码和错误信息（CLAUDE.md 8.5）。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ==================== 1000–1999 通用 ====================

    /** 系统内部错误：未被识别的异常统一兜底到这里，同时也是 {@code Result.fail(message)} 的默认码 */
    SYSTEM_ERROR(1000, "系统内部错误"),

    /** 请求参数错误：@Valid 校验不通过、枚举值非法、JSON 解析失败等 */
    PARAM_INVALID(1001, "请求参数错误"),

    /** 缺少必填参数：参数完全没传，区别于传了但值不合法的 {@link #PARAM_INVALID} */
    PARAM_MISSING(1002, "缺少必填参数"),

    /** 未授权：未登录，或 token / API Key 已失效。前端应跳转登录 */
    UNAUTHORIZED(1003, "未登录或登录已失效"),

    /** 无权限：身份有效但不允许执行该操作。区别于 {@link #UNAUTHORIZED}，前端不要跳登录 */
    FORBIDDEN(1004, "无权限执行该操作"),

    /** 资源不存在：按 id 查不到对象，或访问了不存在的路径 */
    NOT_FOUND(1005, "请求的资源不存在"),

    /** 请求方法不支持：对只接受 POST 的接口发了 GET 之类 */
    METHOD_NOT_ALLOWED(1006, "不支持的请求方法"),

    /** 请求内容过大：超过 Nginx 的 client_max_body_size 或应用侧的上传上限 */
    REQUEST_TOO_LARGE(1007, "请求内容过大"),

    /** 请求过于频繁：限流拒绝，或线程池按 AbortPolicy 拒绝任务（CLAUDE.md 6.2） */
    TOO_MANY_REQUESTS(1008, "请求过于频繁，请稍后重试"),

    /** 数据冲突：唯一键重复、状态机不允许的流转等 */
    DATA_CONFLICT(1009, "数据已存在或当前状态不允许该操作"),

    // ==================== 2000–2999 Provider ====================
    // 模块落地时在此追加，勿越段

    // ==================== 3000–3999 Agent ====================
    // 模块落地时在此追加，勿越段

    // ==================== 4000–4999 Chat ====================
    // 模块落地时在此追加，勿越段

    // ==================== 5000–5999 MCP ====================
    // 模块落地时在此追加，勿越段

    // ==================== 6000–6999 Workflow ====================
    // 模块落地时在此追加，勿越段

    // ==================== 7000–7999 Knowledge ====================
    // 模块落地时在此追加，勿越段

    ;

    /** 四位业务错误码，直接透出到响应体的 code 字段 */
    private final Integer code;

    /**
     * 默认错误文案，可直接展示给用户。
     * <p>需要携带上下文时（如「Agent[客服助手] 名称重复」）不要改枚举，
     * 用 {@link BizException#BizException(ErrorCode, String)} 在抛出点覆盖。
     */
    private final String message;
}
