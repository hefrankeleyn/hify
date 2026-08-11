<script setup lang="ts" generic="T extends object">
import { nextTick, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'

/**
 * 通用表单弹窗组件。
 *
 * 只负责弹窗壳子（标题 / 宽度 / 表单容器 / 提交态 / 关闭重置）；具体的表单项由父组件通过默认插槽传入，
 * 插槽会把响应式的表单数据 `formModel` 交出去，父组件的 `<el-form-item>` 直接绑它就行：
 * ```html
 * <HifyFormDialog ref="dialogRef" v-model="visible" title="新增提供商" :default-value="defaultForm" @submit="handleSubmit">
 *   <template #default="{ formModel }">
 *     <el-form-item label="名称" prop="name">
 *       <el-input v-model="formModel.name" />
 *     </el-form-item>
 *   </template>
 * </HifyFormDialog>
 * ```
 * 提交本身不在组件内部完成——校验通过后把数据通过 `submit` 事件交给父组件调用真正的 API，
 * 父组件调用完（不管成功失败）都要执行事件参数里的 `done(success)`，组件才知道要不要收起 loading、要不要关闭弹窗。
 */
const props = withDefaults(
  defineProps<{
    /** 弹窗显隐，配合 v-model 使用 */
    modelValue: boolean
    title: string
    /** 弹窗宽度，默认对齐 Element Plus 的 500px */
    width?: string | number
    rules?: FormRules
    /** 表单 label 宽度，管理面表单统一 100px */
    labelWidth?: string
    /** 新增模式下表单的初始值，也是「关闭后重置」要回到的状态 */
    defaultValue: T
  }>(),
  {
    width: '500px',
    labelWidth: '100px',
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 表单校验通过后触发；父组件处理完 API 调用要调用 done(success) 告知结果 */
  (e: 'submit', data: T, done: (success: boolean) => void): void
}>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const formModel = reactive({ ...props.defaultValue }) as T

/**
 * 打开弹窗。
 * @param data 编辑模式下传入的行数据；不传即为新增模式，表单用 defaultValue 兜底
 */
function open(data?: Partial<T>): void {
  Object.assign(formModel, props.defaultValue, data ?? {})
  emit('update:modelValue', true)
  // clearValidate 要等表单实际渲染出来才有效，nextTick 之后 el-form 已经拿到新的 formModel
  void nextTick(() => formRef.value?.clearValidate())
}

function handleCancel(): void {
  emit('update:modelValue', false)
}

/** 弹窗完全关闭（过渡动画结束）后再重置表单，避免关闭过程中用户看到内容瞬间清空 */
function handleClosed(): void {
  submitting.value = false
  Object.assign(formModel, props.defaultValue)
  formRef.value?.clearValidate()
}

async function handleConfirm(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  emit('submit', { ...formModel } as T, (success: boolean) => {
    submitting.value = false
    if (success) {
      emit('update:modelValue', false)
    }
  })
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    :width="width"
    :close-on-click-modal="false"
    append-to-body
    class="hify-form-dialog"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
    @closed="handleClosed"
  >
    <el-form ref="formRef" :model="formModel" :rules="rules" :label-width="labelWidth" label-position="right">
      <slot :form-model="formModel" />
    </el-form>

    <template #footer>
      <el-button @click="handleCancel">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>
