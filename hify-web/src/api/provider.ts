import type { PageData, PageQuery } from '@/types/result'
import type { Provider, ProviderForm } from '@/types/provider'

/**
 * 模型提供商接口（mock 实现）。
 *
 * `hify-provider` 的真实 REST 接口还没落地，这里先用内存数组模拟后端行为，
 * 函数签名和真实接口保持一致（分页查询走 `PageData<T>`，写操作走 `Promise<void>`），
 * 等后端接口就绪后，把函数体换成 `utils/request.ts` 的 `get/post/put/del/getPage`，
 * `ProviderList.vue` 和 `HifyTable` / `HifyFormDialog` 都不需要改。
 */

/** 自增 id 从 mock 数据的最大 id 之后开始 */
let seq = 5

/** 内存里的 mock 数据，模块级变量，刷新页面即重置为初始状态 */
const mockProviders: Provider[] = [
  {
    id: 1,
    name: '公司内部 OpenAI 网关',
    type: 'OPENAI',
    apiKey: 'sk-live-51f3a9c2b8d94e2b',
    baseUrl: 'https://api.openai.com/v1',
    enabled: true,
    createdAt: '2026-06-01 10:00:00',
  },
  {
    id: 2,
    name: 'Claude 官方',
    type: 'CLAUDE',
    apiKey: 'sk-ant-api03-9f2b7c1e0a3d',
    baseUrl: 'https://api.anthropic.com',
    enabled: true,
    createdAt: '2026-06-12 14:30:00',
  },
  {
    id: 3,
    name: 'Gemini 测试环境',
    type: 'GEMINI',
    apiKey: 'AIzaSyD-mock0000000000000',
    baseUrl: 'https://generativelanguage.googleapis.com',
    enabled: false,
    createdAt: '2026-07-03 09:15:00',
  },
  {
    id: 4,
    name: '本地 Ollama',
    type: 'OLLAMA',
    apiKey: '',
    baseUrl: 'http://localhost:11434',
    enabled: true,
    createdAt: '2026-07-20 16:45:00',
  },
  {
    id: 5,
    name: '备用 OpenAI 兼容网关',
    type: 'OPENAI',
    apiKey: 'sk-compat-mock-77821f',
    baseUrl: 'https://api.deepseek.com/v1',
    enabled: false,
    createdAt: '2026-08-05 11:20:00',
  },
]

/** 模拟网络延迟，让 loading 态肉眼可见，而不是接口一闪而过 */
function delay(ms = 400): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}

/**
 * 分页查询提供商列表。
 * @param query 分页参数
 */
export async function listProviders(query: PageQuery): Promise<PageData<Provider>> {
  await delay()
  const page = query.page ?? 1
  const pageSize = query.pageSize ?? 20
  const start = (page - 1) * pageSize
  return {
    list: mockProviders.slice(start, start + pageSize),
    total: mockProviders.length,
    page,
    size: pageSize,
  }
}

/**
 * 新增提供商。
 * @param form 表单数据
 */
export async function createProvider(form: ProviderForm): Promise<void> {
  await delay()
  seq += 1
  mockProviders.unshift({ id: seq, createdAt: new Date().toLocaleString('zh-CN'), ...form })
}

/**
 * 更新提供商。
 * @param id   主键
 * @param form 表单数据
 */
export async function updateProvider(id: number, form: ProviderForm): Promise<void> {
  await delay()
  const index = mockProviders.findIndex((item) => item.id === id)
  if (index !== -1) {
    mockProviders[index] = { ...mockProviders[index], ...form }
  }
}

/**
 * 删除提供商（mock 里直接从数组移除；真实接口是软删除，见 CLAUDE.md 5.3）。
 * @param id 主键
 */
export async function deleteProvider(id: number): Promise<void> {
  await delay()
  const index = mockProviders.findIndex((item) => item.id === id)
  if (index !== -1) {
    mockProviders.splice(index, 1)
  }
}
