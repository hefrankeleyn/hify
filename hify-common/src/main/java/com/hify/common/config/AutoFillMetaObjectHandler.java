package com.hify.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通用字段自动填充。
 * <p>CLAUDE.md 5.2 的必备五字段里，{@code created_at} 与 {@code updated_at} 的维护责任在这里——
 * 🔴 建表时<b>只设 {@code DEFAULT} 不设 {@code ON UPDATE}</b>，因为 {@code ON UPDATE} 会在逻辑删除时
 * 也改动更新时间，把「最后一次业务修改」这个语义弄脏。
 *
 * <p>⚠️ <b>本类只在 entity 字段带 {@code @TableField(fill = ...)} 时才生效</b>：
 * <pre>
 * &#64;TableField(fill = FieldFill.INSERT)
 * private LocalDateTime createdAt;
 *
 * &#64;TableField(fill = FieldFill.INSERT_UPDATE)
 * private LocalDateTime updatedAt;
 * </pre>
 * 漏加注解不会报错，只会静默不填，然后靠数据库的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 兜住 insert、
 * 而 update 时 {@code updated_at} 永远停在创建时间。
 *
 * <p>{@code creator_id} 没有在这里自动填充：它要从 {@code UserContext} 取当前登录用户，
 * 而登录体系尚未落地。等 {@code UserContext} 就绪后在此追加，
 * 注意 CLAUDE.md 3.8 第 7 条——异步线程里 {@code ThreadLocal} 是空的。
 */
@Slf4j
@Component
public class AutoFillMetaObjectHandler implements MetaObjectHandler {

    /** 创建时间的<b>属性名</b>，对应 {@code created_at} 列 */
    private static final String CREATED_AT = "createdAt";

    /** 更新时间的<b>属性名</b>，对应 {@code updated_at} 列 */
    private static final String UPDATED_AT = "updatedAt";

    /**
     * 插入时填充创建时间与更新时间。
     * <p>两者取同一个时刻，避免同一行的 {@code created_at} 与 {@code updated_at} 相差几毫秒，
     * 让「从未被修改过」这个判断（{@code createdAt.equals(updatedAt)}）失效。
     *
     * @param metaObject MyBatis 的元对象，包裹着待插入的 entity
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, CREATED_AT, LocalDateTime.class, now);
        strictInsertFill(metaObject, UPDATED_AT, LocalDateTime.class, now);

        // 写操作在高频路径上,按 3.4 第 4 条降为 debug
        log.debug("[FILL] 插入填充, entity={}, at={}", metaObject.getOriginalObject().getClass().getSimpleName(), now);
    }

    /**
     * 更新时填充更新时间。
     * <p>不碰 {@code createdAt}——{@code strictUpdateFill} 只处理声明为
     * {@code FieldFill.UPDATE} / {@code INSERT_UPDATE} 的字段。
     *
     * @param metaObject MyBatis 的元对象，包裹着待更新的 entity
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictUpdateFill(metaObject, UPDATED_AT, LocalDateTime.class, now);

        log.debug("[FILL] 更新填充, entity={}, at={}", metaObject.getOriginalObject().getClass().getSimpleName(), now);
    }
}
