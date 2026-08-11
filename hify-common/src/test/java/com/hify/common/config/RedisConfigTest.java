package com.hify.common.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisConfig} 单元测试。
 * <p>不连真实 Redis：{@code LettuceConnectionFactory} 只有在 {@code afterPropertiesSet()}
 * 之后才会建连接，这里只取模板上配好的序列化器直接做往返。
 * <p>覆盖三个最容易出问题的点：key 是不是明文、value 能否还原成原类型、{@code LocalDateTime} 会不会炸。
 */
class RedisConfigTest {

    /** 被测配置产出的模板 */
    private final RedisTemplate<String, Object> redisTemplate =
            new RedisConfig().redisTemplate(new LettuceConnectionFactory());

    @Test
    @DisplayName("key 与 hashKey 用 String 序列化,redis-cli 里可读")
    void keysArePlainString() {
        assertThat(redisTemplate.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(redisTemplate.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);

        StringRedisSerializer keySerializer = (StringRedisSerializer) redisTemplate.getKeySerializer();
        byte[] raw = keySerializer.serialize("hify:agent:1");
        assertThat(new String(raw, StandardCharsets.UTF_8)).isEqualTo("hify:agent:1");
    }

    @Test
    @DisplayName("value 往返后还原成原类型,而不是 LinkedHashMap")
    void valueRoundTripKeepsConcreteType() {
        SampleCache original = new SampleCache(
                "客服助手", LocalDateTime.of(2026, 8, 11, 10, 30, 0), List.of("kb-1", "kb-2"));

        Object restored = roundTrip(original);

        assertThat(restored).isInstanceOf(SampleCache.class);
        assertThat((SampleCache) restored).isEqualTo(original);
    }

    @Test
    @DisplayName("LocalDateTime 序列化成 ISO 字符串,不是时间戳数组")
    void localDateTimeIsIsoString() {
        SampleCache original = new SampleCache(
                "客服助手", LocalDateTime.of(2026, 8, 11, 10, 30, 0), List.of());

        String json = serializeToJson(original);

        assertThat(json).contains("2026-08-11T10:30:00");
        // 时间戳数组形态形如 [2026,8,11,10,30],出现即说明 WRITE_DATES_AS_TIMESTAMPS 没关掉
        assertThat(json).doesNotContain("[2026,8,11");
    }

    @Test
    @DisplayName("JSON 里带 @class 类型信息,这是能还原类型的前提")
    void jsonCarriesTypeInformation() {
        String json = serializeToJson(new SampleCache("x", LocalDateTime.now(), List.of()));

        assertThat(json).contains("@class").contains(SampleCache.class.getName());
    }

    /**
     * 用模板上配好的 value 序列化器做一次序列化 + 反序列化。
     *
     * @param value 待往返的对象
     * @return 还原出来的对象
     */
    private Object roundTrip(Object value) {
        RedisSerializer<Object> serializer = valueSerializer();
        return serializer.deserialize(serializer.serialize(value));
    }

    /**
     * 取序列化后的 JSON 文本，用于断言报文形态。
     *
     * @param value 待序列化的对象
     * @return JSON 字符串
     */
    private String serializeToJson(Object value) {
        return new String(valueSerializer().serialize(value), StandardCharsets.UTF_8);
    }

    /**
     * 取模板上配置的 value 序列化器。
     *
     * @return value 序列化器
     */
    @SuppressWarnings("unchecked")
    private RedisSerializer<Object> valueSerializer() {
        return (RedisSerializer<Object>) redisTemplate.getValueSerializer();
    }

    /**
     * 测试用的缓存对象，模拟 Agent 配置这类带时间字段与集合字段的结构。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class SampleCache {

        /** 名称 */
        private String name;

        /** 创建时间，用来验证 JSR-310 支持 */
        private LocalDateTime createdAt;

        /** 关联的知识库编码，用来验证集合字段 */
        private List<String> knowledgeBaseCodes;
    }
}
