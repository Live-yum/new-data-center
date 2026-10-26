/** Real production-build / qiankun regression. Data is explicitly demo-only. */
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

import { chromium } from 'playwright';

const directory = resolve(
  process.env.MICRO_DEMO_SCREENSHOT_DIR || 'test-results/iot',
);
await mkdir(directory, { recursive: true });
const url = new URL(process.env.MICRO_DEMO_URL || 'http://127.0.0.1:5174/');
// The demo host deliberately uses hash history; a pathname query is not a Vue route.
url.hash = '/data-center?view=dashboards';
const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined,
  headless: true,
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 1100 },
  locale: 'zh-CN',
});
await context.tracing.start({
  screenshots: true,
  snapshots: true,
  sources: true,
});
const errors = [];
const checkpoints = [];
const captures = [];
let failure;
const page = await context.newPage();
page.setDefaultTimeout(15_000);
context.on('page', (target) =>
  target.on('pageerror', (error) => errors.push(error.message)),
);
page.on('pageerror', (error) => errors.push(error.message));

async function until(check, message, timeout = 15_000) {
  const end = Date.now() + timeout;
  while (!(await check())) {
    if (Date.now() >= end) throw new Error(`Timeout: ${message}`);
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}
async function mounted(target = page) {
  await target.getByTestId('status').filter({ hasText: '已挂载' }).waitFor();
  await target.getByTestId('iot-device-select').waitFor();
  await target.getByTestId('iot-widget-demo-0').waitFor();
  assert.equal(await target.locator('.dc-shell').count(), 1);
}
async function idle(target = page) {
  await target.getByTestId('pending').filter({ hasText: /^0$/ }).waitFor();
}
async function checkpoint(name, test) {
  await test();
  checkpoints.push(name);
  console.log(`PASS ${name}`);
}
async function navigate(path) {
  await page.locator('.dc-global-nav').locator(`a[href$="${path}"]`).click();
  await page.waitForURL(new RegExp(`view=${path.slice(1)}`));
}
async function pointerMove(handle, dx, dy) {
  await handle.scrollIntoViewIfNeeded();
  const box = await handle.boundingBox();
  assert.ok(box, 'Drag / resize handle must actually be rendered');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(
    box.x + box.width / 2 + dx,
    box.y + box.height / 2 + dy,
    { steps: 12 },
  );
  await page.mouse.up();
}

try {
  await page.goto(url.toString());
  await mounted();
  await idle();
  // The remaining checks intentionally use only user-visible controls and rendered
  // attributes, never inject localStorage fixtures or call implementation internals.
  await checkpoint('七类真实组件、当前设备模板切换和缺测提示', async () => {
    assert.deepEqual(
      (
        await page
          .locator('[data-widget-type]')
          .evaluateAll((nodes) =>
            nodes.map((n) => n.getAttribute('data-widget-type')),
          )
      ).sort(),
      ['bar', 'gauge', 'image', 'line', 'stat', 'table', 'timeline'],
    );
    const current = page.getByTestId('iot-widget-demo-0');
    const multiple = page.getByTestId('iot-widget-demo-5');
    await current.locator('svg[role="img"]').waitFor();
    for (const id of ['2', '3', '1']) {
      await page.getByTestId('iot-device-select').selectOption(id);
      await until(
        async () => (await current.getAttribute('data-device-ids')) === id,
        'current device binding',
      );
      assert.equal(await multiple.getAttribute('data-device-ids'), '1,2');
      if (id === '3') {
        await current
          .locator('.iot-warning')
          .filter({ hasText: '缺少' })
          .waitFor();
        assert.equal(
          await current.locator('svg[role="img"]').count(),
          0,
          'Missing mappings must not render a fabricated zero series',
        );
      } else {
        await current.locator('svg[role="img"]').first().waitFor();
        assert.match(await current.innerText(), new RegExp(`锅炉 ${id}`));
      }
    }
  });

  await checkpoint(
    '固定/多设备绑定、组件编辑、真实指针拖拽缩放、保存和刷新恢复',
    async () => {
      await page.getByTestId('iot-edit').click();
      const widget = page.getByTestId('iot-widget-demo-0');
      await widget.getByRole('button', { name: '配置', exact: true }).click();
      await page.getByTestId('iot-binding-title').fill('浏览器验证 · 固定温度');
      await page.getByTestId('iot-binding-mode').selectOption('fixed');
      await page.getByTestId('iot-binding-devices').selectOption('2');
      await page.getByTestId('iot-binding-save').click();
      await page.getByTestId('iot-device-select').selectOption('3');
      assert.equal(await widget.getAttribute('data-binding-mode'), 'fixed');
      assert.equal(await widget.getAttribute('data-device-ids'), '2');
      await widget.locator('svg[role="img"]').waitFor();
      assert.match(await widget.innerText(), /锅炉 2/);
      await page.getByTestId('iot-device-select').selectOption('1');
      // Move into an empty grid area first: overlapping cards must not intercept the pointer handle.
      for (let row = 0; row < 12; row++)
        await page.getByTestId('iot-drag-demo-0').press('ArrowDown');
      const before = JSON.parse(await widget.getAttribute('data-grid'));
      await pointerMove(page.getByTestId('iot-drag-demo-0'), 100, 70);
      const moved = JSON.parse(await widget.getAttribute('data-grid'));
      assert.ok(
        moved.x > before.x || moved.y > before.y,
        'Pointer drag must change real grid coordinates',
      );
      await pointerMove(page.getByTestId('iot-resize-demo-0'), 100, 70);
      const resized = await widget.getAttribute('data-grid');
      assert.ok(
        JSON.parse(resized).w > moved.w || JSON.parse(resized).h > moved.h,
        'Pointer resize must change grid dimensions',
      );
      await page.getByTestId('iot-save').click();
      await page
        .getByRole('status')
        .filter({ hasText: '已保存，可刷新恢复' })
        .waitFor();
      await page.reload();
      await mounted();
      assert.equal(
        await page.getByTestId('iot-widget-demo-0').getAttribute('data-grid'),
        resized,
      );
      assert.equal(
        await page
          .getByTestId('iot-widget-demo-0')
          .getAttribute('data-binding-mode'),
        'fixed',
      );
      assert.equal(
        await page
          .getByTestId('iot-widget-demo-0')
          .getAttribute('data-device-ids'),
        '2',
      );
      await page.getByTestId('iot-edit').click();
      await page
        .getByTestId('iot-widget-demo-0')
        .getByRole('button', { name: '配置', exact: true })
        .click();
      assert.equal(
        await page.getByTestId('iot-binding-title').inputValue(),
        '浏览器验证 · 固定温度',
      );
      await page
        .getByRole('dialog', { name: '组件数据绑定' })
        .getByRole('button', { name: '取消', exact: true })
        .click();
      await page
        .getByTestId('iot-widget-demo-5')
        .getByRole('button', { name: '配置', exact: true })
        .click();
      await page.getByTestId('iot-binding-mode').selectOption('multiple');
      await page.getByTestId('iot-binding-devices').selectOption(['1', '2']);
      await page.getByTestId('iot-binding-save').click();
      assert.equal(
        await page
          .getByTestId('iot-widget-demo-5')
          .getAttribute('data-device-ids'),
        '1,2',
      );
      await page.getByTestId('iot-save').click();
      await page
        .getByRole('status')
        .filter({ hasText: '已保存，可刷新恢复' })
        .waitFor();
    },
  );

  await checkpoint('空白看板和组件创建、取消编辑、删除与模板副本', async () => {
    await page.getByTestId('iot-new').click();
    await page.getByLabel('看板名称', { exact: true }).fill('浏览器临时看板');
    await page.getByTestId('iot-gallery').click();
    await page.getByTestId('iot-add-stat').click();
    await page.getByTestId('iot-binding-title').fill('新数值组件');
    await page.getByTestId('iot-binding-save').click();
    assert.equal(await page.locator('[data-widget-type="stat"]').count(), 1);
    await page.getByTestId('iot-save').click();
    await page
      .getByRole('status')
      .filter({ hasText: '已保存，可刷新恢复' })
      .waitFor();
    const id = await page.getByTestId('iot-dashboard-select').inputValue();
    assert.notEqual(id, '0');
    await page.reload();
    await mounted();
    await page.getByTestId('iot-dashboard-select').selectOption(id);
    await until(
      async () => (await page.locator('[data-widget-type]').count()) === 1,
      'saved new board selection',
    );
    await page.getByTestId('iot-edit').click();
    await page
      .locator('[data-widget-type="stat"]')
      .getByRole('button', { name: '配置', exact: true })
      .click();
    assert.equal(
      await page.getByTestId('iot-binding-title').inputValue(),
      '新数值组件',
    );
    await page.getByTestId('iot-binding-title').fill('不得保存的取消修改');
    await page
      .getByRole('dialog', { name: '组件数据绑定' })
      .getByRole('button', { name: '取消', exact: true })
      .click();
    await page
      .locator('[data-widget-type="stat"]')
      .getByRole('button', { name: '配置', exact: true })
      .click();
    assert.equal(
      await page.getByTestId('iot-binding-title').inputValue(),
      '新数值组件',
    );
    await page
      .getByRole('dialog', { name: '组件数据绑定' })
      .getByRole('button', { name: '取消', exact: true })
      .click();
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByTestId('iot-delete').click();
    await page.getByTestId('iot-widget-demo-0').waitFor();
    assert.equal(
      await page
        .getByTestId('iot-dashboard-select')
        .locator(`option[value="${id}"]`)
        .count(),
      0,
    );
    await page
      .getByRole('button', { name: '锅炉运行模板 → 创建副本', exact: true })
      .click();
    assert.equal(
      await page.getByLabel('看板名称', { exact: true }).inputValue(),
      '锅炉运行模板 副本',
    );
    assert.equal(await page.locator('[data-widget-type]').count(), 7);
    assert.equal(
      await page
        .getByTestId('iot-widget-demo-0')
        .getAttribute('data-binding-mode'),
      'currentDevice',
    );
    // Reload intentionally discards the unsaved template copy.
    await page.reload();
    await mounted();
  });

  await checkpoint('并发版本冲突保留草稿并显式恢复', async () => {
    const other = await context.newPage();
    other.setDefaultTimeout(15_000);
    try {
      await other.goto(url.toString());
      await mounted(other);
      await other.getByTestId('iot-edit').click();
      await other
        .getByLabel('看板名称', { exact: true })
        .fill('另一个窗口的新版本');
      await page.getByTestId('iot-edit').click();
      await page.getByLabel('看板名称', { exact: true }).fill('先保存的版本');
      await page.getByTestId('iot-save').click();
      await page
        .getByRole('status')
        .filter({ hasText: '已保存，可刷新恢复' })
        .waitFor();
      await other.getByTestId('iot-save').click();
      await other.getByTestId('iot-conflict').waitFor();
      assert.equal(
        await other.getByLabel('看板名称', { exact: true }).inputValue(),
        '另一个窗口的新版本',
      );
      other.once('dialog', (dialog) => dialog.accept());
      await other
        .getByRole('button', { name: '加载最新版本', exact: true })
        .click();
      await other.getByTestId('iot-conflict').waitFor({ state: 'hidden' });
      await other.getByTestId('iot-edit').click();
      assert.equal(
        await other.getByLabel('看板名称', { exact: true }).inputValue(),
        '先保存的版本',
      );
    } finally {
      await other.close();
    }
  });

  await checkpoint('历史查询、多设备结果、取消与重复查询', async () => {
    await navigate('/history');
    await page
      .getByLabel('历史设备集合', { exact: true })
      .selectOption(['1', '2']);
    await page.getByTestId('iot-history-run').click();
    await page.getByTestId('iot-history-cancel').click();
    await idle();
    assert.equal(
      await page.getByTestId('iot-history-results').locator('tbody tr').count(),
      0,
    );
    assert.equal(await page.getByTestId('iot-error').count(), 0);
    for (let index = 0; index < 2; index++) {
      await page.getByTestId('iot-history-run').click();
      await page
        .getByTestId('iot-history-results')
        .locator('tbody tr')
        .first()
        .waitFor();
      const results = await page.getByTestId('iot-history-results').innerText();
      assert.match(results, /锅炉 1/);
      assert.match(results, /锅炉 2/);
    }
  });

  await checkpoint('下载失败/过期不可用、运行中取消和完成CSV下载', async () => {
    await page.getByTestId('iot-export').click();
    await page.waitForURL(/view=downloads/);
    for (const [id, status] of [
      ['demo-failed', 'FAILED'],
      ['demo-expired', 'EXPIRED'],
    ]) {
      const row = page.getByTestId(`iot-export-${id}`);
      await row.filter({ hasText: status }).waitFor();
      assert.equal(
        await row
          .getByRole('button', { name: '下载 CSV', exact: true })
          .isDisabled(),
        true,
      );
    }
    const running = page
      .locator('tr[data-testid^="iot-export-"]')
      .filter({ hasText: 'RUNNING' });
    await running.waitFor();
    const cancelledId = await running.getAttribute('data-testid');
    await running.getByRole('button', { name: '取消', exact: true }).click();
    await page
      .getByTestId(cancelledId)
      .filter({ hasText: 'CANCELLED' })
      .waitFor();
    assert.equal(
      await page
        .getByTestId(cancelledId)
        .getByRole('button', { name: '下载 CSV', exact: true })
        .isDisabled(),
      true,
    );
    await navigate('/history');
    await page.getByTestId('iot-export').click();
    await page.waitForURL(/view=downloads/);
    const pending = page
      .locator('tr[data-testid^="iot-export-"]')
      .filter({ hasText: 'RUNNING' });
    await pending.waitFor();
    const completedId = await pending.getAttribute('data-testid');
    await until(async () => {
      await page.getByRole('button', { name: '刷新任务', exact: true }).click();
      await idle();
      return (await page.getByTestId(completedId).innerText()).includes(
        'SUCCEEDED',
      );
    }, 'export completion');
    const downloadPromise = page.waitForEvent('download');
    await page
      .getByTestId(completedId)
      .getByRole('button', { name: '下载 CSV', exact: true })
      .click();
    const download = await downloadPromise;
    assert.equal(await download.failure(), null);
    assert.match(download.suggestedFilename(), /^iot-export-.*\.csv$/);
    const stream = await download.createReadStream();
    const chunks = [];
    for await (const chunk of stream) chunks.push(chunk);
    const csv = Buffer.concat(chunks).toString('utf8');
    assert.match(csv, /temperature_out/);
    assert.match(csv, /quality/i);
    await download.saveAs(join(directory, 'iot-export.csv'));
  });

  await checkpoint('故障恢复、旧租户隔离、反复会话切换和卸载清理', async () => {
    await navigate('/dashboards');
    await mounted();
    await page.getByLabel('模拟数据请求失败', { exact: true }).check();
    await page
      .getByTestId('iot-error')
      .filter({ hasText: '演示请求失败' })
      .waitFor();
    await page.getByLabel('模拟数据请求失败', { exact: true }).uncheck();
    await mounted();
    for (const tenant of ['2', '1', '2']) {
      await page.getByLabel('演示租户', { exact: true }).selectOption(tenant);
      await page
        .getByTestId('iot-device-select')
        .filter({ hasText: tenant === '1' ? '演示甲' : '演示乙' })
        .waitFor();
      await idle();
      const text = await page.locator('.iot-app').innerText();
      assert.doesNotMatch(text, tenant === '1' ? /演示乙/ : /演示甲/);
    }
    // Deliberately replace sessions before the previous 350 ms demo reads finish.
    for (const tenant of ['1', '2', '1', '2']) {
      await page.getByLabel('演示租户', { exact: true }).selectOption(tenant);
    }
    await page
      .getByTestId('iot-device-select')
      .filter({ hasText: '演示乙' })
      .waitFor();
    await idle();
    assert.doesNotMatch(await page.locator('.iot-app').innerText(), /演示甲/);
    await page.getByTestId('iot-edit').click();
    await page
      .getByTestId('iot-widget-demo-0')
      .getByRole('button', { name: '配置', exact: true })
      .click();
    assert.notEqual(
      await page.getByTestId('iot-binding-title').inputValue(),
      '浏览器验证 · 固定温度',
      'Tenant 2 must not inherit tenant 1 saved dashboard',
    );
    await page
      .getByRole('dialog', { name: '组件数据绑定' })
      .getByRole('button', { name: '取消', exact: true })
      .click();
    await navigate('/history');
    await page.getByTestId('iot-history-run').click();
    await page.getByRole('button', { name: '卸载子应用', exact: true }).click();
    await page.getByTestId('status').filter({ hasText: '未挂载' }).waitFor();
    assert.equal(await page.locator('.iot-app, .dc-shell').count(), 0);
    await idle();
    const lateRequests = await page.evaluate(
      () =>
        new Promise((resolve) => {
          const pending = document.querySelector('[data-testid="pending"]');
          const values = [];
          const observer = new MutationObserver(() => {
            if (pending.textContent.trim() !== '0')
              values.push(pending.textContent);
          });
          observer.observe(pending, {
            childList: true,
            characterData: true,
            subtree: true,
          });
          // Observe a whole refresh period to catch an uncleared 10-second poll timer.
          setTimeout(() => {
            observer.disconnect();
            resolve(values);
          }, 10_500);
        }),
    );
    assert.deepEqual(
      lateRequests,
      [],
      'Unmount must stop polling, not just abort the current request',
    );
    await page
      .getByRole('button', { name: '挂载 / 重试', exact: true })
      .click();
    await page.getByTestId('iot-history-run').waitFor();
    assert.equal(
      await page.getByTestId('iot-history-results').locator('tbody tr').count(),
      0,
    );
    await page
      .getByRole('button', { name: '退出演示会话', exact: true })
      .click();
    await page.getByTestId('status').filter({ hasText: '未挂载' }).waitFor();
    await idle();
    assert.equal(await page.locator('.iot-app').count(), 0);
    await page
      .getByRole('button', { name: '登录演示会话', exact: true })
      .click();
    await page.getByTestId('iot-history-run').waitFor();
    assert.equal(await page.getByTestId('iot-error').count(), 0);
  });

  await checkpoint('真实桌面与移动端 qiankun 截图和无横向溢出', async () => {
    for (const [name, viewport] of [
      ['desktop', { width: 1440, height: 1100 }],
      ['mobile', { width: 390, height: 844 }],
    ]) {
      const captureContext = await browser.newContext({
        viewport,
        locale: 'zh-CN',
        isMobile: name === 'mobile',
        hasTouch: name === 'mobile',
        reducedMotion: 'reduce',
      });
      const target = await captureContext.newPage();
      target.setDefaultTimeout(15_000);
      target.on('pageerror', (error) => errors.push(error.message));
      try {
        await target.goto(url.toString());
        await mounted(target);
        await idle(target);
        assert.equal(await target.locator('[data-widget-type]').count(), 7);
        await target
          .getByTestId('iot-widget-demo-0')
          .locator('svg[role="img"]')
          .waitFor();
        assert.match(
          await target.locator('.dc-topbar').innerText(),
          /演示数据/,
        );
        assert.equal(await target.getByTestId('iot-error').count(), 0);
        await target.evaluate(() => document.fonts.ready);
        assert.equal(
          await target.evaluate(
            () =>
              document.documentElement.scrollWidth <=
              document.documentElement.clientWidth,
          ),
          true,
          `${name}: no horizontal overflow`,
        );
        const filename = `iot-demo-${name}.png`;
        await target.screenshot({
          path: join(directory, filename),
          fullPage: true,
          animations: 'disabled',
        });
        captures.push({
          filename,
          viewport,
          url: target.url(),
          widgets: 7,
          source: 'demo',
        });
      } finally {
        await captureContext.close();
      }
    }
  });
  assert.deepEqual(
    errors,
    [],
    'No uncaught browser errors, including after canceled requests',
  );
} catch (error) {
  failure = error;
  await page
    .screenshot({ path: join(directory, 'iot-failure.png'), fullPage: true })
    .catch(() => {});
  throw error;
} finally {
  await writeFile(
    join(directory, 'iot-verification.json'),
    JSON.stringify(
      {
        capturedAt: new Date().toISOString(),
        commit: process.env.GITHUB_SHA || null,
        browser: browser.version(),
        passed: !failure,
        checkpoints,
        captures,
        pageErrors: errors,
        failure: failure ? String(failure.stack || failure) : null,
        note: '真实生产构建 qiankun 浏览器回归；演示 bridge，不声称连接认证业务服务或实时库。独立微服务/数据库由另一个 CI job 验证。',
      },
      null,
      2,
    ) + '\n',
  );
  await context.tracing.stop({ path: join(directory, 'iot-trace.zip') });
  await browser.close();
}
