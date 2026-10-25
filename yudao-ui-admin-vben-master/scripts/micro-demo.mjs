import { fileURLToPath } from 'node:url';

import { createServer, preview } from 'vite';

const mode = process.argv[2] ?? 'dev';
if (!['dev', 'preview'].includes(mode))
  throw new Error('使用 dev 或 preview 模式');
const servers = [];
let closing;

async function close() {
  closing ??= Promise.all(
    servers.map((server) =>
      'close' in server
        ? server.close()
        : new Promise((resolve, reject) => {
            server.httpServer.close((error) =>
              error ? reject(error) : resolve(),
            );
            server.httpServer.closeAllConnections();
          }),
    ),
  );
  await closing;
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    void close().then(() => process.exit(0));
  });
}

try {
  for (const app of ['web-data-center', 'web-micro-demo']) {
    const root = fileURLToPath(new URL(`../apps/${app}/`, import.meta.url));
    const server = await (mode === 'dev'
      ? createServer({ root })
      : preview({ root }));
    servers.push(server);
    if (mode === 'dev') await server.listen();
    server.printUrls();
  }
  console.log(`微前端演示宿主：http://127.0.0.1:5174/（${mode}，演示数据）`);
} catch (error) {
  await close();
  console.error(error);
  process.exitCode = 1;
}
