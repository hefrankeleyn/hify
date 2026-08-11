package com.hify;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Hify 应用入口。
 * <p>模块化单体：八个 Maven 模块编译期隔离，运行期是<b>一个进程、一个 jar</b>（CLAUDE.md 2.1）。
 *
 * <p>本类放在 {@code com.hify} 下是刻意的——{@code @SpringBootApplication} 的组件扫描以本类所在包为根，
 * 放在这里才能一次覆盖 {@code com.hify.common}、{@code com.hify.provider} …… 全部八个模块。
 * 往下挪一层（比如 {@code com.hify.app}）会导致其它模块的 Bean 全部扫不到。
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.hify.*.mapper")
public class HifyApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数，直接透传给 Spring Boot
     */
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(HifyApplication.class, args);

        Environment environment = context.getEnvironment();
        // 启动完成时打一条,便于在容器日志里确认端口与激活的 profile
        log.info("[STARTUP] Hify 启动完成, port={}, contextPath={}, profiles={}",
                environment.getProperty("server.port"),
                environment.getProperty("server.servlet.context-path", ""),
                Arrays.toString(environment.getActiveProfiles()));
    }
}
