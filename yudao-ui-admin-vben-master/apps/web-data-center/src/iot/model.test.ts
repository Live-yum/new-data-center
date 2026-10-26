import { describe, it, expect, vi } from 'vitest';
import {
  bindingDevices,
  bindingIssues,
  createWidget,
  moveGrid,
  settleLayout,
  widgetQuery,
} from './model';
describe('IoT binding and layout', () => {
  it('currentDevice follows selection; fixed and multiple never do', () => {
    const w = createWidget('line', 0);
    expect(bindingDevices(w, 9)).toEqual([9]);
    w.binding = { mode: 'fixed', deviceIds: [1, 2], metricKey: 't' };
    expect(bindingDevices(w, 9)).toEqual([1]);
    w.binding.mode = 'multiple';
    expect(bindingDevices(w, 9)).toEqual([1, 2]);
  });
  it('reports missing mappings without synthetic values', () => {
    const w = createWidget('line', 0);
    expect(bindingIssues(w, 3, [{ id: 3, name: 'D', code: 'd' }], {})).toEqual([
      'D 缺少指标映射 temperature_out',
    ]);
  });
  it('clamps grid and keeps resizing inside 12 columns', () => {
    expect(moveGrid({ x: 10, y: 0, w: 2, h: 3 }, 30, -4)).toEqual({
      x: 10,
      y: 0,
      w: 2,
      h: 3,
    });
    expect(moveGrid({ x: 10, y: 0, w: 2, h: 3 }, 9, -9, true)).toEqual({
      x: 10,
      y: 0,
      w: 2,
      h: 3,
    });
  });
  it('uses offset-aware UTC history windows and RAW realtime', () => {
    const w = createWidget('line', 0);
    w.aggregation = 'AVG';
    expect(widgetQuery(w, 1).aggregation).toBe('RAW');
    expect(widgetQuery(w, 1, true).startTime).toMatch(/Z$/);
    expect(widgetQuery(w, 1, true).intervalMs).toBe(60000);
  });
});

it('moves colliding neighbours down without changing priority grid', () => {
  const a = createWidget('line', 0),
    b = createWidget('line', 1);
  b.grid = { ...a.grid };
  const desired = { ...a.grid };
  settleLayout([a, b], a.id);
  expect(a.grid).toEqual(desired);
  expect(b.grid.y).toBe(a.grid.y + a.grid.h);
});

it('seven-day window captures the clock once, never seven days plus a millisecond', () => {
  const now = vi
    .spyOn(Date, 'now')
    .mockReturnValueOnce(1800000000000)
    .mockReturnValue(1800000000001);
  try {
    const widget = createWidget('line', 0);
    widget.timeWindowSeconds = 604800;
    const query = widgetQuery(widget, 1, true);
    expect(Date.parse(query.endTime!) - Date.parse(query.startTime!)).toBe(
      604800000,
    );
    expect(query.endTime).toBe(new Date(1800000000000).toISOString());
    expect(now).toHaveBeenCalledTimes(1);
  } finally {
    now.mockRestore();
  }
});
