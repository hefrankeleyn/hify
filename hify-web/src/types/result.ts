/**
 * 与后端 `com.hify.common.result` 一一对应的响应体类型。
 * 定义依据：CLAUDE.md 8.2 / 8.3 / 8.4 / 8.5。
 */

/** 成功码，后端固定 200（ResultConstant.SUCCESS_CODE） */
export const SUCCESS_CODE = 200

/**
 * 统一响应体。
 *
 * 后端所有接口都返回这个结构，形如：
 * `{ "code": 200, "message": "success", "data": {} }`
 */
export interface Result<T = unknown> {
  /** 业务状态码：200 成功；失败时取自后端 ErrorCode 枚举（按模块分四位号段） */
  code: number
  /** 提示文案：成功固定 "success"，失败为可直接展示给用户的中文描述 */
  message: string
  /** 业务数据；无返回值的写接口为 null */
  data: T
}

/**
 * 分页响应体。
 *
 * 后端的 `PageResult<T>` 继承 `Result<List<T>>`，
 * 分页元信息拍平到与 code / message / data 同级，`data` 即当前页列表。
 */
export interface PageResult<T = unknown> extends Result<T[]> {
  /** 总条数。⚠️ 大表关闭 count 时后端固定回 0，不能拿它算总页数（CLAUDE.md 5.5） */
  total: number
  /** 当前页码，从 1 开始 */
  page: number
  /** 每页条数。⚠️ 响应侧叫 size，请求侧叫 pageSize，两侧刻意不同名 */
  size: number
}

/**
 * 分页请求参数基类。
 * 各模块的查询条件继承它，只补自己的过滤字段。
 */
export interface PageQuery {
  /** 页码，从 1 开始 */
  page?: number
  /** 每页条数，默认 20，后端上限 100（超过会被 @Max(100) 拒绝） */
  pageSize?: number
}

/**
 * 剥掉响应壳之后交给页面使用的分页数据。
 * 由 `utils/request.ts` 的 `getPage()` 产出。
 */
export interface PageData<T> {
  /** 当前页列表；后端为空时返回 []，这里同样保证非 null（CLAUDE.md 8.4） */
  list: T[]
  /** 总条数 */
  total: number
  /** 当前页码 */
  page: number
  /** 每页条数 */
  size: number
}
