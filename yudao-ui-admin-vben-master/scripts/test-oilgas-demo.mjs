/** Real built qiankun oil/gas demo checks. No image fabrication or live plant access. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { chromium } from 'playwright';
// CI pins Node 22.18+, with native erasable TypeScript support.
import { oilgasScenes } from '../packages/micro-apps/src/oilgas-demo.ts';

const directory = resolve(
  process.env.MICRO_DEMO_SCREENSHOT_DIR || 'test-results/oilgas',
);
await mkdir(directory, { recursive: true });
const commit = execFileSync('git', ['rev-parse', 'HEAD'], {
  encoding: 'utf8',
}).trim();
if (process.env.GITHUB_SHA)
  assert.equal(
    commit,
    process.env.GITHUB_SHA,
    'Evidence must identify the exact checked-out commit',
  );
const scenes = oilgasScenes.map((scene) => [
  scene.id,
  scene.devices[0].id,
  scene.name,
]);
assert.deepEqual(
  scenes.map(([id]) => id),
  ['separation', 'gas', 'storage', 'utilities', 'offshore'],
);
const base = process.env.MICRO_DEMO_URL || 'http://127.0.0.1:5174/';
function sceneUrl(scene) {
  const url = new URL(base);
  url.hash = `/data-center?view=demo-${scene}`;
  return url.toString();
}
const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined,
  headless: true,
});
const context = await browser.newContext({
  viewport: { width: 1600, height: 1100 },
  locale: 'zh-CN',
  reducedMotion: 'reduce',
});
await context.tracing.start({
  screenshots: true,
  snapshots: true,
  sources: true,
});
const errors = [];
const writes = [];
const checks = [];
const captures = [];
const page = await context.newPage();
page.setDefaultTimeout(15_000);
page.on('pageerror', (error) => errors.push(error.message));
context.on('request', (request) => {
  if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method()))
    writes.push(`${request.method()} ${request.url()}`);
});
let failure;
async function until(test, description) {
  const deadline = Date.now() + 15_000;
  while (!(await test())) {
    if (Date.now() >= deadline) throw new Error(`Timeout: ${description}`);
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}
async function check(name, action) {
  await action();
  checks.push(name);
  console.log(`PASS ${name}`);
}
async function ready(scene) {
  await page.getByTestId('status').filter({ hasText: '已挂载' }).waitFor();
  await page
    .locator(`[data-testid="oilgas-demo"][data-scene="${scene}"]`)
    .waitFor();
  await page.getByTestId('pending').filter({ hasText: /^0$/ }).waitFor();
  await page.getByTestId('oilgas-trend').locator('svg').waitFor();
  assert.equal(
    await page.locator('.dc-shell').count(),
    1,
    'Only one genuine child may be mounted',
  );
  assert.equal(
    await page
      .getByTestId('micro-container')
      .locator('[data-testid="oilgas-demo"]')
      .count(),
    1,
  );
}
async function diagramGeometry() {
  const problems = await page.getByTestId('process-diagram').evaluate((svg) => {
    const view = svg.getBoundingClientRect();
    const text = [...svg.querySelectorAll('text')]
      .map((node) => ({
        label: node.textContent,
        box: node.getBoundingClientRect(),
      }))
      .filter(({ box }) => box.width && box.height);
    const problems = [];
    const panel = svg.closest('.og-flow-panel').getBoundingClientRect();
    if (view.left < panel.left - 1 || view.right > panel.right + 1)
      problems.push('Diagram extends beyond the screenshot-visible panel');
    for (const { label, box } of text) {
      if (
        box.x < view.x - 1 ||
        box.y < view.y - 1 ||
        box.x + box.width > view.x + view.width + 1 ||
        box.y + box.height > view.y + view.height + 1
      )
        problems.push(`Clipped text: ${label}`);
    }
    for (let i = 0; i < text.length; i++)
      for (let j = i + 1; j < text.length; j++) {
        const a = text[i].box,
          b = text[j].box;
        if (
          Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x) > 1 &&
          Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y) > 1
        )
          problems.push(
            `Overlapping labels: ${text[i].label} / ${text[j].label}`,
          );
      }
    return problems;
  });
  assert.deepEqual(
    problems,
    [],
    'Process diagram labels must not overlap or clip',
  );
}
async function capture(scene, mode) {
  await page.evaluate(() => document.fonts.ready);
  await diagramGeometry();
  assert.equal(
    await page.evaluate(
      () =>
        document.documentElement.scrollWidth <=
        document.documentElement.clientWidth,
    ),
    true,
    `${scene}/${mode}: no document horizontal overflow`,
  );
  const root = page.getByTestId('oilgas-demo');
  const filename = `oilgas-${scene}-${mode}.png`;
  // Locator screenshot captures the real rendered child only, excluding host controls.
  await root.screenshot({
    path: join(directory, filename),
    animations: 'disabled',
  });
  captures.push({
    filename,
    scene,
    viewport: page.viewportSize(),
    url: page.url(),
    commit,
    browser: browser.version(),
    source: 'demo',
    childEntry: await page.locator('.demo-entry code').innerText(),
    mountStatus: await page.getByTestId('status').innerText(),
    screenshotTarget: '[data-testid="oilgas-demo"]',
    checks: [
      'real qiankun mount',
      'sample marker',
      'diagram label geometry',
      'no horizontal overflow',
    ],
  });
}
try {
  await page.goto(sceneUrl('separation'));
  for (const [scene, firstDevice, name] of scenes) {
    await check(
      `${name}: real scene navigation, catalog equipment choices and read-only samples`,
      async () => {
        if (scene !== 'separation')
          await page.getByTestId(`scene-${scene}`).click();
        await page.waitForURL(new RegExp(`view=demo-${scene}`));
        await ready(scene);
        const root = page.getByTestId('oilgas-demo');
        assert.match(await root.innerText(), /演示|示例/);
        assert.match(
          await page.getByTestId('oilgas-disclaimer').innerText(),
          /非工程设计|不.*工程设计|不是工程设计/,
        );
        assert.match(
          await page.getByTestId('oilgas-readonly').innerText(),
          /只读/,
        );
        assert.equal(
          await root
            .getByRole('button', {
              name: /启动|停机|启停|开阀|关阀|下发|写入|设置参数/,
            })
            .count(),
          0,
          'No plant write controls',
        );
        const catalog = oilgasScenes.find((entry) => entry.id === scene);
        assert.equal(
          await root.locator('[data-testid^="equipment-"]').count(),
          catalog.devices.length,
        );
        for (const device of catalog.devices) {
          const id = String(device.id);
          await page.getByTestId(`equipment-${id}`).click();
          await until(
            async () =>
              (await root.getAttribute('data-selected-device')) === id,
            'selected equipment changes',
          );
          assert.equal(
            await page
              .getByTestId(`equipment-${id}`)
              .getAttribute('aria-pressed'),
            'true',
          );
          await until(
            async () =>
              (await page
                .getByTestId('oilgas-trend')
                .getAttribute('data-device-id')) === id,
            'trend follows selected device',
          );
          await ready(scene);
        }
        const continuousMetrics = catalog.metrics
          .filter((entry) => entry.metricKey !== 'running_state')
          .map((entry) => entry.metricKey);
        assert.deepEqual(
          await page
            .getByTestId('oilgas-metric')
            .locator('option')
            .evaluateAll((nodes) => nodes.map((node) => node.value).sort()),
          [...continuousMetrics].sort(),
        );
        for (const metric of continuousMetrics) {
          await page.getByTestId('oilgas-metric').selectOption(metric);
          await until(
            async () =>
              (await page
                .getByTestId('oilgas-trend')
                .getAttribute('data-metric')) === metric,
            'trend follows selected metric',
          );
          await ready(scene);
          assert.ok(
            (await page.getByTestId('oilgas-samples').innerText()).includes(
              metric === 'flow' && catalog.devices.at(-1).flowUnit
                ? catalog.devices.at(-1).flowUnit
                : catalog.metrics.find((entry) => entry.metricKey === metric)
                    .unit,
            ),
            'Sample units must match the selected catalog metric',
          );
        }
        assert.match(await root.innerText(), /样例运行状态/);
        const samples = await page.getByTestId('oilgas-samples').innerText();
        assert.match(samples, /质量|quality/i);
        await page.getByTestId(`equipment-${firstDevice}`).click();
        await page
          .getByTestId('oilgas-metric')
          .selectOption(catalog.metrics[0].metricKey);
        await ready(scene);
        await capture(scene, 'desktop');
      },
    );
  }
  await check(
    'Browser Back/Forward, reload and invalid-route fallback',
    async () => {
      await page.goBack();
      await ready('utilities');
      await page.goForward();
      await ready('offshore');
      await page.reload();
      await ready('offshore');
      const invalid = new URL(base);
      invalid.hash = '/data-center?view=demo-invalid';
      await page.goto(invalid.toString());
      await page
        .getByTestId('route')
        .filter({ hasText: '/overview' })
        .waitFor();
      await page.locator('.data-center-app').waitFor();
      assert.equal(await page.getByTestId('oilgas-demo').count(), 0);
    },
  );
  await check(
    'Mobile separation screenshot, sample visibility and diagram geometry',
    async () => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.goto(sceneUrl('separation'));
      await ready('separation');
      await capture('separation', 'mobile');
      await page.setViewportSize({ width: 1600, height: 1100 });
    },
  );
  await check(
    'Read failure clears stale telemetry and recovers without fabricated values',
    async () => {
      await page.getByLabel('模拟数据请求失败', { exact: true }).check();
      await page.getByTestId('oilgas-demo').getByRole('alert').waitFor();
      assert.equal(
        await page.getByTestId('oilgas-trend').locator('svg').count(),
        0,
      );
      assert.match(
        await page.getByTestId('oilgas-samples').innerText(),
        /暂无样例数据/,
      );
      await page.getByLabel('模拟数据请求失败', { exact: true }).uncheck();
      await ready('separation');
      assert.equal(
        await page.getByTestId('oilgas-demo').getByRole('alert').count(),
        0,
      );
    },
  );
  await check(
    'Unmount cancels in-flight reads and stops polling; clean remount',
    async () => {
      await page.getByTestId('oilgas-metric').selectOption('temperature');
      await page
        .getByTestId('pending')
        .filter({ hasText: /^[1-9]\d*$/ })
        .waitFor();
      await page
        .getByRole('button', { name: '卸载子应用', exact: true })
        .click();
      await page.getByTestId('status').filter({ hasText: '未挂载' }).waitFor();
      await page.getByTestId('pending').filter({ hasText: /^0$/ }).waitFor();
      assert.equal(await page.locator('.dc-shell').count(), 0);
      const lateRequests = await page.getByTestId('pending').evaluate(
        (node) =>
          new Promise((resolve) => {
            const values = [];
            const observer = new MutationObserver(() => {
              if (node.textContent.trim() !== '0')
                values.push(node.textContent);
            });
            observer.observe(node, {
              childList: true,
              characterData: true,
              subtree: true,
            });
            setTimeout(() => {
              observer.disconnect();
              resolve(values);
            }, 10_500);
          }),
      );
      assert.deepEqual(
        lateRequests,
        [],
        'Unmount must clear polling beyond two five-second intervals',
      );
      await page
        .getByRole('button', { name: '挂载 / 重试', exact: true })
        .click();
      await ready('separation');
    },
  );
  assert.deepEqual(writes, [], 'Read-only scenes must not emit write requests');
  assert.deepEqual(
    errors,
    [],
    'No uncaught page errors, including cancellation',
  );
} catch (error) {
  failure = error;
  await page
    .screenshot({ path: join(directory, 'oilgas-failure.png'), fullPage: true })
    .catch(() => {});
  throw error;
} finally {
  await writeFile(
    join(directory, 'oilgas-verification.json'),
    JSON.stringify(
      {
        capturedAt: new Date().toISOString(),
        commit,
        browser: browser.version(),
        passed: !failure,
        scenes: scenes.map(([scene]) => scene),
        checks,
        captures,
        writes,
        pageErrors: errors,
        failure: failure ? String(failure.stack || failure) : null,
        note: '真实生产构建 qiankun 子应用截图；合成只读示例数据，未连接生产/实时库；概念工艺示意，不是工程设计。',
      },
      null,
      2,
    ) + '\n',
  );
  await context.tracing.stop({ path: join(directory, 'oilgas-trace.zip') });
  await browser.close();
}
