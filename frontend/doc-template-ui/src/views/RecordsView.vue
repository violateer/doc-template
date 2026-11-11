<script setup lang="ts">
import { h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import {
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NIcon,
  NInput,
  NSelect,
  NTag,
  NTooltip,
  useDialog,
  useMessage
} from 'naive-ui';
import { RefreshOutline, SearchOutline } from '@vicons/ionicons5';
import { api, downloadFile, recordDownloadUrl, recordPreviewUrl } from '../api';
import type { PageResult, RecordRow } from '../types';
import { batchDeleteSummary, formatTime, isInteractiveClick } from '../utils';
import type { BatchDeleteResult } from '../utils';
import { statusMeta } from '../theme';
import { rowActions } from '../components/rowActions';
import FilePreviewModal from '../components/FilePreviewModal.vue';
import BatchBar from '../components/BatchBar.vue';

const message = useMessage();
const dialog = useDialog();
const records = ref<RecordRow[]>([]);
const loading = ref(false);
const keyword = ref('');
const statusFilter = ref<string | null>(null);
/** 勾选的行主键。翻页 / 改筛选 / 刷新都会清空，避免「选了看不见的行」 */
const checkedKeys = ref<number[]>([]);

const previewShow = ref(false);
const previewSrc = ref('');
const previewName = ref('');
const previewDownloadSrc = ref('');

const statusOptions = [
  { label: '全部状态', value: 'ALL' },
  { label: '生成成功', value: 'SUCCESS' },
  { label: '生成失败', value: 'FAILED' }
];

/** 服务端分页：默认每页 20 条 */
const pagination = reactive({
  page: 1,
  pageSize: 20,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [20, 50, 100],
  prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 条`,
  onChange: (page: number) => {
    pagination.page = page;
    void load();
  },
  onUpdatePageSize: (size: number) => {
    pagination.pageSize = size;
    pagination.page = 1;
    void load();
  }
});

async function load() {
  loading.value = true;
  // 列表换了（翻页 / 筛选 / 刷新 / 删除后重载），之前勾的行可能已经不在这一页了
  checkedKeys.value = [];
  try {
    const query = new URLSearchParams({
      page: String(pagination.page),
      size: String(pagination.pageSize)
    });
    if (keyword.value.trim()) query.set('keyword', keyword.value.trim());
    if (statusFilter.value && statusFilter.value !== 'ALL') query.set('status', statusFilter.value);
    const data = await api<PageResult<RecordRow>>(`/generate-records?${query.toString()}`);
    records.value = data.records ?? [];
    pagination.itemCount = data.total ?? records.value.length;
    // 删到当前页空了就退回上一页
    if (!records.value.length && pagination.page > 1) {
      pagination.page -= 1;
      await load();
    }
  } catch (e: any) {
    message.error(e.message);
  } finally {
    loading.value = false;
  }
}

let keywordTimer: ReturnType<typeof setTimeout> | null = null;
watch(keyword, () => {
  if (keywordTimer) clearTimeout(keywordTimer);
  keywordTimer = setTimeout(() => {
    pagination.page = 1;
    void load();
  }, 300);
});

watch(statusFilter, () => {
  pagination.page = 1;
  void load();
});

onBeforeUnmount(() => {
  if (keywordTimer) clearTimeout(keywordTimer);
});

function openPreview(row: RecordRow) {
  if (!row.has_result_file) return;
  previewSrc.value = recordPreviewUrl(row.id);
  previewDownloadSrc.value = recordDownloadUrl(row.id);
  previewName.value = row.result_file_name ?? '生成文件';
  previewShow.value = true;
}

async function download(row: RecordRow) {
  try {
    await downloadFile(recordDownloadUrl(row.id), row.result_file_name ?? 'document.docx');
  } catch (e: any) {
    message.error(e.message);
  }
}

async function remove(row: RecordRow) {
  try {
    await api(`/generate-records/${row.id}`, { method: 'DELETE' });
    message.success('记录及生成文件已删除');
    await load();
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmRemove(row: RecordRow) {
  dialog.warning({
    title: '删除生成记录',
    content: `将删除记录 #${row.id} 及其已生成的文件，且不可恢复。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => remove(row)
  });
}

/** 批量删除：后端逐条删，单条失败不影响其他条，结果按「成功 n / 失败 m」提示 */
async function batchRemove() {
  const ids = [...checkedKeys.value];
  if (!ids.length) return;
  try {
    const res = await api<BatchDeleteResult>('/generate-records/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ ids })
    });
    checkedKeys.value = [];
    await load();
    const summary = batchDeleteSummary('生成记录', res);
    message[summary.type](summary.text);
  } catch (e: any) {
    message.error(e.message);
  }
}

/** 取消勾选：批量操作条上的「取消选择」 */
function clearSelection() {
  checkedKeys.value = [];
}

function isRowSelected(row: RecordRow): boolean {
  return checkedKeys.value.includes(row.id);
}

/** 点行 = 勾选 / 取消勾选；勾选框和操作列按钮的点击不接管（它们自己会处理） */
function toggleRow(row: RecordRow, event: MouseEvent) {
  if (isInteractiveClick(event)) return;
  checkedKeys.value = isRowSelected(row)
    ? checkedKeys.value.filter((key) => key !== row.id)
    : [...checkedKeys.value, row.id];
}

const rowProps = (row: RecordRow) => ({
  style: 'cursor: pointer;',
  onClick: (event: MouseEvent) => toggleRow(row, event)
});

const rowClassName = (row: RecordRow) => (isRowSelected(row) ? 'is-checked' : '');

function confirmBatchRemove() {
  const count = checkedKeys.value.length;
  if (!count) return;
  dialog.warning({
    title: '批量删除生成记录',
    content: `将删除选中的 ${count} 条生成记录及其已生成的文件，且不可恢复。`,
    positiveText: `删除 ${count} 条`,
    negativeText: '取消',
    onPositiveClick: () => batchRemove()
  });
}

const columns = [
  // 勾选列固定在左侧，横向滚动时始终可见
  { type: 'selection' as const, fixed: 'left' as const, width: 44 },
  { title: '记录', key: 'id', width: 90, render: (row: RecordRow) => h('span', { class: 'mono' }, `#${row.id}`) },
  {
    title: '模板',
    key: 'template_name',
    minWidth: 200,
    render: (row: RecordRow) =>
      h('div', null, [
        h('div', { class: 'cell-title' }, row.template_name ?? `模板 #${row.template_id}`),
        h('div', { class: 'cell-sub mono' }, row.result_file_name ?? '无生成文件')
      ])
  },
  {
    title: '版本',
    key: 'template_version_id',
    width: 90,
    render: (row: RecordRow) =>
      row.template_version_id ? h('span', { class: 'mono muted' }, `#${row.template_version_id}`) : '—'
  },
  {
    title: '导出格式',
    key: 'export_type',
    width: 110,
    render: (row: RecordRow) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.export_type === 'PDF' ? 'error' : 'default' },
        { default: () => (row.export_type === 'PDF' ? 'PDF' : '原格式') }
      )
  },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: (row: RecordRow) => {
      const meta = statusMeta(row.status);
      return h(NTag, { size: 'small', bordered: false, type: meta.type }, { default: () => meta.label });
    }
  },
  {
    title: '说明',
    key: 'error_message',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row: RecordRow) =>
      h('span', { class: row.error_message ? 'muted' : 'muted' }, row.error_message ?? '—')
  },
  {
    title: '生成人',
    key: 'create_by',
    width: 110,
    render: (row: RecordRow) => h('span', { class: 'muted' }, row.create_by ?? '—')
  },
  {
    title: '生成时间',
    key: 'create_time',
    width: 160,
    render: (row: RecordRow) => h('span', { class: 'muted' }, formatTime(row.create_time))
  },
  {
    title: '操作',
    key: 'actions',
    width: 200,
    fixed: 'right' as const,
    render: (row: RecordRow) =>
      rowActions([
        { label: '预览', tone: 'info', disabled: !row.has_result_file, onClick: () => openPreview(row) },
        { label: '下载', tone: 'success', disabled: !row.has_result_file, onClick: () => download(row) },
        { label: '删除', tone: 'error', onClick: () => confirmRemove(row) }
      ])
  }
];

onMounted(load);
</script>

<template>
  <div class="view">
    <NCard :bordered="false" class="table-card" content-style="flex:1;min-height:0;display:flex;flex-direction:column;">
      <div class="toolbar">
        <div class="toolbar-left">
          <NInput v-model:value="keyword" placeholder="搜索记录号 / 模板 / 文件名" clearable style="width: 260px">
            <template #prefix>
              <NIcon color="#a3adbd"><SearchOutline /></NIcon>
            </template>
          </NInput>
          <NSelect v-model:value="statusFilter" :options="statusOptions" placeholder="全部状态" clearable style="width: 140px" />
        </div>
        <div class="toolbar-right">
          <BatchBar
            v-if="checkedKeys.length"
            :count="checkedKeys.length"
            label="生成记录"
            @remove="confirmBatchRemove"
            @clear="clearSelection"
          />
          <NTooltip trigger="hover">
            <template #trigger>
              <NButton circle quaternary @click="load">
                <template #icon>
                  <NIcon><RefreshOutline /></NIcon>
                </template>
              </NButton>
            </template>
            刷新
          </NTooltip>
        </div>
      </div>

      <NDataTable
        remote
        flex-height
        paginate-single-page
        :columns="columns"
        :data="records"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: RecordRow) => row.id"
        :checked-row-keys="checkedKeys"
        :row-props="rowProps"
        :row-class-name="rowClassName"
        :bordered="false"
        size="small"
        :scroll-x="1324"
        style="flex: 1; min-height: 0"
        @update:checked-row-keys="(keys: Array<string | number>) => (checkedKeys = keys as number[])"
      >
        <template #empty>
          <NEmpty description="暂无生成记录，可在模板页使用「模拟生成」" />
        </template>
      </NDataTable>
    </NCard>

    <FilePreviewModal
      v-model:show="previewShow"
      :src="previewSrc"
      :download-src="previewDownloadSrc"
      :filename="previewName"
    />
  </div>
</template>
