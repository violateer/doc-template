import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'node:path';

function buildTime() {
  const now = new Date();
  const pad = (input: number) => String(input).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

export default defineConfig({
  base: '/doc-template/',
  plugins: [vue()],
  define: {
    // 注入构建时刻：页脚会展示它，用来确认浏览器加载的是最新构建产物而不是旧包
    __BUILD_TIME__: JSON.stringify(buildTime())
  },
  build: {
    outDir: resolve(
      __dirname,
      '../../backend/doc-template-spring-boot-starter/src/main/resources/META-INF/resources/doc-template'
    ),
    emptyOutDir: true
  }
});
