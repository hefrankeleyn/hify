package com.hify.common.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.constant.ResultConstant;
import com.hify.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Result} 单元测试。
 * <p>重点验证 CLAUDE.md 8.2 约定的三段报文结构，以及 8.4 的空值约定。
 */
class ResultTest {

    /** JSON 序列化器，与 Spring MVC 默认使用的是同一个实现 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("ok() 产出 code=200、message=success、data=null")
    void okWithoutData() {
        Result<Void> result = Result.ok();

        assertThat(result.getCode()).isEqualTo(ResultConstant.SUCCESS_CODE);
        assertThat(result.getMessage()).isEqualTo(ResultConstant.SUCCESS_MESSAGE);
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("ok(data) 原样携带业务数据")
    void okWithData() {
        Result<String> result = Result.ok("hello");

        assertThat(result.getCode()).isEqualTo(ResultConstant.SUCCESS_CODE);
        assertThat(result.getData()).isEqualTo("hello");
    }

    @Test
    @DisplayName("fail(code, message) 使用传入的四位业务错误码，data 为 null")
    void failWithBusinessCode() {
        Result<Void> result = Result.fail(3001, "Agent 名称重复");

        assertThat(result.getCode()).isEqualTo(3001);
        assertThat(result.getMessage()).isEqualTo("Agent 名称重复");
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("fail(message) 回退到 SYSTEM_ERROR 的错误码 1000")
    void failWithDefaultCode() {
        Result<Void> result = Result.fail("系统繁忙");

        assertThat(result.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(result.getMessage()).isEqualTo("系统繁忙");
    }

    @Test
    @DisplayName("序列化后恰好只有 code / message / data 三个字段，且 data=null 不被吃掉")
    void serializeKeepsExactlyThreeFields() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(Result.ok()));

        List<String> fieldNames = json.properties().stream().map(Map.Entry::getKey).toList();
        assertThat(fieldNames).containsExactlyInAnyOrder("code", "message", "data");
        // 8.4:对象不存在时返回 null,不能被 Jackson 的 NON_NULL 策略吞掉
        assertThat(json.get("data").isNull()).isTrue();
    }
}
