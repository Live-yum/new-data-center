import type {
  IotDevice,
  IotMapping,
  IotProcessScene,
  IotSeries,
} from '@vben/micro-apps';

import { formatValue } from './widget-model';

export type ProcessEquipmentKind =
  | 'separator'
  | 'tank'
  | 'pump'
  | 'compressor'
  | 'tower';

/** Diagram IDs identify graphical slots only, never a device or a query target. */
export interface ProcessDiagramScene {
  id: string;
  name: string;
  devices: {
    id: number;
    name: string;
    code: string;
    kind: ProcessEquipmentKind;
  }[];
}

export interface ProcessTemplate {
  id: IotProcessScene['template'];
  name: string;
  subtitle: string;
  slots: { id: string; name: string; kind: ProcessEquipmentKind }[];
}

function slots(
  values: [name: string, kind: ProcessEquipmentKind][],
): ProcessTemplate['slots'] {
  return values.map(([name, kind], index) => ({
    id: `slot-${index + 1}`,
    name,
    kind,
  }));
}

/** Layout starting points only: no fictional devices, measurements or thresholds. */
export const processTemplates: ProcessTemplate[] = [
  {
    id: 'separation',
    name: '陆地油气水分离',
    subtitle: '缓冲 · 分离 · 分路输送',
    slots: slots([
      ['入口缓冲图位', 'tank'],
      ['分离设备图位', 'separator'],
      ['原油输送图位', 'pump'],
      ['采出水缓冲图位', 'tank'],
    ]),
  },
  {
    id: 'gas',
    name: '天然气处理',
    subtitle: '入口分离 · 脱水 · 压缩外输',
    slots: slots([
      ['入口分离图位', 'separator'],
      ['脱水塔器图位', 'tower'],
      ['气体压缩图位', 'compressor'],
      ['出口缓冲图位', 'tank'],
    ]),
  },
  {
    id: 'storage',
    name: '储罐外输',
    subtitle: '储存 · 转输 · 外输',
    slots: slots([
      ['储罐图位 A', 'tank'],
      ['储罐图位 B', 'tank'],
      ['外输泵图位 A', 'pump'],
      ['外输泵图位 B', 'pump'],
    ]),
  },
  {
    id: 'utilities',
    name: '泵站公用工程',
    subtitle: '循环水 · 缓冲 · 公用工程供给',
    slots: slots([
      ['循环水缓冲图位', 'tank'],
      ['循环水泵图位 A', 'pump'],
      ['循环水泵图位 B', 'pump'],
      ['回水缓冲图位', 'tank'],
    ]),
  },
  {
    id: 'offshore',
    name: '海上平台总览',
    subtitle: '海上来液 · 分离处理 · 油气外输',
    slots: slots([
      ['生产分离图位', 'separator'],
      ['原油缓冲图位', 'tank'],
      ['原油外输图位', 'pump'],
      ['气体压缩图位', 'compressor'],
    ]),
  },
];

export function createProcessScene(
  templateId: IotProcessScene['template'],
): IotProcessScene {
  const template = processTemplates.find((item) => item.id === templateId);
  if (!template) throw new Error('不支持的工艺页面类型');
  return {
    id: 0,
    name: template.name,
    description: '',
    version: 0,
    schemaVersion: 1,
    template: template.id,
    bindings: template.slots.map((slot) => ({
      slotId: slot.id,
      deviceId: null,
      metricKeys: [],
      primaryMetricKey: '',
    })),
    refreshSeconds: 5,
    staleAfterSeconds: 120,
  };
}

export function cloneProcessScene(scene: IotProcessScene): IotProcessScene {
  return JSON.parse(JSON.stringify(scene)) as IotProcessScene;
}

export function validateProcessScene(
  scene: IotProcessScene,
  devices: IotDevice[],
  mappings: Record<number, IotMapping[]>,
): string[] {
  const issues: string[] = [];
  if (!scene.name.trim() || scene.name.length > 100)
    issues.push('页面名称必须为 1–100 个字符');
  if ((scene.description?.length ?? 0) > 500)
    issues.push('页面说明不能超过 500 个字符');
  if (
    !Number.isSafeInteger(scene.id) ||
    scene.id < 0 ||
    !Number.isSafeInteger(scene.version) ||
    scene.version < 0 ||
    scene.schemaVersion !== 1
  )
    issues.push('页面版本信息无效，请重新加载');
  const template = processTemplates.find((item) => item.id === scene.template);
  if (!template) issues.push('不支持的工艺页面类型');
  if (
    !Number.isInteger(scene.refreshSeconds) ||
    scene.refreshSeconds < 5 ||
    scene.refreshSeconds > 300
  )
    issues.push('刷新间隔必须为 5–300 秒的整数');
  if (
    !Number.isInteger(scene.staleAfterSeconds) ||
    scene.staleAfterSeconds < scene.refreshSeconds ||
    scene.staleAfterSeconds > 3600
  )
    issues.push('陈旧判定必须不少于刷新间隔且不超过 3600 秒');
  const slotIds = template?.slots.map((slot) => slot.id) ?? [];
  if (
    scene.bindings.length !== 4 ||
    new Set(scene.bindings.map((binding) => binding.slotId)).size !== 4 ||
    scene.bindings.some((binding) => !slotIds.includes(binding.slotId))
  )
    issues.push('页面必须包含四个不重复的模板图位');
  if (
    scene.bindings.reduce(
      (count, binding) => count + binding.metricKeys.length,
      0,
    ) > 50
  )
    issues.push('每个页面最多绑定 50 个指标');

  for (const binding of scene.bindings) {
    const label =
      template?.slots.find((slot) => slot.id === binding.slotId)?.name ??
      binding.slotId;
    if (binding.deviceId === null) {
      if (binding.metricKeys.length > 0 || binding.primaryMetricKey)
        issues.push(`${label}：未绑定设备时不能保留指标`);
      continue;
    }
    const device = devices.find((item) => item.id === binding.deviceId);
    if (
      !Number.isSafeInteger(binding.deviceId) ||
      binding.deviceId <= 0 ||
      !device
    )
      issues.push(`${label}：绑定设备已不可用，请重新选择`);
    if (
      binding.metricKeys.length === 0 ||
      binding.metricKeys.length > 20 ||
      new Set(binding.metricKeys).size !== binding.metricKeys.length
    )
      issues.push(`${label}：请选择 1–20 个不重复的指标`);
    if (
      !binding.primaryMetricKey ||
      !binding.metricKeys.includes(binding.primaryMetricKey)
    )
      issues.push(`${label}：图上主指标必须包含在所选指标中`);
    for (const metric of binding.metricKeys) {
      if (
        !/^[A-Za-z0-9_.-]{1,64}$/.test(metric) ||
        !mappings[binding.deviceId]?.some(
          (mapping) =>
            mapping.metricKey === metric && !!mapping.pointName.trim(),
        )
      )
        issues.push(`${label}：缺少有效测点映射 ${metric || '（空指标）'}`);
    }
  }
  return issues;
}

export function sceneDiagram(
  scene: IotProcessScene,
  devices: IotDevice[],
): ProcessDiagramScene {
  const template = processTemplates.find((item) => item.id === scene.template);
  if (!template) throw new Error('不支持的工艺页面类型');
  return {
    id: template.id,
    name: scene.name,
    devices: template.slots.map((slot, index) => {
      const binding = scene.bindings.find((item) => item.slotId === slot.id);
      const device = devices.find((item) => item.id === binding?.deviceId);
      const bound = binding?.deviceId != null;
      return {
        id: index + 1,
        kind: slot.kind,
        name:
          device?.name ?? (bound ? '设备已不可用' : `${slot.name} · 未绑定`),
        code: device?.code ?? (bound ? `设备 ID ${binding.deviceId}` : slot.id),
      };
    }),
  };
}

export interface ProcessReading {
  status:
    | 'unbound'
    | 'mapping-missing'
    | 'failed'
    | 'loading'
    | 'missing'
    | 'stale'
    | 'expired'
    | 'bad'
    | 'unknown'
    | 'fresh';
  label: string;
  value: string;
  unit: string;
  time: string;
  timeSource: 'DEVICE' | 'RECEIVED' | 'UNKNOWN';
  timeLabel: string;
  quality: string;
}

export interface ProcessReadingOptions {
  now: number;
  staleAfterSeconds: number;
  failed?: boolean;
  unbound?: boolean;
  mappingMissing?: boolean;
  loading?: boolean;
}

/** Never interpolate, coerce strings into numbers, replace missing values or infer GOOD. */
export function processReading(
  series: IotSeries | undefined,
  options: ProcessReadingOptions,
): ProcessReading {
  const result: ProcessReading = {
    status: 'missing',
    label: '缺测',
    value: '—',
    unit: series?.unit ?? '',
    time: '',
    timeSource: 'UNKNOWN',
    timeLabel: '时间来源未知',
    quality: '',
  };
  const state = (status: ProcessReading['status'], label: string) => ({
    ...result,
    status,
    label,
  });
  if (options.unbound) return state('unbound', '未绑定');
  if (options.mappingMissing) return state('mapping-missing', '映射缺失');
  if (options.failed) return state('failed', '读取失败');
  const sample = series?.points
    .filter((point) => Number.isFinite(Date.parse(point.time)))
    .toSorted((a, b) => Date.parse(a.time) - Date.parse(b.time))
    .at(-1);
  if (!sample) return options.loading ? state('loading', '读取中') : result;
  result.time = sample.time;
  result.timeSource = sample.timeSource ?? 'UNKNOWN';
  if (sample.timeSource === 'DEVICE') result.timeLabel = '采样时间';
  else if (sample.timeSource === 'RECEIVED') result.timeLabel = '接收时间';
  result.quality = sample.quality || 'UNKNOWN';
  if (!Number.isFinite(options.now) || Date.parse(sample.time) > options.now)
    return state(
      'missing',
      result.timeSource === 'UNKNOWN' ? '时间异常' : `${result.timeLabel}异常`,
    );
  const quality = result.quality.trim().toUpperCase();
  if (['BAD', 'INVALID', 'ERROR', 'DEMO'].includes(quality))
    return state('bad', '质量不良');
  if (
    sample.value === null ||
    (typeof sample.value === 'number' && !Number.isFinite(sample.value)) ||
    (typeof sample.value === 'string' && !sample.value.trim()) ||
    (typeof sample.value !== 'number' && typeof sample.value !== 'string')
  )
    return result;
  const beyondRetention =
    !Number.isFinite(options.staleAfterSeconds) ||
    options.staleAfterSeconds <= 0 ||
    options.now - Date.parse(sample.time) > options.staleAfterSeconds * 1000;
  // A reception timestamp cannot establish measurement freshness. Expiration
  // only bounds how long the UI retains that received/legacy record, including
  // during a hanging request; it does not assert the acquisition time or age.
  if (sample.timeSource !== 'DEVICE') {
    if (beyondRetention)
      return state(
        'expired',
        sample.timeSource === 'RECEIVED'
          ? '接收记录已过期，采集时间未知'
          : '时间来源未知，记录已过期',
      );
    return {
      ...result,
      status: 'unknown',
      label: '采集时间未知',
      value: formatValue({ ...sample, timestamp: sample.time }),
    };
  }
  if (beyondRetention) return state('stale', '数据陈旧');
  return {
    ...result,
    status: quality === 'GOOD' ? 'fresh' : 'unknown',
    label: quality === 'GOOD' ? '实时' : '质量未知',
    value: formatValue({ ...sample, timestamp: sample.time }),
  };
}
