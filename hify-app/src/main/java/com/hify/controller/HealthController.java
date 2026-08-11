package com.hify.controller;

import com.hify.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口。
 * <p>供本地自测、Nginx 上游探活、以及后续 K8s 的 liveness / readiness 探针使用。
 *
 * <p>本类是 {@code hify-app} 里唯一的 Controller。CLAUDE.md 2.1 规定 {@code hify-app}
 * 「无业务代码」——健康检查不属于任何业务域，是应用外壳自身的运维接口，因此放在这里，
 * 而不是硬塞进某个业务模块。业务接口一律归各自模块的 {@code controller} 包。
 *
 * <p>⚠️ 目前只回报「进程活着」，<b>不探测 MySQL / Redis 的可用性</b>。
 * 若将来要做 readiness 探针（依赖不通就摘流量），需要另开一个端点做真实依赖检查——
 * 不要把依赖检查混进本端点，否则数据库抖一下会把整个 Pod 重启掉。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    /** 健康检查的固定返回文案 */
    private static final String RUNNING_MESSAGE = "Hify is running";

    /**
     * 健康检查。
     *
     * @return 固定返回 {@code code=200}、{@code data="Hify is running"}，不会为 {@code null}
     */
    @GetMapping
    public Result<String> health() {
        // 🔴 用 debug 而不是 3.4 规定的 info：探针会以秒级频率轮询本端点,
        // 打 info 会把生产日志冲垮,性质等同于 3.4 第 4 条禁止的「循环体内打 info」
        log.debug("[API] 健康检查");
        return Result.ok(RUNNING_MESSAGE);
    }
}
