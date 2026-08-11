package com.hify.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusPropertiesCustomizer;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局配置。
 * <p>装配两件事：分页插件、逻辑删除。自动填充见 {@link AutoFillMetaObjectHandler}。
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数，与 CLAUDE.md 8.3 的 pageSize 上限一致，兜住绕过 DTO 校验的调用 */
    private static final Long MAX_PAGE_SIZE = 100L;

    /** 逻辑删除字段的<b>属性名</b>（不是列名），对应 CLAUDE.md 5.2 必备五字段里的 deleted */
    private static final String LOGIC_DELETE_FIELD = "deleted";

    /** 未删除时的值 */
    private static final String LOGIC_NOT_DELETE_VALUE = "0";

    /**
     * 删除时写入的值：删除时刻的毫秒时间戳。
     * <p>🔴 这是 CLAUDE.md 5.3 里「唯一索引与软删除共存」方案的核心——每次删除产生一个不同的
     * {@code deleted} 值，配合 {@code UNIQUE KEY (name, deleted)} 就能无限次删除并重建同名对象。
     * <p>✅ 已在 MyBatis-Plus 3.5.10.1 上核实该 SQL 表达式<b>不会</b>被当成字面量加引号：
     * {@code TableInfo#formatLogicDeleteSql} 只在 {@code isCharSequence()} 为 true 时套单引号，
     * 而该标志取自字段的 Java 类型；{@code deleted} 声明为 {@code Long}，故此处原样拼进 SQL。
     * <p>⚠️ 因此 <b>entity 里 {@code deleted} 必须声明为 {@code Long}</b>，
     * 一旦写成 {@code String} 就会变成 {@code deleted='UNIX_TIMESTAMP(NOW(3)) * 1000'}，静默写坏数据。
     */
    private static final String LOGIC_DELETE_VALUE = "UNIX_TIMESTAMP(NOW(3)) * 1000";

    /**
     * 装配 MyBatis-Plus 拦截器链。
     * <p>目前只有分页插件。CLAUDE.md 5.5 要求大表走游标分页而非 {@code LIMIT offset}，
     * 那类查询自己写 SQL，不经过本插件。
     *
     * @return 拦截器链，不会为 {@code null}
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 超过上限时直接截断为 100,而不是放行
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 页码超出总页数时返回空结果,不回到第一页——回第一页会让前端误以为还有数据,翻页翻不完
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        log.info("[CONFIG] MyBatis-Plus 分页插件已装配, dbType=MYSQL, maxLimit={}, overflow=false", MAX_PAGE_SIZE);
        return interceptor;
    }

    /**
     * 全局逻辑删除配置。
     * <p>配在全局而不是每个 entity 上写 {@code @TableLogic}，是为了避免 16 张表抄 16 遍、
     * 且抄漏一张就会在查询里漏掉 {@code AND deleted = 0}。
     * <p>entity 侧仍需把字段声明为 {@code Long deleted}（属性名必须叫 {@code deleted}）。
     *
     * @return 属性定制器，不会为 {@code null}
     */
    @Bean
    public MybatisPlusPropertiesCustomizer logicDeletePropertiesCustomizer() {
        return properties -> {
            GlobalConfig.DbConfig dbConfig = properties.getGlobalConfig().getDbConfig();
            dbConfig.setLogicDeleteField(LOGIC_DELETE_FIELD);
            dbConfig.setLogicNotDeleteValue(LOGIC_NOT_DELETE_VALUE);
            dbConfig.setLogicDeleteValue(LOGIC_DELETE_VALUE);

            log.info("[CONFIG] 逻辑删除已配置, field={}, notDeleteValue={}, deleteValue={}",
                    LOGIC_DELETE_FIELD, LOGIC_NOT_DELETE_VALUE, LOGIC_DELETE_VALUE);
        };
    }
}
