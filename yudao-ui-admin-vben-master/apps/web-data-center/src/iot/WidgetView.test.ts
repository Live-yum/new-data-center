import { afterEach, describe, expect, it } from 'vitest';
import { createApp, nextTick } from 'vue';
import WidgetView from './WidgetView.vue';
import type { WidgetConfig, WidgetSeries } from './widget-model';
const series: WidgetSeries[] = [
  {
    deviceId: 1,
    deviceName: '锅炉一',
    metricKey: 'temperature',
    unit: '°C',
    values: [{ timestamp: '2026-01-01T01:00:00Z', value: 12 }],
  },
];
let cleanup = () => {};
afterEach(() => cleanup());
async function render(
  widget: WidgetConfig,
  data = series,
  onSelectMetric?: (key: string) => void,
) {
  const node = document.createElement('div');
  document.body.append(node);
  const app = createApp(WidgetView, { widget, series: data, onSelectMetric });
  app.mount(node);
  cleanup = () => {
    app.unmount();
    node.remove();
  };
  await nextTick();
  return node;
}
describe('IoT widget renderer', () => {
  it('renders stat alias with the unit provided by the point, not a configured override', async () => {
    const node = await render({ type: 'stat', title: '温度', unit: 'MPa' });
    expect(node.textContent).toContain('12');
    expect(node.textContent).toContain('°C');
    expect(node.textContent).not.toContain('MPa');
  });
  it('separates series with mixed units', async () => {
    const node = await render({ type: 'line', title: '比较' }, [
      ...series,
      { ...series[0]!, deviceId: 2, deviceName: '压力', unit: 'MPa' },
    ]);
    expect(node.textContent).toContain('不同单位独立坐标显示');
    expect(node.querySelectorAll('svg')).toHaveLength(2);
  });
  it('renders gaps as empty, not a zero reading', async () => {
    const node = await render({ type: 'gauge', title: '仪表' }, [
      {
        ...series[0]!,
        values: [{ timestamp: '2026-01-01T01:00:00Z', value: null }],
      },
    ]);
    expect(node.textContent).toContain('—');
    expect(node.querySelector('.iot-gauge-fill')).toBeNull();
  });
  it('makes bound image hotspots emit a view-only metric selection', async () => {
    let selected = '';
    const node = await render(
      {
        type: 'image',
        title: '设备',
        imageUrl: '/device.png',
        hotspots: [{ x: 50, y: 50, label: '出口', metricKey: 'temperature' }],
      },
      series,
      (key) => {
        selected = key;
      },
    );
    const button = node.querySelector('button')!;
    expect(button.textContent).toContain('12');
    button.click();
    expect(selected).toBe('temperature');
  });
  it('rejects remote image URLs without making an image element', async () => {
    const node = await render({
      type: 'image',
      title: '设备',
      imageUrl: 'https://example.org/tracker',
    });
    expect(node.querySelector('img')).toBeNull();
    expect(node.textContent).toContain('站内图片');
  });
});
