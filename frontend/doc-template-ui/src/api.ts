import type { AiStatus } from './types';

declare global {
  interface Window {
    DOC_TEMPLATE_CONFIG?: {
      apiBaseUrl?: string;
      tokenStorageKey?: string;
      tokenHeader?: string;
      tokenPrefix?: string;
      loginUrl?: string;
      layout?: 'full' | 'bare';
    };
  }
}

type DocTemplateConfig = NonNullable<Window['DOC_TEMPLATE_CONFIG']>;
/**
 * iframe 嵌入时，宿主没法在页面里改 `window.DOC_TEMPLATE_CONFIG`，只能把这几项挂在地址上：
 *
 * ```
 * <iframe src="/doc-template/?tokenStorageKey=Admin-Token&tokenHeader=Authorization&tokenPrefix=Bearer&layout=bare">
 * ```
 *
 * `layout=bare` 会把左侧栏（品牌 + 菜单 + 页脚）和顶栏（标题）一起去掉，只留内容区，
 * 页面里不留任何导航和标题 —— 适合被宿主系统嵌进内容区，切页面由宿主的菜单改 iframe 的 hash 决定。
 *
 * 只认这几个键：`apiBaseUrl` / `loginUrl` 故意不从 URL 读 —— 它们决定请求发到哪、跳转到哪，
 * 允许被链接改写就等于给了钓鱼入口。token 本身也不要放 URL，用 Cookie 或宿主自己的注入方式。
 * `layout` 只是外观开关，改它最多把菜单藏起来，没有安全含义，所以放行。
 */
const IFRAME_CONFIG_KEYS = ['tokenStorageKey', 'tokenHeader', 'tokenPrefix', 'layout'] as const;

/** 请求头名只允许 token 字符，避免把非法值塞进 Headers 抛 TypeError。 */
const HEADER_NAME = /^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/;

/** 读地址栏上的 iframe 参数；没传或值不合法就跳过，交给 window 配置或默认值。 */
function readIframeConfig(): DocTemplateConfig {
  // 先按 string 收，值已经逐个校验过，最后整体转成配置类型（key 是联合字面量，直接赋 string 过不了类型检查）
  const result: Record<string, string> = {};
  const query = new URLSearchParams(window.location.search);
  for (const key of IFRAME_CONFIG_KEYS) {
    const raw = query.get(key);
    if (raw === null) continue;
    const value = raw.trim();
    // tokenPrefix 允许空串（表示不加前缀），另外几个空值视为没传
    if (key !== 'tokenPrefix' && !value) continue;
    if (key === 'tokenHeader' && !HEADER_NAME.test(value)) continue;
    // layout 只认两个值，写错了按默认（full）处理，避免因单个拼写错误导致界面异常
    if (key === 'layout' && value !== 'full' && value !== 'bare') continue;
    result[key] = value;
  }
  return result as DocTemplateConfig;
}

/** 地址栏参数优先于 `window.DOC_TEMPLATE_CONFIG`：它只影响这一次嵌入，粒度更细。 */
const config: DocTemplateConfig = { ...(window.DOC_TEMPLATE_CONFIG ?? {}), ...readIframeConfig() };
export const apiBase = (config.apiBaseUrl ?? '/api/doc-template').replace(/\/$/, '');

/**
 * 布局模式：`full` 带左侧栏（直接访问 `/doc-template/` 时的形态），`bare` 只留顶栏 + 内容区。
 * 被宿主 iframe 嵌进内容区时用 `?layout=bare`，否则宿主的菜单和这里的左侧栏会叠成两条导航。
 */
export const layoutMode: 'full' | 'bare' = config.layout === 'bare' ? 'bare' : 'full';

/** 401 交给宿主处理：配了 loginUrl 就跳登录页并带上回跳地址，没配则由调用方提示错误。 */
function handleUnauthorized() {
  const loginUrl = config.loginUrl;
  if (!loginUrl) return;
  const redirect = encodeURIComponent(window.location.href);
  window.location.href = loginUrl.includes('?') ? `${loginUrl}&redirect=${redirect}` : `${loginUrl}?redirect=${redirect}`;
}

function token(): string {
  const key = config.tokenStorageKey ?? 'token';
  return localStorage.getItem(key) ?? localStorage.getItem('Authorization') ?? '';
}

function authHeaders(): Record<string, string> {
  const value = token();
  if (!value) return {};
  const prefix = config.tokenPrefix ?? 'Bearer';
  const header = config.tokenHeader ?? 'Authorization';
  return { [header]: prefix && !value.startsWith(prefix + ' ') ? `${prefix} ${value}` : value };
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type') && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  for (const [key, value] of Object.entries(authHeaders())) headers.set(key, value);
  const response = await fetch(apiBase + path, { ...init, headers, credentials: 'same-origin' });
  if (!response.ok) {
    let detail = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      detail = body.msg ?? body.message ?? detail;
    } catch {
      /* 拿不到响应体时保留状态码文案 */
    }
    if (response.status === 401) handleUnauthorized();
    throw new Error(detail);
  }
  return response;
}

export async function api<T = any>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await request(path, init);
  if (response.status === 204) return null as T;
  return (await response.json()) as T;
}

/** 取二进制接口（预览 / 下载），鉴权头与 JSON 接口一致。 */
export async function apiBlob(path: string): Promise<Blob> {
  const response = await request(path);
  return response.blob();
}

/**
 * 带 JSON body 的二进制接口。
 *
 * <p>标注预览是 POST（计划在 body 里），没法用地址拼出来，所以单独开一个。</p>
 */
export async function apiPostBlob(path: string, body: unknown): Promise<Blob> {
  const response = await request(path, { method: 'POST', body: JSON.stringify(body) });
  return response.blob();
}

/**
 * 当前运行的后端（Starter）版本号，显示在左侧栏底部。
 * 老版本 Starter 没有这个接口，取不到时返回空串，页面只显示「Starter」。
 */
export async function fetchStarterVersion(): Promise<string> {
  try {
    const data = await api<{ version?: string | null }>('/system/version');
    return (data?.version ?? '').trim();
  } catch {
    return '';
  }
}

export function uploadTemplate(id: number | string, file: File) {
  const data = new FormData();
  data.append('file', file);
  return api(`/templates/${id}/file`, { method: 'POST', body: data });
}

export async function downloadFile(path: string, filename: string) {
  const blob = await apiBlob(path);
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

export const previewUrl = (versionId: number | string) => `/templates/versions/${versionId}/preview`;
export const downloadUrl = (versionId: number | string) => `/templates/versions/${versionId}/download`;
export const recordPreviewUrl = (id: number | string) => `/generate-records/${id}/preview`;
export const recordDownloadUrl = (id: number | string) => `/generate-records/${id}/file`;

/** AI 标注是否可用。老版本 Starter 没有这个接口，取不到时按「未启用」处理。 */
export async function fetchAiStatus(): Promise<AiStatus> {
  try {
    return await api<AiStatus>('/ai/status');
  } catch {
    return { enabled: false, model: null, max_attempts: 0, max_input_chars: 0 };
  }
}
