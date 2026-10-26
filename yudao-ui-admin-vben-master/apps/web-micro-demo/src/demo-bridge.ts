import type {
  DataCenterBridge,
  DataCenterDept,
  DataCenterDictData,
} from '@vben/micro-apps';

import { createDemoIotBridge } from './demo-iot';

interface DemoBridgeOptions {
  delay?: number;
  fail?: boolean;
  onPending: (change: number) => void;
  signal: AbortSignal;
  tenantId: number;
}

/** 仅供独立演示宿主使用；不调用业务 API，也不生成或存储登录令牌。 */
export function createDemoBridge(options: DemoBridgeOptions): DataCenterBridge {
  const prefix = options.tenantId === 1 ? '演示甲' : '演示乙';
  const departments: DataCenterDept[] = [
    { id: options.tenantId * 100, name: `${prefix} · 数据中心` },
    {
      id: options.tenantId * 100 + 1,
      name: `${prefix} · 运行部`,
      parentId: options.tenantId * 100,
    },
  ];
  const dictionaries: DataCenterDictData[] = [
    { dictType: 'demo_status', label: `${prefix} · 启用`, value: '1' },
    { dictType: 'demo_status', label: `${prefix} · 停用`, value: '0' },
    { dictType: 'demo_source', label: '演示数据', value: 'demo' },
  ];

  function read<T>(data: T, signal: AbortSignal): Promise<T> {
    if (signal.aborted || options.signal.aborted)
      return Promise.reject(new DOMException('演示会话已结束', 'AbortError'));
    return new Promise((resolve, reject) => {
      options.onPending(1);
      const finish = () => {
        clearTimeout(timer);
        signal.removeEventListener('abort', cancel);
        options.signal.removeEventListener('abort', cancel);
        options.onPending(-1);
      };
      const cancel = () => {
        finish();
        reject(new DOMException('演示请求已取消', 'AbortError'));
      };
      const timer = setTimeout(() => {
        finish();
        if (options.fail)
          reject(new Error('演示请求失败：关闭故障模拟后刷新数据。'));
        else resolve(structuredClone(data));
      }, options.delay ?? 350);
      signal.addEventListener('abort', cancel, { once: true });
      options.signal.addEventListener('abort', cancel, { once: true });
    });
  }

  return {
    iot: createDemoIotBridge(options.tenantId, options.signal, read),
    getDepartments: (signal) => read(departments, signal),
    getDictionaries: (signal) => read(dictionaries, signal),
  };
}
