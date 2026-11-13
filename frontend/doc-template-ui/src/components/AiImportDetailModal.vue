<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue';
import {
  NAlert,
  NButton,
  NEmpty,
  NModal,
  NSpace,
  NSpin,
  NTag,
  useDialog,
  useMessage
} from 'naive-ui';
import { cancelAiImport, fetchAiImport, fetchAiImportProgress, rerunAiImport, startAiImport } from '../api';
import type { AiImportDetail, AiImportProgress, AiTraceRow } from '../types';
import { AI_STAGES, aiStatusMeta, formatElapsed, stageIndex, stageLabel } from '../aiImport';
import { formatTime } from '../utils';

/**
 * 导入任务详情：看状态与阶段、看失败原因、看每次尝试的思维链。
 *
 * <p>执行期间每秒拉一次详情，进入终态就停 —— 不引 SSE / WebSocket，
 * 一次导入最多几分钟，轮询的代价远小于多一套长连接。点「开始导入 / 重新分析」之后
 * 后台线程还没接手时任务仍是 {@code PENDING}，所以这一小段也要继续拉，
 * 否则页面会停在「待开始」，只能靠手动刷新才看到进度。</p>
 */
const props = defineProps<{ show: boolean; id: number | null }>();
const emit = defineEmits<{
  (event: 'update:show', value: boolean): void;
  (event: 'changed'): void;
  (event: 'open-template', id: number): void;
}>();

const message = useMessage();
const dialog = useDialog();

const visible = computed({
  get: () => props.show,
  set: (value: boolean) => emit('update:show', value)
});

const detail = ref<AiImportDetail | null>(null);
const loading = ref(false);
const acting = ref(false);
/** 刚点过「开始导入 / 重新分析」，后台线程尚未接手（任务还是 PENDING） */
const watching = ref(false);
/**
 * 思维链里展开着的尝试。
 *
 * <p>不用 {@code NCollapse}：它的默认插槽被编译成稳定插槽（`_: 1`），父组件因轮询
 * 重渲染时 Vue 会跳过这个子组件，折叠面板里的文字就停在旧对象上，只有点开或收起
 * （`expanded-names` 变化触发子组件更新）才刷新。这里自绘折叠列表，内容留在本组件的
 * 渲染作用域里，轮询拿到新内容即刻生效。</p>
 */
const openTraceIds = ref<number[]>([]);
/** 已经自动展开过的尝试 id：只自动展开一次，用户手动收起后不再抢 */
let autoExpandedId = 0;
/** 弹窗正文容器：自动滚动要在这个范围内找流式块 */
const bodyRef = ref<HTMLElement | null>(null);
/** 已经补齐过提示词的尝试 id（轮询接口不带提示词，新尝试要单独取一次） */
const promptFilled = new Set<number>();

/**
 * 打字机显示：轮询一次拿到一整段（后端每 800ms 落一次库），直接渲染就是一大块文字跳出来。
 *
 * <p>这里把目标文本按帧铺开：每帧露出剩余长度的一部分，大致用一秒追平一次轮询间隔，
 * 看起来就是模型在一个字一个字往外写。{@code traceId} 是当前打的是哪一次尝试，
 * 放进同一个响应式对象里，切换时模板才会跟着换。生成结束的那一次轮询不再逐帧追，
 * 未铺开的尾部直接补齐，避免任务完成后还要等一秒才看到全文。</p>
 */
const typed = reactive({ traceId: 0, reasoning: '', content: '' });
let typedTarget = { reasoning: '', content: '' };
/** 两块文本上一轮对齐到的目标长度，用来判断这一轮有没有新增内容 */
let typedKnown = { reasoning: 0, content: 0 };
let typeTimer: ReturnType<typeof setTimeout> | null = null;

let pollTimer: ReturnType<typeof setTimeout> | null = null;

const statusMeta = computed(() => aiStatusMeta(detail.value?.status ?? ''));
const running = computed(() => detail.value?.status === 'RUNNING');
const currentStage = computed(() => detail.value?.stage ?? null);
/** 抬头里的阶段文案：还没开始时没有阶段，给一句「等待执行」比一个短横线好读 */
const stageText = computed(() => {
  if (currentStage.value) return stageLabel(currentStage.value);
  return detail.value?.status === 'PENDING' ? '等待执行' : '-';
});
const stepIndex = computed(() => stageIndex(currentStage.value) + 1);
const succeeded = computed(() => detail.value?.status === 'SUCCEEDED');
/** 最新一次尝试：流式内容长在这一行上 */
const lastTraceId = computed(() => {
  const traces = detail.value?.traces ?? [];
  return traces.length ? traces[traces.length - 1].id : 0;
});

/** 这一行是不是正在生成中（最新一次尝试、任务在跑、还没报错）。 */
function isStreaming(trace: AiTraceRow) {
  return running.value && trace.id === lastTraceId.value && !trace.error;
}

function isTraceOpen(id: number) {
  return openTraceIds.value.includes(id);
}

function toggleTrace(id: number) {
  openTraceIds.value = isTraceOpen(id)
    ? openTraceIds.value.filter((key) => key !== id)
    : [...openTraceIds.value, id];
}

/** 正在输出的那次尝试里的文本块；找不到时退回最后一个尝试。 */
function tracePreList(): HTMLPreElement[] {
  const body = bodyRef.value;
  if (!body) return [];
  const live = body.querySelector('.trace-item.is-typing .trace-body');
  if (live) return [...live.querySelectorAll('pre')];
  const items = body.querySelectorAll('.trace-item');
  if (!items.length) return [];
  return [...items[items.length - 1].querySelectorAll('.trace-body pre')];
}

/**
 * 把思维链内容滚到底。
 *
 * <p>非 {@code force} 时只在原本就贴底的情况下跟到底，用户自己往上翻过就不抢位置。
 * {@code force} 不做任何判断：一次性补齐一大段之后 {@code scrollHeight} 会突然变长，
 * 事后的间距已经无法区分「补齐」和「用户翻过」；补齐后浏览器还要重排一次，
 * 所以等一帧再补滚一次。</p>
 */
async function stickToBottom(force = false) {
  await nextTick();
  scrollTraceToBottom(force);
  if (!force) return;
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  scrollTraceToBottom(true);
}

function scrollTraceToBottom(force: boolean) {
  tracePreList().forEach((pre) => {
    if (!force) {
      const gap = pre.scrollHeight - pre.scrollTop - pre.clientHeight;
      if (gap > 60) return;
    }
    pre.scrollTop = pre.scrollHeight;
  });
  if (!force) return;
  // 补齐后整块可能超出弹窗可视区，正文容器一并到底
  const body = bodyRef.value;
  if (body) body.scrollTop = body.scrollHeight;
}

/** 当前是否贴在底部：必须在内容变长之前调用，事后再测就分不清是补齐还是用户翻过。 */
function isAtBottom() {
  const list = tracePreList();
  if (!list.length) return true;
  return list.every((pre) => pre.scrollHeight - pre.scrollTop - pre.clientHeight <= 60);
}

/** 流式中的那次尝试显示打字机文本，其余直接显示落库内容。 */
function shownReasoning(trace: AiTraceRow) {
  if (typed.traceId !== trace.id) return trace.reasoning || '（模型没有返回思考过程）';
  // 打字机还没铺开时不要抢先显示占位文案
  return typed.reasoning || (typedTarget.reasoning ? '' : '（模型没有返回思考过程）');
}

function shownContent(trace: AiTraceRow) {
  if (typed.traceId !== trace.id) return trace.content || '（空）';
  return typed.content || (typedTarget.content ? '' : '（空）');
}

/** 一帧：把两块文本各往前推一点，追平后自动停表。 */
function typeTick() {
  typeTimer = null;
  let moving = false;
  (['reasoning', 'content'] as const).forEach((key) => {
    const target = typedTarget[key];
    const shown = typed[key];
    if (shown === target) return;
    // 目标变短（重新分析清空）直接对齐，不要一个字符一个字符往回退
    if (shown.length > target.length) {
      typed[key] = target;
      return;
    }
    const remaining = target.length - shown.length;
    // 每次露出剩余量的 1/45，约一秒追平一个轮询间隔；最少一帧一个字
    const step = Math.max(1, Math.ceil(remaining / 45));
    typed[key] = target.slice(0, shown.length + step);
    moving = true;
  });
  if (!moving) return;
  void stickToBottom();
  typeTimer = setTimeout(typeTick, 16);
}

function startTyping() {
  if (typeTimer !== null) return;
  typeTimer = setTimeout(typeTick, 16);
}

function stopTyping() {
  if (typeTimer !== null) {
    clearTimeout(typeTimer);
    typeTimer = null;
  }
}

function resetTyped() {
  typed.traceId = 0;
  typedTarget = { reasoning: '', content: '' };
  typedKnown = { reasoning: 0, content: 0 };
  typed.reasoning = '';
  typed.content = '';
  stopTyping();
}

/**
 * 每次拿到新详情后对齐打字机。
 *
 * <p>只有「这一轮确实新增了内容」的那一块才逐帧铺开；没有新增的块直接补齐。
 * 这样思考过程写完、回答开始输出之后，思考过程立刻完整显示，不会在回答往外冒的
 * 同时还在一个字一个字地补。本轮生成结束（任务不再运行或该次尝试已报错）时两块都补齐；
 * 打开一个早已结束的任务则直接用落库内容，不重放一遍。</p>
 */
function syncTyped() {
  const traces = detail.value?.traces ?? [];
  const last = traces.length ? traces[traces.length - 1] : null;
  if (!last) {
    resetTyped();
    return;
  }
  const streaming = isStreaming(last);
  if (typed.traceId !== last.id) {
    if (!streaming) {
      resetTyped();
      return;
    }
    typed.traceId = last.id;
    typed.reasoning = '';
    typed.content = '';
    typedKnown = { reasoning: 0, content: 0 };
  }
  const nextTarget = { reasoning: last.reasoning ?? '', content: last.content ?? '' };
  // 补齐前先记下是否贴底：补一大段之后间距必然变大，事后再测就分不清是补齐还是用户翻过
  const stuck = isAtBottom();
  // 回答原文已经开始输出，说明模型已经进入作答阶段，思考过程视为定型
  const answering = nextTarget.content.length > 0;
  let settled = false;
  (['reasoning', 'content'] as const).forEach((key) => {
    const target = nextTarget[key];
    const grown = target.length > typedKnown[key];
    typedKnown[key] = target.length;
    const settle = !streaming || !grown || (key === 'reasoning' && answering);
    if (!settle) return;
    if (typed[key] !== target) settled = true;
    typed[key] = target;
  });
  typedTarget = nextTarget;
  // 生成结束（接口返回终态）无条件滚到底；生成中的补齐仍尊重「用户翻过就不抢」
  if (settled) void stickToBottom(!streaming || stuck);
  if (!streaming) {
    stopTyping();
    return;
  }
  startTyping();
}

/**
 * 阶段条的分段状态。
 *
 * <p>用一条分段进度条代替逐段带圈的步骤条：八个中文标签在弹窗宽度里排不开，
 * 挤在一起既看不清进度也难看。这里只给「已走完 / 当前 / 失败 / 未开始」四种状态，
 * 具体阶段名放在段落的 title 里，鼠标悬停即可看到。</p>
 */
const segments = computed(() => {
  const status = detail.value?.status ?? 'PENDING';
  const current = stepIndex.value;
  return AI_STAGES.map((stage, index) => {
    const position = index + 1;
    let state: 'done' | 'current' | 'failed' | 'canceled' | 'todo' = 'todo';
    if (current > 0 && position < current) {
      state = 'done';
    } else if (current > 0 && position === current) {
      if (status === 'FAILED') state = 'failed';
      else if (status === 'CANCELED') state = 'canceled';
      else if (status === 'SUCCEEDED') state = 'done';
      else state = 'current';
    }
    return { stage, label: `${position}. ${stageLabel(stage)}`, state };
  });
});
const canStart = computed(
  () => !!detail.value && !running.value && detail.value.status === 'PENDING'
);
const canRerun = computed(
  () =>
    !!detail.value &&
    !running.value &&
    detail.value.status !== 'PENDING' &&
    detail.value.can_rerun !== false
);
const canCancel = computed(
  () => !!detail.value && (detail.value.status === 'PENDING' || detail.value.status === 'RUNNING')
);
/** 正文里的操作区：右上角只放重新分析与查看模板，删除只在列表页做 */
const hasBodyActions = computed(() => canStart.value || canCancel.value);

watch(
  () => [props.show, props.id] as const,
  ([show, id]) => {
    if (!show || id === null) {
      stopPolling();
      stopTyping();
      return;
    }
    watching.value = false;
    openTraceIds.value = [];
    autoExpandedId = 0;
    promptFilled.clear();
    resetTyped();
    void load(id);
  }
);

onBeforeUnmount(() => {
  stopPolling();
  stopTyping();
});

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

/**
 * 把轮询拿到的轻量进度合并进当前详情。
 *
 * <p>进度里没有提示词与计划原文（一次尝试的 user 提示词可达几万字符），
 * 所以提示词按思维链行的 id 从上一份详情里继承；失败原因由带 error 的行现算，
 * 与后端那个查询是同一份数据。</p>
 */
function mergeProgress(progress: AiImportProgress): AiImportDetail {
  const current = detail.value;
  const previous = new Map((current?.traces ?? []).map((trace) => [trace.id, trace]));
  const traces = (progress.traces ?? []).map((trace) => {
    const old = previous.get(trace.id);
    if (!old) return trace;
    return {
      ...trace,
      system_prompt: trace.system_prompt ?? old.system_prompt,
      user_prompt: trace.user_prompt ?? old.user_prompt
    };
  });
  const failures = traces
    .filter((trace) => trace.error)
    .map((trace) => ({
      attempt: trace.attempt,
      stage: trace.stage,
      error: String(trace.error),
      elapsed_ms: trace.elapsed_ms ?? 0
    }));
  const base = current ?? ({ plan_json: null } as unknown as AiImportDetail);
  return { ...base, ...progress, traces, failures };
}

/**
 * 补齐提示词：轻量进度接口不带提示词，新尝试（尤其是重新分析之后）本地也没有旧值可继承。
 *
 * <p>按思维链行的 id 记一次，每行只补一次；把整份详情取回来只取提示词两列，
 * 正文仍用轮询里的新值，避免用旧快照盖掉正在增长的内容。</p>
 */
async function fillPrompts(importId: number, traceId: number) {
  try {
    const full = await fetchAiImport(importId);
    const sources = new Map(full.traces.map((trace) => [trace.id, trace]));
    const current = detail.value;
    if (!current) return;
    detail.value = {
      ...current,
      traces: current.traces.map((trace) => {
        const source = sources.get(trace.id);
        if (!source) return trace;
        return {
          ...trace,
          system_prompt: trace.system_prompt ?? source.system_prompt,
          user_prompt: trace.user_prompt ?? source.user_prompt
        };
      })
    };
  } catch {
    // 取不到就让下一次轮询再试
    promptFilled.delete(traceId);
  }
}

/** 新一轮尝试开始时把它的折叠项展开一次，让流式内容直接可见。 */
function autoExpandLatest() {
  const traces = detail.value?.traces ?? [];
  if (!traces.length || !running.value) return;
  const id = traces[traces.length - 1].id;
  if (id === autoExpandedId) return;
  autoExpandedId = id;
  if (!isTraceOpen(id)) openTraceIds.value = [...openTraceIds.value, id];
}

/** 拉详情。`silent=true` 用于轮询，走轻量进度接口，不显示 loading，避免弹窗一直闪。 */
async function load(id: number, silent = false) {
  stopPolling();
  if (!silent) loading.value = true;
  try {
    if (silent) {
      detail.value = mergeProgress(await fetchAiImportProgress(id));
    } else {
      const data = await fetchAiImport(id);
      detail.value = data;
      data.traces.forEach((trace) => promptFilled.add(trace.id));
    }
    const data = detail.value;
    if (!data) return;
    autoExpandLatest();
    syncTyped();
    // 轮询拿到终态：无条件滚到底，保证一次性补齐的最后一段一定可见；
    // 首次打开（非轮询）保持原位，让用户从头看
    const finished =
      data.status === 'SUCCEEDED' || data.status === 'FAILED' || data.status === 'CANCELED';
    void stickToBottom(silent && finished);
    // 轮询里出现没取过提示词的尝试，单独补一次
    const missing = data.traces.find(
      (trace) => !promptFilled.has(trace.id) && !trace.user_prompt && !trace.system_prompt
    );
    if (missing) {
      promptFilled.add(missing.id);
      void fillPrompts(id, missing.id);
    }
    if (data.status === 'SUCCEEDED' || data.status === 'FAILED' || data.status === 'CANCELED') {
      watching.value = false;
    }
    // 执行中要拉；刚点过开始、后台还没接手的 PENDING 也要拉。
    // 一秒一次：流式内容每 800ms 落一次库，这个节奏能看出内容在长，又不至于把请求打满。
    if (data.status === 'RUNNING' || (data.status === 'PENDING' && watching.value)) {
      pollTimer = setTimeout(() => void load(id, true), 1000);
    } else {
      watching.value = false;
      // 轮询到终态时也要让列表刷新一次，否则列表还停在上一次的状态上
      emit('changed');
    }
  } catch (e: any) {
    if (!silent) message.error(e.message);
    stopPolling();
    // 一次导入要跑几分钟，中途偶发失败（网络抖动、宿主重启）不该让页面停在半路；
    // 任务还在跑或刚提交过，就隔三秒再拉一次
    if (silent && (watching.value || detail.value?.status === 'RUNNING')) {
      pollTimer = setTimeout(() => void load(id, true), 3000);
    }
  } finally {
    if (!silent) loading.value = false;
  }
}

async function start() {
  if (!detail.value) return;
  acting.value = true;
  try {
    await startAiImport(detail.value.id);
    watching.value = true;
    message.success('已开始，后台分析中');
    await load(detail.value.id);
    emit('changed');
  } catch (e: any) {
    message.error(e.message);
  } finally {
    acting.value = false;
  }
}

async function rerun() {
  if (!detail.value) return;
  const templateId = detail.value.template_id;
  const go = async () => {
    if (!detail.value) return;
    acting.value = true;
    try {
      await rerunAiImport(detail.value.id);
      watching.value = true;
      // 上一轮的思维链已经被后端清掉，本地展开状态、提示词缓存与打字机一并复位，
      // 否则新一次尝试会继承旧 id 的展开状态，提示词也会被当成「已经取过」
      openTraceIds.value = [];
      autoExpandedId = 0;
      promptFilled.clear();
      resetTyped();
      message.success('已重新提交，后台分析中');
      await load(detail.value.id);
      emit('changed');
    } catch (e: any) {
      message.error(e.message);
    } finally {
      acting.value = false;
    }
  };
  if (templateId === null) {
    void go();
    return;
  }
  const instance = dialog.warning({
    title: '重新分析',
    content: `将重新读一遍文件并再跑一轮分析，结果写回模板「${detail.value.template_name}」`
      + `（新增一个版本、按新计划重建数据块）。分析失败时模板保持原样。`,
    positiveText: '重新分析',
    negativeText: '取消',
    onPositiveClick: async () => {
      instance.loading = true;
      instance.closable = false;
      instance.negativeButtonProps = { disabled: true };
      await go();
    }
  });
}

async function cancel() {
  if (!detail.value) return;
  acting.value = true;
  try {
    await cancelAiImport(detail.value.id);
    message.success('已请求取消');
    await load(detail.value.id);
    emit('changed');
  } catch (e: any) {
    message.error(e.message);
  } finally {
    acting.value = false;
  }
}

function openTemplate() {
  if (detail.value?.template_id) emit('open-template', detail.value.template_id);
}

function traceTitle(trace: AiTraceRow) {
  const parts = [`第 ${trace.attempt} 次`, stageLabel(trace.stage)];
  if (trace.elapsed_ms !== null) parts.push(formatElapsed(trace.elapsed_ms));
  if (trace.completion_tokens !== null) parts.push(`${trace.completion_tokens} tokens`);
  return parts.join(' · ');
}

/** 已收到的字符数：流式期间这个数字一直在涨，是「还在生成」最直观的信号。 */
function traceChars(trace: AiTraceRow) {
  const total = (trace.reasoning?.length ?? 0) + (trace.content?.length ?? 0);
  return total >= 1000 ? `${(total / 1000).toFixed(1)}k` : String(total);
}
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="card"
    :title="detail ? detail.file_name : '导入任务'"
    :mask-closable="false"
    style="width: min(900px, 94vw)"
  >
    <template #header-extra>
      <div class="head-actions">
        <NButton
          v-if="canRerun"
          size="tiny"
          secondary
          type="warning"
          :loading="acting"
          @click="rerun"
        >
          重新分析
        </NButton>
        <NButton v-if="detail?.template_id" size="tiny" secondary type="primary" @click="openTemplate">
          查看模板
        </NButton>
      </div>
    </template>

    <NSpin :show="loading">
      <div v-if="detail" ref="bodyRef" class="modal-body">
        <!-- 状态与阶段固定在顶部：详情很长，滚到计划 JSON 或思维链时仍要能看见当前在哪一步 -->
        <div class="detail-top">
          <div class="detail-head">
            <NTag size="small" :bordered="false" :type="statusMeta.type">{{ statusMeta.label }}</NTag>
            <span class="stage-text">{{ stageText }}</span>
            <span v-if="currentStage" class="stage-step">{{ stepIndex }} / {{ AI_STAGES.length }}</span>
            <span class="head-sep" />
            <span class="muted">耗时 {{ formatElapsed(detail.elapsed_ms) }}</span>
            <span class="head-sep" />
            <span class="muted">创建 {{ formatTime(detail.create_time) }}</span>
          </div>

          <div class="stage-rail" :class="{ 'is-succeeded': succeeded }">
            <span
              v-for="segment in segments"
              :key="segment.stage"
              class="rail-seg"
              :class="`is-${segment.state}`"
              :title="segment.label"
            >
              <i class="rail-fill" />
            </span>
          </div>
        </div>

        <NAlert v-if="detail.error_message" type="error" :bordered="false" style="margin: 12px 0">
          {{ detail.error_message }}
        </NAlert>

        <div v-if="hasBodyActions" class="detail-actions">
          <NButton v-if="canStart" type="primary" size="small" :loading="acting" @click="start">
            开始导入
          </NButton>
          <NButton v-if="canCancel" size="small" :loading="acting" @click="cancel">取消</NButton>
        </div>

        <div class="detail-grid">
          <span>模板名称：{{ detail.template_name }}</span>
          <span>模板编码：{{ detail.template_code }}</span>
          <span>文件类型：{{ detail.file_type }}</span>
          <span>发起人：{{ detail.create_by ?? '-' }}</span>
        </div>
        <div v-if="detail.hint" class="detail-hint">补充要求：{{ detail.hint }}</div>

        <template v-if="detail.failures.length">
          <div class="section-title">每次尝试的失败原因</div>
          <div v-for="item in detail.failures" :key="`f-${item.attempt}`" class="failure">
            <NTag size="small" :bordered="false">第 {{ item.attempt }} 次 · {{ stageLabel(item.stage) }}</NTag>
            <span>{{ item.error }}</span>
          </div>
        </template>

        <div class="section-title">思维链（{{ detail.traces.length }} 次尝试）</div>
        <NEmpty v-if="!detail.traces.length" description="没有记录：可能未开启思维链，或任务还没开始" />
        <div v-else class="trace-list">
          <div
            v-for="trace in detail.traces"
            :key="trace.id"
            class="trace-item"
            :class="{ 'is-live': isStreaming(trace), 'is-typing': typed.traceId === trace.id }"
          >
            <button type="button" class="trace-toggle" @click="toggleTrace(trace.id)">
              <span class="trace-caret" :class="{ 'is-open': isTraceOpen(trace.id) }" />
              <span class="trace-head">
                {{ traceTitle(trace) }}
                <span v-if="isStreaming(trace)" class="trace-live">
                  <i class="trace-live__dot" />生成中 · {{ traceChars(trace) }} 字
                </span>
              </span>
            </button>
            <div
              v-show="isTraceOpen(trace.id)"
              class="trace-body"
            >
              <div v-if="trace.error" class="trace-error">失败原因：{{ trace.error }}</div>
              <div class="trace-label">思考过程</div>
              <pre class="trace-block" :class="{ 'is-live': isStreaming(trace) }">{{ shownReasoning(trace) }}</pre>
              <div class="trace-label">回答原文</div>
              <pre class="trace-block" :class="{ 'is-live': isStreaming(trace) }">{{ shownContent(trace) }}</pre>
              <details class="prompt-block">
                <summary>提示词（system / user）</summary>
                <div class="trace-label">system</div>
                <pre class="trace-block">{{ trace.system_prompt || '（空）' }}</pre>
                <div class="trace-label">user</div>
                <pre class="trace-block">{{ trace.user_prompt || '（空）' }}</pre>
              </details>
            </div>
          </div>
        </div>
      </div>
      <NEmpty v-else description="任务不存在或已被删除" />
    </NSpin>

    <template #footer>
      <NSpace justify="end">
        <NButton @click="visible = false">关闭</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
/* 弹窗内容区限高：任务详情可能很长（计划 JSON + 思维链），不能把弹窗顶出屏幕 */
.modal-body {
  max-height: calc(100vh - 260px);
  overflow-y: auto;
  padding-right: 6px;
}
/* 状态与阶段吸在内容区顶部：滚到计划 JSON 或思维链时仍看得见当前在哪一步。
   底色与卡片一致，否则下面的内容会从字缝里透出来 */
.detail-top {
  position: sticky;
  top: 0;
  z-index: 2;
  padding: 2px 0 12px;
  border-bottom: 1px solid var(--divider);
  background: var(--panel);
}
/* 标题栏右侧的小按钮：重新分析 / 查看模板 */
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-right: 4px;
}
.detail-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.stage-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-strong);
}
.stage-step {
  font-size: 12px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.head-sep {
  width: 1px;
  height: 12px;
  background: var(--border);
}
/* 阶段进度条：八段细条，走完的实心、当前段呼吸、失败段标红 */
.stage-rail {
  display: flex;
  gap: 4px;
  margin: 10px 0 0;
}
.rail-seg {
  position: relative;
  flex: 1;
  height: 6px;
  border-radius: var(--radius-badge);
  background: var(--divider);
  overflow: hidden;
}
.rail-seg .rail-fill {
  position: absolute;
  inset: 0;
  transform: scaleX(0);
  transform-origin: left center;
  transition: transform var(--dur) var(--ease);
}
.rail-seg.is-done .rail-fill {
  transform: scaleX(1);
  background: var(--primary);
}
.rail-seg.is-current .rail-fill {
  transform: scaleX(1);
  background: linear-gradient(90deg, var(--primary), var(--action));
}
.rail-seg.is-failed .rail-fill {
  transform: scaleX(1);
  background: var(--danger);
}
.rail-seg.is-canceled .rail-fill {
  transform: scaleX(1);
  background: var(--text-faint);
}
.stage-rail.is-succeeded .rail-seg.is-done .rail-fill {
  background: var(--success);
}
.rail-seg.is-current {
  animation: rail-breathe 1.6s var(--ease) infinite;
}
@keyframes rail-breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}
@media (prefers-reduced-motion: reduce) {
  .rail-seg.is-current {
    animation: none;
  }
}
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 16px;
  color: #4a5563;
  font-size: 13px;
}
.detail-hint {
  margin-top: 6px;
  color: #6b7785;
  font-size: 13px;
}
.section-title {
  margin: 18px 0 8px;
  color: #33404f;
  font-weight: 600;
  font-size: 13px;
}
.failure {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 6px;
  font-size: 13px;
}
.trace-label {
  margin: 8px 0 4px;
  color: #6b7785;
  font-size: 12px;
}
.trace-block {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f6f8fb;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
}
.trace-error {
  margin-bottom: 8px;
  color: #d03050;
  font-size: 13px;
}
/* 自绘折叠列表：每轮尝试一张小卡片，正在生成的那张描主色边 */
.trace-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.trace-item {
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  background: var(--panel);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}
.trace-item.is-live {
  border-color: var(--action);
  box-shadow: 0 0 0 3px var(--primary-tint);
}
.trace-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border: none;
  border-radius: var(--radius-control);
  background: none;
  color: var(--text-strong);
  font-family: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.trace-toggle:hover {
  background: var(--divider);
}
/* 箭头用一个三角形：旋转 90° 表示展开 */
.trace-caret {
  flex: none;
  width: 0;
  height: 0;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 5px solid var(--text-faint);
  transition: transform var(--dur) var(--ease);
}
.trace-caret.is-open {
  transform: rotate(90deg);
}
.trace-body {
  padding: 0 12px 12px;
}
/* 思维链标题：正在生成的那次尝试带一个呼吸圆点与字数 */
.trace-head {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.trace-live {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--primary);
  font-size: 12px;
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}
.trace-live__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--primary);
  animation: trace-blink 1.2s var(--ease) infinite;
}
@keyframes trace-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}
@media (prefers-reduced-motion: reduce) {
  .trace-live__dot {
    animation: none;
  }
}
/* 流式中的内容块左侧描一道主色，和已经结束的区分开 */
.trace-block.is-live {
  border-left: 2px solid var(--primary);
}
/* 提示词用原生折叠：套一层 NCollapse 会叠出上下各一大段留白 */
.prompt-block {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #eef1f5;
}
.prompt-block > summary {
  cursor: pointer;
  color: #33404f;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.6;
}
.prompt-block > summary:hover {
  color: #2f6fed;
}
.prompt-block .trace-label {
  margin: 10px 0 4px;
}
</style>
