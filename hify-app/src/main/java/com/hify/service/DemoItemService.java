package com.hify.service;

import com.hify.common.dto.PageData;
import com.hify.common.dto.PageQuery;
import com.hify.dto.DemoItemCreateRequest;
import com.hify.dto.DemoItemResponse;
import com.hify.dto.DemoItemUpdateRequest;

/**
 * DEMO 演示项业务能力声明。
 * <p>⚠️ 仅用于验证全链路是否打通，不是真实业务接口。
 */
public interface DemoItemService {

    /**
     * 创建演示项。
     *
     * @param request 创建请求，不能为 {@code null}
     * @return 新建记录的主键 id
     */
    Long create(DemoItemCreateRequest request);

    /**
     * 更新演示项。
     *
     * @param id      演示项 id
     * @param request 更新请求，不能为 {@code null}
     * @throws com.hify.common.exception.BizException 记录不存在时抛出
     */
    void update(Long id, DemoItemUpdateRequest request);

    /**
     * 删除演示项（软删）。
     *
     * @param id 演示项 id
     * @throws com.hify.common.exception.BizException 记录不存在时抛出
     */
    void delete(Long id);

    /**
     * 查询演示项详情。
     *
     * @param id 演示项 id
     * @return 详情，不会为 {@code null}
     * @throws com.hify.common.exception.BizException 记录不存在时抛出
     */
    DemoItemResponse get(Long id);

    /**
     * 分页查询演示项。
     *
     * @param query 分页参数，不能为 {@code null}
     * @return 分页结果，不会为 {@code null}
     */
    PageData<DemoItemResponse> page(PageQuery query);
}
