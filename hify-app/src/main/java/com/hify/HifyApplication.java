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
 *
 * <p>⚠️ {@code @MapperScan} 用了两条 Ant 风格路径，不是一条：
 * <ul>
 *   <li>{@code com.hify.*.mapper} —— {@code *} 恰好匹配一段，覆盖其余七个业务模块
 *       （{@code com.hify.provider.mapper} 等，均带一段模块名）；</li>
 *   <li>{@code com.hify.mapper} —— {@code hify-app} 自己的 Mapper（如 {@code user}/{@code api_key}）
 *       直接挂在 {@code com.hify} 下，天然没有模块名这一段，{@code *} 匹配不到零段，必须单独列出。</li>
 * </ul>
 * 两条都保留而不是合并成 {@code com.hify.**.mapper}，是为了不放松对其余七个模块的扫描精度——
 * {@code **} 会连带扫到嵌套更深的 Mapper 包，掩盖扁平九包规则（2.3）被违反的信号。
 */
@Slf4j
@SpringBootApplication
@MapperScan({"com.hify.*.mapper", "com.hify.mapper"})
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
