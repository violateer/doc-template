<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  NAlert,
  NButton,
  NCollapse,
  NCollapseItem,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  NSpace,
  NSpin,
  NStep,
  NSteps,
  NTag,
  NUpload,
  NUploadDragger,
  useMessage
} from 'naive-ui';
import type { UploadFileInfo } from 'naive-ui';
import { CloudUploadOutline, DocumentTextOutline } from '@vicons/ionicons5';
import { api, apiPostBlob, fetchAiStatus, uploadTemplate } from '../api';
import type { AiPlanResult, AiStatus, CheckResult, MarkPlan, TemplateRow } from '../types';
import FilePreviewModal from './FilePreviewModal.vue';

/**
 * AI 导入向导：上传一份填好的 Word / Excel，让模型标出哪些是变量，确认后直接变成「模板 + 数据块」。
 *
 * <p>页面只负责展示和改计划，落笔、自检、生成 SQL 全在后端跑 —— 前端不拼任何模板标记。
 * 计划是一份 JSON，模型给的不对可以直接在第二步改，改完重新自检，所以模型出错不会写坏模板。</p>
 *
 * <p>需要开启 AI 才能用：第一步的按钮在 AI 未启用时不可点。</p>
 */
const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{
  (event: 'update:show', value: boolean): void;
  (event: 'saved', template: TemplateRow): void;
}>();

const message = useMessage();
const visible = computed({
  get: () => props.show,
  set: (value: boolean) => emit('update:show', value)
});

const aiStatus = ref<AiStatus | null>(null);
const step = ref(1);
const file = ref<File | null>(null);
const form = ref({ name: '', code: '' });
const hint = ref('');
const analyzing = ref(false);
const saving = ref(false);
const checking = ref(false);
/** 分析成功后建出来的模板 id；为空表示还没建，或者已经放弃 */
const templateId = ref<number | null>(null);
const result = ref<AiPlanResult | null>(null);
/** 计划 JSON 的文本形态，第二步可改；提交时以它为准 */
const planText = ref('');
const planError = ref('');
const previewShow = ref(false);
const previewBlob = ref<Blob | null>(null);
const previewName = ref('');

/** AI 未启用时第一步的按钮不可点，这里给模板和按钮一个统一的判断。 */
const aiEnabled = computed(() => aiStatus.value?.enabled === true);
const canAnalyze = computed(() => !!file.value && !!form.value.name.trim() && !analyzing.value);

watch(
  () => props.show,
  (show) => {
    if (!show) {
      previewShow.value = false;
      return;
    }
    step.value = 1;
    file.value = null;
    form.value = { name: '', code: '' };
    hint.value = '';
    templateId.value = null;
    result.value = null;
    planText.value = '';
    planError.value = '';
    previewBlob.value = null;
    void loadStatus();
  }
);

async function loadStatus() {
  aiStatus.value = await fetchAiStatus();
}

/** 选文件时把模板名 / 编码按文件名预填一份，用户还能改。 */
function onFileChange(options: { fileList: UploadFileInfo[] }) {
  const picked = options.fileList[0]?.file ?? null;
  file.value = picked;
  // 换了文件就是另一份模板，之前建的那个不能再用
  templateId.value = null;
  if (!picked) return;
  const base = picked.name.replace(/\.[^.]+$/, '');
  form.value.name = base;
  form.value.code = suggestCode(base);
}

/** 编码只留字母数字下划线，且必须以字母开头；同名再分析时靠时间戳后缀避开唯一约束。 */
function suggestCode(base: string) {
  const cleaned = base.replace(/[^A-Za-z0-9_]+/g, '_').replace(/^_+|_+$/g, '');
  const head = /^[A-Za-z]/.test(cleaned) ? cleaned : `tpl_${cleaned}`;
  return `${head.slice(0, 40)}_${Date.now().toString(36)}`;
}

async function analyze() {
  if (!file.value || !canAnalyze.value) return;
  analyzing.value = true;
  planError.value = '';
  try {
    if (templateId.value === null) {
      const created = await api<TemplateRow>('/templates', {
        method: 'POST',
        body: JSON.stringify({
          templateName: form.value.name.trim(),
          templateCode: form.value.code.trim(),
          exportType: 'ORIGINAL',
          status: 'ENABLED',
          remark: 'AI 导入向导创建'
        })
      });
      templateId.value = created.id;
      await uploadTemplate(created.id, file.value);
    } else {
      // 同一份文件重新分析：直接换一版，不重复建模板
      await uploadTemplate(templateId.value, file.value);
    }

    const planned = await api<AiPlanResult>(`/templates/${templateId.value}/ai/plan`, {
      method: 'POST',
      body: JSON.stringify({ hint: hint.value.trim() || null })
    });
    result.value = planned;
    if (planned.ok && planned.plan) {
      planText.value = JSON.stringify(planned.plan, null, 2);
      step.value = 2;
      message.success(`分析完成：${planned.blocks?.length ?? 0} 个数据块，${planned.plan.marks.length} 条标记`);
    } else {
      planError.value = planned.error ?? '模型没有给出可用的计划';
      step.value = 2;
      message.warning('模型未给出可用计划，失败原因如下');
    }
  } catch (e: any) {
    planError.value = e.message;
    message.error(e.message);
  } finally {
    analyzing.value = false;
  }
}

/** 改完 JSON 重新跑一遍自检：落笔 + 生成 SQL + 渲染一次，都在后端。 */
async function recheck() {
  if (!templateId.value) return;
  let parsed: MarkPlan;
  try {
    parsed = JSON.parse(planText.value) as MarkPlan;
  } catch (e: any) {
    planError.value = `计划不是合法 JSON：${e.message}`;
    return;
  }
  checking.value = true;
  planError.value = '';
  try {
    const checked = await api<CheckResult>(`/templates/${templateId.value}/annotate/check`, {
      method: 'POST',
      body: JSON.stringify(parsed)
    });
    if (checked.ok) {
      result.value = { ...(result.value ?? { ok: true }), ...checked, plan: parsed };
      message.success('自检通过');
    } else {
      planError.value = checked.error ?? '自检没通过';
    }
  } catch (e: any) {
    planError.value = e.message;
  } finally {
    checking.value = false;
  }
}

async function openPreview(mode: 'template' | 'render') {
  if (!templateId.value) return;
  let parsed: MarkPlan;
  try {
    parsed = JSON.parse(planText.value) as MarkPlan;
  } catch (e: any) {
    message.error(`计划不是合法 JSON：${e.message}`);
    return;
  }
  try {
    previewBlob.value = await apiPostBlob(
      `/templates/${templateId.value}/annotate/preview?mode=${mode}`,
      parsed
    );
    previewName.value = mode === 'template' ? '模板（标记可见）' : '效果预览';
    previewShow.value = true;
  } catch (e: any) {
    message.error(e.message);
  }
}

async function save() {
  if (!templateId.value) return;
  let parsed: MarkPlan;
  try {
    parsed = JSON.parse(planText.value) as MarkPlan;
  } catch (e: any) {
    message.error(`计划不是合法 JSON：${e.message}`);
    return;
  }
  saving.value = true;
  try {
    const applied = await api<{ ok: boolean; version: { id: number } }>(`/templates/${templateId.value}/annotate`, {
      method: 'POST',
      body: JSON.stringify(parsed)
    });
    const fresh = await api<TemplateRow>(`/templates/${templateId.value}`);
    emit('saved', fresh);
    message.success(`已保存：${applied.version ? '生成了新版本，' : ''}数据块已创建`);
    visible.value = false;
  } catch (e: any) {
    message.error(e.message);
  } finally {
    saving.value = false;
  }
}

/** 放弃：把向导建出来的草稿模板删掉，不留一个只有文件没有块的半成品。 */
async function discard() {
  if (templateId.value === null) {
    visible.value = false;
    return;
  }
  try {
    await api(`/templates/${templateId.value}`, { method: 'DELETE' });
    message.success('已删除这个草稿模板');
    visible.value = false;
  } catch (e: any) {
    message.error(e.message);
  }
}

function shapeLabel(shape: string) {
  return shape === 'array' ? '多条' : '单条';
}
</script>

<template>
  <NModal v-model:show="visible" preset="card" title="AI 导入：从填好的文档生成模板" style="width: min(980px, 94vw)">
    <NSteps :current="step" size="small" style="margin-bottom: 18px">
      <NStep title="上传文档" description="选一份已经填好数据的 Word / Excel" />
      <NStep title="确认标注" description="核对模型标注结果，可直接修改计划" />
      <NStep title="预览并保存" description="对比原文和效果，确认后落库" />
    </NSteps>

    <NAlert v-if="aiStatus && !aiEnabled" type="warning" :bordered="false" style="margin-bottom: 14px">
      AI 标注未启用。配置 <code>doc-template.ai</code> 下的 <code>enabled</code>、<code>base-url</code>、
      <code>api-key</code>、<code>model</code> 后重启；或注册 <code>DocTemplateAiClient</code> Bean 接管模型调用。
    </NAlert>

    <!-- 第一步：上传 -->
    <template v-if="step === 1">
      <NUpload
        :default-upload="false"
        :max="1"
        accept=".docx,.xlsx"
        @change="onFileChange"
      >
        <NUploadDragger>
          <div class="wizard-drop">
            <NIcon size="40" color="#2f6fed"><CloudUploadOutline /></NIcon>
            <div class="wizard-drop-title">点击或拖拽文件到这里</div>
            <div class="muted">支持 .docx / .xlsx，一份文档对应一个模板</div>
            <div v-if="file" class="wizard-file">
              <NIcon><DocumentTextOutline /></NIcon>
              <span>{{ file.name }}</span>
            </div>
          </div>
        </NUploadDragger>
      </NUpload>

      <NForm label-placement="left" label-width="86" style="margin-top: 16px">
        <NFormItem label="模板名称">
          <NInput v-model:value="form.name" placeholder="例如：2026 年检验报告" />
        </NFormItem>
        <NFormItem label="模板编码">
          <NInput v-model:value="form.code" placeholder="全局唯一，默认按文件名生成" />
        </NFormItem>
        <NFormItem label="补充要求">
          <NInput
            v-model:value="hint"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="可选。示例：第 2 张表为明细，日期拆分为开始与结束两个字段"
          />
        </NFormItem>
      </NForm>

      <NSpace justify="end">
        <NButton @click="visible = false">取消</NButton>
        <NButton type="primary" :loading="analyzing" :disabled="!canAnalyze || !aiEnabled" @click="analyze">
          {{ analyzing ? '正在分析…' : '开始分析' }}
        </NButton>
      </NSpace>
    </template>

    <!-- 第二步：确认 -->
    <template v-else-if="step === 2">
      <NSpin :show="checking">
        <NAlert v-if="planError" type="error" :bordered="false" style="margin-bottom: 12px">
          {{ planError }}
        </NAlert>

        <div v-if="result?.ok" class="wizard-summary">
          <span>模型：{{ result.model ?? aiStatus?.model ?? '-' }}</span>
          <span>第 {{ result.attempt ?? 1 }} 次尝试通过</span>
          <span>{{ result.blocks?.length ?? 0 }} 个数据块</span>
          <span>{{ result.plan?.marks.length ?? 0 }} 条标记</span>
          <span>产出 {{ Math.round((result.output_bytes ?? 0) / 1024) }} KB</span>
        </div>

        <NAlert
          v-for="(warning, index) in result?.warnings ?? []"
          :key="`w-${index}`"
          type="warning"
          :bordered="false"
          style="margin-bottom: 8px"
        >
          {{ warning }}
        </NAlert>
        <NAlert v-if="result?.unbound?.length" type="info" :bordered="false" style="margin-bottom: 8px">
          以下占位符不属于任何数据块，生成时需手工填写：{{ result.unbound.join('、') }}
        </NAlert>
        <NAlert
          v-for="(item, index) in result?.suggestions ?? []"
          :key="`s-${index}`"
          type="success"
          :bordered="false"
          style="margin-bottom: 8px"
        >
          {{ item.block }}.{{ item.field }}：{{ item.reason }}
        </NAlert>

        <div v-if="result?.failures?.length" class="wizard-failures">
          <div class="wizard-section-title">每次尝试的失败原因</div>
          <div v-for="item in result.failures" :key="item.attempt" class="wizard-failure">
            <NTag size="small" :bordered="false">第 {{ item.attempt }} 次 · {{ item.stage }}</NTag>
            <span>{{ item.error }}</span>
          </div>
        </div>

        <div v-if="result?.blocks?.length" class="wizard-blocks">
          <NCollapse>
            <NCollapseItem
              v-for="block in result.blocks"
              :key="block.code"
              :name="block.code"
              :title="`${block.name || block.code}（${block.code} · ${shapeLabel(block.shape)} · ${block.rows} 行样例）`"
            >
              <table class="wizard-table">
                <thead>
                  <tr>
                    <th>字段</th>
                    <th>标题</th>
                    <th>类型</th>
                    <th>样例值</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="field in block.fields" :key="field.field">
                    <td class="mono">{{ field.field }}</td>
                    <td>{{ field.label }}</td>
                    <td>{{ field.type }}</td>
                    <td class="mono">{{ field.samples.join(' / ') || '（无）' }}</td>
                  </tr>
                </tbody>
              </table>
              <div class="wizard-sql-label">数据源 SQL（常量查询，不访问任何表）</div>
              <pre class="wizard-sql">{{ block.sql }}</pre>
            </NCollapseItem>
          </NCollapse>
        </div>

        <NEmpty v-if="result && !result.ok && !planError" description="未生成可用计划" style="padding: 24px 0" />

        <NCollapse style="margin-top: 14px">
          <NCollapseItem title="计划 JSON（可改，改完点「重新自检」）" name="plan">
            <NInput
              v-model:value="planText"
              type="textarea"
              :autosize="{ minRows: 12, maxRows: 24 }"
              class="wizard-plan-input"
              placeholder="计划 JSON"
            />
            <div class="muted" style="margin-top: 6px">
              标记类型：value 取值 / loop 行循环 / region 区域循环。坐标一律 0 起始，
              <code>rowTo</code> 是样例数据或区域的最后一行。动态列与嵌套循环不在导入范围内。
            </div>
          </NCollapseItem>
        </NCollapse>
      </NSpin>

      <NSpace justify="space-between" style="margin-top: 16px">
        <NButton quaternary type="error" @click="discard">放弃并删除草稿</NButton>
        <NSpace>
          <NButton @click="step = 1">上一步</NButton>
          <NButton :loading="checking" @click="recheck">重新自检</NButton>
          <NButton type="primary" :disabled="!result?.ok" @click="step = 3">下一步：预览</NButton>
        </NSpace>
      </NSpace>
    </template>

    <!-- 第三步：预览与保存 -->
    <template v-else>
      <NAlert type="info" :bordered="false" style="margin-bottom: 14px">
        「模板」是落笔后的文件（标记可见，用于核对位置），「效果」是用样例数据渲染的结果。
      </NAlert>
      <NSpace>
        <NButton @click="openPreview('template')">看模板（标记可见）</NButton>
        <NButton @click="openPreview('render')">看效果（模拟数据）</NButton>
      </NSpace>
      <div class="muted" style="margin-top: 10px">
        保存后写入新版本，并按计划创建 {{ result?.blocks?.length ?? 0 }} 个 SQL 数据块；
        数据块为常量查询，替换为真实表请在数据块里改。
      </div>

      <NSpace justify="space-between" style="margin-top: 20px">
        <NButton quaternary type="error" @click="discard">放弃并删除草稿</NButton>
        <NSpace>
          <NButton @click="step = 2">上一步</NButton>
          <NButton type="primary" :loading="saving" @click="save">保存为模板</NButton>
        </NSpace>
      </NSpace>
    </template>

    <FilePreviewModal
      v-model:show="previewShow"
      src=""
      :blob="previewBlob"
      :filename="`${form.name || 'preview'}.${file?.name.split('.').pop() ?? 'docx'}`"
    />
  </NModal>
</template>

<style scoped>
.wizard-drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 18px 0;
}
.wizard-drop-title {
  font-weight: 600;
}
.wizard-file {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  background: #eaf1ff;
  color: #2f6fed;
}
.wizard-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 12px;
  color: #6b7785;
  font-size: 13px;
}
.wizard-blocks {
  margin-top: 8px;
}
.wizard-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.wizard-table th,
.wizard-table td {
  padding: 6px 8px;
  border-bottom: 1px solid #eef1f5;
  text-align: left;
}
.wizard-table th {
  color: #6b7785;
  font-weight: 500;
}
.wizard-sql-label {
  margin: 10px 0 4px;
  color: #6b7785;
  font-size: 12px;
}
.wizard-sql {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f6f8fb;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.wizard-section-title {
  margin-bottom: 6px;
  color: #6b7785;
  font-size: 13px;
}
.wizard-failure {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 6px;
  font-size: 13px;
}
.wizard-plan-input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
</style>
