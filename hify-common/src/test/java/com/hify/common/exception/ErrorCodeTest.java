package com.hify.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorCode} 单元测试。
 * <p>错误码集中在一个枚举里，最大的风险是<b>撞号</b>和<b>越段</b>——
 * 两者都不会在编译期报错，只能靠测试守住。
 */
class ErrorCodeTest {

    /** 错误码号段下界，见 CLAUDE.md 8.5 */
    private static final int MIN_CODE = 1000;

    /** 错误码号段上界（含），7999 是 Knowledge 段的末位 */
    private static final int MAX_CODE = 7999;

    @Test
    @DisplayName("所有错误码互不重复")
    void codesAreUnique() {
        List<Integer> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::getCode).toList();

        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("所有错误码都是四位数且落在 1000-7999 之内")
    void codesAreWithinDefinedSegments() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.getCode())
                    .as("错误码 %s 越界", errorCode.name())
                    .isBetween(MIN_CODE, MAX_CODE);
        }
    }

    @Test
    @DisplayName("所有错误码都有非空文案")
    void messagesAreNotBlank() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.getMessage())
                    .as("错误码 %s 缺少文案", errorCode.name())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("通用段的错误码全部落在 1000-1999")
    void commonSegmentIsRespected() {
        // 当前枚举只填了通用段,模块段落地后本用例要按段改写
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.getCode())
                    .as("错误码 %s 不在通用段", errorCode.name())
                    .isBetween(1000, 1999);
        }
    }
}
