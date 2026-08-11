import { createPinia } from 'pinia'

/**
 * Pinia 实例。
 * 在 `main.ts` 里 `app.use(pinia)` 注册，各 store 文件直接 `defineStore` 即可。
 */
export const pinia = createPinia()

export default pinia
