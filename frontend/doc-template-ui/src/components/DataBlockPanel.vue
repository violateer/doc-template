<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue';
import {
  NButton,
  NDataTable,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NTooltip,
  useDialog,
  useMessage
} from 'naive-ui';
import { WarningOutline } from '@vicons/ionicons5';
import type { DataTableColumns } from 'naive-ui';
import { api } from '../api';
import type { DataBlockRow, DataSourceItem, DictRow, ProviderItem } from '../types';
import { batchDeleteSummary, isInteractiveClick, tryParseJson } from '../utils';
import type { BatchDeleteResult } from '../utils';
import { configFields, dataSourceMeta, paramFieldNotes, paramSample } from '../dataBlockFields';
import type { ConfigField, DataSourceType } from '../dataBlockFields';
import { rowActions } from './rowActions';
import BatchBar from './BatchBar.vue';

const props = defineProps<{ templateId: number | null }>();

const message = useMessage();
const dialog = useDialog();
const rows = ref<DataBlockRow[]>([]);
const loading = ref(false);
/** 勾选的数据块主键，重新加载列表时清空 */
const checkedKeys = ref<number[]>([]);
const providers = ref<ProviderItem[]>([]);
const dataSources = ref<DataSourceItem[]>([]);
const editing = ref<Partial<DataBlockRow> | null>(null);
/** 编辑前的块编码，用来判断「编码被改过」才显示那条同步模板的提示 */
const originalCode = ref('');
const saving = ref(false);
const showEditor = ref(false);
/** 编辑弹窗右侧的说明侧栏：默认收起，表单占满整宽；打开弹窗时复位 */
const asideOpen = ref(false);

/* ------------------------------------------------------------ 数据源预览 */
/** 预览弹窗：配完数据块先跑一次，看 Provider / 接口 / SQL 到底返回什么 */
const showPreview = ref(false);
const previewLoading = ref(false);
const previewTitle = ref('');
const previewError = ref('');
const previewJson = ref('');
const previewElapsed = ref<number | null>(null);
/** 参数映射里以 $request. 开头的键，预览时需要人工填值 */
const previewParams = ref<Array<{ key: string; value: string }>>([]);
/** 本次预览用的块定义（编辑中的未保存内容也走这里） */
const previewSource = ref<Record<string, any> | null>(null);
/** 结果的展示方式：原始 JSON / 表格；默认沿用上次选择 */
const previewMode = ref<'json' | 'table'>('json');
/** 原始结果对象，JSON 与表格两种视图共用同一份数据 */
const previewData = ref<unknown>(null);
/** 本次是否已经成功执行过：用来区分「返回空结果」和「还没跑」 */
const previewDone = ref(false);

const typeOptions = [
  { label: 'Java Provider', value: 'JAVA' },
  { label: 'SQL 数据源', value: 'SQL' },
  { label: 'API 数据源', value: 'API' }
];

const statusOptions = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' }
];

/* ------------------------------------------------------------ 表单状态 */
/** 当前类型下要渲染的配置项（来自 dataBlockFields.ts） */
const sourceType = computed<DataSourceType>(
  () => (editing.value?.data_source_type as DataSourceType) ?? 'JAVA'
);
const fields = computed<ConfigField[]>(() => configFields[sourceType.value] ?? []);
const meta = computed(() => dataSourceMeta[sourceType.value]);

/** 配置项占几列：显式 span 优先；多行文本 / JSON / 键值对默认占满整行（3 列） */
function fieldSpan(field: ConfigField) {
  if (field.span) return field.span;
  return field.type === 'textarea' || field.type === 'json' || field.type === 'kv' ? 3 : 1;
}

/** 按 field.group 分组展示，未标注分组的字段自成一组且不显示小标题 */
const fieldGroups = computed(() => {
  const groups: Array<{ title: string; fields: ConfigField[] }> = [];
  for (const field of fields.value) {
    const title = field.group ?? '';
    let group = groups.find((item) => item.title === title);
    if (!group) {
      group = { title, fields: [] };
      groups.push(group);
    }
    group.fields.push(field);
  }
  return groups;
});
/** 表单值：键名与 data_source_config 一致，类型按字段类型转换 */
const configValues = ref<Record<string, any>>({});
/** 配置里不认识、但需要原样保留的键 */
const extraConfig = ref<Record<string, any>>({});
const extraKeys = computed(() => Object.keys(extraConfig.value));
/** 参数映射按「键 / 值」成对编辑，保存时再拼回对象 */
const paramRows = ref<Array<{ key: string; value: string }>>([]);

const IMAGE_KEYS = ['imageFields', 'image_fields', 'images'];
const MERGE_ALIGN_KEYS = ['mergeCellAlign', 'merge_cell_align', 'mergeAlign'];
/** 由「字段值转换」区块单独维护，不放进「未识别的扩展项」 */
const CONVERTER_KEYS = ['converters'];

/* ------------------------------------------------------------ 合并单元格对齐 */
/** 竖向合并出来的那一格，内容垂直怎么对齐；空串 = 不设置（跟随模板，默认靠上） */
const mergeAlign = ref('');

const mergeAlignOptions = [
  { label: '不设置（跟随模板，默认靠上）', value: '' },
  { label: '靠上', value: 'top' },
  { label: '居中', value: 'center' },
  { label: '靠下', value: 'bottom' }
];

const TOP_WORDS = ['top', 'upper'];
const CENTER_WORDS = ['center', 'centre', 'middle', 'mid'];
const BOTTOM_WORDS = ['bottom', 'lower'];

/**
 * 与后端 DocTemplateMergeAlign.parse 保持同一套归一化：只挑垂直方向的词，
 * 老配置里的水平词（left / right）直接忽略；认不出时返回空串（= 不设置）。
 */
function normalizeMergeAlign(raw: unknown): string {
  const text = String(raw ?? '')
    .trim()
    .toLowerCase()
    .replace(/[-_\s,/]+/g, '-');
  if (!text) return '';
  let vertical = '';
  for (const part of text.split('-')) {
    if (TOP_WORDS.includes(part)) vertical = 'top';
    else if (BOTTOM_WORDS.includes(part)) vertical = 'bottom';
    else if (CENTER_WORDS.includes(part)) vertical = 'center';
  }
  return vertical;
}

function readMergeAlign(config: Record<string, any>): string {
  for (const key of MERGE_ALIGN_KEYS) {
    const value = normalizeMergeAlign(config?.[key]);
    if (value) return value;
  }
  return '';
}

/* ------------------------------------------------------------ 图片字段 */
type ImageAlign = 'CENTER' | 'LEFT' | 'RIGHT';

interface ImageFieldRow {
  field: string;
  width: number | null;
  height: number | null;
  align: ImageAlign;
}

/** 数据块配置里声明「这个字段是图片」，渲染时占位符会变成图片而不是文本 */
const imageFields = ref<ImageFieldRow[]>([]);

const alignOptions = [
  { label: '居中', value: 'CENTER' },
  { label: '居左', value: 'LEFT' },
  { label: '居右', value: 'RIGHT' }
];

function toInt(value: unknown): number | null {
  const num = Number(value);
  return Number.isFinite(num) && num > 0 ? Math.round(num) : null;
}

function toAlign(value: unknown): ImageAlign {
  const text = String(value ?? '').trim().toUpperCase();
  if (text === 'LEFT' || text === 'START') return 'LEFT';
  if (text === 'RIGHT' || text === 'END') return 'RIGHT';
  return 'CENTER';
}

/** 兼容 imageFields / image_fields / images，以及字符串、数组、对象三种写法 */
function readImageFields(config: Record<string, any>): ImageFieldRow[] {
  const raw = config?.imageFields ?? config?.image_fields ?? config?.images;
  if (!raw) return [];
  const list = Array.isArray(raw) ? raw : typeof raw === 'string' ? raw.split(',') : [raw];
  const rows: ImageFieldRow[] = [];
  for (const item of list) {
    if (typeof item === 'string') {
      const [field, size] = item.split(':');
      const name = (field ?? '').trim();
      if (!name) continue;
      const [width, height] = String(size ?? '').split(/[x*]/);
      rows.push({ field: name, width: toInt(width), height: toInt(height), align: 'CENTER' });
      continue;
    }
    if (item && typeof item === 'object') {
      const name = String(item.field ?? item.name ?? item.key ?? '').trim();
      if (!name) continue;
      rows.push({
        field: name,
        width: toInt(item.width ?? item.w),
        height: toInt(item.height ?? item.h),
        align: toAlign(item.align ?? item.alignment ?? item.position)
      });
    }
  }
  return rows;
}

/** 保存时写回 config.imageFields / mergeCellAlign / converters（清掉旧的别名键，避免两份配置打架） */
function withRenderOptions(config: Record<string, any>): Record<string, any> {
  const result: Record<string, any> = { ...config };
  delete result.imageFields;
  delete result.image_fields;
  delete result.images;
  for (const key of MERGE_ALIGN_KEYS) delete result[key];
  delete result.converters;
  const list = imageFields.value
    .map((row) => ({
      field: (row.field ?? '').trim(),
      width: toInt(row.width),
      height: toInt(row.height),
      align: toAlign(row.align)
    }))
    .filter((row) => !!row.field)
    .map((row) => {
      const item: Record<string, any> = { field: row.field, align: row.align };
      if (row.width) item.width = row.width;
      if (row.height) item.height = row.height;
      return item;
    });
  if (list.length) result.imageFields = list;
  if (mergeAlign.value) result.mergeCellAlign = mergeAlign.value;
  const converters = buildConverters();
  if (converters.length) result.converters = converters;
  return result;
}

function addImageField() {
  imageFields.value.push({ field: '', width: null, height: null, align: 'CENTER' });
}

function removeImageField(index: number) {
  imageFields.value.splice(index, 1);
}

/* ------------------------------------------------------------ 字段值转换 */
/**
 * 数据块配置里的 `converters`：取数完成后把某些字段的值换成展示值。
 *
 * 结构与后端一致：`[{field, type, args}]`，`field` 可以写 `a,b` 一次覆盖多个字段。
 * 模板里也能用 `${amount|number:2}` 就地覆盖（模板优先，因为渲染发生在数据块之后）。
 *
 * 参数名由转换器自己定义，这里按 `CONVERTER_META` 渲染成固定行（每个控件都带 label），
 * 不再让人手敲参数名；宿主自定义的转换器没有元数据，下拉里统一归到「自定义转换器」，
 * 具体是哪一个在下面的参数区用查询下拉选。
 */
interface ConverterRow {
  field: string;
  type: string;
  /** 是不是宿主自定义转换器（`type` 不在 CONVERTER_META 里）：决定下拉显示哪一项 */
  custom: boolean;
  /** 已知参数：key 由转换器固定，界面按控件类型渲染 */
  args: Record<string, string>;
  /** 转换器没声明的参数（宿主自定义、或老配置里多出来的），原样保留、手工编辑 */
  extraArgs: Array<{ key: string; value: string }>;
}

/** 「转换器」下拉里代表「自定义」的那一项，不会写进配置 */
const CUSTOM_TYPE = '__custom__';

/** 参数控件的形态：文本框 / 数字 / 开关 / 固定下拉 / 可输入下拉 / 字典下拉 */
type ArgControl = 'text' | 'number' | 'switch' | 'select' | 'pattern' | 'dict';

interface ConverterArg {
  key: string;
  /** 界面上显示的中文名，说明这个值填的是什么 */
  label: string;
  control: ArgControl;
  placeholder?: string;
  options?: Array<{ label: string; value: string }>;
  min?: number;
  max?: number;
  /** 允许在下拉里直接输入新值（日期格式、fallback 字面量） */
  tag?: boolean;
  /** 新建规则 / 切换类型时带上的默认值；留空表示「不写这一项」 */
  defaultValue?: string;
  /** 「示例」按钮填的值，没给就用 defaultValue */
  sample?: string;
}

interface ConverterMeta {
  label: string;
  desc: string;
  args: ConverterArg[];
}

const DATE_PATTERNS = [
  { label: 'yyyy-MM-dd', value: 'yyyy-MM-dd' },
  { label: 'yyyy-MM-dd HH:mm', value: 'yyyy-MM-dd HH:mm' },
  { label: 'yyyy-MM-dd HH:mm:ss', value: 'yyyy-MM-dd HH:mm:ss' },
  { label: 'yyyy年MM月dd日', value: 'yyyy年MM月dd日' },
  { label: 'yyyy/MM/dd', value: 'yyyy/MM/dd' },
  { label: 'yyyyMMdd', value: 'yyyyMMdd' },
  { label: 'HH:mm:ss', value: 'HH:mm:ss' }
];

const ROUNDING_OPTIONS = [
  { label: '四舍五入（默认）', value: 'half_up' },
  { label: '银行家舍入', value: 'half_even' },
  { label: '直接截断', value: 'down' },
  { label: '远离零进位', value: 'up' }
];

/** 内置转换器的中文名、说明与**固定参数表**：参数名只在这里出现一次，界面按它渲染 */
const CONVERTER_META: Record<string, ConverterMeta> = {
  date: {
    label: '日期格式化',
    desc: '时间戳或日期字符串按指定格式输出，认不出来的值原样输出',
    args: [
      {
        key: 'pattern',
        label: '日期格式',
        control: 'pattern',
        options: DATE_PATTERNS,
        defaultValue: 'yyyy-MM-dd',
        sample: 'yyyy-MM-dd HH:mm',
        placeholder: '选择或直接输入，如 yyyy年MM月dd日'
      }
    ]
  },
  number: {
    label: '数字格式',
    desc: '固定小数位、千分位、前后缀；不填小数位就保留原精度',
    args: [
      { key: 'scale', label: '小数位', control: 'number', min: 0, max: 6, placeholder: '不填＝原精度', sample: '2' },
      { key: 'grouping', label: '千分位', control: 'switch', sample: 'true' },
      { key: 'rounding', label: '舍入方式', control: 'select', options: ROUNDING_OPTIONS, sample: 'half_up' },
      { key: 'prefix', label: '前缀', control: 'text', placeholder: '如 ¥' },
      { key: 'suffix', label: '后缀', control: 'text', placeholder: '如 元' }
    ]
  },
  boolean: {
    label: '是否',
    desc: '0/1、Y/N、true/false 换成文字',
    args: [
      { key: 'trueText', label: '真值显示为', control: 'text', placeholder: '默认「是」', sample: '是' },
      { key: 'falseText', label: '假值显示为', control: 'text', placeholder: '默认「否」', sample: '否' }
    ]
  },
  percent: {
    label: '百分比',
    desc: '小数转百分比，默认乘 100 再加百分号',
    args: [
      { key: 'scale', label: '小数位', control: 'number', min: 0, max: 6, placeholder: '默认 2', sample: '2' },
      { key: 'symbol', label: '百分号', control: 'text', placeholder: '默认 %' },
      { key: 'multiply', label: '乘以 100', control: 'switch', sample: 'false' }
    ]
  },
  upper: { label: '转大写', desc: '字母转大写，其他字符不变', args: [] },
  lower: { label: '转小写', desc: '字母转小写，其他字符不变', args: [] },
  trim: {
    label: '去首尾空白',
    desc: '去掉首尾空格，可选把中间连续空白压成一个',
    args: [{ key: 'collapse', label: '压缩中间空白', control: 'switch', sample: 'true' }]
  },
  mask: {
    label: '脱敏',
    desc: '手机号 / 身份证打码；填了保留位数就以它为准',
    args: [
      {
        key: 'mode',
        label: '脱敏方案',
        control: 'select',
        options: [
          { label: '手机号（前 3 后 4）', value: 'phone' },
          { label: '身份证（前 6 后 4）', value: 'idcard' }
        ],
        defaultValue: 'phone'
      },
      { key: 'keepStart', label: '保留前 N 位', control: 'number', min: 0, max: 20, placeholder: '跟随方案' },
      { key: 'keepEnd', label: '保留后 N 位', control: 'number', min: 0, max: 20, placeholder: '跟随方案' },
      { key: 'mask', label: '掩码字符', control: 'text', placeholder: '默认 *' }
    ]
  },
  dict: {
    label: '数据字典',
    desc: '按字典把值换成显示名；字典里查不到时按「查不到时」处理',
    args: [
      { key: 'code', label: '字典', control: 'dict', placeholder: '搜索字典名称或编码' },
      {
        key: 'fallback',
        label: '查不到时',
        control: 'select',
        tag: true,
        options: [
          { label: '原样输出（默认）', value: 'original' },
          { label: '输出空串', value: 'blank' }
        ],
        placeholder: '原样输出，或直接输入要显示的字'
      }
    ]
  }
};

const converterRows = ref<ConverterRow[]>([]);
/** 后端注册的转换器编码（内置 + 宿主自定义） */
const converterCodes = ref<string[]>([]);
/** 字典下拉的选项，来自 `/dicts` */
const dictOptions = ref<Array<{ label: string; value: string }>>([]);
const dictLoading = ref(false);

/** 下拉里的顺序：内置按常用程度排（date / number / boolean …） */
const CONVERTER_ORDER = Object.keys(CONVERTER_META);

/** 宿主注册的自定义转换器编码（不在 CONVERTER_META 里的那些） */
const customCodes = computed(() => converterCodes.value.filter((code) => !isBuiltin(code)));

/**
 * 「转换器」下拉：只列内置转换器，外加一个「自定义转换器」入口。
 * 宿主注册了几个自定义转换器不影响这里 —— 具体是哪一个在下面的参数区选。
 */
const converterOptions = computed(() => {
  const codes = new Set(converterCodes.value.filter((code) => isBuiltin(code)));
  // 已配置但后端没返回的编码也补进来，避免下拉显示空白
  for (const row of converterRows.value) if (row.type && isBuiltin(row.type)) codes.add(row.type);
  const options: Array<{ label: string; value: string; disabled?: boolean }> = [...codes]
    .sort((left, right) => CONVERTER_ORDER.indexOf(left.toLowerCase()) - CONVERTER_ORDER.indexOf(right.toLowerCase()))
    .map((code) => ({ label: `${CONVERTER_META[code.toLowerCase()].label}（${code}）`, value: code }));
  options.push({
    label: '自定义转换器',
    value: CUSTOM_TYPE,
    // 一个自定义转换器都没注册时不给选，免得点进去是一片空下拉
    disabled: customCodes.value.length === 0
  });
  return options;
});

/** 「转换器」下拉显示什么：自定义统一显示「自定义转换器」 */
function typeSelectValue(row: ConverterRow): string {
  return row.custom ? CUSTOM_TYPE : row.type;
}

/** 某个自定义转换器编码的候选（后端注册的 + 配置里已经在用的，后者可能已经下线） */
function customOptionsFor(row: ConverterRow): Array<{ label: string; value: string }> {
  const codes = new Set(customCodes.value);
  if (row.type && !isBuiltin(row.type)) codes.add(row.type);
  return [...codes].sort().map((code) => ({ label: code, value: code }));
}

/** 「转换器」下拉切换：选到「自定义转换器」就切到自定义模式，具体编码留给下面的下拉 */
function handleTypeSelect(row: ConverterRow, value: string) {
  if (value === CUSTOM_TYPE) {
    row.custom = true;
    row.type = customCodes.value[0] ?? '';
    row.args = {};
    return;
  }
  handleConverterTypeChange(row, value);
}

/** 该类型在界面上的固定参数表；宿主自定义的返回空数组 */
function specArgs(row: ConverterRow): ConverterArg[] {
  return CONVERTER_META[(row.type ?? '').toLowerCase()]?.args ?? [];
}

function isBuiltin(type: string): boolean {
  return Boolean(CONVERTER_META[(type ?? '').toLowerCase()]);
}

function converterDesc(row: ConverterRow): string {
  const customDesc = '宿主自定义转换器：具体是哪个在下面选，参数名由它的实现决定';
  if (row.custom) return customDesc;
  if (!row.type) return '先选转换器';
  const meta = CONVERTER_META[row.type.toLowerCase()];
  return meta ? meta.desc : customDesc;
}

function argValue(row: ConverterRow, arg: ConverterArg): string {
  return row.args?.[arg.key] ?? '';
}

/** 空串等于「不写这一项」，保存时会被丢掉（让后端用它自己的默认值） */
function setArg(row: ConverterRow, arg: ConverterArg, value: unknown) {
  const text = value == null ? '' : String(value);
  if (!row.args) row.args = {};
  if (text === '') delete row.args[arg.key];
  else row.args[arg.key] = text;
}

function argNumber(row: ConverterRow, arg: ConverterArg): number | null {
  const text = argValue(row, arg);
  if (!text) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

function argBool(row: ConverterRow, arg: ConverterArg): boolean {
  return argValue(row, arg) === 'true';
}

/** 必填但还没填的项：自定义转换器没选具体编码、dict 没选字典。保存不拦，界面先提示 */
function missingRequired(row: ConverterRow): string[] {
  const missing: string[] = [];
  if (row.custom && !row.type) missing.push('自定义转换器');
  for (const arg of specArgs(row)) {
    if (arg.control === 'dict' && !argValue(row, arg)) missing.push(arg.label);
  }
  return missing;
}

/** 该类型的默认参数（只有明确写了 defaultValue 的才带上） */
function seedArgs(type: string): Record<string, string> {
  const seeded: Record<string, string> = {};
  for (const arg of CONVERTER_META[(type ?? '').toLowerCase()]?.args ?? []) {
    if (arg.defaultValue) seeded[arg.key] = arg.defaultValue;
  }
  return seeded;
}

/** 兼容数组写法与 `{字段:{type,args}}` 写法，统一成行；认不出的参数进 extraArgs */
function readConverters(config: Record<string, any>): ConverterRow[] {
  const raw = config?.converters;
  if (!raw) return [];
  const rows: ConverterRow[] = [];
  const push = (field: string, definition: any) => {
    const name = String(field ?? '').trim();
    const type = String(definition?.type ?? '').trim();
    if (!name || !type) return;
    const known = new Set(specKeys(type));
    const args: Record<string, string> = {};
    const extraArgs: Array<{ key: string; value: string }> = [];
    const rawArgs = definition?.args;
    if (rawArgs && typeof rawArgs === 'object' && !Array.isArray(rawArgs)) {
      for (const [key, value] of Object.entries(rawArgs as Record<string, unknown>)) {
        const text = value == null ? '' : String(value);
        if (known.has(key)) args[key] = text;
        else extraArgs.push({ key, value: text });
      }
    }
    rows.push({ field: name, type, custom: !isBuiltin(type), args, extraArgs });
  };
  if (Array.isArray(raw)) {
    for (const item of raw) push(item?.field, item);
  } else if (typeof raw === 'object') {
    for (const [field, definition] of Object.entries(raw as Record<string, any>)) push(field, definition);
  }
  return rows;
}

function specKeys(type: string): string[] {
  return (CONVERTER_META[(type ?? '').toLowerCase()]?.args ?? []).map((arg) => arg.key);
}

/** 行 → `[{field,type,args}]`；参数值统一按字符串写，转换器自己解析 */
function buildConverters(): Array<Record<string, any>> {
  const list: Array<Record<string, any>> = [];
  for (const row of converterRows.value) {
    const field = (row.field ?? '').trim();
    const type = (row.type ?? '').trim();
    if (!field || !type) continue;
    const args: Record<string, string> = {};
    for (const [key, value] of Object.entries(row.args ?? {})) {
      const text = value == null ? '' : String(value);
      if (key && text !== '') args[key] = text;
    }
    for (const arg of row.extraArgs ?? []) {
      const key = (arg.key ?? '').trim();
      if (key) args[key] = arg.value ?? '';
    }
    const item: Record<string, any> = { field, type };
    if (Object.keys(args).length) item.args = args;
    list.push(item);
  }
  return list;
}

function addConverter() {
  const codes = converterCodes.value;
  const type = codes.includes('date') ? 'date' : (codes.find((code) => isBuiltin(code)) ?? 'date');
  converterRows.value.push({ field: '', type, custom: false, args: seedArgs(type), extraArgs: [] });
}

function removeConverter(index: number) {
  converterRows.value.splice(index, 1);
}

/** 换类型时只保留同名参数（number 与 percent 都有 scale），其余挪进「自定义参数」不丢 */
function handleConverterTypeChange(row: ConverterRow, type?: string) {
  if (type) row.type = type;
  row.custom = false;
  const spec = specArgs(row);
  const known = new Set(spec.map((arg) => arg.key));
  const next: Record<string, string> = {};
  for (const arg of spec) {
    const current = row.args?.[arg.key];
    if (current) next[arg.key] = current;
    else if (arg.defaultValue) next[arg.key] = arg.defaultValue;
  }
  if (!row.extraArgs) row.extraArgs = [];
  for (const [key, value] of Object.entries(row.args ?? {})) {
    if (!known.has(key) && value) row.extraArgs.push({ key, value });
  }
  row.args = next;
}

/** 一键填一组能跑的示例值 */
function fillConverterSample(row: ConverterRow) {
  if (row.custom) {
    message.info('自定义转换器的参数由宿主定义，请在下面按「参数名 / 参数值」填');
    return;
  }
  const next: Record<string, string> = {};
  for (const arg of specArgs(row)) {
    const value = arg.sample ?? arg.defaultValue;
    if (value) next[arg.key] = value;
  }
  row.args = next;
  if (!specArgs(row).length) message.info('该转换器没有参数可填');
  else message.success('已填入该转换器的示例参数');
}

/* ------------------------------------------------------------ 配置项表单 */
function clone<T>(value: T): T {
  return value == null ? value : (JSON.parse(JSON.stringify(value)) as T);
}

/** 下拉选项：Provider / 数据源来自后端，其余来自字段定义 */
function fieldOptions(field: ConfigField) {
  if (field.dynamic === 'providers') {
    return providers.value.map((item) => ({ label: `${item.code} · ${item.implementation}`, value: item.code }));
  }
  if (field.dynamic === 'dataSources') {
    return [
      { label: '（默认：宿主主库）', value: '' },
      ...dataSources.value.map((item) => ({
        label: item.description ? `${item.code} · ${item.description}` : item.code,
        value: item.code
      }))
    ];
  }
  return field.options ?? [];
}

/**
 * 动态下拉（Provider / 数据源）的搜索：label 和 value 一起匹配，大小写不敏感。
 * Provider 的 label 是「code · 实现类全名」，按编码或类名任一段都能搜到。
 */
function filterOption(pattern: string, option: Record<string, any>) {
  const keyword = pattern.trim().toLowerCase();
  if (!keyword) return true;
  const text = `${String(option.label ?? '')} ${String(option.value ?? '')}`.toLowerCase();
  return text.includes(keyword);
}

/** 新建时的默认值 */
function defaultsFor(type: DataSourceType): Record<string, any> {
  const values: Record<string, any> = {};
  for (const field of configFields[type] ?? []) {
    if (field.type === 'number') values[field.key] = null;
    else if (field.type === 'kv') values[field.key] = [];
    else if (field.type === 'boolean') values[field.key] = field.defaultValue ?? 'false';
    else values[field.key] = '';
  }
  if (type === 'JAVA') values.provider = providers.value[0]?.code ?? '';
  if (type === 'API') {
    values.method = 'GET';
    values.timeoutMs = 5000;
  }
  return values;
}

function kvToRows(value: unknown): Array<{ key: string; value: string }> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [];
  return Object.entries(value as Record<string, unknown>).map(([key, val]) => ({
    key,
    value: typeof val === 'string' ? val : JSON.stringify(val)
  }));
}

function rowsToKv(list: Array<{ key: string; value: string }>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const row of list ?? []) {
    const key = (row.key ?? '').trim();
    if (key) result[key] = row.value ?? '';
  }
  return result;
}

/** 把已有的 JSON 配置拆成「表单值 + 未识别键」 */
function applyConfig(type: DataSourceType, config: Record<string, any>) {
  const values: Record<string, any> = {};
  const known = new Set<string>();
  for (const field of configFields[type] ?? []) {
    known.add(field.key);
    const raw = config?.[field.key];
    switch (field.type) {
      case 'boolean':
        // 表单里用 'true' / 'false' 两个字符串，写回配置时再转成真正的布尔
        values[field.key] =
          raw == null ? (field.defaultValue ?? 'false') : raw === true || raw === 'true' ? 'true' : 'false';
        break;
      case 'number':
        values[field.key] = raw == null || raw === '' ? null : Number(raw);
        break;
      case 'kv':
        values[field.key] = kvToRows(raw);
        break;
      case 'json':
        values[field.key] = raw == null ? '' : JSON.stringify(raw, null, 2);
        break;
      default:
        values[field.key] = raw == null ? '' : String(raw);
    }
  }
  configValues.value = values;

  const extra: Record<string, any> = {};
  for (const [key, value] of Object.entries(config ?? {})) {
    if (known.has(key) || IMAGE_KEYS.includes(key) || MERGE_ALIGN_KEYS.includes(key) || CONVERTER_KEYS.includes(key)) continue;
    extra[key] = value;
  }
  extraConfig.value = extra;
}

function numberOrNull(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const num = Number(value);
  return Number.isFinite(num) ? Math.round(num) : null;
}

/** 表单值 → data_source_config 对象（未识别的键原样带回） */
function buildConfig(): Record<string, any> | null {
  const config: Record<string, any> = { ...extraConfig.value };
  for (const field of fields.value) {
    const value = configValues.value[field.key];
    if (field.type === 'boolean') {
      config[field.key] = value === 'true';
      continue;
    }
    if (field.type === 'number') {
      const num = numberOrNull(value);
      if (num != null) config[field.key] = num;
      continue;
    }
    if (field.type === 'kv') {
      const map = rowsToKv(value ?? []);
      if (Object.keys(map).length) config[field.key] = map;
      continue;
    }
    if (field.type === 'json') {
      const text = String(value ?? '').trim();
      if (!text) continue;
      const parsed = tryParseJson(text);
      if (!parsed.ok) {
        message.error(`「${field.label}」不是合法 JSON：${parsed.error}`);
        return null;
      }
      config[field.key] = parsed.value;
      continue;
    }
    const text = String(value ?? '').trim();
    if (text) config[field.key] = text;
  }
  for (const field of fields.value) {
    if (!field.required) continue;
    const value = config[field.key];
    if (value === undefined || value === null || value === '') {
      message.warning(`请填写「${field.label}」`);
      return null;
    }
  }
  return config;
}

/** 单字段「填入示例」 */
function fillField(field: ConfigField) {
  if (field.sample === undefined) return;
  const sample = clone(field.sample);
  if (field.type === 'kv') configValues.value[field.key] = kvToRows(sample);
  else if (field.type === 'json') configValues.value[field.key] = JSON.stringify(sample, null, 2);
  else configValues.value[field.key] = sample;
  message.success(`已填入「${field.label}」示例`);
}

/* ------------------------------------------------------------ 参数映射 */
function toParamRows(raw: string | null | undefined): Array<{ key: string; value: string }> {
  const parsed = tryParseJson(raw ?? '{}');
  const value = parsed.ok ? parsed.value : null;
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [];
  return Object.entries(value as Record<string, unknown>).map(([key, val]) => ({
    key,
    value: typeof val === 'string' ? val : JSON.stringify(val)
  }));
}

/** 值里除了 $request.xxx，也允许写数字/布尔/JSON 字面量 */
function paramValueOf(text: string): unknown {
  const raw = (text ?? '').trim();
  if (!raw) return '';
  if (raw.startsWith('$request')) return raw;
  if (raw === 'true' || raw === 'false' || raw === 'null') return JSON.parse(raw);
  if (/^-?\d+(\.\d+)?$/.test(raw)) return Number(raw);
  if (raw.startsWith('{') || raw.startsWith('[')) {
    const parsed = tryParseJson(raw);
    if (parsed.ok) return parsed.value;
  }
  return raw;
}

function buildParams(): Record<string, unknown> {
  const params: Record<string, unknown> = {};
  for (const row of paramRows.value) {
    const key = (row.key ?? '').trim();
    if (key) params[key] = paramValueOf(row.value);
  }
  return params;
}

function fillParamSample() {
  paramRows.value = Object.entries(paramSample).map(([key, value]) => ({ key, value }));
  message.success('已填入参数映射示例');
}

/* ------------------------------------------------------------ 列表与保存 */
async function load() {
  checkedKeys.value = [];
  if (!props.templateId) {
    rows.value = [];
    return;
  }
  loading.value = true;
  try {
    rows.value = await api<DataBlockRow[]>(`/data-blocks?templateId=${props.templateId}`);
  } catch (e: any) {
    message.error(e.message);
  } finally {
    loading.value = false;
  }
}

async function loadProviders() {
  try {
    providers.value = await api<ProviderItem[]>('/system/providers');
  } catch {
    providers.value = [];
  }
}

/** SQL 数据块的 dataSource 字段可以填什么，由后端列出 */
async function loadDataSources() {
  try {
    dataSources.value = await api<DataSourceItem[]>('/system/data-sources');
  } catch {
    dataSources.value = [];
  }
}

/** 可用的字段值转换器（内置 + 宿主注册），下拉选项用 */
async function loadConverters() {
  try {
    converterCodes.value = await api<string[]>('/system/converters');
  } catch {
    converterCodes.value = Object.keys(CONVERTER_META);
  }
}

/** 字典下拉的选项：dict 转换器的 code 只能从已有的字典里选，不要手敲编码 */
async function loadDicts() {
  dictLoading.value = true;
  try {
    const rows = await api<DictRow[]>('/dicts');
    dictOptions.value = (rows ?? []).map((item) => ({
      label: `${item.dict_name}（${item.dict_code}）`,
      value: item.dict_code
    }));
  } catch {
    dictOptions.value = [];
  } finally {
    dictLoading.value = false;
  }
}

function openCreate() {
  // 字段名必须是后端返回的 snake_case，否则弹窗里的 v-model 拿不到初值
  editing.value = {
    block_code: '',
    block_name: '',
    data_source_type: 'JAVA',
    status: 'ENABLED'
  };
  originalCode.value = '';
  configValues.value = defaultsFor('JAVA');
  extraConfig.value = {};
  paramRows.value = [];
  imageFields.value = [];
  converterRows.value = [];
  mergeAlign.value = '';
  asideOpen.value = false;
  showEditor.value = true;
  void loadDicts();
}

function openEdit(row: DataBlockRow) {
  editing.value = { ...row };
  originalCode.value = row.block_code ?? '';
  const parsed = tryParseJson(row.data_source_config);
  const config = parsed.ok && parsed.value && typeof parsed.value === 'object' ? parsed.value : {};
  const type = (row.data_source_type ?? 'JAVA') as DataSourceType;
  applyConfig(type, config as Record<string, any>);
  paramRows.value = toParamRows(row.param_config);
  imageFields.value = readImageFields(config as Record<string, any>);
  converterRows.value = readConverters(config as Record<string, any>);
  mergeAlign.value = readMergeAlign(config as Record<string, any>);
  asideOpen.value = false;
  showEditor.value = true;
  void loadDicts();
}

/** 切换数据源类型：配置项完全不同，直接按新类型重置（参数映射与图片字段保留） */
function handleTypeChange(next: DataSourceType) {
  configValues.value = defaultsFor(next);
  extraConfig.value = {};
  message.info(`已切换为「${dataSourceMeta[next].label}」，配置项已重置`);
}

/** 新建数据块排在最后：取现有最大 sort_order + 1，避免和历史数据撞号插到中间 */
function nextSortOrder() {
  return rows.value.reduce((max, row) => Math.max(max, Number(row.sort_order) || 0), 0) + 1;
}

async function save() {
  if (!props.templateId || !editing.value) return;
  const code = (editing.value.block_code ?? '').trim();
  const name = (editing.value.block_name ?? '').trim();
  if (!code) {
    message.warning('请填写数据块编码');
    return;
  }
  if (!name) {
    message.warning('请填写数据块名称');
    return;
  }
  const config = buildConfig();
  if (!config) return;
  const body = {
    blockCode: code,
    blockName: name,
    dataSourceType: editing.value.data_source_type,
    dataSourceConfig: withRenderOptions(config),
    paramConfig: buildParams(),
    // 排序不再让人填：新建的排在最后，编辑保持原值，执行顺序就是列表顺序
    sortOrder: editing.value.sort_order ?? nextSortOrder(),
    status: editing.value.status ?? 'ENABLED'
  };
  saving.value = true;
  try {
    if (editing.value.id) {
      await api(`/data-blocks/${editing.value.id}`, { method: 'PUT', body: JSON.stringify(body) });
    } else {
      await api(`/data-blocks?templateId=${props.templateId}`, { method: 'POST', body: JSON.stringify(body) });
    }
    message.success('数据块已保存');
    showEditor.value = false;
    await load();
  } catch (e: any) {
    message.error(e.message);
  } finally {
    saving.value = false;
  }
}

async function remove(row: DataBlockRow) {
  try {
    await api(`/data-blocks/${row.id}`, { method: 'DELETE' });
    message.success('已删除数据块');
    await load();
  } catch (e: any) {
    message.error(e.message);
  }
}

function confirmRemove(row: DataBlockRow) {
  dialog.warning({
    title: '删除数据块',
    content: `确认删除数据块「${row.block_name}」（${row.block_code}）？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => remove(row)
  });
}

/** 批量删除：后端逐条删，单条失败不影响其他条 */
async function batchRemove() {
  const ids = [...checkedKeys.value];
  if (!ids.length) return;
  try {
    const res = await api<BatchDeleteResult>('/data-blocks/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ ids })
    });
    checkedKeys.value = [];
    await load();
    const summary = batchDeleteSummary('数据块', res);
    message[summary.type](summary.text);
  } catch (e: any) {
    message.error(e.message);
  }
}

/** 取消勾选：批量操作条上的「取消选择」 */
function clearSelection() {
  checkedKeys.value = [];
}

function isRowSelected(row: DataBlockRow): boolean {
  return checkedKeys.value.includes(row.id);
}

/** 点行 = 勾选 / 取消勾选；勾选框和操作列按钮的点击不接管（它们自己会处理） */
function toggleRow(row: DataBlockRow, event: MouseEvent) {
  if (isInteractiveClick(event)) return;
  checkedKeys.value = isRowSelected(row)
    ? checkedKeys.value.filter((key) => key !== row.id)
    : [...checkedKeys.value, row.id];
}

const rowProps = (row: DataBlockRow) => ({
  style: 'cursor: pointer;',
  onClick: (event: MouseEvent) => toggleRow(row, event)
});

const rowClassName = (row: DataBlockRow) => (isRowSelected(row) ? 'is-checked' : '');

function confirmBatchRemove() {
  const count = checkedKeys.value.length;
  if (!count) return;
  dialog.warning({
    title: '批量删除数据块',
    content: `确认删除选中的 ${count} 个数据块？删除后模板里引用它们的占位符会取不到值。`,
    positiveText: `删除 ${count} 个`,
    negativeText: '取消',
    onPositiveClick: () => batchRemove()
  });
}

/* ------------------------------------------------------------ 数据源预览 */
/** 从参数映射里挑出 `$request.xxx`：这些值生成时要由调用方给，预览时就得手填 */
function requestParamKeys(rows: Array<{ key: string; value: string }>) {
  const keys: string[] = [];
  for (const row of rows ?? []) {
    const value = (row.value ?? '').trim();
    if (!value.startsWith('$request.')) continue;
    const path = value.slice('$request.'.length).trim();
    if (path && !keys.includes(path)) keys.push(path);
  }
  return keys;
}

/** `a.b` 形式的参数名还原成嵌套对象，和执行器 `at(request, 'a.b')` 的取值方式对齐 */
function setParamPath(target: Record<string, any>, path: string, value: string) {
  const parts = path.split('.');
  let node = target;
  for (let i = 0; i < parts.length; i += 1) {
    const part = parts[i];
    if (i === parts.length - 1) {
      node[part] = value;
      return;
    }
    if (typeof node[part] !== 'object' || node[part] === null) node[part] = {};
    node = node[part];
  }
}

function openPreview(payload: Record<string, any>, title: string, paramSource: Array<{ key: string; value: string }>) {
  previewSource.value = payload;
  previewTitle.value = title;
  previewParams.value = requestParamKeys(paramSource).map((key) => ({ key, value: '' }));
  previewJson.value = '';
  previewError.value = '';
  previewElapsed.value = null;
  previewData.value = null;
  previewDone.value = false;
  showPreview.value = true;
  void runPreview();
}

/** 编辑弹窗里预览：用当前表单值，没保存也能测 */
function previewFromEditor() {
  const config = buildConfig();
  if (!config) return;
  const title = editing.value?.block_name || editing.value?.block_code || '数据块';
  openPreview(
    {
      blockName: title,
      dataSourceType: sourceType.value,
      dataSourceConfig: withRenderOptions(config),
      paramConfig: buildParams()
    },
    title,
    paramRows.value
  );
}

/** 列表里预览已保存的数据块 */
function previewFromRow(row: DataBlockRow) {
  const config = tryParseJson(row.data_source_config);
  const params = tryParseJson(row.param_config ?? '{}');
  openPreview(
    {
      blockName: row.block_name,
      dataSourceType: row.data_source_type,
      dataSourceConfig: config.ok ? config.value : {},
      paramConfig: params.ok ? params.value : {}
    },
    row.block_name,
    toParamRows(row.param_config)
  );
}

async function runPreview() {
  if (!previewSource.value) return;
  previewLoading.value = true;
  previewError.value = '';
  previewJson.value = '';
  previewData.value = null;
  previewDone.value = false;
  try {
    const requestParams: Record<string, any> = {};
    for (const param of previewParams.value) {
      const value = (param.value ?? '').trim();
      if (value) setParamPath(requestParams, param.key, value);
    }
    const res = await api<any>('/data-blocks/preview', {
      method: 'POST',
      body: JSON.stringify({ ...previewSource.value, requestParams })
    });
    previewElapsed.value = typeof res?.elapsed_ms === 'number' ? res.elapsed_ms : null;
    if (res?.ok) {
      previewData.value = res.data ?? null;
      previewDone.value = true;
      previewJson.value = JSON.stringify(res.data ?? null, null, 2);
      if (previewJson.value === 'null') previewJson.value = '（空结果）';
    } else {
      previewError.value = res?.error ?? '执行失败';
    }
  } catch (e: any) {
    previewError.value = e.message;
  } finally {
    previewLoading.value = false;
  }
}

/* ------------------------------------------------------------ 预览表格化 */
/**
 * 表格视图的数据结构：一次预览可能拆成多张表。
 * 顶层是数组 → 一张表；顶层是对象 → 「概览」键值表 + 每个数组字段一张子表，
 * 这样 `{ list: [...], total: 3 }` 这种包装结构也能看明白。
 */
interface PreviewTable {
  key: string;
  title: string;
  columns: Array<{ key: string; title: string }>;
  rows: Array<Record<string, string>>;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/** 单元格统一转成文本：对象/数组直接压成 JSON，避免表格里出现 [object Object] */
function cellText(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function valueType(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (Array.isArray(value)) return `array(${value.length})`;
  if (typeof value === 'object') return 'object';
  return typeof value;
}

/** 数组 → 表格：元素都是对象时取所有键的并集做列，否则压成单列「值」 */
function tableFromArray(key: string, title: string, list: unknown[]): PreviewTable {
  const objects = list.filter(isPlainObject);
  if (objects.length && objects.length === list.length) {
    const keys: string[] = [];
    for (const item of objects) {
      for (const name of Object.keys(item)) {
        if (!keys.includes(name)) keys.push(name);
      }
    }
    return {
      key,
      title,
      columns: [{ key: '__index', title: '#' }, ...keys.map((name) => ({ key: name, title: name }))],
      rows: objects.map((item, index) => {
        const row: Record<string, string> = { __index: String(index + 1) };
        for (const name of keys) row[name] = cellText(item[name]);
        return row;
      })
    };
  }
  return {
    key,
    title,
    columns: [
      { key: '__index', title: '#' },
      { key: '__value', title: '值' }
    ],
    rows: list.map((item, index) => ({ __index: String(index + 1), __value: cellText(item) }))
  };
}

/** 对象 → 键值表（单条记录、或包装结构里的标量字段） */
function tableFromObject(key: string, title: string, obj: Record<string, unknown>): PreviewTable {
  return {
    key,
    title,
    columns: [
      { key: 'field', title: '字段' },
      { key: 'value', title: '值' },
      { key: 'type', title: '类型' }
    ],
    rows: Object.entries(obj).map(([name, value]) => ({
      field: name,
      value: cellText(value),
      type: valueType(value)
    }))
  };
}

const previewTables = computed<PreviewTable[]>(() => {
  const data = previewData.value;
  if (data === null || data === undefined) return [];
  if (Array.isArray(data)) return [tableFromArray('rows', '结果', data)];
  if (isPlainObject(data)) {
    const tables: PreviewTable[] = [];
    const scalars: Record<string, unknown> = {};
    for (const [name, value] of Object.entries(data)) {
      // 数组单独成表，其余（含空数组）留在概览里，一眼看到整体形状
      if (Array.isArray(value) && value.length) tables.push(tableFromArray(name, name, value));
      else scalars[name] = value;
    }
    if (Object.keys(scalars).length) tables.unshift(tableFromObject('overview', '概览', scalars));
    return tables;
  }
  // 顶层是标量：当成单值表，比在 JSON 视图里看一行更直观
  return [tableFromArray('rows', '结果', [data])];
});

/** NDataTable 的列定义：长文本省略 + 悬浮看全文 */
function tableColumns(table: PreviewTable): DataTableColumns<Record<string, string>> {
  return table.columns.map((column) => {
    if (column.key === '__index') return { title: column.title, key: column.key, width: 52 };
    return {
      title: column.title,
      key: column.key,
      minWidth: column.key === 'type' ? 88 : 120,
      ellipsis: { tooltip: true }
    };
  });
}

/** 列表里的配置摘要：只挑最关键的几个字段，避免把整段 SQL 塞进表格 */
function configSummary(row: DataBlockRow) {
  const parsed = tryParseJson(row.data_source_config);
  if (!parsed.ok) return row.data_source_config;
  const value = (parsed.value ?? {}) as Record<string, any>;
  const images = readImageFields(value);
  const imageSuffix = images.length ? ` · 图片：${images.map((item) => item.field).join('、')}` : '';
  const converters = readConverters(value);
  const converterSuffix = converters.length ? ` · 转换：${converters.map((item) => item.field).join('、')}` : '';
  if (row.data_source_type === 'JAVA') return `provider: ${value.provider ?? '未设置'}${imageSuffix}${converterSuffix}`;
  if (row.data_source_type === 'SQL') {
    const sql = String(value.sql ?? '').replace(/\s+/g, ' ').trim();
    const head = sql.length > 90 ? `${sql.slice(0, 90)}…` : sql || '未配置 sql';
    return `${head} · ${value.single === false ? '数组' : '单条'}${imageSuffix}${converterSuffix}`;
  }
  const method = String(value.method ?? 'GET').toUpperCase();
  return `${method} ${value.url ?? '未配置 url'}${imageSuffix}${converterSuffix}`;
}

const columns = [
  // 勾选列固定在左侧，横向滚动时始终可见
  { type: 'selection' as const, fixed: 'left' as const, width: 44 },
  {
    title: '数据块',
    key: 'block_name',
    minWidth: 190,
    render: (row: DataBlockRow) =>
      h('div', null, [
        h('div', { class: 'cell-title' }, row.block_name),
        h('div', { class: 'cell-sub mono' }, row.block_code)
      ])
  },
  {
    title: '数据源',
    key: 'data_source_type',
    width: 120,
    render: (row: DataBlockRow) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.data_source_type === 'JAVA' ? 'info' : 'warning' },
        { default: () => (row.data_source_type === 'JAVA' ? 'Java' : row.data_source_type) }
      )
  },
  {
    title: '配置',
    key: 'config',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row: DataBlockRow) => h('span', { class: 'mono muted' }, configSummary(row))
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: (row: DataBlockRow) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.status === 'ENABLED' ? 'success' : 'default' },
        { default: () => (row.status === 'ENABLED' ? '启用' : '停用') }
      )
  },
  {
    title: '操作',
    key: 'actions',
    width: 176,
    render: (row: DataBlockRow) =>
      rowActions([
        { label: '编辑', tone: 'primary', onClick: () => openEdit(row) },
        { label: '预览', tone: 'info', onClick: () => previewFromRow(row) },
        { label: '删除', tone: 'error', onClick: () => confirmRemove(row) }
      ])
  }
];

watch(() => props.templateId, load, { immediate: true });
onMounted(() => {
  void loadProviders();
  void loadDataSources();
  void loadConverters();
  void loadDicts();
});
</script>

<template>
  <div>
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="muted">
          生成时按列表顺序依次执行启用的数据块，块编码即参数命名空间（如 <span class="mono">${report.report_no}</span>）
        </span>
      </div>
      <div class="toolbar-right">
        <BatchBar
          v-if="checkedKeys.length"
          :count="checkedKeys.length"
          label="数据块"
          @remove="confirmBatchRemove"
          @clear="clearSelection"
        />
        <NButton size="small" @click="load">刷新</NButton>
        <NButton type="primary" size="small" :disabled="!templateId" @click="openCreate">新增数据块</NButton>
      </div>
    </div>

    <NDataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :row-key="(row: DataBlockRow) => row.id"
      :checked-row-keys="checkedKeys"
      :row-props="rowProps"
      :row-class-name="rowClassName"
      :bordered="false"
      size="small"
      :scroll-x="804"
      @update:checked-row-keys="(keys: Array<string | number>) => (checkedKeys = keys as number[])"
    >
      <template #empty>
        <div class="empty-hint">还没有数据块，点击「新增数据块」开始配置</div>
      </template>
    </NDataTable>

    <NModal
      v-model:show="showEditor"
      preset="card"
      class="doc-modal-card"
      :title="editing?.id ? '编辑数据块' : '新增数据块'"
      style="width: min(1080px, 94vw); max-height: 88vh"
    >
      <!-- 说明开关放在标题栏右侧、关闭按钮左边：一个侧边栏图标 -->
      <template #header-extra>
        <NTooltip trigger="hover">
          <template #trigger>
            <NButton
              class="aside-toggle"
              :class="{ 'is-active': asideOpen }"
              size="small"
              quaternary
              circle
              :aria-expanded="asideOpen"
              aria-label="配置说明"
              @click="asideOpen = !asideOpen"
            >
              <template #icon>
                <svg class="aside-icon" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
                  <rect x="1.5" y="2.5" width="13" height="11" rx="2" fill="none" stroke="currentColor" stroke-width="1.2" />
                  <line x1="6" y1="2.5" x2="6" y2="13.5" stroke="currentColor" stroke-width="1.2" />
                </svg>
              </template>
            </NButton>
          </template>
          {{ asideOpen ? '收起配置说明' : '展开配置说明' }}
        </NTooltip>
      </template>

      <div class="editor-body">
        <NForm v-if="editing" class="editor-form" label-placement="top" size="small">
          <div class="basic-grid">
            <NFormItem label="数据块编码" required>
              <NInput v-model:value="editing.block_code" placeholder="例如 report" class="mono" />
              <template #feedback>
                <span v-if="editing.id && editing.block_code !== originalCode" class="code-hint">
                  <span v-pre>改编码不会动模板文件，记得同步改模板里的 ${编码.字段} / {{#each 编码}}，否则生成时取不到这个块。</span>
                </span>
              </template>
            </NFormItem>
            <NFormItem label="数据块名称" required>
              <NInput v-model:value="editing.block_name" placeholder="例如 检验报告主信息" />
            </NFormItem>
            <NFormItem label="数据源类型">
              <NSelect v-model:value="editing.data_source_type" :options="typeOptions" @update:value="handleTypeChange" />
            </NFormItem>
            <NFormItem label="状态">
              <NSelect v-model:value="editing.status" :options="statusOptions" />
            </NFormItem>
          </div>

          <div class="section-head">
            <span class="section-title">数据源配置</span>
            <span class="section-badge">{{ meta.label }}</span>
          </div>
          <div v-if="meta.warning" class="cfg-warn">
            <NIcon :size="13" class="cfg-warn-icon"><WarningOutline /></NIcon>
            <span>{{ meta.warning }}</span>
          </div>

          <!-- 所有配置项共用一张网格：分组标题占满一行，窄字段一行排三个 -->
          <div class="cfg-grid">
            <template v-for="(group, groupIndex) in fieldGroups" :key="group.title || groupIndex">
              <div v-if="group.title" class="cfg-group-title">{{ group.title }}</div>
              <div
                v-for="field in group.fields"
                :key="field.key"
                class="cfg-field"
                :style="{ gridColumn: `span ${fieldSpan(field)}` }"
              >
                <div class="cfg-label">
                  <span class="cfg-label-main">
                    <span class="cfg-label-text">
                      {{ field.label }}<span v-if="field.required" class="field-required">*</span>
                    </span>
                    <code class="cfg-key">{{ field.key }}</code>
                  </span>
                  <NButton
                    v-if="field.sample !== undefined"
                    size="tiny"
                    text
                    type="primary"
                    @click="fillField(field)"
                  >
                    填入示例
                  </NButton>
                </div>

                <NInput
                  v-if="field.type === 'text'"
                  v-model:value="configValues[field.key]"
                  size="small"
                  :placeholder="field.placeholder"
                />
                <NInput
                  v-else-if="field.type === 'textarea'"
                  v-model:value="configValues[field.key]"
                  type="textarea"
                  class="json-editor"
                  :autosize="{ minRows: field.rows ?? 4, maxRows: 14 }"
                  :placeholder="field.placeholder"
                />
                <NInputNumber
                  v-else-if="field.type === 'number'"
                  v-model:value="configValues[field.key]"
                  size="small"
                  :min="0"
                  :show-button="false"
                  :placeholder="field.placeholder"
                >
                  <template v-if="field.suffix" #suffix>{{ field.suffix }}</template>
                </NInputNumber>
                <NSelect
                  v-else-if="field.type === 'select'"
                  v-model:value="configValues[field.key]"
                  size="small"
                  :options="fieldOptions(field)"
                  :placeholder="field.placeholder"
                  :filterable="!!field.dynamic"
                  :filter-option="filterOption"
                  :tag="field.dynamic === 'providers'"
                  :consistent-menu-width="false"
                />
                <div v-else-if="field.type === 'kv'" class="kv-list">
                  <div v-for="(item, index) in configValues[field.key]" :key="index" class="kv-row">
                    <NInput v-model:value="item.key" size="small" class="mono" placeholder="名称" />
                    <NInput v-model:value="item.value" size="small" class="mono" placeholder="值，可用 ${name}" />
                    <NButton size="small" quaternary type="error" @click="configValues[field.key].splice(index, 1)">
                      移除
                    </NButton>
                  </div>
                  <NButton size="tiny" dashed @click="configValues[field.key].push({ key: '', value: '' })">
                    添加一项
                  </NButton>
                </div>
                <NInput
                  v-else-if="field.type === 'json'"
                  v-model:value="configValues[field.key]"
                  type="textarea"
                  class="json-editor"
                  :autosize="{ minRows: field.rows ?? 4, maxRows: 12 }"
                  :placeholder="field.placeholder"
                />
                <!-- 布尔字段用下拉选，两个选项固定是 单条 / 多条 -->
                <NSelect
                  v-else-if="field.type === 'boolean'"
                  v-model:value="configValues[field.key]"
                  size="small"
                  :options="fieldOptions(field)"
                  :consistent-menu-width="false"
                />
              </div>
            </template>

            <!-- 合并单元格对齐：整篇文档一份设置，和窄字段排在同一行 -->
            <div class="cfg-field">
              <div class="cfg-label">
                <span class="cfg-label-main">
                  <span class="cfg-label-text">合并单元格对齐</span>
                  <code class="cfg-key">mergeCellAlign</code>
                </span>
              </div>
              <NSelect v-model:value="mergeAlign" size="small" :options="mergeAlignOptions" />
            </div>
          </div>

          <div class="list-section">
            <div class="list-head">
              <span class="list-title">图片字段</span>
            </div>
            <div class="field-body">
              <div v-if="imageFields.length" class="kv-list">
                <div v-for="(row, index) in imageFields" :key="index" class="image-row">
                  <NInput v-model:value="row.field" size="small" class="mono" placeholder="字段名，如 logo / photo" />
                  <NInputNumber v-model:value="row.width" size="small" :min="1" :max="4000" placeholder="宽 px" />
                  <NInputNumber v-model:value="row.height" size="small" :min="1" :max="4000" placeholder="高 px" />
                  <NSelect v-model:value="row.align" size="small" :options="alignOptions" />
                  <NButton size="small" quaternary type="error" @click="removeImageField(index)">移除</NButton>
                </div>
              </div>
              <div v-else class="field-empty">还没有图片字段；模板里写 <span class="mono">${logo}</span>，取到的值按图片渲染</div>
              <NButton size="tiny" dashed @click="addImageField">添加图片字段</NButton>
            </div>
          </div>

          <div class="list-section">
            <div class="list-head">
              <span class="list-title">字段值转换</span>
            </div>
            <div class="field-body">
              <div v-if="converterRows.length" class="converter-list">
                <div v-for="(row, index) in converterRows" :key="index" class="converter-item">
                  <div class="converter-main">
                    <span class="converter-label">字段</span>
                    <NInput v-model:value="row.field" size="small" class="mono" placeholder="字段名，多个用逗号分隔" />
                    <span class="converter-label">转换器</span>
                    <NSelect
                      size="small"
                      :options="converterOptions"
                      :consistent-menu-width="false"
                      :value="typeSelectValue(row)"
                      @update:value="(value: string) => handleTypeSelect(row, value)"
                    />
                    <NButton size="small" quaternary type="primary" @click="fillConverterSample(row)">示例</NButton>
                    <NButton size="small" quaternary type="error" @click="removeConverter(index)">移除</NButton>
                  </div>
                  <div class="converter-desc">{{ converterDesc(row) }}</div>
                  <div v-if="missingRequired(row).length" class="converter-warn">
                    还差「{{ missingRequired(row).join('、') }}」，不填的话这条规则用不了
                  </div>

                  <div v-if="row.custom" class="converter-args">
                    <div class="converter-arg">
                      <label class="converter-arg-label" title="参数名：type">自定义转换器</label>
                      <div class="converter-arg-control">
                        <NSelect
                          size="small"
                          filterable
                          :options="customOptionsFor(row)"
                          :value="row.type || null"
                          placeholder="搜索转换器编码，如 demoSku"
                          @update:value="(value: string | null) => (row.type = value ?? '')"
                        />
                      </div>
                    </div>
                  </div>

                  <div v-if="specArgs(row).length" class="converter-args">
                    <div v-for="arg in specArgs(row)" :key="arg.key" class="converter-arg">
                      <label class="converter-arg-label" :title="`参数名：${arg.key}`">{{ arg.label }}</label>
                      <div class="converter-arg-control">
                        <NSwitch
                          v-if="arg.control === 'switch'"
                          size="small"
                          :value="argBool(row, arg)"
                          @update:value="(value: boolean) => setArg(row, arg, value ? 'true' : '')"
                        />
                        <NInputNumber
                          v-else-if="arg.control === 'number'"
                          size="small"
                          :value="argNumber(row, arg)"
                          :min="arg.min"
                          :max="arg.max"
                          :show-button="false"
                          :placeholder="arg.placeholder"
                          @update:value="(value: number | null) => setArg(row, arg, value)"
                        />
                        <NSelect
                          v-else-if="arg.control === 'dict'"
                          size="small"
                          filterable
                          clearable
                          :loading="dictLoading"
                          :options="dictOptions"
                          :value="argValue(row, arg) || null"
                          :placeholder="arg.placeholder ?? '搜索字典名称或编码'"
                          @update:value="(value: string | null) => setArg(row, arg, value)"
                        />
                        <NSelect
                          v-else-if="arg.control === 'select'"
                          size="small"
                          :options="arg.options ?? []"
                          :tag="Boolean(arg.tag)"
                          :filterable="Boolean(arg.tag)"
                          :value="argValue(row, arg) || null"
                          :placeholder="arg.placeholder ?? '默认'"
                          @update:value="(value: string | null) => setArg(row, arg, value)"
                        />
                        <NSelect
                          v-else-if="arg.control === 'pattern'"
                          size="small"
                          filterable
                          tag
                          :options="arg.options ?? []"
                          :value="argValue(row, arg) || null"
                          :placeholder="arg.placeholder ?? '选择或直接输入'"
                          @update:value="(value: string | null) => setArg(row, arg, value)"
                        />
                        <NInput
                          v-else
                          size="small"
                          class="mono"
                          :value="argValue(row, arg)"
                          :placeholder="arg.placeholder ?? ''"
                          @update:value="(value: string) => setArg(row, arg, value)"
                        />
                      </div>
                    </div>
                  </div>
                  <div v-else class="converter-hint">
                    {{ isBuiltin(row.type) ? '该转换器不需要参数' : '宿主自定义转换器，参数名由它的实现决定' }}
                  </div>

                  <div v-if="row.extraArgs.length" class="converter-args">
                    <div v-for="(arg, argIndex) in row.extraArgs" :key="argIndex" class="converter-extra-arg">
                      <NInput v-model:value="arg.key" size="small" class="mono" placeholder="参数名" />
                      <NInput v-model:value="arg.value" size="small" class="mono" placeholder="参数值" />
                      <NButton size="small" quaternary type="error" @click="row.extraArgs.splice(argIndex, 1)">
                        移除
                      </NButton>
                    </div>
                  </div>
                  <div class="converter-arg-actions">
                    <NButton
                      v-if="row.custom || row.extraArgs.length"
                      size="tiny"
                      dashed
                      @click="row.extraArgs.push({ key: '', value: '' })"
                    >
                      添加自定义参数
                    </NButton>
                    <span class="converter-hint">
                      {{
                        row.custom
                          ? '参数名由宿主转换器自己定义，按「参数名 / 参数值」填；转换器编码来自 /system/converters。'
                          : '参数名由转换器固定，界面只填值；空着的项保存时不写入，交给转换器用它自己的默认值。'
                      }}
                    </span>
                  </div>
                </div>
              </div>
              <div v-else class="field-empty">还没有转换规则；数据块返回什么就渲染什么</div>
              <NButton size="tiny" dashed @click="addConverter">添加转换</NButton>
            </div>
          </div>

          <div class="list-section">
            <div class="list-head">
              <span class="list-title">参数映射</span>
              <NButton size="tiny" text type="primary" @click="fillParamSample">填入示例</NButton>
            </div>
            <div class="field-body">
              <div v-if="paramRows.length" class="kv-list">
                <div v-for="(row, index) in paramRows" :key="index" class="kv-row">
                  <NInput v-model:value="row.key" size="small" class="mono" placeholder="参数名，如 reportNo" />
                  <NInput v-model:value="row.value" size="small" class="mono" placeholder="$request.reportNo 或字面量" />
                  <NButton size="small" quaternary type="error" @click="paramRows.splice(index, 1)">移除</NButton>
                </div>
              </div>
              <div v-else class="field-empty">还没有参数；数据源不需要入参时可以留空</div>
              <NButton size="tiny" dashed @click="paramRows.push({ key: '', value: '' })">添加参数</NButton>
            </div>
          </div>

          <div v-if="extraKeys.length" class="extra-hint">
            配置里还有 {{ extraKeys.length }} 个扩展项（<span class="mono">{{ extraKeys.join('、') }}</span>），保存时会原样保留。
          </div>
        </NForm>

        <!-- 说明侧栏：内容与表单同一份字段定义，默认收起；自己滚动，不跟表单挤同一条滚动条 -->
        <aside v-if="asideOpen" class="editor-aside">
          <div class="aside-title">配置说明</div>
          <div class="aside-summary">{{ meta.summary }}</div>

          <div v-for="(group, groupIndex) in fieldGroups" :key="group.title || groupIndex" class="aside-group">
            <div v-if="group.title" class="aside-group-title">{{ group.title }}</div>
            <div v-for="field in group.fields" :key="field.key" class="aside-item">
              <div class="aside-item-head">
                <span class="aside-item-label">{{ field.label }}</span>
                <code class="cfg-key">{{ field.key }}</code>
              </div>
              <div class="aside-item-desc">{{ field.desc }}</div>
            </div>
          </div>

          <div class="aside-group">
            <div class="aside-group-title">参数映射</div>
            <div v-for="note in paramFieldNotes" :key="note.name" class="aside-item">
              <div class="aside-item-head">
                <code class="aside-item-key">{{ note.name }}</code>
              </div>
              <div class="aside-item-desc">{{ note.desc }}</div>
            </div>
          </div>

          <div class="aside-group">
            <div class="aside-group-title">图片字段</div>
            <div class="aside-item">
              <div class="aside-item-desc">
                字段值可以是 http(s) 地址、data URI 或 Base64（SQL 返回 BLOB 也可以）；宽高留空按原图尺寸，对齐是图片在单元格内的水平位置，四周默认留 4px 白边。
              </div>
            </div>
          </div>

          <div class="aside-group">
            <div class="aside-group-title">字段值转换</div>
            <div class="aside-item">
              <div class="aside-item-desc">
                取数完成后按字段名转换值：0/1 显示成「是/否」、时间戳格式化、金额千分位、枚举换中文。
                模板里也能就地覆盖：<code>${amount|number:2}</code>、<code>${status|dict(order_status)}</code>（模板优先，因为渲染发生在取数之后）。
              </div>
            </div>
          </div>

          <div class="aside-group">
            <div class="aside-group-title">合并单元格对齐</div>
            <div class="aside-item">
              <div class="aside-item-desc">
                模板里用 merge= 竖向合并出来的那一格，内容垂直怎么对齐。水平方向跟随原单元格；不设置时保持模板原样（默认靠上）。
              </div>
            </div>
          </div>
        </aside>
      </div>

      <template #footer>
        <div class="modal-footer">
          <NButton size="small" secondary type="info" :disabled="!editing" @click="previewFromEditor">
            预览数据源
          </NButton>
          <NSpace justify="end">
            <NButton @click="showEditor = false">取消</NButton>
            <NButton type="primary" :loading="saving" @click="save">保存</NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <!-- 数据源预览：只看取数结果，不落库、不生成文件 -->
    <NModal
      v-model:show="showPreview"
      preset="card"
      class="doc-modal-card"
      title="预览数据源"
      style="width: min(880px, 94vw); max-height: 86vh"
    >
      <div class="preview-head">
        <span class="preview-name">{{ previewTitle }}</span>
        <div class="preview-head-right">
          <NRadioGroup v-model:value="previewMode" size="small">
            <NRadioButton value="json">JSON</NRadioButton>
            <NRadioButton value="table">表格</NRadioButton>
          </NRadioGroup>
          <span v-if="previewElapsed !== null" class="preview-elapsed">耗时 {{ previewElapsed }} ms</span>
        </div>
      </div>

      <div v-if="previewParams.length" class="preview-params">
        <div class="preview-params-title">
          需要参数：参数映射里的 <code>$request.xxx</code> 生成时由调用方提供，这里手动填
        </div>
        <div v-for="param in previewParams" :key="param.key" class="preview-param-row">
          <span class="preview-param-key mono">$request.{{ param.key }}</span>
          <NInput
            v-model:value="param.value"
            size="small"
            class="mono"
            placeholder="填一个值再执行"
            @keyup.enter="runPreview"
          />
        </div>
      </div>

      <div v-if="previewLoading" class="preview-empty">执行中…</div>
      <div v-else-if="previewError" class="preview-error">{{ previewError }}</div>
      <template v-else-if="previewMode === 'table'">
        <div v-if="previewTables.length" class="preview-tables">
          <div v-for="table in previewTables" :key="table.key" class="preview-table">
            <div class="preview-table-head">
              <span class="preview-table-title mono">{{ table.title }}</span>
              <span class="preview-table-count">{{ table.rows.length }} 行</span>
            </div>
            <NDataTable
              :columns="tableColumns(table)"
              :data="table.rows"
              :row-key="(row: Record<string, string>) => row.__index"
              :bordered="false"
              :single-line="false"
              size="small"
              :scroll-x="table.columns.length * 130"
            />
          </div>
        </div>
        <div v-else-if="previewDone" class="preview-empty">（空结果）</div>
        <div v-else class="preview-empty">还没有结果</div>
      </template>
      <pre v-else-if="previewJson" class="preview-json">{{ previewJson }}</pre>
      <div v-else class="preview-empty">还没有结果</div>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="showPreview = false">关闭</NButton>
          <NButton type="primary" :loading="previewLoading" @click="runPreview">重新执行</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
/* -------------------------------------------------- 编辑弹窗：表单 + 说明侧栏 */
/* 左侧表单占满剩余宽度，右侧说明固定宽度；侧栏收起时表单自动铺满整宽 */
.editor-body {
  display: flex;
  align-items: flex-start;
  gap: 20px;
}

.editor-form {
  flex: 1 1 auto;
  min-width: 0;
}

/* 侧栏跟随弹窗内容区滚动，自己再套一层滚动，和表单互不影响 */
.editor-aside {
  flex: none;
  align-self: flex-start;
  position: sticky;
  top: 0;
  width: 320px;
  max-height: 60vh;
  overflow-y: auto;
  padding: 12px 14px;
  border: 1px solid #e6ecf6;
  border-radius: 10px;
  background: #fbfcfe;
}

/* 标题栏里的说明开关：与关闭按钮同排，展开时图标变色 */
.aside-toggle {
  color: #5b6577;
}

.aside-toggle.is-active {
  color: #2f6fed;
  background-color: #eef4ff;
}

.aside-icon {
  display: block;
}

.aside-toggle.is-active .aside-icon {
  color: #2f6fed;
}

.aside-title {
  margin-bottom: 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: #1f2733;
}

.aside-summary {
  margin-bottom: 12px;
  padding: 7px 9px;
  border: 1px solid #d9e6fb;
  border-radius: 8px;
  background: #f4f8ff;
  color: #4a5f88;
  font-size: 11.5px;
  line-height: 1.55;
}

.aside-group {
  margin-top: 12px;
}

.aside-group-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #42506b;
}

.aside-item + .aside-item {
  margin-top: 8px;
}

.aside-item-head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.aside-item-label {
  font-size: 12px;
  font-weight: 500;
  color: #1f2733;
  line-height: 1.4;
}

.aside-item-key {
  padding: 0 5px;
  border-radius: 4px;
  background: #f2f5fa;
  color: #2f6fed;
  font-family: 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace;
  font-size: 10.5px;
  line-height: 16px;
}

.aside-item-desc {
  margin-top: 2px;
  font-size: 11.5px;
  line-height: 1.6;
  color: #5b6577;
  word-break: break-word;
}

.aside-item-desc code {
  font-family: 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace;
  font-size: 11px;
  color: #2f6fed;
}

/* 窄屏：侧栏会把表单挤成一条，干脆不显示开关（打开弹窗时本就是收起状态） */
@media (max-width: 992px) {
  .aside-toggle {
    display: none;
  }
}

.basic-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 2px 0 8px;
  padding-top: 10px;
  border-top: 1px solid #eef2f8;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2733;
}

.section-badge {
  padding: 1px 8px;
  border-radius: 20px;
  font-size: 11px;
  color: #2f6fed;
  background: #eef4ff;
  border: 1px solid #dbe8ff;
}

/* 表单项下方的浅色说明（目前只有「改数据块编码」用） */
.code-hint {
  color: #8a94a6;
  font-size: 11.5px;
  line-height: 1.5;
}

/* 数据源风险提示（目前只有 SQL 数据源会用） */
.cfg-warn {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: -4px 0 12px;
  padding: 7px 10px;
  border: 1px solid #f2d4a8;
  border-radius: 8px;
  background: #fdf6ec;
  color: #8a5a12;
  font-size: 11.5px;
  line-height: 1.55;
}

.cfg-warn-icon {
  flex: none;
  margin-top: 2px;
  color: #d99026;
}

/* -------------------------------------------------- 配置项分组 */
/* 分组标题是网格里的一整行，下面的字段接着在同一张网格里排 */
.cfg-group-title {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  font-weight: 600;
  color: #42506b;
}

.cfg-group-title::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 2px;
  background: #2f6fed;
  opacity: 0.8;
}

.cfg-group-title::after {
  content: '';
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, #e9eff9, #f6f9fd);
}

/* -------------------------------------------------- 配置项网格 */
/* 三列：窄字段（开关 / 数字 / 下拉 / 单行文本）一行排三个，多行文本 / JSON / 键值对占满整行 */
.cfg-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px 18px;
  align-items: start;
}

.cfg-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.cfg-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 18px;
}

.cfg-label-main {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.cfg-label-text {
  font-size: 12.5px;
  font-weight: 500;
  color: #1f2733;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 配置项对应的 JSON 键名，方便对照 data_source_config */
.cfg-key {
  flex: none;
  padding: 0 5px;
  border-radius: 4px;
  background: #f2f5fa;
  color: #9aa5b7;
  font-family: 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace;
  font-size: 10.5px;
  line-height: 16px;
}

.cfg-field :deep(.n-input-number),
.cfg-field :deep(.n-select),
.cfg-field :deep(.n-input) {
  width: 100%;
}

/* -------------------------------------------------- 图片字段 / 参数映射 */
.list-section {
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid #eef2f8;
}

/* 标题和右侧按钮同一行，用 flex 居中保证文字与按钮严格对齐 */
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 24px;
  margin-bottom: 8px;
}

.list-title {
  font-size: 13px;
  font-weight: 600;
  line-height: 24px;
  color: #1f2733;
}

.field-required {
  color: #d03050;
  margin-left: 2px;
}

.field-body {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-empty {
  font-size: 12px;
  color: #a3adbb;
  line-height: 1.6;
}

.extra-hint {
  margin-top: 4px;
  font-size: 11.5px;
  color: #8b95a5;
  line-height: 1.6;
}

/* -------------------------------------------------- 键值对列表 */
.kv-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
  width: 100%;
}

.kv-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr) auto;
  gap: 8px;
  width: 100%;
  align-items: center;
}

/* -------------------------------------------------- 图片字段 */
.image-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 92px 92px 88px auto;
  align-items: center;
  gap: 8px;
  width: 100%;
}

/* -------------------------------------------------- 字段值转换 */
.converter-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.converter-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid #e6ecf6;
  border-radius: 8px;
  background: #fbfcfe;
}

.converter-main {
  display: grid;
  grid-template-columns: auto minmax(0, 1.3fr) auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
}

.converter-label {
  font-size: 12px;
  color: #5b6472;
  white-space: nowrap;
}

.converter-desc {
  font-size: 11.5px;
  color: #8b95a5;
  line-height: 1.5;
}

.converter-warn {
  font-size: 11.5px;
  color: #d03050;
  line-height: 1.5;
}

.converter-args {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-left: 2px;
}

.converter-arg {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
}

.converter-arg-label {
  font-size: 12px;
  color: #5b6472;
  text-align: right;
  white-space: nowrap;
}

.converter-arg-control {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.converter-arg-control > .n-input,
.converter-arg-control > .n-input-number,
.converter-arg-control > .n-select {
  flex: 1 1 auto;
  min-width: 0;
}

.converter-extra-arg {
  display: grid;
  grid-template-columns: minmax(0, 160px) minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.converter-arg-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.converter-hint {
  font-size: 11.5px;
  color: #8b95a5;
  line-height: 1.5;
}

/* -------------------------------------------------- 数据源预览 */
.modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.preview-name {
  font-size: 13px;
  font-weight: 600;
  color: #1f2733;
}

.preview-elapsed {
  font-size: 11.5px;
  color: #8b95a5;
}

.preview-head-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

/* 表格视图：顶层数组一张表，包装结构拆成「概览 + 子表」，整体滚动 */
.preview-tables {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-height: 46vh;
  overflow: auto;
}

.preview-table-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.preview-table-title {
  font-size: 12px;
  font-weight: 600;
  color: #42506b;
}

.preview-table-count {
  font-size: 11.5px;
  color: #8b95a5;
}

.preview-table :deep(.n-data-table .n-data-table-th) {
  background: #f7f9fd;
}

.preview-params {
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid #d9e6fb;
  border-radius: 8px;
  background: #f4f8ff;
}

.preview-params-title {
  margin-bottom: 8px;
  font-size: 11.5px;
  line-height: 1.55;
  color: #4a5f88;
}

.preview-params-title code {
  font-family: 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace;
  color: #2f6fed;
}

.preview-param-row {
  display: grid;
  grid-template-columns: minmax(0, 220px) minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  margin-top: 6px;
}

.preview-param-key {
  font-size: 12px;
  color: #42506b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-json {
  max-height: 46vh;
  margin: 0;
  padding: 12px 14px;
  overflow: auto;
  border: 1px solid #e6ecf6;
  border-radius: 8px;
  background: #fbfcfe;
  color: #1f2733;
  font-family: 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.preview-error {
  padding: 10px 12px;
  border: 1px solid #f3c9c9;
  border-radius: 8px;
  background: #fef4f4;
  color: #b42318;
  font-size: 12.5px;
  line-height: 1.6;
  word-break: break-all;
}

.preview-empty {
  padding: 18px 0;
  text-align: center;
  font-size: 12.5px;
  color: #a3adbb;
}
</style>
