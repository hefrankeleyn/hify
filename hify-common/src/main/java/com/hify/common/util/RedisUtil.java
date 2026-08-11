package com.hify.common.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 操作工具。
 * <p>只封装最常用的四类操作，序列化行为由 {@code RedisConfig} 决定。
 *
 * <p>⚠️ <b>本类不是 {@code @UtilityClass}</b>：它需要注入 {@code RedisTemplate}，
 * 因此是个普通 Spring Bean，用构造器注入（CLAUDE.md 3.10 禁止字段注入）。
 *
 * <p>⚠️ <b>不吞异常</b>：Redis 不可用时异常会原样抛出，最终落到全局异常处理器变成「系统内部错误」。
 * 这是刻意的——静默失败会让 Cache-Aside 变成「每次都读不到缓存」而无人察觉。
 * 若某处需要「Redis 挂了也要能用」的降级，在<b>调用方</b>按业务语义处理，不要在这里统一 catch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisUtil {

    /** 由 {@code RedisConfig} 装配，key 为 String、value 为 JSON */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 写入一个不过期的键。
     * <p>⚠️ 慎用：CLAUDE.md 九节列出的缓存对象都有明确 TTL，
     * 不带 TTL 的键在多次发布后会变成没人认领的垃圾。优先用 {@link #set(String, Object, Duration)}。
     *
     * @param key   键，不能为 {@code null}
     * @param value 值，会被序列化成 JSON
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
        log.debug("[REDIS] set, key={}, ttl=永久", key);
    }

    /**
     * 写入一个带过期时间的键。
     *
     * @param key     键，不能为 {@code null}
     * @param value   值，会被序列化成 JSON
     * @param timeout 存活时长，不能为 {@code null}
     */
    public void set(String key, Object value, Duration timeout) {
        redisTemplate.opsForValue().set(key, value, timeout);
        log.debug("[REDIS] set, key={}, ttl={}s", key, timeout.getSeconds());
    }

    /**
     * 读取一个键。
     *
     * @param key 键，不能为 {@code null}
     * @return 反序列化后的值；键不存在时返回 {@code null}。
     *         得益于 JSON 里的 {@code @class} 信息，返回的是写入时的原始类型，可直接强转
     */
    public Object get(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        log.debug("[REDIS] get, key={}, hit={}", key, value != null);
        return value;
    }

    /**
     * 删除一个键。
     *
     * @param key 键，不能为 {@code null}
     * @return {@code true} 表示键存在且已删除，{@code false} 表示键本来就不存在
     */
    public Boolean delete(String key) {
        Boolean deleted = redisTemplate.delete(key);
        log.debug("[REDIS] delete, key={}, deleted={}", key, deleted);
        return deleted;
    }

    /**
     * 给已存在的键设置过期时间。
     *
     * @param key     键，不能为 {@code null}
     * @param timeout 存活时长，不能为 {@code null}
     * @return {@code true} 表示设置成功，{@code false} 表示键不存在
     */
    public Boolean expire(String key, Duration timeout) {
        Boolean result = redisTemplate.expire(key, timeout);
        log.debug("[REDIS] expire, key={}, ttl={}s, result={}", key, timeout.getSeconds(), result);
        return result;
    }
}
