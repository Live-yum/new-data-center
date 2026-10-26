export type WidgetType =
  | 'bar'
  | 'gauge'
  | 'image'
  | 'line'
  | 'table'
  | 'timeline'
  | 'stat'
  | 'value';
export interface WidgetConfig {
  type: WidgetType;
  title: string;
  unit?: string;
  min?: number;
  max?: number;
  imageUrl?: string;
  hotspots?: { x: number; y: number; label: string; metricKey?: string }[];
}
export interface WidgetSample {
  timestamp: string;
  value: number | string | null;
  quality?: string;
}
export interface WidgetSeries {
  deviceId: number;
  deviceName: string;
  unit: string;
  metricKey?: string;
  values: WidgetSample[];
}

export function numericValue(sample?: WidgetSample): number | null {
  if (
    !sample ||
    sample.value === null ||
    sample.quality?.toLowerCase() === 'bad'
  )
    return null;
  // Never coerce strings, empty values or booleans into measurements.
  return typeof sample.value === 'number' && Number.isFinite(sample.value)
    ? sample.value
    : null;
}
export function orderedSamples(values: WidgetSample[]): WidgetSample[] {
  return values
    .filter((sample) => Number.isFinite(Date.parse(sample.timestamp)))
    .toSorted((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp));
}
export function latestSample(series: WidgetSeries): WidgetSample | undefined {
  return orderedSamples(series.values).at(-1);
}
export function formatValue(sample?: WidgetSample): string {
  if (
    !sample ||
    sample.value === null ||
    sample.quality?.toLowerCase() === 'bad'
  )
    return '—';
  if (typeof sample.value === 'number')
    return Number.isFinite(sample.value)
      ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 }).format(
          sample.value,
        )
      : '—';
  return sample.value || '—';
}
export function gaugePercent(
  sample: WidgetSample | undefined,
  min = 0,
  max = 100,
): number | null {
  const value = numericValue(sample);
  if (
    value === null ||
    !Number.isFinite(min) ||
    !Number.isFinite(max) ||
    max <= min
  )
    return null;
  return Math.min(100, Math.max(0, ((value - min) / (max - min)) * 100));
}
/** SVG paths are split at missing/bad samples rather than filling with zero or bridging gaps. */
export function chartGeometry(values: WidgetSample[]) {
  const samples = orderedSamples(values);
  const numbers = samples
    .map(numericValue)
    .filter((value): value is number => value !== null);
  if (!numbers.length)
    return {
      paths: [] as string[],
      bars: [] as {
        x: number;
        y: number;
        height: number;
        value: number;
        timestamp: string;
      }[],
      min: 0,
      max: 1,
    };
  const min = Math.min(0, ...numbers);
  const max = Math.max(0, ...numbers);
  const span = max - min || 1;
  const start = Date.parse(samples[0]!.timestamp);
  const duration = Date.parse(samples.at(-1)!.timestamp) - start;
  const y = (value: number) => 106 - ((value - min) / span) * 92;
  const paths: string[] = [];
  const bars: {
    x: number;
    y: number;
    height: number;
    value: number;
    timestamp: string;
  }[] = [];
  let path = '';
  for (const sample of samples) {
    const value = numericValue(sample);
    if (value === null) {
      if (path) paths.push(path);
      path = '';
      continue;
    }
    const x = duration
      ? 12 + ((Date.parse(sample.timestamp) - start) / duration) * 276
      : 150;
    path += `${path ? ' L' : 'M'}${x.toFixed(2)},${y(value).toFixed(2)}`;
    bars.push({
      x,
      y: Math.min(y(value), y(0)),
      height: Math.abs(y(value) - y(0)),
      value,
      timestamp: sample.timestamp,
    });
  }
  if (path) paths.push(path);
  return { paths, bars, min, max: max === min ? max + 1 : max };
}
/** Site-local images only; never leak tenant page traffic to arbitrary origins. */
export function safeImageUrl(value?: string): string | undefined {
  if (
    !value ||
    !value.startsWith('/') ||
    value.startsWith('//') ||
    /[\\\x00-\x20]/.test(value)
  )
    return undefined;
  try {
    const url = new URL(value, 'https://local.invalid');
    return url.origin === 'https://local.invalid' ? value : undefined;
  } catch {
    return undefined;
  }
}
export function coordinate(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 50;
}

/** Recorded states occupy their observed interval; the final sample is a marker, not a prediction. */
export function timelineGeometry(values: WidgetSample[]) {
  const samples = orderedSamples(values);
  if (!samples.length) return [];
  const start = Date.parse(samples[0]!.timestamp);
  const duration = Date.parse(samples.at(-1)!.timestamp) - start;
  return samples.map((sample, index) => {
    const next = samples[index + 1];
    const interval = next
      ? Date.parse(next.timestamp) - Date.parse(sample.timestamp)
      : 0;
    let hash = 0;
    for (const char of formatValue(sample))
      hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
    return {
      sample,
      left: duration
        ? ((Date.parse(sample.timestamp) - start) / duration) * 100
        : 0,
      width: duration ? (interval / duration) * 100 : 0,
      color:
        formatValue(sample) === '—'
          ? '#bec8d5'
          : ['#00868b', '#e7a233', '#6187c9', '#8b6fbc'][hash % 4]!,
    };
  });
}
