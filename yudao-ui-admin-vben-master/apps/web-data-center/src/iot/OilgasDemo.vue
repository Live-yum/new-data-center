<script setup lang="ts">
import type { IotSeries } from '@vben/micro-apps';
import { oilgasScenes } from '@vben/micro-apps';
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { dataCenterKey } from '../context';
import ProcessDiagram from './ProcessDiagram.vue';
import WidgetView from './WidgetView.vue';
import { formatValue } from './widget-model';
import './oilgas-demo.css';
const state = inject(dataCenterKey);
const route = useRoute();
const scene = computed(
  () =>
    oilgasScenes.find((s) => `/demo-${s.id}` === route.path) ??
    oilgasScenes[0]!,
);
const enabled = computed(
  () =>
    state?.props.context?.dataSource === 'demo' &&
    !!state?.props.bridge?.iot?.demoOilgasQuery,
);
const selected = ref(0);
const metricKey = ref('');
const realtime = ref<IotSeries[]>([]);
const history = ref<IotSeries[]>([]);
const error = ref('');
const loading = ref(false);
const lastUpdated = ref('');
let generation = 0;
let request: AbortController | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
const device = computed(
  () =>
    scene.value.devices.find((d) => d.id === selected.value) ??
    scene.value.devices[0]!,
);
const metric = computed(
  () =>
    scene.value.metrics.find((m) => m.metricKey === metricKey.value) ??
    scene.value.metrics[0]!,
);
function display(series?: IotSeries) {
  const p = series?.points.at(-1);
  return formatValue(p ? { ...p, timestamp: p.time } : undefined);
}
const readings = computed(() =>
  Object.fromEntries(
    scene.value.devices.map((d) => {
      const s = realtime.value.find(
        (v) => v.deviceId === d.id && v.metricKey === d.metricKey,
      );
      return [d.id, s ? `${display(s)} ${s.unit}` : '—'];
    }),
  ),
);
const cards = computed(() =>
  scene.value.metrics
    .filter((m) => m.metricKey !== 'running_state')
    .map((m) => ({
      ...m,
      series: realtime.value.find(
        (s) => s.deviceId === device.value.id && s.metricKey === m.metricKey,
      ),
    })),
);
const trend = computed(() =>
  history.value.map((s) => ({
    deviceId: s.deviceId,
    deviceName: device.value.name,
    metricKey: s.metricKey,
    unit: s.unit,
    values: s.points.map((p) => ({
      timestamp: p.time,
      value: p.value,
      quality: p.quality,
    })),
  })),
);
const samples = computed(
  () => history.value[0]?.points.slice(-4).reverse() ?? [],
);
function stop() {
  generation++;
  request?.abort();
  clearTimeout(timer);
}
async function refresh() {
  stop();
  const version = generation;
  realtime.value = [];
  history.value = [];
  lastUpdated.value = '';
  error.value = '';
  if (!enabled.value) {
    loading.value = false;
    return;
  }
  const bridge = state!.props.bridge!.iot!;
  const controller = new AbortController();
  request = controller;
  loading.value = true;
  async function read() {
    const end = new Date();
    try {
      const [live, past] = await Promise.all([
        bridge.demoOilgasQuery!(
          'realtime',
          {
            deviceIds: scene.value.devices.map((d) => d.id),
            metricKeys: scene.value.metrics.map((m) => m.metricKey),
            aggregation: 'RAW',
          },
          controller.signal,
        ),
        bridge.demoOilgasQuery!(
          'history',
          {
            deviceIds: [device.value.id],
            metricKeys: [metricKey.value],
            aggregation: 'RAW',
            startTime: new Date(end.getTime() - 1800000).toISOString(),
            endTime: end.toISOString(),
            intervalMs: 60000,
          },
          controller.signal,
        ),
      ]);
      if (version !== generation || controller.signal.aborted) return;
      if (live.source !== 'DEMO' || past.source !== 'DEMO')
        throw new Error('演示页面拒绝非 DEMO 数据源');
      realtime.value = live.series;
      history.value = past.series;
      lastUpdated.value = live.series[0]?.points.at(-1)?.time ?? '';
      error.value = '';
    } catch (cause) {
      if (version !== generation || controller.signal.aborted) return;
      realtime.value = [];
      history.value = [];
      lastUpdated.value = '';
      error.value = cause instanceof Error ? cause.message : '演示数据读取失败';
    } finally {
      if (version === generation && !controller.signal.aborted) {
        loading.value = false;
        timer = setTimeout(() => void read(), 5000);
      }
    }
  }
  await read();
}
watch(
  () => scene.value.id,
  () => {
    selected.value = scene.value.devices[0]!.id;
    metricKey.value = scene.value.metrics[0]!.metricKey;
  },
  { immediate: true, flush: 'sync' },
);
watch(
  [
    () => state?.props.bridge,
    () => state?.props.context?.sessionKey,
    () => state?.props.context?.tenantId,
    enabled,
    () => scene.value.id,
    selected,
    metricKey,
  ],
  () => void refresh(),
  { immediate: true },
);
onBeforeUnmount(stop);
function time(value: string) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour12: false });
}
</script>
<template>
  <div v-if="!enabled" class="dc-notice" data-testid="oilgas-unavailable">
    油气工艺示例仅在演示环境提供。正式模式未接入现场油气数据，不使用模拟值替代。
  </div>
  <section
    v-else
    class="og-workspace"
    data-testid="oilgas-demo"
    :data-scene="scene.id"
    :data-selected-device="device.id"
    :aria-busy="loading"
  >
    <div class="og-toolbar">
      <nav aria-label="油气演示场景">
        <RouterLink
          v-for="s in oilgasScenes"
          :key="s.id"
          :to="`/demo-${s.id}`"
          :data-testid="`scene-${s.id}`"
          >{{ s.name }}</RouterLink
        >
      </nav>
      <span class="og-demo-badge">DEMO · 模拟数据</span>
    </div>
    <div class="og-context">
      <span>{{ scene.subtitle }}</span
      ><span data-testid="oilgas-readonly"
        >只读观测 · 每 5 秒刷新 <span class="og-live-dot"></span
      ></span>
    </div>
    <div v-if="error" class="dc-error" role="alert">{{ error }}</div>
    <div class="og-main-grid">
      <article class="og-flow-panel">
        <div class="og-panel-heading">
          <h2>工艺总览 <small>PROCESS VIEW</small></h2>
          <span>选择设备查看测点 ↗</span>
        </div>
        <ProcessDiagram
          :scene="scene"
          :selected="device.id"
          :readings="readings"
          @select="selected = $event"
        />
        <div class="og-flow-footer">
          <span><i></i> 主流程示意 <i class="og-gas"></i> 支路示意</span
          ><span>原创 SVG · 非 P&amp;ID</span>
        </div>
      </article>
      <aside class="og-device-panel">
        <div class="og-panel-heading">
          <h2>设备测点</h2>
          <span>DEMO</span>
        </div>
        <label class="og-device-select"
          >当前设备<select v-model="selected" data-testid="oilgas-device">
            <option v-for="d in scene.devices" :key="d.id" :value="d.id">
              {{ d.name }} · {{ d.code }}
            </option>
          </select></label
        >
        <div class="og-device-identity">
          <span>{{ device.code }}</span
          ><strong>{{ device.name }}</strong
          ><small
            >样例运行状态 ·
            {{
              display(
                realtime.find(
                  (s) =>
                    s.deviceId === device.id && s.metricKey === 'running_state',
                ),
              )
            }}</small
          >
        </div>
        <div v-for="card in cards" :key="card.metricKey" class="og-reading">
          <span>{{ card.name }}</span
          ><strong
            >{{ display(card.series) }}
            <small>{{ card.series?.unit ?? card.unit }}</small></strong
          >
        </div>
        <div class="og-timestamp">
          {{
            lastUpdated ? `采样 ${time(lastUpdated)} · DEMO` : '等待样例数据'
          }}
        </div>
      </aside>
    </div>
    <div class="og-bottom-grid">
      <article
        class="og-trend-panel"
        data-testid="oilgas-trend"
        :data-device-id="device.id"
        :data-metric="metricKey"
      >
        <div class="og-panel-heading">
          <h2>测点趋势 <small>最近 30 分钟 · RAW / 60 秒</small></h2>
          <select
            v-model="metricKey"
            aria-label="趋势指标"
            data-testid="oilgas-metric"
          >
            <option
              v-for="m in scene.metrics.filter(
                (v) => v.metricKey !== 'running_state',
              )"
              :key="m.metricKey"
              :value="m.metricKey"
            >
              {{ m.name }}
            </option>
          </select>
        </div>
        <WidgetView
          :widget="{ type: 'line', title: `${device.name} · ${metric.name}` }"
          :series="trend"
          :loading="loading"
        />
      </article>
      <article class="og-samples-panel" data-testid="oilgas-samples">
        <div class="og-panel-heading">
          <h2>采样明细</h2>
          <span>{{ history[0]?.unit ?? metric.unit }}</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>时间（本地）</th>
              <th>数值</th>
              <th>质量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in samples" :key="p.time">
              <td>{{ time(p.time) }}</td>
              <td>
                {{
                  formatValue({
                    timestamp: p.time,
                    value: p.value,
                    quality: p.quality,
                  })
                }}
              </td>
              <td>
                <span class="og-quality">{{ p.quality }}</span>
              </td>
            </tr>
            <tr v-if="!samples.length">
              <td colspan="3">暂无样例数据</td>
            </tr>
          </tbody>
        </table>
        <p>缺测显示 —，趋势留空，不补零。</p>
      </article>
    </div>
    <footer class="og-disclaimer" data-testid="oilgas-disclaimer">
      <strong>演示边界</strong>
      模拟数据，未连接实时库；工艺流程仅为功能示意，非工程设计，不代表现场安全状态或生产设定。无设备启停、阀门控制或复位功能。
    </footer>
  </section>
</template>
