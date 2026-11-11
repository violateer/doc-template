<script setup lang="ts">
import { computed, h, ref, watch } from 'vue';
import {
  NAlert,
  NButton,
  NDataTable,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NTabPane,
  NTabs,
  NTag,
  NUpload,
  NUploadDragger,
  NIcon,
  NText,
  useDialog,
  useMessage
} from 'naive-ui';
import type { UploadCustomRequestOptions } from 'naive-ui';
import { CloudUploadOutline, DocumentTextOutline, RefreshOutline } from '@vicons/ionicons5';
import { api, downloadFile, downloadUrl, previewUrl, uploadTemplate } from '../api';
import type { TemplateRow, VersionRow } from '../types';
import { formatTime, shortSha } from '../utils';
import { rowActions } from './rowActions';
import DataBlockPanel from './DataBlockPanel.vue';
import FilePreviewModal from './FilePreviewModal.vue';

const props = defineProps<{ show: boolean; template: TemplateRow | null }>();
const emit = defineEmits<{
  (event: 'update:show', value: boolean): void;
  (event: 'saved', template: TemplateRow): void;
}>();

const message = useMessage();
const dialog = useDialog();
const visible = computed({
  get: () => props.show,
  set: (value: boolean) => emit('update:show', value)
});

const current = ref<TemplateRow | null>(null);
const activeTab = ref<'basic' | 'blocks'>('basic');
const form = ref({ template_name: '', template_code: '', export_type: 'ORIGINAL', status: 'ENABLED', remark: '' });
const versions = ref<VersionRow[]>([]);
const loadingVersions = ref(false);
const saving = ref(false);
const uploading = ref(false);
/** 「其他版本」弹窗：默认只展示当前版本，历史版本收在弹窗里 */
const versionsShow = ref(false);
const switchingId = ref<number | null>(null);

const previewShow = ref(false);
const previewSrc = ref('');
const previewName = ref('');
const previewDownloadSrc = ref('');

const exportOptions = [
  { label: '原格式（DOCX / XLSX）', value: 'ORIGINAL' },
  { label: 'PDF（内置转换）', value: 'PDF' }
];
const statusOptions = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' }
];

watch(
  () => [props.show, props.template],
  ([show]) => {
    if (!show) {
      versionsShow.value = false;
      return;
    }
    current.value = props.template ? { ...props.template } : null;
    activeTab.value = 'basic';
    form.value = {
      template_name: props.template?.template_name ?? '',
      template_code: props.template?.template_code ?? '',
      export_type: props.template?.export_type ?? 'ORIGINAL',
      status: props.template?.status ?? 'ENABLED',
      remark: props.template?.remark ?? ''
    };
    versions.value = [];
    if (props.template) void loadVersions();
  },
  { immediate: true }
);

async function loadVersions() {
  if (!current.value) return;
  loadingVersions.value = true;
  try {
    versions.value = await api<VersionRow[]>(`/templates/${current.value.id}/versions`);
  } catch (e: any) {
    message.error(e.message);
  } finally {
    loadingVersions.value = false;
  }
}

/**
 * 「当前版本」以模板的 current_version_id 为准；上传后后端会把它指向新版本。
 * 万一这个 id 在版本列表里找不到（例如版本被删过），退回到 version_no 最大的那条。
 */
const currentVersionId = computed<number | null>(() => {
  const declared = current.value?.current_version_id ?? null;
  if (declared !== null && versions.value.some((version) => version.id === declared)) return declared;
  const latest = versions.value.reduce<VersionRow | null>(
    (acc, version) => (!acc || version.version_no > acc.version_no ? version : acc),
    null
  );
  return latest?.id ?? declared;
});

/** 当前生效的版本（生成文档时用的那一版） */
const currentVersion = computed<VersionRow | null>(
  () => versions.value.find((version) => version.id === currentVersionId.value) ?? null
);

/** 历史版本：弹窗里展示，可以随时切换回去 */
const otherVersions = computed(() =>
  versions.value.filter((version) => version.id !== currentVersionId.value)
);

async function saveBasic() {
  if (!form.value.template_name.trim()) {
    message.warning('请填写模板名称');
    return;
  }
  if (!form.value.template_code.trim()) {
    message.warning('请填写模板编码');
    return;
  }
  saving.value = true;
  try {
    if (current.value) {
      const updated = await api<TemplateRow>(`/templates/${current.value.id}`, {
        method: 'PUT',
        body: JSON.stringify({
          templateCode: form.value.template_code.trim(),
          templateName: form.value.template_name,
          exportType: form.value.export_type,
          status: form.value.status,
          remark: form.value.remark
        })
      });
      current.value = updated;
      emit('saved', updated);
      message.success('模板信息已更新');
      // 编辑场景保存后直接关闭弹窗；新建场景保持打开，方便紧接着上传模板文件
      visible.value = false;
    } else {
      const created = await api<TemplateRow>('/templates', {
        method: 'POST',
        body: JSON.stringify({
          templateName: form.value.template_name,
          templateCode: form.value.template_code,
          exportType: form.value.export_type,
          status: form.value.status,
          remark: form.value.remark
        })
      });
      current.value = created;
      emit('saved', created);
      message.success('模板已创建，请继续上传模板文件');
    }
  } catch (e: any) {
    message.error(e.message);
  } finally {
    saving.value = false;
  }
}

async function handleUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  if (!current.value) {
    message.warning('请先保存模板信息');
    return;
  }
  const raw = file.file;
  if (!raw) return;
  uploading.value = true;
  try {
    const version = (await uploadTemplate(current.value.id, raw)) as VersionRow;
    // 上传后后端会把 current_version_id 指向新版本，必须重新拉一次模板，
    // 否则「当前」标记还停在旧版本上。
    const fresh = await api<TemplateRow>(`/templates/${current.value.id}`);
    current.value = fresh;
    message.success(version?.version_no ? `已上传 v${version.version_no}，当前版本已切换` : '模板文件已上传，已生成新版本');
    onFinish();
    await loadVersions();
    emit('saved', fresh);
  } catch (e: any) {
    onError();
    message.error(e.message);
  } finally {
    uploading.value = false;
  }
}

function openPreview(version: VersionRow) {
  previewSrc.value = previewUrl(version.id);
  previewDownloadSrc.value = downloadUrl(version.id);
  previewName.value = version.file_name;
  previewShow.value = true;
}

async function removeVersion(version: VersionRow) {
  if (!current.value) return;
  try {
    await api(`/templates/${current.value.id}/versions/${version.id}`, { method: 'DELETE' });
    message.success('已删除该版本及存储文件');
    // 删掉的可能是当前版本，后端会自动指向剩下的最新版本，所以必须重新拉模板
    const fresh = await api<TemplateRow>(`/templates/${current.value.id}`);
    current.value = fresh;
    await loadVersions();
    emit('saved', fresh);
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmRemoveVersion(version: VersionRow) {
  dialog.warning({
    title: '删除版本',
    content: `删除 v${version.version_no}（${version.file_name}）会同时删除云存储中的文件，且不可恢复。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => removeVersion(version)
  });
}

async function downloadVersion(version: VersionRow) {
  try {
    await downloadFile(downloadUrl(version.id), version.file_name);
  } catch (e: any) {
    message.error(e.message);
  }
}

/** 把历史版本切回当前版本：生成文档立刻改用这一版文件 */
async function setCurrentVersion(version: VersionRow) {
  if (!current.value || switchingId.value !== null) return;
  switchingId.value = version.id;
  try {
    const updated = await api<TemplateRow>(`/templates/${current.value.id}/versions/${version.id}/current`, {
      method: 'PUT'
    });
    current.value = updated;
    await loadVersions();
    emit('saved', updated);
    versionsShow.value = false;
    message.success(`已切换到 v${version.version_no}，后续生成将使用该版本`);
  } catch (e: any) {
    message.error(e.message);
  } finally {
    switchingId.value = null;
  }
}

function confirmSwitchVersion(version: VersionRow) {
  dialog.info({
    title: '切换当前版本',
    content: `切换后，生成文档将使用 v${version.version_no}（${version.file_name}），历史生成记录不受影响。`,
    positiveText: '切换',
    negativeText: '取消',
    onPositiveClick: () => setCurrentVersion(version)
  });
}

const versionColumns = computed(() => [
  {
    title: '版本',
    key: 'version_no',
    width: 84,
    render: (row: VersionRow) =>
      h(NTag, { size: 'small', bordered: false, type: 'info' }, { default: () => `v${row.version_no}` })
  },
  {
    title: '文件名',
    key: 'file_name',
    minWidth: 230,
    ellipsis: { tooltip: true },
    render: (row: VersionRow) =>
      h('div', null, [
        h('div', { class: 'cell-title', style: 'font-weight:500' }, row.file_name),
        h('div', { class: 'cell-sub mono' }, `sha256 ${shortSha(row.file_sha256)}`)
      ])
  },
  { title: '上传时间', key: 'create_time', width: 160, render: (row: VersionRow) => formatTime(row.create_time) },
  {
    title: '操作',
    key: 'actions',
    width: 210,
    fixed: 'right' as const,
    render: (row: VersionRow) =>
      rowActions([
        {
          label: switchingId.value === row.id ? '切换中…' : '设为当前',
          tone: 'primary',
          disabled: switchingId.value !== null,
          onClick: () => confirmSwitchVersion(row)
        },
        { label: '预览', tone: 'info', onClick: () => openPreview(row) },
        { label: '下载', tone: 'success', onClick: () => downloadVersion(row) },
        { label: '删除', tone: 'error', onClick: () => confirmRemoveVersion(row) }
      ])
  }
]);
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="card"
    class="doc-modal-card"
    style="width: min(1120px, 96vw); max-height: 88vh"
    :bordered="false"
  >
    <template #header>
      <div style="display: flex; align-items: center; gap: 10px">
        <NText strong style="font-size: 16px">{{ current ? '编辑模板' : '新增模板' }}</NText>
        <NTag v-if="current" size="small" :bordered="false" type="info" class="mono">{{ current.template_code }}</NTag>
        <NTag v-if="current" size="small" :bordered="false">{{ current.file_type }}</NTag>
      </div>
    </template>

    <NTabs v-model:value="activeTab" type="line" animated>
      <NTabPane name="basic" tab="基本信息">
        <div class="basic-stack">
          <NForm label-placement="top" size="small">
            <div class="form-grid">
              <NFormItem label="模板名称" required>
                <NInput v-model:value="form.template_name" placeholder="例如：成品检验报告" />
              </NFormItem>
              <NFormItem label="模板编码" required>
                <NInput
                  v-model:value="form.template_code"
                  placeholder="例如：finishedInspectionReport"
                  class="mono"
                />
              </NFormItem>
              <NFormItem label="默认导出格式">
                <NSelect v-model:value="form.export_type" :options="exportOptions" />
              </NFormItem>
              <NFormItem label="状态">
                <NSelect v-model:value="form.status" :options="statusOptions" />
              </NFormItem>
            </div>
            <NFormItem label="备注">
              <NInput v-model:value="form.remark" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
            </NFormItem>
          </NForm>
          <NSpace justify="end" style="margin-top: 4px">
            <NButton @click="visible = false">关闭</NButton>
            <NButton type="primary" :loading="saving" @click="saveBasic">
              {{ current ? '保存基本信息' : '创建模板' }}
            </NButton>
          </NSpace>

          <div class="section-divider" />

          <template v-if="current">
            <NUpload :custom-request="handleUpload" :show-file-list="false" accept=".docx,.xlsx" :disabled="uploading">
              <NUploadDragger class="uploader">
                <NIcon size="30" color="#2f6fed"><CloudUploadOutline /></NIcon>
                <div class="uploader-title">{{ uploading ? '正在上传…' : '点击或拖拽上传模板文件' }}</div>
                <div class="uploader-sub">支持 .docx / .xlsx，上传后自动生成新版本</div>
              </NUploadDragger>
            </NUpload>

            <div class="section-head">
              <span class="section-title">当前版本</span>
              <div class="section-actions">
                <NButton size="tiny" quaternary :loading="loadingVersions" @click="loadVersions">
                  <template #icon>
                    <NIcon><RefreshOutline /></NIcon>
                  </template>
                  刷新
                </NButton>
                <NButton size="tiny" secondary type="primary" @click="versionsShow = true">
                  其他版本
                  <span v-if="otherVersions.length" class="version-count">{{ otherVersions.length }}</span>
                </NButton>
              </div>
            </div>

            <div v-if="currentVersion" class="version-card">
              <div class="version-badge">v{{ currentVersion.version_no }}</div>
              <div class="version-main">
                <div class="version-name">{{ currentVersion.file_name }}</div>
                <div class="version-meta">
                  <span>{{ formatTime(currentVersion.create_time) }}</span>
                  <span class="version-dot">·</span>
                  <span class="mono">sha256 {{ shortSha(currentVersion.file_sha256) }}</span>
                </div>
              </div>
              <div class="version-actions">
                <NButton size="tiny" quaternary type="primary" @click="openPreview(currentVersion)">预览</NButton>
                <NButton size="tiny" quaternary @click="downloadVersion(currentVersion)">下载</NButton>
                <NButton size="tiny" quaternary type="error" @click="confirmRemoveVersion(currentVersion)">删除</NButton>
              </div>
            </div>
            <div v-else class="version-empty">尚未上传模板文件</div>
          </template>
          <div v-else class="uploader-placeholder">
            <NIcon size="34" color="#b3bdcc"><DocumentTextOutline /></NIcon>
            <div class="uploader-title">先保存模板信息</div>
            <div class="uploader-sub">创建成功后即可上传 .docx / .xlsx 模板并配置数据块</div>
          </div>
        </div>
      </NTabPane>

      <NTabPane name="blocks" tab="数据块">
        <NAlert v-if="!current" type="info" :bordered="false" style="margin-bottom: 12px">
          请先在「基本信息」中保存模板，再配置数据块。
        </NAlert>
        <DataBlockPanel v-else :template-id="current.id" />
      </NTabPane>
    </NTabs>

    <NModal
      v-model:show="versionsShow"
      preset="card"
      class="doc-modal-card"
      title="历史版本"
      style="width: min(900px, 94vw); max-height: 80vh"
      :bordered="false"
    >
      <div class="versions-tip">
        仅列出当前版本以外的历史版本；设为当前后，生成文档将使用所选版本。
      </div>
      <NDataTable
        :columns="versionColumns"
        :data="otherVersions"
        :loading="loadingVersions"
        :row-key="(row: VersionRow) => row.id"
        size="small"
        :bordered="false"
        :max-height="420"
        :scroll-x="780"
      >
        <template #empty>
          <div class="empty-hint">暂无其他版本，上传新文件后在此列出</div>
        </template>
      </NDataTable>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="versionsShow = false">关闭</NButton>
        </NSpace>
      </template>
    </NModal>

    <FilePreviewModal
      v-model:show="previewShow"
      :src="previewSrc"
      :download-src="previewDownloadSrc"
      :filename="previewName"
    />
  </NModal>
</template>

<style scoped>
.basic-stack {
  display: flex;
  flex-direction: column;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.section-divider {
  height: 1px;
  background: #eef1f7;
  margin: 20px 0 18px;
}

.uploader {
  border-radius: 14px;
  padding: 18px 12px;
  text-align: center;
  background: #fafcff;
}

.uploader-title {
  margin-top: 8px;
  font-size: 13.5px;
  font-weight: 600;
  color: #1f2733;
}

.uploader-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #8b95a5;
}

.uploader-placeholder {
  border: 1px dashed #dfe6f1;
  border-radius: 14px;
  padding: 40px 16px;
  text-align: center;
  background: #fafcff;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 18px 0 8px;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-count {
  margin-left: 5px;
  padding: 0 5px;
  border-radius: 7px;
  font-size: 11px;
  line-height: 15px;
  background: rgba(47, 111, 237, 0.12);
  color: #2f6fed;
}

/* -------------------------------------------------- 当前版本卡片 */
.version-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid #e4ebf7;
  border-radius: 12px;
  background: linear-gradient(180deg, #f7faff 0%, #ffffff 100%);
}

.version-badge {
  flex: none;
  min-width: 44px;
  padding: 3px 8px;
  border-radius: 8px;
  text-align: center;
  font-size: 12.5px;
  font-weight: 600;
  color: #2f6fed;
  background: rgba(47, 111, 237, 0.1);
}

.version-main {
  flex: 1;
  min-width: 0;
}

.version-name {
  font-size: 13px;
  font-weight: 500;
  color: #1f2733;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.version-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  font-size: 11.5px;
  color: #8b95a5;
}

.version-dot {
  color: #c3ccda;
}

.version-actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 2px;
}

.version-empty {
  padding: 18px 0;
  text-align: center;
  font-size: 12.5px;
  color: #a3adbb;
  border: 1px dashed #dfe6f1;
  border-radius: 12px;
  background: #fafcff;
}

.versions-tip {
  margin-bottom: 10px;
  padding: 7px 10px;
  border: 1px solid #d9e6fb;
  border-radius: 8px;
  background: #f4f8ff;
  color: #4a5f88;
  font-size: 12px;
  line-height: 1.6;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #4b5563;
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
