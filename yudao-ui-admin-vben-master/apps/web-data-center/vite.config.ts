import { fileURLToPath } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

/** qiankun 2 使用 HTML + UMD 入口；开发与生产加载同一种构建产物。 */
export default defineConfig({
  base: './',
  plugins: [
    vue(),
    {
      name: 'data-center-html-entry',
      generateBundle() {
        this.emitFile({
          type: 'asset',
          fileName: 'index.html',
          source: `<!doctype html>
<html lang="zh-CN"><head><meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>数据中心工作台</title><link rel="stylesheet" href="./style.css" /></head>
<body><div id="data-center-root"></div>
<script src="./data-center.umd.js" entry></script></body></html>`,
        });
      },
    },
  ],
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  build: {
    target: 'es2020',
    lib: {
      entry: fileURLToPath(new URL('./src/main.ts', import.meta.url)),
      name: 'dataCenterApp',
      formats: ['umd'],
      fileName: () => 'data-center.umd.js',
      cssFileName: 'style',
    },
  },
  preview: {
    cors: { origin: /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/ },
    headers: { 'Cache-Control': 'no-store' },
  },
});
