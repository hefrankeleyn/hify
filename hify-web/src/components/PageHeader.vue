<script setup lang="ts">
/**
 * 页面标题区：标题 + 描述 + 右侧操作按钮，管理面各页面顶部的统一模式。
 * 操作按钮通过具名插槽 `actions` 传入，页面自己决定放几个、什么类型的按钮。
 */
withDefaults(
  defineProps<{
    /** 页面标题 */
    title: string
    /** 页面描述，选填，说明这个页面是干什么的 */
    description?: string
    /** 与下方内容的间距，默认 24px；信息密度更高的列表页可以调小，比如 16px */
    marginBottom?: string
  }>(),
  {
    marginBottom: 'var(--hify-space-6)',
  },
)
</script>

<template>
  <div class="page-header" :style="{ marginBottom }">
    <div class="page-header__text">
      <h2 class="page-header__title">{{ title }}</h2>
      <p v-if="description" class="page-header__description">{{ description }}</p>
    </div>
    <div class="page-header__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--hify-space-4);
}

.page-header__title {
  margin: 0;
  font-size: var(--hify-font-size-2xl);
  font-weight: var(--hify-font-weight-semibold);
  color: var(--hify-text-heading);
  line-height: var(--hify-line-height-tight);
}

.page-header__description {
  margin: var(--hify-space-2) 0 0;
  font-size: var(--hify-font-size-sm);
  color: var(--hify-text-muted);
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: var(--hify-space-3);
  flex-shrink: 0;
}
</style>
