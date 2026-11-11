<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { NModal, NSpin, NAlert, NButton, NSpace, NTabs, NTabPane, NEmpty } from 'naive-ui';
import { renderAsync } from 'docx-preview';
import * as XLSX from 'xlsx';
import { apiBlob, downloadFile } from '../api';
import { fileKind } from '../utils';

const props = defineProps<{
  show: boolean;
  src: string;
  filename: string;
  downloadSrc?: string;
}>();

const emit = defineEmits<{ (event: 'update:show', value: boolean): void }>();

const visible = computed({
  get: () => props.show,
  set: (value: boolean) => emit('update:show', value)
});

const kind = computed(() => fileKind(props.filename));
const loading = ref(false);
const error = ref('');
const docxHost = ref<HTMLElement | null>(null);
const sheets = ref<{ name: string; html: string }[]>([]);
const activeSheet = ref('');
const pdfUrl = ref('');
/** docx 已拿到字节、正在排版时的遮罩（此时 loading 已关闭，容器必须先挂载出来） */
const rendering = ref(false);
let pdfObjectUrl = '';
// 每次加载递增；异步回调里比对，避免连续打开多个文件时旧结果覆盖新结果
let loadSeq = 0;

function extractTable(html: string): string {
  const match = /<table[\s\S]*<\/table>/i.exec(html);
  return match ? match[0] : html;
}

function reset() {
  error.value = '';
  sheets.value = [];
  activeSheet.value = '';
  rendering.value = false;
  if (docxHost.value) docxHost.value.innerHTML = '';
  if (pdfObjectUrl) {
    URL.revokeObjectURL(pdfObjectUrl);
    pdfObjectUrl = '';
  }
  pdfUrl.value = '';
}

async function load() {
  const seq = ++loadSeq;
  reset();
  if (!props.src) return;
  loading.value = true;
  try {
    const blob = await apiBlob(props.src);
    const buffer = await blob.arrayBuffer();
    if (seq !== loadSeq) return;
    if (kind.value === 'docx') {
      await renderDocx(buffer, seq);
    } else if (kind.value === 'xlsx') {
      const workbook = XLSX.read(buffer, { type: 'array' });
      // 空 sheet 的 !ref 是 undefined，直接 sheet_to_html 会抛
      // "Cannot read properties of undefined (reading 'indexOf')"，所以先过滤掉
      sheets.value = workbook.SheetNames.flatMap((name) => {
        const sheet = workbook.Sheets[name];
        if (!sheet || !sheet['!ref']) return [];
        return [{ name, html: extractTable(XLSX.utils.sheet_to_html(sheet, { header: '', footer: '' })) }];
      });
      if (!sheets.value.length) {
        error.value = '该文件没有可显示的内容（所有工作表都是空的）';
        return;
      }
      activeSheet.value = sheets.value[0].name;
    } else if (kind.value === 'pdf') {
      pdfObjectUrl = URL.createObjectURL(new Blob([buffer], { type: 'application/pdf' }));
      pdfUrl.value = pdfObjectUrl;
    } else {
      error.value = '该文件类型暂不支持在线预览，请下载后查看';
    }
  } catch (e: any) {
    if (seq === loadSeq) error.value = e?.message ?? '预览加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

/**
 * docx-preview 必须写入一个已经挂载到文档里的真实节点。
 * 但 loading 期间模板只渲染 loading 占位块，`docxHost` 还不存在，
 * 所以先把 loading 关掉让预览容器出现，等一次 DOM 更新后再渲染。
 */
async function renderDocx(buffer: ArrayBuffer, seq: number) {
  rendering.value = true;
  loading.value = false;
  try {
    await nextTick();
    if (seq !== loadSeq) return;
    const host = docxHost.value;
    if (!host) {
      error.value = '预览容器未就绪，请关闭弹窗后重新打开';
      return;
    }
    host.innerHTML = '';
    await renderAsync(buffer, host, undefined, {
      className: 'docx',
      inWrapper: true,
      ignoreWidth: false,
      ignoreHeight: true,
      breakPages: true,
      experimental: true
    });
    if (seq !== loadSeq) return;
    // 渲染成功但一个可见节点都没有时给出提示，而不是留一片空白
    if (!host.textContent?.trim() && !host.querySelector('img, table, svg, canvas')) {
      error.value = '未能解析出可显示的内容，请点击「下载原文件」查看';
      return;
    }
    host.parentElement?.scrollTo({ top: 0 });
  } finally {
    if (seq === loadSeq) rendering.value = false;
  }
}

async function download() {
  try {
    await downloadFile(props.downloadSrc ?? props.src, props.filename);
  } catch (e: any) {
    error.value = e?.message ?? '下载失败';
  }
}

watch(
  () => [props.show, props.src],
  ([show]) => {
    if (show) {
      void load();
    } else {
      // 关闭时让仍在进行中的加载/渲染失效，避免它们回来后又往已卸载的节点里写
      loadSeq++;
      reset();
    }
  }
);
</script>

<template>
  <NModal v-model:show="visible" preset="card" :title="`文件预览 · ${filename}`" style="width: min(1080px, 94vw)">
    <div v-if="loading" class="preview-shell" style="display: grid; place-items: center">
      <NSpin size="large" description="正在加载文件…" />
    </div>
    <template v-else>
      <NAlert v-if="error" type="error" :bordered="false" style="margin-bottom: 12px">{{ error }}</NAlert>

      <div v-else-if="kind === 'xlsx' && sheets.length">
        <NTabs v-model:value="activeSheet" type="segment" size="small" style="margin-bottom: 12px">
          <NTabPane v-for="sheet in sheets" :key="sheet.name" :name="sheet.name" :tab="sheet.name">
            <div class="preview-shell">
              <div class="sheet-preview" v-html="sheet.html" />
            </div>
          </NTabPane>
        </NTabs>
      </div>

      <div v-else-if="kind === 'pdf'" class="preview-shell" style="padding: 0">
        <iframe v-if="pdfUrl" class="pdf-frame" :src="pdfUrl" title="PDF 预览" />
      </div>

      <div v-else-if="kind === 'docx'" class="preview-shell docx-preview-wrapper">
        <div ref="docxHost" />
        <div v-if="rendering" class="docx-rendering">
          <NSpin size="large" description="正在排版文档…" />
        </div>
      </div>

      <NEmpty v-else description="暂不支持该文件类型预览" style="padding: 40px 0" />
    </template>

    <template #footer>
      <NSpace justify="end">
        <NButton @click="visible = false">关闭</NButton>
        <NButton type="primary" ghost @click="download">下载原文件</NButton>
      </NSpace>
    </template>
  </NModal>
</template>
