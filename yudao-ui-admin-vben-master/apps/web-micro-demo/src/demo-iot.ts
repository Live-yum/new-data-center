import type {
  IotBridge,
  IotDashboard,
  IotDevice,
  IotExport,
  IotMapping,
  IotQuery,
  IotResult,
  IotWidget,
} from '@vben/micro-apps';

// Match JSON transport semantics, including Vue reactive input objects.
function jsonClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

type Read = <T>(value: T, signal: AbortSignal) => Promise<T>;
interface Store {
  devices: IotDevice[];
  mappings: Record<number, IotMapping[]>;
  dashboards: IotDashboard[];
  templates: IotDashboard[];
  exports: (IotExport & { csv: string })[];
  nextId: number;
}
const metrics = [
  {
    metricKey: 'temperature_out',
    name: '出口温度',
    pointName: 'T_OUT',
    unit: '°C',
  },
  {
    metricKey: 'pressure_steam',
    name: '蒸汽压力',
    pointName: 'P_STM',
    unit: 'MPa',
  },
  { metricKey: 'flow_rate', name: '瞬时流量', pointName: 'FLOW', unit: 't/h' },
  {
    metricKey: 'running_state',
    name: '运行状态',
    pointName: 'STATE',
    unit: '',
  },
];
export function demoTemplate(): IotDashboard {
  const kinds: IotWidget['type'][] = [
    'line',
    'gauge',
    'stat',
    'timeline',
    'table',
    'bar',
    'image',
  ];
  const names = [
    '出口温度趋势',
    '蒸汽压力',
    '瞬时流量',
    '运行状态时间轴',
    '最新测点数据',
    '多设备温度比较',
    '设备测点热点',
  ];
  return {
    id: 0,
    name: '锅炉运行模板',
    version: 0,
    schemaVersion: 1,
    widgets: kinds.map((type, index) => ({
      id: `demo-${index}`,
      title: names[index]!,
      type,
      binding: {
        mode: 'currentDevice',
        deviceIds: [],
        metricKey: metrics[[0, 1, 2, 3, 0, 0, 0][index]!]!.metricKey,
      },
      grid: {
        x: [0, 6, 9, 0, 0, 6, 9][index]!,
        y: [0, 0, 0, 4, 7, 7, 7][index]!,
        w: [6, 3, 3, 12, 6, 3, 3][index]!,
        h: index === 3 ? 3 : 4,
      },
      timeWindowSeconds: 3600,
      aggregation: 'RAW',
      ...(type === 'gauge' ? { min: 0, max: 1.2 } : {}),
      ...(type === 'image'
        ? {
            imageUrl: '/images/iot-boiler.svg',
            hotspots: [
              { x: 50, y: 40, label: '出口温度', metricKey: 'temperature_out' },
            ],
          }
        : {}),
    })),
  };
}
function initial(tenantId: number): Store {
  const devices = [1, 2, 3].map((id) => ({
    id,
    name: `${tenantId === 1 ? '演示甲' : '演示乙'} · 锅炉 ${id}`,
    code: `BLR-00${id}`,
  }));
  const template = demoTemplate();
  template.id = 1;
  template.version = 1;
  template.widgets[5]!.binding = {
    mode: 'multiple',
    deviceIds: [1, 2],
    metricKey: 'temperature_out',
  };
  return {
    devices,
    mappings: Object.fromEntries(
      devices.map((d) => [
        d.id,
        metrics
          .filter((m) => d.id !== 3 || m.metricKey !== 'temperature_out')
          .map((m) => ({ ...m, pointName: `BLR${d.id}.${m.pointName}` })),
      ]),
    ),
    dashboards: [template],
    exports: [
      {
        id: 'demo-failed',
        status: 'FAILED',
        createdAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
        error: '演示失败任务：数据源暂不可用（仅示例）',
        csv: '',
      },
      {
        id: 'demo-expired',
        status: 'EXPIRED',
        createdAt: new Date(Date.now() - 172_800_000).toISOString(),
        expiresAt: new Date(Date.now() - 86_400_000).toISOString(),
        error: '演示过期任务：请重新创建导出',
        csv: '',
      },
    ],
    templates: [],
    nextId: 10,
  };
}
function csvCell(value: unknown) {
  return `"${String(value ?? '')
    .replaceAll('"', '""')
    .replace(/^[=+@-]/, '\u0027$&')}"`;
}
export function createDemoIotBridge(
  tenantId: number,
  lifetime: AbortSignal,
  read: Read,
): IotBridge {
  const key = `ndc-iot-demo-v1-tenant-${tenantId}`;
  let fallback = initial(tenantId);
  function load(): Store {
    try {
      const raw = globalThis.localStorage?.getItem(key);
      if (raw) {
        const value = JSON.parse(raw) as Store;
        if (
          Array.isArray(value.devices) &&
          Array.isArray(value.dashboards) &&
          Number.isFinite(value.nextId)
        )
          return {
            ...value,
            templates: Array.isArray(value.templates) ? value.templates : [],
          };
      }
    } catch {
      /* Preview can run with unavailable storage. */
    }
    return structuredClone(fallback);
  }
  function persist(value: Store) {
    try {
      if (!globalThis.localStorage) throw new Error('Storage unavailable');
      globalThis.localStorage.setItem(key, JSON.stringify(value));
      fallback = structuredClone(value);
    } catch {
      throw new Error('浏览器存储不可用：演示修改未持久化，请允许站点存储。');
    }
  }
  async function run<T>(
    signal: AbortSignal,
    action: (store: Store) => T,
    write = false,
  ): Promise<T> {
    await read(null, signal);
    if (signal.aborted || lifetime.aborted)
      throw new DOMException('演示会话已结束', 'AbortError');
    const store = load();
    const result = action(store);
    if (write) persist(store);
    return structuredClone(result);
  }
  function query(
    store: Store,
    mode: 'history' | 'realtime',
    q: IotQuery,
  ): IotResult {
    if (
      !q.deviceIds.length ||
      q.deviceIds.length > 10 ||
      !q.metricKeys.length ||
      q.metricKeys.length > 20 ||
      q.deviceIds.length * q.metricKeys.length > 50 ||
      q.deviceIds.some((id) => !Number.isInteger(id) || id <= 0) ||
      q.metricKeys.some((key) => !/^[A-Za-z0-9_.-]{1,64}$/.test(key)) ||
      new Set(q.deviceIds).size !== q.deviceIds.length ||
      new Set(q.metricKeys).size !== q.metricKeys.length ||
      !['RAW', 'AVG', 'MIN', 'MAX', 'LAST'].includes(q.aggregation)
    )
      throw new Error('设备、指标或聚合参数无效（最多10设备、20指标、50序列）');
    if (mode === 'realtime' && q.aggregation !== 'RAW')
      throw new Error('实时查询仅支持RAW');
    const hasOffset = (value?: string) =>
      !!value && /(?:Z|[+-]\d{2}:\d{2})$/.test(value);
    if (
      mode === 'history' &&
      (!hasOffset(q.startTime) ||
        !hasOffset(q.endTime) ||
        !Number.isInteger(q.intervalMs) ||
        q.intervalMs! < 1000)
    )
      throw new Error('历史查询必须提供带时区的起止时间和至少1000ms采样间隔');
    const end = mode === 'history' ? Date.parse(q.endTime!) : Date.now();
    const start = mode === 'history' ? Date.parse(q.startTime!) : end;
    if (
      mode === 'history' &&
      (!Number.isFinite(start) ||
        !Number.isFinite(end) ||
        start >= end ||
        end - start > 7 * 86_400_000)
    )
      throw new Error('查询窗口须大于0且不超过7天');
    const count =
      mode === 'history' ? Math.floor((end - start) / q.intervalMs!) + 1 : 1;
    if (count * q.deviceIds.length * q.metricKeys.length > 10_000)
      throw new Error('查询超过10000采样点，请增大间隔或缩小范围');
    return {
      source: 'DEMO',
      series: q.deviceIds.flatMap((deviceId) =>
        q.metricKeys.map((metricKey) => {
          const mapping = store.mappings[deviceId]?.find(
            (m) => m.metricKey === metricKey,
          );
          if (!mapping) throw new Error('设备未配置请求的统一指标映射');
          const points = Array.from({ length: count }, (_, index) => {
            const time =
              mode === 'realtime' ? end : start + index * q.intervalMs!;
            const missing =
              count > 5 && index === Math.floor(count / 2) && deviceId === 2;
            const value = missing
              ? null
              : metricKey === 'running_state'
                ? index % 12 < 9
                  ? '运行'
                  : '待机'
                : Number(
                    (
                      (metricKey === 'pressure_steam'
                        ? 0.8
                        : metricKey === 'flow_rate'
                          ? 12
                          : 82) +
                      Math.sin(time / 120_000 + deviceId) *
                        (metricKey === 'pressure_steam' ? 0.08 : 4) +
                      deviceId * (metricKey === 'pressure_steam' ? 0.01 : 0.8)
                    ).toFixed(2),
                  );
            return {
              time: new Date(time).toISOString(),
              value,
              quality: missing ? 'MISSING' : 'DEMO',
            };
          });
          if (q.aggregation !== 'RAW') {
            if (
              q.aggregation !== 'LAST' &&
              points.some((p) => typeof p.value === 'string')
            )
              throw new Error('数值聚合不支持文本指标');
            const numeric = points
              .map((p) => p.value)
              .filter((v): v is number => typeof v === 'number');
            const last = points.at(-1)!;
            const value =
              q.aggregation === 'LAST'
                ? last.value
                : numeric.length
                  ? q.aggregation === 'MIN'
                    ? Math.min(...numeric)
                    : q.aggregation === 'MAX'
                      ? Math.max(...numeric)
                      : numeric.reduce((a, b) => a + b, 0) / numeric.length
                  : null;
            return {
              deviceId,
              metricKey,
              unit: mapping?.unit || '',
              points: [{ ...last, value }],
            };
          }
          return { deviceId, metricKey, unit: mapping?.unit || '', points };
        }),
      ),
    };
  }
  return {
    devices: (s) => run(s, (d) => d.devices),
    createDevice: (device, s) =>
      run(
        s,
        (d) => {
          if (!device.name.trim() || !device.code.trim())
            throw new Error('设备名称与编码必填');
          if (d.devices.some((v) => v.code === device.code))
            throw new Error('设备编码重复');
          const item = { ...device, id: d.nextId++ };
          d.devices.push(item);
          d.mappings[item.id] = [];
          return item;
        },
        true,
      ),
    mappings: (id, s) => run(s, (d) => d.mappings[id] || []),
    saveMappings: (id, mappings, s) =>
      run(
        s,
        (d) => {
          if (!d.devices.some((v) => v.id === id))
            throw new Error('设备不存在');
          if (
            new Set(mappings.map((v) => v.metricKey)).size !== mappings.length
          )
            throw new Error('指标键不可重复');
          d.mappings[id] = jsonClone(mappings);
          return true;
        },
        true,
      ),
    dashboards: (s) => run(s, (d) => d.dashboards),
    dashboard: (id, s) =>
      run(s, (d) => {
        const item = d.dashboards.find((v) => v.id === id);
        if (!item) throw new Error('看板不存在');
        return item;
      }),
    saveDashboard: (value, s) =>
      run(
        s,
        (d) => {
          const found = d.dashboards.find((v) => v.id === value.id);
          if (value.id && !found) throw new Error('看板不存在');
          if (found && found.version !== value.version)
            throw Object.assign(
              new Error('版本冲突：看板已被其他窗口修改，请重新加载后合并。'),
              { status: 409, code: 1070000409 },
            );
          const item = jsonClone({
            ...value,
            id: found?.id || d.nextId++,
            version: (found?.version || 0) + 1,
          });
          if (found) d.dashboards[d.dashboards.indexOf(found)] = item;
          else d.dashboards.push(item);
          return item;
        },
        true,
      ),
    deleteDashboard: (id, version, s) =>
      run(
        s,
        (d) => {
          const item = d.dashboards.find((v) => v.id === id);
          if (!item || item.version !== version)
            throw Object.assign(new Error('版本冲突，请重新加载看板'), {
              status: 409,
            });
          d.dashboards = d.dashboards.filter((v) => v.id !== id);
          return true;
        },
        true,
      ),
    templates: (s) => run(s, (d) => [demoTemplate(), ...d.templates]),
    createTemplate: (value, s) =>
      run(
        s,
        (d) => {
          if (!value.name.trim() || !value.widgets.length)
            throw new Error('模板名称和组件必填');
          if (
            value.widgets.some(
              (widget) => widget.binding.mode !== 'currentDevice',
            )
          )
            throw new Error('模板仅支持当前设备绑定，请先移除固定或多设备绑定');
          const item = jsonClone({ ...value, id: d.nextId++, version: 1 });
          d.templates.push(item);
          return item;
        },
        true,
      ),
    query: (mode, q, s) => run(s, (d) => query(d, mode, q)),
    exports: (s) =>
      run(
        s,
        (d) => {
          for (const item of d.exports) {
            if (
              item.status === 'RUNNING' &&
              Date.now() - Date.parse(item.createdAt) > 3000
            )
              item.status = 'SUCCEEDED';
            if (Date.parse(item.expiresAt) < Date.now())
              item.status = 'EXPIRED';
          }
          return d.exports.map(({ csv: _, ...item }) => item);
        },
        true,
      ),
    createExport: (q, s) =>
      run(
        s,
        (d) => {
          const result = query(d, 'history', q);
          const csv = [
            'deviceId,metricKey,unit,time,value,quality',
            ...result.series.flatMap((series) =>
              series.points.map((p) =>
                [
                  series.deviceId,
                  series.metricKey,
                  series.unit,
                  p.time,
                  p.value,
                  p.quality,
                ]
                  .map(csvCell)
                  .join(','),
              ),
            ),
          ].join('\r\n');
          const item = {
            id: crypto.randomUUID(),
            status: 'RUNNING',
            createdAt: new Date().toISOString(),
            expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
            csv,
          };
          d.exports.unshift(item);
          const { csv: _, ...resultItem } = item;
          return resultItem;
        },
        true,
      ),
    cancelExport: (id, s) =>
      run(
        s,
        (d) => {
          const item = d.exports.find((v) => v.id === id);
          if (!item) throw new Error('任务不存在');
          if (item.status === 'SUCCEEDED')
            throw new Error('任务已完成，不能取消');
          item.status = 'CANCELLED';
          return true;
        },
        true,
      ),
    downloadExport: async (id, s) => {
      const csv = await run(s, (d) => {
        const item = d.exports.find((v) => v.id === id);
        if (
          !item ||
          item.status !== 'SUCCEEDED' ||
          Date.parse(item.expiresAt) < Date.now()
        )
          throw new Error('文件未完成或已过期');
        return item.csv;
      });
      return new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' });
    },
  };
}
