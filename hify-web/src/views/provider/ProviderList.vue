<script setup lang="ts">
/**
 * 模型提供商管理页。
 *
 * `hify-provider` 的真实 REST 接口还没落地，列表 / 增删改全部走 `api/provider.ts` 的 mock 实现，
 * 写法和真实接口完全一致（`HifyTable` 的 `api` 直接对接 `PageData<T>`），
 * 接口就绪后只需要把 `api/provider.ts` 内部换成真实请求，这个页面不用改。
 */
import { computed, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'
import HifyTable, { type HifyTableColumn } from '@/components/HifyTable.vue'
import HifyFormDialog from '@/components/HifyFormDialog.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useMediaQuery } from '@/composables/useMediaQuery'
import { notifySuccess } from '@/utils/notify'
import { createProvider, deleteProvider, listProviders, updateProvider } from '@/api/provider'
import { PROVIDER_TYPE_OPTIONS, type Provider, type ProviderForm, type ProviderType } from '@/types/provider'

/*
 * HifyTable / HifyFormDialog 是泛型组件，`InstanceType<typeof Comp>` 在 vue-tsc 里推不出泛型实参，
 * 直接按 defineExpose 出来的形状声明 ref 类型，绕开这个已知的类型工具限制。
 */
const tableRef = ref<{ refresh: () => void } | null>(null)
const dialogRef = ref<{ open: (data?: Partial<ProviderForm>) => void } | null>(null)

/** 表单弹窗显隐；打开 / 关闭主要由 HifyFormDialog 内部通过 v-model 驱动 */
const formVisible = ref(false)

/** 当前正在编辑的行；新增时为 null，用来决定弹窗标题、以及提交时走新增还是编辑 */
const editingRow = ref<Provider | null>(null)

const dialogTitle = computed(() => (editingRow.value ? '编辑提供商' : '新增提供商'))

/** 新增模式下表单的初始值 */
const defaultFormValue: ProviderForm = {
  name: '',
  type: 'OPENAI',
  apiKey: '',
  baseUrl: '',
  enabled: true,
}

const rules: FormRules<ProviderForm> = {
  name: [{ required: true, message: '请输入提供商名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择提供商类型', trigger: 'change' }],
  apiKey: [{ required: true, message: '请输入 API Key', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }],
}

/** 窄屏（< 1200px）下隐藏次要列，只保留名称 / 类型 / 状态 / 操作 —— 断点和侧边栏折叠共用同一个 */
const isNarrowScreen = useMediaQuery('(max-width: 1199px)')
const NARROW_HIDDEN_PROPS = new Set(['baseUrl', 'createdAt'])

const columns = computed<HifyTableColumn[]>(() => {
  const all: HifyTableColumn[] = [
    { label: '名称', prop: 'name', minWidth: 160 },
    { label: '类型', prop: 'type', slot: 'type', width: 110 },
    { label: 'Base URL', prop: 'baseUrl', minWidth: 220, showOverflowTooltip: true },
    { label: '状态', prop: 'enabled', slot: 'status', width: 90, align: 'center' },
    { label: '创建时间', prop: 'createdAt', width: 170 },
    { label: '操作', slot: 'actions', width: 140, align: 'center' },
  ]
  return isNarrowScreen.value ? all.filter((column) => !NARROW_HIDDEN_PROPS.has(column.prop ?? '')) : all
})

/** 类型枚举值 → 展示文案 */
function getProviderTypeLabel(type: ProviderType): string {
  return PROVIDER_TYPE_OPTIONS.find((option) => option.value === type)?.label ?? type
}

function handleCreate(): void {
  editingRow.value = null
  dialogRef.value?.open()
}

function handleEdit(row: Provider): void {
  editingRow.value = row
  dialogRef.value?.open(row)
}

/**
 * HifyFormDialog 校验通过后触发；这里才是真正调用（mock）API 的地方。
 * @param form 表单数据
 * @param done 告知弹窗提交结果：true 关闭弹窗，false 停留在表单上让用户改
 */
async function handleSubmit(form: ProviderForm, done: (success: boolean) => void): Promise<void> {
  try {
    if (editingRow.value) {
      await updateProvider(editingRow.value.id, form)
    } else {
      await createProvider(form)
    }
    notifySuccess(editingRow.value ? '保存成功' : '新增成功')
    done(true)
    tableRef.value?.refresh()
  } catch (error) {
    console.warn('[ProviderList] 保存提供商失败', error)
    done(false)
  }
}

async function handleDelete(row: Provider): Promise<void> {
  const deleted = await useConfirm(
    `确定删除提供商「${row.name}」吗？删除后不可恢复。`,
    () => deleteProvider(row.id),
    '删除成功',
  )
  if (deleted) {
    tableRef.value?.refresh()
  }
}
</script>

<template>
  <div>
    <PageHeader
      title="模型提供商管理"
      description="管理接入的模型提供商（OpenAI / Claude / Gemini / Ollama）与其下的可用模型"
      margin-bottom="var(--hify-space-4)"
    >
      <template #actions>
        <el-button type="primary" @click="handleCreate">
          <el-icon><Plus /></el-icon>
          新增提供商
        </el-button>
      </template>
    </PageHeader>

    <HifyTable ref="tableRef" class="hify-card" :columns="columns" :api="listProviders">
      <template #type="{ row }">
        {{ getProviderTypeLabel((row as Provider).type) }}
      </template>

      <template #status="{ row }">
        <el-tag :type="(row as Provider).enabled ? 'success' : 'info'">
          {{ (row as Provider).enabled ? '启用' : '禁用' }}
        </el-tag>
      </template>

      <template #actions="{ row }">
        <el-button type="primary" text @click="handleEdit(row as Provider)">编辑</el-button>
        <el-button type="danger" text class="action-delete" @click="handleDelete(row as Provider)">删除</el-button>
      </template>
    </HifyTable>

    <HifyFormDialog
      ref="dialogRef"
      v-model="formVisible"
      :title="dialogTitle"
      width="520px"
      :rules="rules"
      :default-value="defaultFormValue"
      @submit="handleSubmit"
    >
      <template #default="{ formModel }">
        <el-form-item label="名称" prop="name">
          <el-input v-model="(formModel as ProviderForm).name" placeholder="请输入提供商名称" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="(formModel as ProviderForm).type" placeholder="请选择类型" style="width: 100%">
            <el-option
              v-for="option in PROVIDER_TYPE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="(formModel as ProviderForm).apiKey" type="password" show-password placeholder="sk-..." />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="(formModel as ProviderForm).baseUrl" placeholder="https://api.openai.com/v1" />
        </el-form-item>
      </template>
    </HifyFormDialog>
  </div>
</template>

<style scoped>
.action-delete {
  margin-left: var(--hify-space-2);
}
</style>
