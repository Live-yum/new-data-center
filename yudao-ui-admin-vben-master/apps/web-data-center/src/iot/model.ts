import type {
  IotDevice,
  IotMapping,
  IotQuery,
  IotWidget,
  IotWidgetType,
} from '@vben/micro-apps';
export const widgetTypes: {
  type: IotWidgetType;
  label: string;
  description: string;
}[] = [
  { type: 'line', label: '趋势折线', description: '连续指标 · 缺测断线' },
  { type: 'bar', label: '对比柱状', description: '多设备 · 独立单位' },
  { type: 'gauge', label: '仪表盘', description: '实时值 · 自定义范围' },
  { type: 'stat', label: '数值卡', description: '最新数据与质量' },
  { type: 'timeline', label: '状态时间轴', description: '离散状态 · 间隔采样' },
  { type: 'table', label: '测点数据表', description: '时间、值和原始质量' },
  {
    type: 'image',
    label: '图片测点热点',
    description: '站内图片 · 只读趋势入口',
  },
];
export function bindingDevices(widget: IotWidget, current: number): number[] {
  return widget.binding.mode === 'currentDevice'
    ? current
      ? [current]
      : []
    : widget.binding.mode === 'fixed'
      ? widget.binding.deviceIds.slice(0, 1)
      : [...new Set(widget.binding.deviceIds)];
}
export function widgetQuery(
  widget: IotWidget,
  current: number,
  history = false,
  start?: string,
  end?: string,
): IotQuery {
  const endMs = end ? Date.parse(end) : Date.now();
  return {
    deviceIds: bindingDevices(widget, current),
    metricKeys: [
      ...new Set([
        widget.binding.metricKey,
        ...(widget.type === 'image'
          ? (widget.hotspots
              ?.map((h) => h.metricKey)
              .filter((key): key is string => !!key) ?? [])
          : []),
      ]),
    ],
    aggregation: history ? widget.aggregation : 'RAW',
    ...(history
      ? {
          intervalMs: Math.max(
            60000,
            Math.ceil(
              widget.timeWindowSeconds /
                (Math.floor(
                  10000 / Math.max(1, bindingDevices(widget, current).length),
                ) -
                  1),
            ) * 1000,
          ),
          startTime:
            start ??
            new Date(endMs - widget.timeWindowSeconds * 1000).toISOString(),
          endTime: end ?? new Date(endMs).toISOString(),
        }
      : {}),
  };
}
export function bindingIssues(
  widget: IotWidget,
  current: number,
  devices: IotDevice[],
  mappings: Record<number, IotMapping[]>,
): string[] {
  const ids = bindingDevices(widget, current);
  if (!ids.length) return ['请选择绑定设备'];
  const found = ids.map((id) => ({
    id,
    mapping: mappings[id]?.find(
      (m) => m.metricKey === widget.binding.metricKey,
    ),
  }));
  const issues = found
    .filter((v) => !v.mapping)
    .map(
      (v) =>
        `${devices.find((d) => d.id === v.id)?.name ?? v.id} 缺少指标映射 ${widget.binding.metricKey}`,
    );
  if (
    new Set(found.map((v) => v.mapping?.unit).filter((v) => v !== undefined))
      .size > 1
  )
    issues.push('单位不同：组件将按设备分图显示，不自动换算');
  return issues;
}
export function createWidget(type: IotWidgetType, index: number): IotWidget {
  return {
    id: globalThis.crypto?.randomUUID?.() ?? `widget-${Date.now()}-${index}`,
    type,
    title: widgetTypes.find((v) => v.type === type)!.label,
    binding: {
      mode: 'currentDevice',
      deviceIds: [],
      metricKey: 'temperature_out',
    },
    grid: { x: 0, y: index * 4, w: type === 'stat' ? 3 : 6, h: 4 },
    timeWindowSeconds: 3600,
    aggregation: 'RAW',
    min: 0,
    max: 100,
  };
}
export function moveGrid(
  grid: IotWidget['grid'],
  dx: number,
  dy: number,
  resize = false,
): IotWidget['grid'] {
  return resize
    ? {
        ...grid,
        w: Math.max(2, Math.min(12 - grid.x, grid.w + dx)),
        h: Math.max(3, Math.min(12, grid.h + dy)),
      }
    : {
        ...grid,
        x: Math.max(0, Math.min(12 - grid.w, grid.x + dx)),
        y: Math.max(0, grid.y + dy),
      };
}
/** Preserve all cards when a drag/resize collides: move affected neighbours down. */
export function settleLayout(widgets: IotWidget[], priorityId: string): void {
  const ordered = [...widgets].sort((a, b) =>
    a.id === priorityId ? -1 : b.id === priorityId ? 1 : a.grid.y - b.grid.y,
  );
  const placed: IotWidget[] = [];
  for (const item of ordered) {
    let collisions: IotWidget[];
    do {
      collisions = placed.filter(
        (other) =>
          item.grid.x < other.grid.x + other.grid.w &&
          item.grid.x + item.grid.w > other.grid.x &&
          item.grid.y < other.grid.y + other.grid.h &&
          item.grid.y + item.grid.h > other.grid.y,
      );
      if (collisions.length)
        item.grid.y = Math.max(
          ...collisions.map((other) => other.grid.y + other.grid.h),
        );
    } while (collisions.length);
    placed.push(item);
  }
}
