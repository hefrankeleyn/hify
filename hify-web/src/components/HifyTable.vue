<script lang="ts">
/**
 * 列配置。
 * `slot` 传了就用同名具名插槽渲染单元格（配合 `<template #列名="{ row }">` 使用，
 * 状态标签、操作按钮这类需要自定义渲染的列都走这个）；不传就展示 `row[prop]` 的原始值。
 */
export interface HifyTableColumn {
  /** 表头文案 */
  label: string
  /** 对应字段名；只用插槽渲染、不需要展示原始字段值的列（如"操作"列）可以不传 */
  prop?: string
  width?: string | number
  minWidth?: string | number
  align?: 'left' | 'center' | 'right'
  /** 具名插槽名 */
  slot?: string
  /** 内容超长时是否省略并 hover 提示完整内容 */
  showOverflowTooltip?: boolean
}
</script>

<script setup lang="ts" generic="T extends object">
import { computed, onMounted, ref } from 'vue'
import type { PageData, PageQuery } from '@/types/result'

/**
 * 通用列表页表格组件。
 *
 * 内部自管 loading / 分页参数 / 数据请求，页面只需要提供 `columns` 配置和一个分页查询方法。
 * `api` 的返回值直接对接 `utils/request.ts` 的 `getPage()` 产出的 `PageData<T>`——
 * 真实接口落地后，页面把 `api` 换成 `(query) => getPage<T>('/v1/xxx', query)` 就能直接用，
 * HifyTable 本身不用改。
 */
const props = withDefaults(
  defineProps<{
    /** 列配置 */
    columns: HifyTableColumn[]
    /** 分页查询方法 */
    api: (query: PageQuery) => Promise<PageData<T>>
    /** 是否展示分页，默认展示 */
    showPagination?: boolean
    /** 每页条数，默认 20，对齐 CLAUDE.md 8.3 的默认值 */
    pageSize?: number
    /** 行高（px）。管理面表格统一给 52，比 Element Plus 默认的行高更利于信息密度和可读性的平衡 */
    rowHeight?: number
  }>(),
  {
    showPagination: true,
    pageSize: 20,
    rowHeight: 52,
  },
)

const loading = ref(false)
const list = ref<T[]>([])
const total = ref(0)
const page = ref(1)

const rowStyle = computed(() => ({ height: `${props.rowHeight}px` }))

/**
 * 按当前页码 / 每页条数拉一次数据。
 * 失败时不额外处理——真实接口的失败提示由 `utils/request.ts` 的拦截器统一弹出，
 * mock 接口目前也不会失败；这里只保证 `loading` 一定会被复位。
 */
async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await props.api({ page: page.value, pageSize: props.pageSize })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

/** 重置到第一页并重新拉取；新增 / 编辑 / 删除成功后调这个 */
function refresh(): void {
  page.value = 1
  void load()
}

function handlePageChange(nextPage: number): void {
  page.value = nextPage
  void load()
}

onMounted(load)

defineExpose({ refresh })
</script>

<template>
  <div class="hify-table">
    <el-table v-loading="loading" :data="list" :row-style="rowStyle" class="hify-table__el-table">
      <el-table-column
        v-for="column in columns"
        :key="column.prop ?? column.slot ?? column.label"
        :label="column.label"
        :prop="column.prop"
        :width="column.width"
        :min-width="column.minWidth"
        :align="column.align"
        :show-overflow-tooltip="column.showOverflowTooltip"
      >
        <template v-if="column.slot" #default="scope">
          <slot :name="column.slot" :row="scope.row" :index="scope.$index" />
        </template>
      </el-table-column>

      <template #empty>
        <el-empty description="暂无数据" />
      </template>
    </el-table>

    <div v-if="showPagination" class="hify-table__pagination">
      <el-pagination
        layout="total, prev, pager, next"
        :current-page="page"
        :page-size="pageSize"
        :total="total"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<style scoped>
.hify-table {
  /* 表头背景改浅灰、行悬浮态改用与卡片一致的 hover 色，只在这个组件的作用域内覆盖，
     不影响项目里其它可能有不同诉求的表格 */
  --el-table-header-bg-color: var(--hify-bg-canvas);
  --el-table-row-hover-bg-color: var(--hify-bg-surface-hover);
}

.hify-table__pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--hify-space-4);
  padding-top: var(--hify-space-4);
  border-top: 1px solid var(--hify-border-subtle);
}
</style>
