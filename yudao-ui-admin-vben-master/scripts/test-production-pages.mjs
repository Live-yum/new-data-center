/** Actual built child + shared production bridge + authenticated packaged API.
 * External RPC/vendor peers are explicit fixtures. NEVER actual site evidence.
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { chromium } from 'playwright';

if (process.env.CI_CONTRACT_FIXTURE !== 'explicit-test-only')
  throw new Error('Requires explicit-test-only fixture acknowledgement');
const directory = resolve(
  process.env.PRODUCTION_TEST_ARTIFACT_DIR ||
    'test-results/production-contract',
);
await mkdir(directory, { recursive: true });
const commit = execFileSync('git', ['rev-parse', 'HEAD'], {
  encoding: 'utf8',
}).trim();
if (process.env.GITHUB_SHA) assert.equal(commit, process.env.GITHUB_SHA);
const { devices, peer, unavailable } = JSON.parse(
  await readFile(join(directory, 'seed.json'), 'utf8'),
);
const scenes = ['separation', 'gas', 'storage', 'utilities', 'offshore'];
const base = 'http://127.0.0.1:5176/';
const checks = [],
  captures = [],
  requests = [],
  errors = [],
  dialogs = [];
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
const page = await context.newPage();
page.setDefaultTimeout(20_000);
page.on('pageerror', (error) => errors.push(error.message));
let expectedDialog;
page.on('dialog', async (dialog) => {
  const action = expectedDialog;
  expectedDialog = undefined;
  dialogs.push({
    message: dialog.message(),
    action: action || 'UNEXPECTED_DISMISSED',
  });
  if (action === 'accept') await dialog.accept();
  else await dialog.dismiss();
});
context.on('request', (request) => {
  const url = new URL(request.url());
  if (
    url.pathname.startsWith('/admin-api/') ||
    url.pathname.startsWith('/api/')
  )
    requests.push({
      path: url.pathname,
      method: request.method(),
      origin: url.origin,
    });
});
const root = () => page.getByTestId('production-scenes');
const point = (metric = 'pressure') =>
  page
    .getByTestId('production-live')
    .locator(`.pr-reading[data-metric="${metric}"]`);
const tick = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function until(test, description, timeout = 20_000) {
  const end = Date.now() + timeout;
  while (!(await test())) {
    if (Date.now() > end) throw new Error(`Timeout: ${description}`);
    await tick(50);
  }
}
async function check(name, run) {
  await run();
  checks.push(name);
  console.log(`PASS ${name}`);
}
async function api(path, method = 'GET', data) {
  const response = await fetch(`http://127.0.0.1:48083/admin-api/iot${path}`, {
    method,
    headers: {
      Authorization: 'Bearer ci-admin-tenant-7001',
      'tenant-id': '7001',
      'Content-Type': 'application/json',
    },
    body: data === undefined ? undefined : JSON.stringify(data),
  });
  const value = await response.json();
  assert.equal(value.code, 0, JSON.stringify({ path, value }));
  return value.data;
}
async function control(mode = 'normal', delayMs = 0, source = 'source-a', historyDelayMs = 0) {
  const port = source === 'source-b' ? 48085 : 48084;
  const response = await fetch(`http://127.0.0.1:${port}/__fixture/control`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode, delayMs, historyDelayMs }),
  });
  assert.equal(response.status, 200);
}
async function mounted(scene) {
  await page
    .getByTestId('harness-status')
    .filter({ hasText: /^已挂载$/ })
    .waitFor();
  await page
    .locator(`[data-testid="production-scenes"][data-template="${scene}"]`)
    .waitFor();
  await until(
    async () => (await root().getAttribute('aria-busy')) === 'false',
    'production initialization',
  );
  assert.equal(await page.locator('.dc-shell').count(), 1);
  assert.equal(await page.getByTestId('production-unavailable').count(), 0);
}
async function ready(scene, value = 10.25, source = 'source-a') {
  await mounted(scene);
  await page.getByTestId('production-trend').locator('svg').first().waitFor();
  await until(
    async () => (await point().getAttribute('data-status')) === 'fresh',
    'REALDB device-time fresh reading',
  );
  const formatted = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 }).format(value);
  assert.ok((await point().locator('strong').innerText()).includes(formatted));
  assert.equal(await page.getByTestId('production-live').getAttribute('data-source-id'), source);
  assert.match(await point().innerText(), /GOOD/);
}
async function goto(scene) {
  await page.goto(`${base}#/process-${scene}`);
  await mounted(scene);
}
async function diagramGeometry() {
  const issues = await page.getByTestId('process-diagram').evaluate((svg) => {
    const bounds = svg.getBoundingClientRect();
    const labels = [...svg.querySelectorAll('text')]
      .map((node) => ({
        text: node.textContent.trim(),
        box: node.getBoundingClientRect(),
      }))
      .filter(({ box }) => box.width && box.height);
    const issues = [];
    for (const { text, box } of labels) {
      if (
        box.left < bounds.left - 1 ||
        box.right > bounds.right + 1 ||
        box.top < bounds.top - 1 ||
        box.bottom > bounds.bottom + 1
      )
        issues.push(`Clipped: ${text}`);
      if (box.height < 10) issues.push(`Unreadably small: ${text}`);
    }
    for (let i = 0; i < labels.length; i++)
      for (let j = i + 1; j < labels.length; j++) {
        const a = labels[i].box,
          b = labels[j].box;
        if (
          Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 &&
          Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1
        )
          issues.push(`Overlap: ${labels[i].text} / ${labels[j].text}`);
      }
    return issues;
  });
  assert.deepEqual(
    issues,
    [],
    'Actual configured equipment labels must not overlap/clip',
  );
}
async function capture(scene, viewport) {
  await page.evaluate(() => document.fonts.ready);
  await diagramGeometry();
  assert.equal(
    await page.evaluate(
      () =>
        document.documentElement.scrollWidth <=
        document.documentElement.clientWidth,
    ),
    true,
    'No document horizontal overflow',
  );
  await page.getByTestId('contract-fixture-banner').waitFor();
  const filename = `production-contract-${scene}-${viewport}.png`;
  // Preserve the visible fixture banner; NEVER crop it to imply site evidence.
  await page.screenshot({
    path: join(directory, filename),
    fullPage: true,
    animations: 'disabled',
  });
  captures.push({
    filename,
    scene,
    viewport: page.viewportSize(),
    url: page.url(),
    commit,
    browser: browser.version(),
    evidenceType: 'AUTOMATED_CONTRACT_FIXTURE',
    apiDataSource: 'Independent named REALDB adapters with test-only upstreams',
    screenshotIncludesFixtureBanner: true,
  });
}
let failure;
try {
  await check(
    'Empty production page has no simulated devices or values; source configuration is not connectivity',
    async () => {
      await goto('separation');
      await page.getByTestId('scene-empty').waitFor();
      assert.equal(await page.getByTestId('process-diagram').count(), 0);
      assert.match(
        await page.getByTestId('source-status').innerText(),
        /已配置.*连接待/,
      );
      assert.equal(await page.getByTestId('oilgas-demo').count(), 0);
    },
  );

  for (const scene of scenes) {
    await check(
      `${scene}: configure actual platform device mappings, save, reload and render production page`,
      async () => {
        if (scene !== 'separation') {
          await page.getByTestId(`production-${scene}`).click();
          await mounted(scene);
        }
        await page.getByTestId('scene-empty').waitFor();
        await page.getByTestId('scene-new').click();
        await page
          .getByTestId('scene-name')
          .fill(`契约测试 ${scene}（非现场）`);
        await page
          .getByTestId('scene-description')
          .fill('自动化契约夹具；真实生产代码和API，不代表紫金桥现场验收。');
        await page.getByTestId('scene-refresh-seconds').fill('5');
        await page.getByTestId('scene-stale-seconds').fill('15');
        for (let index = 1; index <= 4; index++) {
          await page
            .getByTestId(`slot-${index}-device`)
            .selectOption(String(devices[index - 1].id));
          await page
            .getByTestId(`slot-${index}-metrics`)
            .selectOption(['pressure', 'temperature']);
          await page
            .getByTestId(`slot-${index}-primary`)
            .selectOption('pressure');
        }
        await page.getByTestId('scene-save').click();
        await page.getByTestId('scene-editor').waitFor({ state: 'detached' });
        await ready(scene);
        const saved = (await api('/process-pages')).find(
          (item) => item.template === scene,
        );
        assert.ok(saved?.id > 0);
        assert.deepEqual(
          saved.bindings.map((item) => item.deviceId),
          devices.map((item) => item.id),
        );
        await page.reload();
        await ready(scene);
        assert.equal(
          await page.getByTestId('production-scene-select').inputValue(),
          String(saved.id),
        );
        await capture(scene, 'desktop');
      },
    );
  }

  await check(
    'Browser Back/Forward and source-readiness navigation preserve production routes',
    async () => {
      await page.getByTestId('production-utilities').click();
      await ready('utilities');
      await page.getByTestId('production-offshore').click();
      await ready('offshore');
      await page.goBack();
      await ready('utilities');
      await page.goForward();
      await ready('offshore');
      await page
        .getByRole('link', { name: '紫金桥数据源', exact: true })
        .click();
      await page.getByTestId('production-sources').waitFor();
      assert.match(
        await page.getByTestId('production-sources').innerText(),
        /配置完整不等于网络连通/,
      );
      await page.getByRole('link', { name: '生产工艺', exact: true }).click();
      await ready('separation');
    },
  );

  await check(
    'Diagram selection, actual units/qualities and history metric changes',
    async () => {
      await page.getByTestId('equipment-2').click();
      await until(
        async () =>
          (await page
            .getByTestId('production-live')
            .getAttribute('data-device-id')) === String(devices[1].id),
        'diagram device binding',
      );
      await until(
        async () => (await point().getAttribute('data-status')) === 'bad',
        'BAD remains visibly bad',
      );
      assert.doesNotMatch(
        await point().locator('strong').innerText(),
        /20\.25/,
      );
      await page.getByTestId('production-node').selectOption('slot-3');
      await until(
        async () => (await point().getAttribute('data-status')) === 'unknown',
        'UNKNOWN does not become GOOD',
      );
      await page.getByTestId('production-node').selectOption('slot-1');
      await ready('separation');
      await page.getByTestId('production-metric').selectOption('temperature');
      await until(
        async () =>
          (await page
            .getByTestId('production-trend')
            .getAttribute('data-metric')) === 'temperature',
        'trend metric binding',
      );
      await until(
        async () =>
          (await page.getByTestId('production-samples').innerText()).includes(
            '°C',
          ),
        'actual history units',
      );
      await page.getByTestId('production-metric').selectOption('pressure');
      await ready('separation');
    },
  );

  await check(
    'Missing, stale, received-only timestamps and upstream outage never become fresh synthetic values',
    async () => {
      for (const [mode, expected, text] of [
        ['missing', 'missing', /缺测/],
        ['stale', 'stale', /陈旧/],
        ['received', 'unknown', /接收|采集时间未知/],
        ['failure', 'failed', /失败/],
      ]) {
        await control(mode);
        await page
          .getByTestId('production-live')
          .getByRole('button', { name: '立即读取', exact: true })
          .click();
        await until(
          async () => (await point().getAttribute('data-status')) === expected,
          `${mode} state`,
        );
        assert.match(await point().innerText(), text);
        if (mode !== 'received')
          assert.doesNotMatch(
            await point().locator('strong').innerText(),
            /10\.25/,
          );
      }
      await control();
      await page
        .getByTestId('production-live')
        .getByRole('button', { name: '立即读取', exact: true })
        .click();
      await ready('separation');
    },
  );

  await check(
    'Removed point mapping is shown explicitly without invented values, then restored through real API',
    async () => {
      const path = `/devices/${devices[0].id}/mappings`;
      const original = await api(path);
      try {
        await api(
          path,
          'PUT',
          original.filter((mapping) => mapping.metricKey !== 'pressure'),
        );
        await page.getByTestId('scene-reload').click();
        await mounted('separation');
        await until(
          async () =>
            (await point().getAttribute('data-status')) === 'mapping-missing',
          'removed mapping state',
        );
        assert.match(await point().innerText(), /映射缺失/);
        assert.doesNotMatch(
          await point().locator('strong').innerText(),
          /10\.25/,
        );
      } finally {
        await api(path, 'PUT', original);
      }
      await page.getByTestId('scene-reload').click();
      await ready('separation');
    },
  );

  await check(
    'Production history creates a real export job and downloads authenticated CSV through shared bridge',
    async () => {
      const previous = new Set((await api('/exports')).map((job) => job.id));
      await page.getByTestId('production-export').click();
      let job;
      await until(async () => {
        job = (await api('/exports')).find((entry) => !previous.has(entry.id));
        return !!job;
      }, 'persisted export job');
      await until(
        async () => {
          job = (await api('/exports')).find((entry) => entry.id === job.id);
          assert.notEqual(job.status, 'FAILED', job.error);
          return job.status === 'SUCCEEDED';
        },
        'actual export worker',
        30_000,
      );
      await page
        .getByRole('link', { name: '下载任务', exact: true })
        .first()
        .click();
      const row = page.getByTestId(`iot-export-${job.id}`);
      await row.waitFor();
      const downloadPromise = page.waitForEvent('download');
      await row.getByRole('button', { name: '下载 CSV', exact: true }).click();
      const download = await downloadPromise;
      const filename = join(directory, 'production-contract-history.csv');
      await download.saveAs(filename);
      const csv = await readFile(filename, 'utf8');
      assert.match(csv, /MPa|°C/);
      assert.match(csv, /GOOD|MISSING/);
      assert.ok(csv.split('\n').length > 10);
      await page.getByRole('link', { name: '生产工艺', exact: true }).click();
      await ready('separation');
    },
  );

  await check(
    'Conflict retains draft; cancel preserves remote version and explicit reload restores it',
    async () => {
      const saved = (await api('/process-pages')).find(
        (item) => item.template === 'separation',
      );
      await page.getByTestId('scene-edit').click();
      await page.getByTestId('scene-name').fill('本地未覆盖草稿');
      const changed = await api(`/process-pages/${saved.id}`, 'PUT', {
        ...saved,
        name: '服务端并发修改（契约测试）',
      });
      await page.getByTestId('scene-save').click();
      await page.getByTestId('scene-conflict').waitFor();
      assert.equal(
        await page.getByTestId('scene-name').inputValue(),
        '本地未覆盖草稿',
      );
      assert.equal(
        (await api(`/process-pages/${saved.id}`)).version,
        changed.version,
      );
      await page.getByTestId('scene-cancel').click();
      await page.getByTestId('scene-reload').click();
      await ready('separation');
      assert.match(
        await page.getByTestId('production-scene-select').innerText(),
        /服务端并发修改/,
      );
    },
  );

  await check(
    'Dirty internal navigation and voluntary host exit require confirmation; URL reconciles after cancellation',
    async () => {
      await page.getByTestId('scene-edit').click();
      await page.getByTestId('scene-name').fill('保留未保存草稿');
      const before = page.url();
      expectedDialog = 'dismiss';
      await page.getByTestId('production-gas').click();
      await until(
        async () => expectedDialog === undefined,
        'internal navigation confirmation',
      );
      assert.equal(page.url(), before);
      assert.equal(
        await page.getByTestId('scene-name').inputValue(),
        '保留未保存草稿',
      );
      expectedDialog = 'dismiss';
      await page.locator('#leave-host').click();
      await until(
        async () => expectedDialog === undefined,
        'host exit confirmation',
      );
      assert.equal(page.url(), before);
      assert.equal(await page.locator('.dc-shell').count(), 1);
      // Host-owned navigation is guarded before history is committed.
      expectedDialog = 'dismiss';
      await page.locator('#host-storage').click();
      await until(
        async () => expectedDialog === undefined && page.url() === before,
        'host URL restored after rejected child update',
      );
      expectedDialog = 'accept';
      await page.getByTestId('production-gas').click();
      await ready('gas');
      assert.equal(await page.getByTestId('scene-editor').count(), 0);
      await page.getByTestId('scene-edit').click();
      await page.getByTestId('scene-name').fill('浏览器后退仍保留草稿');
      const currentUrl = page.url();
      expectedDialog = 'dismiss';
      await page.evaluate(() => history.back());
      await until(
        async () => expectedDialog === undefined && page.url() === currentUrl,
        'Back cancellation restores history location',
      );
      assert.equal(
        await page.getByTestId('scene-name').inputValue(),
        '浏览器后退仍保留草稿',
      );
      expectedDialog = 'accept';
      await page.evaluate(() => history.back());
      await ready('separation');
      await page.goForward();
      await ready('gas');
    },
  );

  await check(
    'In-flight save blocks repeated mutation and host exit, then persists once',
    async () => {
      await page.getByTestId('scene-edit').click();
      await page.getByTestId('scene-name').fill('一次保存（契约测试）');
      let release,
        intercepted = false;
      const gate = new Promise((resolve) => {
        release = resolve;
      });
      const matcher = '**/admin-api/iot/process-pages/*';
      const delayRealRequest = async (route) => {
        if (route.request().method() === 'PUT') {
          intercepted = true;
          await gate;
        }
        await route.continue(); // Never fulfill/fabricate a response: actual backend receives it unchanged.
      };
      await page.route(matcher, delayRealRequest);
      try {
        await page.getByTestId('scene-save').click();
        await until(async () => intercepted, 'real save is pending');
        assert.equal(await page.getByTestId('scene-save').isDisabled(), true);
        assert.equal(await page.getByTestId('scene-cancel').isDisabled(), true);
        const before = page.url();
        await page.locator('#leave-host').click();
        assert.equal(page.url(), before);
        assert.equal(await page.locator('.dc-shell').count(), 1);
      } finally {
        release();
      }
      await page.getByTestId('scene-editor').waitFor({ state: 'detached' });
      await page.unroute(matcher, delayRealRequest);
      await ready('gas');
    },
  );

  await check(
    'Tenant switch clears dirty draft and late in-flight readings, including empty destination tenant',
    async () => {
      await control('normal', 3000);
      await page.getByTestId('production-metric').selectOption('temperature');
      await page.getByTestId('scene-edit').click();
      await page.getByTestId('scene-name').fill('不可泄露的旧租户草稿');
      await page.locator('#tenant-two').click();
      await page.getByTestId('scene-empty').waitFor();
      await tick(3500);
      assert.equal(await page.getByTestId('scene-editor').count(), 0);
      assert.equal(await page.getByTestId('process-diagram').count(), 0);
      assert.doesNotMatch(
        await root().innerText(),
        /不可泄露的旧租户草稿|自动化夹具设备/,
      );
      await control();
      await page.locator('#tenant-one').click();
      await ready('gas');
    },
  );

  await check(
    'Mobile production layout is readable, internally pannable, with fixture-labelled evidence',
    async () => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.getByTestId('production-separation').click();
      await ready('separation');
      const pan = page.getByTestId('production-diagram-scroll');
      assert.equal(
        await pan.evaluate(
          (node) =>
            node.scrollWidth > node.clientWidth &&
            ['auto', 'scroll'].includes(getComputedStyle(node).overflowX),
        ),
        true,
      );
      await page.getByTestId('production-node').selectOption('slot-4');
      await until(async () => {
        const area = await pan.boundingBox(),
          equipment = await page.getByTestId('equipment-4').boundingBox();
        return (
          area &&
          equipment &&
          equipment.x + equipment.width / 2 >= area.x &&
          equipment.x + equipment.width / 2 <= area.x + area.width
        );
      }, 'mobile selected equipment brought into scroll viewport');
      await page.getByTestId('production-node').selectOption('slot-1');
      await ready('separation');
      await capture('separation', 'mobile');
      await page.setViewportSize({ width: 1600, height: 1100 });
    },
  );

  await check(
    'Unmount aborts requests, stops polling beyond two intervals, and remounts cleanly',
    async () => {
      await control('normal', 3000);
      await page.getByTestId('production-metric').selectOption('temperature');
      await page.locator('#unmount').click();
      await page
        .getByTestId('harness-status')
        .filter({ hasText: /^未挂载$/ })
        .waitFor();
      await until(
        async () =>
          (await page.getByTestId('harness-pending').innerText()) === '0',
        'all harness requests cancel',
      );
      const count = requests.length;
      await tick(10_500);
      assert.equal(
        requests.length,
        count,
        'No background API polling after child unmount',
      );
      assert.equal(await page.locator('.dc-shell').count(), 0);
      await control();
      await page.locator('#remount').click();
      await ready('separation');
    },
  );

  await check(
    'Explicit source selection creates and persists the intended device binding; equal names remain distinguishable',
    async () => {
      await page.getByRole('link', { name: '设备与测点', exact: true }).click();
      const selector = page.getByTestId('iot-new-device-source');
      await selector.waitFor();
      await until(async () => !(await selector.isDisabled()), 'tenant source catalog loaded');
      assert.equal(await selector.inputValue(), '', 'Never auto-select the first configured source');
      assert.equal(await page.getByTestId('iot-device-create').isDisabled(), true);
      assert.equal(await selector.locator('option[value="source-disabled"]').isDisabled(), true);
      for (const [device, source] of [[devices[0], 'source-a'], [peer, 'source-b']]) {
        const row = page.locator(`.iot-device-row[data-device-id="${device.id}"]`);
        assert.ok((await row.innerText()).includes(device.name));
        assert.ok((await row.innerText()).includes(`[${source}]`));
      }
      await page.getByLabel('设备名称', { exact: true }).fill('浏览器独立源设备（非现场）');
      await page.getByLabel('设备编码', { exact: true }).fill('browser_source_b_fixture');
      await selector.selectOption('source-b');
      await page.getByTestId('iot-device-create').click();
      let created;
      await until(async () => {
        created = (await api('/devices')).find((device) => device.code === 'browser_source_b_fixture');
        return !!created;
      }, 'browser-created metadata persisted');
      assert.equal(created.sourceId, 'source-b');
      await until(async () => (await page.getByTestId('iot-current-source').innerText()).includes('[source-b]'), 'created device identity');
      await page.reload();
      await page.locator(`.iot-device-row[data-device-id="${created.id}"]`).click();
      assert.match(await page.getByTestId('iot-current-source').innerText(), /source-b/);
      assert.equal(await selector.inputValue(), '');
      await page.getByRole('link', { name: '生产工艺', exact: true }).click();
      await ready('separation');
    },
  );

  await check(
    'Same-name cross-source process bindings persist and display different values with source identity',
    async () => {
      await page.getByTestId('scene-edit').click();
      const options = page.getByTestId('slot-2-device');
      for (const [device, source] of [[devices[0], 'source-a'], [peer, 'source-b']]) {
        assert.ok((await options.locator(`option[value="${device.id}"]`).innerText()).includes(`[${source}]`));
      }
      await options.selectOption(String(peer.id));
      await page.getByTestId('slot-2-metrics').selectOption(['pressure', 'temperature']);
      await page.getByTestId('slot-2-primary').selectOption('pressure');
      await page.getByTestId('slot-3-device').selectOption(String(unavailable.id));
      await page.getByTestId('slot-3-metrics').selectOption(['pressure']);
      await page.getByTestId('slot-3-primary').selectOption('pressure');
      await page.getByTestId('scene-save').click();
      await page.getByTestId('scene-editor').waitFor({ state: 'detached' });
      await ready('separation');
      const saved = (await api('/process-pages')).find((item) => item.template === 'separation');
      assert.equal(saved.bindings[1].deviceId, peer.id);
      assert.equal(saved.bindings[2].deviceId, unavailable.id);
      await page.reload();
      await ready('separation');
      await page.getByTestId('production-node').selectOption('slot-2');
      await ready('separation', 1010.25, 'source-b');
      assert.match(await page.getByTestId('production-device-source').innerText(), /source-b/);
      assert.match(await page.getByTestId('production-trend').innerText(), /source-b/);
      await capture('cross-source', 'desktop');
    },
  );

  await check(
    'A disabled or failed process source never masks a healthy source or substitutes a same-named point',
    async () => {
      await page.getByTestId('production-node').selectOption('slot-3');
      await until(async () => (await point().getAttribute('data-status')) === 'failed', 'disabled source is visibly unavailable');
      assert.equal(await page.getByTestId('production-live').getAttribute('data-source-id'), 'source-disabled');
      assert.match(await page.getByTestId('production-live').innerText(), /未启用/);
      assert.equal((await point().locator('strong').innerText()).trim(), '—');
      await page.getByTestId('production-node').selectOption('slot-1');
      await ready('separation');
      await control('failure', 0, 'source-b');
      await page.getByTestId('production-node').selectOption('slot-2');
      await page.getByTestId('production-live').getByRole('button', { name: '立即读取', exact: true }).click();
      await until(async () => (await point().getAttribute('data-status')) === 'failed', 'source B outage');
      assert.doesNotMatch(await point().locator('strong').innerText(), /10\.25/);
      await page.getByTestId('production-node').selectOption('slot-1');
      await ready('separation');
      await control('normal', 0, 'source-b');
      await page.getByTestId('production-node').selectOption('slot-2');
      await page.getByTestId('production-live').getByRole('button', { name: '立即读取', exact: true }).click();
      await ready('separation', 1010.25, 'source-b');
    },
  );

  await check(
    'Cross-source history retains both identities and healthy rows during a second-source outage',
    async () => {
      await page.getByRole('link', { name: '历史查询', exact: true }).click();
      const selector = page.getByLabel('历史设备集合', { exact: true });
      await selector.waitFor();
      await selector.selectOption([String(devices[0].id), String(peer.id)]);
      await page.getByLabel('历史指标', { exact: true }).selectOption('pressure');
      await page.getByTestId('iot-history-run').click();
      const results = page.getByTestId('iot-history-results');
      await until(async () => (await results.locator('tbody tr').count()) > 10, 'real cross-source history rows');
      assert.match(await results.innerText(), /source-a/);
      assert.match(await results.innerText(), /source-b/);
      assert.match(await results.innerText(), /1,010\.25/);
      assert.equal(await results.locator('.iot-chart-series').count(), 2);
      await control('failure', 0, 'source-b');
      await page.getByTestId('iot-history-run').click();
      await page.getByTestId('iot-history-errors').waitFor();
      assert.match(await page.getByTestId('iot-history-errors').innerText(), /source-b/);
      assert.match(await results.innerText(), /source-a/);
      assert.doesNotMatch(await results.innerText(), /source-b|1,010\.25/);
      assert.equal(await results.locator('.iot-chart-series').count(), 1);
      await control('normal', 0, 'source-b');
      await page.getByTestId('iot-history-run').click();
      await until(async () => (await results.locator('.iot-chart-series').count()) === 2, 'both independent sources recover');
      assert.equal(await page.getByTestId('iot-history-errors').count(), 0);
      await page.getByTestId('contract-fixture-banner').waitFor();
      const filename = 'production-contract-cross-source-history.png';
      await page.screenshot({ path: join(directory, filename), fullPage: true, animations: 'disabled' });
      captures.push({ filename, commit, evidenceType: 'AUTOMATED_CONTRACT_FIXTURE', screenshotIncludesFixtureBanner: true });
    },
  );

  await check(
    'Browser cross-source export shows frozen source summary and downloads source-labelled CSV',
    async () => {
      const previous = new Set((await api('/exports')).map((job) => job.id));
      await page.getByTestId('iot-export').click();
      let job;
      await until(async () => {
        job = (await api('/exports')).find((entry) => !previous.has(entry.id));
        return !!job;
      }, 'cross-source browser export persisted');
      assert.deepEqual([...job.sourceIds].sort(), ['source-a', 'source-b']);
      await until(async () => {
        job = await api(`/exports/${job.id}`);
        assert.notEqual(job.status, 'FAILED', job.error);
        return job.status === 'SUCCEEDED';
      }, 'cross-source browser export completed', 30_000);
      await page.getByRole('link', { name: '下载任务', exact: true }).first().click();
      const row = page.getByTestId(`iot-export-${job.id}`);
      await row.waitFor();
      assert.match(await row.getByTestId('iot-export-sources').innerText(), /source-a/);
      assert.match(await row.getByTestId('iot-export-sources').innerText(), /source-b/);
      const downloaded = page.waitForEvent('download');
      await row.getByRole('button', { name: '下载 CSV', exact: true }).click();
      const filename = join(directory, 'production-contract-cross-source-history.csv');
      await (await downloaded).saveAs(filename);
      const csv = await readFile(filename, 'utf8');
      assert.match(csv, /^\uFEFF?sourceId,deviceId,metricKey,unit,time,value,quality/);
      assert.ok(csv.includes(`"source-a","${devices[0].id}"`));
      assert.ok(csv.includes(`"source-b","${peer.id}"`));
      assert.match(csv, /"10\.25"/);
      assert.match(csv, /"1010\.25"/);
    },
  );

  await check(
    'Cross-source history cancellation, tenant replacement and remount discard late values and source selections',
    async () => {
      const configure = async () => {
        await page.getByRole('link', { name: '历史查询', exact: true }).click();
        await page.getByLabel('历史设备集合', { exact: true }).selectOption([String(devices[0].id), String(peer.id)]);
        await page.getByLabel('历史指标', { exact: true }).selectOption('pressure');
      };
      await configure();
      await control('normal', 0, 'source-b', 2000);
      await page.getByTestId('iot-history-run').click();
      await page.getByTestId('iot-history-cancel').click();
      await tick(2300);
      assert.equal(await page.getByTestId('iot-history-results').locator('tbody tr').count(), 0);
      await page.getByTestId('iot-history-run').click();
      await page.locator('#tenant-two').click();
      await until(async () => !(await page.getByLabel('历史设备集合', { exact: true }).innerText()).includes('source-a'), 'old tenant options cleared');
      await tick(2300);
      assert.equal(await page.getByTestId('iot-history-results').locator('tbody tr').count(), 0);
      assert.doesNotMatch(await page.getByLabel('历史设备集合', { exact: true }).innerText(), /source-a|自动化夹具设备/);
      await control('normal', 0, 'source-b');
      await page.locator('#tenant-one').click();
      await until(async () => (await page.getByLabel('历史设备集合', { exact: true }).locator('option').count()) >= devices.length, 'original tenant devices restored');
      await configure();
      await control('normal', 0, 'source-b', 2000);
      await page.getByTestId('iot-history-run').click();
      await page.locator('#unmount').click();
      await page.getByTestId('harness-status').filter({ hasText: /^未挂载$/ }).waitFor();
      await until(async () => (await page.getByTestId('harness-pending').innerText()) === '0', 'multi-source pending requests aborted');
      await control('normal', 0, 'source-b');
      await page.locator('#remount').click();
      await page.getByTestId('harness-status').filter({ hasText: /^已挂载$/ }).waitFor();
      await page.getByTestId('iot-history-run').waitFor();
      await tick(2300);
      assert.equal(await page.getByTestId('iot-history-results').locator('tbody tr').count(), 0);
      await page.getByRole('link', { name: '生产工艺', exact: true }).click();
      await ready('separation');
    },
  );

  await check(
    'Six same-source widgets dispatch bounded reads and keep polling while another source remains pending',
    async () => {
      // Persist genuine platform metadata. All responses still come from the
      // packaged service: routes below only hold transport, never fulfill data.
      const aIds = Array.from({ length: 6 }, (_, index) => `scheduler-a-${index + 1}`);
      const widget = (id, deviceId, index) => ({
        id, title: `TEST FIXTURE ${id}`, type: 'table',
        binding: { mode: 'fixed', deviceIds: [deviceId], metricKey: 'pressure' },
        grid: { x: 0, y: index * 3, w: 12, h: 3 },
        unit: 'MPa', timeWindowSeconds: 300, aggregation: 'RAW',
      });
      const board = await api('/dashboards', 'POST', {
        id: 0, name: 'TEST FIXTURE 多源并发与轮询（非现场）', version: 0, schemaVersion: 1,
        widgets: [...aIds.map((id, index) => widget(id, devices[0].id, index)), widget('scheduler-b', peer.id, 6)],
      });
      const matcher = '**/admin-api/iot/query/realtime';
      let releaseA, releaseB, aReleased = false, bReleased = false, cleaningUp = false;
      let heldA = 0, heldB = 0, heldBSince = 0;
      const aGate = new Promise((resolve) => { releaseA = resolve; });
      const bGate = new Promise((resolve) => { releaseB = resolve; });
      const starts = new WeakMap();
      const aResponses = [], transportErrors = [];
      const holdQueries = async (route) => {
        const request = route.request();
        const query = request.postDataJSON();
        starts.set(request, Date.now());
        if (query.deviceIds?.includes(peer.id)) {
          assert.deepEqual(query.deviceIds, [peer.id], 'B must be scheduled independently, not grouped behind A');
          heldB++;
          heldBSince ||= Date.now();
          await bGate;
        } else if (query.deviceIds?.includes(devices[0].id) && !aReleased) {
          heldA++;
          await aGate;
        }
        try {
          await route.continue();
        } catch (error) {
          if (!cleaningUp) transportErrors.push(String(error));
        }
      };
      const observe = async (response) => {
        const request = response.request();
        if (!response.url().endsWith('/admin-api/iot/query/realtime') || !starts.has(request)) return;
        const query = request.postDataJSON();
        if (query.deviceIds?.length !== 1 || query.deviceIds[0] !== devices[0].id) return;
        try {
          const result = await response.json();
          assert.equal(result.code, 0);
          assert.equal(result.data.series[0].sourceId, 'source-a');
          assert.equal(result.data.series[0].points[0].value, 10.25);
          aResponses.push({ startedAt: starts.get(request), pointTime: result.data.series[0].points[0].time });
        } catch (error) {
          if (!cleaningUp) transportErrors.push(String(error));
        }
      };
      // Leave the preceding live process page before installing gates, so a
      // last process poll cannot contaminate the dashboard-only lane counts.
      await page.getByRole('link', { name: '设备与测点', exact: true }).click();
      await page.getByTestId('iot-new-device-source').waitFor();
      await until(async () => (await page.getByTestId('harness-pending').innerText()) === '0', 'non-query workbench catalog is ready and process requests stopped');
      await page.route(matcher, holdQueries);
      page.on('response', observe);
      try {
        await page.getByRole('link', { name: '设备看板', exact: true }).click();
        await until(async () => heldA >= 2 && heldB >= 1, 'independent A and B source lanes start');
        // With six same-source widgets, only the two active lane slots may
        // reach transport. The remaining four must wait in the UI scheduler.
        await tick(300);
        assert.equal(heldA, 2, 'No source pool/queue overload from a six-widget dashboard');
        assert.equal(heldB, 1);
        aReleased = true;
        releaseA();
        for (const id of aIds) {
          const cell = page.getByTestId(`iot-widget-${id}`).locator('tbody tr').first();
          await cell.waitFor();
          assert.match(await cell.innerText(), /source-a/);
          assert.equal((await cell.locator('td').nth(2).innerText()).trim(), '10.25');
          assert.doesNotMatch(await page.getByTestId(`iot-widget-${id}`).innerText(), /SOURCE_BUSY|并发请求已满/);
        }
        await until(async () => aResponses.length >= 6, 'all six actual A reads completed');
        const firstPollLatest = Math.max(...aResponses.slice(0, aIds.length).map((item) => Date.parse(item.pointTime)));
        // Keep B unresolved beyond the declared ten-second poll interval. A
        // must complete a new upstream read, not merely retain its old values.
        await until(async () => aResponses.some((item) =>
          item.startedAt >= heldBSince + 10_000 && Date.parse(item.pointTime) > firstPollLatest),
        'healthy A independently polls again while B transport remains unresolved');
        for (const id of aIds) {
          const row = page.getByTestId(`iot-widget-${id}`).locator('tbody tr').first();
          await row.waitFor();
          assert.equal((await row.locator('td').nth(2).innerText()).trim(), '10.25');
        }
        assert.equal(bReleased, false);
        assert.equal(heldB, 1, 'A polling must not cancel/restart B or duplicate B work');
        assert.ok(Number(await page.getByTestId('harness-pending').innerText()) >= 1);
        assert.equal(await page.getByTestId('iot-widget-scheduler-b').locator('tbody tr').count(), 0);
        assert.ok(Date.now() - heldBSince >= 10_000);
        await page.getByTestId('contract-fixture-banner').waitFor();
        const filename = 'production-contract-source-scheduler.png';
        await page.screenshot({ path: join(directory, filename), fullPage: true, animations: 'disabled' });
        captures.push({ filename, commit, evidenceType: 'AUTOMATED_CONTRACT_FIXTURE', screenshotIncludesFixtureBanner: true });
        const heldForMs = Date.now() - heldBSince;
        bReleased = true;
        releaseB();
        const bRow = page.getByTestId('iot-widget-scheduler-b').locator('tbody tr').first();
        await bRow.waitFor();
        assert.match(await bRow.innerText(), /source-b/);
        assert.equal((await bRow.locator('td').nth(2).innerText()).trim(), '1,010.25');
        assert.deepEqual(transportErrors, []);
        await writeFile(join(directory, 'source-scheduler-audit.json'), JSON.stringify({
          commit, evidenceType: 'AUTOMATED_CONTRACT_FIXTURE', sameSourceWidgets: aIds.length,
          initialSameSourceTransportConcurrency: heldA, blockedSource: 'source-b', heldForMs,
          healthySource: 'source-a', actualReadResponses: aResponses,
          healthyPolledWhileOtherPending: true, blockedSourceRecovered: true,
          note: 'Browser transport delay only; all values/responses from actual packaged API and loopback vendor peers. Not site acceptance.',
        }, null, 2) + '\n');
        await page.locator('#unmount').click();
        await page.getByTestId('harness-status').filter({ hasText: /^未挂载$/ }).waitFor();
        await until(async () => (await page.getByTestId('harness-pending').innerText()) === '0', 'all source scheduler requests stop on unmount');
        const stopped = requests.length;
        await tick(10_500);
        assert.equal(requests.length, stopped, 'No independent source polling after unmount, beyond the dashboard interval');
      } finally {
        cleaningUp = true;
        aReleased = bReleased = true;
        releaseA(); releaseB();
        await page.unroute(matcher, holdQueries);
        page.off('response', observe);
        await api(`/dashboards/${board.id}?version=${board.version}`, 'DELETE');
      }
      await page.locator('#remount').click();
      await page.getByTestId('harness-status').filter({ hasText: /^已挂载$/ }).waitFor();
      await page.getByRole('link', { name: '生产工艺', exact: true }).click();
      await ready('separation');
    },
  );

  assert.ok(checks.length >= 24, 'Retain all seventeen original production browser checks');
  assert.deepEqual(
    errors,
    [],
    'No uncaught page errors, including cancellation',
  );
  assert.ok(
    dialogs.every((dialog) => dialog.action !== 'UNEXPECTED_DISMISSED'),
    'All dialogs must be expected and handled',
  );
  assert.ok(
    requests.length > 20 &&
      requests.every(
        (request) =>
          request.origin === 'http://127.0.0.1:5176' &&
          request.path.startsWith('/admin-api/iot/'),
      ),
    'Child uses the bounded bridge, not direct vendor HTTP',
  );
  const upstream = {};
  for (const [source, port] of [['source-a', 48084], ['source-b', 48085]]) {
    upstream[source] = await (await fetch(`http://127.0.0.1:${port}/__fixture/state`)).json();
    const vendor = upstream[source].requests.filter((request) => request.path.startsWith('/api/'));
    assert.ok(vendor.length > 0 && vendor.every((request) =>
      request.method === 'GET' && request.authenticated && request.sourceId === source),
    'Both independent peers receive only their own authenticated reads; no device mutations');
  }
  await writeFile(
    join(directory, 'upstream-contract-audit.json'),
    JSON.stringify(upstream, null, 2) + '\n',
  );
} catch (error) {
  failure = error;
  await page
    .screenshot({
      path: join(directory, 'production-contract-failure.png'),
      fullPage: true,
    })
    .catch(() => {});
  throw error;
} finally {
  await control().catch(() => {});
  await control('normal', 0, 'source-b').catch(() => {});
  await writeFile(
    join(directory, 'production-browser-verification.json'),
    JSON.stringify(
      {
        capturedAt: new Date().toISOString(),
        commit,
        browser: browser.version(),
        passed: !failure,
        checks,
        captures,
        requests,
        pageErrors: errors,
        dialogs,
        failure: failure ? String(failure.stack || failure) : null,
        evidenceType: 'AUTOMATED_CONTRACT_FIXTURE',
        note: '真实生产子应用 + 正式同源 bridge factory + 真实鉴权/租户/API/PostgreSQL/RealDbClient；系统 RPC 与紫金桥上游为测试夹具。不是正式登录/网关联调，更不是紫金桥现场验收。',
      },
      null,
      2,
    ) + '\n',
  );
  await context.tracing.stop({
    path: join(directory, 'production-contract-trace.zip'),
  });
  await browser.close();
}
