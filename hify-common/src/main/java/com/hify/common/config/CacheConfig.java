package com.hify.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Cache 集成，落实 {@code CLAUDE.md} 九节的 Redis Cache-Aside 策略。
 * <p>⚠️ 不需要新加 {@code spring-boot-starter-cache} 依赖——{@code @EnableCaching}/
 * {@code @Cacheable} 来自 {@code spring-context}（任何 Spring Boot 应用天然自带），
 * {@link RedisCacheManager} 来自 {@code spring-data-redis}（{@link RedisConfig} 已经在用这个
 * starter），纯配置工作，不算引入白名单外依赖。
 *
 * <p>value 序列化复用 {@link RedisConfig#buildRedisObjectMapper()} 那一套 —— 保证
 * {@code @Cacheable} 写入的 key 和 {@code RedisUtil} 手动写入的 key 是同一套 JSON 格式，
 * 不会出现同一个 Redis 实例里两套序列化规则互相看不懂的情况。
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig {

    /** 全局 key 前缀,最终 key 形如 hify:provider-cache::123 */
    private static final String KEY_PREFIX = "hify:";

    /** 默认 TTL(CLAUDE.md 九节:Provider/Agent 配置 30min) */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /** Provider 配置缓存 */
    private static final String PROVIDER_CACHE = "provider-cache";

    /** Agent 配置缓存 */
    private static final String AGENT_CACHE = "agent-cache";

    /** 对话上下文缓存,TTL 2h(CLAUDE.md 九节) */
    private static final String SESSION_CACHE = "session-cache";

    /**
     * 装配 {@link CacheManager}。
     * <p>各业务模块的 {@code service.impl} 方法上按 {@code CLAUDE.md} 九节列出的对象加
     * {@code @Cacheable(cacheNames = "provider-cache", key = "#id")} / {@code @CacheEvict}
     * 即可生效，具体 {@code cacheNames} 从下面已注册的三个里选，不要在业务代码里手写新的
     * cache 名——那会绕开这里统一配的 TTL，退化成用默认 30min。
     *
     * @param connectionFactory 由 Spring Boot 自动配置提供的连接工厂
     * @return 配好前缀、序列化、分缓存 TTL 的缓存管理器，不会为 {@code null}
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(RedisConfig.buildRedisObjectMapper());

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_TTL)
                .computePrefixWith(cacheName -> KEY_PREFIX + cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));

        Map<String, RedisCacheConfiguration> namedConfigs = new HashMap<>();
        namedConfigs.put(PROVIDER_CACHE, defaultConfig.entryTtl(Duration.ofMinutes(30)));
        namedConfigs.put(AGENT_CACHE, defaultConfig.entryTtl(Duration.ofMinutes(30)));
        namedConfigs.put(SESSION_CACHE, defaultConfig.entryTtl(Duration.ofHours(2)));

        RedisCacheManager cacheManager = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(namedConfigs)
                .build();

        log.info("[CONFIG] RedisCacheManager 已装配, keyPrefix={}, defaultTtl={}min, caches={}",
                KEY_PREFIX, DEFAULT_TTL.toMinutes(), namedConfigs.keySet());
        return cacheManager;
    }
}
