package com.hify.common.exception;

import com.hify.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 * <p>🔴 CLAUDE.md 3.7 第 5 条：<b>Controller 禁止写 try-catch</b>，异常统一在这里转成
 * {@code Result}，所有响应都经 {@link Result#fail} 构造、错误码一律取自 {@link ErrorCode}。
 *
 * <p><b>HTTP 状态码一律 200</b>，错误信息只体现在响应体的 {@code code} 字段。
 * 这是为了兑现 8.2 的「所有接口返回 {@code Result<T>}」——前端只需一条解析路径。
 * 副作用：Spring 原本会让 {@link MethodArgumentNotValidException} 产生 400，
 * 被本处理器接管后变成 200。若要恢复真实状态码，在对应方法上加 {@code @ResponseStatus}。
 *
 * <p>⚠️ <b>三条覆盖不到的路径，写代码时要自己兜住：</b>
 * <ol>
 *   <li><b>异步线程</b>——CLAUDE.md 3.7 第 6 条。SSE 的 {@code llmChatExecutor.execute(...)}
 *       里抛的异常不会走到这里，必须在 runnable 内部自己 catch 后经 {@code SseEmitter} 发 error 事件。</li>
 *   <li><b>响应已经开始输出之后</b>——SSE 首字节已发出时再抛异常，改不了状态码也插不进 JSON 体，
 *       只能作为一个 {@code error} 事件补发。</li>
 *   <li><b>Filter / Interceptor 里抛的异常</b>——早于 DispatcherServlet，进不了 advice。</li>
 * </ol>
 *
 * <p>⚠️ 当前只声明了三个 handler。其余 Web 层异常（{@code BindException} 即查询参数绑定失败、
 * {@code ConstraintViolationException}、{@code HttpMessageNotReadableException} 即 JSON 格式错、
 * {@code HttpRequestMethodNotSupportedException}、{@code NoResourceFoundException}）
 * 都会落到 {@link #handleException} 里被报成「系统内部错误」并打 ERROR 全栈——
 * 这些其实是客户端的问题，需要时再按 {@link ErrorCode} 里已备好的
 * {@code PARAM_INVALID} / {@code METHOD_NOT_ALLOWED} / {@code NOT_FOUND} 补 handler。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 多个字段校验错误之间的分隔符 */
    private static final String FIELD_ERROR_DELIMITER = "; ";

    /** 单个字段校验错误里「字段名」与「错误文案」之间的分隔符 */
    private static final String FIELD_MESSAGE_DELIMITER = ": ";

    /**
     * 处理业务异常。
     * <p>这是可预期的失败（名称重复、状态不允许流转等），按 3.4 打 {@code warn} 且不带堆栈——
     * 它不是故障，打全栈只会淹没真正的错误。
     *
     * @param exception 业务异常，由 {@code service.impl} 抛出
     * @param request   当前请求，仅用于取 URI 做日志上下文
     * @return 携带 {@link BizException#getCode()} 与异常文案的失败响应，不会为 {@code null}
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException exception, HttpServletRequest request) {
        // BizException 允许用 null 覆盖文案,这里兜底回退到枚举默认文案,避免响应体出现 null（8.4）
        String message = StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : exception.getErrorCode().getMessage();

        log.warn("[BIZ] 业务异常, uri={}, code={}, message={}",
                request.getRequestURI(), exception.getCode(), message);

        return Result.fail(exception.getCode(), message);
    }

    /**
     * 处理 {@code @Valid @RequestBody} 的参数校验失败。
     * <p>把 {@code BindingResult} 里的字段错误拼成一句可直接展示的文案。
     *
     * @param exception 校验异常，携带全部字段错误
     * @param request   当前请求，仅用于取 URI 做日志上下文
     * @return code 为 {@link ErrorCode#PARAM_INVALID} 的失败响应，不会为 {@code null}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception,
                                                              HttpServletRequest request) {
        String detail = buildFieldErrorDetail(exception.getBindingResult().getFieldErrors());

        log.warn("[PARAM] 参数校验不通过, uri={}, detail={}", request.getRequestURI(), detail);

        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), detail);
    }

    /**
     * 兜底处理一切未被上面两个 handler 接住的异常。
     * <p>按 3.4 打 {@code error} 并带完整堆栈——走到这里说明是没预料到的故障，必须能查。
     *
     * @param exception 未预期异常
     * @param request   当前请求，仅用于取 URI 做日志上下文
     * @return code 为 {@link ErrorCode#SYSTEM_ERROR} 的失败响应，不会为 {@code null}
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception, HttpServletRequest request) {
        log.error("[SYS] 未预期异常, uri={}", request.getRequestURI(), exception);

        // 🔴 绝不把 exception.getMessage() 透给前端:里面可能带 SQL 片段、内网地址、
        // 甚至上游返回的密钥内容。对外统一用枚举的默认文案,细节只留在日志里。
        return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }

    /**
     * 把字段校验错误拼成一句话，形如 {@code name: 不能为空; pageSize: 最大不能超过100}。
     *
     * @param fieldErrors 字段错误列表，不会为 {@code null}
     * @return 拼好的文案；列表为空时回退到 {@link ErrorCode#PARAM_INVALID} 的默认文案
     */
    private String buildFieldErrorDetail(List<FieldError> fieldErrors) {
        if (fieldErrors.isEmpty()) {
            return ErrorCode.PARAM_INVALID.getMessage();
        }
        // 🔴 只取字段名与校验文案,绝不取 getRejectedValue():
        // 被拒绝的值可能就是 apiKey 之类的敏感内容,拼进文案会同时进日志和响应体（3.4 第 2 条）
        return fieldErrors.stream()
                .map(fieldError -> fieldError.getField() + FIELD_MESSAGE_DELIMITER + fieldError.getDefaultMessage())
                .collect(Collectors.joining(FIELD_ERROR_DELIMITER));
    }
}
