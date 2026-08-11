/**
 * Agent 定义与配置的数据库实体，与表一一对应，类名 {@code XxxEntity}。
 * <p>🔴 必备五字段：{@code id}、{@code createdAt}、{@code updatedAt}、{@code deleted}、{@code creatorId}，见 CLAUDE.md 5.2。
 * <p>🔴 {@code deleted} 必须声明为 {@code Long}（存删除时刻的毫秒时间戳）。
 * 写成 {@code String} 会让逻辑删除的 SQL 表达式被加上单引号，不报错、静默写坏数据，见 5.3。
 * <p>🔴 {@code createdAt} / {@code updatedAt} 要带 {@code @TableField(fill = ...)}，
 * 否则 {@code AutoFillMetaObjectHandler} 静默不填。
 * <p>🔴 属性必须用包装类型，不设任何默认值，布尔属性不加 {@code is} 前缀。
 * <p>🔴 禁止出现：业务方法、对象引用字段（关联只存 id，不做存在性校验）。
 * <p>🔴 不出现在 Controller 方法签名和跨模块接口签名里。
 */
package com.hify.agent.entity;
