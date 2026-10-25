/** 主应用只传递显示信息和受控接口，不向子应用分发访问/刷新令牌。 */
export interface DataCenterContext {
  locale: string;
  sessionKey: string;
  tenantId: null | number;
  userName: string;
}

export interface DataCenterDept {
  id: number;
  name: string;
  parentId?: number;
}

export interface DataCenterDictData {
  dictType: string;
  id?: number;
  label: string;
  value: string;
}

/** 保持原后台请求拦截器、租户隔离和后端权限校验的统一入口。 */
export interface DataCenterBridge {
  getDepartments: (signal: AbortSignal) => Promise<DataCenterDept[]>;
  getDictionaries: (signal: AbortSignal) => Promise<DataCenterDictData[]>;
}

export interface DataCenterProps {
  bridge?: DataCenterBridge;
  container?: HTMLElement;
  context?: DataCenterContext;
  onNavigate?: (path: string) => void;
  route?: string;
}

const dataCenterRoutes = new Set([
  '/overview',
  '/departments',
  '/dictionaries',
]);

export function normalizeDataCenterRoute(path?: string): string {
  return path && dataCenterRoutes.has(path) ? path : '/overview';
}
