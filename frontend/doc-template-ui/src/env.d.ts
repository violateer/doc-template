/// <reference types="vite/client" />

/** 包版本号，由 vite.config.ts 的 define 注入，用于识别页面加载的是哪个版本的产物 */
declare const __BUILD_VERSION__: string;
