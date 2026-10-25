import { fileURLToPath } from 'node:url';

import { qiankun } from '@qiankunjs/bundler-plugin/vite';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

/** 官方插件为唯一入口模块添加 entry 标记；显式保留 ESM 生命周期导出。 */
export default defineConfig(({ command, isPreview }) => ({
  base: command === 'build' || isPreview ? '/micro-apps/data-center/' : '/',
  plugins: [vue(), qiankun()],
  build: {
    emptyOutDir: true,
    target: 'es2022',
    // 避免 HTML 包装入口生成仅含副作用的 preload chunk。
    modulePreload: false,
    rolldownOptions: {
      input: {
        index: fileURLToPath(new URL('./index.html', import.meta.url)),
        main: fileURLToPath(new URL('./src/main.ts', import.meta.url)),
      },
      // Vite 默认不保留导出；strict 又会让 HTML 指向无生命周期名的共享 chunk。
      preserveEntrySignatures: 'allow-extension',
    },
  },
  server: { host: '127.0.0.1', port: 5175, strictPort: true },
  preview: {
    host: '127.0.0.1',
    port: 5175,
    strictPort: true,
    headers: { 'Cache-Control': 'no-store' },
  },
}));
