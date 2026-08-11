package com.hify.common.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * Spring MVC 的 Jackson 全局时间序列化配置。
 * <p>⚠️ 这个 {@code ObjectMapper} 是 HTTP 响应用的那个，和 {@link RedisConfig} 里为 Redis
 * 单独构造的 {@code ObjectMapper} 是两回事，互不影响、不要复用（{@code RedisConfig} 类头注释已点明）。
 *
 * <p>不用 {@code spring.jackson.serialization.write-dates-as-timestamps=false} 这一个开关了事，
 * 是因为它只解决"不输出成数组"，不能保证具体格式——{@code LocalDateTime} 若带纳秒会被
 * 输出成 {@code 2026-08-12T10:30:00.123456789} 这种变长格式。这里显式注册按固定 pattern
 * 格式化的序列化器，格式在任何输入下都是确定的 {@code yyyy-MM-dd'T'HH:mm:ss} / {@code yyyy-MM-dd}。
 * <p>⚠️ 代价是丢弃秒以下精度——数据库 {@code created_at}/{@code updated_at} 是
 * {@code DATETIME(3)}(毫秒精度)，序列化到 JSON 后毫秒部分会被截断，只在展示层生效，
 * 不影响库里存的值。
 */
@Slf4j
@Configuration
public class JacksonConfig {

    /** LocalDateTime 的输出/解析格式,ISO 8601 去掉时区、去掉小数秒 */
    private static final String LOCAL_DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    /** LocalDate 的输出/解析格式 */
    private static final String LOCAL_DATE_PATTERN = "yyyy-MM-dd";

    /**
     * 定制 Spring Boot 自动装配的 {@code ObjectMapper}。
     * <p>用 {@link Jackson2ObjectMapperBuilderCustomizer} 而不是直接声明一个新的
     * {@code @Bean ObjectMapper}——后者会整个替换掉 Spring Boot 的自动配置，
     * 丢失它默认装配的其它模块；定制器只是在其基础上追加行为。
     *
     * @return 定制器，不会为 {@code null}
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonObjectMapperBuilderCustomizer() {
        return builder -> {
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(LOCAL_DATE_TIME_PATTERN);
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(LOCAL_DATE_PATTERN);

            JavaTimeModule javaTimeModule = new JavaTimeModule();
            javaTimeModule.addSerializer(new LocalDateTimeSerializer(dateTimeFormatter));
            javaTimeModule.addDeserializer(java.time.LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));
            javaTimeModule.addSerializer(new LocalDateSerializer(dateFormatter));
            javaTimeModule.addDeserializer(java.time.LocalDate.class, new LocalDateDeserializer(dateFormatter));

            builder.modules(javaTimeModule);
            // 双保险:即便以后有代码手动创建了没走本定制器的 ObjectMapper,这个开关也能兜住
            // "别输出成数组"这一条,只是不保证具体 pattern
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            log.info("[CONFIG] Jackson 时间序列化已配置, LocalDateTime={}, LocalDate={}",
                    LOCAL_DATE_TIME_PATTERN, LOCAL_DATE_PATTERN);
        };
    }
}
