<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  NAlert,
  NButton,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NTabPane,
  NTabs,
  NTag,
  NText,
  useMessage
} from 'naive-ui';
import { api, apiBase, downloadFile, recordDownloadUrl, recordPreviewUrl } from '../api';
import type { DataBlockRow, PlaceholderInfo, RecordRow, TemplateRow, VersionRow } from '../types';
import { prettyJson, tryParseJson } from '../utils';
import FilePreviewModal from './FilePreviewModal.vue';

const props = defineProps<{ show: boolean; template: TemplateRow | null }>();
const emit = defineEmits<{
  (event: 'update:show', value: boolean): void;
  (event: 'generated'): void;
}>();

const message = useMessage();
const visible = computed({
  get: () => props.show,
  set: (value: boolean) => emit('update:show', value)
});

const versions = ref<VersionRow[]>([]);
const versionId = ref<number | null>(null);
const exportType = ref<'ORIGINAL' | 'PDF'>('ORIGINAL');
const paramsText = ref('{}');
const info = ref<PlaceholderInfo | null>(null);
const blocks = ref<DataBlockRow[]>([]);
const loading = ref(false);
const generating = ref(false);
const result = ref<RecordRow | null>(null);
const errorText = ref('');
/** 这次调用的真实响应，格式化后展示；请求失败时放错误信息 */
const responseText = ref('');

const previewShow = ref(false);
const previewSrc = ref('');
const previewName = ref('');
const previewDownloadSrc = ref('');

const exportOptions = [
  { label: '原格式（DOCX / XLSX）', value: 'ORIGINAL' },
  { label: 'PDF（内置转换）', value: 'PDF' }
];

const versionOptions = computed(() =>
  versions.value.map((version) => ({ label: `v${version.version_no} · ${version.file_name}`, value: version.id }))
);

const hasVersion = computed(() => !!versionId.value);

/** 启用的数据块编码，占位符以它们为前缀时由数据块自动填充，不需要手写参数 */
const blockCodes = computed(() =>
  blocks.value.filter((block) => (block.status ?? 'ENABLED') === 'ENABLED').map((block) => block.block_code)
);
/** 循环体里的字段（明细行），由数组项提供 */
const loopFields = computed(() => new Set(info.value?.loop_fields ?? []));

function managedByBlock(key: string): boolean {
  if (loopFields.value.has(key)) return true;
  return blockCodes.value.some((code) => key === code || key.startsWith(`${code}.`));
}

const placeholders = computed(() => info.value?.placeholders ?? []);
const manualPlaceholders = computed(() => placeholders.value.filter((key) => !managedByBlock(key)));

interface BlockView {
  id: number;
  code: string;
  name: string;
  type: 'JAVA' | 'SQL' | 'API';
  tagType: 'info' | 'warning' | 'default';
  enabled: boolean;
  summary: string;
  params: string[];
  images: string[];
}

function parseConfig(raw: string | null | undefined): Record<string, any> {
  const parsed = tryParseJson(raw ?? '{}');
  const value = parsed.ok ? parsed.value : null;
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

/** 数据块配置里声明的图片字段（imageFields / image_fields / images） */
function imageFieldsOf(config: Record<string, any>): string[] {
  const raw = config.imageFields ?? config.image_fields ?? config.images;
  if (!raw) return [];
  const list = Array.isArray(raw) ? raw : typeof raw === 'string' ? raw.split(',') : [raw];
  const names: string[] = [];
  for (const item of list) {
    if (typeof item === 'string') {
      const name = item.split(':')[0].trim();
      if (name) names.push(name);
      continue;
    }
    if (item && typeof item === 'object') {
      const name = String(item.field ?? item.name ?? item.key ?? '').trim();
      if (name) names.push(name);
    }
  }
  return [...new Set(names)];
}

/** 数据块配置里声明成二维码的字段（qrCodes / qr_codes / qrcodes） */
function qrFieldsOf(config: Record<string, any>): string[] {
  const raw = config.qrCodes ?? config.qr_codes ?? config.qrcodes ?? config.qrCode;
  if (!raw) return [];
  const list = Array.isArray(raw) ? raw : typeof raw === 'string' ? raw.split(',') : [raw];
  const names: string[] = [];
  for (const item of list) {
    if (typeof item === 'string') {
      const name = item.split(':')[0].trim();
      if (name) names.push(name);
      continue;
    }
    if (item && typeof item === 'object') {
      const name = String(item.field ?? item.name ?? item.key ?? '').trim();
      if (name) names.push(name);
    }
  }
  return [...new Set(names)];
}

/** 参数映射里 $request.xxx 的 xxx，就是「模拟生成」时需要手填的键 */
function requestParamsOf(paramConfig: string | null | undefined): string[] {
  const config = parseConfig(paramConfig);
  const names = new Set<string>();
  for (const value of Object.values(config)) {
    if (typeof value !== 'string') continue;
    const text = value.trim();
    if (!text.startsWith('$request')) continue;
    const name = text.replace(/^\$request\.?/, '').split('.')[0].trim();
    if (name) names.add(name);
  }
  return [...names];
}

function summaryOf(type: BlockView['type'], config: Record<string, any>): string {
  if (type === 'SQL') {
    const sql = String(config.sql ?? '').replace(/\s+/g, ' ').trim();
    if (!sql) return '（未配置 sql）';
    return sql.length > 110 ? `${sql.slice(0, 110)}…` : sql;
  }
  if (type === 'API') {
    const method = String(config.method ?? 'GET').toUpperCase();
    const url = String(config.url ?? '').trim();
    return `${method} ${url || '（未配置 url）'}`;
  }
  const provider = String(config.provider ?? '').trim();
  return `Provider：${provider || '（未配置 provider）'}`;
}

/** 数据块按 sort_order 排序后的展示模型：生成时后端就是按这个顺序执行的 */
const blockViews = computed<BlockView[]>(() =>
  blocks.value
    .slice()
    .sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0) || a.id - b.id)
    .map((block) => {
      const type = (block.data_source_type ?? 'JAVA') as BlockView['type'];
      const config = parseConfig(block.data_source_config);
      return {
        id: block.id,
        code: block.block_code,
        name: block.block_name,
        type,
        tagType: type === 'SQL' ? 'info' : type === 'API' ? 'warning' : 'default',
        enabled: (block.status ?? 'ENABLED') === 'ENABLED',
        summary: summaryOf(type, config),
        params: requestParamsOf(block.param_config),
        images: [...new Set([...imageFieldsOf(config), ...qrFieldsOf(config)])]
      };
    })
);

/** 启用中的数据块需要的全部参数键 */
const requiredParams = computed(() => {
  const keys = new Set<string>();
  for (const block of blockViews.value) {
    if (!block.enabled) continue;
    for (const name of block.params) keys.add(name);
  }
  return [...keys];
});

const currentParams = computed<Record<string, any>>(() => {
  const parsed = tryParseJson(paramsText.value);
  const value = parsed.ok ? parsed.value : null;
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
});

/* ------------------------------------------------------------ 接口调用 */

/**
 * 把管理页的 API 基址补成完整 URL。
 *
 * <p>`apiBaseUrl` 可能是绝对地址（宿主把接口挂在别的域上），也可能只是 `/api/doc-template`，
 * 后者用当前页面的 origin 拼——用户从哪个域名打开管理页，调用方就用哪个域名。</p>
 */
function absoluteApiUrl(path: string): string {
  if (/^https?:\/\//i.test(apiBase)) return apiBase + path;
  return `${window.location.origin}${apiBase}${path}`;
}

/** 这次模拟等价的接口调用地址：模板 id + 参数，就是别的项目里要发的那条请求 */
const generateUrl = computed(() =>
  props.template ? absoluteApiUrl(`/templates/${props.template.id}/generate`) : ''
);

/** 请求体：和点「开始生成」时实际发出去的是同一份 */
const requestBody = computed(() => ({
  templateVersionId: versionId.value,
  exportType: exportType.value,
  requestParams: currentParams.value
}));

const requestBodyText = computed(() => prettyJson(requestBody.value));

/** 接口根地址，拼文件下载地址用（不带尾部斜杠） */
const apiRoot = computed(() => absoluteApiUrl(''));

/** 把请求体整体再缩进一层，嵌进各语言模板里保持对齐 */
function indentBody(indent: string): string {
  return requestBodyText.value
    .split('\n')
    .map((line) => indent + line)
    .join('\n');
}

function curlSample(): string {
  // 单引号里换行是合法的，请求体可以原样嵌进去；值里自带的单引号要按 shell 规则转义
  const body = requestBodyText.value.replace(/'/g, "'\\''");
  return [
    `curl -X POST '${generateUrl.value}' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -d '${body}'`
  ].join('\n');
}

function jsSample(): string {
  // JSON 对象字面量本身就是合法的 JS 字面量，请求体可以整段照抄
  return [
    `const payload = ${requestBodyText.value};`,
    '',
    `const response = await fetch('${generateUrl.value}', {`,
    `  method: 'POST',`,
    `  headers: { 'Content-Type': 'application/json' },`,
    `  body: JSON.stringify(payload)`,
    `});`,
    '',
    `if (!response.ok) throw new Error('生成失败：' + response.status);`,
    `const record = await response.json();`,
    '',
    `// record.result_file_name 是生成的文件名，用 record.id 取文件：`,
    `// GET ${apiRoot.value}/generate-records/\${record.id}/file`
  ].join('\n');
}

function javaSample(): string {
  // 文本块（Java 15+）收尾的三引号缩进决定剥离多少，所以请求体整体缩进 8 格
  return [
    `import java.net.URI;`,
    `import java.net.http.HttpClient;`,
    `import java.net.http.HttpRequest;`,
    `import java.net.http.HttpResponse;`,
    '',
    `String url = "${generateUrl.value}";`,
    `String body = """`,
    indentBody('        '),
    `        """;`,
    '',
    `HttpRequest request = HttpRequest.newBuilder(URI.create(url))`,
    `        .header("Content-Type", "application/json")`,
    `        .POST(HttpRequest.BodyPublishers.ofString(body))`,
    `        .build();`,
    '',
    `HttpResponse<String> response = HttpClient.newHttpClient()`,
    `        .send(request, HttpResponse.BodyHandlers.ofString());`,
    '',
    `System.out.println(response.body());`
  ].join('\n');
}

function pythonSample(): string {
  return [
    `import json`,
    `import requests`,
    '',
    `payload = json.loads("""${requestBodyText.value}""")`,
    '',
    `response = requests.post("${generateUrl.value}", json=payload)`,
    `response.raise_for_status()`,
    '',
    `record = response.json()   # record["id"] / record["result_file_name"]`,
    `# 取文件：GET ${apiRoot.value}/generate-records/{record["id"]}/file`
  ].join('\n');
}

/** 调用示例页签：同一个请求，四种语言的写法 */
const codeSamples = computed<{ key: string; tab: string; code: string }[]>(() => [
  { key: 'curl', tab: 'curl', code: curlSample() },
  { key: 'js', tab: 'JavaScript', code: jsSample() },
  { key: 'java', tab: 'Java', code: javaSample() },
  { key: 'python', tab: 'Python', code: pythonSample() }
]);

const activeSample = ref('curl');

/** 数据块需要但当前 JSON 里没有（或为空）的参数 */
const missingParams = computed(() =>
  requiredParams.value.filter((key) => {
    const value = currentParams.value[key];
    return value === undefined || value === null || value === '';
  })
);

const enabledBlockCount = computed(() => blockViews.value.filter((block) => block.enabled).length);

watch(
  () => [props.show, props.template],
  async ([show]) => {
    if (!show || !props.template) return;
    result.value = null;
    errorText.value = '';
    responseText.value = '';
    paramsText.value = '{}';
    info.value = null;
    exportType.value = props.template.export_type === 'PDF' ? 'PDF' : 'ORIGINAL';
    await load();
  },
  { immediate: true }
);

async function load() {
  if (!props.template) return;
  loading.value = true;
  try {
    versions.value = await api<VersionRow[]>(`/templates/${props.template.id}/versions`);
    blocks.value = await api<DataBlockRow[]>(`/data-blocks?templateId=${props.template.id}`).catch(() => []);
    versionId.value = props.template.current_version_id ?? versions.value[0]?.id ?? null;
    if (!versionId.value) {
      info.value = null;
      paramsText.value = '{}';
      return;
    }
    info.value = await api<PlaceholderInfo>(`/templates/${props.template.id}/placeholders`);
    // 只预填「不由数据块管理」的占位符；数据块参数留空让用户按需填
    const sample = info.value.sample_params ?? {};
    const manual: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(sample)) {
      if (!managedByBlock(key)) manual[key] = value;
    }
    paramsText.value = prettyJson(manual);
    // 打开就补齐参数键，省得每次再点一下「补齐参数」
    mergeParamKeys({ silent: true });
  } catch (e: any) {
    message.error(e.message);
  } finally {
    loading.value = false;
  }
}

/**
 * 把「数据块需要的参数键」和「手填占位符键」并进参数 JSON，已有的值不动。
 * 打开弹窗时自动跑一次（silent）；按钮留着是为了中途改了数据块配置后再补一次。
 */
function mergeParamKeys(options: { silent?: boolean } = {}) {
  const base: Record<string, any> = { ...currentParams.value };
  let added = 0;
  for (const key of requiredParams.value) {
    if (!(key in base)) {
      base[key] = '';
      added++;
    }
  }
  for (const key of manualPlaceholders.value) {
    if (!(key in base)) {
      base[key] = '';
      added++;
    }
  }
  paramsText.value = prettyJson(base);
  if (!options.silent) message.success(added ? `已补齐 ${added} 个参数键，请填写取值` : '参数键已齐全');
}

function formatParams() {
  const parsed = tryParseJson(paramsText.value);
  if (!parsed.ok) {
    message.error(`JSON 格式错误：${parsed.error}`);
    return;
  }
  paramsText.value = prettyJson(parsed.value);
}

async function generate() {
  if (!props.template) return;
  const parsed = tryParseJson(paramsText.value);
  if (!parsed.ok) {
    message.error(`参数不是合法 JSON：${parsed.error}`);
    return;
  }
  if (!parsed.value || typeof parsed.value !== 'object' || Array.isArray(parsed.value)) {
    message.error('参数必须是 JSON 对象');
    return;
  }
  if (missingParams.value.length) {
    message.warning(`数据块需要的参数没有值：${missingParams.value.join('、')}，数据源可能取不到数据`);
  }
  generating.value = true;
  errorText.value = '';
  try {
    const record = await api<RecordRow>(`/templates/${props.template.id}/generate`, {
      method: 'POST',
      body: JSON.stringify({
        templateVersionId: versionId.value,
        exportType: exportType.value,
        requestParams: parsed.value
      })
    });
    result.value = record;
    responseText.value = prettyJson(record);
    if (record.status === 'SUCCESS') {
      message.success('生成成功');
      emit('generated');
    } else {
      errorText.value = record.error_message ?? '生成失败';
    }
  } catch (e: any) {
    errorText.value = e.message;
    responseText.value = e.message;
    message.error(e.message);
  } finally {
    generating.value = false;
  }
}

function openResultPreview() {
  if (!result.value) return;
  previewSrc.value = recordPreviewUrl(result.value.id);
  previewDownloadSrc.value = recordDownloadUrl(result.value.id);
  previewName.value = result.value.result_file_name ?? '生成文件';
  previewShow.value = true;
}

async function downloadResult() {
  if (!result.value) return;
  try {
    await downloadFile(recordDownloadUrl(result.value.id), result.value.result_file_name ?? 'document.docx');
  } catch (e: any) {
    message.error(e.message);
  }
}
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="card"
    class="doc-modal-card"
    style="width: min(1080px, 96vw); max-height: 88vh"
    :bordered="false"
  >
    <template #header>
      <div style="display: flex; align-items: center; gap: 10px">
        <NText strong style="font-size: 16px">模拟生成</NText>
        <NTag size="small" :bordered="false" type="info">{{ template?.template_name }}</NTag>
        <NTag v-if="enabledBlockCount" size="small" :bordered="false" type="success">
          {{ enabledBlockCount }} 个数据块将执行
        </NTag>
      </div>
    </template>

    <NAlert v-if="!hasVersion" type="warning" :bordered="false" style="margin-bottom: 14px">
      该模板尚未上传文件，请先在「基本信息」中上传 .docx / .xlsx 模板。
    </NAlert>

    <div class="generate-grid">
      <div>
        <NForm label-placement="top" size="small">
          <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px">
            <NFormItem label="模板版本">
              <NSelect v-model:value="versionId" :options="versionOptions" placeholder="选择版本" />
            </NFormItem>
            <NFormItem label="导出格式">
              <NSelect v-model:value="exportType" :options="exportOptions" />
            </NFormItem>
          </div>
          <NFormItem>
            <template #label>
              <div class="params-label">
                <span>数据源参数（JSON）</span>
                <span>
                  <NButton size="tiny" text type="primary" @click="mergeParamKeys()">补齐参数</NButton>
                  <NButton size="tiny" text style="margin-left: 10px" @click="formatParams">格式化</NButton>
                </span>
              </div>
            </template>
            <NInput
              v-model:value="paramsText"
              type="textarea"
              class="json-editor"
              :autosize="{ minRows: 10, maxRows: 18 }"
              placeholder='{"reportNo": "R-001"}'
            />
          </NFormItem>
        </NForm>

        <div class="params-hint">
          生成时按下列顺序执行数据块，结果自动填充模板占位符；
          这里只需要写<span class="strong">数据源需要的参数</span>（键名对应参数映射里的
          <span class="mono">$request.xxx</span> 的 <span class="mono">xxx</span>）。
        </div>

        <NAlert v-if="missingParams.length" type="warning" :bordered="false" style="margin-top: 12px">
          数据块需要的参数还没有值：<span class="mono">{{ missingParams.join('、') }}</span>
          ，不填的话数据源可能查不到数据。
        </NAlert>
      </div>

      <div>
        <div class="panel-title">数据块执行顺序</div>
        <div v-if="blockViews.length" class="block-list">
          <div
            v-for="(block, index) in blockViews"
            :key="block.id"
            class="block-card"
            :class="{ 'is-off': !block.enabled }"
          >
            <div class="block-head">
              <span class="block-index">{{ index + 1 }}</span>
              <span class="mono block-code">{{ block.code }}</span>
              <NTag size="tiny" :bordered="false" :type="block.tagType">{{ block.type }}</NTag>
              <NTag v-if="!block.enabled" size="tiny" :bordered="false">已停用</NTag>
              <span class="block-name">{{ block.name }}</span>
            </div>
            <div class="block-summary mono">{{ block.summary }}</div>
            <div v-if="block.params.length" class="block-params">
              需要参数：<span class="mono">{{ block.params.join('、') }}</span>
            </div>
            <div v-if="block.images.length" class="block-params">
              图片 / 二维码字段：<span class="mono">{{ block.images.join('、') }}</span>
              <NTag size="tiny" :bordered="false" type="info" style="margin-left: 6px">自动渲染</NTag>
            </div>
          </div>
        </div>
        <div v-else class="empty-hint">
          该模板没有配置数据块。参数 JSON 里的键会直接替换模板占位符。
        </div>

        <div class="panel-title" style="margin-top: 16px">模板里手填的占位符</div>
        <div v-if="manualPlaceholders.length" class="hint-chips">
          <NTag v-for="key in manualPlaceholders" :key="key" size="small" :bordered="false" class="mono">{{ key }}</NTag>
        </div>
        <div v-else class="empty-hint">没有需要手填的占位符。</div>
      </div>
    </div>

    <div class="panel-title" style="margin-top: 18px">生成结果</div>
    <div v-if="result && result.status === 'SUCCESS'" class="result-panel">
      <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px">
        <NTag type="success" size="small" :bordered="false">生成成功</NTag>
        <span class="mono muted">记录 #{{ result.id }}</span>
      </div>
      <div class="cell-title" style="font-size: 13px">{{ result.result_file_name }}</div>
      <NSpace style="margin-top: 12px">
        <NButton size="small" type="primary" ghost @click="openResultPreview">预览</NButton>
        <NButton size="small" @click="downloadResult">下载</NButton>
      </NSpace>
    </div>
    <NAlert v-else-if="errorText" type="error" :bordered="false" title="生成失败">
      {{ errorText }}
    </NAlert>
    <div v-else class="result-panel muted">
      开始生成时按上述顺序执行数据块取数，再用当前版本渲染并保存文件。
    </div>

    <div class="panel-title" style="margin-top: 18px">
      接口调用
      <span class="muted" style="font-weight: 400; font-size: 12px">
        其他项目可直接按此请求调用，无需内嵌管理页
      </span>
    </div>
    <div class="call-panel">
      <div class="call-row">
        <span class="call-label">请求地址</span>
        <span class="call-value">
          <NTag size="tiny" :bordered="false" type="info">POST</NTag>
          <span class="mono call-url">{{ generateUrl }}</span>
        </span>
      </div>
      <div class="call-row">
        <span class="call-label">请求头</span>
        <span class="mono call-value">Content-Type: application/json</span>
      </div>
      <div class="call-row">
        <span class="call-label">请求体</span>
        <pre class="call-pre">{{ requestBodyText }}</pre>
      </div>
      <div class="call-row">
        <span class="call-label">返回结果</span>
        <pre v-if="responseText" class="call-pre">{{ responseText }}</pre>
        <span v-else class="call-value muted">点「开始生成」后显示这次调用的真实响应</span>
      </div>
      <div class="code-panel">
        <div class="code-title">调用示例</div>
        <NTabs v-model:value="activeSample" type="segment" size="small">
          <NTabPane v-for="sample in codeSamples" :key="sample.key" :name="sample.key" :tab="sample.tab">
            <pre class="code-body">{{ sample.code }}</pre>
          </NTabPane>
        </NTabs>
      </div>
      <div class="muted call-hint">
        请求体里的 <span class="mono">templateVersionId</span> 可以省略，缺省用模板的当前版本。
        宿主启用了认证（<span class="mono">auth.mode=custom</span>）时，调用方要带上自己的身份凭证（Cookie 或认证头）；
        管理页用的是浏览器里的登录态。
      </div>
    </div>

    <template #footer>
      <NSpace justify="space-between" align="center">
        <span class="muted">
          模板中的 <span class="mono">${key}</span> 或 <span class="mono" v-pre>{{ key }}</span> 会被参数值替换
        </span>
        <NSpace>
          <NButton @click="visible = false">关闭</NButton>
          <NButton type="primary" :loading="generating" :disabled="!hasVersion" @click="generate">开始生成</NButton>
        </NSpace>
      </NSpace>
    </template>

    <FilePreviewModal
      v-model:show="previewShow"
      :src="previewSrc"
      :download-src="previewDownloadSrc"
      :filename="previewName"
    />
  </NModal>
</template>

<style scoped>
.generate-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 24px;
}

.params-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.params-hint {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-muted);
  background: #f7f9fd;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
}

.params-hint .strong {
  color: var(--text-strong);
}

.panel-title {
  font-size: 13px;
  font-weight: 500;
  color: #4b5563;
  margin-bottom: 8px;
}

/* 数据块一多就把弹窗撑长：这一块自己限高滚动，弹窗整体不再跟着变高 */
.block-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 340px;
  overflow-y: auto;
  /* 出滚动条时给卡片留一点空隙，避免贴住滚动条 */
  padding-right: 4px;
}

.block-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fbfcfe;
}

.block-card.is-off {
  opacity: 0.55;
}

.block-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.block-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #e8effd;
  color: var(--primary);
  font-size: 11px;
  font-weight: 500;
}

.block-code {
  font-size: 12.5px;
  color: var(--text-strong);
}

.block-name {
  font-size: 12px;
  color: var(--text-muted);
}

.block-summary {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: #5b6577;
  word-break: break-all;
}

.block-params {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.empty-hint {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-muted);
  border: 1px dashed #d7e0f0;
  border-radius: 10px;
  padding: 10px 12px;
}

/* -------------------------------------------------- 接口调用 */
.call-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px;
  background: #fbfcfe;
}

.call-row {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
}

.call-label {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted);
}

.call-value {
  font-size: 12px;
  line-height: 18px;
  color: #3b4657;
  word-break: break-all;
}

.call-url {
  margin-left: 6px;
}

.call-pre {
  margin: 0;
  font-family: 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.6;
  color: #3b4657;
  background: #f4f7fc;
  border-radius: 6px;
  padding: 6px 8px;
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.code-panel {
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px dashed #dfe6f2;
}

.code-title {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.code-body {
  margin: 0;
  font-family: 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.65;
  color: #33404f;
  background: #f4f7fc;
  border-radius: 6px;
  padding: 8px 10px;
  max-height: 260px;
  overflow: auto;
  white-space: pre;
  tab-size: 2;
}

.code-panel :deep(.n-tab-pane) {
  padding: 8px 0 0;
}

.call-hint {
  font-size: 11.5px;
  line-height: 1.7;
}

@media (max-width: 900px) {
  .generate-grid {
    grid-template-columns: 1fr;
  }
}
</style>
