/** Illustrative demo catalog only; never a source of production telemetry or limits. */
export interface OilgasMetric {
  metricKey: string;
  name: string;
  unit: string;
  base: number;
  amplitude: number;
}
export interface OilgasDevice {
  id: number;
  name: string;
  code: string;
  kind: 'separator' | 'tank' | 'pump' | 'compressor' | 'tower';
  metricKey: string;
  flowUnit?: string;
  availableMetrics?: string[];
  flowBase?: number;
}
export interface OilgasScene {
  id: string;
  name: string;
  subtitle: string;
  devices: OilgasDevice[];
  metrics: OilgasMetric[];
}
const metrics = (
  pressure: number,
  temperature: number,
  flow: number,
  unit = 'm³/h',
): OilgasMetric[] => [
  {
    metricKey: 'pressure',
    name: '压力',
    unit: 'MPa',
    base: pressure,
    amplitude: pressure * 0.04,
  },
  {
    metricKey: 'temperature',
    name: '温度',
    unit: '°C',
    base: temperature,
    amplitude: 2.4,
  },
  {
    metricKey: 'flow',
    name: '流量',
    unit,
    base: flow,
    amplitude: flow * 0.06,
  },
  {
    metricKey: 'running_state',
    name: '设备状态',
    unit: '',
    base: 0,
    amplitude: 0,
  },
];
export const oilgasScenes: OilgasScene[] = [
  {
    id: 'separation',
    name: '陆地油气水分离',
    subtitle: '来液缓冲 · 三相分离 · 分路计量',
    metrics: metrics(0.65, 48, 126),
    devices: [
      {
        id: 101,
        name: '入口缓冲罐',
        code: 'SEP-V101',
        kind: 'tank',
        metricKey: 'pressure',
      },
      {
        id: 102,
        name: '三相分离器',
        code: 'SEP-V102',
        kind: 'separator',
        metricKey: 'temperature',
      },
      {
        id: 103,
        name: '原油转输泵',
        code: 'SEP-P103',
        kind: 'pump',
        metricKey: 'flow',
      },
      {
        id: 104,
        name: '采出水缓冲罐',
        code: 'SEP-V104',
        kind: 'tank',
        metricKey: 'pressure',
      },
    ],
  },
  {
    id: 'gas',
    name: '天然气处理',
    subtitle: '入口分离 · 脱水 · 压缩外输',
    metrics: metrics(3.2, 35, 8400, 'Nm³/h'),
    devices: [
      {
        id: 111,
        name: '入口分离器',
        code: 'GAS-V201',
        kind: 'separator',
        metricKey: 'pressure',
      },
      {
        id: 112,
        name: '脱水吸收塔',
        code: 'GAS-T202',
        kind: 'tower',
        metricKey: 'temperature',
      },
      {
        id: 113,
        name: '外输压缩机',
        code: 'GAS-C203',
        kind: 'compressor',
        metricKey: 'flow',
      },
      {
        id: 114,
        name: '出口缓冲罐',
        code: 'GAS-V204',
        kind: 'tank',
        metricKey: 'pressure',
      },
    ],
  },
  {
    id: 'storage',
    name: '储罐外输',
    subtitle: '原油储存 · 转输 · 出站计量',
    metrics: [
      {
        metricKey: 'level',
        name: '液位',
        unit: '%',
        base: 62,
        amplitude: 4,
      },
      ...metrics(0.42, 32, 215).slice(1),
    ],
    devices: [
      {
        id: 121,
        name: '原油储罐 A',
        code: 'STO-T301',
        kind: 'tank',
        metricKey: 'level',
      },
      {
        id: 122,
        name: '原油储罐 B',
        code: 'STO-T302',
        kind: 'tank',
        metricKey: 'level',
      },
      {
        id: 123,
        name: '外输泵 A',
        code: 'STO-P303',
        availableMetrics: ['temperature', 'flow', 'running_state'],
        kind: 'pump',
        metricKey: 'flow',
      },
      {
        id: 124,
        name: '外输泵 B',
        code: 'STO-P304',
        availableMetrics: ['temperature', 'flow', 'running_state'],
        kind: 'pump',
        metricKey: 'flow',
      },
    ],
  },
  {
    id: 'utilities',
    name: '泵站公用工程',
    subtitle: '循环水 · 缓冲 · 公用工程供给',
    metrics: metrics(0.38, 26, 320),
    devices: [
      {
        id: 131,
        name: '循环水罐',
        code: 'UTL-T401',
        kind: 'tank',
        metricKey: 'temperature',
      },
      {
        id: 132,
        name: '循环水泵 A',
        code: 'UTL-P402',
        kind: 'pump',
        metricKey: 'flow',
      },
      {
        id: 133,
        name: '循环水泵 B',
        code: 'UTL-P403',
        kind: 'pump',
        metricKey: 'pressure',
      },
      {
        id: 134,
        name: '回水缓冲罐',
        code: 'UTL-T404',
        kind: 'tank',
        metricKey: 'temperature',
      },
    ],
  },
  {
    id: 'offshore',
    name: '海上平台总览',
    subtitle: '海上来液 · 分离处理 · 油气外输',
    metrics: metrics(1.8, 54, 168),
    devices: [
      {
        id: 141,
        name: '生产分离器',
        code: 'OFF-V501',
        kind: 'separator',
        metricKey: 'pressure',
      },
      {
        id: 142,
        name: '原油缓冲罐',
        code: 'OFF-T502',
        kind: 'tank',
        metricKey: 'temperature',
      },
      {
        id: 143,
        name: '原油外输泵',
        code: 'OFF-P503',
        kind: 'pump',
        metricKey: 'flow',
      },
      {
        id: 144,
        name: '气体压缩机',
        code: 'OFF-C504',
        flowUnit: 'Nm³/h',
        flowBase: 6200,
        kind: 'compressor',
        metricKey: 'pressure',
      },
    ],
  },
];
