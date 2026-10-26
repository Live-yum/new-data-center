import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

import { chromium } from 'playwright';

const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined,
  headless: true,
});
const page = await browser.newPage();
page.setDefaultTimeout(15_000);
const errors = [];
page.on('pageerror', (error) => errors.push(error.message));
const url = process.env.MICRO_DEMO_URL || 'http://127.0.0.1:5174/';
const screenshotDirectory = process.env.MICRO_DEMO_SCREENSHOT_DIR;

async function mounted(target = page) {
  await target.getByTestId('status').filter({ hasText: '已挂载' }).waitFor();
  await target.locator('.dc-toolbar p').filter({ hasText: '更新于' }).waitFor();
  assert.equal(await target.locator('.data-center-app').count(), 1);
}

async function captureScreenshots() {
  if (!screenshotDirectory) return;
  const directory = resolve(screenshotDirectory);
  await mkdir(directory, { recursive: true });
  const captures = [];
  for (const [name, viewport] of [
    ['desktop', { width: 1440, height: 1100 }],
    ['mobile', { width: 390, height: 844 }],
  ]) {
    const target = await browser.newPage({
      viewport,
      deviceScaleFactor: 1,
      isMobile: name === 'mobile',
      hasTouch: name === 'mobile',
      locale: 'zh-CN',
      reducedMotion: 'reduce',
    });
    target.setDefaultTimeout(15_000);
    target.on('pageerror', (error) => errors.push(error.message));
    try {
      await target.goto(url);
      await mounted(target);
      await target.getByTestId('pending').filter({ hasText: /^0$/ }).waitFor();
      assert.equal(await target.getByTestId('route').innerText(), '/overview');
      assert.equal(
        await target
          .getByTestId('micro-container')
          .locator('.data-center-app')
          .count(),
        1,
        `${name}: 截图必须包含真实挂载的子应用`,
      );
      assert.equal(await target.locator('.dc-status').innerText(), '演示数据');
      assert.deepEqual(
        await target.locator('.dc-metrics strong').allTextContents(),
        ['2', '2', '3'],
      );
      assert.equal(await target.locator('.demo-error, .dc-error').count(), 0);
      await target.evaluate(() => document.fonts.ready);
      assert.equal(
        await target.evaluate(
          () =>
            document.documentElement.scrollWidth <=
            document.documentElement.clientWidth,
        ),
        true,
        `${name}: 页面不应出现横向溢出`,
      );
      const filename = `micro-demo-${name}.png`;
      await target.screenshot({
        path: join(directory, filename),
        fullPage: true,
        animations: 'disabled',
      });
      captures.push({
        filename,
        viewport,
        url: target.url(),
        entry: await target.locator('.demo-entry code').innerText(),
        status: await target.getByTestId('status').innerText(),
        dataSource: 'demo',
      });
    } finally {
      await target.close();
    }
  }
  assert.deepEqual(errors, []);
  await writeFile(
    join(directory, 'verification.json'),
    `${JSON.stringify(
      {
        capturedAt: new Date().toISOString(),
        commit: process.env.GITHUB_SHA || null,
        browser: browser.version(),
        pageErrors: errors,
        captures,
        note: '真实 qiankun 挂载截图；宿主演示数据，未连接业务或实时数据库。',
      },
      null,
      2,
    )}\n`,
  );
  console.log(`真实桌面 / 移动端截图及验证记录：${directory}`);
}

try {
  await page.goto(url);
  await mounted();
  assert.match(await page.locator('.dc-status').innerText(), /演示数据/);
  assert.equal(
    await page
      .locator('.data-center-app')
      .evaluate((element) => element.getRootNode() instanceof ShadowRoot),
    false,
  );
  const scoped = await page.evaluate(() =>
    [...document.styleSheets].some((sheet) => {
      try {
        return [...sheet.cssRules].some((rule) =>
          rule.cssText.startsWith('@scope'),
        );
      } catch {
        return false;
      }
    }),
  );
  assert.equal(scoped, true, 'qiankun 3 应通过 CSS @scope 隔离实际子应用样式');

  await page.getByRole('link', { name: '宿主打开部门', exact: true }).click();
  await page
    .locator('.data-center-app tbody')
    .filter({ hasText: '演示甲 · 运行部' })
    .waitFor();
  await page
    .locator('.dc-nav')
    .getByRole('link', { name: '数据字典', exact: true })
    .click();
  await page.waitForURL(/view=dictionaries/);
  await page.goBack();
  await page.waitForURL(/view=departments/);
  await page.reload();
  await mounted();
  assert.equal(await page.getByTestId('route').innerText(), '/departments');

  await page.getByLabel('演示租户', { exact: true }).selectOption('2');
  await page
    .locator('.data-center-app tbody')
    .filter({ hasText: '演示乙 · 运行部' })
    .waitFor();
  assert.doesNotMatch(
    await page.locator('.data-center-app tbody').innerText(),
    /演示甲/,
  );
  await page.getByLabel('演示用户', { exact: true }).selectOption('演示用户乙');
  await page.locator('.dc-session').filter({ hasText: '演示用户乙' }).waitFor();
  await page.getByLabel('模拟数据请求失败', { exact: true }).check();
  await page.locator('.dc-error').filter({ hasText: '演示请求失败' }).waitFor();
  await page.getByLabel('模拟数据请求失败', { exact: true }).uncheck();
  await mounted();
  assert.equal(await page.locator('.dc-error').count(), 0);

  await page.getByRole('button', { name: '刷新数据', exact: true }).click();
  await page.getByTestId('pending').filter({ hasText: /^2$/ }).waitFor();
  await page.getByRole('button', { name: '卸载子应用', exact: true }).click();
  await page.getByTestId('status').filter({ hasText: '未挂载' }).waitFor();
  assert.equal(await page.locator('.data-center-app').count(), 0);
  assert.equal(await page.getByTestId('pending').innerText(), '0');
  await page.getByRole('button', { name: '挂载 / 重试', exact: true }).click();
  await mounted();
  await page.getByRole('button', { name: '退出演示会话', exact: true }).click();
  await page.getByTestId('status').filter({ hasText: '未挂载' }).waitFor();
  assert.equal(await page.locator('.data-center-app').count(), 0);
  assert.equal(await page.getByTestId('pending').innerText(), '0');
  await page.getByRole('button', { name: '登录演示会话', exact: true }).click();
  await mounted();
  await page
    .getByRole('button', { name: '挂载 / 重试', exact: true })
    .dblclick();
  await mounted();
  assert.match(
    await page.getByRole('region', { name: '生命周期与通信记录' }).innerText(),
    /update 完成/,
  );
  const entry = await page.locator('.demo-entry code').innerText();
  const standalone = await browser.newPage();
  standalone.setDefaultTimeout(15_000);
  standalone.on('pageerror', (error) => errors.push(error.message));
  await standalone.goto(`${entry}#/departments`);
  await standalone
    .locator('.dc-status')
    .filter({ hasText: '独立预览' })
    .waitFor();
  assert.match(
    await standalone.locator('.dc-notice').innerText(),
    /当前为子应用独立预览/,
  );
  assert.equal(
    await standalone
      .getByRole('button', { name: '刷新数据', exact: true })
      .isDisabled(),
    true,
  );
  await standalone.close();

  const recovery = await browser.newPage();
  recovery.setDefaultTimeout(15_000);
  recovery.on('pageerror', (error) => errors.push(error.message));
  await recovery.route(entry, (route) =>
    route.fulfill({ status: 503, body: '演示入口暂不可用' }),
  );
  await recovery.goto(url);
  await recovery
    .getByTestId('status')
    .filter({ hasText: '加载失败' })
    .waitFor();
  await recovery.unroute(entry);
  await recovery
    .getByRole('button', { name: '挂载 / 重试', exact: true })
    .click();
  await mounted(recovery);
  await recovery.close();
  assert.deepEqual(errors, []);
  await captureScreenshots();
  console.log(
    `PASS ${url}：原生 ESM 挂载、CSS @scope、双向路由/返回/刷新、租户/用户 update、故障恢复、请求取消、卸载/重挂载/退出、快速重试、独立预览、入口503后重试恢复；0 pageerror。`,
  );
} finally {
  await browser.close();
}
