<script setup lang="ts">
import { NButton } from 'naive-ui';

/**
 * 表格批量操作组：勾选了行才出现，跟刷新按钮挤在同一行（放在 `.toolbar-right` 里）。
 *
 * 只做展示与抛事件，删除确认由调用方负责——三个列表（模板 / 生成记录 / 数据块）
 * 的删除文案和后果不一样，塞进组件里就得加一堆 props。
 */
defineProps<{
  /** 已选条数 */
  count: number;
  /** 名词，用来拼「已选 3 个模板」 */
  label?: string;
}>();

const emit = defineEmits<{
  (e: 'remove'): void;
  (e: 'clear'): void;
}>();
</script>

<template>
  <div class="batch-inline">
    <span class="batch-inline__text">
      已选 <b>{{ count }}</b> 个{{ label ?? '条目' }}
    </span>
    <NButton size="small" type="error" secondary @click="emit('remove')">批量删除</NButton>
    <NButton size="small" quaternary @click="emit('clear')">取消选择</NButton>
  </div>
</template>
