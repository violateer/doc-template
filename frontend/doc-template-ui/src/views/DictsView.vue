<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue';
import {
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NSpace,
  NTag,
  NTooltip,
  useDialog,
  useMessage
} from 'naive-ui';
import { RefreshOutline, SearchOutline } from '@vicons/ionicons5';
import { api } from '../api';
import type { DictItemRow, DictRow } from '../types';
import { batchDeleteSummary, formatTime, isInteractiveClick } from '../utils';
import type { BatchDeleteResult } from '../utils';
import { rowActions } from '../components/rowActions';
import BatchBar from '../components/BatchBar.vue';

const message = useMessage();
const dialog = useDialog();

/* ------------------------------------------------------------ 字典（主表） */
const dicts = ref<DictRow[]>([]);
const loading = ref(false);
const keyword = ref('');
const checkedDicts = ref<number[]>([]);
const selectedId = ref<number | null>(null);

/* ------------------------------------------------------------ 字典项（子表） */
const items = ref<DictItemRow[]>([]);
const itemsLoading = ref(false);
const checkedItems = ref<number[]>([]);

const selected = computed(() => dicts.value.find((row) => row.id === selectedId.value) ?? null);

async function loadDicts() {
  loading.value = true;
  checkedDicts.value = [];
  try {
    const query = keyword.value.trim() ? `?keyword=${encodeURIComponent(keyword.value.trim())}` : '';
    dicts.value = await api<DictRow[]>(`/dicts${query}`);
    // 选中的字典被搜索条件过滤掉了：清空选中，避免下面还挂着一本「看不见的字典」
    if (selectedId.value && !dicts.value.some((row) => row.id === selectedId.value)) {
      selectedId.value = null;
      items.value = [];
    }
    // 首次进来默认选中第一本，下面的字典项区域不空着
    if (!selectedId.value && dicts.value.length) selectDict(dicts.value[0]);
  } catch (e: any) {
    message.error(e.message);
  } finally {
    loading.value = false;
  }
}

function selectDict(row: DictRow) {
  selectedId.value = row.id;
  checkedItems.value = [];
  void loadItems();
}

async function loadItems() {
  if (!selectedId.value) {
    items.value = [];
    return;
  }
  itemsLoading.value = true;
  checkedItems.value = [];
  try {
    items.value = await api<DictItemRow[]>(`/dicts/${selectedId.value}/items`);
  } catch (e: any) {
    message.error(e.message);
  } finally {
    itemsLoading.value = false;
  }
}

let keywordTimer: ReturnType<typeof setTimeout> | null = null;
watch(keyword, () => {
  if (keywordTimer) clearTimeout(keywordTimer);
  keywordTimer = setTimeout(() => void loadDicts(), 300);
});

/* ------------------------------------------------------------ 字典编辑 */
const dictEditor = ref<Partial<DictRow> | null>(null);
const showDictEditor = ref(false);
const savingDict = ref(false);

function openCreateDict() {
  dictEditor.value = { dict_code: '', dict_name: '', remark: '' };
  showDictEditor.value = true;
}

function openEditDict(row: DictRow) {
  dictEditor.value = { ...row };
  showDictEditor.value = true;
}

async function saveDict() {
  const editor = dictEditor.value;
  if (!editor) return;
  const code = String(editor.dict_code ?? '').trim();
  const name = String(editor.dict_name ?? '').trim();
  if (!code) return message.warning('请填写字典编码');
  if (!name) return message.warning('请填写字典名称');
  const body = { dictCode: code, dictName: name, remark: String(editor.remark ?? '').trim() };
  savingDict.value = true;
  try {
    if (editor.id) {
      await api(`/dicts/${editor.id}`, { method: 'PUT', body: JSON.stringify(body) });
      message.success('字典已保存');
    } else {
      const created = await api<DictRow>('/dicts', { method: 'POST', body: JSON.stringify(body) });
      message.success('字典已创建，接着添加字典项');
      if (created?.id) selectedId.value = created.id;
    }
    showDictEditor.value = false;
    await loadDicts();
    if (selectedId.value) await loadItems();
  } catch (e: any) {
    message.error(e.message);
  } finally {
    savingDict.value = false;
  }
}

async function removeDict(row: DictRow) {
  try {
    await api(`/dicts/${row.id}`, { method: 'DELETE' });
    message.success('字典及其字典项已删除');
    if (selectedId.value === row.id) {
      selectedId.value = null;
      items.value = [];
    }
    await loadDicts();
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmRemoveDict(row: DictRow) {
  dialog.warning({
    title: '删除字典',
    content: `将删除字典「${row.dict_code} · ${row.dict_name}」及其 ${row.item_count ?? 0} 个字典项，且不可恢复。引用它的转换器会退化为原值输出。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => removeDict(row)
  });
}

async function batchRemoveDicts() {
  const ids = [...checkedDicts.value];
  if (!ids.length) return;
  try {
    const res = await api<BatchDeleteResult>('/dicts/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ ids })
    });
    if (ids.includes(selectedId.value ?? -1)) {
      selectedId.value = null;
      items.value = [];
    }
    await loadDicts();
    const summary = batchDeleteSummary('字典', res);
    message[summary.type](summary.text);
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmBatchRemoveDicts() {
  const count = checkedDicts.value.length;
  if (!count) return;
  dialog.warning({
    title: '批量删除字典',
    content: `将删除选中的 ${count} 本字典及其全部字典项，且不可恢复。`,
    positiveText: `删除 ${count} 本`,
    negativeText: '取消',
    onPositiveClick: () => batchRemoveDicts()
  });
}

/* ------------------------------------------------------------ 字典项编辑 */
const itemEditor = ref<Partial<DictItemRow> | null>(null);
const showItemEditor = ref(false);
const savingItem = ref(false);

function openCreateItem() {
  if (!selectedId.value) return message.warning('请先选中一本字典');
  itemEditor.value = { item_value: '', item_label: '', sort_order: null as unknown as number };
  showItemEditor.value = true;
}

function openEditItem(row: DictItemRow) {
  itemEditor.value = { ...row };
  showItemEditor.value = true;
}

async function saveItem() {
  const editor = itemEditor.value;
  if (!editor || !selectedId.value) return;
  const value = String(editor.item_value ?? '').trim();
  const label = String(editor.item_label ?? '').trim();
  if (!value) return message.warning('请填写字典值');
  if (!label) return message.warning('请填写显示名');
  const body = { itemValue: value, itemLabel: label, sortOrder: editor.sort_order ?? null };
  savingItem.value = true;
  try {
    if (editor.id) {
      await api(`/dicts/items/${editor.id}`, { method: 'PUT', body: JSON.stringify(body) });
    } else {
      await api(`/dicts/${selectedId.value}/items`, { method: 'POST', body: JSON.stringify(body) });
    }
    message.success('字典项已保存');
    showItemEditor.value = false;
    await loadItems();
    // 项数变了，上方列表上的计数要跟着变
    await loadDicts();
  } catch (e: any) {
    message.error(e.message);
  } finally {
    savingItem.value = false;
  }
}

async function removeItem(row: DictItemRow) {
  try {
    await api(`/dicts/items/${row.id}`, { method: 'DELETE' });
    message.success('字典项已删除');
    await loadItems();
    await loadDicts();
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmRemoveItem(row: DictItemRow) {
  dialog.warning({
    title: '删除字典项',
    content: `确认删除「${row.item_value} → ${row.item_label}」？删除后模板里该值会按 fallback 输出。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => removeItem(row)
  });
}

async function batchRemoveItems() {
  const ids = [...checkedItems.value];
  if (!ids.length) return;
  try {
    const res = await api<BatchDeleteResult>('/dicts/items/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ ids })
    });
    await loadItems();
    await loadDicts();
    const summary = batchDeleteSummary('字典项', res);
    message[summary.type](summary.text);
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmBatchRemoveItems() {
  const count = checkedItems.value.length;
  if (!count) return;
  dialog.warning({
    title: '批量删除字典项',
    content: `将删除选中的 ${count} 个字典项，且不可恢复。`,
    positiveText: `删除 ${count} 个`,
    negativeText: '取消',
    onPositiveClick: () => batchRemoveItems()
  });
}

/* ------------------------------------------------------------ 表格 */
const dictColumns = [
  { type: 'selection' as const, width: 44 },
  {
    title: '字典',
    key: 'dict_code',
    minWidth: 220,
    render: (row: DictRow) =>
      h('div', null, [
        h('div', { class: 'cell-title mono' }, row.dict_code),
        h('div', { class: 'cell-sub' }, row.dict_name)
      ])
  },
  {
    title: '项数',
    key: 'item_count',
    width: 90,
    render: (row: DictRow) =>
      h(NTag, { size: 'small', bordered: false, type: 'info' }, { default: () => `${row.item_count ?? 0} 项` })
  },
  {
    title: '备注',
    key: 'remark',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row: DictRow) => h('span', { class: 'muted' }, row.remark || '—')
  },
  {
    title: '更新时间',
    key: 'update_time',
    width: 160,
    render: (row: DictRow) => h('span', { class: 'muted' }, formatTime(row.update_time ?? row.create_time))
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    render: (row: DictRow) =>
      rowActions([
        { label: '维护项', tone: 'info', onClick: () => selectDict(row) },
        { label: '编辑', tone: 'primary', onClick: () => openEditDict(row) },
        { label: '删除', tone: 'error', onClick: () => confirmRemoveDict(row) }
      ])
  }
];

const itemColumns = [
  { type: 'selection' as const, width: 44 },
  {
    title: '值',
    key: 'item_value',
    minWidth: 160,
    render: (row: DictItemRow) => h('span', { class: 'mono' }, row.item_value)
  },
  {
    title: '显示名',
    key: 'item_label',
    minWidth: 200,
    render: (row: DictItemRow) => h('span', null, row.item_label)
  },
  {
    title: '排序',
    key: 'sort_order',
    width: 90,
    render: (row: DictItemRow) => h('span', { class: 'muted mono' }, String(row.sort_order ?? 0))
  },
  {
    title: '操作',
    key: 'actions',
    width: 140,
    render: (row: DictItemRow) =>
      rowActions([
        { label: '编辑', tone: 'primary', onClick: () => openEditItem(row) },
        { label: '删除', tone: 'error', onClick: () => confirmRemoveItem(row) }
      ])
  }
];

/** 点字典行 = 选中它（勾选框和操作列按钮自己处理，不接管） */
const dictRowProps = (row: DictRow) => ({
  style: 'cursor: pointer;',
  onClick: (event: MouseEvent) => {
    if (isInteractiveClick(event)) return;
    selectDict(row);
  }
});

const dictRowClassName = (row: DictRow) => (row.id === selectedId.value ? 'is-checked' : '');

onMounted(loadDicts);
</script>

<template>
  <div class="view dict-view">
    <NCard
      :bordered="false"
      class="table-card dict-card"
      content-style="flex:1;min-height:0;display:flex;flex-direction:column;"
    >
      <div class="toolbar">
        <div class="toolbar-left">
          <NInput v-model:value="keyword" placeholder="搜索字典编码 / 名称 / 备注" clearable style="width: 260px">
            <template #prefix>
              <NIcon color="#a3adbd"><SearchOutline /></NIcon>
            </template>
          </NInput>
          <NTag size="small" :bordered="false">共 {{ dicts.length }} 本字典</NTag>
        </div>
        <div class="toolbar-right">
          <BatchBar
            v-if="checkedDicts.length"
            :count="checkedDicts.length"
            label="字典"
            @remove="confirmBatchRemoveDicts"
            @clear="checkedDicts = []"
          />
          <NTooltip trigger="hover">
            <template #trigger>
              <NButton circle quaternary @click="loadDicts">
                <template #icon>
                  <NIcon><RefreshOutline /></NIcon>
                </template>
              </NButton>
            </template>
            刷新
          </NTooltip>
          <NButton type="primary" size="small" @click="openCreateDict">新增字典</NButton>
        </div>
      </div>

      <NDataTable
        flex-height
        :columns="dictColumns"
        :data="dicts"
        :loading="loading"
        :row-key="(row: DictRow) => row.id"
        :checked-row-keys="checkedDicts"
        :row-props="dictRowProps"
        :row-class-name="dictRowClassName"
        :bordered="false"
        size="small"
        :scroll-x="900"
        style="flex: 1; min-height: 0"
        @update:checked-row-keys="(keys: Array<string | number>) => (checkedDicts = keys as number[])"
      >
        <template #empty>
          <NEmpty description="还没有字典，点击「新增字典」创建一本" />
        </template>
      </NDataTable>
    </NCard>

    <NCard
      :bordered="false"
      class="table-card items-card"
      content-style="flex:1;min-height:0;display:flex;flex-direction:column;"
    >
      <div class="toolbar">
        <div class="toolbar-left">
          <template v-if="selected">
            <span class="items-title">字典项</span>
            <NTag size="small" :bordered="false" type="info">{{ selected.dict_code }}</NTag>
            <span class="items-sub">{{ selected.dict_name }}</span>
          </template>
          <span v-else class="items-sub">先在上方选中一本字典</span>
        </div>
        <div class="toolbar-right">
          <BatchBar
            v-if="checkedItems.length"
            :count="checkedItems.length"
            label="字典项"
            @remove="confirmBatchRemoveItems"
            @clear="checkedItems = []"
          />
          <NButton circle quaternary :disabled="!selected" @click="loadItems">
            <template #icon>
              <NIcon><RefreshOutline /></NIcon>
            </template>
          </NButton>
          <NButton type="primary" size="small" :disabled="!selected" @click="openCreateItem">新增字典项</NButton>
        </div>
      </div>

      <div class="items-hint">
        模板里写 <span class="mono">${status|dict(order_status)}</span>，或在数据块配置的
        <span class="mono">converters</span> 里写
        <span class="mono">{"field":"status","type":"dict","args":{"code":"order_status"}}</span>，
        取到的值就换成这里的显示名。
      </div>

      <NDataTable
        flex-height
        :columns="itemColumns"
        :data="items"
        :loading="itemsLoading"
        :row-key="(row: DictItemRow) => row.id"
        :checked-row-keys="checkedItems"
        :bordered="false"
        size="small"
        :scroll-x="700"
        style="flex: 1; min-height: 0"
        @update:checked-row-keys="(keys: Array<string | number>) => (checkedItems = keys as number[])"
      >
        <template #empty>
          <NEmpty
            :description="selected ? '这本字典还没有项，点击「新增字典项」添加' : '选中一本字典后在这里维护它的「值 → 显示名」'"
          />
        </template>
      </NDataTable>
    </NCard>

    <!-- 字典编辑 -->
    <NModal
      v-model:show="showDictEditor"
      preset="card"
      class="doc-modal-card"
      :title="dictEditor?.id ? '编辑字典' : '新增字典'"
      style="width: min(520px, 94vw)"
    >
      <NForm v-if="dictEditor" label-placement="top" size="small">
        <NFormItem label="字典编码" required>
          <NInput v-model:value="dictEditor.dict_code" class="mono" placeholder="转换器里引用的名字，如 order_status" />
        </NFormItem>
        <NFormItem label="字典名称" required>
          <NInput v-model:value="dictEditor.dict_name" placeholder="给配置者看的名字，如 订单状态" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="dictEditor.remark" type="textarea" :rows="3" placeholder="可选，说明这本字典用在哪" />
        </NFormItem>
      </NForm>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="showDictEditor = false">取消</NButton>
          <NButton type="primary" :loading="savingDict" @click="saveDict">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- 字典项编辑 -->
    <NModal
      v-model:show="showItemEditor"
      preset="card"
      class="doc-modal-card"
      :title="itemEditor?.id ? '编辑字典项' : `新增字典项 · ${selected?.dict_code ?? ''}`"
      style="width: min(560px, 94vw)"
    >
      <NForm v-if="itemEditor" label-placement="top" size="small">
        <div class="dict-grid">
          <NFormItem label="字典值" required>
            <NInput v-model:value="itemEditor.item_value" class="mono" placeholder="数据库里存的值，如 PAID" />
          </NFormItem>
          <NFormItem label="显示名" required>
            <NInput v-model:value="itemEditor.item_label" placeholder="模板里显示的文字，如 已付款" />
          </NFormItem>
        </div>
        <NFormItem label="排序号">
          <NInputNumber v-model:value="itemEditor.sort_order" :min="0" :show-button="false" placeholder="留空排到最后" />
        </NFormItem>
      </NForm>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="showItemEditor = false">取消</NButton>
          <NButton type="primary" :loading="savingItem" @click="saveItem">保存</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.dict-view {
  gap: 14px;
}

/* 上：字典主表（约四成高）；下：字典项子表（剩余高度），两张表各自内部滚动 */
.dict-view .dict-card {
  flex: 0 0 40%;
}

.dict-view .items-card {
  flex: 1 1 auto;
}

.items-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink, #1f2733);
}

.items-sub {
  font-size: 12px;
  color: #8b95a5;
}

.items-hint {
  margin-bottom: 10px;
  padding: 7px 10px;
  border: 1px solid #d9e6fb;
  border-radius: 8px;
  background: #f4f8ff;
  color: #4a5f88;
  font-size: 11.5px;
  line-height: 1.6;
}

.items-hint .mono {
  color: #2f6fed;
}

.dict-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
</style>
