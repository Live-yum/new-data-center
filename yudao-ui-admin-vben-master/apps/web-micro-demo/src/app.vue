<script lang="ts" setup>
import type { DataCenterBridge, DataCenterProps } from '@vben/micro-apps';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import {
  createMicroAppController,
  normalizeDataCenterRoute,
} from '@vben/micro-apps';

import { loadMicroApp } from 'qiankun';

import { createDemoBridge } from './demo-bridge';

const route = useRoute();
const router = useRouter();
const container = ref<HTMLElement>();
const tenantId = ref(1);
const userName = ref('演示用户甲');
const signedIn = ref(true);
const simulateFailure = ref(false);
const pending = ref(0);
const status = ref('未挂载');
const error = ref('');
const events = ref<{ id: number; message: string }[]>([]);
const entry =
  import.meta.env.VITE_DATA_CENTER_ENTRY ||
  (import.meta.env.DEV
    ? 'http://127.0.0.1:5175/'
    : 'http://127.0.0.1:5175/micro-apps/data-center/');
const childRoute = computed(() =>
  normalizeDataCenterRoute(
    typeof route.query.view === 'string' ? `/${route.query.view}` : undefined,
  ),
);
let controller:
  | ReturnType<typeof createMicroAppController<DataCenterProps>>
  | undefined;
let lifetime = new AbortController();
let bridge: DataCenterBridge | undefined;
let sessionVersion = 0;
let generation = 0;
let eventId = 0;
let disposed = false;

function record(message: string) {
  events.value = [{ id: ++eventId, message }, ...events.value].slice(0, 20);
}

function renewSession() {
  lifetime.abort();
  lifetime = new AbortController();
  sessionVersion++;
  bridge = createDemoBridge({
    tenantId: tenantId.value,
    signal: lifetime.signal,
    fail: simulateFailure.value,
    onPending: (change) => {
      pending.value += change;
    },
  });
}

function props(): DataCenterProps {
  return {
    bridge,
    context: {
      dataSource: 'demo',
      locale: 'zh-CN',
      sessionKey: `demo:${sessionVersion}:${tenantId.value}:${userName.value}`,
      tenantId: tenantId.value,
      userName: userName.value,
    },
    route: childRoute.value,
    onNavigate: (path) => {
      if (disposed || !signedIn.value) return;
      record(`子 → 主：导航 ${path}`);
      void router.push({
        path: '/data-center',
        query: { view: normalizeDataCenterRoute(path).slice(1) },
      });
    },
  };
}

async function stop() {
  const version = ++generation;
  lifetime.abort();
  const current = controller;
  controller = undefined;
  try {
    await current?.unmount();
    if (current) record('unmount 完成：视图、路由及请求已清理');
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '卸载失败';
  }
  if (version === generation) status.value = '未挂载';
  return version;
}

async function start() {
  const version = await stop();
  if (version !== generation || disposed || !signedIn.value || !container.value)
    return;
  error.value = '';
  status.value = '加载中';
  renewSession();
  const element = container.value;
  controller = createMicroAppController<DataCenterProps>(() =>
    loadMicroApp(
      { name: 'dataCenterDemo', entry, container: element, props: props() },
      { sandbox: { styleIsolation: true } },
    ),
  );
  record('mount 开始：加载真实 web-data-center 子应用');
  try {
    await controller.mount();
    if (version === generation && !disposed) {
      status.value = '已挂载';
      record('mount 完成');
    }
  } catch (cause) {
    if (version === generation && !disposed) {
      status.value = '加载失败';
      error.value = cause instanceof Error ? cause.message : '子应用加载失败';
      record('mount 失败：可检查子应用服务后重新挂载');
    }
  }
}

async function update(message: string) {
  try {
    if (controller) {
      await controller.update(props());
      record(`update 完成：${message}`);
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '子应用更新失败';
  }
}

async function toggleSession() {
  signedIn.value = !signedIn.value;
  if (signedIn.value) {
    record('演示登录：创建新会话');
    await start();
  } else {
    record('演示退出：撤销旧 bridge');
    await stop();
  }
}

watch([tenantId, userName, simulateFailure], async () => {
  renewSession();
  await update(`会话 ${sessionVersion} / 租户 ${tenantId.value}`);
});
watch(childRoute, async () => {
  await update(`路由 ${childRoute.value}`);
});
onMounted(() => {
  void start();
});
onBeforeUnmount(() => {
  disposed = true;
  void stop();
});
</script>

<template>
  <main class="micro-demo">
    <header class="demo-heading">
      <p class="demo-eyebrow">Q I A N K U N · 3</p>
      <h1>微前端集成示例</h1>
      <p>同一个数据中心子应用，独立宿主、原生 ESM、受控 props 通信。</p>
      <p class="demo-warning">
        演示数据 · 无需 Java、数据库或账号 · qiankun 3.0.0-rc.22 预发布版本
      </p>
    </header>

    <section class="demo-controls" aria-label="宿主控制面板">
      <div class="demo-actions">
        <button type="button" :disabled="!signedIn" @click="start">
          挂载 / 重试
        </button>
        <button type="button" @click="stop">卸载子应用</button>
        <button type="button" @click="toggleSession">
          {{ signedIn ? '退出演示会话' : '登录演示会话' }}
        </button>
      </div>
      <div class="demo-fields">
        <label
          >演示租户
          <select
            v-model="tenantId"
            aria-label="演示租户"
            :disabled="!signedIn"
          >
            <option :value="1">租户甲（1）</option>
            <option :value="2">租户乙（2）</option>
          </select>
        </label>
        <label
          >演示用户
          <select
            v-model="userName"
            aria-label="演示用户"
            :disabled="!signedIn"
          >
            <option>演示用户甲</option>
            <option>演示用户乙</option>
          </select>
        </label>
        <label class="demo-checkbox"
          ><input
            v-model="simulateFailure"
            type="checkbox"
            :disabled="!signedIn"
          />模拟数据请求失败</label
        >
      </div>
      <nav class="demo-routes" aria-label="宿主路由">
        <RouterLink :to="{ path: '/data-center', query: { view: 'overview' } }"
          >宿主打开总览</RouterLink
        >
        <RouterLink
          :to="{ path: '/data-center', query: { view: 'departments' } }"
          >宿主打开部门</RouterLink
        >
        <RouterLink
          :to="{ path: '/data-center', query: { view: 'dictionaries' } }"
          >宿主打开字典</RouterLink
        >
      </nav>
      <div class="demo-state" aria-live="polite">
        <span
          >状态：<strong data-testid="status">{{ status }}</strong></span
        >
        <span
          >进行中的演示请求：<strong data-testid="pending">{{
            pending
          }}</strong></span
        >
        <span
          >子路由：<code data-testid="route">{{ childRoute }}</code></span
        >
      </div>
      <p class="demo-entry">
        子应用入口：<code>{{ entry }}</code>
      </p>
      <p v-if="error" class="demo-error" role="alert">{{ error }}</p>
    </section>

    <section class="demo-child" aria-label="微应用容器">
      <div v-if="status === '未挂载'" class="demo-empty">
        子应用已卸载。点击“挂载 / 重试”可验证再次挂载。
      </div>
      <div ref="container" data-testid="micro-container"></div>
    </section>
    <section class="demo-log" aria-label="生命周期与通信记录">
      <h2>生命周期与通信记录</h2>
      <p>
        路由修改通过 update 进入子应用，子应用导航通过 onNavigate
        回到宿主。切换会话会取消旧请求。
      </p>
      <ol>
        <li v-for="event in events" :key="event.id">
          <span>{{ event.id }}</span
          >{{ event.message }}
        </li>
      </ol>
    </section>
  </main>
</template>
