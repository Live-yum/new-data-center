import { describe, expect, it } from 'vitest';
import {
  chartGeometry,
  coordinate,
  formatValue,
  gaugePercent,
  latestSample,
  numericValue,
  safeImageUrl,
  timelineGeometry,
} from './widget-model';
import type { WidgetSample } from './widget-model';
const sample = (
  value: number | string | null,
  seconds = 0,
  quality?: string,
): WidgetSample => ({
  timestamp: new Date(1700000000000 + seconds * 1000).toISOString(),
  value,
  quality,
});
describe('IoT widget data semantics', () => {
  it('preserves missing and invalid readings rather than manufacturing zero', () => {
    expect(numericValue(sample(null))).toBeNull();
    expect(numericValue(sample(''))).toBeNull();
    expect(numericValue(sample('12'))).toBeNull();
    expect(numericValue(sample(12, 0, 'bad'))).toBeNull();
    expect(numericValue(sample(Number.NaN))).toBeNull();
    expect(numericValue(sample(0))).toBe(0);
    expect(formatValue(sample(null))).toBe('—');
  });
  it('splits trend paths at absent readings and uses time rather than sample position', () => {
    const geometry = chartGeometry([
      sample(4, 0),
      sample(null, 1),
      sample(8, 2),
      sample(12, 10),
    ]);
    expect(geometry.paths).toHaveLength(2);
    expect(geometry.bars).toHaveLength(3);
    expect(geometry.bars[1]?.x).toBeCloseTo(67.2);
  });
  it('sorts readings without mutating input and retains a missing latest sample', () => {
    const values = [sample(null, 10), sample(5, 0)];
    expect(
      latestSample({ deviceId: 1, deviceName: 'A', unit: '°C', values })?.value,
    ).toBeNull();
    expect(values[0]?.value).toBeNull();
  });
  it('handles single, negative, all missing and invalid timestamp charts', () => {
    expect(chartGeometry([sample(-5)]).bars[0]?.height).toBeGreaterThan(0);
    expect(chartGeometry([sample(0)]).paths[0]).not.toContain('NaN');
    expect(chartGeometry([sample(null)]).paths).toEqual([]);
    expect(chartGeometry([{ value: 2, timestamp: 'invalid' }]).bars).toEqual(
      [],
    );
  });
  it('does not show an invented gauge reading and clamps valid ranges', () => {
    expect(gaugePercent(sample(null))).toBeNull();
    expect(gaugePercent(sample(12), 10, 10)).toBeNull();
    expect(gaugePercent(sample(20), 0, 10)).toBe(100);
    expect(gaugePercent(sample(-20), 0, 10)).toBe(0);
  });
  it('uses observed state intervals without extrapolating the final sample', () => {
    const result = timelineGeometry([
      sample('运行', 0),
      sample('待机', 2),
      sample(null, 10),
    ]);
    expect(result.map((segment) => segment.width)).toEqual([20, 80, 0]);
    expect(result[2]?.left).toBe(100);
    expect(result[2]?.color).toBe('#bec8d5');
  });
  it('allows only same-origin absolute paths for images', () => {
    expect(safeImageUrl('/assets/boiler.png')).toBe('/assets/boiler.png');
    for (const url of [
      'https://example.com/a.png',
      '//example.com/a.png',
      'javascript:alert(1)',
      'data:image/svg+xml,test',
      '/\\evil/a.png',
      '/\nevil',
    ])
      expect(safeImageUrl(url)).toBeUndefined();
    expect(coordinate(150)).toBe(100);
    expect(coordinate(Number.NaN)).toBe(50);
  });
});
