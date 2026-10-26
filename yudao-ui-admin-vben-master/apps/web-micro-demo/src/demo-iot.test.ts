import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { createDemoBridge } from './demo-bridge';

const signal = () => new AbortController().signal;
function bridge(tenantId = 1, lifetime = new AbortController(), fail = false) {
  return createDemoBridge({
    tenantId,
    signal: lifetime.signal,
    delay: 1,
    fail,
    onPending: () => {},
  }).iot!;
}
async function advance<T>(promise: Promise<T>) {
  await vi.advanceTimersByTimeAsync(2);
  return promise;
}
beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers();
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
describe('IoT demo bridge', () => {
  it('persists dashboard edits across bridges, isolates tenants and rejects optimistic conflicts', async () => {
    const a = bridge();
    const original = await advance(a.dashboard(1, signal()));
    const updated = await advance(
      a.saveDashboard(
        reactive({ ...original, name: '保存后的看板' }),
        signal(),
      ),
    );
    expect(updated.version).toBe(2);
    expect((await advance(bridge().dashboard(1, signal()))).name).toBe(
      '保存后的看板',
    );
    expect((await advance(bridge(2).dashboard(1, signal()))).name).toBe(
      '锅炉运行模板',
    );
    const stale = a.saveDashboard(original, signal());
    const rejection = expect(stale).rejects.toMatchObject({ status: 409 });
    await vi.advanceTimersByTimeAsync(2);
    await rejection;
    const deleted = a.deleteDashboard(1, 1, signal());
    const rejectDelete = expect(deleted).rejects.toMatchObject({ status: 409 });
    await vi.advanceTimersByTimeAsync(2);
    await rejectDelete;
    await advance(a.deleteDashboard(1, 2, signal()));
    expect(await advance(a.dashboards(signal()))).toEqual([]);
  });
  it('persists reusable templates and rejects fixed-device template bindings', async () => {
    const a = bridge();
    const template = (await advance(a.templates(signal())))[0]!;
    const saved = await advance(
      a.createTemplate({ ...template, name: '用户模板' }, signal()),
    );
    expect(saved.id).toBeGreaterThan(0);
    expect(
      (await advance(bridge().templates(signal()))).map((t) => t.name),
    ).toContain('用户模板');
    expect(
      (await advance(bridge(2).templates(signal()))).map((t) => t.name),
    ).not.toContain('用户模板');
    template.widgets[0]!.binding.mode = 'fixed';
    const rejected = a.createTemplate(template, signal());
    const assertion = expect(rejected).rejects.toThrow('当前设备绑定');
    await vi.advanceTimersByTimeAsync(2);
    await assertion;
    const key = 'ndc-iot-demo-v1-tenant-1';
    const legacy = JSON.parse(localStorage.getItem(key)!);
    delete legacy.templates;
    localStorage.setItem(key, JSON.stringify(legacy));
    expect(await advance(bridge().templates(signal()))).toHaveLength(1);
  });
  it('aborted sessions and simulated failures cannot persist writes', async () => {
    const life = new AbortController();
    const a = bridge(1, life);
    const request = a.createDevice({ name: 'not saved', code: 'X' }, signal());
    const rejection = expect(request).rejects.toMatchObject({
      name: 'AbortError',
    });
    life.abort();
    await rejection;
    expect(await advance(bridge().devices(signal()))).toHaveLength(3);
    const failed = bridge(1, new AbortController(), true).createDevice(
      { name: 'not saved', code: 'X' },
      signal(),
    );
    const failedAssertion = expect(failed).rejects.toThrow('演示请求失败');
    await vi.advanceTimersByTimeAsync(2);
    await failedAssertion;
    expect(await advance(bridge().devices(signal()))).toHaveLength(3);
  });
  it('does not retain failed persistence in memory', async () => {
    const a = bridge();
    vi.stubGlobal('localStorage', {
      getItem: () => null,
      setItem: () => {
        throw new Error('quota');
      },
    });
    const pending = a.createDevice(
      { name: 'quota rejected', code: 'QUOTA' },
      signal(),
    );
    const assertion = expect(pending).rejects.toThrow('未持久化');
    await vi.advanceTimersByTimeAsync(2);
    await assertion;
    vi.unstubAllGlobals();
    expect(await advance(a.devices(signal()))).toHaveLength(3);
  });
  it('uses distinct per-device values and leaves missing samples null', async () => {
    const data = await advance(
      bridge().query(
        'history',
        {
          deviceIds: [1, 2],
          metricKeys: ['temperature_out'],
          aggregation: 'RAW',
          intervalMs: 60_000,
          startTime: '2026-10-26T00:00:00Z',
          endTime: '2026-10-26T01:00:00Z',
        },
        signal(),
      ),
    );
    expect(data.source).toBe('DEMO');
    expect(data.series[0]!.points[0]!.value).not.toEqual(
      data.series[1]!.points[0]!.value,
    );
    expect(
      data.series[1]!.points.some(
        (p) => p.value === null && p.quality === 'MISSING',
      ),
    ).toBe(true);
  });
  it('preserves sampling intervals and aggregates the full sampled window', async () => {
    const a = bridge();
    const q = {
      deviceIds: [1],
      metricKeys: ['temperature_out'],
      aggregation: 'RAW' as const,
      intervalMs: 7000,
      startTime: '2026-10-26T00:00:00Z',
      endTime: '2026-10-26T00:00:20Z',
    };
    const raw = await advance(a.query('history', q, signal()));
    const points = raw.series[0]!.points;
    expect(points.map((p) => p.time)).toEqual([
      '2026-10-26T00:00:00.000Z',
      '2026-10-26T00:00:07.000Z',
      '2026-10-26T00:00:14.000Z',
    ]);
    const avg = await advance(
      a.query('history', { ...q, aggregation: 'AVG' }, signal()),
    );
    expect(avg.series[0]!.points).toHaveLength(1);
    expect(avg.series[0]!.points[0]!.value).toBeCloseTo(
      points.reduce((sum, p) => sum + Number(p.value), 0) / points.length,
    );
    for (const bad of [
      { ...q, intervalMs: undefined },
      { ...q, intervalMs: 999 },
      { ...q, startTime: '2026-10-26T00:00:00' },
      { ...q, endTime: '2026-11-03T00:00:00Z' },
      { ...q, intervalMs: 1000, endTime: '2026-10-27T00:00:00Z' },
      { ...q, deviceIds: [3] },
    ]) {
      const pending = a.query('history', bad, signal());
      const assertion = expect(pending).rejects.toThrow();
      await vi.advanceTimersByTimeAsync(2);
      await assertion;
    }
  });
  it('creates cancellable tasks, completes and downloads CSV, rejects failed/expired downloads', async () => {
    const a = bridge();
    const q = {
      deviceIds: [1],
      metricKeys: ['temperature_out'],
      aggregation: 'RAW' as const,
      intervalMs: 60_000,
      startTime: '2026-10-26T00:00:00Z',
      endTime: '2026-10-26T01:00:00Z',
    };
    const first = await advance(a.createExport(q, signal()));
    expect(first.status).toBe('RUNNING');
    await advance(a.cancelExport(first.id, signal()));
    expect(
      (await advance(a.exports(signal()))).find((v) => v.id === first.id)
        ?.status,
    ).toBe('CANCELLED');
    const second = await advance(a.createExport(q, signal()));
    await vi.advanceTimersByTimeAsync(3100);
    expect(
      (await advance(a.exports(signal()))).find((v) => v.id === second.id)
        ?.status,
    ).toBe('SUCCEEDED');
    const blob = await advance(a.downloadExport(second.id, signal()));
    expect(await blob.text()).toContain('temperature_out');
    for (const id of ['demo-failed', 'demo-expired', first.id]) {
      const download = a.downloadExport(id, signal());
      const assertion = expect(download).rejects.toThrow('文件未完成或已过期');
      await vi.advanceTimersByTimeAsync(2);
      await assertion;
    }
  });
});
