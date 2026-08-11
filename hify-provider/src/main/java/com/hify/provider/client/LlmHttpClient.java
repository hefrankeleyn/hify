package com.hify.provider.client;

import com.hify.common.exception.BizException;
import com.hify.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM HTTP 客户端：普通请求 + 流式 SSE 请求。
 * <p>只用 JDK 自带的 {@code java.net.http.HttpClient}，不引三方 HTTP 库，也不用
 * {@code WebClient}（会把 WebFlux 拖进来，违反 {@code CLAUDE.md} 七节红线）——
 * 一个客户端同时覆盖普通请求和流式 SSE，不需要为两种请求形态各引一个库。
 *
 * <p>🔴 <b>四道超时闸</b>（{@code CLAUDE.md} 6.1）：连接 3s、首字节 30s、流间隔 30s、整体 300s，
 * 默认值按云 API 配置；Ollama 等超时更长的 provider 由调用方通过带 {@link Duration} 参数的
 * 重载方法传入各自的值，本类不感知具体 provider 是谁。
 *
 * <p>⚠️ <b>流间隔超时的实现方式</b>：JDK {@code HttpClient} 的 {@code timeout()} 只是整体超时，
 * 没有暴露"距上一次读到数据多久"这个信号。本类的做法是把实际的阻塞读操作丢到一个独立的
 * {@code llm-stream-reader-} 线程池上跑，读到的每一行经 {@link java.util.concurrent.BlockingQueue}
 * 传回调用线程；调用线程在 {@code queue.poll(streamIntervalTimeout, ...)} 上等，超时就
 * {@code readerTask.cancel(true)} 中断读线程并判定为流间隔超时。
 * <p>⚠️ 代价：每一路流式调用会同时占用 {@code llm-chat-} 线程池的一个线程（跑业务编排，
 * 阻塞在本类的 {@code queue.poll} 上）和 {@code llm-stream-reader-} 线程池的一个线程
 * （跑实际的阻塞 socket 读）——线程数量翻倍，因此本类内部的读线程池容量对齐
 * {@code llm-chat-} 的 max（64），避免它先于 {@code llm-chat-} 打满。
 *
 * <p>异常统一转 {@link BizException}，不新建独立的异常类型（{@code CLAUDE.md} 3.7 第 1 条：
 * 只抛 {@code BizException}）。
 */
@Slf4j
@Component
public class LlmHttpClient {

    /** 连接超时(CLAUDE.md 6.1) */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 非流式同步调用的默认整体超时(CLAUDE.md 6.1"其它超时") */
    private static final Duration DEFAULT_SYNC_TIMEOUT = Duration.ofSeconds(60);

    /** 流式请求默认首字节超时 */
    private static final Duration DEFAULT_FIRST_BYTE_TIMEOUT = Duration.ofSeconds(30);

    /** 流式请求默认流间隔超时 */
    private static final Duration DEFAULT_STREAM_INTERVAL_TIMEOUT = Duration.ofSeconds(30);

    /** 流式请求默认整体超时 */
    private static final Duration DEFAULT_STREAM_OVERALL_TIMEOUT = Duration.ofSeconds(300);

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_STATUS_OK_MIN = 200;
    private static final int HTTP_STATUS_OK_MAX = 299;

    /** 可重试的 5xx 状态码集合(CLAUDE.md 6.4错误分类表);529 是部分 provider 用来表示"服务过载"的非标准码 */
    private static final Set<Integer> RETRYABLE_SERVER_ERRORS = Set.of(500, 502, 503, 504, 529);

    /** Retry-After 响应头名 */
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    /** 流读线程池容量对齐 llm-chat- 的 max(64),见类注释 */
    private static final int STREAM_READER_CORE_POOL_SIZE = 32;
    private static final int STREAM_READER_MAX_POOL_SIZE = 64;
    private static final String STREAM_READER_THREAD_PREFIX = "llm-stream-reader-";

    private final HttpClient httpClient;

    /** 流式读取专用线程池,只做阻塞 IO,不参与业务编排(业务编排仍在调用方所在的 llm-chat- 线程上) */
    private final ThreadPoolExecutor streamReaderExecutor;

    public LlmHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.streamReaderExecutor = new ThreadPoolExecutor(
                STREAM_READER_CORE_POOL_SIZE,
                STREAM_READER_MAX_POOL_SIZE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                buildThreadFactory(STREAM_READER_THREAD_PREFIX),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 发起非流式 POST 请求，默认整体超时 60s。
     *
     * @param url     目标地址
     * @param headers 请求头，可为空 Map
     * @param body    请求体，JSON 文本
     * @return 响应体原文
     * @throws BizException {@link ErrorCode#PROVIDER_TIMEOUT}/{@link ErrorCode#PROVIDER_AUTH_FAILED}/
     *                       {@link ErrorCode#PROVIDER_RATE_LIMITED} 之一
     */
    public String post(String url, Map<String, String> headers, String body) {
        return post(url, headers, body, DEFAULT_SYNC_TIMEOUT);
    }

    /**
     * 发起非流式 POST 请求。
     *
     * @param url            目标地址
     * @param headers        请求头，可为空 Map
     * @param body           请求体，JSON 文本
     * @param overallTimeout 整体超时
     * @return 响应体原文
     * @throws BizException 同 {@link #post(String, Map, String)}
     */
    public String post(String url, Map<String, String> headers, String body, Duration overallTimeout) {
        RetryExecutor retry = new RetryExecutor();
        while (true) {
            long start = System.currentTimeMillis();
            HttpRequest request = buildRequestBuilder(url, headers, overallTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = System.currentTimeMillis() - start;
                int statusCode = response.statusCode();
                log.info("[LLM] POST 完成, url={}, status={}, elapsedMs={}", url, statusCode, elapsedMs);

                if (isSuccess(statusCode)) {
                    return response.body();
                }
                if (isAuthFailure(statusCode)) {
                    // 认证失败不可重试(CLAUDE.md 6.4)
                    log.warn("[LLM] 认证失败, url={}, status={}", url, statusCode);
                    throw new BizException(ErrorCode.PROVIDER_AUTH_FAILED);
                }
                if (statusCode == HTTP_TOO_MANY_REQUESTS) {
                    if (!retry.canRetry()) {
                        log.warn("[LLM] 触发限流且重试次数已用尽, url={}", url);
                        throw new BizException(ErrorCode.PROVIDER_RATE_LIMITED);
                    }
                    long backoffMillis = retry.nextRateLimitBackoffMillis(parseRetryAfterSeconds(response.headers()));
                    log.warn("[LLM] 触发限流,{}ms 后重试, url={}", backoffMillis, url);
                    sleepQuietly(backoffMillis);
                    continue;
                }
                if (RETRYABLE_SERVER_ERRORS.contains(statusCode) && retry.canRetry()) {
                    long backoffMillis = retry.nextBackoffMillis();
                    log.warn("[LLM] 上游返回 {},{}ms 后重试, url={}", statusCode, backoffMillis, url);
                    sleepQuietly(backoffMillis);
                    continue;
                }
                // 400/404/413 等客户端错误,或重试预算已耗尽的 5xx:不再重试,直接报错
                log.warn("[LLM] 上游返回非成功状态码, url={}, status={}", url, statusCode);
                throw new BizException(ErrorCode.PROVIDER_TIMEOUT, "上游返回状态码 " + statusCode);
            } catch (IOException e) {
                // HttpTimeoutException extends IOException,同一分支处理即可
                long elapsedMs = System.currentTimeMillis() - start;
                if (!retry.canRetry()) {
                    log.error("[LLM] POST 失败且重试次数已用尽, url={}, elapsedMs={}", url, elapsedMs, e);
                    throw new BizException(ErrorCode.PROVIDER_TIMEOUT, e);
                }
                long backoffMillis = retry.nextBackoffMillis();
                log.warn("[LLM] POST 失败,{}ms 后重试, url={}, elapsedMs={}, cause={}",
                        backoffMillis, url, elapsedMs, e.toString());
                sleepQuietly(backoffMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.SYSTEM_ERROR, e);
            }
        }
    }

    /**
     * 发起流式 SSE 请求，默认四道闸取云 API 的值（首字节 30s / 流间隔 30s / 整体 300s）。
     *
     * @param url      目标地址
     * @param headers  请求头，可为空 Map
     * @param body     请求体，JSON 文本
     * @param callback 逐行回调，每收到响应体一行调用一次
     * @throws BizException 同 {@link #post(String, Map, String)}
     */
    public void stream(String url, Map<String, String> headers, String body, StreamCallback callback) {
        stream(url, headers, body, callback,
                DEFAULT_FIRST_BYTE_TIMEOUT, DEFAULT_STREAM_INTERVAL_TIMEOUT, DEFAULT_STREAM_OVERALL_TIMEOUT);
    }

    /**
     * 发起流式 SSE 请求，四道闸的后三道（首字节/流间隔/整体）由调用方指定
     * ——不同 provider（如 Ollama）的超时值不同，本类不感知具体是哪个 provider。
     *
     * @param url                   目标地址
     * @param headers               请求头，可为空 Map
     * @param body                  请求体，JSON 文本
     * @param callback              逐行回调
     * @param firstByteTimeout      首字节超时
     * @param streamIntervalTimeout 流间隔超时——距上一行数据多久没收到新数据就判定为超时
     * @param overallTimeout        整体超时——从发起请求到流结束的总耗时上限
     * @throws BizException {@link ErrorCode#PROVIDER_TIMEOUT} 等，首字节到达之后的失败
     *                       ({@code streamIntervalTimeout}/{@code overallTimeout} 触发的)
     *                       调用方不应重试（CLAUDE.md 6.4 铁律）
     */
    public void stream(String url, Map<String, String> headers, String body, StreamCallback callback,
                        Duration firstByteTimeout, Duration streamIntervalTimeout, Duration overallTimeout) {
        long start = System.currentTimeMillis();
        long deadline = start + overallTimeout.toMillis();

        HttpRequest request = buildRequestBuilder(url, headers, overallTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response = openStream(url, request, firstByteTimeout);
        checkStatus(url, response.statusCode());
        log.info("[LLM] 流式请求开始, url={}, status={}", url, response.statusCode());

        Object streamEnd = new Object();
        LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        Future<?> readerTask = streamReaderExecutor.submit(
                () -> readLines(response.body(), queue, streamEnd));

        try {
            consumeQueue(url, queue, streamEnd, callback, readerTask, deadline, streamIntervalTimeout);
        } finally {
            long elapsedMs = System.currentTimeMillis() - start;
            log.info("[LLM] 流式请求结束, url={}, elapsedMs={}", url, elapsedMs);
        }
    }

    /**
     * 发起异步请求并等首字节（响应头）到达，超时或连接失败则按重试预算重试。
     * <p>⚠️ 简化：连接失败（CLAUDE.md 6.4 允许最多 2 次）和首字节超时（该规则明确"仅 1 次"）
     * 在这里共用同一个 {@link RetryExecutor}（最多 2 次），没有分别单独计数——
     * 差别只是首字节超时场景下最多少重试 1 次，不影响"首字节到达之后一律不重试"这条主铁律
     * （本方法一旦成功拿到响应头就立刻返回，之后不再经过任何重试逻辑）。
     */
    private HttpResponse<InputStream> openStream(String url, HttpRequest request, Duration firstByteTimeout) {
        RetryExecutor retry = new RetryExecutor();
        while (true) {
            CompletableFuture<HttpResponse<InputStream>> future =
                    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            try {
                return future.get(firstByteTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                if (!retry.canRetry()) {
                    log.error("[LLM] 流式请求首字节超时且重试次数已用尽, url={}", url);
                    throw new BizException(ErrorCode.PROVIDER_TIMEOUT, "首字节超时");
                }
                long backoffMillis = retry.nextBackoffMillis();
                log.warn("[LLM] 流式请求首字节超时,{}ms 后重试, url={}", backoffMillis, url);
                sleepQuietly(backoffMillis);
            } catch (ExecutionException e) {
                if (!retry.canRetry()) {
                    log.error("[LLM] 流式请求发起失败且重试次数已用尽, url={}", url, e.getCause());
                    throw new BizException(ErrorCode.PROVIDER_TIMEOUT, e.getCause());
                }
                long backoffMillis = retry.nextBackoffMillis();
                log.warn("[LLM] 流式请求发起失败,{}ms 后重试, url={}, cause={}", backoffMillis, url, e.getCause());
                sleepQuietly(backoffMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.SYSTEM_ERROR, e);
            }
        }
    }

    /**
     * 在独立的读线程上跑的任务体：逐行读，读到的每一行/异常/结束标记都塞进队列。
     * <p>本方法运行在 {@code llm-stream-reader-} 线程上，不是调用方线程。
     */
    private void readLines(InputStream body, LinkedBlockingQueue<Object> queue, Object streamEnd) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                queue.put(line);
            }
            queue.put(streamEnd);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 包含 IOException:上游主动断开、或 cancel(true) 中断阻塞读时抛出的异常,都会走到这里
            queue.offer(e);
        }
    }

    /**
     * 在调用方线程上跑：从队列取数据回调给业务方，同时负责流间隔超时和整体超时的判定。
     */
    private void consumeQueue(String url, LinkedBlockingQueue<Object> queue, Object streamEnd,
                               StreamCallback callback, Future<?> readerTask,
                               long deadline, Duration streamIntervalTimeout) {
        try {
            while (true) {
                long remainingMs = deadline - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    readerTask.cancel(true);
                    log.error("[LLM] 流式请求整体超时, url={}", url);
                    throw new BizException(ErrorCode.PROVIDER_TIMEOUT, "整体超时");
                }

                long waitMs = Math.min(remainingMs, streamIntervalTimeout.toMillis());
                Object item = queue.poll(waitMs, TimeUnit.MILLISECONDS);
                if (item == null) {
                    readerTask.cancel(true);
                    log.error("[LLM] 流间隔超时, url={}", url);
                    throw new BizException(ErrorCode.PROVIDER_TIMEOUT, "流间隔超时");
                }
                if (item == streamEnd) {
                    return;
                }
                if (item instanceof Exception) {
                    log.error("[LLM] 流式读取失败, url={}", url, (Exception) item);
                    throw new BizException(ErrorCode.PROVIDER_TIMEOUT, (Exception) item);
                }
                callback.onLine((String) item);
            }
        } catch (InterruptedException e) {
            readerTask.cancel(true);
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, e);
        }
    }

    private HttpRequest.Builder buildRequestBuilder(String url, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return builder;
    }

    /**
     * 按 CLAUDE.md 6.4 的分类把非 2xx 状态码映射成 BizException。
     * <p>只显式区分 401/403/429 这三类——其余非 2xx 状态码（400/404/5xx 等）的精细分类
     * 留给调用方按具体 provider 的响应体解析后自行处理，本类不越权替调用方判断。
     *
     * @param url        目标地址,仅用于日志
     * @param statusCode HTTP 状态码
     */
    private void checkStatus(String url, int statusCode) {
        if (statusCode >= HTTP_STATUS_OK_MIN && statusCode <= HTTP_STATUS_OK_MAX) {
            return;
        }
        if (statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN) {
            log.warn("[LLM] 认证失败, url={}, status={}", url, statusCode);
            throw new BizException(ErrorCode.PROVIDER_AUTH_FAILED);
        }
        if (statusCode == HTTP_TOO_MANY_REQUESTS) {
            log.warn("[LLM] 触发限流, url={}, status={}", url, statusCode);
            throw new BizException(ErrorCode.PROVIDER_RATE_LIMITED);
        }
        log.warn("[LLM] 上游返回非成功状态码, url={}, status={}", url, statusCode);
        throw new BizException(ErrorCode.PROVIDER_TIMEOUT, "上游返回状态码 " + statusCode);
    }

    private boolean isSuccess(int statusCode) {
        return statusCode >= HTTP_STATUS_OK_MIN && statusCode <= HTTP_STATUS_OK_MAX;
    }

    private boolean isAuthFailure(int statusCode) {
        return statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN;
    }

    /**
     * 解析响应头里的 {@code Retry-After}（CLAUDE.md 6.4："429 优先读 Retry-After"）。
     * <p>只处理"秒数"这种数字格式；{@code Retry-After} 也可能是 HTTP-date 格式（如
     * {@code Retry-After: Wed, 21 Oct 2026 07:28:00 GMT}），本类不解析这种格式，
     * 遇到时按未提供处理，退回 {@link RetryExecutor} 的兜底退避时间。
     *
     * @param headers 响应头
     * @return 秒数；没有该响应头或解析失败时返回 {@code null}
     */
    private Long parseRetryAfterSeconds(HttpHeaders headers) {
        return headers.firstValue(RETRY_AFTER_HEADER)
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, e);
        }
    }

    private static ThreadFactory buildThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
