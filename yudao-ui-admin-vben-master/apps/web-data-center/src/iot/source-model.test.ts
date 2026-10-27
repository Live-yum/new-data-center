import type { IotDevice, IotResult, IotSource } from '@vben/micro-apps';
import { describe, expect, it } from 'vitest';
import {
  deviceSourceId,
  deviceSourceIssue,
  deviceSourceLabel,
  productionSeries,
  sourceResultErrors,
} from './source-model';
const sources: IotSource[] = ['a', 'b'].map((id) => ({
  id,
  name: '同名数据源',
  type: 'REALDB',
  enabled: true,
  configured: true,
  status: 'CONFIGURED',
  timeZone: 'Asia/Shanghai',
  description: '',
}));
const devices: IotDevice[] = [
  { id: 1, name: '同名设备', code: 'A', sourceId: 'a' },
  { id: 2, name: '同名设备', code: 'B', sourceId: 'b' },
];
function result(): IotResult {
  return {
    source: 'REALDB',
    series: devices.map((d) => ({
      deviceId: d.id,
      sourceId: d.sourceId,
      metricKey: 'same.point',
      unit: 'kPa',
      points: [
        {
          time: '2026-10-27T00:00:00Z',
          value: d.id * 10,
          quality: 'GOOD',
          timeSource: 'DEVICE',
        },
      ],
    })),
  };
}
describe('source-aware identity and partial failure boundaries', () => {
  it('labels equal source/device names with stable IDs and never adopts the first source for old devices', () => {
    const old = { id: 3, name: '旧设备', code: 'OLD' };
    expect(deviceSourceId(old)).toBe('default');
    expect(deviceSourceLabel(old, sources)).toBe('[default] · 兼容默认源');
    expect(deviceSourceLabel(devices[0], sources)).toContain('[a]');
    expect(deviceSourceLabel(devices[1], sources)).toContain('[b]');
  });
  it('distinguishes disabled, unavailable/denied and unverified catalog without making a network health claim', () => {
    expect(
      deviceSourceIssue(
        devices[0],
        [{ ...sources[0]!, status: 'DISABLED' }],
        true,
      ),
    ).toContain('未启用');
    expect(deviceSourceIssue(devices[0], [], true)).toContain('无权访问');
    expect(deviceSourceIssue(devices[0], [], false)).toBe('');
    expect(deviceSourceIssue(devices[0], sources, true)).toBe('');
  });
  it('keeps equal named points separate and returns healthy-source data when another fails', () => {
    const value = result();
    expect(
      productionSeries(value, devices).map((s) => s.points[0]!.value),
    ).toEqual([10, 20]);
    value.partial = true;
    value.errors = [
      { sourceId: 'a', code: 'SOURCE_UNAVAILABLE', message: '读取失败' },
    ];
    expect(productionSeries(value, devices).map((s) => s.sourceId)).toEqual([
      'b',
    ]);
    expect(sourceResultErrors(value, sources)[0]).toContain('同名数据源 [a]');
  });
  it('rejects an incorrect source or omitted named-source identity instead of silently relabeling values', () => {
    const value = result();
    value.series[0]!.sourceId = 'b';
    expect(() => productionSeries(value, devices)).toThrow('拒绝混用同名测点');
    value.series[0]!.sourceId = undefined;
    expect(() => productionSeries(value, devices)).toThrow('拒绝混用同名测点');
  });
  it('accepts source-less legacy results only for the legacy default binding', () => {
    const value = result();
    value.series = [value.series[0]!];
    delete value.series[0]!.sourceId;
    expect(
      productionSeries(value, [{ id: 1, name: '旧设备', code: 'OLD' }]),
    ).toHaveLength(1);
  });
  it('rejects demo discriminator or quality and describes partial failure even if an old server omits errors', () => {
    const value = result();
    value.source = 'DEMO';
    expect(() => productionSeries(value, devices)).toThrow('非 REALDB');
    value.source = 'REALDB';
    value.series[0]!.points[0]!.quality = 'DEMO';
    expect(() => productionSeries(value, devices)).toThrow('模拟质量');
    expect(
      sourceResultErrors(
        { source: 'REALDB', series: [], partial: true },
        sources,
      )[0],
    ).toContain('部分数据源读取失败');
  });
});
