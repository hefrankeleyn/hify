package com.hify.common.constant;

import lombok.experimental.UtilityClass;

/**
 * 统一响应体常量。
 * <p>存在的唯一理由是兜住 CLAUDE.md 3.2 的红线「不允许魔法值直接出现在代码中」——
 * 成功码、成功文案、通用失败码都不许在 {@code Result} 的工厂方法里写字面量。
 * <p>🔴 本类只装响应体相关的常量，不要退化成全局常量类（3.2：按模块、按功能分类）。
 */
@UtilityClass
public class ResultConstant {

    /**
     * 成功响应码。
     * <p>与 CLAUDE.md 8.2 的报文样例一致，固定 200。
     * <p>注意它不属于 8.5 的四位业务错误码体系——成功不是错误，不占任何模块号段。
     */
    public static final Integer SUCCESS_CODE = 200;

    /** 成功响应文案，固定 "success"，前端只看 code 不看它 */
    public static final String SUCCESS_MESSAGE = "success";

    // 注意:这里没有「通用失败码」常量。失败码的唯一定义源是
    // com.hify.common.exception.ErrorCode 枚举,不要在本类里再抄一份。
}
