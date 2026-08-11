package com.hify.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 对话编排线程池配置。
 * <p>装配 {@code llmChatExecutor}——SSE 对话编排跑在这个池上，一次对话从进池到结束
 * 独占一个线程 1～5 分钟（RAG 检索 + 流式调模型 + 工具调用循环），池的容量直接等于
 * 「能同时服务多少人对话」。
 *
 * <p>⚠️ <b>队列容量刻意开得很小（16），不是疏漏</b>：{@link ThreadPoolTaskExecutor} 的扩容规则是
 * 「核心线程满了才进队列，队列满了才扩容到 max」。如果队列开大，新请求会先在队列里堆积，
 * 线程数迟迟不会往 {@code maxPoolSize} 扩——SSE 场景下表现为用户已经建立连接，却迟迟等不到
 * 第一个字，比直接拒绝还伤体验。队列小，才能让池尽快从 core 扩到 max。
 * 详见 {@code docs/02-架构设计/01-过程/04_LLM调用的线程超时重试与容错.md} 1.2 节。
 *
 * <p>与 {@code kb-index-} 池（{@code hify-knowledge} 的 {@code KnowledgeExecutorConfig}）
 * 严格隔离，绝不共用——一次批量导入文档如果和对话共用一个池，会把对话线程全部吃光。
 */
@Slf4j
@Configuration
public class ChatExecutorConfig {

    /** 核心线程数:按峰值并发 SSE 对话数配置(CLAUDE.md 6.2) */
    private static final int CORE_POOL_SIZE = 32;

    /** 最大线程数 */
    private static final int MAX_POOL_SIZE = 64;

    /** 队列容量:刻意开小,避免新请求堆积在队列里而不触发扩容,见类注释 */
    private static final int QUEUE_CAPACITY = 16;

    /** 线程名前缀,必须有意义(CLAUDE.md 6.2 红线),日志/线程 dump 里一眼能认出是哪个池 */
    private static final String THREAD_NAME_PREFIX = "llm-chat-";

    /**
     * 装配 SSE 对话编排线程池。
     * <p>拒绝策略用 {@link ThreadPoolExecutor.AbortPolicy}，不用 {@code CallerRunsPolicy}——
     * 后者会让 Tomcat 工作线程去跑分钟级的 LLM 调用，等于把 Tomcat 自己的线程池也一起拖垮
     * （CLAUDE.md 6.2）。池满时调用方应捕获 {@link java.util.concurrent.RejectedExecutionException}，
     * 转换成「当前对话数已达上限，请稍后重试」的业务错误。
     *
     * @return 已初始化的线程池，不会为 {@code null}
     */
    @Bean("llmChatExecutor")
    @Qualifier("llmChatExecutor")
    public ThreadPoolTaskExecutor llmChatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();

        log.info("[CONFIG] llmChatExecutor 已装配, core={}, max={}, queue={}, prefix={}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY, THREAD_NAME_PREFIX);
        return executor;
    }
}
