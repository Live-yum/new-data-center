import type { IotDevice, IotMapping, IotSeries } from '@vben/micro-apps';

import { describe, expect, it } from 'vitest';

import {
  cloneProcessScene,
  createProcessScene,
  processReading,
  processTemplates,
  sceneDiagram,
  validateProcessScene,
} from './process-model';

const devices: IotDevice[] = [
  { id: 2701, name: '现场西区入口罐', code: 'WEST-INLET-7' },
  { id: 2702, name: '现场外输泵', code: 'WEST-PUMP-2' },
];
const mappings: Record<number, IotMapping[]> = {
  2701: [
    {
      metricKey: 'inlet.pressure',
      name: '入口压力',
      pointName: '厂区A.罐7.PT',
      unit: 'kPa',
    },
    { metricKey: 'level', name: '液位', pointName: '厂区A.罐7.LT', unit: 'm' },
  ],
  2702: [
    {
      metricKey: 'flow',
      name: '外输流量',
      pointName: '厂区A.泵2.FT',
      unit: 'm³/h',
    },
  ],
};

function configuredScene() {
  const scene = createProcessScene('separation');
  scene.bindings[0] = {
    slotId: 'slot-1',
    deviceId: 2701,
    metricKeys: ['inlet.pressure', 'level'],
    primaryMetricKey: 'inlet.pressure',
  };
  return scene;
}

describe('production process templates and configuration', () => {
  it('provides all five layouts without prebound devices or fake metric keys', () => {
    expect(processTemplates.map((item) => item.id)).toEqual([
      'separation',
      'gas',
      'storage',
      'utilities',
      'offshore',
    ]);
    for (const template of processTemplates) {
      const scene = createProcessScene(template.id);
      expect(scene.id).toBe(0);
      expect(scene.schemaVersion).toBe(1);
      expect(scene.refreshSeconds).toBe(5);
      expect(scene.staleAfterSeconds).toBe(120);
      expect(scene.bindings.map((item) => item.slotId)).toEqual([
        'slot-1',
        'slot-2',
        'slot-3',
        'slot-4',
      ]);
      expect(
        scene.bindings.every(
          (item) =>
            item.deviceId === null &&
            item.metricKeys.length === 0 &&
            item.primaryMetricKey === '',
        ),
      ).toBe(true);
      expect(validateProcessScene(scene, [], {})).toEqual([]);
    }
  });

  it('creates independent drafts and clones nested bindings', () => {
    const original = configuredScene();
    const copy = cloneProcessScene(original);
    copy.bindings[0]!.metricKeys.push('flow');
    copy.name = '本地草稿';
    expect(original.name).not.toBe(copy.name);
    expect(original.bindings[0]!.metricKeys).toEqual([
      'inlet.pressure',
      'level',
    ]);
    expect(createProcessScene('separation').bindings[0]!.metricKeys).toEqual(
      [],
    );
  });

  it('uses actual device names and codes, not template identities', () => {
    const diagram = sceneDiagram(configuredScene(), devices);
    expect(diagram.devices[0]).toEqual({
      id: 1,
      name: '现场西区入口罐',
      code: 'WEST-INLET-7',
      kind: 'tank',
    });
    expect(diagram.devices[1]!.name).toContain('未绑定');
    expect(diagram.devices.map((item) => item.id)).toEqual([1, 2, 3, 4]);
    expect(
      diagram.devices.some((item) =>
        devices.some((device) => device.id === item.id),
      ),
    ).toBe(false);
    const unavailable = sceneDiagram(configuredScene(), []);
    expect(unavailable.devices[0]!.name).toBe('设备已不可用');
    expect(unavailable.devices[0]!.code).toContain('2701');
  });

  it('allows configured tenant devices with available mapped metrics', () => {
    expect(validateProcessScene(configuredScene(), devices, mappings)).toEqual(
      [],
    );
  });

  it('rejects disappeared devices and mappings rather than inventing metrics', () => {
    const scene = configuredScene();
    expect(validateProcessScene(scene, [], mappings)).toContain(
      '入口缓冲图位：绑定设备已不可用，请重新选择',
    );
    expect(validateProcessScene(scene, devices, {})).toContain(
      '入口缓冲图位：缺少有效测点映射 inlet.pressure',
    );
    const blank = {
      ...mappings,
      2701: mappings[2701]!.map((item) => ({ ...item, pointName: ' ' })),
    };
    expect(validateProcessScene(scene, devices, blank)).toHaveLength(2);
  });

  it('requires primary metric selection and clears metrics when unbinding', () => {
    const scene = configuredScene();
    scene.bindings[0]!.primaryMetricKey = 'flow';
    expect(validateProcessScene(scene, devices, mappings)).toContain(
      '入口缓冲图位：图上主指标必须包含在所选指标中',
    );
    scene.bindings[0]!.deviceId = null;
    expect(validateProcessScene(scene, devices, mappings)).toContain(
      '入口缓冲图位：未绑定设备时不能保留指标',
    );
    scene.bindings[0]!.metricKeys = [];
    scene.bindings[0]!.primaryMetricKey = '';
    expect(validateProcessScene(scene, devices, mappings)).toEqual([]);
  });

  it('rejects empty, duplicate and over-limit metric selections', () => {
    const scene = configuredScene();
    for (const keys of [
      [],
      ['level', 'level'],
      Array.from({ length: 21 }, (_, i) => `metric${i}`),
    ]) {
      scene.bindings[0]!.metricKeys = keys;
      expect(validateProcessScene(scene, devices, mappings)).toContain(
        '入口缓冲图位：请选择 1–20 个不重复的指标',
      );
    }
  });

  it('enforces exact slots, total series limit and safe backend metric keys', () => {
    const scene = configuredScene();
    scene.bindings[1]!.slotId = 'slot-1';
    expect(validateProcessScene(scene, devices, mappings)).toContain(
      '页面必须包含四个不重复的模板图位',
    );
    scene.bindings[1]!.slotId = 'unexpected';
    expect(validateProcessScene(scene, devices, mappings)).toContain(
      '页面必须包含四个不重复的模板图位',
    );
    const large = createProcessScene('gas');
    for (const binding of large.bindings)
      binding.metricKeys = Array.from({ length: 13 }, (_, i) => `metric${i}`);
    expect(validateProcessScene(large, devices, mappings)).toContain(
      '每个页面最多绑定 50 个指标',
    );
    const invalid = configuredScene();
    invalid.bindings[0]!.metricKeys = ['../x?admin=true'];
    invalid.bindings[0]!.primaryMetricKey = '../x?admin=true';
    expect(
      validateProcessScene(invalid, devices, {
        2701: [
          { metricKey: '../x?admin=true', name: '', pointName: 'PT', unit: '' },
        ],
      }),
    ).toContain('入口缓冲图位：缺少有效测点映射 ../x?admin=true');
  });

  it('validates metadata and refresh/stale limits without silently normalizing them', () => {
    const scene = configuredScene();
    scene.name = '  ';
    scene.description = 'x'.repeat(501);
    scene.refreshSeconds = 4;
    scene.staleAfterSeconds = 3;
    scene.version = -1;
    const errors = validateProcessScene(scene, devices, mappings);
    expect(errors).toHaveLength(5);
    scene.name = '生产图';
    scene.description = '';
    scene.refreshSeconds = 300;
    scene.staleAfterSeconds = 3600;
    scene.version = 1;
    expect(validateProcessScene(scene, devices, mappings)).toEqual([]);
    scene.refreshSeconds = 5.5;
    expect(validateProcessScene(scene, devices, mappings)).toContain(
      '刷新间隔必须为 5–300 秒的整数',
    );
  });
});

const now = Date.parse('2026-10-27T02:00:00.000Z');
const options = { now, staleAfterSeconds: 120 };
function series(
  value: IotSeries['points'][number]['value'] = 12.3456,
  quality = 'GOOD',
  time = new Date(now - 1000).toISOString(),
): IotSeries {
  return {
    deviceId: 2701,
    metricKey: 'inlet.pressure',
    unit: 'kPa',
    points: [{ value, quality, time, timeSource: 'DEVICE' }],
  };
}

describe('honest production reading states', () => {
  it('displays an actual valid sample, preserving the returned unit and timestamp', () => {
    expect(processReading(series(), options)).toEqual({
      status: 'fresh',
      label: '实时',
      value: '12.346',
      unit: 'kPa',
      time: '2026-10-27T01:59:59.000Z',
      timeSource: 'DEVICE',
      timeLabel: '采样时间',
      quality: 'GOOD',
    });
    expect(processReading(series(0), options).value).toBe('0');
  });

  it('distinguishes unbound, mapping-missing, failed, loading and missing states', () => {
    expect(
      processReading(undefined, { ...options, unbound: true }).status,
    ).toBe('unbound');
    expect(
      processReading(undefined, { ...options, mappingMissing: true }).status,
    ).toBe('mapping-missing');
    expect(
      processReading(series(), { ...options, failed: true }),
    ).toMatchObject({ status: 'failed', value: '—', time: '' });
    expect(
      processReading(undefined, { ...options, loading: true }).status,
    ).toBe('loading');
    expect(processReading(undefined, options)).toMatchObject({
      status: 'missing',
      value: '—',
    });
    expect(processReading(series(), { ...options, loading: true }).status).toBe(
      'fresh',
    );
  });

  it('never calls UNKNOWN or another unspecified quality GOOD', () => {
    for (const quality of ['UNKNOWN', '', 'UNCERTAIN', 'VENDOR_CODE_127']) {
      const result = processReading(series(12, quality), options);
      expect(result).toMatchObject({
        status: 'unknown',
        label: '质量未知',
        value: '12',
      });
      expect(result.quality).toBe(quality || 'UNKNOWN');
    }
    expect(processReading(series('运行', 'GOOD'), options).value).toBe('运行');
  });

  it('suppresses BAD, invalid and demo-quality values rather than showing a healthy value', () => {
    for (const quality of ['BAD', 'bad', 'INVALID', 'ERROR', 'DEMO']) {
      expect(processReading(series(999, quality), options)).toMatchObject({
        status: 'bad',
        value: '—',
        quality,
      });
    }
  });

  it('hides stale values but preserves the sample time and raw quality', () => {
    const oldTime = new Date(now - 120_001).toISOString();
    expect(processReading(series(55, 'GOOD', oldTime), options)).toMatchObject({
      status: 'stale',
      label: '数据陈旧',
      value: '—',
      time: oldTime,
      quality: 'GOOD',
    });
    expect(
      processReading(
        series(55, 'GOOD', new Date(now - 120_000).toISOString()),
        options,
      ).status,
    ).toBe('fresh');
  });

  it('never uses a recent reception time to claim measurement freshness', () => {
    const time = new Date(now - 1000).toISOString();
    const received = series(12, 'GOOD', time);
    received.points[0]!.timeSource = 'RECEIVED';
    expect(processReading(received, options)).toMatchObject({
      status: 'unknown',
      label: '采集时间未知',
      value: '12',
      time,
      timeSource: 'RECEIVED',
      timeLabel: '接收时间',
      quality: 'GOOD',
    });
  });

  it('treats legacy timestamps without provenance conservatively', () => {
    const legacy = series(12, 'GOOD', new Date(now - 1000).toISOString());
    delete legacy.points[0]!.timeSource;
    expect(processReading(legacy, options)).toMatchObject({
      status: 'unknown',
      label: '采集时间未知',
      value: '12',
      timeSource: 'UNKNOWN',
      timeLabel: '时间来源未知',
    });
    legacy.points[0]!.quality = 'BAD';
    expect(processReading(legacy, options)).toMatchObject({
      status: 'bad',
      value: '—',
    });
  });

  it('expires an old received record without asserting measurement staleness', () => {
    const time = new Date(now - 120_001).toISOString();
    const received = series(12, 'GOOD', time);
    received.points[0]!.timeSource = 'RECEIVED';
    expect(processReading(received, options)).toMatchObject({
      status: 'expired',
      label: '接收记录已过期，采集时间未知',
      value: '—',
      time,
      timeSource: 'RECEIVED',
      timeLabel: '接收时间',
      quality: 'GOOD',
    });
    received.points[0]!.time = new Date(now - 120_000).toISOString();
    expect(processReading(received, options)).toMatchObject({
      status: 'unknown',
      value: '12',
    });
  });

  it('expires unknown-provenance records rather than retaining them indefinitely during a hung request', () => {
    const legacy = series(12);
    delete legacy.points[0]!.timeSource;
    expect(processReading(legacy, { ...options, loading: true })).toMatchObject(
      { status: 'unknown', value: '12' },
    );
    expect(
      processReading(legacy, { ...options, now: now + 120_000, loading: true }),
    ).toMatchObject({
      status: 'expired',
      label: '时间来源未知，记录已过期',
      value: '—',
      timeSource: 'UNKNOWN',
      timeLabel: '时间来源未知',
    });
  });

  it('labels a future reception timestamp as reception, never as sampling', () => {
    const received = series(12, 'GOOD', new Date(now + 1).toISOString());
    received.points[0]!.timeSource = 'RECEIVED';
    expect(processReading(received, options)).toMatchObject({
      status: 'missing',
      label: '接收时间异常',
      value: '—',
      timeLabel: '接收时间',
    });
  });

  it('does not replace null, empty, non-finite or invalid timestamp samples with zero', () => {
    for (const value of [null, '', ' ', Number.NaN, Infinity]) {
      expect(processReading(series(value), options)).toMatchObject({
        status: 'missing',
        value: '—',
      });
    }
    expect(
      processReading(series(5, 'GOOD', 'not-a-time'), options),
    ).toMatchObject({ status: 'missing', value: '—', time: '' });
    expect(
      processReading(
        series(5, 'GOOD', new Date(now + 1).toISOString()),
        options,
      ),
    ).toMatchObject({ status: 'missing', label: '采样时间异常', value: '—' });
  });

  it('uses the newest valid timestamp without mutating or trusting transport ordering', () => {
    const unordered = series();
    unordered.points = [
      {
        time: new Date(now - 1000).toISOString(),
        value: 7,
        quality: 'GOOD',
        timeSource: 'DEVICE',
      },
      { time: 'invalid', value: 999, quality: 'GOOD', timeSource: 'DEVICE' },
      {
        time: new Date(now - 10_000).toISOString(),
        value: 3,
        quality: 'GOOD',
        timeSource: 'DEVICE',
      },
    ];
    const original = JSON.stringify(unordered);
    expect(processReading(unordered, options).value).toBe('7');
    expect(JSON.stringify(unordered)).toBe(original);
    unordered.points.push({
      time: new Date(now + 60_000).toISOString(),
      value: 999,
      quality: 'GOOD',
      timeSource: 'DEVICE',
    });
    expect(processReading(unordered, options).value).toBe('—');
  });
});
