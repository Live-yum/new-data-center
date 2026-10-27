import type {
  DataCenterProps,
  IotBridge,
  IotMapping,
  IotProcessScene,
  IotQuery,
  IotResult,
  IotSource,
} from '@vben/micro-apps';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';

import { mount, unmount, update } from '../lifecycle';
import { cloneProcessScene, createProcessScene } from './process-model';

// Explicit in-memory unit-test transport. REALDB below exercises the production
// discriminator; these fixtures are not a real Zijinqiao or plant acceptance test.
const deviceList = [
  { id: 2701, name: '实际目录设备甲', code: 'SITE-A-01' },
  { id: 7302, name: '实际目录设备乙', code: 'SITE-B-02' },
];
const mappingList: Record<number, IotMapping[]> = {
  2701: [
    {
      metricKey: 'pressure',
      name: '入口压力',
      pointName: 'A.PT001',
      unit: 'kPa',
    },
    {
      metricKey: 'temperature',
      name: '入口温度',
      pointName: 'A.TT001',
      unit: '°C',
    },
  ],
  7302: [
    { metricKey: 'flow', name: '外输流量', pointName: 'B.FT008', unit: 'm³/h' },
  ],
};
const source: IotSource = {
  id: 'default',
  name: '紫金桥 RealDB',
  type: 'REALDB',
  enabled: true,
  configured: true,
  status: 'CONFIGURED',
  timeZone: 'Asia/Shanghai',
  description: '测试仅声明配置存在，不代表连接已经验证',
};

function configuredScene(): IotProcessScene {
  const value = createProcessScene('separation');
  value.id = 804;
  value.version = 3;
  value.name = '已保存的真实绑定页面';
  value.bindings[0] = {
    slotId: 'slot-1',
    deviceId: 2701,
    metricKeys: ['pressure'],
    primaryMetricKey: 'pressure',
  };
  value.bindings[1] = {
    slotId: 'slot-2',
    deviceId: 7302,
    metricKeys: ['flow'],
    primaryMetricKey: 'flow',
  };
  return value;
}

function result(query: IotQuery, value = 42): IotResult {
  return {
    source: 'REALDB',
    series: query.deviceIds.flatMap((deviceId) =>
      query.metricKeys.map((metricKey) => ({
        deviceId,
        metricKey,
        unit: metricKey === 'flow' ? 'm³/h' : 'kPa',
        points: [
          {
            time: new Date(Date.now() - 1000).toISOString(),
            value,
            quality: 'GOOD',
            timeSource: 'DEVICE' as const,
          },
        ],
      })),
    ),
  };
}

function fixture(
  initial = [configuredScene()],
  overrides: Partial<IotBridge> = {},
) {
  let stored = initial.map((value) => cloneProcessScene(value));
  const iot: IotBridge = {
    devices: vi.fn(async () => structuredClone(deviceList)),
    createDevice: vi.fn(async (value) => ({ id: 9403, ...value })),
    mappings: vi.fn(async (id) => structuredClone(mappingList[id] ?? [])),
    saveMappings: vi.fn(async () => true),
    sources: vi.fn(async () => [{ ...source }]),
    processScenes: vi.fn(async () =>
      stored.map((value) => cloneProcessScene(value)),
    ),
    processScene: vi.fn(async (id) =>
      cloneProcessScene(stored.find((page) => page.id === id)!),
    ),
    saveProcessScene: vi.fn(async (value) => {
      const saved = cloneProcessScene({
        ...value,
        id: value.id || 981,
        version: value.version + 1,
      });
      stored = [...stored.filter((page) => page.id !== saved.id), saved];
      return cloneProcessScene(saved);
    }),
    deleteProcessScene: vi.fn(async (id) => {
      stored = stored.filter((page) => page.id !== id);
      return true;
    }),
    dashboards: vi.fn(async () => []),
    dashboard: vi.fn(async () => {
      throw new Error('Unused unit-test method');
    }),
    saveDashboard: vi.fn(async (value) => value),
    deleteDashboard: vi.fn(async () => true),
    templates: vi.fn(async () => []),
    createTemplate: vi.fn(async (value) => value),
    query: vi.fn(async (_mode, query) => result(query)),
    exports: vi.fn(async () => []),
    createExport: vi.fn(async () => ({
      id: 'contract-job',
      status: 'PENDING',
      createdAt: '',
      expiresAt: '',
    })),
    cancelExport: vi.fn(async () => true),
    downloadExport: vi.fn(async () => new Blob()),
    ...overrides,
  };
  return iot;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

let container: HTMLElement;
function props(
  iot: IotBridge,
  route = '/process-separation',
  sessionKey = 'one',
): DataCenterProps {
  return {
    container,
    route,
    context: {
      dataSource: 'platform',
      sessionKey,
      tenantId: sessionKey === 'one' ? 1 : 2,
      userName: '组件测试',
      locale: 'zh-CN',
    },
    bridge: {
      iot,
      getDepartments: async () => [],
      getDictionaries: async () => [],
    },
  };
}
async function settle() {
  for (let index = 0; index < 60; index++) await Promise.resolve();
  await nextTick();
}
function get<T extends Element = HTMLElement>(id: string): T {
  const element = container.querySelector<T>(`[data-testid="${id}"]`);
  if (!element) throw new Error(`Missing test element: ${id}`);
  return element;
}
async function click(id: string) {
  get<HTMLElement>(id).click();
  await settle();
}
async function input(id: string, value: string) {
  const element = get<HTMLInputElement>(id);
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
  await settle();
}
async function select(id: string, value: string | string[]) {
  const element = get<HTMLSelectElement>(id);
  if (Array.isArray(value)) {
    for (const option of element.options)
      option.selected = value.includes(option.value);
  } else element.value = value;
  element.dispatchEvent(new Event('change', { bubbles: true }));
  await settle();
}
function submit() {
  get('scene-editor').dispatchEvent(
    new Event('submit', { bubbles: true, cancelable: true }),
  );
}
function reading(metric: string): HTMLElement {
  const element = get('production-live').querySelector<HTMLElement>(
    `[data-metric="${metric}"]`,
  );
  if (!element) throw new Error(`Missing reading: ${metric}`);
  return element;
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-27T02:00:00.000Z'));
  vi.stubGlobal(
    'confirm',
    vi.fn(() => true),
  );
  container = document.createElement('div');
  container.innerHTML = '<div id="data-center-root"></div>';
  document.body.append(container);
});
afterEach(async () => {
  await unmount();
  container?.remove();
  vi.clearAllTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('production process page lifecycle and read-only bindings', () => {
  it('starts empty without fabricated devices, queries or values and exposes all five page types', async () => {
    const api = fixture([]);
    await mount(props(api));
    await settle();
    expect(get('scene-empty').textContent).toContain('尚未配置');
    expect(api.query).not.toHaveBeenCalled();
    expect(api.mappings).not.toHaveBeenCalled();
    expect(
      container.querySelector('[data-testid="process-diagram"]'),
    ).toBeNull();
    for (const type of [
      'separation',
      'gas',
      'storage',
      'utilities',
      'offshore',
    ])
      expect(get(`production-${type}`)).toBeTruthy();
    expect(get('source-status').textContent).toContain('连接待实际读取验证');
  });

  it('isolates demo sessions and blocks disabled production sources without a fake fallback', async () => {
    const api = fixture();
    const demo = props(api);
    demo.context!.dataSource = 'demo';
    await mount(demo);
    await settle();
    expect(get('production-unavailable')).toBeTruthy();
    expect(api.devices).not.toHaveBeenCalled();
    expect(api.sources).not.toHaveBeenCalled();
    expect(api.query).not.toHaveBeenCalled();
    const disabled = fixture(undefined, {
      sources: vi.fn(async () => [
        { ...source, enabled: false, status: 'DISABLED' as const },
      ]),
    });
    await update(props(disabled));
    await settle();
    expect(get('source-status').textContent).toContain('未启用');
    expect(disabled.query).not.toHaveBeenCalled();
    expect(reading('pressure').dataset.status).toBe('failed');
    expect(reading('pressure').textContent).not.toContain('42');
  });

  it('creates, saves and reloads actual device and mapping IDs through the production bridge', async () => {
    const api = fixture([]);
    await mount(props(api));
    await settle();
    await click('scene-new');
    await input('scene-name', '西区真实站点');
    await select('slot-1-device', '2701');
    expect(
      [...get<HTMLSelectElement>('slot-1-metrics').options].map(
        (item) => item.value,
      ),
    ).toEqual(['pressure', 'temperature']);
    await select('slot-1-metrics', ['pressure', 'temperature']);
    await select('slot-1-primary', 'temperature');
    await click('scene-save');
    expect(api.saveProcessScene).toHaveBeenCalledTimes(1);
    const saved = vi.mocked(api.saveProcessScene!).mock.calls[0]![0];
    expect(saved).toMatchObject({
      id: 0,
      name: '西区真实站点',
      template: 'separation',
    });
    expect(saved.bindings[0]).toEqual({
      slotId: 'slot-1',
      deviceId: 2701,
      metricKeys: ['pressure', 'temperature'],
      primaryMetricKey: 'temperature',
    });
    expect(
      saved.bindings.slice(1).every((item) => item.deviceId === null),
    ).toBe(true);
    expect(get('production-live').textContent).toContain('实际目录设备甲');
    expect(
      vi
        .mocked(api.query)
        .mock.calls.every(([, query]) =>
          query.deviceIds.every((id) => id === 2701),
        ),
    ).toBe(true);
    await click('scene-reload');
    expect(get<HTMLSelectElement>('production-scene-select').value).toBe('981');
    const displayedQuery = vi
      .mocked(api.query)
      .mock.calls.findLast(([mode]) => mode === 'history')![1];
    vi.setSystemTime(new Date(Date.now() + 60_000));
    await click('production-export');
    expect(api.createExport).toHaveBeenCalledWith(
      displayedQuery,
      expect.any(AbortSignal),
    );
    expect(get('production-trend').textContent).toContain('contract-job');
    await click('scene-edit');
    expect(get<HTMLInputElement>('scene-name').value).toBe('西区真实站点');
    expect(get<HTMLSelectElement>('slot-1-device').value).toBe('2701');
    expect(get<HTMLSelectElement>('slot-1-primary').value).toBe('temperature');
  });

  it('resets metrics when changing the bound device and cancellation restores the persisted binding', async () => {
    const api = fixture();
    await mount(props(api));
    await settle();
    await click('scene-edit');
    await select('slot-1-device', '7302');
    const metrics = get<HTMLSelectElement>('slot-1-metrics');
    expect([...metrics.options].map((item) => item.value)).toEqual(['flow']);
    expect([...metrics.selectedOptions]).toHaveLength(0);
    expect(get<HTMLSelectElement>('slot-1-primary').value).toBe('');
    expect(get<HTMLButtonElement>('scene-save').disabled).toBe(true);
    await select('slot-1-metrics', ['flow']);
    expect(get<HTMLSelectElement>('slot-1-primary').value).toBe('flow');
    await click('scene-cancel');
    expect(api.saveProcessScene).not.toHaveBeenCalled();
    expect(get('production-live').dataset.deviceId).toBe('2701');
    expect(reading('pressure')).toBeTruthy();
  });

  it('deduplicates a device across slots and chunks the union into at most 20 metric keys', async () => {
    const page = configuredScene();
    const keys = Array.from({ length: 32 }, (_, index) => `metric${index}`);
    page.bindings[0]!.metricKeys = keys.slice(0, 20);
    page.bindings[0]!.primaryMetricKey = keys[0]!;
    page.bindings[1] = {
      slotId: 'slot-2',
      deviceId: 2701,
      metricKeys: keys.slice(12),
      primaryMetricKey: keys[12]!,
    };
    const api = fixture([page], {
      mappings: vi.fn(async () =>
        keys.map((metricKey) => ({
          metricKey,
          name: metricKey,
          pointName: `A.${metricKey}`,
          unit: 'kPa',
        })),
      ),
    });
    await mount(props(api));
    await settle();
    const reads = vi
      .mocked(api.query)
      .mock.calls.filter(([mode]) => mode === 'realtime');
    expect(reads).toHaveLength(2);
    expect(
      reads.every(
        ([, query]) =>
          query.deviceIds.length === 1 &&
          query.deviceIds[0] === 2701 &&
          query.metricKeys.length <= 20,
      ),
    ).toBe(true);
    expect(reads.flatMap(([, query]) => query.metricKeys).toSorted()).toEqual(
      keys.toSorted(),
    );
    expect(reading('metric0').dataset.status).toBe('fresh');
  });

  it('distinguishes missing, stale, BAD, received-time and unknown-quality telemetry', async () => {
    const page = configuredScene();
    const keys = ['missing', 'stale', 'bad', 'received', 'unknown', 'fresh'];
    page.bindings[0]!.metricKeys = keys;
    page.bindings[0]!.primaryMetricKey = 'fresh';
    const api = fixture([page], {
      mappings: vi.fn(async (id) =>
        id === 2701
          ? keys.map((metricKey) => ({
              metricKey,
              name: metricKey,
              pointName: `A.${metricKey}`,
              unit: 'kPa',
            }))
          : (mappingList[id] ?? []),
      ),
      query: vi.fn(async (_mode, query) => {
        const response = result(query);
        for (const series of response.series) {
          const point = series.points[0]!;
          if (series.metricKey === 'missing') point.value = null;
          if (series.metricKey === 'stale')
            point.time = new Date(Date.now() - 121_000).toISOString();
          if (series.metricKey === 'bad') point.quality = 'BAD';
          if (series.metricKey === 'received') point.timeSource = 'RECEIVED';
          if (series.metricKey === 'unknown') point.quality = 'UNKNOWN';
        }
        return response;
      }),
    });
    await mount(props(api));
    await settle();
    for (const [key, status] of Object.entries({
      missing: 'missing',
      stale: 'stale',
      bad: 'bad',
      received: 'unknown',
      unknown: 'unknown',
      fresh: 'fresh',
    }))
      expect(reading(key).dataset.status).toBe(status);
    for (const key of ['missing', 'stale', 'bad'])
      expect(reading(key).querySelector('strong')!.textContent).toContain('—');
    expect(reading('received').textContent).toContain('采集时间未知');
    expect(reading('received').textContent).toContain('接收时间');
    expect(reading('unknown').textContent).toContain('质量 UNKNOWN');
  });

  it('clears previously displayed values when a later read reports a non-REALDB source', async () => {
    let rejected = false;
    const api = fixture(undefined, {
      query: vi.fn(async (mode, query) => ({
        ...result(query, 876.125),
        source: mode === 'realtime' && rejected ? 'DEMO' : 'REALDB',
      })),
    });
    await mount(props(api));
    await settle();
    expect(reading('pressure').textContent).toContain('876.125');
    rejected = true;
    await vi.advanceTimersByTimeAsync(5000);
    await settle();
    expect(reading('pressure').dataset.status).toBe('failed');
    expect(reading('pressure').textContent).not.toContain('876.125');
    expect(get('production-live').textContent).toContain('拒绝非 REALDB');
  });

  it('keeps independent successful devices visible when another device read fails', async () => {
    const api = fixture(undefined, {
      query: vi.fn(async (mode, query) => {
        if (mode === 'realtime' && query.deviceIds[0] === 2701)
          throw new Error('只读网关超时');
        return result(query, 63);
      }),
    });
    await mount(props(api));
    await settle();
    expect(reading('pressure').dataset.status).toBe('failed');
    await select('production-node', 'slot-2');
    expect(reading('flow').dataset.status).toBe('fresh');
    expect(reading('flow').textContent).toContain('63');
    expect(get('production-live').textContent).not.toContain('只读网关超时');
  });

  it('cancels history and rejects late responses after selected-slot changes', async () => {
    const pending: {
      query: IotQuery;
      signal: AbortSignal;
      resolve: (value: IotResult) => void;
    }[] = [];
    const api = fixture(undefined, {
      query: vi.fn(async (mode, query, signal) => {
        if (mode === 'realtime' || query.deviceIds[0] === 7302)
          return result(query, 63);
        const wait = deferred<IotResult>();
        pending.push({ query, signal, resolve: wait.resolve });
        return wait.promise;
      }),
    });
    await mount(props(api));
    await settle();
    const cancel = [...container.querySelectorAll('button')].find(
      (button) => button.textContent?.trim() === '取消查询',
    )!;
    expect(cancel).toBeTruthy();
    cancel.click();
    await settle();
    expect(pending.every((item) => item.signal.aborted)).toBe(true);
    expect(get('production-trend').textContent).toContain('历史查询已取消');
    await select('production-node', 'slot-2');
    pending.forEach((item) =>
      item.resolve({
        ...result(item.query, 999_991),
        series: result(item.query, 999_991).series.map((value) => ({
          ...value,
          unit: 'LATE_OLD_SLOT',
        })),
      }),
    );
    await settle();
    expect(get('production-trend').dataset.deviceId).toBe('7302');
    expect(get('production-samples').textContent).toContain('63');
    expect(container.textContent).not.toContain('LATE_OLD_SLOT');
    expect(container.textContent).not.toContain('999,991');
  });

  it('preserves a conflicting draft until explicit confirmed reload of the remote version', async () => {
    const remote = configuredScene();
    remote.version = 4;
    remote.name = '服务端新版本';
    const api = fixture(undefined, {
      saveProcessScene: vi.fn(async () => {
        throw Object.assign(new Error('版本冲突'), { code: 1_070_000_409 });
      }),
      processScene: vi.fn(async () => cloneProcessScene(remote)),
    });
    await mount(props(api));
    await settle();
    await click('scene-edit');
    await input('scene-name', '不能丢失的本地草稿');
    await click('scene-save');
    expect(get('scene-conflict').textContent).toContain('本地草稿已保留');
    expect(get<HTMLInputElement>('scene-name').value).toBe(
      '不能丢失的本地草稿',
    );
    vi.mocked(window.confirm).mockReturnValueOnce(false);
    await click('scene-conflict-reload');
    expect(api.processScene).not.toHaveBeenCalled();
    expect(get<HTMLInputElement>('scene-name').value).toBe(
      '不能丢失的本地草稿',
    );
    await click('scene-conflict-reload');
    expect(api.processScene).toHaveBeenCalledWith(804, expect.any(AbortSignal));
    expect(container.querySelector('[data-testid="scene-editor"]')).toBeNull();
    expect(container.textContent).toContain('服务端新版本');
  });

  it('clears a prior tenant and ignores reads from a bridge that disregards abort', async () => {
    const pending: {
      query: IotQuery;
      signal: AbortSignal;
      resolve: (value: IotResult) => void;
    }[] = [];
    const oldApi = fixture(undefined, {
      query: vi.fn(async (_mode, query, signal) => {
        const wait = deferred<IotResult>();
        pending.push({ query, signal, resolve: wait.resolve });
        return wait.promise;
      }),
    });
    await mount(props(oldApi));
    await settle();
    expect(pending.length).toBeGreaterThan(0);
    const newApi = fixture([]);
    await update(props(newApi, '/process-separation', 'two'));
    await settle();
    expect(pending.every((item) => item.signal.aborted)).toBe(true);
    pending.forEach((item) =>
      item.resolve({
        ...result(item.query, 999_993),
        series: result(item.query, 999_993).series.map((value) => ({
          ...value,
          unit: 'OLD_TENANT',
        })),
      }),
    );
    await settle();
    expect(get('scene-empty')).toBeTruthy();
    expect(container.textContent).not.toContain('OLD_TENANT');
    expect(newApi.query).not.toHaveBeenCalled();
    const oldCount = vi.mocked(oldApi.query).mock.calls.length;
    await vi.advanceTimersByTimeAsync(15_000);
    expect(oldApi.query).toHaveBeenCalledTimes(oldCount);
  });

  it('stops pending reads and polling on route leave and unmount', async () => {
    const api = fixture();
    const pageProps = props(api);
    await mount(pageProps);
    await settle();
    const firstSignals = vi.mocked(api.query).mock.calls.map((call) => call[2]);
    await update({ ...pageProps, route: '/overview' });
    await settle();
    expect(firstSignals.every((signal) => signal.aborted)).toBe(true);
    const leftCount = vi.mocked(api.query).mock.calls.length;
    await vi.advanceTimersByTimeAsync(15_000);
    expect(api.query).toHaveBeenCalledTimes(leftCount);
    await update(pageProps);
    await settle();
    await unmount();
    expect(
      vi.mocked(api.query).mock.calls.every((call) => call[2].aborted),
    ).toBe(true);
    const unmountedCount = vi.mocked(api.query).mock.calls.length;
    await vi.advanceTimersByTimeAsync(15_000);
    expect(api.query).toHaveBeenCalledTimes(unmountedCount);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('accepts only one save while busy and disables cancellation and route changes', async () => {
    const pending = deferred<IotProcessScene>();
    const api = fixture(undefined, {
      saveProcessScene: vi.fn(async () => pending.promise),
    });
    const pageProps = props(api);
    await mount(pageProps);
    await settle();
    await click('scene-edit');
    await input('scene-name', '保存事务中的草稿');
    submit();
    submit();
    await settle();
    expect(api.saveProcessScene).toHaveBeenCalledTimes(1);
    expect(get('scene-editor').querySelector('fieldset')!.disabled).toBe(true);
    get('scene-cancel').dispatchEvent(
      new MouseEvent('click', { bubbles: true }),
    );
    await update({ ...pageProps, route: '/process-gas' });
    await settle();
    expect(get('production-scenes').dataset.template).toBe('separation');
    expect(get('scene-editor')).toBeTruthy();
    pending.resolve({
      ...configuredScene(),
      name: '保存事务中的草稿',
      version: 4,
    });
    await settle();
    expect(container.querySelector('[data-testid="scene-editor"]')).toBeNull();
    expect(container.textContent).toContain('保存事务中的草稿');
  });

  it('keeps a rejected internal and host navigation on the original route and reconciles the host URL', async () => {
    const api = fixture();
    const navigate = vi.fn();
    const pageProps = { ...props(api), onNavigate: navigate };
    await mount(pageProps);
    await settle();
    await click('scene-edit');
    await input('scene-name', '离开前必须保留');
    vi.mocked(window.confirm).mockReturnValue(false);
    await click('production-gas');
    expect(get('production-scenes').dataset.template).toBe('separation');
    expect(get<HTMLInputElement>('scene-name').value).toBe('离开前必须保留');
    expect(navigate).not.toHaveBeenCalledWith('/process-gas');
    await update({ ...pageProps, route: '/process-gas' });
    await settle();
    expect(get('production-scenes').dataset.template).toBe('separation');
    expect(get<HTMLInputElement>('scene-name').value).toBe('离开前必须保留');
    expect(navigate).toHaveBeenCalledWith('/process-separation', {
      replace: true,
    });
    vi.mocked(window.confirm).mockReturnValue(true);
    await click('production-gas');
    expect(get('production-scenes').dataset.template).toBe('gas');
    expect(container.querySelector('[data-testid="scene-editor"]')).toBeNull();
    expect(get('scene-empty')).toBeTruthy();
  });

  const namedDevices = deviceList.map((device, index) => ({
    ...device,
    sourceId: index === 0 ? 'a' : 'b',
  }));
  const namedSources = ['a', 'b'].map((id) => ({
    ...source,
    id,
    name: `现场源${id}`,
  }));
  function namedResult(query: IotQuery, value = 42): IotResult {
    const data = result(query, value);
    data.series.forEach((item) => {
      item.sourceId = namedDevices.find(
        (device) => device.id === item.deviceId,
      )!.sourceId;
    });
    return data;
  }
  it('blocks only disabled source A while source B in the same scene remains readable', async () => {
    const api = fixture(undefined, {
      devices: vi.fn(async () => namedDevices),
      sources: vi.fn(async () => [
        { ...namedSources[0]!, status: 'DISABLED' as const, enabled: false },
        namedSources[1]!,
      ]),
      query: vi.fn(async (_mode, query) => namedResult(query, 87)),
    });
    await mount(props(api));
    await settle();
    expect(get('production-device-source').textContent).toContain(
      '现场源a [a]',
    );
    expect(reading('pressure').dataset.status).toBe('failed');
    expect(get('production-live').textContent).toContain('未启用');
    expect(
      vi
        .mocked(api.query)
        .mock.calls.every(([, query]) => query.deviceIds[0] === 7302),
    ).toBe(true);
    await select('production-node', 'slot-2');
    expect(reading('flow').textContent).toContain('87');
    expect(get('production-history-source').textContent).toContain(
      '现场源b [b]',
    );
    expect(get('production-live').dataset.sourceId).toBe('b');
  });
  it('removes failed-source previous values but preserves the healthy source after an outage', async () => {
    let failed = false;
    const api = fixture(undefined, {
      devices: vi.fn(async () => namedDevices),
      sources: vi.fn(async () => namedSources),
      query: vi.fn(async (_mode, query) => {
        if (failed && query.deviceIds[0] === 2701)
          return {
            source: 'REALDB',
            partial: true,
            series: [],
            errors: [
              {
                sourceId: 'a',
                code: 'SOURCE_UNAVAILABLE',
                message: '独立源读取失败',
              },
            ],
          };
        return namedResult(query, query.deviceIds[0] === 2701 ? 31 : 87);
      }),
    });
    await mount(props(api));
    await settle();
    expect(reading('pressure').textContent).toContain('31');
    failed = true;
    await click('production-live-refresh');
    expect(reading('pressure').dataset.status).toBe('failed');
    expect(reading('pressure').textContent).not.toContain('31');
    expect(get('production-live').textContent).toContain(
      '现场源a [a]：独立源读取失败',
    );
    await select('production-node', 'slot-2');
    expect(reading('flow').textContent).toContain('87');
    expect(get('production-trend').textContent).toContain('现场源b [b]');
  });
  it('rejects cross-source or source-less named-source responses and cannot export that history', async () => {
    const api = fixture(undefined, {
      devices: vi.fn(async () => namedDevices),
      sources: vi.fn(async () => namedSources),
      query: vi.fn(async (_mode, query) => result(query, 999)),
    });
    await mount(props(api));
    await settle();
    expect(reading('pressure').dataset.status).toBe('failed');
    expect(reading('pressure').textContent).not.toContain('999');
    expect(get('production-live').textContent).toContain('拒绝混用同名测点');
    expect(get<HTMLButtonElement>('production-export').disabled).toBe(true);
  });
  it('treats a missing tenant-visible source as unavailable or denied, without substituting another source', async () => {
    const api = fixture(undefined, {
      devices: vi.fn(async () => namedDevices),
      sources: vi.fn(async () => [namedSources[1]!]),
      query: vi.fn(async (_mode, query) => namedResult(query, 87)),
    });
    await mount(props(api));
    await settle();
    expect(get('production-live').textContent).toContain('无权访问');
    expect(get('production-live').textContent).toContain('[a]');
    expect(
      vi
        .mocked(api.query)
        .mock.calls.some(([, query]) => query.deviceIds.includes(2701)),
    ).toBe(false);
    await select('production-node', 'slot-2');
    expect(reading('flow').textContent).toContain('87');
  });
  it('bounds each source live queue and keeps healthy source polling while another source remains unresolved', async () => {
    const page = configuredScene(); page.refreshSeconds = 5;
    const devices = [2701, 7302, 9403, 9404].map((id, index) => ({ id, name: `排队设备${index}`, code: `QUEUE-${index}`, sourceId: index === 3 ? 'b' : 'a' }));
    page.bindings = devices.map((device, index) => ({ slotId: `slot-${index + 1}`, deviceId: device.id, metricKeys: ['pressure'], primaryMetricKey: 'pressure' }));
    const gates = Array.from({ length: 3 }, () => deferred<IotResult>()); const blockedB = deferred<IotResult>();
    let callsA = 0, callsB = 0, active = 0, maximum = 0;
    function data(query: IotQuery, value: number) { const response = result(query, value); response.series.forEach((item) => { item.sourceId = devices.find((device) => device.id === item.deviceId)!.sourceId; }); return response; }
    const api = fixture([page], { devices: vi.fn(async () => devices), sources: vi.fn(async () => namedSources), mappings: vi.fn(async () => [mappingList[2701]![0]!]), query: vi.fn(async (mode, query) => {
      if (mode === 'history') return data(query, 10);
      if (query.deviceIds[0] === 9404) { callsB++; return blockedB.promise; }
      const index = callsA++; maximum = Math.max(maximum, ++active);
      try { return index < 3 ? await gates[index]!.promise : data(query, 200 + index); } finally { active--; }
    }) });
    await mount(props(api)); await settle(); expect(callsA).toBe(2); expect(callsB).toBe(1);
    for (let index = 0; index < 3; index++) { gates[index]!.resolve(data({ deviceIds: [devices[index]!.id], metricKeys: ['pressure'], aggregation: 'RAW' }, 100 + index)); await settle(); }
    expect(callsA).toBe(3); expect(maximum).toBe(2); expect(reading('pressure').textContent).toContain('100');
    expect(get<HTMLButtonElement>('production-live-refresh').disabled).toBe(false);
    await vi.advanceTimersByTimeAsync(5000); await settle();
    expect(callsA).toBe(6); expect(callsB).toBe(1); expect(maximum).toBe(2); expect(reading('pressure').textContent).toContain('203');
    await unmount(); const count = callsA; blockedB.resolve(data({ deviceIds: [9404], metricKeys: ['pressure'], aggregation: 'RAW' }, 999)); await settle();
    await vi.advanceTimersByTimeAsync(15000); expect(callsA).toBe(count);
  });

  it('shares the same two-request budget between four live device reads and automatic history', async () => {
    const page = configuredScene();
    const devices = [2701, 7302, 9403, 9404].map((id, index) => ({ id, name: `预算设备${index}`, code: `BUDGET-${index}`, sourceId: 'a' }));
    page.bindings = devices.map((device, index) => ({ slotId: `slot-${index + 1}`, deviceId: device.id, metricKeys: ['pressure'], primaryMetricKey: 'pressure' }));
    const gates = Array.from({ length: 4 }, () => deferred<IotResult>()); let reads = 0, historyReads = 0, active = 0, maximum = 0;
    function data(query: IotQuery) { const response = result(query, 61); response.series.forEach((item) => { item.sourceId = 'a'; }); return response; }
    const api = fixture([page], { devices: vi.fn(async () => devices), sources: vi.fn(async () => [namedSources[0]!]), mappings: vi.fn(async () => [mappingList[2701]![0]!]), query: vi.fn(async (mode, query) => {
      maximum = Math.max(maximum, ++active);
      try { if (mode === 'history') { historyReads++; return data(query); } return await gates[reads++]!.promise; } finally { active--; }
    }) });
    await mount(props(api)); await settle(); expect(reads).toBe(2); expect(historyReads).toBe(0);
    for (let index = 0; index < 4; index++) { gates[index]!.resolve(data({ deviceIds: [devices[index]!.id], metricKeys: ['pressure'], aggregation: 'RAW' })); await settle(); }
    expect(reads).toBe(4); expect(historyReads).toBe(1); expect(maximum).toBe(2);
    expect(get('production-trend').querySelector('svg')).not.toBeNull(); expect(container.textContent).not.toContain('SOURCE_BUSY');
  });
  it('removes a cancelled queued history request before the live requests release their shared source slots', async () => {
    const gates = [deferred<IotResult>(), deferred<IotResult>()]; let liveReads = 0, historyReads = 0;
    const api = fixture(undefined, { query: vi.fn(async (mode, query) => { if (mode === 'history') { historyReads++; return result(query); } return gates[liveReads++]!.promise; }) });
    await mount(props(api)); await settle(); expect(liveReads).toBe(2); expect(historyReads).toBe(0);
    await click('production-history-cancel');
    gates[0]!.resolve(result({ deviceIds: [2701], metricKeys: ['pressure'], aggregation: 'RAW' }));
    gates[1]!.resolve(result({ deviceIds: [7302], metricKeys: ['flow'], aggregation: 'RAW' })); await settle();
    expect(historyReads).toBe(0); expect(get('production-trend').textContent).toContain('历史查询已取消');
  });

});
