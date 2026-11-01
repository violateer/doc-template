/// <reference types="vite/client" />

/** 构建时刻，由 vite.config.ts 的 define 注入，用于一眼识别当前页面是否为最新构建产物 */
declare const __BUILD_TIME__: string;
