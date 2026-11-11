import type { DialogApi } from 'naive-ui';

/** 把后端返回的时间戳格式化成 `yyyy-MM-dd HH:mm:ss`。 */
export function formatTime(value: string | number[] | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const pad = (input: number, length = 2) => String(input).padStart(length, '0');
  // 防御性处理：Jackson 把时间写成数组时，LocalDateTime 会是 [2026,10,27,11,23,9] 这种形状，
  // 这里一并归一化，表格里就不会出现一串裸数字。
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value;
    return `${pad(year, 4)}-${pad(month)}-${pad(day)} ${pad(hour)}:${pad(minute)}:${pad(second)}`;
  }
  const text = String(value).replace('T', ' ').replace(/\.\d+$/, '').trim();
  const match = /^(\d{4})-(\d{2})-(\d{2})[ ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(text);
  if (match) return `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]}:${match[6] ?? '00'}`;
  return text || '—';
}

export function fileKind(name: string | null | undefined): 'docx' | 'xlsx' | 'pdf' | 'other' {
  const lower = (name ?? '').toLowerCase();
  if (lower.endsWith('.docx') || lower.endsWith('.doc')) return 'docx';
  if (lower.endsWith('.xlsx') || lower.endsWith('.xls')) return 'xlsx';
  if (lower.endsWith('.pdf')) return 'pdf';
  return 'other';
}

export function tryParseJson(text: string | null | undefined): { ok: boolean; value: any; error?: string } {
  if (!text || !text.trim()) return { ok: true, value: {} };
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (error: any) {
    return { ok: false, value: null, error: error?.message ?? 'JSON 解析失败' };
  }
}

export function prettyJson(value: unknown): string {
  try {
    return JSON.stringify(typeof value === 'string' ? JSON.parse(value) : value, null, 2);
  } catch {
    return String(value ?? '');
  }
}

export function shortSha(sha: string | null | undefined): string {
  if (!sha) return '—';
  return sha.slice(0, 10);
}

/**
 * 判断这次点击是不是落在「自己就有行为」的元素上。
 *
 * 表格里点行等于勾选，但勾选框、操作列按钮、下拉、输入框本来就有自己的动作，
 * 再叠一层就会双重触发（点击「删除」时同时勾选该行）。这些一律交给元素自己处理。
 */
export function isInteractiveClick(event: MouseEvent): boolean {
  const target = event.target as HTMLElement | null;
  if (!target || typeof target.closest !== 'function') return true;
  return !!target.closest(
    'button, a, input, textarea, select, .n-checkbox, .n-base-selection, .row-actions'
  );
}

/** 批量删除接口的统一返回：成功条数 + 逐条失败明细（后端逐条删，单条失败不影响其他条）。 */
export interface BatchDeleteResult {
  deleted: number;
  failed: Array<{ id: number | string; message: string }>;
}

/**
 * 删除确认：点「确认」后按钮立即显示加载状态并禁用，弹窗保持打开直到动作结束。
 *
 * <p>确认弹窗自身只等回调返回的 Promise 结束才关闭，期间按钮没有任何视觉变化，
 * 接口稍慢就表现为「点击后没有反应」。这里改写弹窗对象上的 `loading`，
 * 与 `Dialog` 组件自身的 `loading` 属性同名，按钮随即显示加载动画且不可再次点击。</p>
 *
 * <p>失败原因由传入的动作自行提示；此处吞掉异常，避免回调抛出后弹窗无法关闭。</p>
 */
export function confirmDelete(
  dialog: DialogApi,
  options: {
    title: string;
    content: string;
    positiveText?: string;
    action: () => Promise<unknown>;
  }
): void {
  const instance = dialog.warning({
    title: options.title,
    content: options.content,
    positiveText: options.positiveText ?? '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      instance.loading = true;
      instance.closable = false;
      instance.negativeButtonProps = { disabled: true };
      try {
        await options.action();
      } catch {
        // 失败原因由动作自身提示，这里只保证弹窗能够正常关闭
      }
    }
  });
}

/**
 * 把批量删除的结果拼成一句提示。
 *
 * 全成功给 success，有失败给 warning 并把**第一条**失败原因带上——失败原因往往同一类
 * （记录已被别人删了、外键锁住），只报条数的话用户还得自己回去翻。
 */
export function batchDeleteSummary(label: string, result: BatchDeleteResult | null | undefined) {
  const deleted = Number(result?.deleted ?? 0);
  const failed = result?.failed ?? [];
  if (!failed.length) {
    return { type: 'success' as const, text: `已删除 ${deleted} 个${label}` };
  }
  const first = failed[0]?.message ?? '未知原因';
  const more = failed.length > 1 ? ` 等 ${failed.length} 条` : '';
  return { type: 'warning' as const, text: `已删除 ${deleted} 个${label}，${failed.length} 条失败${more}：${first}` };
}
