import type {
  IotDevice,
  IotResult,
  IotSeries,
  IotSource,
} from '@vben/micro-apps';

export const sourceStatusLabels: Record<IotSource['status'], string> = {
  CONFIGURED: '已配置 · 连接待实际读取验证',
  DISABLED: '未启用',
  NOT_CONFIGURED: '配置不完整',
};

/** Old records mean only the explicitly retained default; never choose the first source. */
export function deviceSourceId(device?: IotDevice): string {
  return device?.sourceId ?? 'default';
}

export function sourceLabel(id: string, sources: IotSource[]): string {
  const source = sources.find((item) => item.id === id);
  return source ? `${source.name} [${id}]` : `[${id}]`;
}

export function deviceSourceLabel(
  device: IotDevice | undefined,
  sources: IotSource[],
): string {
  if (!device) return '设备不可用';
  const id = deviceSourceId(device);
  return `${sourceLabel(id, sources)}${device.sourceId === undefined ? ' · 兼容默认源' : ''}`;
}

/** Catalog absence is only authoritative after a successful tenant-scoped load. */
export function deviceSourceIssue(
  device: IotDevice | undefined,
  sources: IotSource[],
  catalogLoaded: boolean,
): string {
  if (!device) return '设备不可用';
  const id = deviceSourceId(device);
  const source = sources.find((item) => item.id === id);
  if (!source)
    return catalogLoaded
      ? `数据源 [${id}] 不可用或当前租户无权访问；不会切换其他数据源。`
      : '';
  return source.status === 'CONFIGURED'
    ? ''
    : `数据源 ${sourceLabel(id, sources)} ${sourceStatusLabels[source.status]}；请由管理员在部署端处理。`;
}

export function sourceResultErrors(
  result: IotResult,
  sources: IotSource[],
): string[] {
  const errors = (result.errors ?? []).map(
    (item) =>
      `数据源 ${sourceLabel(item.sourceId, sources)}：${item.message || item.code || '读取失败'}${item.code ? ` (${item.code})` : ''}`,
  );
  if (result.partial && errors.length === 0)
    errors.push('部分数据源读取失败；缺失数据不会补零或切换数据源。');
  return errors;
}

/** Keep successful independent sources; reject misrouted identities rather than relabeling them. */
export function productionSeries(
  result: IotResult,
  devices: IotDevice[],
): IotSeries[] {
  if (
    result.source !== 'REALDB' ||
    result.series.some((item) =>
      item.points.some((point) => point.quality?.toUpperCase() === 'DEMO'),
    )
  )
    throw new Error(
      '生产页面拒绝非 REALDB 数据源或模拟质量标记，不使用演示或模拟值。',
    );
  const failed = new Set((result.errors ?? []).map((item) => item.sourceId));
  return result.series.filter((item) => {
    const device = devices.find((value) => value.id === item.deviceId);
    if (!device) return false;
    const expected = deviceSourceId(device);
    // Source-less old servers are compatible only with the legacy default.
    if ((item.sourceId ?? 'default') !== expected)
      throw new Error(
        `设备 ${device.name} 返回的数据源与绑定 [${expected}] 不符；拒绝混用同名测点。`,
      );
    return !failed.has(expected);
  });
}
