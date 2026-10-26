<script setup lang="ts">
import type {
  IotDashboard,
  IotDevice,
  IotMapping,
  IotWidget,
  IotWidgetType,
  IotSeries,
  IotExport,
  IotAggregation,
} from '@vben/micro-apps';
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue';
import { normalizeIotError } from '@vben/micro-apps';
import { useRoute, useRouter } from 'vue-router';
import { dataCenterKey } from '../context';
import {
  bindingDevices,
  bindingIssues,
  createWidget,
  moveGrid,
  settleLayout,
  widgetQuery,
  widgetTypes,
} from './model';
import WidgetView from './WidgetView.vue';
const state = inject(dataCenterKey)!;
const route = useRoute();
const router = useRouter();
const devices = ref<IotDevice[]>([]),
  mappings = ref<Record<number, IotMapping[]>>({}),
  dashboards = ref<IotDashboard[]>([]),
  templates = ref<IotDashboard[]>([]),
  jobs = ref<IotExport[]>([]);
const currentDevice = ref(0),
  selectedDashboard = ref(0),
  board = ref<IotDashboard>(),
  editing = ref(false),
  dirty = ref(false),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  conflict = ref(false),
  notice = ref('');
const series = ref<Record<string, IotSeries[]>>({}),
  widgetErrors = ref<Record<string, string>>({});
const search = ref(''),
  gallery = ref(false),
  draft = ref<IotWidget>(),
  mappingDraft = ref<IotMapping[]>([]),
  newName = ref(''),
  newCode = ref('');
const historyMetric = ref('temperature_out'),
  historyInterval = ref(60),
  historyDevices = ref<number[]>([]),
  historyStart = ref(localTime(Date.now() - 3600000)),
  historyEnd = ref(localTime(Date.now())),
  historyAggregation = ref<IotAggregation>('RAW'),
  historySeries = ref<IotSeries[]>([]),
  historyLoading = ref(false);
let lifetime = new AbortController();
let reads = new AbortController();
let historyRequest: AbortController | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
let gestureCleanup: (() => void) | undefined;
const bridge = computed(() => state.props.bridge?.iot);
const currentMappings = computed(
  () => mappings.value[currentDevice.value] ?? [],
);
const visibleDevices = computed(() =>
  devices.value.filter((d) =>
    `${d.name} ${d.code}`.toLowerCase().includes(search.value.toLowerCase()),
  ),
);
const metrics = computed(() =>
  Array.from(
    new Map(
      Object.values(mappings.value)
        .flat()
        .map((m) => [m.metricKey, m]),
    ).values(),
  ),
);
const draftIssues = computed(() =>
  draft.value
    ? bindingIssues(
        draft.value,
        currentDevice.value,
        devices.value,
        mappings.value,
      )
    : [],
);
function localTime(value: number) {
  const d = new Date(value);
  return new Date(value - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
}
function message(cause: unknown) {
  return normalizeIotError(cause).message;
}
function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}
function clearRequests() {
  lifetime.abort();
  reads.abort();
  historyRequest?.abort();
  clearTimeout(timer);
  gestureCleanup?.();
}
async function initialize() {
  clearRequests();
  lifetime = new AbortController();
  const signal = lifetime.signal;
  devices.value = [];
  mappings.value = {};
  dashboards.value = [];
  templates.value = [];
  jobs.value = [];
  board.value = undefined;
  series.value = {};
  historySeries.value = [];
  currentDevice.value = 0;
  selectedDashboard.value = 0;
  historyDevices.value = [];
  draft.value = undefined;
  gallery.value = false;
  editing.value = false;
  dirty.value = false;
  error.value = '';
  conflict.value = false;
  notice.value = '';
  busy.value = false;
  historyLoading.value = false;
  if (!bridge.value) {
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const api = bridge.value;
    const [ds, bs, ts] = await Promise.all([
      api.devices(signal),
      api.dashboards(signal),
      api.templates(signal),
    ]);
    if (signal.aborted) return;
    const mapEntries = await Promise.all(
      ds.map(async (d) => [d.id, await api.mappings(d.id, signal)] as const),
    );
    if (signal.aborted) return;
    devices.value = ds;
    mappings.value = Object.fromEntries(mapEntries);
    dashboards.value = bs;
    templates.value = ts;
    currentDevice.value = ds[0]?.id ?? 0;
    historyDevices.value = ds[0] ? [ds[0].id] : [];
    selectedDashboard.value = bs[0]?.id ?? 0;
    board.value = bs[0] ? clone(bs[0]) : undefined;
    mappingDraft.value = clone(currentMappings.value);
    await refresh();
  } catch (cause) {
    if (!signal.aborted) error.value = message(cause);
  } finally {
    if (!signal.aborted) loading.value = false;
  }
}
async function action(work: (signal: AbortSignal) => Promise<void>) {
  if (busy.value) return;
  gestureCleanup?.();
  const signal = lifetime.signal;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await work(signal);
  } catch (cause) {
    if (!signal.aborted) {
      error.value = message(cause);
      if (
        normalizeIotError(cause).code === 1070000409 ||
        /409|version|版本|冲突|conflict/i.test(error.value)
      )
        conflict.value = true;
    }
  } finally {
    if (!signal.aborted) busy.value = false;
  }
}
async function refresh() {
  clearTimeout(timer);
  reads.abort();
  reads = new AbortController();
  const request = reads;
  const signal = request.signal;
  const cancel = () => request.abort();
  const sessionSignal = lifetime.signal;
  sessionSignal.addEventListener('abort', cancel, { once: true });
  const api = bridge.value;
  if (!api || sessionSignal.aborted) return;
  try {
    if (route.path === '/downloads') {
      const list = await api.exports(signal);
      if (!signal.aborted) jobs.value = list;
    } else if (route.path === '/dashboards' && board.value) {
      await Promise.all(
        board.value.widgets.map(async (widget) => {
          if (widget.type === 'image' && widget.binding.mode === 'multiple') {
            series.value[widget.id] = [];
            widgetErrors.value[widget.id] =
              '图片热点仅支持当前或固定单设备；请修改绑定';
            return;
          }
          const ids = bindingDevices(widget, currentDevice.value);
          const missing =
            ids.length === 0 ||
            ids.some(
              (id) =>
                !mappings.value[id]?.some(
                  (m) => m.metricKey === widget.binding.metricKey,
                ),
            );
          if (missing) {
            series.value[widget.id] = [];
            widgetErrors.value[widget.id] = '缺少设备或指标映射；不会补零';
            return;
          }
          try {
            const history = ['line', 'bar', 'timeline'].includes(widget.type);
            const result = await api.query(
              history ? 'history' : 'realtime',
              widgetQuery(widget, currentDevice.value, history),
              signal,
            );
            if (!signal.aborted) {
              series.value[widget.id] = result.series;
              widgetErrors.value[widget.id] = '';
            }
          } catch (cause) {
            if (!signal.aborted) {
              series.value[widget.id] = [];
              widgetErrors.value[widget.id] = message(cause);
            }
          }
        }),
      );
    }
  } catch (cause) {
    if (!signal.aborted) error.value = message(cause);
  } finally {
    sessionSignal.removeEventListener('abort', cancel);
    if (!signal.aborted && !sessionSignal.aborted)
      timer = setTimeout(() => void refresh(), 10000);
  }
}
function displaySeries(items: IotSeries[] = []) {
  return items.map((s) => ({
    deviceId: s.deviceId,
    metricKey: s.metricKey,
    deviceName: `${devices.value.find((d) => d.id === s.deviceId)?.name ?? s.deviceId} · ${s.metricKey}`,
    unit: s.unit,
    values: s.points.map((p) => ({
      timestamp: p.time,
      value: p.value,
      quality: p.quality,
    })),
  }));
}
async function selectBoard() {
  if (dirty.value && !window.confirm('放弃未保存的看板修改？')) {
    selectedDashboard.value = board.value?.id ?? 0;
    return;
  }
  const id = selectedDashboard.value;
  await action(async (signal) => {
    const found = await bridge.value!.dashboard(id, signal);
    if (!signal.aborted) {
      board.value = clone(found);
      dashboards.value = dashboards.value.map((item) =>
        item.id === found.id ? clone(found) : item,
      );
      editing.value = false;
      dirty.value = false;
      conflict.value = false;
      series.value = {};
      await refresh();
    }
  });
}
function beginNew(template?: IotDashboard) {
  if (busy.value) return;
  if (dirty.value && !window.confirm('放弃未保存的修改？')) return;
  board.value = {
    id: 0,
    name: template ? `${template.name} 副本` : '新建设备看板',
    version: 0,
    schemaVersion: 1,
    widgets: clone(template?.widgets ?? []),
  };
  selectedDashboard.value = 0;
  editing.value = true;
  dirty.value = true;
  series.value = {};
  void router.push('/dashboards');
  void refresh();
}
async function saveBoard() {
  if (!board.value) return;
  gestureCleanup?.();
  const value = clone(board.value);
  if (!value.name.trim()) {
    error.value = '请输入看板名称';
    return;
  }
  await action(async (signal) => {
    const saved = await bridge.value!.saveDashboard(value, signal);
    if (signal.aborted) return;
    board.value = clone(saved);
    selectedDashboard.value = saved.id;
    const list = await bridge.value!.dashboards(signal);
    if (signal.aborted) return;
    dashboards.value = list;
    dirty.value = false;
    conflict.value = false;
    notice.value = '已保存，可刷新恢复';
    await refresh();
  });
}
async function saveTemplate() {
  if (!board.value) return;
  if (board.value.widgets.some((w) => w.binding.mode !== 'currentDevice')) {
    error.value =
      '模板只允许“当前设备”绑定，请先修改固定设备/设备集合组件。原看板不会改变。';
    return;
  }
  const value = {
    ...clone(board.value),
    id: 0,
    version: 0,
    name: `${board.value.name} 模板`,
  };
  await action(async (signal) => {
    await bridge.value!.createTemplate(value, signal);
    if (signal.aborted) return;
    const list = await bridge.value!.templates(signal);
    if (signal.aborted) return;
    templates.value = list;
    notice.value = '模板已保存，可在下方应用为新看板';
  });
}
async function removeBoard() {
  if (
    !board.value?.id ||
    !window.confirm(`删除看板“${board.value.name}”？此操作不删除设备。`)
  )
    return;
  const { id, version } = board.value;
  await action(async (signal) => {
    await bridge.value!.deleteDashboard(id, version, signal);
    if (signal.aborted) return;
    const list = await bridge.value!.dashboards(signal);
    if (signal.aborted) return;
    dashboards.value = list;
    board.value = list[0] ? clone(list[0]) : undefined;
    selectedDashboard.value = list[0]?.id ?? 0;
    dirty.value = false;
    editing.value = false;
    await refresh();
  });
}
function addWidget(type: IotWidgetType) {
  if (busy.value) return;
  if (!board.value) beginNew();
  draft.value = createWidget(type, board.value?.widgets.length ?? 0);
  gallery.value = false;
  void router.push('/dashboards');
}
function saveBinding() {
  if (busy.value) return;
  if (!draft.value || !board.value) return;
  const w = draft.value;
  if (w.type === 'image' && w.binding.mode === 'multiple') {
    error.value = '图片热点仅支持当前设备或固定单设备';
    return;
  }
  if (!['line', 'bar'].includes(w.type)) w.aggregation = 'RAW';
  if (
    !w.title.trim() ||
    !w.binding.metricKey ||
    !bindingDevices(w, currentDevice.value).length
  ) {
    error.value = '请填写标题、指标和绑定设备';
    return;
  }
  if (
    w.type === 'image' &&
    w.imageUrl &&
    (!w.imageUrl.startsWith('/') ||
      w.imageUrl.startsWith('//') ||
      w.imageUrl.includes('\\') ||
      w.imageUrl.includes('..'))
  ) {
    error.value = '图片仅支持站内 / 路径';
    return;
  }
  if (
    !Number.isInteger(w.timeWindowSeconds) ||
    w.timeWindowSeconds < 1 ||
    w.timeWindowSeconds > 604800
  ) {
    error.value = '历史窗口必须在1到604800秒之间';
    return;
  }
  if (w.min !== undefined && w.max !== undefined && w.min >= w.max) {
    error.value = '量程最大值必须大于最小值';
    return;
  }
  if (w.binding.mode === 'currentDevice') w.binding.deviceIds = [];
  else w.binding.deviceIds = bindingDevices(w, currentDevice.value);
  if (!w.imageUrl) delete w.imageUrl;
  const index = board.value.widgets.findIndex((v) => v.id === w.id);
  if (index < 0) board.value.widgets.push(clone(w));
  else board.value.widgets[index] = clone(w);
  settleLayout(board.value.widgets, w.id);
  draft.value = undefined;
  editing.value = true;
  dirty.value = true;
  void refresh();
}
function deleteWidget(id: string) {
  if (busy.value) return;
  if (board.value) {
    board.value.widgets = board.value.widgets.filter((w) => w.id !== id);
    dirty.value = true;
  }
}
function gesture(event: PointerEvent, widget: IotWidget, resize = false) {
  if (busy.value || !editing.value || event.button !== 0) return;
  event.preventDefault();
  gestureCleanup?.();
  const target = event.currentTarget as HTMLElement;
  const grid = target.closest('.iot-grid') as HTMLElement;
  const start = clone(widget.grid),
    x = event.clientX,
    y = event.clientY;
  const width = grid.getBoundingClientRect().width / 12;
  const originalGrids = board.value!.widgets.map((item) => ({
    id: item.id,
    grid: clone(item.grid),
  }));
  const move = (e: PointerEvent) => {
    if (busy.value) return;
    for (const item of board.value?.widgets ?? []) {
      const original = originalGrids.find((value) => value.id === item.id);
      if (original) item.grid = clone(original.grid);
    }
    widget.grid = moveGrid(
      start,
      Math.round((e.clientX - x) / width),
      Math.round((e.clientY - y) / 70),
      resize,
    );
    settleLayout(board.value!.widgets, widget.id);
    dirty.value = true;
  };
  const stop = () => {
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', stop);
    window.removeEventListener('pointercancel', stop);
    gestureCleanup = undefined;
  };
  gestureCleanup = stop;
  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', stop, { once: true });
  window.addEventListener('pointercancel', stop, { once: true });
}
function keyboardGrid(event: KeyboardEvent, widget: IotWidget, resize = false) {
  if (busy.value) return;
  const d: Record<string, number[]> = {
    ArrowLeft: [-1, 0],
    ArrowRight: [1, 0],
    ArrowUp: [0, -1],
    ArrowDown: [0, 1],
  };
  const delta = d[event.key];
  if (delta) {
    event.preventDefault();
    widget.grid = moveGrid(widget.grid, delta[0]!, delta[1]!, resize);
    settleLayout(board.value!.widgets, widget.id);
    dirty.value = true;
  }
}
function historyQuery() {
  if (!historyDevices.value.length || !historyMetric.value)
    throw new Error('请选择设备与指标');
  const start = new Date(historyStart.value),
    end = new Date(historyEnd.value);
  if (
    !Number.isFinite(start.getTime()) ||
    !Number.isFinite(end.getTime()) ||
    start >= end
  )
    throw new Error('请选择有效起止时间');
  if (end.getTime() - start.getTime() > 604800000)
    throw new Error('历史窗口不能超过7天');
  if (!Number.isInteger(historyInterval.value) || historyInterval.value < 1)
    throw new Error('采样间隔至少1秒');
  if (
    (Math.floor(
      (end.getTime() - start.getTime()) / (historyInterval.value * 1000),
    ) +
      1) *
      historyDevices.value.length >
    10000
  )
    throw new Error('超过10000采样点，请增大采样间隔或缩小范围');
  return {
    intervalMs: historyInterval.value * 1000,
    deviceIds: [...historyDevices.value],
    metricKeys: [historyMetric.value],
    startTime: start.toISOString(),
    endTime: end.toISOString(),
    aggregation: historyAggregation.value,
  };
}
async function runHistory() {
  historyRequest?.abort();
  const request = new AbortController();
  historyRequest = request;
  const signal = request.signal;
  const session = lifetime.signal;
  const cancel = () => request.abort();
  session.addEventListener('abort', cancel, { once: true });
  historyLoading.value = true;
  historySeries.value = [];
  error.value = '';
  try {
    const result = await bridge.value!.query('history', historyQuery(), signal);
    if (!signal.aborted) historySeries.value = result.series;
  } catch (cause) {
    if (!signal.aborted) error.value = message(cause);
  } finally {
    session.removeEventListener('abort', cancel);
    if (historyRequest === request) historyLoading.value = false;
  }
}
function cancelHistory() {
  historyRequest?.abort();
  historyLoading.value = false;
}
function openMetric(metric: string, widget?: IotWidget) {
  historyMetric.value = metric;
  historyDevices.value = widget
    ? bindingDevices(widget, currentDevice.value)
    : [currentDevice.value];
  void router.push('/history');
}
async function exportHistory() {
  await action(async (signal) => {
    await bridge.value!.createExport(historyQuery(), signal);
    if (!signal.aborted) {
      notice.value = '下载任务已创建';
      await router.push('/downloads');
      await refresh();
    }
  });
}
async function download(job: IotExport) {
  await action(async (signal) => {
    const blob = await bridge.value!.downloadExport(job.id, signal);
    if (signal.aborted) return;
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `iot-export-${job.id}.csv`;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
}
async function createDevice() {
  if (!newName.value.trim() || !newCode.value.trim()) {
    error.value = '请输入设备名称和编码';
    return;
  }
  await action(async (signal) => {
    const d = await bridge.value!.createDevice(
      { name: newName.value, code: newCode.value },
      signal,
    );
    if (signal.aborted) return;
    devices.value.push(d);
    mappings.value[d.id] = [];
    currentDevice.value = d.id;
    newName.value = '';
    newCode.value = '';
    notice.value = '设备已创建，请配置测点映射';
  });
}
async function saveMappings() {
  const id = currentDevice.value;
  const value = clone(mappingDraft.value);
  if (
    value.some((m) => !m.metricKey.trim() || !m.pointName.trim()) ||
    new Set(value.map((m) => m.metricKey)).size !== value.length
  ) {
    error.value = '指标键和原始测点必填，指标键不能重复';
    return;
  }
  await action(async (signal) => {
    await bridge.value!.saveMappings(id, value, signal);
    if (signal.aborted) return;
    mappings.value[id] = value;
    notice.value = '测点映射已保存';
  });
}
watch([() => state.props.context?.sessionKey, () => state.props.bridge], initialize, {
  immediate: true,
});
watch(currentDevice, () => {
  mappingDraft.value = clone(currentMappings.value);
  series.value = {};
  void refresh();
});
watch(
  () => route.path,
  () => {
    cancelHistory();
    clearTimeout(timer);
    void refresh();
  },
);
onBeforeUnmount(clearRequests);
</script>

<template>
  <section class="iot-app" aria-label="IoT 设备工作台">
    <div v-if="!bridge" class="iot-notice">
      当前宿主未提供 IoT 接口。请从已配置的管理平台或演示宿主进入。
    </div>
    <div v-if="loading" role="status">正在加载设备与看板…</div>
    <div v-if="error" class="iot-error" data-testid="iot-error" role="alert">
      {{ error }} <button @click="error = ''">关闭</button
      ><button v-if="!devices.length" @click="initialize">重新加载</button>
    </div>
    <div v-if="conflict" class="iot-error" data-testid="iot-conflict">
      服务端版本已变化。当前草稿保留；请记录修改后重新加载最新版本。<button
        @click="selectBoard"
      >
        加载最新版本
      </button>
    </div>
    <div v-if="notice" class="iot-notice" role="status">{{ notice }}</div>
    <template v-if="bridge && !loading">
      <fieldset
        v-if="route.path === '/dashboards'"
        class="iot-board-view"
        :disabled="busy"
      >
        <div class="iot-toolbar">
          <label
            >当前设备
            <select v-model="currentDevice" data-testid="iot-device-select">
              <option v-for="d in devices" :value="d.id" :key="d.id">
                {{ d.name }} · {{ d.code }}
              </option>
            </select></label
          >
          <label
            >看板
            <select
              v-model="selectedDashboard"
              data-testid="iot-dashboard-select"
              :disabled="busy"
              @change="selectBoard"
            >
              <option v-if="!selectedDashboard" :value="0">未保存看板</option>
              <option v-for="d in dashboards" :key="d.id" :value="d.id">
                {{ d.name }}
              </option>
            </select></label
          >
          <span class="iot-muted"
            >10 秒刷新 · 趋势按≥60秒间隔采样 ·
            {{
              state.props.context?.dataSource === 'demo'
                ? '演示数据'
                : '实时库'
            }}<b v-if="dirty"> · 未保存</b></span
          >
          <button @click="refresh">刷新</button
          ><button data-testid="iot-new" @click="beginNew()">新建</button
          ><button
            v-if="board"
            data-testid="iot-edit"
            @click="editing = !editing"
          >
            {{ editing ? '退出编辑' : '编辑看板' }}
          </button>
        </div>
        <div v-if="editing && board" class="iot-toolbar">
          <label
            >看板名称
            <input
              v-model="board.name"
              @input="dirty = true"
              aria-label="看板名称" /></label
          ><button data-testid="iot-gallery" @click="gallery = true">
            ＋ 添加组件</button
          ><button
            class="primary"
            data-testid="iot-save"
            :disabled="busy || !dirty"
            @click="saveBoard"
          >
            保存看板</button
          ><button
            data-testid="iot-save-template"
            :disabled="busy"
            @click="saveTemplate"
          >
            保存为当前设备模板</button
          ><button
            data-testid="iot-delete"
            :disabled="busy || !board.id"
            @click="removeBoard"
          >
            删除看板</button
          ><span class="iot-muted"
            >12 列网格 · 拖动 / 右下缩放 · 方向键亦可调整</span
          >
        </div>
        <div v-if="!board" class="iot-empty">
          尚无看板。<button @click="beginNew()">新建空白看板</button
          ><button v-for="t in templates" :key="t.id" @click="beginNew(t)">
            使用模板：{{ t.name }}
          </button>
        </div>
        <div v-if="board" class="iot-grid" :class="{ 'is-editing': editing }">
          <article
            v-for="w in board.widgets"
            :key="w.id"
            class="iot-widget"
            :data-testid="`iot-widget-${w.id}`"
            :data-widget-type="w.type"
            :data-binding-mode="w.binding.mode"
            :data-device-ids="bindingDevices(w, currentDevice).join(',')"
            :data-grid="JSON.stringify(w.grid)"
            :style="{
              gridColumn: `${w.grid.x + 1} / span ${w.grid.w}`,
              gridRow: `${w.grid.y + 1} / span ${w.grid.h}`,
            }"
          >
            <div class="iot-widget-tools">
              <button
                v-if="editing"
                class="iot-drag"
                :data-testid="`iot-drag-${w.id}`"
                aria-label="移动组件"
                @pointerdown="gesture($event, w)"
                @keydown="keyboardGrid($event, w)"
              >
                ⠿</button
              ><span class="iot-muted"
                >{{
                  w.binding.mode === 'currentDevice'
                    ? '当前设备'
                    : w.binding.mode === 'fixed'
                      ? '固定设备'
                      : '设备集合'
                }}
                / {{ w.binding.metricKey }} ·
                {{
                  ['line', 'bar', 'timeline'].includes(w.type)
                    ? `间隔采样 / ${w.aggregation}`
                    : '实时 RAW'
                }}</span
              ><button v-if="editing" @click="draft = clone(w)">配置</button
              ><button v-if="editing" @click="deleteWidget(w.id)">移除</button>
            </div>
            <div v-if="widgetErrors[w.id]" class="iot-warning">
              {{ widgetErrors[w.id] }}
            </div>
            <WidgetView
              :widget="w"
              :series="displaySeries(series[w.id])"
              @select-metric="openMetric($event, w)"
            />
            <button
              v-if="editing"
              class="iot-resize"
              :data-testid="`iot-resize-${w.id}`"
              aria-label="缩放组件"
              @pointerdown="gesture($event, w, true)"
              @keydown="keyboardGrid($event, w, true)"
            >
              ◢
            </button>
          </article>
        </div>
        <div v-if="board && !board.widgets.length" class="iot-empty">
          空白画布 · <button @click="gallery = true">添加组件</button>
        </div>
        <div class="iot-template-bar">
          <span>当前设备模板</span
          ><button v-for="t in templates" :key="t.id" @click="beginNew(t)">
            {{ t.name }} → 创建副本
          </button>
        </div>
      </fieldset>
      <div v-if="route.path === '/devices'" class="iot-device-layout">
        <aside class="iot-panel">
          <input
            v-model="search"
            placeholder="搜索设备名称 / 编码"
            aria-label="搜索设备"
          /><button
            v-for="d in visibleDevices"
            :key="d.id"
            class="iot-device-row"
            :class="{ selected: currentDevice === d.id }"
            @click="currentDevice = d.id"
          >
            <strong>{{ d.name }}</strong
            ><small>{{ d.code }}</small>
          </button>
          <hr />
          <label
            >设备名称<input v-model="newName" aria-label="设备名称" /></label
          ><label
            >设备编码<input v-model="newCode" aria-label="设备编码" /></label
          ><button :disabled="busy" @click="createDevice">创建设备</button>
        </aside>
        <div class="iot-panel">
          <div class="iot-toolbar">
            <strong
              >{{ devices.find((d) => d.id === currentDevice)?.name }} ·
              测点映射</strong
            ><button
              :disabled="!currentDevice"
              @click="
                mappingDraft.push({
                  metricKey: '',
                  name: '',
                  pointName: '',
                  unit: '',
                })
              "
            >
              添加映射</button
            ><button
              class="primary"
              :disabled="busy || !currentDevice"
              @click="saveMappings"
            >
              保存映射
            </button>
          </div>
          <p class="iot-muted">
            统一指标键用于跨设备模板；原始测点名由服务端读取紫金桥。不修改现场设备或测点。
          </p>
          <div class="iot-scroll">
            <table>
              <thead>
                <tr>
                  <th>统一指标键</th>
                  <th>名称</th>
                  <th>原始测点名</th>
                  <th>单位</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(m, i) in mappingDraft" :key="i">
                  <td>
                    <input v-model="m.metricKey" aria-label="统一指标键" />
                  </td>
                  <td><input v-model="m.name" aria-label="指标名称" /></td>
                  <td>
                    <input v-model="m.pointName" aria-label="原始测点名" />
                  </td>
                  <td><input v-model="m.unit" aria-label="单位" /></td>
                  <td>
                    <button @click="mappingDraft.splice(i, 1)">移除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="!mappingDraft.length" class="iot-empty">
            暂无映射；请添加真实测点名。
          </div>
        </div>
      </div>
      <div v-if="route.path === '/widgets'" class="iot-gallery-grid">
        <button
          v-for="item in widgetTypes"
          :key="item.type"
          class="iot-gallery-card"
          :data-testid="`iot-add-${item.type}`"
          @click="addWidget(item.type)"
        >
          <span class="iot-gallery-symbol">{{
            {
              line: '⌁',
              bar: '▥',
              gauge: '◴',
              stat: '86.2',
              timeline: '▬',
              table: '▤',
              image: '▧',
            }[item.type]
          }}</span
          ><strong>{{ item.label }}</strong
          ><span>{{ item.description }}</span
          ><small>添加并绑定 →</small>
        </button>
      </div>
      <div v-if="route.path === '/history'" class="iot-panel">
        <div class="iot-toolbar">
          <label
            >设备集合<select
              v-model="historyDevices"
              multiple
              aria-label="历史设备集合"
            >
              <option v-for="d in devices" :key="d.id" :value="d.id">
                {{ d.name }}
              </option>
            </select></label
          ><label
            >统一指标<select v-model="historyMetric" aria-label="历史指标">
              <option
                v-for="m in metrics"
                :key="m.metricKey"
                :value="m.metricKey"
              >
                {{ m.name }} / {{ m.metricKey }}
              </option>
            </select></label
          ><label
            >开始（本地时间）<input
              v-model="historyStart"
              type="datetime-local" /></label
          ><label
            >结束（本地时间）<input
              v-model="historyEnd"
              type="datetime-local" /></label
          ><label
            >采样间隔（秒）<input
              v-model.number="historyInterval"
              type="number"
              min="1"
              aria-label="采样间隔秒" /></label
          ><label
            >整个窗口聚合<select v-model="historyAggregation">
              <option
                v-for="a in ['RAW', 'AVG', 'MIN', 'MAX', 'LAST']"
                :key="a"
              >
                {{ a }}
              </option>
            </select></label
          ><button
            class="primary"
            data-testid="iot-history-run"
            :disabled="historyLoading"
            @click="runHistory"
          >
            查询历史</button
          ><button
            v-if="historyLoading"
            data-testid="iot-history-cancel"
            @click="cancelHistory"
          >
            取消查询</button
          ><button
            data-testid="iot-export"
            :disabled="busy"
            @click="exportHistory"
          >
            创建下载任务
          </button>
        </div>
        <p class="iot-muted">
          时间将转换为带时区的 UTC。RAW
          为指定间隔采样，并非原始全量记录；AVG/MIN/MAX/LAST
          对整个时间窗的采样结果统计为一个值，不是按间隔分桶。CSV
          使用相同口径。缺测不补 0。
        </p>
        <div data-testid="iot-history-results">
          <WidgetView
            :widget="{ type: 'line', title: '历史趋势' }"
            :series="displaySeries(historySeries)"
            :loading="historyLoading"
          /><WidgetView
            :widget="{ type: 'table', title: '查询结果' }"
            :series="displaySeries(historySeries)"
          />
        </div>
      </div>
      <div v-if="route.path === '/downloads'" class="iot-panel">
        <div class="iot-toolbar">
          <span>当前租户下载任务 · 10 秒刷新</span
          ><button @click="refresh">刷新任务</button>
        </div>
        <div class="iot-scroll">
          <table>
            <thead>
              <tr>
                <th>任务编号</th>
                <th>状态</th>
                <th>创建时间</th>
                <th>过期时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="job in jobs"
                :key="job.id"
                :data-testid="`iot-export-${job.id}`"
              >
                <td>{{ job.id }}</td>
                <td>
                  {{ job.status }}
                  <span class="iot-warning">{{ job.error }}</span>
                </td>
                <td>{{ job.createdAt }}</td>
                <td>{{ job.expiresAt }}</td>
                <td>
                  <button
                    :disabled="
                      busy ||
                      !['COMPLETED', 'SUCCEEDED', 'SUCCESS'].includes(
                        job.status,
                      )
                    "
                    @click="download(job)"
                  >
                    下载 CSV</button
                  ><button
                    :disabled="
                      busy ||
                      !['PENDING', 'RUNNING', 'QUEUED'].includes(job.status)
                    "
                    @click="
                      action(async (signal) => {
                        await bridge!.cancelExport(job.id, signal);
                        if (!signal.aborted) await refresh();
                      })
                    "
                  >
                    取消
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!jobs.length" class="iot-empty">
          暂无下载任务，请从历史查询创建。
        </div>
      </div>
    </template>
    <div v-if="gallery" class="iot-overlay" @click.self="gallery = false">
      <section
        class="iot-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="组件图库"
      >
        <div class="iot-toolbar">
          <strong>选择组件</strong
          ><button @click="gallery = false">关闭</button>
        </div>
        <div class="iot-gallery-grid">
          <button
            v-for="item in widgetTypes"
            :key="item.type"
            class="iot-gallery-card"
            :data-testid="`iot-add-${item.type}`"
            @click="addWidget(item.type)"
          >
            <strong>{{ item.label }}</strong
            ><span>{{ item.description }}</span>
          </button>
        </div>
      </section>
    </div>
    <div v-if="draft" class="iot-overlay" @click.self="draft = undefined">
      <section
        class="iot-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="组件数据绑定"
      >
        <div class="iot-toolbar">
          <strong>组件数据绑定</strong
          ><button @click="draft = undefined">取消</button
          ><button
            class="primary"
            data-testid="iot-binding-save"
            @click="saveBinding"
          >
            应用组件
          </button>
        </div>
        <div class="iot-form-grid">
          <label
            >组件标题<input
              v-model="draft.title"
              data-testid="iot-binding-title"
              aria-label="组件标题" /></label
          ><label
            >绑定模式<select
              v-model="draft.binding.mode"
              data-testid="iot-binding-mode"
            >
              <option value="currentDevice">当前设备（随顶部切换）</option>
              <option value="fixed">固定设备（不随切换）</option>
              <option v-if="draft.type !== 'image'" value="multiple">
                设备集合（多设备比较）
              </option>
            </select></label
          >
          <label v-if="draft.binding.mode !== 'currentDevice'"
            >绑定设备<select
              v-model="draft.binding.deviceIds"
              multiple
              data-testid="iot-binding-devices"
            >
              <option v-for="d in devices" :value="d.id" :key="d.id">
                {{ d.name }}
              </option></select
            ><small v-if="draft.binding.mode === 'fixed'"
              >固定模式只使用第一个所选设备。</small
            ></label
          >
          <label
            >统一指标键<select
              v-model="draft.binding.metricKey"
              data-testid="iot-binding-metric"
            >
              <option
                v-for="m in metrics"
                :key="m.metricKey"
                :value="m.metricKey"
              >
                {{ m.name }} / {{ m.metricKey }} / {{ m.unit }}
              </option>
            </select></label
          ><label v-if="['line', 'bar', 'timeline'].includes(draft.type)"
            >历史窗口（秒）<input
              v-model.number="draft.timeWindowSeconds"
              type="number"
              min="60"
              max="604800" /></label
          ><label v-if="['line', 'bar'].includes(draft.type)"
            >整个窗口聚合<select
              v-model="draft.aggregation"
              aria-label="组件窗口聚合"
            >
              <option
                v-for="a in ['RAW', 'AVG', 'MIN', 'MAX', 'LAST']"
                :key="a"
              >
                {{ a }}
              </option>
            </select></label
          >
          <p
            v-if="!['line', 'bar', 'timeline'].includes(draft.type)"
            class="iot-muted"
          >
            本组件仅查看最新实时值（RAW），不支持历史窗口统计。
          </p>
          <p v-if="draft.type === 'timeline'" class="iot-muted">
            状态时间轴按间隔读取状态（RAW），不支持数值窗口统计。
          </p>
          <template v-if="draft.type === 'gauge'"
            ><label
              >最小值<input v-model.number="draft.min" type="number" /></label
            ><label
              >最大值<input v-model.number="draft.max" type="number" /></label
          ></template>
          <template v-if="draft.type === 'image'"
            ><label
              >站内图片路径<input
                v-model="draft.imageUrl"
                placeholder="/images/boiler.png" /></label
            ><button
              @click="
                (draft.hotspots ??= []).push({
                  x: 50,
                  y: 50,
                  label: '测点',
                  metricKey: draft.binding.metricKey,
                })
              "
            >
              添加测点热点
            </button>
            <div
              v-for="(h, i) in draft.hotspots"
              :key="i"
              class="iot-hotspot-editor"
            >
              <input v-model="h.label" aria-label="热点名称" /><input
                v-model="h.metricKey"
                aria-label="热点指标"
              /><input
                v-model.number="h.x"
                type="number"
                min="0"
                max="100"
                aria-label="热点X百分比"
              /><input
                v-model.number="h.y"
                type="number"
                min="0"
                max="100"
                aria-label="热点Y百分比"
              /><button @click="draft.hotspots?.splice(i, 1)">移除热点</button>
            </div></template
          >
        </div>
        <p class="iot-muted">
          缺测断线、不补零；不同单位分图展示。不支持设备控制与自动单位换算。
        </p>
        <div v-for="issue in draftIssues" :key="issue" class="iot-warning">
          {{ issue }}
        </div>
        <table>
          <thead>
            <tr>
              <th>设备</th>
              <th>原始测点</th>
              <th>单位</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="id in bindingDevices(draft, currentDevice)" :key="id">
              <td>{{ devices.find((d) => d.id === id)?.name }}</td>
              <td>
                {{
                  mappings[id]?.find(
                    (m) => m.metricKey === draft?.binding.metricKey,
                  )?.pointName ?? '缺少映射'
                }}
              </td>
              <td>
                {{
                  mappings[id]?.find(
                    (m) => m.metricKey === draft?.binding.metricKey,
                  )?.unit ?? '—'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>
  </section>
</template>
