package com.hify.knowledge.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 知识库处理线程池配置。
 * <p>装配 {@code kbIndexExecutor}——文档解析、分块、向量化跑在这个池上，是 CPU 密集型批处理
 * 任务，与 {@code llm-chat-}（{@code hify-chat} 的 {@code ChatExecutorConfig}）严格隔离、
 * 绝不共用：一次批量导入文档不能把对话线程吃光，反之亦然。
 *
 * <p>取向与对话池相反：线程数按 CPU 核数配（小），队列可以开大（任务能等），
 * 因为文档处理不像 SSE 对话那样需要立刻响应用户。
 * 详见 {@code docs/02-架构设计/01-过程/04_LLM调用的线程超时重试与容错.md} 1.2 节。
 */
@Slf4j
@Configuration
public class KnowledgeExecutorConfig {

    /** 核心线程数:CPU 密集型任务按核数配,不需要很大(CLAUDE.md 6.2) */
    private static final int CORE_POOL_SIZE = 2;

    /** 最大线程数 */
    private static final int MAX_POOL_SIZE = 4;

    /** 队列容量:文档处理可以等,队列开大不影响体验 */
    private static final int QUEUE_CAPACITY = 64;

    /** 线程名前缀,必须有意义(CLAUDE.md 6.2 红线) */
    private static final String THREAD_NAME_PREFIX = "kb-index-";

    /**
     * 装配知识库文档处理线程池。
     * <p>拒绝策略用 {@link ThreadPoolExecutor.AbortPolicy}，不用 {@code CallerRunsPolicy}，
     * 理由同 {@code llmChatExecutor}（CLAUDE.md 6.2）。池满时调用方应捕获
     * {@link java.util.concurrent.RejectedExecutionException}，转换成业务错误提示稍后重试。
     *
     * @return 已初始化的线程池，不会为 {@code null}
     */
    @Bean("kbIndexExecutor")
    @Qualifier("kbIndexExecutor")
    public ThreadPoolTaskExecutor kbIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();

        log.info("[CONFIG] kbIndexExecutor 已装配, core={}, max={}, queue={}, prefix={}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY, THREAD_NAME_PREFIX);
        return executor;
    }
}
