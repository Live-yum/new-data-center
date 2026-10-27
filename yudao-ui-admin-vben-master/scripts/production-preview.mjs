/** Explicit test-only preview; ordinary production/demo commands do not load it. */
import { fileURLToPath } from 'node:url';
import { preview } from 'vite';

if (process.env.CI_CONTRACT_FIXTURE !== 'explicit-test-only')
  throw new Error('Requires explicit-test-only fixture acknowledgement');
const servers = [];
async function close() {
  await Promise.all(
    servers.map(
      (server) =>
        new Promise((resolve) => {
          server.httpServer.close(resolve);
          server.httpServer.closeAllConnections();
        }),
    ),
  );
}
for (const signal of ['SIGINT', 'SIGTERM'])
  process.once(signal, () => {
    void close().then(() => process.exit(0));
  });
try {
  servers.push(
    await preview({
      root: fileURLToPath(new URL('../apps/web-data-center/', import.meta.url)),
    }),
  );
  servers.push(
    await preview({
      configFile: fileURLToPath(
        new URL('./production-harness/vite.config.ts', import.meta.url),
      ),
    }),
  );
  servers.forEach((server) => server.printUrls());
  console.log(
    'TEST FIXTURE ONLY — actual production child and bounded bridge; not site acceptance',
  );
} catch (error) {
  await close();
  throw error;
}
