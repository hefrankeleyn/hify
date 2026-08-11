package com.hify.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.dto.PageData;
import com.hify.common.dto.PageQuery;
import com.hify.common.exception.BizException;
import com.hify.common.exception.ErrorCode;
import com.hify.common.util.PageHelper;
import com.hify.dto.DemoItemCreateRequest;
import com.hify.dto.DemoItemResponse;
import com.hify.dto.DemoItemUpdateRequest;
import com.hify.entity.DemoItemEntity;
import com.hify.mapper.DemoItemMapper;
import com.hify.service.DemoItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * DEMO 演示项业务实现。
 * <p>⚠️ 仅用于验证全链路是否打通，不是真实业务实现，真实业务模块落地后应删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoItemServiceImpl implements DemoItemService {

    /** demo_item 数据访问 */
    private final DemoItemMapper demoItemMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DemoItemCreateRequest request) {
        log.info("[DEMO] 创建演示项, name={}", request.getName());

        DemoItemEntity entity = new DemoItemEntity();
        entity.setName(request.getName());
        entity.setStatus(request.getStatus());
        demoItemMapper.insert(entity);

        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, DemoItemUpdateRequest request) {
        log.info("[DEMO] 更新演示项, id={}", id);

        DemoItemEntity existing = demoItemMapper.selectById(id);
        if (existing == null) {
            log.warn("[DEMO] 演示项不存在, id={}", id);
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        existing.setName(request.getName());
        existing.setStatus(request.getStatus());
        demoItemMapper.updateById(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        log.info("[DEMO] 删除演示项, id={}", id);

        // 删除前先 SELECT 确认(CLAUDE.md 5.8 第 13 条)
        DemoItemEntity existing = demoItemMapper.selectById(id);
        if (existing == null) {
            log.warn("[DEMO] 演示项不存在, id={}", id);
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        demoItemMapper.deleteById(id);
    }

    @Override
    public DemoItemResponse get(Long id) {
        log.debug("[DEMO] 查询演示项, id={}", id);

        DemoItemEntity entity = demoItemMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        return toResponse(entity);
    }

    @Override
    public PageData<DemoItemResponse> page(PageQuery query) {
        log.debug("[DEMO] 分页查询演示项, page={}, pageSize={}", query.getPage(), query.getPageSize());

        Page<DemoItemEntity> page = PageHelper.toPage(query.getPage(), query.getPageSize());
        LambdaQueryWrapper<DemoItemEntity> wrapper = new LambdaQueryWrapper<DemoItemEntity>()
                .orderByDesc(DemoItemEntity::getId);
        Page<DemoItemEntity> result = demoItemMapper.selectPage(page, wrapper);

        List<DemoItemResponse> records = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return new PageData<>(records, result.getTotal());
    }

    /**
     * 把 Entity 转成对外的 Response。
     *
     * @param entity 数据库实体，不能为 {@code null}
     * @return 响应对象，不会为 {@code null}
     */
    private DemoItemResponse toResponse(DemoItemEntity entity) {
        DemoItemResponse response = new DemoItemResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setStatus(entity.getStatus());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }
}
