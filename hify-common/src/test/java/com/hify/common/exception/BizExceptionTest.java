package com.hify.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BizException} 单元测试。
 * <p>重点验证「自定义 message 覆盖枚举默认文案」这条语义，
 * 以及包装下层异常时 cause 不丢。
 */
class BizExceptionTest {

    @Test
    @DisplayName("只传错误码时,getMessage() 用枚举的默认文案")
    void useDefaultMessageFromErrorCode() {
        BizException exception = new BizException(ErrorCode.NOT_FOUND);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(exception.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        assertThat(exception.getMessage()).isEqualTo(ErrorCode.NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("传自定义文案时覆盖默认文案,但错误码不变")
    void customMessageOverridesDefault() {
        BizException exception = new BizException(ErrorCode.DATA_CONFLICT, "Agent 名称已存在: 客服助手");

        assertThat(exception.getMessage()).isEqualTo("Agent 名称已存在: 客服助手");
        // 覆盖的只是文案,码仍然是枚举里的那个
        assertThat(exception.getCode()).isEqualTo(ErrorCode.DATA_CONFLICT.getCode());
        assertThat(exception.getErrorCode().getMessage()).isEqualTo("数据已存在或当前状态不允许该操作");
    }

    @Test
    @DisplayName("包装下层异常时保留 cause,默认文案仍来自枚举")
    void keepsCause() {
        IOException cause = new IOException("connection reset");
        BizException exception = new BizException(ErrorCode.SYSTEM_ERROR, cause);

        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getMessage()).isEqualTo(ErrorCode.SYSTEM_ERROR.getMessage());
    }

    @Test
    @DisplayName("自定义文案 + cause 两者同时生效")
    void customMessageWithCause() {
        IOException cause = new IOException("connection reset");
        BizException exception = new BizException(ErrorCode.SYSTEM_ERROR, "调用 OpenAI 失败", cause);

        assertThat(exception.getMessage()).isEqualTo("调用 OpenAI 失败");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("是非受检异常,不强制调用方 try-catch")
    void isUnchecked() {
        assertThat(RuntimeException.class).isAssignableFrom(BizException.class);
    }
}
