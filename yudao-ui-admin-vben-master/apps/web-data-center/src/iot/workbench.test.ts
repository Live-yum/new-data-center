import type {
  DataCenterProps,
  IotBridge,
  IotDashboard,
  IotDevice,
  IotResult,
} from '@vben/micro-apps';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { mount, unmount, update } from '../lifecycle';

const mapping = {
  metricKey: 'temperature_out',
  name: '出口温度',
  pointName: 'Boiler.Temp',
  unit: '°C',
};
function dashboard(name = '默认看板'): IotDashboard {
  return {
    id: 1,
    name,
    version: 3,
    schemaVersion: 1,
    widgets: [
      {
        id: 'temperature',
        title: '温度卡',
        type: 'stat',
        binding: {
          mode: 'currentDevice',
          deviceIds: [],
          metricKey: mapping.metricKey,
        },
        grid: { x: 0, y: 0, w: 6, h: 3 },
        timeWindowSeconds: 3600,
        aggregation: 'RAW',
      },
    ],
  };
}
function result(value = 42): IotResult {
  return {
    source: 'test',
    series: [
      {
        deviceId: 1,
        metricKey: mapping.metricKey,
        unit: '°C',
        points: [{ time: '2026-01-01T01:00:00Z', value, quality: 'good' }],
      },
    ],
  };
}
function api(overrides: Partial<IotBridge> = {}): IotBridge {
  return {
    devices: vi.fn(async () => [{ id: 1, name: '当前设备甲', code: 'A' }]),
    createDevice: vi.fn(async (value) => ({ id: 2, ...value })),
    mappings: vi.fn(async () => [mapping]),
    saveMappings: vi.fn(async () => true),
    dashboards: vi.fn(async () => [dashboard()]),
    dashboard: vi.fn(async () => dashboard()),
    saveDashboard: vi.fn(async (value) => ({
      ...value,
      version: value.version + 1,
    })),
    deleteDashboard: vi.fn(async () => true),
    templates: vi.fn(async () => []),
    createTemplate: vi.fn(async (value) => value),
    query: vi.fn(async () => result()),
    exports: vi.fn(async () => []),
    createExport: vi.fn(async () => ({
      id: 'one',
      status: 'PENDING',
      createdAt: '',
      expiresAt: '',
    })),
    cancelExport: vi.fn(async () => true),
    downloadExport: vi.fn(async () => new Blob()),
    ...overrides,
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function settle() {
  for (let i = 0; i < 20; i++) await Promise.resolve();
  await nextTick();
}
function props(
  iot: IotBridge,
  sessionKey = 'session-one',
  route = '/dashboards',
): DataCenterProps {
  return {
    route,
    context: {
      sessionKey,
      tenantId: sessionKey === 'session-one' ? 1 : 2,
      locale: 'zh-CN',
      userName: '测试',
      dataSource: 'demo',
    },
    bridge: {
      getDepartments: async () => [],
      getDictionaries: async () => [],
      iot,
    },
  };
}

describe('IoT workbench session, cancellation and conflict integration', () => {
  let container: HTMLElement;
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
    vi.restoreAllMocks();
  });
  function button(id: string) {
    const node = container.querySelector<HTMLButtonElement>(
      `[data-testid="${id}"]`,
    );
    expect(node).not.toBeNull();
    return node!;
  }

  it('mounts the real dashboard route, renders bridge data and preserves host URL', async () => {
    const url = window.location.href;
    const bridge = api();
    await mount({ container, ...props(bridge) });
    await settle();
    expect(
      container.querySelector('[data-testid="iot-widget-temperature"]'),
    ).not.toBeNull();
    expect(container.textContent).toContain('42');
    expect(container.textContent).toContain('当前设备甲');
    expect(bridge.query).toHaveBeenCalled();
    expect(window.location.href).toBe(url);
  });

  it('aborts initialization from the old session and ignores its late device list', async () => {
    const pending = deferred<IotDevice[]>();
    let oldSignal!: AbortSignal;
    const old = api({
      devices: vi.fn((signal) => {
        oldSignal = signal;
        return pending.promise;
      }),
    });
    await mount({ container, ...props(old) });
    await settle();
    const fresh = api({
      devices: vi.fn(async () => [{ id: 2, name: '新租户设备乙', code: 'B' }]),
    });
    await update(props(fresh, 'session-two'));
    await settle();
    expect(oldSignal.aborted).toBe(true);
    pending.resolve([{ id: 99, name: '旧租户迟到设备', code: 'OLD' }]);
    await settle();
    expect(container.textContent).toContain('新租户设备乙');
    expect(container.textContent).not.toContain('旧租户迟到设备');
  });

  it('aborts old widget reads and never lets a late old reading fill the new tenant', async () => {
    const pending = deferred<IotResult>();
    const signals: AbortSignal[] = [];
    const old = api({
      query: vi.fn((_mode, _query, signal) => {
        signals.push(signal);
        return pending.promise;
      }),
    });
    await mount({ container, ...props(old) });
    await settle();
    expect(signals.length).toBeGreaterThan(0);
    const fresh = api({ query: vi.fn(async () => result(73)) });
    await update(props(fresh, 'session-two'));
    await settle();
    expect(signals.every((signal) => signal.aborted)).toBe(true);
    pending.resolve(result(987654));
    await settle();
    expect(container.textContent).toContain('73');
    expect(container.textContent).not.toContain('987,654');
  });

  it('clears polling and aborts the in-flight read when unmounted', async () => {
    const bridge = api();
    await mount({ container, ...props(bridge) });
    await settle();
    const initialCalls = vi.mocked(bridge.query).mock.calls.length;
    await vi.advanceTimersByTimeAsync(10000);
    await settle();
    expect(vi.mocked(bridge.query).mock.calls.length).toBeGreaterThan(
      initialCalls,
    );
    const pending = deferred<IotResult>();
    let signal!: AbortSignal;
    vi.mocked(bridge.query).mockImplementation((_mode, _query, value) => {
      signal = value;
      return pending.promise;
    });
    button('iot-edit'); // Dashboard remains mounted through the poll.
    await vi.advanceTimersByTimeAsync(10000);
    await settle();
    expect(signal.aborted).toBe(false);
    await unmount();
    expect(signal.aborted).toBe(true);
    const atUnmount = vi.mocked(bridge.query).mock.calls.length;
    pending.resolve(result(100));
    await settle();
    await vi.advanceTimersByTimeAsync(60000);
    expect(vi.mocked(bridge.query).mock.calls.length).toBe(atUnmount);
    expect(
      container.querySelector('#data-center-root')?.childElementCount,
    ).toBe(0);
  });

  it('cancels a history query and rejects its late result without blocking the next query', async () => {
    const pending = deferred<IotResult>();
    let signal!: AbortSignal;
    const bridge = api({
      query: vi.fn((_mode, _query, value) => {
        signal = value;
        return pending.promise;
      }),
    });
    await mount({ container, ...props(bridge, 'session-one', '/history') });
    await settle();
    button('iot-history-run').click();
    await settle();
    expect(signal.aborted).toBe(false);
    button('iot-history-cancel').click();
    await settle();
    expect(signal.aborted).toBe(true);
    pending.resolve(result(987654));
    await settle();
    expect(
      container.querySelector('[data-testid="iot-history-results"]')
        ?.textContent,
    ).not.toContain('987,654');
    expect(button('iot-history-run').disabled).toBe(false);
    vi.mocked(bridge.query).mockResolvedValue(result(81));
    button('iot-history-run').click();
    await settle();
    expect(
      container.querySelector('[data-testid="iot-history-results"]')
        ?.textContent,
    ).toContain('81');
  });

  it('aborts history on route change and does not repopulate it after navigating back', async () => {
    const pending = deferred<IotResult>();
    let signal!: AbortSignal;
    const bridge = api({
      query: vi.fn((_mode, _query, value) => {
        signal = value;
        return pending.promise;
      }),
    });
    await mount({ container, ...props(bridge, 'session-one', '/history') });
    await settle();
    button('iot-history-run').click();
    await settle();
    await update({ route: '/devices' });
    await settle();
    expect(signal.aborted).toBe(true);
    pending.resolve(result(987654));
    await settle();
    await update({ route: '/history' });
    await settle();
    expect(
      container.querySelector('[data-testid="iot-history-results"]')
        ?.textContent,
    ).not.toContain('987,654');
  });

  it('ignores a late post-save dashboard list after the session changes', async () => {
    const pending = deferred<IotDashboard[]>();
    const list = vi
      .fn<IotBridge['dashboards']>()
      .mockResolvedValueOnce([dashboard()])
      .mockImplementation(() => pending.promise);
    const old = api({ dashboards: list });
    await mount({ container, ...props(old) });
    await settle();
    button('iot-edit').click();
    await settle();
    const input = container.querySelector<HTMLInputElement>(
      'input[aria-label="看板名称"]',
    )!;
    input.value = '旧会话编辑';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    button('iot-save').click();
    await settle();
    expect(list).toHaveBeenCalledTimes(2);
    const fresh = api({
      dashboards: vi.fn(async () => [dashboard('新租户看板')]),
    });
    await update(props(fresh, 'session-two'));
    await settle();
    pending.resolve([dashboard('旧租户迟到看板')]);
    await settle();
    const select = container.querySelector(
      '[data-testid="iot-dashboard-select"]',
    );
    expect(select?.textContent).toContain('新租户看板');
    expect(select?.textContent).not.toContain('旧租户迟到看板');
  });

  it.each([
    ['query', 60], ['query', 15], ['export', 60], ['export', 15],
  ] as const)('sends explicit sampling milliseconds and UTC dates for history %s at %s seconds', async (operation, seconds) => {
    const bridge = api();
    await mount({ container, ...props(bridge, 'session-one', '/history') });
    await settle();
    const interval = container.querySelector<HTMLInputElement>('input[aria-label="采样间隔秒"]')!;
    expect(interval.value).toBe('60');
    if (seconds !== 60) {
      interval.value = String(seconds);
      interval.dispatchEvent(new Event('input', { bubbles: true }));
      await settle();
    }
    button(operation === 'query' ? 'iot-history-run' : 'iot-export').click();
    await settle();
    const payload = operation === 'query'
      ? vi.mocked(bridge.query).mock.calls.at(-1)?.[1]
      : vi.mocked(bridge.createExport).mock.calls.at(-1)?.[0];
    expect(payload).toEqual(expect.objectContaining({ intervalMs: seconds * 1000, deviceIds: [1], metricKeys: [mapping.metricKey], aggregation: 'RAW' }));
    expect(payload?.startTime).toMatch(/^\d{4}-\d{2}-\d{2}T.+Z$/);
    expect(payload?.endTime).toMatch(/^\d{4}-\d{2}-\d{2}T.+Z$/);
    expect(Date.parse(payload!.endTime!) - Date.parse(payload!.startTime!)).toBe(3600000);
  });

  it.each([3600, 604800])('includes an explicit bounded sample interval for dashboard line window %s seconds', async (windowSeconds) => {
    const board = dashboard();
    board.widgets[0]!.type = 'line';
    board.widgets[0]!.timeWindowSeconds = windowSeconds;
    const bridge = api({ dashboards: vi.fn(async () => [board]) });
    await mount({ container, ...props(bridge) }); await settle();
    const calls = vi.mocked(bridge.query).mock.calls;
    expect(calls.length).toBeGreaterThan(0);
    for (const [mode, payload] of calls) {
      expect(mode).toBe('history');
      expect(payload.intervalMs).toBeGreaterThanOrEqual(1000);
      expect(payload.startTime).toMatch(/Z$/); expect(payload.endTime).toMatch(/Z$/);
      const samples = (Math.floor((Date.parse(payload.endTime!) - Date.parse(payload.startTime!)) / payload.intervalMs!) + 1) * payload.deviceIds.length * payload.metricKeys.length;
      expect(samples).toBeLessThanOrEqual(10000);
    }
  });

  it('locks dashboard inputs and mutation gestures during save, then permits a new unsaved draft', async () => {
    const pending = deferred<IotDashboard>();
    const bridge = api({ saveDashboard: vi.fn(() => pending.promise) });
    await mount({ container, ...props(bridge) }); await settle(); button('iot-edit').click(); await settle();
    const input = container.querySelector<HTMLInputElement>('input[aria-label="看板名称"]')!;
    input.value = '保存中的名称'; input.dispatchEvent(new Event('input', { bubbles: true })); await settle();
    button('iot-save').click(); await settle();
    for (const node of [input, button('iot-new'), button('iot-gallery'), button('iot-edit'), button('iot-drag-temperature')]) {
      expect(node.closest('fieldset')?.disabled).toBe(true);
    }
    const card = container.querySelector('[data-testid="iot-widget-temperature"]')!;
    const grid = card.getAttribute('data-grid');
    // Synthetic events bypass browser disabled controls: the handlers must still be safe.
    button('iot-new').dispatchEvent(new Event('click', { bubbles: true }));
    button('iot-drag-temperature').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    button('iot-drag-temperature').dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX: 0, clientY: 0, bubbles: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { clientX: 500, clientY: 200 }));
    window.dispatchEvent(new PointerEvent('pointerup'));
    await settle(); expect(card.getAttribute('data-grid')).toBe(grid); expect(input.value).toBe('保存中的名称');
    expect(container.querySelectorAll('.iot-widget')).toHaveLength(1);
    pending.resolve({ ...dashboard('保存中的名称'), version: 4 }); await settle();
    const editable = container.querySelector<HTMLInputElement>('input[aria-label="看板名称"]')!;
    expect(editable.matches(':disabled')).toBe(false); expect(button('iot-gallery').matches(':disabled')).toBe(false);
    editable.value = '保存后的新草稿'; editable.dispatchEvent(new Event('input', { bubbles: true })); await settle();
    expect(editable.value).toBe('保存后的新草稿'); expect(container.textContent).toContain('未保存'); expect(button('iot-save').disabled).toBe(false);
  });

  it('locks dashboard editing while loading another dashboard and restores it on completion', async () => {
    const pending = deferred<IotDashboard>();
    const second = { ...dashboard('第二个看板'), id: 2 };
    const bridge = api({ dashboards: vi.fn(async () => [dashboard(), second]), dashboard: vi.fn(() => pending.promise) });
    await mount({ container, ...props(bridge) }); await settle(); button('iot-edit').click(); await settle();
    const select = container.querySelector<HTMLSelectElement>('[data-testid="iot-dashboard-select"]')!;
    select.value = '2'; select.dispatchEvent(new Event('change', { bubbles: true })); await settle();
    expect(select.matches(':disabled')).toBe(true); expect(button('iot-new').closest('fieldset')?.disabled).toBe(true);
    expect(container.querySelector('input[aria-label="看板名称"]')?.closest('fieldset')?.disabled).toBe(true);
    pending.resolve(second); await settle();
    expect(select.matches(':disabled')).toBe(false); expect(select.value).toBe('2'); expect(button('iot-new').matches(':disabled')).toBe(false);
  });

  it('does not offer multi-device binding for image hotspots', async () => {
    const bridge = api(); await mount({ container, ...props(bridge) }); await settle();
    button('iot-edit').click(); await settle(); button('iot-gallery').click(); await settle(); button('iot-add-image').click(); await settle();
    const option = container.querySelector<HTMLOptionElement>('[data-testid="iot-binding-mode"] option[value="multiple"]');
    expect(!option || option.disabled).toBe(true);
  });

  it('does not offer editable historical aggregation for a realtime stat component', async () => {
    const bridge = api(); await mount({ container, ...props(bridge) }); await settle();
    button('iot-edit').click(); await settle(); button('iot-gallery').click(); await settle(); button('iot-add-stat').click(); await settle();
    const dialog = container.querySelector('[aria-label="组件数据绑定"]')!;
    const aggregation = [...dialog.querySelectorAll('label')].find((label) => label.textContent?.includes('整个窗口聚合'))?.querySelector('select');
    expect(!aggregation || aggregation.matches(':disabled')).toBe(true);
  });

  it('terminates an already active drag before taking the save snapshot', async () => {
    const pending = deferred<IotDashboard>();
    const bridge = api({ saveDashboard: vi.fn(() => pending.promise) });
    await mount({ container, ...props(bridge) }); await settle(); button('iot-edit').click(); await settle();
    const grid = container.querySelector<HTMLElement>('.iot-grid')!;
    vi.spyOn(grid, 'getBoundingClientRect').mockReturnValue({ width: 1200, height: 600, x: 0, y: 0, top: 0, left: 0, right: 1200, bottom: 600, toJSON: () => ({}) });
    button('iot-drag-temperature').dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX: 0, clientY: 0, bubbles: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { clientX: 100, clientY: 70 })); await settle();
    const card = container.querySelector('[data-testid="iot-widget-temperature"]')!;
    const beforeSave = card.getAttribute('data-grid');
    expect(JSON.parse(beforeSave!).x).toBe(1); expect(JSON.parse(beforeSave!).y).toBe(1);
    button('iot-save').click(); await settle();
    const snapshot = vi.mocked(bridge.saveDashboard).mock.calls[0]![0];
    expect(snapshot.widgets[0]!.grid).toEqual(JSON.parse(beforeSave!));
    window.dispatchEvent(new PointerEvent('pointermove', { clientX: 500, clientY: 280 })); await settle();
    expect(card.getAttribute('data-grid')).toBe(beforeSave);
    pending.resolve({ ...snapshot, version: snapshot.version + 1 }); await settle();
    window.dispatchEvent(new PointerEvent('pointermove', { clientX: 800, clientY: 420 }));
    window.dispatchEvent(new PointerEvent('pointerup')); await settle();
    expect(card.getAttribute('data-grid')).toBe(beforeSave);
    expect(container.textContent).toContain('已保存');
  });

  it('normalizes a real interceptor-shaped business conflict and preserves the local title', async () => {
    const business = { code: 1070000409, msg: '版本已变化，请重新加载' };
    const bridge = api({ saveDashboard: vi.fn(async () => { throw { data: business, response: { data: business } }; }) });
    await mount({ container, ...props(bridge) }); await settle(); button('iot-edit').click(); await settle();
    const input = container.querySelector<HTMLInputElement>('input[aria-label="看板名称"]')!;
    input.value = '正式接口冲突时的本地标题'; input.dispatchEvent(new Event('input', { bubbles: true })); await settle();
    button('iot-save').click(); await settle();
    expect(container.querySelector('[data-testid="iot-conflict"]')?.textContent).toContain('当前草稿保留');
    expect(container.querySelector('[data-testid="iot-error"]')?.textContent).toContain('版本已变化');
    expect(input.value).toBe('正式接口冲突时的本地标题');
    expect(bridge.dashboard).not.toHaveBeenCalled(); expect(button('iot-save').disabled).toBe(false);
  });

  it('retains a dirty draft and exposes an explicit reload choice on optimistic-lock conflict', async () => {
    const bridge = api({
      saveDashboard: vi.fn(async () => {
        throw new Error('409 version conflict');
      }),
    });
    await mount({ container, ...props(bridge) });
    await settle();
    button('iot-edit').click();
    await settle();
    const input = container.querySelector<HTMLInputElement>(
      'input[aria-label="看板名称"]',
    )!;
    input.value = '未保存的本地草稿';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    button('iot-save').click();
    await settle();
    expect(
      container.querySelector('[data-testid="iot-conflict"]')?.textContent,
    ).toContain('当前草稿保留');
    expect(input.value).toBe('未保存的本地草稿');
    expect(container.textContent).toContain('未保存');
    expect(bridge.dashboard).not.toHaveBeenCalled();
    expect(button('iot-save').disabled).toBe(false);
    expect(bridge.saveDashboard).toHaveBeenCalledWith(
      expect.objectContaining({ name: '未保存的本地草稿', version: 3 }),
      expect.any(AbortSignal),
    );
  });
});
