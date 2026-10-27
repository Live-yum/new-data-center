import type { IotBridge } from '@vben/micro-apps';

/** Host-owned transport retains authentication, tenant and lifecycle enforcement. */
export type IotRead = <T>(
  path: string,
  signal: AbortSignal,
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE',
  data?: unknown,
  blob?: boolean,
) => Promise<T>;

export function createIotBridge(read: IotRead): IotBridge {
  const id = (value: number | string) => encodeURIComponent(String(value));
  return {
    sources: (signal) => read('/iot/sources', signal),
    processScenes: (signal) => read('/iot/process-pages', signal),
    processScene: (pageId, signal) =>
      read(`/iot/process-pages/${id(pageId)}`, signal),
    saveProcessScene: (value, signal) =>
      read(
        value.id ? `/iot/process-pages/${id(value.id)}` : '/iot/process-pages',
        signal,
        value.id ? 'PUT' : 'POST',
        value,
      ),
    deleteProcessScene: (pageId, version, signal) =>
      read(
        `/iot/process-pages/${id(pageId)}?version=${id(version)}`,
        signal,
        'DELETE',
      ),
    devices: (signal) => read('/iot/devices', signal),
    createDevice: (value, signal) =>
      read('/iot/devices', signal, 'POST', value),
    mappings: (deviceId, signal) =>
      read(`/iot/devices/${id(deviceId)}/mappings`, signal),
    saveMappings: (deviceId, value, signal) =>
      read(`/iot/devices/${id(deviceId)}/mappings`, signal, 'PUT', value),
    dashboards: (signal) => read('/iot/dashboards', signal),
    dashboard: (dashboardId, signal) =>
      read(`/iot/dashboards/${id(dashboardId)}`, signal),
    saveDashboard: (value, signal) =>
      read(
        value.id ? `/iot/dashboards/${id(value.id)}` : '/iot/dashboards',
        signal,
        value.id ? 'PUT' : 'POST',
        value,
      ),
    deleteDashboard: (dashboardId, version, signal) =>
      read(
        `/iot/dashboards/${id(dashboardId)}?version=${id(version)}`,
        signal,
        'DELETE',
      ),
    templates: (signal) => read('/iot/templates', signal),
    createTemplate: (value, signal) =>
      read('/iot/templates', signal, 'POST', value),
    query: (mode, value, signal) =>
      read(
        mode === 'history' ? '/iot/query/history' : '/iot/query/realtime',
        signal,
        'POST',
        value,
      ),
    exports: (signal) => read('/iot/exports', signal),
    createExport: (value, signal) =>
      read('/iot/exports', signal, 'POST', value),
    cancelExport: (exportId, signal) =>
      read(`/iot/exports/${id(exportId)}/cancel`, signal, 'POST'),
    downloadExport: (exportId, signal) =>
      read(`/iot/exports/${id(exportId)}/file`, signal, 'GET', undefined, true),
  };
}
