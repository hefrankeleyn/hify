package com.hify.common.exception;

import lombok.Getter;

/**
 * 业务异常。
 * <p>🔴 CLAUDE.md 3.7 红线：<b>全项目只抛这一种异常</b>，
 * 禁止 {@code throw new RuntimeException} / {@code new Exception}。
 *
 * <p>使用约定：
 * <ul>
 *   <li>用默认文案：{@code throw new BizException(ErrorCode.NOT_FOUND)}</li>
 *   <li>需要带上下文时覆盖文案：
 *       {@code throw new BizException(ErrorCode.DATA_CONFLICT, "Agent 名称已存在: " + name)}</li>
 *   <li>包装下层异常时把 cause 传进来，别丢栈：
 *       {@code throw new BizException(ErrorCode.SYSTEM_ERROR, e)}</li>
 * </ul>
 *
 * <p>🔴 抛出前先按 3.4 打一条 {@code log.warn}，写清原因与上下文。
 * <p>🔴 Controller 不写 try-catch，统一由 {@code @RestControllerAdvice} 转成
 * {@code Result.fail(errorCode.getCode(), getMessage())}（3.7 第 5 条）。
 * <p>⚠️ 但 <b>异步线程里的异常进不了 {@code @RestControllerAdvice}</b>，
 * SSE 的异步任务必须自己兜底（3.7 第 6 条、七节示例）。
 *
 * <p>⚠️ 熔断器（{@code hify-provider} 的 {@code CircuitBreakerService}）<b>不是</b>整体
 * {@code ignoreExceptions(BizException.class)}——{@code LlmHttpClient} 把所有失败（含超时、
 * 限流这类真正的上游故障）都统一转成了本异常，整体排除会让熔断器永远看不到失败、形同虚设。
 * 实际做法是按 {@link ErrorCode} 精确判断：只有 {@link ErrorCode#PROVIDER_AUTH_FAILED} 这类
 * "调用方自己配置错了"的错误码不计入失败率，超时、限流仍然计入。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码，决定响应体的 code 字段，不会为 null。枚举天然可序列化，不能加 transient */
    private final ErrorCode errorCode;

    /**
     * 用错误码的默认文案构造。
     *
     * @param errorCode 错误码，不能为 null
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 用自定义文案覆盖错误码的默认文案。
     * <p>适用于需要把具体对象名、数量等上下文拼进提示语的场景。
     *
     * @param errorCode 错误码，不能为 null
     * @param message   自定义错误文案，会成为 {@link #getMessage()} 的返回值；
     *                  传 null 则 {@link #getMessage()} 返回 null，调用方需自行兜底
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 用错误码的默认文案构造，并保留下层异常。
     *
     * @param errorCode 错误码，不能为 null
     * @param cause     下层异常，允许为 null
     */
    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    /**
     * 用自定义文案构造，并保留下层异常。
     *
     * @param errorCode 错误码，不能为 null
     * @param message   自定义错误文案
     * @param cause     下层异常，允许为 null
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 取四位业务错误码。
     * <p>等价于 {@code getErrorCode().getCode()}，给全局异常处理器省一次链式调用。
     *
     * @return 错误码数值，不会为 null
     */
    public Integer getCode() {
        return errorCode.getCode();
    }
}
