import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

// Reuse the already pinned qiankun dependency; this is not a workspace app.
const require = createRequire(
  new URL('../../apps/web-micro-demo/package.json', import.meta.url),
);
const proxy = {
  '/admin-api': { target: 'http://127.0.0.1:48083', changeOrigin: false },
  '/__fixture': { target: 'http://127.0.0.1:48084', changeOrigin: false },
};
export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  resolve: {
    alias: {
      qiankun: require.resolve('qiankun'),
      'vue-router': require.resolve('vue-router'),
    },
  },
  build: { target: 'es2022', outDir: 'dist', emptyOutDir: true },
  server: { host: '127.0.0.1', port: 5176, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 5176, strictPort: true, proxy },
});
