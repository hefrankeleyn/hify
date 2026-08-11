package com.hify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * DEMO 演示实体，对应 {@code demo_item} 表。
 * <p>⚠️ 仅用于验证 {@link BaseEntity} 到 {@code Controller} 的全链路，不是真实业务实体，
 * 真实业务模块的 CRUD 落地后应删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("demo_item")
public class DemoItemEntity extends BaseEntity {

    /** 名称 */
    private String name;

    /** 状态:1 启用 0 停用 */
    private Integer status;
}
