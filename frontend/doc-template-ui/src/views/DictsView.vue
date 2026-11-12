<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
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
import type { DictItemRow, DictRow, PageResult } from '../types';
import { batchDeleteSummary, confirmDelete, isInteractiveClick } from '../utils';
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

/** 字典项服务端分页：默认每页 20 条，与模板列表同一套交互 */
const itemPagination = reactive({
  page: 1,
  pageSize: 20,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [20, 50, 100],
  prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 条`,
  onChange: (page: number) => {
    itemPagination.page = page;
    void loadItems();
  },
  onUpdatePageSize: (size: number) => {
    itemPagination.pageSize = size;
    itemPagination.page = 1;
    void loadItems();
  }
});

const selected = computed(() => dicts.value.find((row) => row.id === selectedId.value) ?? null);

async function loadDicts() {
  loading.value = true;
  checkedDicts.value = [];
  try {
    const query = keyword.value.trim() ? `?keyword=${encodeURIComponent(keyword.value.trim())}` : '';
    dicts.value = await api<DictRow[]>(`/dicts${query}`);
    // 选中的字典被搜索条件过滤掉了：清空选中，避免右侧还挂着一本「看不见的字典」
    if (selectedId.value && !dicts.value.some((row) => row.id === selectedId.value)) {
      selectedId.value = null;
      items.value = [];
      itemPagination.itemCount = 0;
    }
    // 首次进来默认选中第一本，右侧的字典项区域不空着
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
  itemPagination.page = 1;
  void loadItems();
}

async function loadItems() {
  if (!selectedId.value) {
    items.value = [];
    itemPagination.itemCount = 0;
    return;
  }
  itemsLoading.value = true;
  // 换页 / 换字典 / 删完重载都会走到这里，之前勾的项可能已经不在这一页了
  checkedItems.value = [];
  try {
    const data = await api<PageResult<DictItemRow>>(
      `/dicts/${selectedId.value}/items?page=${itemPagination.page}&size=${itemPagination.pageSize}`
    );
    items.value = data.records ?? [];
    itemPagination.itemCount = data.total ?? items.value.length;
    // 删到当前页空了就退回上一页
    if (!items.value.length && itemPagination.page > 1) {
      itemPagination.page -= 1;
      await loadItems();
    }
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
    if (selectedId.value) {
      itemPagination.page = 1;
      await loadItems();
    }
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
      itemPagination.itemCount = 0;
    }
    await loadDicts();
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmRemoveDict(row: DictRow) {
  confirmDelete(dialog, {
    title: '删除字典',
    content: `将删除字典「${row.dict_code} · ${row.dict_name}」及其 ${row.item_count ?? 0} 个字典项，且不可恢复。引用它的转换器会退化为原值输出。`,
    action: () => removeDict(row)
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
      itemPagination.itemCount = 0;
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
  confirmDelete(dialog, {
    title: '批量删除字典',
    content: `将删除选中的 ${count} 本字典及其全部字典项，且不可恢复。`,
    positiveText: `删除 ${count} 本`,
    action: () => batchRemoveDicts()
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
  const creating = !editor.id;
  savingItem.value = true;
  try {
    if (editor.id) {
      await api(`/dicts/items/${editor.id}`, { method: 'PUT', body: JSON.stringify(body) });
    } else {
      await api(`/dicts/${selectedId.value}/items`, { method: 'POST', body: JSON.stringify(body) });
    }
    message.success('字典项已保存');
    showItemEditor.value = false;
    // 新增的项排在最后：直接翻到最后一页，省得以为没保存成功
    if (creating) {
      const pageSize = itemPagination.pageSize;
      itemPagination.page = Math.max(1, Math.ceil((itemPagination.itemCount + 1) / pageSize));
    }
    await loadItems();
    // 项数变了，左侧列表上的计数要跟着变
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
  confirmDelete(dialog, {
    title: '删除字典项',
    content: `确认删除「${row.item_value} → ${row.item_label}」？删除后模板里该值会按 fallback 输出。`,
    action: () => removeItem(row)
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
  confirmDelete(dialog, {
    title: '批量删除字典项',
    content: `将删除选中的 ${count} 个字典项，且不可恢复。`,
    positiveText: `删除 ${count} 个`,
    action: () => batchRemoveItems()
  });
}

/* ------------------------------------------------------------ 表格 */
/** 左侧列表窄，只保留「选哪本」需要的信息；备注和时间放进字典编辑弹窗里看 */
const dictColumns = [
  { type: 'selection' as const, width: 40 },
  {
    title: '字典',
    key: 'dict_code',
    minWidth: 140,
    render: (row: DictRow) =>
      h('div', null, [
        h('div', { class: 'cell-title mono' }, row.dict_code),
        h('div', { class: 'cell-sub' }, row.dict_name)
      ])
  },
  {
    title: '项数',
    key: 'item_count',
    width: 68,
    render: (row: DictRow) =>
      h(NTag, { size: 'small', bordered: false, type: 'info' }, { default: () => `${row.item_count ?? 0}` })
  },
  {
    title: '操作',
    key: 'actions',
    width: 84,
    render: (row: DictRow) =>
      rowActions([
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

function isDictChecked(row: DictRow): boolean {
  return checkedDicts.value.includes(row.id);
}

function isItemChecked(row: DictItemRow): boolean {
  return checkedItems.value.includes(row.id);
}

/** 点字典行 = 勾选 / 取消勾选，同时右侧跟着切到这本字典；勾选框和操作列按钮自己处理，不接管 */
const dictRowProps = (row: DictRow) => ({
  style: 'cursor: pointer;',
  onClick: (event: MouseEvent) => {
    if (isInteractiveClick(event)) return;
    checkedDicts.value = isDictChecked(row)
      ? checkedDicts.value.filter((key) => key !== row.id)
      : [...checkedDicts.value, row.id];
    // 勾选是为了批量删，点开是为了看字典项：两个意图都满足，右侧跟着点的那一行走
    if (selectedId.value !== row.id) selectDict(row);
  }
});

/** 勾选的行用蓝底，右侧正在展示的那本用左侧竖条，两种状态能同时看出来 */
const dictRowClassName = (row: DictRow) => {
  const classes: string[] = [];
  if (row.id === selectedId.value) classes.push('is-active');
  if (isDictChecked(row)) classes.push('is-checked');
  return classes.join(' ');
};

/** 点字典项行 = 勾选 / 取消勾选；勾选框和操作列按钮自己处理，不接管 */
const itemRowProps = (row: DictItemRow) => ({
  style: 'cursor: pointer;',
  onClick: (event: MouseEvent) => {
    if (isInteractiveClick(event)) return;
    checkedItems.value = isItemChecked(row)
      ? checkedItems.value.filter((key) => key !== row.id)
      : [...checkedItems.value, row.id];
  }
});

const itemRowClassName = (row: DictItemRow) => (isItemChecked(row) ? 'is-checked' : '');

onMounted(loadDicts);
</script>

<template>
  <div class="view dict-view">
    <!-- 左：字典列表（窄栏，点一行 = 勾选该行 + 右侧切到这本字典的字典项） -->
    <NCard
      :bordered="false"
      class="table-card dict-card"
      content-style="flex:1;min-height:0;display:flex;flex-direction:column;"
    >
      <div class="side-toolbar">
        <NInput v-model:value="keyword" size="small" placeholder="搜索编码 / 名称 / 备注" clearable>
          <template #prefix>
            <NIcon color="#94a3b8"><SearchOutline /></NIcon>
          </template>
        </NInput>
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

      <div class="side-meta">
        <span class="muted">共 {{ dicts.length }} 本</span>
        <BatchBar
          v-if="checkedDicts.length"
          :count="checkedDicts.length"
          label="字典"
          @remove="confirmBatchRemoveDicts"
          @clear="checkedDicts = []"
        />
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
        :scroll-x="360"
        style="flex: 1; min-height: 0"
        @update:checked-row-keys="(keys: Array<string | number>) => (checkedDicts = keys as number[])"
      >
        <template #empty>
          <NEmpty description="暂无字典，点击「新增字典」创建" />
        </template>
      </NDataTable>
    </NCard>

    <!-- 右：选中字典的字典项（服务端分页） -->
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
          <span v-else class="items-sub">先在左侧选中一本字典</span>
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
        remote
        :columns="itemColumns"
        :data="items"
        :loading="itemsLoading"
        :row-key="(row: DictItemRow) => row.id"
        :checked-row-keys="checkedItems"
        :row-props="itemRowProps"
        :row-class-name="itemRowClassName"
        :pagination="itemPagination"
        :bordered="false"
        size="small"
        :scroll-x="700"
        style="flex: 1; min-height: 0"
        @update:checked-row-keys="(keys: Array<string | number>) => (checkedItems = keys as number[])"
      >
        <template #empty>
          <NEmpty
            :description="selected ? '该字典暂无字典项，点击「新增字典项」添加' : '在左侧选择字典后，在此维护其「值 → 显示名」映射'"
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
/* 左：字典列表（固定宽度）；右：字典项占满剩余宽度。窄屏回退成上下结构 */
.dict-view {
  flex-direction: row;
  gap: 14px;
}

.dict-view .dict-card {
  flex: 0 0 400px;
}

.dict-view .items-card {
  flex: 1 1 auto;
  min-width: 0;
}

.side-toolbar {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.side-toolbar .n-input {
  flex: 1 1 auto;
  min-width: 0;
}

/* 这一行平时只有「共 N 本」，勾选后换成批量操作条（小尺寸按钮 28px 高）。
   高度按操作条留够，勾选 / 取消时下面的表格不会跟着上下跳 */
.side-meta {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 30px;
  margin-bottom: 8px;
}

.items-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink, #0f172a);
}

.items-sub {
  font-size: 12px;
  color: #64748b;
}

.items-hint {
  flex: none;
  margin-bottom: 10px;
  padding: 7px 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #eff6ff;
  color: #475569;
  font-size: 12px;
  line-height: 1.6;
}

.items-hint .mono {
  color: #1e40af;
}

.dict-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

@media (max-width: 992px) {
  .dict-view {
    flex-direction: column;
  }

  .dict-view .dict-card {
    flex: 0 0 40%;
  }
}
</style>
