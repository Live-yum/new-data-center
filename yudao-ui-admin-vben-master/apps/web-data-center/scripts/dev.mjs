import { spawn } from 'node:child_process';

const children = new Set();
const bin = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm';
function run(args) {
  const child = spawn(bin, ['exec', 'vite', ...args], { stdio: 'inherit' });
  children.add(child);
  child.once('exit', () => children.delete(child));
  return child;
}

const build = run(['build']);
const exitCode = await new Promise((resolve, reject) => {
  build.once('exit', resolve);
  build.once('error', reject);
});
if (exitCode !== 0) process.exit(exitCode ?? 1);

run(['build', '--watch']);
run(['preview', '--host', '127.0.0.1', '--port', '5175', '--strictPort']);
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    for (const child of children) child.kill(signal);
  });
}
