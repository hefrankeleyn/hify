package com.hify.provider.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试退避计算与预算控制（CLAUDE.md 6.4）。
 * <p>🔴 铁律：首字节到达之后一律不重试。本类只负责"要不要再试一次、等多久"这个纯计算问题，
 * "现在是不是已经过了首字节这条线"由调用方（{@link LlmHttpClient}）判断——{@code post()}
 * 全程可重试（没有部分输出的概念），{@code stream()} 只在拿到响应头之前（{@link
 * LlmHttpClient#stream}内部的连接阶段）允许重试，一旦开始读取行数据就不再经过本类。
 *
 * <p>⚠️ 每次外部调用（每次 {@code post()}/每次建流）都要 {@code new} 一个新实例——
 * 「最多 2 次、总预算 60s」是<b>单次调用</b>的重试预算，不是跨请求共享的全局计数器。
 */
class RetryExecutor {

    /** 默认最多重试次数(不含首次尝试) */
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    /** 首字节超时的重试次数单独收紧为 1 次(CLAUDE.md 6.4:"首字节超时(仅 1 次)") */
    static final int FIRST_BYTE_TIMEOUT_MAX_ATTEMPTS = 1;

    private static final long BASE_BACKOFF_MILLIS = 500L;
    private static final long MAX_BACKOFF_MILLIS = 1500L;
    private static final double JITTER_RATIO = 0.3;

    private static final long TOTAL_BUDGET_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final long RATE_LIMIT_FALLBACK_FIRST_MILLIS = Duration.ofSeconds(2).toMillis();
    private static final long RATE_LIMIT_FALLBACK_SECOND_MILLIS = Duration.ofSeconds(4).toMillis();

    private final int maxAttempts;
    private final long deadline;
    private int attempt;

    RetryExecutor() {
        this(DEFAULT_MAX_ATTEMPTS);
    }

    RetryExecutor(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        this.deadline = System.currentTimeMillis() + TOTAL_BUDGET_MILLIS;
        this.attempt = 0;
    }

    /**
     * 是否还可以再试一次。
     *
     * @return 未超过最大次数且未超过总预算时为 {@code true}
     */
    boolean canRetry() {
        return attempt < maxAttempts && System.currentTimeMillis() < deadline;
    }

    /**
     * 记一次失败重试，返回这次失败后该等多久再发下一次请求。
     * <p>用于连接失败、超时、5xx 这类"基础设施性"失败——退避 500ms → 1500ms，带 ±30% 抖动，
     * 避免多个并发请求在同一时刻集中重试。
     *
     * @return 退避时长(ms)
     */
    long nextBackoffMillis() {
        attempt++;
        long base = attempt <= 1 ? BASE_BACKOFF_MILLIS : MAX_BACKOFF_MILLIS;
        double jitterFactor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * JITTER_RATIO;
        return Math.round(base * jitterFactor);
    }

    /**
     * 记一次限流重试，返回退避时长。
     * <p>优先用服务端 {@code Retry-After} 响应头给的秒数（CLAUDE.md 6.4："429 优先读
     * Retry-After"）；服务端没给时按 2s/4s 兜底。
     *
     * @param retryAfterSeconds {@code Retry-After} 响应头解析出的秒数，没有该响应头时传 {@code null}
     * @return 退避时长(ms)
     */
    long nextRateLimitBackoffMillis(Long retryAfterSeconds) {
        attempt++;
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return Duration.ofSeconds(retryAfterSeconds).toMillis();
        }
        return attempt <= 1 ? RATE_LIMIT_FALLBACK_FIRST_MILLIS : RATE_LIMIT_FALLBACK_SECOND_MILLIS;
    }
}
