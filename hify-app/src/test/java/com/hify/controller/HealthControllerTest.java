package com.hify.controller;

import com.hify.common.exception.GlobalExceptionHandler;
import com.hify.common.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HealthController} 单元测试。
 * <p>用 standalone 模式，不加载 Spring 上下文——健康检查不依赖任何 Bean，
 * 起完整上下文既慢又会把 MySQL / Redis 拖成测试的前置条件。
 */
class HealthControllerTest {

    /** 被测对象 */
    private final HealthController controller = new HealthController();

    /**
     * 只挂被测 Controller 的最小 MVC 环境。
     * <p>⚠️ 必须显式 {@code setControllerAdvice}：{@code standaloneSetup} <b>默认不注册</b>
     * {@code @RestControllerAdvice}，不挂的话异常路径的断言会与真实应用不符——
     * 测试里看到 405，线上却是被兜底 handler 吃成的 200。
     */
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HealthController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("直接调用返回成功响应与固定文案")
    void returnsRunningMessage() {
        Result<String> result = controller.health();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getMessage()).isEqualTo("success");
        assertThat(result.getData()).isEqualTo("Hify is running");
    }

    @Test
    @DisplayName("GET /api/v1/health 映射正确,响应体符合 8.2 的三段结构")
    void endpointIsMappedAtExpectedPath() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value("Hify is running"));
    }

    /**
     * 钉住 POST 的<b>当前真实行为</b>，而不是期望行为。
     * <p>⚠️ 这里断言的 200 + code 1000 是一个<b>已知缺口</b>：
     * {@code HttpRequestMethodNotSupportedException} 没有专门的 handler，
     * 落进 {@link GlobalExceptionHandler#handleException} 被报成「系统内部错误」，
     * 还会打一条 ERROR 全栈——而它其实是客户端用错了方法。
     * <p>{@code ErrorCode.METHOD_NOT_ALLOWED}（1006）已备好。补上 handler 后本用例会失败，
     * 那正是提醒：把断言改成 405 / code 1006。
     */
    @Test
    @DisplayName("POST 目前被兜底 handler 吃成 200 + 1000（已知缺口,补 handler 后本用例应失败）")
    void postIsSwallowedByFallbackHandler() throws Exception {
        mockMvc.perform(post("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("系统内部错误"));
    }
}
