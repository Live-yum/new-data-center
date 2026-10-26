import type { DataCenterProps, IotResult } from '@vben/micro-apps';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { mount, unmount, update } from '../lifecycle';
import { createDemoIotBridge } from '../../../web-micro-demo/src/demo-iot';
let container: HTMLElement;
async function settle() {
  for (let i = 0; i < 30; i++) await Promise.resolve();
  await nextTick();
}
function props(
  route = '/demo-separation',
  sessionKey = 'one',
): DataCenterProps {
  const lifetime = new AbortController();
  return {
    container,
    route,
    context: {
      dataSource: 'demo',
      sessionKey,
      tenantId: sessionKey === 'one' ? 1 : 2,
      locale: 'zh-CN',
      userName: 'test',
    },
    bridge: {
      getDepartments: async () => [],
      getDictionaries: async () => [],
      iot: createDemoIotBridge(1, lifetime.signal, async (value) => value),
    },
  };
}
beforeEach(() => {
  vi.useFakeTimers();
  container = document.createElement('div');
  container.innerHTML = '<div id="data-center-root"></div>';
  document.body.append(container);
});
afterEach(async () => {
  await unmount();
  container.remove();
  vi.useRealTimers();
});
describe('oilgas demo isolation and readonly interactions', () => {
  it('removes tank level from pump cards and queries, resetting the old metric and aborting its request', async () => {
    const p = props('/demo-storage');
    const query = vi.spyOn(p.bridge!.iot!, 'demoOilgasQuery');
    await mount(p);
    await settle();
    expect(
      container
        .querySelector('[data-testid="oilgas-trend"]')
        ?.getAttribute('data-metric'),
    ).toBe('level');
    const oldSignal = query.mock.calls.find(([mode]) => mode === 'history')![2];
    container
      .querySelector('[data-testid="equipment-123"]')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await settle();
    expect(oldSignal.aborted).toBe(true);
    const select = container.querySelector(
      '[data-testid="oilgas-metric"]',
    ) as HTMLSelectElement;
    expect([...select.options].map((option) => option.value)).toEqual([
      'temperature',
      'flow',
    ]);
    expect(select.value).toBe('temperature');
    expect(
      container.querySelector('.og-device-panel')?.textContent,
    ).not.toContain('液位');
    expect(
      query.mock.calls.some(
        ([, q]) => q.deviceIds.includes(123) && q.metricKeys.includes('level'),
      ),
    ).toBe(false);
    expect(
      container
        .querySelector('[data-testid="oilgas-trend"]')
        ?.getAttribute('data-device-id'),
    ).toBe('123');
    expect(container.querySelector('[role="alert"]')).toBeNull();
  });
  it('does not query or show simulated readings outside demo context', async () => {
    const p = props();
    p.context!.dataSource = 'platform';
    const query = vi.spyOn(p.bridge!.iot!, 'demoOilgasQuery');
    await mount(p);
    await settle();
    expect(query).not.toHaveBeenCalled();
    expect(
      container.querySelector('[data-testid="oilgas-unavailable"]'),
    ).toBeTruthy();
    expect(container.querySelector('[data-testid="oilgas-demo"]')).toBeNull();
  });
  it('switches scene, selected device and metric through real query contracts', async () => {
    const p = props();
    const query = vi.spyOn(p.bridge!.iot!, 'demoOilgasQuery');
    await mount(p);
    await settle();
    expect(
      container.querySelector('[data-testid="oilgas-trend"] svg'),
    ).toBeTruthy();
    (
      container.querySelector('[data-testid="equipment-102"]') as Element
    ).dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await settle();
    expect(
      container
        .querySelector('[data-testid="oilgas-demo"]')
        ?.getAttribute('data-selected-device'),
    ).toBe('102');
    expect(
      query.mock.calls.some(
        ([mode, q]) => mode === 'history' && q.deviceIds[0] === 102,
      ),
    ).toBe(true);
    const select = container.querySelector(
      '[data-testid="oilgas-metric"]',
    ) as HTMLSelectElement;
    select.value = 'temperature';
    select.dispatchEvent(new Event('change'));
    await settle();
    expect(
      container.querySelector('[data-testid="oilgas-samples"]')?.textContent,
    ).toContain('°C');
    await update({ ...p, route: '/demo-offshore' });
    await settle();
    expect(
      container
        .querySelector('[data-testid="oilgas-demo"]')
        ?.getAttribute('data-scene'),
    ).toBe('offshore');
    expect(
      container
        .querySelector('[data-testid="oilgas-trend"]')
        ?.getAttribute('data-device-id'),
    ).toBe('141');
    expect(container.textContent).toContain('非工程设计');
    expect(container.querySelectorAll('svg [role="button"]')).toHaveLength(4);
  });
  it('clears prior tenant and discards late results even if a bridge ignores abort', async () => {
    const p = props();
    const completions: ((v: IotResult) => void)[] = [];
    const signals: AbortSignal[] = [];
    p.bridge!.iot!.demoOilgasQuery = vi.fn((_mode, _q, signal) => {
      signals.push(signal);
      return new Promise<IotResult>((resolve) => completions.push(resolve));
    });
    await mount(p);
    await settle();
    expect(completions).toHaveLength(5);
    await update(props('/demo-gas', 'two'));
    await settle();
    expect(signals.every((s) => s.aborted)).toBe(true);
    const late: IotResult = {
      source: 'DEMO',
      series: [
        {
          deviceId: 101,
          metricKey: 'pressure',
          unit: 'BAD_OLD_TENANT',
          points: [
            { time: new Date().toISOString(), value: 99999, quality: 'DEMO' },
          ],
        },
      ],
    };
    completions.forEach((resolve) => resolve(late));
    await settle();
    expect(container.textContent).not.toContain('BAD_OLD_TENANT');
    expect(
      container
        .querySelector('[data-testid="oilgas-demo"]')
        ?.getAttribute('data-scene'),
    ).toBe('gas');
  });
  it('stops polling and pending reads on route leave and unmount', async () => {
    const p = props();
    const query = vi.spyOn(p.bridge!.iot!, 'demoOilgasQuery');
    await mount(p);
    await settle();
    const signal = query.mock.calls[0]![2];
    await update({ ...p, route: '/overview' });
    await settle();
    const count = query.mock.calls.length;
    await vi.advanceTimersByTimeAsync(15000);
    expect(query).toHaveBeenCalledTimes(count);
    expect(signal.aborted).toBe(true);
    await update({ ...p, route: '/demo-storage' });
    await settle();
    await unmount();
    const after = query.mock.calls.length;
    await vi.advanceTimersByTimeAsync(15000);
    expect(query).toHaveBeenCalledTimes(after);
  });
  it('clears fabricated values when the bridge reports a non-DEMO source', async () => {
    const p = props();
    p.bridge!.iot!.demoOilgasQuery = async () => ({
      source: 'REALDB',
      series: [],
    });
    await mount(p);
    await settle();
    expect(container.textContent).toContain('拒绝非 DEMO 数据源');
    expect(
      container.querySelector('[data-testid="oilgas-trend"] svg'),
    ).toBeNull();
  });
});
