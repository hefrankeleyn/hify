package com.hify.provider.client;

/**
 * {@link LlmHttpClient#stream} 的逐行回调。
 * <p>每收到 SSE 响应体的一行就回调一次，行内容不做任何解析（{@code data: xxx} 前缀等
 * 由调用方按具体 provider 的协议自行处理，本类只负责把字节流安全地切成行）。
 */
@FunctionalInterface
public interface StreamCallback {

    /**
     * 收到一行数据时回调。
     *
     * @param line 一行原始内容，不含行结束符
     */
    void onLine(String line);
}
