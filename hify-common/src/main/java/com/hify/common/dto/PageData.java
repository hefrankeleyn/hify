package com.hify.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 分页查询结果的模块间传递载体。
 * <p>{@code service} 接口不允许出现 {@code IPage}/{@code Page}（CLAUDE.md 4.1），
 * {@code PageResult} 的构造又只允许发生在 Controller（8.2）——两条规则中间缺一个「服务层
 * 往外传分页结果」的中性载体，本类就是补这个位置：{@code service.impl} 从 {@code IPage} 里
 * 取出 {@code records}/{@code total} 装进本类返回，Controller 拿到后再调
 * {@link Result#of} 拼成 {@code PageResult}。
 *
 * @param <T> 列表元素类型
 * @see com.hify.common.result.PageResult#of
 * @see com.hify.common.util.PageHelper#toPage
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageData<T> {

    /** 当前页数据 */
    private List<T> records = Collections.emptyList();

    /** 总记录数 */
    private Long total = 0L;
}
