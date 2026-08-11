/**
 * 模型提供商相关类型。
 * 对应后端 `hify-provider` 模块的 `model_provider` 表（CLAUDE.md 5.1）。
 */

/** 模型提供商类型 */
export type ProviderType = 'OPENAI' | 'CLAUDE' | 'GEMINI' | 'OLLAMA'

/** 提供商类型下拉选项：value 对应后端枚举值，label 是展示文案 */
export const PROVIDER_TYPE_OPTIONS: Array<{ label: string; value: ProviderType }> = [
  { label: 'OpenAI', value: 'OPENAI' },
  { label: 'Claude', value: 'CLAUDE' },
  { label: 'Gemini', value: 'GEMINI' },
  { label: 'Ollama', value: 'OLLAMA' },
]

/**
 * 模型提供商。
 * `hify-provider` 的真实接口还没落地，目前由 `api/provider.ts` 的 mock 实现产出这个形状的数据。
 */
export interface Provider {
  id: number
  /** 提供商名称，用户自定义，如「公司内部 OpenAI 网关」 */
  name: string
  /** 提供商类型 */
  type: ProviderType
  /** API Key。管理面回显真实接口落地后应做脱敏，mock 阶段暂不处理 */
  apiKey: string
  /** 请求基地址，如 https://api.openai.com/v1 */
  baseUrl: string
  /** 是否启用 */
  enabled: boolean
  /** 创建时间 */
  createdAt: string
}

/** Provider 表单载荷：新增 / 编辑共用，id 和 createdAt 由后端生成，不在表单里 */
export type ProviderForm = Omit<Provider, 'id' | 'createdAt'>
