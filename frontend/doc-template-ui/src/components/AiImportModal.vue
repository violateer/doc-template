<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  NAlert,
  NButton,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  NSpace,
  NUpload,
  NUploadDragger,
  useMessage
} from 'naive-ui';
import type { UploadFileInfo } from 'naive-ui';
import { CloudUploadOutline, DocumentTextOutline } from '@vicons/ionicons5';
import { createAiImport, fetchAiStatus } from '../api';
import type { AiImportDetail, AiStatus } from '../types';

/**
 * 新建 AI 导入任务：只上传文件、填名称与补充要求。
 *
 * <p>上传不再建模板 —— 只产生一条任务，真正消耗模型调用的是详情里的「开始导入」。
 * 分析失败时模板列表不会多出一个只有文件的空壳。</p>
 */
const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{
  (event: 'update:show', value: boolean): void;
  (event: 'created', task: AiImportDetail): void;
}>();

const message = useMessage();
const visible = computed({
  get: () => props.show,
  set: (value: boolean) => emit('update:show', value)
});

const aiStatus = ref<AiStatus | null>(null);
const file = ref<File | null>(null);
const form = ref({ name: '', code: '' });
const hint = ref('');
const saving = ref(false);

const aiEnabled = computed(() => aiStatus.value?.enabled === true);
const canSubmit = computed(() => !!file.value && !!form.value.name.trim() && !saving.value);

watch(
  () => props.show,
  (show) => {
    if (!show) return;
    file.value = null;
    form.value = { name: '', code: '' };
    hint.value = '';
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
  if (!picked) return;
  const base = picked.name.replace(/\.[^.]+$/, '');
  form.value.name = base;
  form.value.code = suggestCode(base);
}

/** 编码只留字母数字下划线，且必须以字母开头；留空时后端也会按同样规则生成。 */
function suggestCode(base: string) {
  const cleaned = base.replace(/[^A-Za-z0-9_]+/g, '_').replace(/^_+|_+$/g, '');
  const head = /^[A-Za-z]/.test(cleaned) ? cleaned : `tpl_${cleaned}`;
  return `${head.slice(0, 40)}_${Date.now().toString(36)}`;
}

async function submit() {
  if (!file.value || !canSubmit.value) return;
  saving.value = true;
  try {
    const created = await createAiImport(file.value, {
      templateName: form.value.name.trim(),
      templateCode: form.value.code.trim(),
      hint: hint.value.trim()
    });
    emit('created', created);
    message.success('任务已创建，点「开始导入」后在后台分析');
    visible.value = false;
  } catch (e: any) {
    message.error(e.message);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <NModal v-model:show="visible" preset="card" title="新建 AI 导入任务" style="width: min(720px, 94vw)">
    <NAlert v-if="aiStatus && !aiEnabled" type="warning" :bordered="false" style="margin-bottom: 14px">
      AI 标注未启用。配置 <code>doc-template.ai</code> 下的 <code>enabled</code>、<code>base-url</code>、
      <code>api-key</code>、<code>model</code> 后重启；或注册 <code>DocTemplateAiClient</code> Bean 接管模型调用。
      当前可以先上传任务，等开启后再执行。
    </NAlert>

    <NUpload :default-upload="false" :max="1" accept=".docx,.xlsx" @change="onFileChange">
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
        <NInput v-model:value="form.code" placeholder="全局唯一，留空按文件名生成" />
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
      <NButton type="primary" :loading="saving" :disabled="!canSubmit" @click="submit">创建任务</NButton>
    </NSpace>
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
</style>
