package com.hify.common.result;

import com.hify.common.constant.ResultConstant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Collections;
import java.util.List;

/**
 * 分页响应体。
 * <p>继承 {@link Result}，把分页元信息拍平到与 {@code code / message / data} 同一层：
 * <pre>
 * {
 *   "code": 200,
 *   "message": "success",
 *   "data": [ {...}, {...} ],
 *   "total": 137,
 *   "page": 1,
 *   "size": 20
 * }
 * </pre>
 * 父类的 {@code data} 承载当前页数据列表，因此泛型实参是 {@code List<T>} 而不是 {@code T}。
 *
 * <p>⚠️ <b>静态工厂不会被继承出正确类型</b>：{@code Result.ok(...)} / {@code Result.fail(...)}
 * 返回的是 {@link Result} 而非本类，通过 {@code PageResult.ok(...)} 调用只是访问了父类的静态方法。
 * 构造分页响应一律用 {@link #of(List, Long, Integer, Integer)}，
 * 方法名刻意避开 {@code ok} 以免形成静态方法隐藏。
 *
 * <p>分页失败时不要用本类，直接返回 {@code Result.fail(...)} 即可——
 * 失败响应没有分页元信息可填。
 *
 * @param <T> 列表元素类型
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class PageResult<T> extends Result<List<T>> {

    /**
     * 符合查询条件的总记录数。
     * <p>⚠️ 大表按 CLAUDE.md 5.5 必须关闭 count（{@code new Page<>(pageNo, pageSize, false)}），
     * 此时传 0，前端显示「100+」或不显示总数，不要拿它做「共 N 页」的计算。
     */
    private Long total;

    /** 当前页码，从 1 开始（与 CLAUDE.md 8.3 的请求参数 page 对齐） */
    private Integer page;

    /**
     * 每页条数。
     * <p>对应请求参数 {@code pageSize}，上限 100，见 CLAUDE.md 8.3。
     * 响应侧刻意叫 {@code size} 而非 {@code pageSize}，是本项目的既定约定。
     */
    private Integer size;

    /**
     * 构造一个分页成功响应。
     *
     * @param list  当前页数据；为 {@code null} 时按 CLAUDE.md 8.4「列表字段空时返回 []」转成空列表
     * @param total 总记录数；关闭 count 的大表传 {@code 0L}，允许为 {@code null}
     * @param page  当前页码，从 1 开始
     * @param size  每页条数
     * @param <T>   列表元素类型
     * @return code=200、message="success" 的分页响应，不会为 {@code null}
     */
    public static <T> PageResult<T> of(List<T> list, Long total, Integer page, Integer size) {
        PageResult<T> result = new PageResult<>();
        result.setCode(ResultConstant.SUCCESS_CODE);
        result.setMessage(ResultConstant.SUCCESS_MESSAGE);
        // CLAUDE.md 8.4 红线:列表字段空时返回 [],不返回 null
        result.setData(list == null ? Collections.<T>emptyList() : list);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }
}
