/** AI 导入任务的展示映射：阶段名与状态标签都只在这里维护一份。 */

export const AI_STAGE_LABELS: Record<string, string> = {
  SNAPSHOT: '读结构',
  PROMPT: '拼提示词',
  CALL: '调模型',
  PARSE: '解析计划',
  VALIDATE: '校验计划',
  CHECK: '落笔自检',
  APPLY: '建模板',
  DONE: '完成'
};

export function stageLabel(stage: string | null | undefined): string {
  if (!stage) return '-';
  return AI_STAGE_LABELS[stage] ?? stage;
}

/** 阶段顺序与后端 `DocTemplateAiStage` 一致；步骤条按它排列，改一处即可。 */
export const AI_STAGES = [
  'SNAPSHOT',
  'PROMPT',
  'CALL',
  'PARSE',
  'VALIDATE',
  'CHECK',
  'APPLY',
  'DONE'
] as const;

/** 当前阶段在步骤条里的序号（从 0 起）；没有阶段时返回 -1。 */
export function stageIndex(stage: string | null | undefined): number {
  if (!stage) return -1;
  return AI_STAGES.indexOf(stage as (typeof AI_STAGES)[number]);
}

/** 阶段进度文案：「4 / 8」。终态任务的阶段列用它说明走到哪一步。 */
export function stageProgress(stage: string | null | undefined): string {
  const index = stageIndex(stage);
  return index < 0 ? '-' : `${index + 1} / ${AI_STAGES.length}`;
}

/** 步骤条整体状态：失败标红，成功标绿，其余按执行中处理。 */
export function stepsStatus(status: string): 'error' | 'finish' | 'process' {
  if (status === 'FAILED') return 'error';
  if (status === 'SUCCEEDED') return 'finish';
  return 'process';
}

/** 任务状态：颜色与文案。`RUNNING` 用 info 而不是 warning —— 它是在正常干活。 */
export function aiStatusMeta(status: string): {
  type: 'success' | 'error' | 'warning' | 'info' | 'default';
  label: string;
} {
  switch (status) {
    case 'PENDING':
      return { type: 'default', label: '待开始' };
    case 'RUNNING':
      return { type: 'info', label: '执行中' };
    case 'SUCCEEDED':
      return { type: 'success', label: '已生成模板' };
    case 'FAILED':
      return { type: 'error', label: '失败' };
    case 'CANCELED':
      return { type: 'warning', label: '已取消' };
    default:
      return { type: 'default', label: status || '-' };
  }
}

/** 耗时：毫秒转「1 分 20 秒」，不足 1 秒显示毫秒。 */
export function formatElapsed(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || ms < 0) return '-';
  if (ms < 1000) return `${ms} ms`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return rest ? `${minutes} 分 ${rest} 秒` : `${minutes} 分`;
}
