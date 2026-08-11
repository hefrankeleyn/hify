package com.hify.provider.client;

import com.hify.common.exception.BizException;
import com.hify.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 按 provider 分实例的熔断器（CLAUDE.md 6.3）。
 * <p>🔴 <b>编程式配置，不用 {@code resilience4j-spring-boot3} starter</b>——熔断器要按
 * {@code providerName} 动态分实例，而 provider 是运行时的数据库数据（用户在管理面创建），
 * 不是启动时就能在 {@code application.yml} 里穷举的静态配置，注解/YAML 驱动的声明式配置做不到
 * "为每一个动态出现的 provider 分别开一个熔断器"。因此本模块只引了
 * {@code resilience4j-circuitbreaker} 核心包，没有引 starter（见 {@code hify-provider/pom.xml}）。
 *
 * <p>🔴 <b>用时间窗（60s）不用计数窗</b>：CLAUDE.md 6.3 明确指出，3–5 QPS 下计数窗要几分钟
 * 才能填满，熔断永远来不及触发。
 *
 * <p>⚠️ <b>失败判定的适配</b>：CLAUDE.md 6.3 原本的示例是
 * {@code recordExceptions(IOException.class, TimeoutException.class)} +
 * {@code ignoreExceptions(BizException.class)}——前提是熔断器能看到原始的 {@code IOException}。
 * 但 {@link LlmHttpClient} 已经把所有失败都转成了 {@link BizException}（{@code CLAUDE.md} 3.7
 * 「只抛 BizException」），熔断器实际上只会看到 {@code BizException} 一种类型。本类改用
 * {@link CircuitBreakerConfig.Builder#recordException(java.util.function.Predicate)}，
 * 按 {@link ErrorCode} 精确判断：{@link ErrorCode#PROVIDER_AUTH_FAILED} 是配置问题不计入失败率
 * （避免 401 把熔断器无辜打开），其余（超时、限流）计入——语义和原设计完全一致，
 * 只是判断方式从"异常类型"换成了"错误码"，因为熔断器现在只能看到一种异常类型。
 */
@Slf4j
@Component
public class CircuitBreakerService {

    /** 滑动时间窗大小 */
    private static final int SLIDING_WINDOW_SIZE_SECONDS = 60;

    /** 窗口内最少调用次数,不足这个数不计算失败率 */
    private static final int MINIMUM_NUMBER_OF_CALLS = 5;

    /** 失败率阈值(%) */
    private static final float FAILURE_RATE_THRESHOLD = 50f;

    /** 熔断打开后多久进入半开状态试探 */
    private static final Duration WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(30);

    /** 半开状态允许放行的探测请求数 */
    private static final int PERMITTED_CALLS_IN_HALF_OPEN_STATE = 2;

    private final CircuitBreakerRegistry registry;

    /** 按 providerName 缓存的熔断器实例,get-or-create */
    private final ConcurrentMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public CircuitBreakerService() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(SLIDING_WINDOW_SIZE_SECONDS)
                .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(WAIT_DURATION_IN_OPEN_STATE)
                .permittedNumberOfCallsInHalfOpenState(PERMITTED_CALLS_IN_HALF_OPEN_STATE)
                .recordException(this::isCountedAsFailure)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);

        log.info("[CONFIG] CircuitBreakerService 已装配, slidingWindow={}s, minCalls={}, "
                        + "failureRateThreshold={}%, waitDurationInOpenState={}s, halfOpenCalls={}",
                SLIDING_WINDOW_SIZE_SECONDS, MINIMUM_NUMBER_OF_CALLS, FAILURE_RATE_THRESHOLD,
                WAIT_DURATION_IN_OPEN_STATE.getSeconds(), PERMITTED_CALLS_IN_HALF_OPEN_STATE);
    }

    /**
     * 取指定 provider 的熔断器，不存在则创建。
     *
     * @param providerName provider 的唯一标识（建议用 provider 表的 id 转字符串，不要用展示名——
     *                      展示名允许改，id 不变）
     * @return 该 provider 专属的熔断器实例，不会为 {@code null}
     */
    public CircuitBreaker getOrCreate(String providerName) {
        return circuitBreakers.computeIfAbsent(providerName, this::createCircuitBreaker);
    }

    /**
     * 用指定 provider 的熔断器包一层执行调用。
     * <p>熔断打开时 Resilience4j 会抛 {@link CallNotPermittedException}——这不是
     * {@link BizException}，必须在这里兜住转换，不能让它原样抛出去（CLAUDE.md 3.7 第 1 条）。
     *
     * @param providerName provider 的唯一标识
     * @param supplier     实际要执行的调用（通常是包着 {@link LlmHttpClient#post}/{@code stream} 的闭包）
     * @param <T>          返回值类型
     * @return {@code supplier} 的返回值
     * @throws BizException 熔断打开时抛 {@link ErrorCode#PROVIDER_TIMEOUT}；{@code supplier}
     *                       内部抛出的 {@code BizException} 原样透传
     */
    public <T> T execute(String providerName, Supplier<T> supplier) {
        CircuitBreaker circuitBreaker = getOrCreate(providerName);
        try {
            return circuitBreaker.executeSupplier(supplier);
        } catch (CallNotPermittedException e) {
            log.warn("[CIRCUIT_BREAKER] provider={} 熔断中,拒绝调用", providerName);
            throw new BizException(ErrorCode.PROVIDER_TIMEOUT, "模型服务暂时不可用，请稍后重试", e);
        }
    }

    private CircuitBreaker createCircuitBreaker(String providerName) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(providerName);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("[CIRCUIT_BREAKER] provider={}, {} -> {}",
                        providerName, event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
        log.info("[CONFIG] 新建熔断器, provider={}", providerName);
        return circuitBreaker;
    }

    /**
     * 判断一次失败是否应该计入熔断器的失败率。
     *
     * @param throwable 调用抛出的异常
     * @return {@code true} 表示计入失败率
     */
    private boolean isCountedAsFailure(Throwable throwable) {
        if (throwable instanceof BizException bizException) {
            // 认证失败是配置问题,不是"provider 不可用",计入会让熔断器被无辜打开(CLAUDE.md 6.3)
            return bizException.getErrorCode() != ErrorCode.PROVIDER_AUTH_FAILED;
        }
        return true;
    }
}
