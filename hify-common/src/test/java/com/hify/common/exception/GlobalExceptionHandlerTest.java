package com.hify.common.exception;

import com.hify.common.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} 单元测试。
 */
class GlobalExceptionHandlerTest {

    /** 被测对象 */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 模拟请求，只为让 handler 取得到 URI */
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents");

    @Test
    @DisplayName("BizException 原样透出错误码与自定义文案")
    void bizExceptionKeepsCodeAndCustomMessage() {
        BizException exception = new BizException(ErrorCode.DATA_CONFLICT, "Agent 名称已存在: 客服助手");

        Result<Void> result = handler.handleBizException(exception, request);

        assertThat(result.getCode()).isEqualTo(ErrorCode.DATA_CONFLICT.getCode());
        assertThat(result.getMessage()).isEqualTo("Agent 名称已存在: 客服助手");
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("BizException 的文案为 null 时回退到枚举默认文案,响应体不出现 null")
    void bizExceptionFallsBackWhenMessageIsNull() {
        BizException exception = new BizException(ErrorCode.NOT_FOUND, (String) null);

        Result<Void> result = handler.handleBizException(exception, request);

        assertThat(result.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        assertThat(result.getMessage()).isEqualTo(ErrorCode.NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("参数校验失败时返回 PARAM_INVALID,并把多个字段错误拼成一句")
    void validationErrorsAreJoined() throws Exception {
        MethodArgumentNotValidException exception = buildValidationException(
                new FieldError("request", "name", "不能为空"),
                new FieldError("request", "pageSize", "最大不能超过100"));

        Result<Void> result = handler.handleMethodArgumentNotValidException(exception, request);

        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(result.getMessage()).isEqualTo("name: 不能为空; pageSize: 最大不能超过100");
    }

    @Test
    @DisplayName("没有具体字段错误时回退到 PARAM_INVALID 的默认文案")
    void validationWithoutFieldErrorsFallsBack() throws Exception {
        MethodArgumentNotValidException exception = buildValidationException();

        Result<Void> result = handler.handleMethodArgumentNotValidException(exception, request);

        assertThat(result.getMessage()).isEqualTo(ErrorCode.PARAM_INVALID.getMessage());
    }

    @Test
    @DisplayName("兜底 handler 返回 SYSTEM_ERROR,且绝不把原始异常信息透给前端")
    void genericExceptionDoesNotLeakDetails() {
        Exception exception = new IllegalStateException(
                "Table 'hify.agent' doesn't exist; jdbc:mysql://10.0.0.7:3306");

        Result<Void> result = handler.handleException(exception, request);

        assertThat(result.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(result.getMessage()).isEqualTo(ErrorCode.SYSTEM_ERROR.getMessage());
        // 内网地址、表名这些细节只能留在日志里
        assertThat(result.getMessage()).doesNotContain("jdbc:mysql", "hify.agent");
    }

    /**
     * 造一个携带指定字段错误的 {@link MethodArgumentNotValidException}。
     *
     * @param fieldErrors 字段错误，可以一个都不传
     * @return 构造好的异常，不会为 null
     * @throws NoSuchMethodException 反射不到占位方法时抛出，正常不会发生
     */
    private MethodArgumentNotValidException buildValidationException(FieldError... fieldErrors)
            throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("placeholderEndpoint", String.class), 0);
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        for (FieldError fieldError : fieldErrors) {
            bindingResult.addError(fieldError);
        }
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }

    /**
     * 仅用于构造 {@link MethodParameter} 的占位方法，永远不会被调用。
     *
     * @param value 占位参数
     */
    @SuppressWarnings("unused")
    private void placeholderEndpoint(String value) {
        // 空实现:MethodParameter 只需要一个真实存在的方法签名
    }
}
