package com.hify.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 通用分页请求参数（CLAUDE.md 8.3）。
 * <p>各模块的分页 Request DTO 组合或继承本类即可获得统一的分页参数与校验规则，
 * 不用每个模块各写一遍 {@code page}/{@code pageSize} 字段。
 *
 * <p>⚠️ 本类字段带了默认值（{@code page = 1}、{@code pageSize = 20}），这不是
 * {@code CLAUDE.md} 3.5 第 4 条禁止的「POJO 属性默认值」——那条针对的是 Entity，
 * 请求参数类给合理默认值本身就是设计意图（不传 page 时按第一页处理），两者场景不同。
 *
 * <p>⚠️ 这两个 {@code @Min}/{@code @Max} 只在 Spring MVC 走 {@code @Valid} 绑定请求参数时生效
 * ——如果代码里手动 {@code new PageQuery(...)} 构造，不会触发校验，此时应改用
 * {@link com.hify.common.util.PageHelper#toPage} 做防御性纠正。
 */
@Data
public class PageQuery {

    /** 页码,从 1 开始 */
    @Min(value = 1, message = "页码不能小于 1")
    private Integer page = 1;

    /** 每页条数,上限 100(CLAUDE.md 8.3),否则一次拉全表能打爆堆 */
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 20;
}
