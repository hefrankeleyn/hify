package com.hify.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级 {@code traceId} 生成/透传 + 访问日志。
 * <p>两件事放在同一个 {@link jakarta.servlet.Filter} 里做，不拆成两个：
 * <ol>
 *   <li>生成/透传 {@code traceId} 并写入 {@link MDC}——日志格式里的 {@code %X{traceId}}
 *       占位符靠这里填；</li>
 *   <li>记录访问日志（method/path/status/耗时），慢请求（&gt;1s）标 {@code WARN}。</li>
 * </ol>
 *
 * <p>⚠️ <b>这条和 Controller 里业务语义的入口日志（"[API] 创建 Agent, name=xxx"）是两回事，
 * 不冲突</b>：那条日志只有 Controller 自己知道该打什么"关键标识"，这里打的是通用 HTTP 访问日志
 * （类似 Nginx access log），和具体业务无关。
 *
 * <p>⚠️ <b>用 {@link jakarta.servlet.Filter} 不用 {@code HandlerInterceptor}</b>：
 * {@code Filter} 跑在 {@code DispatcherServlet} 之前，覆盖请求的完整生命周期（含 404
 * 这类根本没匹配到 Controller 方法的请求）；{@code HandlerInterceptor} 只在匹配到 handler
 * 时才会调用，且 {@code afterCompletion} 不保证在所有分支都被调用到——用它来清理 {@code MDC}
 * 有遗漏风险（{@code CLAUDE.md} 3.8 第 6 条：{@code ThreadLocal} 用完必须 {@code remove}，
 * 线程池复用线程，漏清理会串数据到下一个请求）。{@code Filter} 的 {@code try/finally}
 * 能保证清理一定执行。
 *
 * <p>⚠️ 只覆盖 Tomcat 工作线程这一段——请求一旦进入 {@code llm-chat-}/{@code kb-index-}
 * 线程池的异步任务，{@code MDC} 是空的（{@code CLAUDE.md} 3.8 第 7 条），
 * 需要在提交任务前把 {@code traceId} 显式取出再传入，本类不负责这部分。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    /** 客户端可以自带 traceId 透传（比如前端网关已经生成过一个），没带就自己生成 */
    private static final String TRACE_ID_HEADER = "X-Request-Id";

    /** MDC 里的 key，要和日志 pattern 里的 %X{traceId} 对应 */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    /** 慢请求阈值:超过标 WARN */
    private static final long SLOW_REQUEST_THRESHOLD_MILLIS = 1000L;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMillis = System.currentTimeMillis() - start;
            logAccess(request, response, elapsedMillis);
            // 🔴 ThreadLocal 用完必须 remove(CLAUDE.md 3.8 第 6 条),线程池复用线程,漏清理会串数据
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        return traceId;
    }

    private void logAccess(HttpServletRequest request, HttpServletResponse response, long elapsedMillis) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        int status = response.getStatus();

        if (elapsedMillis > SLOW_REQUEST_THRESHOLD_MILLIS) {
            log.warn("[ACCESS] 慢请求, method={}, path={}, status={}, elapsedMs={}",
                    method, path, status, elapsedMillis);
        } else {
            log.info("[ACCESS] method={}, path={}, status={}, elapsedMs={}",
                    method, path, status, elapsedMillis);
        }
    }
}
