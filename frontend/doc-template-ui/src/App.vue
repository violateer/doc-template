<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue';
import { RouterView, useRoute, useRouter } from 'vue-router';
import {
  NConfigProvider,
  NMessageProvider,
  NDialogProvider,
  NNotificationProvider,
  NMenu,
  NTag,
  NIcon,
  zhCN,
  dateZhCN
} from 'naive-ui';
import type { MenuOption } from 'naive-ui';
import { DocumentTextOutline, LibraryOutline, TimeOutline } from '@vicons/ionicons5';
import { themeOverrides } from './theme';
import { fetchStarterVersion, layoutMode } from './api';

const route = useRoute();
const router = useRouter();

function renderIcon(icon: any) {
  return () => h(NIcon, null, { default: () => h(icon) });
}

const menuOptions: MenuOption[] = [
  { label: '模板管理', key: '/templates', icon: renderIcon(DocumentTextOutline) },
  { label: '生成记录', key: '/generate-records', icon: renderIcon(TimeOutline) },
  { label: '数据字典', key: '/dicts', icon: renderIcon(LibraryOutline) }
];

/** 窄屏顶栏空间紧，去掉图标只留文字 */
const compactMenuOptions: MenuOption[] = menuOptions.map((option) => ({ label: option.label, key: option.key }));

const pageTitle = computed(() => (route.meta.title as string) ?? '模板管理');
const pageDesc = computed(() => (route.meta.desc as string) ?? '');
const activeKey = computed(() => route.path);
// 由 vite.config.ts 注入，只在控制台打印，用来确认浏览器加载的是新构建产物（排查「改了没变化」）
const buildTime = __BUILD_TIME__;

/**
 * `?layout=bare`：被宿主 iframe 嵌进内容区时用，**左侧栏和顶栏都不渲染** ——
 * 没有导航，也没有标题，页面上只剩内容区（标题和导航都交给宿主）。
 * 页面切哪个由宿主决定 —— 宿主的菜单直接改 iframe 地址（`#/templates` / `#/generate-records`）。
 * 直接访问 /doc-template/ 时保持 `full`，左侧栏和顶栏照旧。
 */
const bare = layoutMode === 'bare';
// 构建时刻只在控制台打印（页面上不再显示），排查「改了没变化」时看这一行
console.info(`[doc-template] build ${buildTime}`);

/**
 * 左下角展示的版本号取自后端（也就是当前真正对接的那个 Starter），不是前端自己的版本：
 * 页面是打进 jar 一起发的，写死在前端没有意义。取不到（老版本没有这个接口）就只显示「Starter」。
 */
const starterVersion = ref('');
const starterLabel = computed(() => (starterVersion.value ? `Starter v${starterVersion.value}` : 'Starter'));
onMounted(async () => {
  // bare 模式没有左侧栏，不用为看不见的地方多发一次请求
  if (bare) return;
  starterVersion.value = await fetchStarterVersion();
});

/**
 * 窄屏（< 860px）时 `style.css` 会把左侧栏 `display: none` 掉，如果不管，页面就成了
 * 「进得去、出不来」——没有任何导航入口。这里跟着同一个断点，把导航移到顶栏。
 */
const NARROW_QUERY = '(max-width: 860px)';
const narrow = ref(false);
const narrowMedia = window.matchMedia(NARROW_QUERY);
const syncNarrow = () => {
  narrow.value = narrowMedia.matches;
};
syncNarrow();
narrowMedia.addEventListener('change', syncNarrow);
onBeforeUnmount(() => narrowMedia.removeEventListener('change', syncNarrow));

/** 顶栏菜单只在「左侧栏被窄屏隐藏」时出现；`bare` 模式下始终没有任何导航。 */
const showTopbarMenu = computed(() => !bare && narrow.value);
</script>

<template>
  <NConfigProvider :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <NMessageProvider :max="3">
      <NDialogProvider>
        <NNotificationProvider>
          <div class="shell" :class="{ 'shell--bare': bare }">
            <aside v-if="!bare" class="sider">
              <div class="brand">
                <div class="brand-mark" aria-hidden="true">
                  <svg
                    viewBox="0 0 24 24"
                    width="21"
                    height="21"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2.1"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  >
                    <path d="M12 3 3 7.5 12 12l9-4.5L12 3Z" />
                    <path d="M3 12.5 12 17l9-4.5" />
                    <path d="M3 17.5 12 22l9-4.5" />
                  </svg>
                </div>
                <div class="brand-text">
                  <span class="brand-title">Vio Doc Template</span>
                  <span class="brand-sub">文档模板引擎</span>
                </div>
              </div>
              <NMenu
                :value="activeKey"
                :options="menuOptions"
                :indent="18"
                @update:value="(key: string) => router.push(key)"
              />
              <div class="sider-footer">
                <NTag size="small" :bordered="false" type="info">{{ starterLabel }}</NTag>
              </div>
              <div class="sider-meta">Spring Boot 3 · Naive UI</div>
            </aside>

            <main class="main">
              <header v-if="!bare" class="topbar">
                <div class="topbar-text">
                  <h1>{{ pageTitle }}</h1>
                  <p v-if="pageDesc">{{ pageDesc }}</p>
                </div>
                <div class="topbar-extra">
                  <NMenu
                    v-if="showTopbarMenu"
                    class="topbar-menu"
                    mode="horizontal"
                    :value="activeKey"
                    :options="compactMenuOptions"
                    @update:value="(key: string) => router.push(key)"
                  />
                  <slot name="extra" />
                </div>
              </header>
              <section class="page">
                <RouterView v-slot="{ Component }">
                  <component :is="Component" />
                </RouterView>
              </section>
            </main>
          </div>
        </NNotificationProvider>
      </NDialogProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>
