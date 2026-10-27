/** Test-only host. Never included in ordinary frontend builds/deployments. */
import type { DataCenterProps } from '../../packages/micro-apps/src/types';
import type { IotRead } from '../../apps/web-ele/src/views/data-center/iot-bridge';
import { createIotBridge } from '../../apps/web-ele/src/views/data-center/iot-bridge';
import { createMicroAppController } from '../../packages/micro-apps/src/lifecycle';
import { normalizeDataCenterRoute } from '../../packages/micro-apps/src/types';
import { normalizeIotError } from '../../packages/micro-apps/src/iot-errors';
import { loadMicroApp } from 'qiankun';
import { createRouter, createWebHashHistory } from 'vue-router';

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
});

const status = document.querySelector('[data-testid="harness-status"]')!;
const pendingLabel = document.querySelector('[data-testid="harness-pending"]')!;
let tenant = 7001;
let generation = 0;
let lifetime = new AbortController();
let pending = 0;
let controller:
  | ReturnType<typeof createMicroAppController<DataCenterProps>>
  | undefined;
let cachedProps: DataCenterProps | undefined;
let leaveGuard: (() => boolean) | undefined;
let childApprovedPath: string | undefined;

// Same pre-navigation contract as the formal host: do not commit a rejected
// Back/Forward update or ask twice for a child-approved navigation.
router.beforeEach((to, from) => {
  if (to.path === from.path) return true;
  if (childApprovedPath === to.path) {
    childApprovedPath = undefined;
    return true;
  }
  return leaveGuard?.() ?? true;
});
router.afterEach((to, _from, failure) => {
  if (failure) return;
  if (to.path === '/test-host-exited') void stop().catch(report);
  else void controller?.update(props()).catch(report);
});

function props(): DataCenterProps {
  if (!cachedProps) {
    const session = generation;
    const sessionTenant = tenant;
    const sessionSignal = lifetime.signal;
    const read: IotRead = async <T>(
      path: string,
      signal: AbortSignal,
      method = 'GET',
      data?: unknown,
      blob = false,
    ): Promise<T> => {
      if (signal.aborted || sessionSignal.aborted || session !== generation)
        throw new DOMException('Test session ended', 'AbortError');
      if (!path.startsWith('/iot/'))
        throw new Error('Test transport only accepts bounded IoT API');
      pendingLabel.textContent = String(++pending);
      const abort = AbortSignal.any([signal, sessionSignal]);
      try {
        const response = await fetch(`/admin-api${path}`, {
          method,
          signal: abort,
          headers: {
            Authorization: `Bearer ci-admin-tenant-${sessionTenant}`,
            'tenant-id': String(sessionTenant),
            'Content-Type': 'application/json',
          },
          body: data === undefined ? undefined : JSON.stringify(data),
        });
        if (abort.aborted || session !== generation)
          throw new DOMException('Test session switched', 'AbortError');
        if (blob) {
          if (
            !response.ok ||
            !response.headers.get('content-type')?.includes('text/csv')
          )
            throw new Error('Export download failed');
          return (await response.blob()) as T;
        }
        const result = await response.json();
        if (!response.ok || result.code !== 0) throw result;
        return result.data as T;
      } catch (error) {
        throw normalizeIotError(error);
      } finally {
        pendingLabel.textContent = String(--pending);
      }
    };
    cachedProps = {
      context: {
        dataSource: 'platform',
        locale: 'zh-CN',
        sessionKey: `fixture-${session}-${tenant}`,
        tenantId: tenant,
        userName: '自动化测试用户（非现场）',
      },
      bridge: {
        iot: createIotBridge(read),
        getDepartments: async () => [],
        getDictionaries: async () => [],
      },
      registerLeaveGuard: (guard) => {
        leaveGuard = guard;
      },
      onNavigate: (route, options) => {
        const path = normalizeDataCenterRoute(route);
        if (router.currentRoute.value.path === path) return;
        childApprovedPath = path;
        void router[options?.replace ? 'replace' : 'push'](path)
          .catch(report)
          .finally(() => {
            childApprovedPath = undefined;
          });
      },
    };
  }
  return {
    ...cachedProps,
    route: normalizeDataCenterRoute(router.currentRoute.value.path),
  };
}
async function stop() {
  lifetime.abort();
  generation++;
  cachedProps = undefined;
  leaveGuard = undefined;
  childApprovedPath = undefined;
  const current = controller;
  controller = undefined;
  await current?.unmount();
  status.textContent = '未挂载';
}
async function start() {
  await stop();
  lifetime = new AbortController();
  controller = createMicroAppController<DataCenterProps>(() =>
    loadMicroApp(
      {
        name: 'dataCenterApp',
        entry: 'http://127.0.0.1:5175/micro-apps/data-center/',
        container: document.getElementById('micro-container')!,
        props: props(),
      },
      { sandbox: { styleIsolation: true } },
    ),
  );
  await controller.mount();
  status.textContent = '已挂载';
}
function report(error: unknown) {
  status.textContent = String(error);
  console.error(error);
}
async function switchTenant(next: number) {
  lifetime.abort();
  generation++;
  tenant = next;
  cachedProps = undefined;
  lifetime = new AbortController();
  await controller?.update(props());
}
document.getElementById('tenant-one')!.onclick = () => {
  void switchTenant(7001).catch(report);
};
document.getElementById('tenant-two')!.onclick = () => {
  void switchTenant(7002).catch(report);
};
document.getElementById('unmount')!.onclick = () => {
  void stop().catch(report);
};
document.getElementById('remount')!.onclick = () => {
  void start().catch(report);
};
document.getElementById('leave-host')!.onclick = () => {
  void router.push('/test-host-exited').catch(report);
};
document.getElementById('host-storage')!.onclick = () => {
  void router.push('/process-storage').catch(report);
};
void router
  .push(location.hash.slice(1) || '/process-separation')
  .then(start)
  .catch(report);
