package com.hify.common.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 * <p>只做一件事：把 Spring Boot 默认的 JDK 序列化换成「key 用 String、value 用 JSON」。
 * <p>默认的 {@code JdkSerializationRedisSerializer} 有两个致命问题：
 * 写进去的 key 带一串二进制前缀，{@code redis-cli} 里根本认不出来；
 * value 是 Java 序列化字节流，改一次类结构（加个字段）就反序列化失败。
 *
 * <p>缓存策略见 CLAUDE.md 九节：Provider / Agent 配置 TTL 30min，对话上下文 TTL 2h，
 * 对话消息、知识库文档、LLM 响应<b>不缓存</b>。
 * <p>🔴 不使用进程内缓存（{@code ConcurrentHashMap} 做的本地缓存），为二期多副本留路。
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * 装配 {@code RedisTemplate}。
     * <p>方法名必须叫 {@code redisTemplate}——Spring Boot 的 {@code RedisAutoConfiguration}
     * 用 {@code @ConditionalOnMissingBean(name = "redisTemplate")} 判断，改名就会两个 bean 并存。
     *
     * @param connectionFactory 由 Spring Boot 自动配置提供的连接工厂
     * @return 配好序列化器的模板，不会为 {@code null}
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildObjectMapper());

        // key 与 hashKey 都用 String,保证 redis-cli 里可读、可 SCAN 匹配
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        // value 与 hashValue 都用 JSON
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        log.info("[CONFIG] RedisTemplate 已装配, key=String, value=JSON(含类型信息)");
        return template;
    }

    /**
     * 构造 Redis 专用的 {@code ObjectMapper}。
     * <p>⚠️ 这个 mapper <b>与 Spring MVC 用的那个是两回事</b>，不要互相复用：
     * MVC 的 mapper 绝不能开启默认类型信息，否则每个接口的 JSON 里都会多出 {@code @class} 字段。
     *
     * @return 配好的 mapper，不会为 {@code null}
     */
    private ObjectMapper buildObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 连私有字段一起读写:缓存对象常常没有 setter(如 @Value 的不可变 DTO)
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // LocalDateTime 等 JSR-310 类型的支持。不注册会在写入 createdAt 这类字段时直接抛异常
        objectMapper.registerModule(new JavaTimeModule());
        // 写成 ISO-8601 字符串而不是时间戳数组,便于在 redis-cli 里肉眼核对
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 写入 @class 类型信息,否则读出来的永远是 LinkedHashMap,调用方拿不回原类型。
        // 代价:Redis 里的 JSON 会带一个 @class 字段,且类名变更后旧缓存读不出来(TTL 到期自愈)。
        // LaissezFaire 校验器放行任意类型——本 Redis 只有本应用写入,不接受外部数据,
        // 若将来有外部写入方,必须换成 BasicPolymorphicTypeValidator 白名单。
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        return objectMapper;
    }
}
