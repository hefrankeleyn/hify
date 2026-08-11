package com.hify.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新 DEMO 演示项的请求参数。
 */
@Data
public class DemoItemUpdateRequest {

    /** 名称，不能为空 */
    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称长度不能超过 128")
    private String name;

    /** 状态:1 启用 0 停用，不能为空 */
    @NotNull(message = "状态不能为空")
    private Integer status;
}
