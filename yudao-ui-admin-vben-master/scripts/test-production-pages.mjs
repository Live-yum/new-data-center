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
const { devices } = JSON.parse(
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
async function control(mode = 'normal', delayMs = 0) {
  const response = await fetch('http://127.0.0.1:48084/__fixture/control', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode, delayMs }),
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
async function ready(scene) {
  await mounted(scene);
  await page.getByTestId('production-trend').locator('svg').first().waitFor();
  await until(
    async () => (await point().getAttribute('data-status')) === 'fresh',
    'REALDB device-time fresh reading',
  );
  assert.match(await point().innerText(), /10\.25/);
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
    apiDataSource: 'REALDB adapter with test-only upstream',
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
  const upstream = await (
    await fetch('http://127.0.0.1:48084/__fixture/state')
  ).json();
  const vendor = upstream.requests.filter((request) =>
    request.path.startsWith('/api/'),
  );
  assert.ok(
    vendor.length > 0 &&
      vendor.every(
        (request) => request.method === 'GET' && request.authenticated,
      ),
    'Only authenticated vendor reads; no device mutations',
  );
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
