<script setup lang="ts">
import { h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
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
import { AddOutline, RefreshOutline, SearchOutline } from '@vicons/ionicons5';
import { api, cancelAiImport, deleteAiImport, fetchAiImports, rerunAiImport, startAiImport } from '../api';
import type { AiImportDetail, AiImportRow } from '../types';
import { aiStatusMeta, formatElapsed, stageLabel, stageProgress } from '../aiImport';
import { batchDeleteSummary, confirmDelete, formatTime, isInteractiveClick } from '../utils';
import type { BatchDeleteResult } from '../utils';
import { rowActions } from '../components/rowActions';
import type { RowAction } from '../components/rowActions';
import BatchBar from '../components/BatchBar.vue';
import AiImportModal from '../components/AiImportModal.vue';
import AiImportDetailModal from '../components/AiImportDetailModal.vue';

/**
 * AI 导入任务列表：上传建任务 → 点「开始导入」后台执行 → 详情里确认计划并生成模板。
 *
 * <p>分析要几十秒到几分钟，所以列表只显示状态与当前阶段，细节交给详情弹窗；
 * 有任务在执行时列表每 3 秒刷新一次，全部进入终态就停。</p>
 */
const message = useMessage();
const dialog = useDialog();
const router = useRouter();

const rows = ref<AiImportRow[]>([]);
const loading = ref(false);
const keyword = ref('');
const statusFilter = ref<string | null>(null);
/** 勾选的任务 id：批量删除用 */
const checkedKeys = ref<number[]>([]);

const createShow = ref(false);
const detailShow = ref(false);
const detailId = ref<number | null>(null);

const statusOptions = [
  { label: '全部状态', value: 'ALL' },
  { label: '待开始', value: 'PENDING' },
  { label: '执行中', value: 'RUNNING' },
  { label: '已生成模板', value: 'SUCCEEDED' },
  { label: '失败', value: 'FAILED' },
  { label: '已取消', value: 'CANCELED' }
];

const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 50],
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

let pollTimer: ReturnType<typeof setTimeout> | null = null;
/** 刚点过开始 / 重新分析：这段时间里 PENDING 也算「在动」，列表要继续拉 */
const watching = ref(false);

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

/** 拉列表。`silent=true` 用于轮询，不显示 loading，避免表格每 3 秒闪一次遮罩。 */
async function load(silent = false) {
  stopPolling();
  if (!silent) loading.value = true;
  try {
    const query = new URLSearchParams({
      page: String(pagination.page),
      size: String(pagination.pageSize)
    });
    if (keyword.value.trim()) query.set('keyword', keyword.value.trim());
    if (statusFilter.value && statusFilter.value !== 'ALL') query.set('status', statusFilter.value);
    const data = await fetchAiImports(query.toString());
    rows.value = data.records ?? [];
    pagination.itemCount = data.total ?? rows.value.length;
    if (!rows.value.length && pagination.page > 1) {
      pagination.page -= 1;
      await load(silent);
      return;
    }
    // 有任务在跑就轮询；刚点过开始的那几秒任务还是 PENDING，也要继续拉，否则只能手动刷新
    const busy = rows.value.some(
      (row) => row.status === 'RUNNING' || (watching.value && row.status === 'PENDING')
    );
    if (busy) {
      pollTimer = setTimeout(() => void load(true), 1500);
    } else {
      watching.value = false;
    }
    // 手动刷新或翻页后把已经不在当前页的勾选去掉；轮询时不动，否则选中的行会被刷没
    if (!silent) {
      const visible = new Set(rows.value.map((row) => row.id));
      checkedKeys.value = checkedKeys.value.filter((id) => visible.has(id));
    }
  } catch (e: any) {
    if (!silent) message.error(e.message);
  } finally {
    if (!silent) loading.value = false;
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

onMounted(() => void load());
onBeforeUnmount(() => {
  stopPolling();
  if (keywordTimer) clearTimeout(keywordTimer);
});

function openDetail(row: AiImportRow) {
  detailId.value = row.id;
  detailShow.value = true;
}

function onCreated(task: AiImportDetail) {
  pagination.page = 1;
  void load();
  openDetail(task as AiImportRow);
}

function openTemplate(id: number) {
  detailShow.value = false;
  void router.push({ path: '/templates', query: { open: String(id) } });
}

async function start(row: AiImportRow) {
  try {
    await startAiImport(row.id);
    watching.value = true;
    message.success('已开始，后台分析中');
    await load();
  } catch (e: any) {
    message.error(e.message);
  }
}

/**
 * 重新分析：清掉上一次结果再走一遍流程。
 *
 * <p>已经生成过模板的任务要确认一次 —— 结果会写回那个模板（新增版本、重建数据块），
 * 不是新建一个同名模板。</p>
 */
function rerun(row: AiImportRow) {
  const go = async () => {
    try {
      await rerunAiImport(row.id);
      watching.value = true;
      message.success('已重新提交，后台分析中');
      await load();
    } catch (e: any) {
      message.error(e.message);
    }
  };
  if (!row.template_id) {
    void go();
    return;
  }
  const instance = dialog.warning({
    title: '重新分析',
    content: `将重新读一遍文件并再跑一轮分析，结果写回模板「${row.template_name}」`
      + `（新增一个版本、按新计划重建数据块）。分析失败时模板保持原样。`,
    positiveText: '重新分析',
    negativeText: '取消',
    onPositiveClick: async () => {
      instance.loading = true;
      instance.closable = false;
      instance.negativeButtonProps = { disabled: true };
      await go();
    }
  });
}

async function cancel(row: AiImportRow) {
  try {
    await cancelAiImport(row.id);
    message.success('已请求取消');
    await load();
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmRemove(row: AiImportRow) {
  confirmDelete(dialog, {
    title: '删除导入任务',
    content: `删除任务「${row.file_name}」将同时移除上传的文件与思维链记录，且不可恢复。`
      + (row.template_id ? '已生成的模板会保留，不受影响。' : ''),
    action: async () => {
      try {
        await deleteAiImport(row.id);
        message.success('任务已删除');
        await load();
      } catch (e: any) {
        message.error(e.message);
      }
    }
  });
}

/** 批量删除：后端逐条删，单条失败不影响其他条；模板不跟着删。 */
async function batchRemove() {
  const ids = [...checkedKeys.value];
  if (!ids.length) return;
  try {
    const res = await api<BatchDeleteResult>('/ai/imports/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ ids })
    });
    checkedKeys.value = [];
    await load();
    const summary = batchDeleteSummary('任务', res);
    message[summary.type](summary.text);
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmBatchRemove() {
  const count = checkedKeys.value.length;
  if (!count) return;
  confirmDelete(dialog, {
    title: '批量删除导入任务',
    content: `将删除选中的 ${count} 个任务，连同它们上传的文件与思维链记录，且不可恢复。`
      + '这些任务生成过的模板会保留。',
    positiveText: `删除 ${count} 个`,
    action: () => batchRemove()
  });
}

function clearSelection() {
  checkedKeys.value = [];
}

function isRowSelected(row: AiImportRow): boolean {
  return checkedKeys.value.includes(row.id);
}

/** 点行 = 勾选 / 取消勾选；勾选框与操作列按钮的点击不接管，执行中的行不可选 */
function toggleRow(row: AiImportRow, event: MouseEvent) {
  if (!row.can_delete) return;
  if (isInteractiveClick(event)) return;
  checkedKeys.value = isRowSelected(row)
    ? checkedKeys.value.filter((key) => key !== row.id)
    : [...checkedKeys.value, row.id];
}

const rowProps = (row: AiImportRow) => ({
  style: 'cursor: pointer;',
  onClick: (event: MouseEvent) => toggleRow(row, event)
});

const rowClassName = (row: AiImportRow) => (isRowSelected(row) ? 'is-checked' : '');

const columns = [
  // 勾选列固定在左侧，横向滚动时始终可见；执行中的任务不能删，勾选框一并禁用
  {
    type: 'selection' as const,
    fixed: 'left' as const,
    width: 44,
    disabled: (row: AiImportRow) => !row.can_delete
  },
  {
    title: '文件',
    key: 'file_name',
    minWidth: 230,
    render: (row: AiImportRow) =>
      h('div', null, [
        h('div', { class: 'cell-title' }, row.file_name),
        h('div', { class: 'cell-sub mono' }, row.template_code)
      ])
  },
  {
    title: '模板名称',
    key: 'template_name',
    minWidth: 160,
    render: (row: AiImportRow) => h('span', null, row.template_name)
  },
  {
    title: '状态',
    key: 'status',
    width: 120,
    render: (row: AiImportRow) => {
      const meta = aiStatusMeta(row.status);
      return h(NTag, { size: 'small', bordered: false, type: meta.type }, { default: () => meta.label });
    }
  },
  {
    title: '阶段',
    key: 'stage',
    width: 150,
    render: (row: AiImportRow) => {
      if (!row.stage) return h('span', { class: 'muted' }, '-');
      const active = row.status === 'RUNNING' || row.status === 'PENDING';
      return h('div', { class: 'cell-stage' }, [
        h('span', { class: active ? 'stage-name is-active' : 'stage-name' }, stageLabel(row.stage)),
        h('span', { class: 'cell-sub' }, stageProgress(row.stage))
      ]);
    }
  },
  {
    title: '耗时',
    key: 'elapsed_ms',
    width: 100,
    render: (row: AiImportRow) => h('span', { class: 'muted' }, formatElapsed(row.elapsed_ms))
  },
  {
    title: '创建时间',
    key: 'create_time',
    width: 160,
    render: (row: AiImportRow) => h('span', { class: 'muted' }, formatTime(row.create_time))
  },
  {
    title: '操作',
    key: 'actions',
    width: 340,
    fixed: 'right' as const,
    render: (row: AiImportRow) => {
      const actions: RowAction[] = [];
      if (row.status === 'PENDING') {
        actions.push({ label: '开始导入', tone: 'primary', onClick: () => void start(row) });
      } else if (row.status !== 'RUNNING') {
        actions.push({ label: '重新分析', tone: 'warning', onClick: () => rerun(row) });
      }
      if (row.status === 'PENDING' || row.status === 'RUNNING') {
        actions.push({ label: '取消', tone: 'warning', onClick: () => void cancel(row) });
      }
      actions.push({ label: '详情', tone: 'info', onClick: () => openDetail(row) });
      if (row.template_id) {
        actions.push({
          label: '查看模板',
          tone: 'success',
          onClick: () => openTemplate(row.template_id as number)
        });
      }
      if (row.can_delete) {
        actions.push({ label: '删除', tone: 'error', onClick: () => confirmRemove(row) });
      }
      return rowActions(actions);
    }
  }
];
</script>

<template>
  <div class="view">
    <NCard :bordered="false" class="table-card" content-style="flex:1;min-height:0;display:flex;flex-direction:column;">
      <div class="toolbar">
        <div class="toolbar-left">
          <NInput v-model:value="keyword" placeholder="搜索文件名、模板名或编码" clearable style="width: 260px">
            <template #prefix>
              <NIcon color="#a3adbd"><SearchOutline /></NIcon>
            </template>
          </NInput>
          <NSelect
            v-model:value="statusFilter"
            :options="statusOptions"
            placeholder="全部状态"
            clearable
            style="width: 150px"
          />
        </div>
        <div class="toolbar-right">
          <BatchBar
            v-if="checkedKeys.length"
            :count="checkedKeys.length"
            label="任务"
            @remove="confirmBatchRemove"
            @clear="clearSelection"
          />
          <NTooltip trigger="hover">
            <template #trigger>
              <NButton circle quaternary @click="load()">
                <template #icon>
                  <NIcon><RefreshOutline /></NIcon>
                </template>
              </NButton>
            </template>
            刷新
          </NTooltip>
          <NButton type="primary" @click="createShow = true">
            <template #icon>
              <NIcon><AddOutline /></NIcon>
            </template>
            新建导入任务
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
        :row-key="(row: AiImportRow) => row.id"
        :checked-row-keys="checkedKeys"
        :row-props="rowProps"
        :row-class-name="rowClassName"
        :bordered="false"
        size="small"
        :scroll-x="1264"
        style="flex: 1; min-height: 0"
        @update:checked-row-keys="(keys: Array<string | number>) => (checkedKeys = keys as number[])"
      >
        <template #empty>
          <NEmpty description="暂无导入任务，上传一份填好的文档试试">
            <template #extra>
              <NButton size="small" type="primary" @click="createShow = true">新建导入任务</NButton>
            </template>
          </NEmpty>
        </template>
      </NDataTable>
    </NCard>

    <AiImportModal v-model:show="createShow" @created="onCreated" />
    <AiImportDetailModal
      v-model:show="detailShow"
      :id="detailId"
      @changed="load()"
      @open-template="openTemplate"
    />
  </div>
</template>
