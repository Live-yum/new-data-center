import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { oilgasScenes } from '@vben/micro-apps';
import { createDemoIotBridge } from './demo-iot';

const signal = () => new AbortController().signal;
function bridge(lifetime = signal()) {
  return createDemoIotBridge(1, lifetime, async (value, request) => {
    if (request.aborted) throw new DOMException('Cancelled', 'AbortError');
    return value;
  });
}
beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-26T12:00:00Z'));
});
afterEach(() => vi.useRealTimers());

describe('read-only oil and gas demo fixtures', () => {
  it('provides five distinct scenes and twenty unique catalog devices isolated from existing boilers', async () => {
    expect(oilgasScenes.map((scene) => scene.id)).toEqual([
      'separation',
      'gas',
      'storage',
      'utilities',
      'offshore',
    ]);
    const devices = await bridge().devices(signal());
    expect(devices).toHaveLength(3);
    expect(
      new Set(oilgasScenes.flatMap((s) => s.devices.map((d) => d.id))).size,
    ).toBe(20);
    expect(new Set(devices.map((d) => d.id)).size).toBe(3);
    expect(devices.filter((d) => d.id < 100).map((d) => d.code)).toEqual([
      'BLR-001',
      'BLR-002',
      'BLR-003',
    ]);
    expect((await bridge().dashboard(1, signal())).name).toBe('锅炉运行模板');
    for (const scene of oilgasScenes) {
      expect(scene.devices).toHaveLength(4);
      expect(scene.metrics).toHaveLength(4);
      for (const device of scene.devices) {
        expect(scene.metrics.map((m) => m.metricKey)).toContain(
          device.metricKey,
        );
        expect(await bridge().mappings(device.id, signal())).toEqual([]);
      }
    }
  });
  it('uses identical values and units for realtime and historical samples at the same timestamp', async () => {
    const a = bridge();
    for (const scene of oilgasScenes) {
      const query = {
        deviceIds: [scene.devices[0]!.id],
        metricKeys: scene.metrics.map((m) => m.metricKey),
        aggregation: 'RAW' as const,
      };
      const live = await a.demoOilgasQuery!('realtime', query, signal());
      const past = await a.demoOilgasQuery!(
        'history',
        {
          ...query,
          startTime: '2026-10-26T11:59:00Z',
          endTime: '2026-10-26T12:00:00Z',
          intervalMs: 60_000,
        },
        signal(),
      );
      expect(live.source).toBe('DEMO');
      for (const [index, series] of live.series.entries()) {
        expect(series.points[0]).toEqual(past.series[index]!.points.at(-1));
        expect(series.unit).toBe(scene.metrics[index]!.unit);
        expect(series.points[0]!.quality).toBe('DEMO');
        if (typeof series.points[0]!.value === 'number') {
          const metric = scene.metrics[index]!;
          expect(series.points[0]!.value).toBeGreaterThan(
            metric.base - metric.amplitude - 0.01,
          );
          expect(series.points[0]!.value).toBeLessThan(
            metric.base + metric.amplitude + 0.01,
          );
        }
      }
    }
  });
  it('leaves explicitly missing history values null rather than zero', async () => {
    for (const scene of oilgasScenes) {
      const result = await bridge().demoOilgasQuery!(
        'history',
        {
          deviceIds: [scene.devices[1]!.id],
          metricKeys: ['flow'],
          aggregation: 'RAW',
          startTime: '2026-10-26T11:00:00Z',
          endTime: '2026-10-26T12:00:00Z',
          intervalMs: 60_000,
        },
        signal(),
      );
      expect(result.series[0]!.points[30]).toEqual({
        time: '2026-10-26T11:30:00.000Z',
        value: null,
        quality: 'MISSING',
      });
      expect(result.series[0]!.points).toHaveLength(61);
    }
  });
  it('keeps legacy same-ID devices and editable mappings independent from dedicated demo capability', async () => {
    const a = bridge();
    await a.createDevice({ name: 'seed', code: 'seed' }, signal());
    const key = 'ndc-iot-demo-v1-tenant-1';
    const store = JSON.parse(localStorage.getItem(key)!);
    store.devices.push({ id: 101, name: 'legacy custom', code: 'CUSTOM-101' });
    store.mappings[101] = [
      {
        metricKey: 'pressure',
        name: 'legacy temperature',
        pointName: 'OLD.TEMP',
        unit: '°C',
      },
    ];
    store.nextId = 102;
    localStorage.setItem(key, JSON.stringify(store));
    const mapping = await a.mappings(101, signal());
    expect(mapping[0]!.pointName).toBe('OLD.TEMP');
    await a.saveMappings(101, mapping, signal());
    const legacy = await a.query(
      'realtime',
      { deviceIds: [101], metricKeys: ['pressure'], aggregation: 'RAW' },
      signal(),
    );
    const demo = await a.demoOilgasQuery!(
      'realtime',
      { deviceIds: [101], metricKeys: ['pressure'], aggregation: 'RAW' },
      signal(),
    );
    expect(legacy.series[0]!.unit).toBe('°C');
    await expect(
      a.demoOilgasQuery!(
        'realtime',
        { deviceIds: [1], metricKeys: ['temperature_out'], aggregation: 'RAW' },
        signal(),
      ),
    ).rejects.toThrow('未配置');
    expect(demo.series[0]!.unit).toBe('MPa');
    expect((await a.devices(signal())).filter((d) => d.id === 101)).toEqual([
      { id: 101, name: 'legacy custom', code: 'CUSTOM-101' },
    ]);
    expect(
      (await a.createDevice({ name: 'next', code: 'NEXT' }, signal())).id,
    ).toBe(102);
    const life = new AbortController();
    life.abort();
    await expect(
      bridge(life.signal).demoOilgasQuery!(
        'realtime',
        {
          deviceIds: [101],
          metricKeys: ['pressure'],
          aggregation: 'RAW',
        },
        signal(),
      ),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(
      (
        await a.demoOilgasQuery!(
          'realtime',
          { deviceIds: [144], metricKeys: ['flow'], aggregation: 'RAW' },
          signal(),
        )
      ).series[0]!.unit,
    ).toBe('Nm³/h');
    expect(
      oilgasScenes.find((scene) => scene.id === 'storage')!.metrics[0],
    ).toMatchObject({ metricKey: 'level', unit: '%' });
  });
});
