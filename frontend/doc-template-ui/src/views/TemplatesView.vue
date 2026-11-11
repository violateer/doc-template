<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
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
import {
  AddOutline,
  AlbumsOutline,
  CheckmarkCircleOutline,
  CloudUploadOutline,
  FlashOutline,
  RefreshOutline,
  SearchOutline
} from '@vicons/ionicons5';
import { api, downloadFile, downloadUrl, previewUrl } from '../api';
import type { PageResult, RecordRow, TemplateRow } from '../types';
import { batchDeleteSummary, confirmDelete, formatTime, isInteractiveClick } from '../utils';
import type { BatchDeleteResult } from '../utils';
import { statusMeta } from '../theme';
import { rowActions } from '../components/rowActions';
import TemplateEditModal from '../components/TemplateEditModal.vue';
import GenerateModal from '../components/GenerateModal.vue';
import FilePreviewModal from '../components/FilePreviewModal.vue';
import BatchBar from '../components/BatchBar.vue';
import AiImportModal from '../components/AiImportModal.vue';

const message = useMessage();
const dialog = useDialog();
const rows = ref<TemplateRow[]>([]);
const loading = ref(false);
const keyword = ref('');
const statusFilter = ref<string | null>(null);
/** 勾选的行主键。翻页 / 改筛选 / 刷新都会清空，避免「选了看不见的行」 */
const checkedKeys = ref<number[]>([]);
/** 统计卡片的数据来自独立接口，不受当前页影响 */
const overview = ref({ total: 0, enabled: 0, uploaded: 0, records: 0 });

const editShow = ref(false);
const editing = ref<TemplateRow | null>(null);
const generateShow = ref(false);
const generatingTemplate = ref<TemplateRow | null>(null);
const importShow = ref(false);

const previewShow = ref(false);
const previewSrc = ref('');
const previewName = ref('');
const previewDownloadSrc = ref('');

const statusOptions = [
  { label: '全部状态', value: 'ALL' },
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' }
];

/** 服务端分页：默认每页 10 条 */
const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 50, 100],
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

const stats = computed(() => [
  { label: '模板总数', value: overview.value.total, icon: AlbumsOutline, color: '#2f6fed', bg: '#eaf1ff' },
  { label: '启用中', value: overview.value.enabled, icon: CheckmarkCircleOutline, color: '#18a058', bg: '#e8f7ef' },
  { label: '已上传文件', value: overview.value.uploaded, icon: CloudUploadOutline, color: '#7c4dff', bg: '#f1ecff' },
  { label: '生成记录', value: overview.value.records, icon: FlashOutline, color: '#f0a020', bg: '#fff5e6' }
]);

async function loadStats() {
  try {
    const [counts, recordPage] = await Promise.all([
      api<{ total: number; enabled: number; uploaded: number }>('/templates/stats'),
      api<PageResult<RecordRow>>('/generate-records?page=1&size=1').catch(() => null)
    ]);
    overview.value = {
      total: counts.total ?? 0,
      enabled: counts.enabled ?? 0,
      uploaded: counts.uploaded ?? 0,
      records: recordPage?.total ?? 0
    };
  } catch {
    /* 统计失败不影响列表 */
  }
}

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
    const data = await api<PageResult<TemplateRow>>(`/templates?${query.toString()}`);
    rows.value = data.records ?? [];
    pagination.itemCount = data.total ?? rows.value.length;
    // 删到当前页空了就退回上一页
    if (!rows.value.length && pagination.page > 1) {
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

function openCreate() {
  editing.value = null;
  editShow.value = true;
}

function openEdit(row: TemplateRow) {
  editing.value = row;
  editShow.value = true;
}

function openGenerate(row: TemplateRow) {
  generatingTemplate.value = row;
  generateShow.value = true;
}

function openPreview(row: TemplateRow) {
  if (!row.current_version_id) {
    message.warning('该模板还没有上传文件');
    return;
  }
  previewSrc.value = previewUrl(row.current_version_id);
  previewDownloadSrc.value = downloadUrl(row.current_version_id);
  previewName.value = `${row.template_name}.${row.file_type.toLowerCase()}`;
  previewShow.value = true;
}

async function downloadTemplate(row: TemplateRow) {
  if (!row.current_version_id) {
    message.warning('该模板还没有上传文件');
    return;
  }
  try {
    await downloadFile(downloadUrl(row.current_version_id), `${row.template_name}.${row.file_type.toLowerCase()}`);
  } catch (e: any) {
    message.error(e.message);
  }
}

async function remove(row: TemplateRow) {
  try {
    await api(`/templates/${row.id}`, { method: 'DELETE' });
    message.success('模板及云存储文件已删除');
    await load();
    await loadStats();
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmRemove(row: TemplateRow) {
  confirmDelete(dialog, {
    title: '删除模板',
    content: `删除模板「${row.template_name}」将同时移除其版本、数据块、生成记录及云存储文件，且不可恢复。`,
    action: () => remove(row)
  });
}

/** 批量删除：后端逐条删，单条失败不影响其他条，结果按「成功 n / 失败 m」提示 */
async function batchRemove() {
  const ids = [...checkedKeys.value];
  if (!ids.length) return;
  try {
    const res = await api<BatchDeleteResult>('/templates/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ ids })
    });
    checkedKeys.value = [];
    await load();
    await loadStats();
    const summary = batchDeleteSummary('模板', res);
    message[summary.type](summary.text);
  } catch (e: any) {
    message.error(e.message);
  }
}

/** 取消勾选：批量操作条上的「取消选择」 */
function clearSelection() {
  checkedKeys.value = [];
}

function isRowSelected(row: TemplateRow): boolean {
  return checkedKeys.value.includes(row.id);
}

/** 点行 = 勾选 / 取消勾选；勾选框和操作列按钮的点击不接管（它们自己会处理） */
function toggleRow(row: TemplateRow, event: MouseEvent) {
  if (isInteractiveClick(event)) return;
  checkedKeys.value = isRowSelected(row)
    ? checkedKeys.value.filter((key) => key !== row.id)
    : [...checkedKeys.value, row.id];
}

const rowProps = (row: TemplateRow) => ({
  style: 'cursor: pointer;',
  onClick: (event: MouseEvent) => toggleRow(row, event)
});

const rowClassName = (row: TemplateRow) => (isRowSelected(row) ? 'is-checked' : '');

function confirmBatchRemove() {
  const count = checkedKeys.value.length;
  if (!count) return;
  confirmDelete(dialog, {
    title: '批量删除模板',
    content: `将删除选中的 ${count} 个模板，连同它们的版本、数据块、生成记录及云存储文件，且不可恢复。`,
    positiveText: `删除 ${count} 个`,
    action: () => batchRemove()
  });
}

async function onSaved() {
  await load();
  await loadStats();
}

const columns = [
  // 勾选列固定在左侧，横向滚动时始终可见
  { type: 'selection' as const, fixed: 'left' as const, width: 44 },
  {
    title: '模板',
    key: 'template_name',
    minWidth: 230,
    render: (row: TemplateRow) =>
      h('div', null, [
        h('div', { class: 'cell-title' }, row.template_name),
        h('div', { class: 'cell-sub mono' }, row.template_code)
      ])
  },
  {
    title: '文件类型',
    key: 'file_type',
    width: 110,
    render: (row: TemplateRow) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.file_type === 'XLSX' ? 'warning' : 'info' },
        { default: () => row.file_type }
      )
  },
  {
    title: '导出格式',
    key: 'export_type',
    width: 110,
    render: (row: TemplateRow) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.export_type === 'PDF' ? 'error' : 'default' },
        { default: () => (row.export_type === 'PDF' ? 'PDF' : '原格式') }
      )
  },
  {
    title: '文件',
    key: 'file',
    width: 120,
    render: (row: TemplateRow) =>
      row.current_version_id
        ? h(NTag, { size: 'small', bordered: false, type: 'success' }, { default: () => '已上传' })
        : h(NTag, { size: 'small', bordered: false }, { default: () => '待上传' })
  },
  {
    title: '状态',
    key: 'status',
    width: 96,
    render: (row: TemplateRow) => {
      const meta = statusMeta(row.status);
      return h(NTag, { size: 'small', bordered: false, type: meta.type }, { default: () => meta.label });
    }
  },
  {
    title: '更新时间',
    key: 'update_time',
    width: 160,
    render: (row: TemplateRow) => h('span', { class: 'muted' }, formatTime(row.update_time ?? row.create_time))
  },
  {
    title: '操作',
    key: 'actions',
    width: 268,
    fixed: 'right' as const,
    render: (row: TemplateRow) =>
      rowActions([
        { label: '编辑', tone: 'primary', onClick: () => openEdit(row) },
        { label: '模拟生成', tone: 'warning', disabled: !row.current_version_id, onClick: () => openGenerate(row) },
        { label: '预览', tone: 'info', disabled: !row.current_version_id, onClick: () => openPreview(row) },
        { label: '删除', tone: 'error', onClick: () => confirmRemove(row) }
      ])
  }
];

onMounted(() => {
  void load();
  void loadStats();
});
</script>

<template>
  <div class="view">
    <div class="stat-grid">
      <div v-for="item in stats" :key="item.label" class="stat-card">
        <div class="stat-icon" :style="{ background: item.bg, color: item.color }">
          <NIcon><component :is="item.icon" /></NIcon>
        </div>
        <div class="stat-body">
          <span class="stat-value">{{ item.value }}</span>
          <span class="stat-label">{{ item.label }}</span>
        </div>
      </div>
    </div>

    <NCard :bordered="false" class="table-card" content-style="flex:1;min-height:0;display:flex;flex-direction:column;">
      <div class="toolbar">
        <div class="toolbar-left">
          <NInput v-model:value="keyword" placeholder="搜索模板名称或编码" clearable style="width: 260px">
            <template #prefix>
              <NIcon color="#a3adbd"><SearchOutline /></NIcon>
            </template>
          </NInput>
          <NSelect
            v-model:value="statusFilter"
            :options="statusOptions"
            placeholder="全部状态"
            clearable
            style="width: 140px"
          />
        </div>
        <div class="toolbar-right">
          <BatchBar
            v-if="checkedKeys.length"
            :count="checkedKeys.length"
            label="模板"
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
          <NButton type="primary" ghost @click="importShow = true">
            <template #icon>
              <NIcon><FlashOutline /></NIcon>
            </template>
            AI 导入
          </NButton>
          <NButton type="primary" @click="openCreate">
            <template #icon>
              <NIcon><AddOutline /></NIcon>
            </template>
            新增模板
          </NButton>
        </div>
      </div>

      <NDataTable
        remote
        flex-height
        paginate-single-page
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: TemplateRow) => row.id"
        :checked-row-keys="checkedKeys"
        :row-props="rowProps"
        :row-class-name="rowClassName"
        :bordered="false"
        size="small"
        :scroll-x="1164"
        style="flex: 1; min-height: 0"
        @update:checked-row-keys="(keys: Array<string | number>) => (checkedKeys = keys as number[])"
      >
        <template #empty>
          <NEmpty description="暂无模板，请先创建">
            <template #extra>
              <NButton size="small" type="primary" @click="openCreate">新增模板</NButton>
            </template>
          </NEmpty>
        </template>
      </NDataTable>
    </NCard>

    <TemplateEditModal v-model:show="editShow" :template="editing" @saved="onSaved" />
    <GenerateModal v-model:show="generateShow" :template="generatingTemplate" @generated="onSaved" />
    <AiImportModal v-model:show="importShow" @saved="onSaved" />
    <FilePreviewModal
      v-model:show="previewShow"
      :src="previewSrc"
      :download-src="previewDownloadSrc"
      :filename="previewName"
    />
  </div>
</template>
