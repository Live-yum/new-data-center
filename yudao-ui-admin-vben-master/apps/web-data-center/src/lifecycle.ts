import type { App as VueApp } from 'vue';
import type { Router } from 'vue-router';

import type { DataCenterProps } from '@vben/micro-apps';

import { createApp, shallowReactive } from 'vue';
import {
  createMemoryHistory,
  createRouter,
  createWebHashHistory,
} from 'vue-router';

import { normalizeDataCenterRoute } from '@vben/micro-apps';

import App from './app.vue';
import { dataCenterKey } from './context';
import Workbench from './views/workbench.vue';
import IotWorkbench from './iot/workbench.vue';
import OilgasDemo from './iot/OilgasDemo.vue';
import ProductionScenes from './iot/ProductionScenes.vue';
import DataSources from './iot/DataSources.vue';

let app: VueApp | undefined;
let router: Router | undefined;
let removeRouteHook: (() => void) | undefined;
const state = shallowReactive<{ props: DataCenterProps }>({ props: {} });

export async function bootstrap() {
  // 每次 mount 独立创建实例，bootstrap 不持有 DOM、路由或用户会话。
}

export async function mount(props: DataCenterProps = {}) {
  await unmount();
  const container = props.container ?? document;
  const element = container.querySelector('#data-center-root');
  if (!element) throw new Error('未找到数据中心挂载节点');

  state.props = props;
  router = createRouter({
    history: props.container ? createMemoryHistory() : createWebHashHistory(),
    routes: [
      { path: '/', redirect: '/overview' },
      { path: '/overview', component: Workbench },
      ...['/devices', '/dashboards', '/widgets', '/history', '/downloads'].map(
        (path) => ({ path, component: IotWorkbench }),
      ),
      ...['separation', 'gas', 'storage', 'utilities', 'offshore'].map(
        (id) => ({ path: `/demo-${id}`, component: OilgasDemo }),
      ),
      ...['separation', 'gas', 'storage', 'utilities', 'offshore'].map(
        (id) => ({ path: `/process-${id}`, component: ProductionScenes }),
      ),
      { path: '/sources', component: DataSources },
      { path: '/departments', component: Workbench },
      { path: '/dictionaries', component: Workbench },
      { path: '/:pathMatch(.*)*', redirect: '/overview' },
    ],
  });
  removeRouteHook = router.afterEach((to, _from, failure) => {
    if (failure) return;
    if (to.path !== normalizeDataCenterRoute(state.props.route)) {
      state.props.onNavigate?.(to.path);
    }
  });
  const initialPath =
    props.route ??
    (props.container ? undefined : window.location.hash.slice(1));
  await router.replace(normalizeDataCenterRoute(initialPath));
  app = createApp(App);
  app.provide(dataCenterKey, state);
  app.use(router);
  await router.isReady();
  app.mount(element);
}

export async function update(props: DataCenterProps) {
  const nextProps = { ...state.props, ...props };
  state.props = nextProps;
  const path = normalizeDataCenterRoute(nextProps.route);
  const activeRouter = router;
  if (activeRouter && activeRouter.currentRoute.value.path !== path) {
    const failure = await activeRouter.replace(path);
    // A rejected dirty-draft navigation must restore the host URL, not leave
    // its selected tab pointing at a page the child refused to open.
    if (failure && router === activeRouter && state.props === nextProps) {
      const actual = activeRouter.currentRoute.value.path;
      state.props = { ...nextProps, route: actual };
      nextProps.onNavigate?.(actual, { replace: true });
    }
  }
}

export async function unmount() {
  app?.unmount();
  app = undefined;
  removeRouteHook?.();
  removeRouteHook = undefined;
  router?.options.history.destroy();
  router = undefined;
  state.props = {};
}
