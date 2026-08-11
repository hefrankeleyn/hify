package com.hify.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DEMO 演示项详情响应。
 */
@Data
public class DemoItemResponse {

    /** 主键 id */
    private Long id;

    /** 名称 */
    private String name;

    /** 状态:1 启用 0 停用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
