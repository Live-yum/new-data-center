import assert from 'node:assert/strict';

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

async function mounted(target = page) {
  await target.getByTestId('status').filter({ hasText: '已挂载' }).waitFor();
  await target.locator('.dc-toolbar p').filter({ hasText: '更新于' }).waitFor();
  assert.equal(await target.locator('.data-center-app').count(), 1);
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
  console.log(
    `PASS ${url}：原生 ESM 挂载、CSS @scope、双向路由/返回/刷新、租户/用户 update、故障恢复、请求取消、卸载/重挂载/退出、快速重试、独立预览、入口503后重试恢复；0 pageerror。`,
  );
} finally {
  await browser.close();
}
