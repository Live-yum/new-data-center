import { describe, expect, it, vi } from 'vitest';
import { createIotBridge } from '../../../web-ele/src/views/data-center/iot-bridge';
import { normalizeDataCenterRoute } from '@vben/micro-apps';
import type { IotProcessScene } from '@vben/micro-apps';

describe('production IoT transport contract', () => {
  it('allows only supported production process and source routes', () => {
    for (const path of [
      '/sources',
      '/process-separation',
      '/process-gas',
      '/process-storage',
      '/process-utilities',
      '/process-offshore',
    ])
      expect(normalizeDataCenterRoute(path)).toBe(path);
    expect(normalizeDataCenterRoute('/process-unknown')).toBe('/overview');
    expect(normalizeDataCenterRoute('https://example.com')).toBe('/overview');
  });
  it('routes source and process CRUD only through host-owned transport', async () => {
    const read = vi.fn(async () => ({})) as any;
    const bridge = createIotBridge(read);
    const signal = new AbortController().signal;
    const page: IotProcessScene = {
      id: 0,
      name: '实际站场',
      version: 0,
      schemaVersion: 1,
      template: 'gas',
      bindings: [],
      refreshSeconds: 5,
      staleAfterSeconds: 30,
    };
    await bridge.sources!(signal);
    expect(read).toHaveBeenLastCalledWith('/iot/sources', signal);
    await bridge.processScenes!(signal);
    expect(read).toHaveBeenLastCalledWith('/iot/process-pages', signal);
    await bridge.saveProcessScene!(page, signal);
    expect(read).toHaveBeenLastCalledWith(
      '/iot/process-pages',
      signal,
      'POST',
      page,
    );
    page.id = 17;
    page.version = 2;
    await bridge.saveProcessScene!(page, signal);
    expect(read).toHaveBeenLastCalledWith(
      '/iot/process-pages/17',
      signal,
      'PUT',
      page,
    );
    await bridge.processScene!(17, signal);
    expect(read).toHaveBeenLastCalledWith('/iot/process-pages/17', signal);
    await bridge.deleteProcessScene!(17, 2, signal);
    expect(read).toHaveBeenLastCalledWith(
      '/iot/process-pages/17?version=2',
      signal,
      'DELETE',
    );
    expect(bridge.demoOilgasQuery).toBeUndefined();
  });
  it('preserves explicit device source binding and partial-source outcomes', async () => {
    const result = {
      source: 'REALDB',
      partial: true,
      errors: [
        {
          sourceId: 'source-b',
          code: 'SOURCE_UNAVAILABLE',
          message: '数据源暂不可用',
        },
      ],
      series: [
        {
          sourceId: 'source-a',
          deviceId: 7,
          metricKey: 'pressure',
          unit: 'MPa',
          points: [],
        },
      ],
    };
    const read = vi.fn(async () => result) as any;
    const bridge = createIotBridge(read);
    const signal = new AbortController().signal;
    const device = { name: '同名泵', code: 'pump-a', sourceId: 'source-a' };
    await bridge.createDevice(device, signal);
    expect(read).toHaveBeenLastCalledWith(
      '/iot/devices',
      signal,
      'POST',
      device,
    );
    const query = {
      deviceIds: [7, 8],
      metricKeys: ['pressure'],
      aggregation: 'RAW' as const,
    };
    expect(await bridge.query('realtime', query, signal)).toBe(result);
    expect(read).toHaveBeenCalledTimes(2);
    const job = {
      id: 'job',
      sourceIds: ['source-a', 'source-b'],
      status: 'QUEUED',
    };
    read.mockResolvedValueOnce(job);
    expect(await bridge.createExport(query, signal)).toBe(job);
    expect(read).toHaveBeenLastCalledWith(
      '/iot/exports',
      signal,
      'POST',
      query,
    );
  });
  it('preserves real telemetry, private download and error semantics', async () => {
    const result = { source: 'REALDB', series: [] };
    const read = vi.fn(async () => result) as any;
    const bridge = createIotBridge(read);
    const signal = new AbortController().signal;
    const query = {
      deviceIds: [1],
      metricKeys: ['pressure'],
      aggregation: 'RAW' as const,
    };
    expect(await bridge.query('realtime', query, signal)).toBe(result);
    expect(read).toHaveBeenLastCalledWith(
      '/iot/query/realtime',
      signal,
      'POST',
      query,
    );
    await bridge.downloadExport('a/b', signal);
    expect(read).toHaveBeenLastCalledWith(
      '/iot/exports/a%2Fb/file',
      signal,
      'GET',
      undefined,
      true,
    );
    const failure = new Error('RealDB unavailable');
    read.mockRejectedValueOnce(failure);
    await expect(bridge.query('history', query, signal)).rejects.toBe(failure);
    expect(read).toHaveBeenLastCalledWith(
      '/iot/query/history',
      signal,
      'POST',
      query,
    );
  });
});
