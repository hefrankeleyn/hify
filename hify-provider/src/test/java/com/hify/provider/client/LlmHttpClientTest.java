package com.hify.provider.client;

import com.hify.common.exception.BizException;
import com.hify.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmHttpClient} 的行为测试。
 * <p>用 JDK 自带的 {@link com.sun.net.httpserver.HttpServer} 起一个本地测试服务，
 * 不引入额外的 mock HTTP 依赖，覆盖普通请求、流式请求成功、流间隔超时、连接失败四种场景。
 */
class LlmHttpClientTest {

    private final LlmHttpClient client = new LlmHttpClient();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void post_shouldReturnBodyAndLogElapsed() throws IOException {
        server = startServer("/normal", exchange -> {
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        String body = client.post(baseUrl() + "/normal", Collections.emptyMap(), "{}");

        assertEquals("{\"ok\":true}", body);
    }

    @Test
    void post_shouldThrowAuthFailedOn401() throws IOException {
        server = startServer("/unauthorized", exchange -> exchange.sendResponseHeaders(401, -1));

        BizException exception = assertThrows(BizException.class,
                () -> client.post(baseUrl() + "/unauthorized", Collections.emptyMap(), "{}"));

        assertEquals(ErrorCode.PROVIDER_AUTH_FAILED.getCode(), exception.getCode());
    }

    @Test
    void post_shouldThrowRateLimitedOn429() throws IOException {
        server = startServer("/rate-limited", exchange -> exchange.sendResponseHeaders(429, -1));

        BizException exception = assertThrows(BizException.class,
                () -> client.post(baseUrl() + "/rate-limited", Collections.emptyMap(), "{}"));

        assertEquals(ErrorCode.PROVIDER_RATE_LIMITED.getCode(), exception.getCode());
    }

    @Test
    void post_shouldThrowTimeoutOnConnectionFailure() {
        // 连接一个没有监听的端口,制造连接失败(不需要真的等待超时)
        BizException exception = assertThrows(BizException.class,
                () -> client.post("http://localhost:1", Collections.emptyMap(), "{}"));

        assertEquals(ErrorCode.PROVIDER_TIMEOUT.getCode(), exception.getCode());
    }

    @Test
    void stream_shouldCallbackEachLineInOrder() throws IOException {
        server = startServer("/stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("data: chunk1\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write("data: chunk2\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write("data: chunk3\n".getBytes(StandardCharsets.UTF_8));
            }
        });

        List<String> lines = new CopyOnWriteArrayList<>();
        client.stream(baseUrl() + "/stream", Collections.emptyMap(), "{}", lines::add);

        assertEquals(List.of("data: chunk1", "data: chunk2", "data: chunk3"), lines);
    }

    @Test
    void stream_shouldAbortOnStreamIntervalTimeout() throws IOException {
        server = startServer("/slow-stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("data: first\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                // 第一行之后长时间不发数据,应触发流间隔超时(测试用 500ms 的短超时,不真的等 30s)
                Thread.sleep(3000);
                os.write("data: second\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        List<String> lines = new CopyOnWriteArrayList<>();
        long start = System.currentTimeMillis();
        BizException exception = assertThrows(BizException.class, () -> client.stream(
                baseUrl() + "/slow-stream", Collections.emptyMap(), "{}", lines::add,
                Duration.ofSeconds(5), Duration.ofMillis(500), Duration.ofSeconds(10)));
        long elapsedMs = System.currentTimeMillis() - start;

        assertEquals(ErrorCode.PROVIDER_TIMEOUT.getCode(), exception.getCode());
        assertEquals(List.of("data: first"), lines, "超时前收到的行应该已经回调给业务方");
        assertTrue(elapsedMs < 3000, "应该在 3s 服务端睡眠结束前,由 500ms 的流间隔超时先触发中断");
    }

    private HttpServer startServer(String path, HttpHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext(path, handler::handle);
        // 不调用 setExecutor:用 HttpServer 默认的内部调度线程即可,测试里没有并发请求场景,
        // 避免为一个短生命周期的测试服务器引入线程池(CLAUDE.md 3.8 第 2 条针对的是业务代码)
        httpServer.start();
        return httpServer;
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @FunctionalInterface
    private interface HttpHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
