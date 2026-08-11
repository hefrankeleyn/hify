package com.hify.controller;

import com.hify.common.dto.PageData;
import com.hify.common.dto.PageQuery;
import com.hify.common.result.PageResult;
import com.hify.common.result.Result;
import com.hify.dto.DemoItemCreateRequest;
import com.hify.dto.DemoItemResponse;
import com.hify.dto.DemoItemUpdateRequest;
import com.hify.service.DemoItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMO 演示项接口。
 * <p>⚠️ 仅用于验证 Controller → Service → Mapper → Entity 全链路（含 {@code Result}/
 * {@code PageResult}/参数校验/时间序列化/缓存）是否打通，不是真实业务接口。
 * 真实业务模块的 CRUD 落地后应删除本类及配套的 Entity/Mapper/Service/DTO/Flyway 脚本。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/demo-items")
@RequiredArgsConstructor
public class DemoItemController {

    /** DEMO 演示项业务能力 */
    private final DemoItemService demoItemService;

    /**
     * 创建演示项。
     *
     * @param request 创建请求
     * @return 新建记录的主键 id
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody DemoItemCreateRequest request) {
        log.info("[API] 创建演示项, name={}", request.getName());
        return Result.ok(demoItemService.create(request));
    }

    /**
     * 更新演示项。
     *
     * @param id      演示项 id
     * @param request 更新请求
     * @return 无返回数据
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DemoItemUpdateRequest request) {
        log.info("[API] 更新演示项, id={}", id);
        demoItemService.update(id, request);
        return Result.ok();
    }

    /**
     * 删除演示项（软删）。
     *
     * @param id 演示项 id
     * @return 无返回数据
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("[API] 删除演示项, id={}", id);
        demoItemService.delete(id);
        return Result.ok();
    }

    /**
     * 查询演示项详情。
     *
     * @param id 演示项 id
     * @return 详情
     */
    @GetMapping("/{id}")
    public Result<DemoItemResponse> get(@PathVariable Long id) {
        log.info("[API] 查询演示项, id={}", id);
        return Result.ok(demoItemService.get(id));
    }

    /**
     * 分页查询演示项。
     *
     * @param query 分页参数
     * @return 分页结果
     */
    @GetMapping
    public PageResult<DemoItemResponse> page(@Valid PageQuery query) {
        log.info("[API] 分页查询演示项, page={}, pageSize={}", query.getPage(), query.getPageSize());
        PageData<DemoItemResponse> data = demoItemService.page(query);
        return PageResult.of(data.getRecords(), data.getTotal(), query.getPage(), query.getPageSize());
    }
}
