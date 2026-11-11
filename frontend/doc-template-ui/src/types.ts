export type FileType = 'DOCX' | 'XLSX';
export type ExportType = 'ORIGINAL' | 'PDF';

export interface TemplateRow {
  id: number;
  template_code: string;
  template_name: string;
  file_type: FileType;
  export_type: ExportType;
  current_version_id: number | null;
  status: string;
  remark: string | null;
  create_time: string | null;
  update_time: string | null;
}

export interface VersionRow {
  id: number;
  template_id: number;
  version_no: number;
  file_name: string;
  file_url: string;
  file_sha256: string | null;
  create_time: string | null;
}

export interface DataBlockRow {
  id: number;
  template_id: number;
  block_code: string;
  block_name: string;
  data_source_type: 'JAVA' | 'SQL' | 'API';
  data_source_config: string;
  param_config: string;
  sort_order: number;
  status: string;
}

export interface RecordRow {
  id: number;
  template_id: number;
  template_version_id: number | null;
  template_name?: string | null;
  export_type: ExportType;
  request_params: string | null;
  result_file_url: string | null;
  result_file_name?: string | null;
  has_result_file?: boolean;
  status: 'SUCCESS' | 'FAILED' | 'NOT_IMPLEMENTED' | string;
  error_message: string | null;
  create_by: string | null;
  create_time: string | null;
}

export interface PlaceholderInfo {
  template_id: number;
  version_id: number | null;
  version_no?: number | null;
  file_type: FileType;
  file_name?: string;
  placeholders: string[];
  sample_params: Record<string, unknown>;
  /** 循环体内部的字段，由数组项提供，模拟生成时不需要手填 */
  loop_fields?: string[];
}

export interface ProviderItem {
  code: string;
  implementation: string;
}

/** 数据字典（主表）：一本字典一行，`item_count` 由后端 LEFT JOIN 算出 */
export interface DictRow {
  id: number;
  dict_code: string;
  dict_name: string;
  remark: string | null;
  item_count: number | null;
  create_time: string | null;
  update_time: string | null;
}

/** 字典项（子表）：一行 = 一个「值 → 显示名」映射 */
export interface DictItemRow {
  id: number;
  dict_id: number;
  item_value: string;
  item_label: string;
  sort_order: number;
}

export interface DataSourceItem {
  code: string;
  description: string;
  bean: boolean;
}

/** 后端分页接口的通用返回结构（例如生成记录列表）。 */
export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
  pages: number;
}

/** AI 标注的可用状态（`GET /ai/status`）。默认关闭时不返回模型名。 */
export interface AiStatus {
  enabled: boolean;
  model: string | null;
  max_attempts: number;
  max_input_chars: number;
}

/** 标记计划里的一块：`shape` 是 `object`（单条）或 `array`（多条）。 */
export interface PlanBlock {
  code: string;
  name: string;
  shape: 'object' | 'array';
  fields: PlanField[];
}

export interface PlanField {
  field: string;
  label: string;
  type: 'string' | 'number' | 'date' | 'boolean';
  samples: string[];
}

/**
 * 标记计划。字段名固定驼峰（`rowTo` 不是 `row_to`），原样回传给后端即可。
 */
export interface MarkPlan {
  blocks: PlanBlock[];
  marks: PlanMark[];
  ignore?: { at: Record<string, number>; reason: string }[];
}

export interface PlanMark {
  kind: 'value' | 'loop' | 'region';
  at: {
    paragraph?: number;
    sheet?: number;
    table?: number;
    row?: number;
    rowTo?: number;
    cell?: number;
    p?: number;
  };
  block: string;
  field?: string;
  replace?: string;
  merge?: number[];
  by?: string;
}

/** 自检返回的一块：带生成的模拟 SQL 和行数。 */
export interface CheckBlock {
  code: string;
  name: string | null;
  shape: string;
  rows: number;
  fields: PlanField[];
  sql: string;
}

export interface CheckResult {
  ok: boolean;
  error?: string;
  template_id?: number;
  version_id?: number;
  file_type?: FileType;
  blocks?: CheckBlock[];
  placeholders?: string[];
  unbound?: string[];
  suggestions?: ConverterSuggestion[];
  warnings?: string[];
  ignored?: { at: Record<string, number>; reason: string }[];
  output_bytes?: number;
}

/** 值转换建议：只提一句，不会自动写进数据块。 */
export interface ConverterSuggestion {
  block: string;
  field: string;
  converter: string;
  reason: string;
}

/** AI 出计划的结果：成功时把自检结果平铺进来，失败时带每次尝试的原因。 */
export interface AiPlanResult extends CheckResult {
  attempt?: number;
  attempts?: number;
  model?: string;
  elapsed_ms?: number;
  plan?: MarkPlan;
  failures?: { attempt: number; stage: string; error: string; elapsed_ms: number }[];
}
