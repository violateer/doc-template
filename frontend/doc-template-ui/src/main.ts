import { createApp } from 'vue';
import { createRouter, createWebHashHistory } from 'vue-router';
import App from './App.vue';
import TemplatesView from './views/TemplatesView.vue';
import RecordsView from './views/RecordsView.vue';
import DictsView from './views/DictsView.vue';
import AiImportsView from './views/AiImportsView.vue';
import './style.css';

const router = createRouter({
  history: createWebHashHistory('/doc-template/'),
  routes: [
    { path: '/', redirect: '/templates' },
    {
      path: '/templates',
      component: TemplatesView,
      meta: { title: '模板管理', desc: '模板信息、文件版本与数据块在同一弹窗内维护' }
    },
    {
      path: '/generate-records',
      component: RecordsView,
      meta: { title: '生成记录', desc: '查看每次模拟生成的参数、结果文件与失败原因' }
    },
    {
      path: '/dicts',
      component: DictsView,
      meta: { title: '数据字典', desc: '维护枚举值到显示名的映射，供 dict 转换器使用' }
    },
    {
      path: '/ai-imports',
      component: AiImportsView,
      meta: { title: 'AI 导入', desc: '上传填好的文档，后台分析成模板与数据块' }
    }
  ]
});

router.afterEach((to) => {
  const title = (to.meta.title as string) ?? 'Vio Doc Template';
  document.title = `${title} · Vio Doc Template`;
});

createApp(App).use(router).mount('#app');
