package com.hify.common.result;

import com.hify.common.constant.ResultConstant;
import com.hify.common.exception.ErrorCode;
import lombok.Data;

/**
 * 统一响应体。
 * <p>所有 REST 接口的返回类型，对应 CLAUDE.md 8.2 约定的 {@code code / message / data} 三段结构：
 * <pre>
 * { "code": 200, "message": "success", "data": {} }
 * </pre>
 *
 * <p>🔴 本类是 <b>HTTP 层概念</b>：{@code service} 接口一律返回裸对象，绝不包 {@code Result}。
 * 包装动作只允许发生在两个地方——{@code controller} 方法的 return，
 * 以及 {@code @RestControllerAdvice} 全局异常处理器。
 *
 * <p>分页场景不要直接用本类，用子类 {@link PageResult}。
 *
 * <p>关于日志：本类是纯数据载体，工厂方法里不打任何日志。
 * 失败的上下文由抛出 {@code BizException} 的 {@code service.impl}
 * 和统一兜底的 {@code @RestControllerAdvice} 负责记录，在这里打会重复且淹没有效信息。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    /**
     * 响应码。
     * <p>成功固定为 {@link ResultConstant#SUCCESS_CODE}；
     * 失败为四位业务错误码，号段按模块划分见 CLAUDE.md 8.5。
     */
    private Integer code;

    /**
     * 响应描述。
     * <p>成功固定为 {@link ResultConstant#SUCCESS_MESSAGE}；
     * 失败为可直接展示给用户的错误文案，不要把异常堆栈塞进来。
     */
    private String message;

    /**
     * 业务数据。
     * <p>按 CLAUDE.md 8.4 的空值约定：对象不存在时为 {@code null}；
     * 列表为空时应是 {@code []} 而不是 {@code null}，由业务侧保证。
     */
    private T data;

    /**
     * 构造一个不带数据的成功响应。
     * <p>用于新增 / 更新 / 删除这类无返回值的接口。
     *
     * @param <T> 业务数据类型，由调用处的目标类型推断
     * @return code=200、message="success"、data=null 的响应，不会为 {@code null}
     */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /**
     * 构造一个带数据的成功响应。
     *
     * @param data 业务数据，允许为 {@code null}
     * @param <T>  业务数据类型
     * @return code=200、message="success" 的响应，不会为 {@code null}
     */
    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.setCode(ResultConstant.SUCCESS_CODE);
        result.setMessage(ResultConstant.SUCCESS_MESSAGE);
        result.setData(data);
        return result;
    }

    /**
     * 构造一个失败响应，回退到 {@link ErrorCode#SYSTEM_ERROR} 的错误码。
     * <p>⚠️ 仅用于归不到任何号段的兜底场景。业务失败请用
     * {@link #fail(Integer, String)} 配合 {@link ErrorCode} 里的四位错误码。
     *
     * @param message 错误文案，不能为 {@code null}
     * @param <T>     业务数据类型
     * @return code=1000、data=null 的响应，不会为 {@code null}
     */
    public static <T> Result<T> fail(String message) {
        return fail(ErrorCode.SYSTEM_ERROR.getCode(), message);
    }

    /**
     * 构造一个失败响应。
     *
     * @param code    四位业务错误码，取自 {@link ErrorCode}，号段见 CLAUDE.md 8.5，不能为 {@code null}
     * @param message 错误文案，不能为 {@code null}
     * @param <T>     业务数据类型
     * @return data=null 的失败响应，不会为 {@code null}
     */
    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
