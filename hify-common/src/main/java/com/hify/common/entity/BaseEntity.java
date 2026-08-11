package com.hify.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公共 Entity 基类。
 * <p>持有 {@code CLAUDE.md} 5.2 规定的每张表必备五字段，各模块的 Entity 继承本类后
 * 只需再声明自己的业务列。本类不对应任何具体表。
 *
 * <p>⚠️ <b>{@code deleted} 声明为 {@code Long}，不是 {@code Integer}</b>：对应的数据库列是
 * {@code BIGINT UNSIGNED}，存的是删除时刻的<b>毫秒时间戳</b>（不是 0/1 标记）——
 * 这是 {@code CLAUDE.md} 5.3「唯一索引与软删除共存」方案的核心，用 {@code Integer} 存毫秒时间戳
 * 会直接数值溢出（{@code Integer} 上限约 21 亿，当前毫秒级时间戳已是万亿级）。
 *
 * <p>⚠️ <b>{@code deleted} 字段本身不加 {@code @TableLogic} 注解</b>：逻辑删除的字段名/未删值/
 * 删除值已经在 {@link com.hify.common.config.MybatisPlusConfig} 里做了全局配置，
 * 只要属性名叫 {@code deleted} 就会被自动识别。这是刻意的——16 张表各自标注容易漏标，
 * 且字段级 {@code @TableLogic} 若另外指定 value/delval 会和全局配置的时间戳表达式打架。
 *
 * <p>🔴 本类不设任何属性默认值（{@code CLAUDE.md} 3.5 第 4 条）——{@code deleted} 的初始值 0
 * 由数据库列的 {@code DEFAULT 0} 负责，Java 侧留空表示「未设置」。
 */
@Data
public abstract class BaseEntity {

    /** 主键 id，数据库自增（id-type=auto 已在 application.yml 全局配置） */
    @TableId
    private Long id;

    /** 创建时间，插入时由 AutoFillMetaObjectHandler 自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间，插入和更新时都由 AutoFillMetaObjectHandler 自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除:0 未删,已删存删除时刻毫秒时间戳。属性名必须叫 deleted,由全局配置自动识别,不加 @TableLogic */
    private Long deleted;

    /** 创建人 id。登录体系未落地前始终为 0（数据库 DEFAULT 兜底） */
    private Long creatorId;
}
