<script lang="ts" setup>
import type {
  DataCenterBridge,
  DataCenterDept,
  DataCenterDictData,
  DataCenterProps,
} from '@vben/micro-apps';

import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
} from 'vue-router';

import { Page } from '@vben/common-ui';
import {
  createMicroAppController,
  normalizeDataCenterRoute,
  normalizeIotError,
} from '@vben/micro-apps';
import { preferences } from '@vben/preferences';
import { useAccessStore, useUserStore } from '@vben/stores';

import { ElAlert, ElButton } from 'element-plus';

import { requestClient } from '#/api/request';
import { createIotBridge } from './iot-bridge';

const route = useRoute();
const router = useRouter();
const accessStore = useAccessStore();
const userStore = useUserStore();
const container = ref<HTMLElement>();
const loading = ref(false);
const error = ref('');
let controller:
  | ReturnType<typeof createMicroAppController<DataCenterProps>>
  | undefined;
let active = false;
let lifetime = new AbortController();
let startVersion = 0;
let bridgeSession: string | undefined;
let bridge: DataCenterBridge | undefined;
let childLeaveGuard: (() => boolean) | undefined;
let childApprovedPath: string | undefined;
onBeforeRouteLeave(() => childLeaveGuard?.() ?? true);
onBeforeRouteUpdate((to, from) => {
  if (to.query.view === from.query.view) return true;
  const target = normalizeDataCenterRoute(
    typeof to.query.view === 'string' ? `/${to.query.view}` : undefined,
  );
  if (childApprovedPath === target) {
    childApprovedPath = undefined;
    return true;
  }
  return childLeaveGuard?.() ?? true;
});

function sessionKey() {
  return JSON.stringify([
    !!accessStore.accessToken,
    userStore.userInfo?.userId ?? userStore.userInfo?.id,
    accessStore.tenantId,
    accessStore.visitTenantId,
  ]);
}

/** Only bridge-owned paths/methods are exposed; original auth and tenant interceptors remain in control. */
async function read<T>(
  path: string,
  signal: AbortSignal,
  session: string,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET',
  data?: unknown,
  blob = false,
): Promise<T> {
  if (
    !accessStore.accessToken ||
    session !== sessionKey() ||
    signal.aborted ||
    lifetime.signal.aborted
  ) {
    throw new DOMException('当前会话已结束', 'AbortError');
  }
  const abort = new AbortController();
  const lifetimeSignal = lifetime.signal;
  const cancel = () => abort.abort();
  signal.addEventListener('abort', cancel, { once: true });
  lifetimeSignal.addEventListener('abort', cancel, { once: true });
  try {
    const result = await requestClient.request<T>(path, {
      method,
      data,
      signal: abort.signal,
      ...(blob
        ? { responseType: 'blob' as const, responseReturn: 'body' as const }
        : {}),
    });
    if (abort.signal.aborted || session !== sessionKey())
      throw new DOMException('会话已切换', 'AbortError');
    return result;
  } catch (cause) {
    throw normalizeIotError(cause);
  } finally {
    signal.removeEventListener('abort', cancel);
    lifetimeSignal.removeEventListener('abort', cancel);
  }
}

function props(): DataCenterProps {
  const session = sessionKey();
  if (!bridge || bridgeSession !== session) {
    bridgeSession = session;
    bridge = {
      iot: createIotBridge((path, signal, method, data, blob) =>
        read(path, signal, session, method, data, blob),
      ),
      getDepartments: (signal) =>
        read<DataCenterDept[]>('/system/dept/simple-list', signal, session),
      getDictionaries: (signal) =>
        read<DataCenterDictData[]>(
          '/system/dict-data/simple-list',
          signal,
          session,
        ),
    };
  }
  return {
    context: {
      dataSource: 'platform',
      locale: preferences.app.locale,
      sessionKey: session,
      tenantId: accessStore.visitTenantId ?? accessStore.tenantId,
      userName:
        userStore.userInfo?.nickname ?? userStore.userInfo?.username ?? '',
    },
    bridge,
    route: normalizeDataCenterRoute(
      typeof route.query.view === 'string' ? `/${route.query.view}` : undefined,
    ),
    registerLeaveGuard: (guard) => {
      if (active && session === sessionKey()) childLeaveGuard = guard;
    },
    onNavigate: (path, options) => {
      if (active && accessStore.accessToken) {
        const view = normalizeDataCenterRoute(path).slice(1);
        if (route.query.view !== view) {
          childApprovedPath = normalizeDataCenterRoute(path);
          void router[options?.replace ? 'replace' : 'push']({
            path: '/data-center',
            query: { view },
          }).finally(() => {
            childApprovedPath = undefined;
          });
        }
      }
    },
  };
}

async function stop() {
  childLeaveGuard = undefined;
  childApprovedPath = undefined;
  const version = ++startVersion;
  lifetime.abort();
  const current = controller;
  controller = undefined;
  try {
    await current?.unmount();
  } catch (cause) {
    console.error('数据中心卸载失败', cause);
  }
  return version;
}

async function start() {
  // 必须使用本次 stop 同步取得的版本，避免并发重试等待卸载后复用最新版本。
  const version = await stop();
  if (
    version !== startVersion ||
    !active ||
    !container.value ||
    !accessStore.accessToken
  )
    return;
  lifetime = new AbortController();
  loading.value = true;
  error.value = '';
  try {
    const { loadMicroApp } = await import('qiankun');
    if (!active || version !== startVersion || !container.value) return;
    const config = import.meta.env.PROD
      ? (window._VBEN_ADMIN_PRO_APP_CONF_ ?? {})
      : import.meta.env;
    const entry =
      config.VITE_GLOB_DATA_CENTER_ENTRY ||
      (import.meta.env.DEV
        ? 'http://localhost:5175/'
        : '/micro-apps/data-center/');
    const element = container.value;
    controller = createMicroAppController<DataCenterProps>(() =>
      loadMicroApp(
        {
          name: 'dataCenterApp',
          entry,
          container: element,
          props: props(),
        },
        {
          sandbox: { styleIsolation: true },
        },
      ),
    );
    await controller.mount();
  } catch (cause) {
    if (active && version === startVersion)
      error.value = cause instanceof Error ? cause.message : '数据中心加载失败';
  } finally {
    if (version === startVersion) loading.value = false;
  }
}

watch(sessionKey, async () => {
  childLeaveGuard = undefined;
  childApprovedPath = undefined;
  lifetime.abort();
  if (!accessStore.accessToken) {
    await stop();
    return;
  }
  lifetime = new AbortController();
  try {
    if (controller) await controller.update(props());
    else if (active) await start();
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : '数据中心会话更新失败';
  }
});
watch(
  () => [route.query.view, preferences.app.locale],
  async () => {
    try {
      await controller?.update(props());
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '数据中心更新失败';
    }
  },
);
onMounted(() => {
  active = true;
  void start();
});
onBeforeUnmount(() => {
  active = false;
  void stop();
});
</script>

<template>
  <Page>
    <ElAlert
      v-if="loading"
      title="正在加载数据中心…"
      type="info"
      :closable="false"
      class="mb-4"
    />
    <div v-if="error" class="mb-4">
      <ElAlert :title="error" type="error" :closable="false" class="mb-2" />
      <ElButton @click="start">重新加载</ElButton>
    </div>
    <div ref="container" class="min-h-[520px]" />
  </Page>
</template>
