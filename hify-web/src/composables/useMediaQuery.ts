import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * 包一层 `window.matchMedia`，返回响应式布尔值，用于组件内做断点判断。
 *
 * 用 `matchMedia` 的 change 事件而不是监听 `resize`——只在查询结果真正跨越断点时才更新，
 * 不会在用户拖拽窗口时逐像素触发重渲染。
 *
 * 目前的使用方：`App.vue`（视口 < 1200px 时侧边栏自动折叠为图标模式）、
 * `ProviderList.vue`（同一断点下表格隐藏次要列）——两处需要同一个断点判断，抽出来避免重复监听。
 *
 * @param query CSS 媒体查询字符串，如 `'(max-width: 1199px)'`
 */
export function useMediaQuery(query: string): Ref<boolean> {
  const mediaQueryList = window.matchMedia(query)
  const matches = ref(mediaQueryList.matches)

  function handleChange(event: MediaQueryListEvent): void {
    matches.value = event.matches
  }

  onMounted(() => mediaQueryList.addEventListener('change', handleChange))
  onBeforeUnmount(() => mediaQueryList.removeEventListener('change', handleChange))

  return matches
}
