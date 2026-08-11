package com.hify.common.util;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.experimental.UtilityClass;

/**
 * 分页转换工具。
 * <p>只做「前端分页参数 → MyBatis-Plus {@link Page} 对象」这一件事，在 {@code service.impl} 里调用。
 *
 * <p>⚠️ 响应侧的 {@code IPage → PageResult} 转换<b>没有对应方法</b>：{@code IPage} 只有
 * {@code service.impl} 摸得到（{@code CLAUDE.md} 4.1 禁止 {@code service} 接口签名出现
 * {@code Page}/{@code Wrapper}），而 {@link com.hify.common.result.PageResult} 的构造只允许发生在
 * {@code controller} 的 return 里（8.2）——两条规则决定了这类方法不会有合法调用点。
 * 正确用法是 {@code service.impl} 用 {@code IPage.getRecords()}/{@code getTotal()} 取出普通数据
 * 经 {@code service} 接口传给 Controller，Controller 直接调
 * {@link com.hify.common.result.PageResult#of}，不需要经过本类。
 */
@UtilityClass
public class PageHelper {

    /** 默认页码,与 CLAUDE.md 8.3 对齐 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数上限(CLAUDE.md 8.3),超过时截断而不是报错 */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 把分页参数转成 MyBatis-Plus 的 {@link Page} 对象。
     * <p>对越界值做防御性纠正而不是抛异常——本方法可能被内部代码直接调用（不经过
     * {@code @Valid} 校验的场景），所以即便调用方传了非法值也要能安全兜住，
     * 不代替 HTTP 边界的参数校验（那部分由 Request DTO 上的 {@code @Min}/{@code @Max} 负责）。
     *
     * @param page     页码,从 1 开始;为 {@code null} 或小于 1 时按 1 处理
     * @param pageSize 每页条数;为 {@code null} 或小于 1 时按默认 20 处理,超过 100 按 100 处理
     * @param <T>      查询实体类型
     * @return 可直接传给 {@code baseMapper.selectPage(...)} 的 {@link Page} 对象，不会为 {@code null}
     */
    public static <T> Page<T> toPage(Integer page, Integer pageSize) {
        int normalizedPage = (page == null || page < 1) ? DEFAULT_PAGE : page;
        int normalizedPageSize = (pageSize == null || pageSize < 1)
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        return new Page<>(normalizedPage, normalizedPageSize);
    }
}
