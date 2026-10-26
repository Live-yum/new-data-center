/** Versioned transport contract. No tokens, arbitrary URLs or device-control API. */
export interface IotDevice {
  id: number;
  name: string;
  code: string;
}
export interface IotMapping {
  metricKey: string;
  name: string;
  pointName: string;
  unit: string;
}
export type IotAggregation = 'RAW' | 'AVG' | 'MIN' | 'MAX' | 'LAST';
export type IotWidgetType =
  | 'line'
  | 'bar'
  | 'gauge'
  | 'stat'
  | 'timeline'
  | 'table'
  | 'image';
export interface IotWidget {
  id: string;
  title: string;
  type: IotWidgetType;
  binding: {
    mode: 'currentDevice' | 'fixed' | 'multiple';
    deviceIds: number[];
    metricKey: string;
  };
  grid: { x: number; y: number; w: number; h: number };
  unit?: string;
  min?: number;
  max?: number;
  imageUrl?: string;
  hotspots?: { x: number; y: number; label: string; metricKey?: string }[];
  timeWindowSeconds: number;
  aggregation: IotAggregation;
}
export interface IotDashboard {
  id: number;
  name: string;
  version: number;
  schemaVersion: number;
  widgets: IotWidget[];
}
export interface IotQuery {
  deviceIds: number[];
  metricKeys: string[];
  startTime?: string;
  endTime?: string;
  intervalMs?: number;
  aggregation: IotAggregation;
}
export interface IotSeries {
  deviceId: number;
  metricKey: string;
  unit: string;
  points: { time: string; value: number | string | null; quality: string }[];
}
export interface IotResult {
  source: string;
  series: IotSeries[];
}
export interface IotExport {
  id: string;
  status: string;
  createdAt: string;
  expiresAt: string;
  error?: string;
}
export interface IotBridge {
  devices(signal: AbortSignal): Promise<IotDevice[]>;
  createDevice(
    device: { name: string; code: string },
    signal: AbortSignal,
  ): Promise<IotDevice>;
  mappings(id: number, signal: AbortSignal): Promise<IotMapping[]>;
  saveMappings(
    id: number,
    mappings: IotMapping[],
    signal: AbortSignal,
  ): Promise<unknown>;
  dashboards(signal: AbortSignal): Promise<IotDashboard[]>;
  dashboard(id: number, signal: AbortSignal): Promise<IotDashboard>;
  saveDashboard(
    value: IotDashboard,
    signal: AbortSignal,
  ): Promise<IotDashboard>;
  deleteDashboard(
    id: number,
    version: number,
    signal: AbortSignal,
  ): Promise<unknown>;
  createTemplate(value: IotDashboard, signal: AbortSignal): Promise<IotDashboard>;
  templates(signal: AbortSignal): Promise<IotDashboard[]>;
  query(
    mode: 'realtime' | 'history',
    query: IotQuery,
    signal: AbortSignal,
  ): Promise<IotResult>;
  exports(signal: AbortSignal): Promise<IotExport[]>;
  createExport(query: IotQuery, signal: AbortSignal): Promise<IotExport>;
  cancelExport(id: string, signal: AbortSignal): Promise<unknown>;
  downloadExport(id: string, signal: AbortSignal): Promise<Blob>;
}
