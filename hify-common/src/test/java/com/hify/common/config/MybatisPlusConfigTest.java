package com.hify.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MybatisPlusConfig} 单元测试。
 * <p>不连数据库，只验证装配出来的配置值——这些值一旦写错不会报错，
 * 而是在生产上表现为「分页拉了一万条」或「软删除写坏数据」。
 */
class MybatisPlusConfigTest {

    /** 被测配置 */
    private final MybatisPlusConfig config = new MybatisPlusConfig();

    @Test
    @DisplayName("分页插件按 MySQL 装配,单页上限 100,不允许溢出回第一页")
    void paginationInterceptorIsConfigured() {
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();

        List<InnerInterceptor> inners = interceptor.getInterceptors();
        assertThat(inners).hasSize(1).first().isInstanceOf(PaginationInnerInterceptor.class);

        PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) inners.get(0);
        assertThat(pagination.getDbType()).isEqualTo(DbType.MYSQL);
        // 与 CLAUDE.md 8.3 的 pageSize 上限一致
        assertThat(pagination.getMaxLimit()).isEqualTo(100L);
        assertThat(pagination.isOverflow()).isFalse();
    }

    @Test
    @DisplayName("逻辑删除字段与取值符合 5.3 的时间戳方案")
    void logicDeleteIsConfigured() {
        MybatisPlusProperties properties = new MybatisPlusProperties();

        config.logicDeletePropertiesCustomizer().customize(properties);

        GlobalConfig.DbConfig dbConfig = properties.getGlobalConfig().getDbConfig();
        assertThat(dbConfig.getLogicDeleteField()).isEqualTo("deleted");
        assertThat(dbConfig.getLogicNotDeleteValue()).isEqualTo("0");
        // 🔴 必须是 SQL 表达式而不是固定值,否则同名对象删除一次后就再也建不回来
        assertThat(dbConfig.getLogicDeleteValue()).isEqualTo("UNIX_TIMESTAMP(NOW(3)) * 1000");
    }
}
