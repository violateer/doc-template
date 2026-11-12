import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * 构建标识取包版本号，不取构建时刻，也不取提交号。
 *
 * 这两者都会让产物指纹无谓地变化：时刻每次构建都不同；提交号在「构建 → 提交」之后
 * 又与产物里的值对不上，下次构建必然再变一次。取版本号后，源码不变则产物逐字节一致，
 * 文件名指纹稳定；源码变了，内容哈希本来就会跟着变，不需要额外标识。
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
    emptyOutDir: true
  }
});
