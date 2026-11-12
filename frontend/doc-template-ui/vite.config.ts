import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * 构建标识取包版本号，不取构建时刻，也不取提交号。
 *
 * 时刻每次构建都不同；提交号在「构建 → 提交」之后又与产物里的值对不上，
 * 下次构建必然再变一次。取版本号后，源码不变则产物逐字节一致。
 */
function buildVersion() {
  const pkg = JSON.parse(readFileSync(resolve(__dirname, 'package.json'), 'utf-8')) as { version?: string };
  return pkg.version ?? '0.0.0';
}

export default defineConfig({
  base: '/doc-template/',
  plugins: [vue()],
  define: {
    // 注入包版本号：控制台打印它，用来确认页面加载的是哪个版本的产物
    __BUILD_VERSION__: JSON.stringify(buildVersion())
  },
  build: {
    outDir: resolve(
      __dirname,
      '../../backend/doc-template-spring-boot-starter/src/main/resources/META-INF/resources/doc-template'
    ),
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // 产物文件名固定，不带内容哈希。
        //
        // 带哈希时 index.html 与 assets/ 必须出自同一次构建：两次构建交错、
        // 分支合并时只取了一部分，就会出现「页面引用一个已被删掉的文件」。
        // 服务端把缺失的资源回落成 index.html 返回，浏览器按 module script
        // 校验 MIME 时报 text/html。固定文件名没有这种中间状态。
        //
        // 代价是文件名不再承担缓存失效：由服务端对 /doc-template/** 返回
        // no-cache，浏览器每次请求都重新校验，升级后不会继续跑旧脚本。
        entryFileNames: 'assets/index.js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: (asset) =>
          asset.names.some((name) => name.endsWith('.css'))
            ? 'assets/index.css'
            : 'assets/[name][extname]'
      }
    }
  }
});
