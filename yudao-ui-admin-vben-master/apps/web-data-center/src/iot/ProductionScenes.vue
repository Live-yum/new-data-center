<script setup lang="ts">
import type {
  IotDevice,
  IotMapping,
  IotProcessScene,
  IotQuery,
  IotSeries,
  IotSource,
} from '@vben/micro-apps';
import { normalizeIotError } from '@vben/micro-apps';
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router';
import { dataCenterKey } from '../context';
import ProcessDiagram from './ProcessDiagram.vue';
import SourceStatus from './SourceStatus.vue';
import WidgetView from './WidgetView.vue';
import {
  cloneProcessScene,
  createProcessScene,
  processReading,
  processTemplates,
  sceneDiagram,
  validateProcessScene,
} from './process-model';
import { formatValue } from './widget-model';
import './oilgas-demo.css';
import './production-scenes.css';
const state = inject(dataCenterKey);
const route = useRoute();
const api = computed(() => state?.props.bridge?.iot);
const enabled = computed(
  () =>
    state?.props.context?.dataSource === 'platform' &&
    !!api.value?.processScenes &&
    !!api.value.processScene &&
    !!api.value.saveProcessScene &&
    !!api.value.deleteProcessScene,
);
const template = computed(
  () =>
    processTemplates.find((value) => `/process-${value.id}` === route.path) ??
    processTemplates[0]!,
);
const scenes = ref<IotProcessScene[]>([]);
const devices = ref<IotDevice[]>([]);
const mappings = ref<Record<number, IotMapping[]>>({});
const mappingErrors = ref<Record<number, string>>({});
const mappingLoading = ref<Record<number, boolean>>({});
const sources = ref<IotSource[]>([]);
const sourceError = ref('');
const scene = ref<IotProcessScene>();
const draft = ref<IotProcessScene>();
const pageError = ref('');
const notice = ref('');
const loading = ref(false);
const busy = ref(false);
const conflict = ref(false);
const deleteConfirm = ref(false);
const selectedSlot = ref('slot-1');
const metricKey = ref('');
const live = ref<IotSeries[]>([]);
const liveErrors = ref<Record<number, string>>({});
const liveLoading = ref(false);
const lastRead = ref('');
const history = ref<IotSeries[]>([]);
const lastHistoryQuery = ref<IotQuery>();
const historyError = ref('');
const historyLoading = ref(false);
const historyWindow = ref(1800);
const historyInterval = ref(60);
const historyDisplay = ref<'line' | 'timeline'>('line');
const exportNotice = ref('');
const now = ref(Date.now());
const diagramScroll = ref<HTMLElement>();
let lifetime = new AbortController();
let liveRequest: AbortController | undefined;
let historyRequest: AbortController | undefined;
let liveTimer: ReturnType<typeof setTimeout> | undefined;
let liveEpoch = 0;
let historyEpoch = 0;
let selectionEpoch = 0;
const clockTimer = setInterval(() => {
  now.value = Date.now();
}, 1000);
const editing = computed(() => !!draft.value);
const visibleScene = computed(() => draft.value ?? scene.value);
const matchingScenes = computed(() =>
  scenes.value.filter((value) => value.template === template.value.id),
);
const diagram = computed(() =>
  visibleScene.value
    ? sceneDiagram(visibleScene.value, devices.value)
    : undefined,
);
const binding = computed(() =>
  visibleScene.value?.bindings.find(
    (value) => value.slotId === selectedSlot.value,
  ),
);
const selectedDevice = computed(() =>
  devices.value.find((value) => value.id === binding.value?.deviceId),
);
const selectedIndex = computed(
  () =>
    template.value.slots.findIndex((value) => value.id === selectedSlot.value) +
    1,
);
const selectedMappings = computed(() =>
  (mappings.value[binding.value?.deviceId ?? 0] ?? []).filter((value) =>
    binding.value?.metricKeys.includes(value.metricKey),
  ),
);
const sourceBlocked = computed(
  () =>
    sources.value.length > 0 &&
    !sources.value.some((value) => value.status === 'CONFIGURED'),
);
const draftIssues = computed(() =>
  draft.value
    ? validateProcessScene(draft.value, devices.value, mappings.value)
    : [],
);
function message(cause: unknown) {
  return normalizeIotError(cause).message;
}
function stopLive() {
  liveEpoch++;
  liveRequest?.abort();
  clearTimeout(liveTimer);
  liveLoading.value = false;
}
function stopHistory() {
  historyEpoch++;
  historyRequest?.abort();
  historyLoading.value = false;
}
function stopAll() {
  lifetime.abort();
  stopLive();
  stopHistory();
  selectionEpoch++;
}
async function ensureMappings(id: number, force = false) {
  if (!force && mappings.value[id]) return;
  const signal = lifetime.signal;
  const current = api.value;
  if (!current || signal.aborted) return;
  mappingLoading.value[id] = true;
  mappingErrors.value[id] = '';
  try {
    const result = await current.mappings(id, signal);
    if (!signal.aborted) mappings.value[id] = result;
  } catch (error) {
    if (!signal.aborted) {
      mappings.value = Object.fromEntries(
        Object.entries(mappings.value).filter(([key]) => Number(key) !== id),
      );
      mappingErrors.value[id] = message(error);
    }
  } finally {
    if (!signal.aborted) mappingLoading.value[id] = false;
  }
}
async function installScene(value?: IotProcessScene) {
  const signal = lifetime.signal;
  const version = ++selectionEpoch;
  stopLive();
  stopHistory();
  live.value = [];
  history.value = [];
  lastRead.value = '';
  scene.value = undefined;
  if (!value) return;
  const ids = [
    ...new Set(
      value.bindings
        .map((item) => item.deviceId)
        .filter(
          (id): id is number =>
            id !== null && devices.value.some((item) => item.id === id),
        ),
    ),
  ];
  await Promise.all(ids.map((id) => ensureMappings(id, true)));
  if (signal.aborted || version !== selectionEpoch) return;
  selectedSlot.value = 'slot-1';
  scene.value = cloneProcessScene(value);
}
async function initialize(preferredId?: number) {
  stopAll();
  lifetime = new AbortController();
  const signal = lifetime.signal;
  scenes.value = [];
  devices.value = [];
  mappings.value = {};
  mappingErrors.value = {};
  mappingLoading.value = {};
  sources.value = [];
  sourceError.value = '';
  scene.value = undefined;
  draft.value = undefined;
  live.value = [];
  liveErrors.value = {};
  history.value = [];
  historyError.value = '';
  lastRead.value = '';
  pageError.value = '';
  notice.value = '';
  conflict.value = false;
  deleteConfirm.value = false;
  busy.value = false;
  exportNotice.value = '';
  loading.value = false;
  state?.props.registerLeaveGuard?.(hostCanLeave);
  if (!enabled.value) return;
  loading.value = true;
  const current = api.value!;
  try {
    const [ds, pages] = await Promise.all([
      current.devices(signal),
      current.processScenes!(signal),
      (async () => {
        try {
          if (!current.sources) throw new Error('当前宿主未提供数据源状态能力');
          const result = await current.sources(signal);
          if (!signal.aborted) sources.value = result;
        } catch (error) {
          if (!signal.aborted) sourceError.value = message(error);
        }
      })(),
    ]);
    if (signal.aborted) return;
    devices.value = ds;
    scenes.value = pages;
    const selected =
      pages.find(
        (value) =>
          value.id === preferredId && value.template === template.value.id,
      ) ?? pages.find((value) => value.template === template.value.id);
    await installScene(selected);
  } catch (error) {
    if (!signal.aborted) pageError.value = message(error);
  } finally {
    if (!signal.aborted) loading.value = false;
  }
}
async function selectScene(id: number) {
  if (busy.value || editing.value || loading.value) return;
  const current = api.value;
  if (!current?.processScene) return;
  const signal = lifetime.signal;
  busy.value = true;
  pageError.value = '';
  notice.value = '';
  deleteConfirm.value = false;
  try {
    const result = await current.processScene(id, signal);
    if (!signal.aborted) await installScene(result);
  } catch (error) {
    if (!signal.aborted) pageError.value = message(error);
  } finally {
    if (!signal.aborted) busy.value = false;
  }
}
function beginEdit(create = false) {
  if (busy.value || loading.value || editing.value) return;
  const value = create ? createProcessScene(template.value.id) : scene.value;
  if (!value) return;
  draft.value = cloneProcessScene(value);
  pageError.value = '';
  notice.value = '';
  conflict.value = false;
  deleteConfirm.value = false;
}
function cancelEdit() {
  if (busy.value) return;
  draft.value = undefined;
  pageError.value = '';
  conflict.value = false;
}
async function changeDevice(slotId: string, value: string) {
  if (busy.value || !draft.value) return;
  const slot = draft.value.bindings.find((item) => item.slotId === slotId);
  if (!slot) return;
  slot.deviceId = value ? Number(value) : null;
  slot.metricKeys = [];
  slot.primaryMetricKey = '';
  if (slot.deviceId !== null) await ensureMappings(slot.deviceId);
}
function changeMetrics(slotId: string) {
  if (busy.value || !draft.value) return;
  const slot = draft.value.bindings.find((item) => item.slotId === slotId)!;
  if (!slot.metricKeys.includes(slot.primaryMetricKey))
    slot.primaryMetricKey = slot.metricKeys[0] ?? '';
}
async function save() {
  if (busy.value || !draft.value || !api.value?.saveProcessScene) return;
  pageError.value = '';
  notice.value = '';
  if (draftIssues.value.length > 0) {
    pageError.value = draftIssues.value.join('；');
    return;
  }
  const signal = lifetime.signal;
  const value = cloneProcessScene(draft.value);
  busy.value = true;
  try {
    const saved = await api.value.saveProcessScene(value, signal);
    if (signal.aborted) return;
    scenes.value = [
      ...scenes.value.filter((item) => item.id !== saved.id),
      saved,
    ];
    draft.value = undefined;
    conflict.value = false;
    await installScene(saved);
    if (!signal.aborted)
      notice.value = '页面配置已保存到服务端；实时测点仍以紫金桥读取结果为准。';
  } catch (error) {
    if (!signal.aborted) {
      pageError.value = message(error);
      conflict.value = normalizeIotError(error).code === 1_070_000_409;
    }
  } finally {
    if (!signal.aborted) busy.value = false;
  }
}
async function reloadConflict() {
  if (busy.value || !draft.value?.id || !api.value?.processScene) return;
  // eslint-disable-next-line no-alert -- Explicit confirmation before discarding a retained conflict draft.
  if (!window.confirm('加载服务端最新版本将放弃当前未保存的修改，继续吗？'))
    return;
  const signal = lifetime.signal;
  busy.value = true;
  try {
    const saved = await api.value.processScene(draft.value.id, signal);
    if (signal.aborted) return;
    scenes.value = [
      ...scenes.value.filter((item) => item.id !== saved.id),
      saved,
    ];
    draft.value = undefined;
    pageError.value = '';
    conflict.value = false;
    await installScene(saved);
  } catch (error) {
    if (!signal.aborted) pageError.value = message(error);
  } finally {
    if (!signal.aborted) busy.value = false;
  }
}
async function remove() {
  if (
    busy.value ||
    editing.value ||
    !deleteConfirm.value ||
    !scene.value ||
    !api.value?.deleteProcessScene
  )
    return;
  const signal = lifetime.signal;
  const id = scene.value.id;
  busy.value = true;
  pageError.value = '';
  try {
    await api.value.deleteProcessScene(id, scene.value.version, signal);
    if (signal.aborted) return;
    scenes.value = scenes.value.filter((item) => item.id !== id);
    deleteConfirm.value = false;
    await installScene(matchingScenes.value[0]);
    if (!signal.aborted)
      notice.value = '页面配置已删除；设备和紫金桥测点未被修改。';
  } catch (error) {
    if (!signal.aborted) pageError.value = message(error);
  } finally {
    if (!signal.aborted) busy.value = false;
  }
}
function reading(
  slot: IotProcessScene['bindings'][number],
  key = slot.primaryMetricKey,
) {
  const id = slot.deviceId;
  const mapping = mappings.value[id ?? 0]?.find(
    (item) => item.metricKey === key,
  );
  return processReading(
    live.value.find((item) => item.deviceId === id && item.metricKey === key),
    {
      now: now.value,
      staleAfterSeconds: visibleScene.value?.staleAfterSeconds ?? 120,
      unbound: id === null,
      mappingMissing:
        id !== null &&
        !mappingErrors.value[id] &&
        (!mapping || !devices.value.some((item) => item.id === id)),
      failed:
        id !== null && (!!liveErrors.value[id] || !!mappingErrors.value[id]),
      loading: liveLoading.value,
    },
  );
}
const readings = computed(() =>
  Object.fromEntries(
    (visibleScene.value?.bindings ?? []).map((slot) => {
      const index =
        template.value.slots.findIndex((item) => item.id === slot.slotId) + 1;
      const point = reading(slot);
      let value = '配置预览 · 未读取';
      if (!editing.value) {
        value =
          point.value === '—'
            ? point.label
            : `${point.value} ${point.unit}${point.status === 'unknown' ? ` · ${point.label}` : ''}`;
      }
      return [index, value];
    }),
  ),
);
async function refreshLive() {
  stopLive();
  live.value = [];
  liveErrors.value = {};
  lastRead.value = '';
  const current = api.value;
  const page = scene.value;
  if (!current || !page || editing.value || lifetime.signal.aborted) return;
  if (sourceBlocked.value) {
    for (const slot of page.bindings)
      if (slot.deviceId !== null)
        liveErrors.value[slot.deviceId] =
          '数据源未就绪，请先在部署端完成启用与配置。';
    return;
  }
  const version = liveEpoch;
  const controller = new AbortController();
  liveRequest = controller;
  const requests = new Map<number, Set<string>>();
  for (const slot of page.bindings) {
    if (
      slot.deviceId === null ||
      !devices.value.some((item) => item.id === slot.deviceId)
    )
      continue;
    const keys = slot.metricKeys.filter((key) =>
      mappings.value[slot.deviceId!]?.some((item) => item.metricKey === key),
    );
    if (keys.length === 0) continue;
    const set = requests.get(slot.deviceId) ?? new Set<string>();
    keys.forEach((key) => set.add(key));
    requests.set(slot.deviceId, set);
  }
  if (requests.size === 0) return;
  async function poll() {
    if (controller.signal.aborted || version !== liveEpoch) return;
    liveLoading.value = true;
    await Promise.all(
      [...requests].map(async ([id, keys]) => {
        try {
          // Each device has its own mappings. Bound each request to the API's
          // 20-metric limit even when several slots share the same device.
          const metricKeys = [...keys];
          const batches: string[][] = [];
          for (let offset = 0; offset < metricKeys.length; offset += 20)
            batches.push(metricKeys.slice(offset, offset + 20));
          const results = await Promise.all(
            batches.map((batch) =>
              current!.query(
                'realtime',
                { deviceIds: [id], metricKeys: batch, aggregation: 'RAW' },
                controller.signal,
              ),
            ),
          );
          if (controller.signal.aborted || version !== liveEpoch) return;
          if (
            results.some(
              (result) =>
                result.source !== 'REALDB' ||
                result.series.some((item) =>
                  item.points.some(
                    (point) => point.quality?.toUpperCase() === 'DEMO',
                  ),
                ),
            )
          )
            throw new Error(
              '生产页面拒绝非 REALDB 数据源或模拟质量标记，不使用演示或模拟值。',
            );
          const accepted = results
            .flatMap((result) => result.series)
            .filter((item) => item.deviceId === id && keys.has(item.metricKey));
          live.value = [
            ...live.value.filter((item) => item.deviceId !== id),
            ...accepted,
          ];
          liveErrors.value[id] = '';
          now.value = Date.now();
          lastRead.value = new Date().toISOString();
        } catch (error) {
          if (controller.signal.aborted || version !== liveEpoch) return;
          live.value = live.value.filter((item) => item.deviceId !== id);
          liveErrors.value[id] = message(error);
        }
      }),
    );
    if (controller.signal.aborted || version !== liveEpoch) return;
    liveLoading.value = false;
    liveTimer = setTimeout(() => void poll(), page!.refreshSeconds * 1000);
  }
  await poll();
}
function buildHistoryQuery(): IotQuery | undefined {
  const slot = binding.value;
  if (
    !slot?.deviceId ||
    !metricKey.value ||
    !selectedMappings.value.some((value) => value.metricKey === metricKey.value)
  )
    return;
  if (
    !Number.isInteger(historyInterval.value) ||
    historyInterval.value < 1 ||
    historyWindow.value / historyInterval.value + 1 > 10_000
  )
    throw new Error('采样间隔至少 1 秒，且单次查询不能超过 10000 个采样点。');
  const end = Date.now();
  return {
    deviceIds: [slot.deviceId],
    metricKeys: [metricKey.value],
    aggregation: 'RAW',
    startTime: new Date(end - historyWindow.value * 1000).toISOString(),
    endTime: new Date(end).toISOString(),
    intervalMs: historyInterval.value * 1000,
  };
}
async function refreshHistory() {
  stopHistory();
  history.value = [];
  lastHistoryQuery.value = undefined;
  historyError.value = '';
  exportNotice.value = '';
  if (
    !enabled.value ||
    editing.value ||
    !scene.value ||
    lifetime.signal.aborted ||
    sourceBlocked.value
  )
    return;
  const current = api.value;
  if (!current) return;
  const version = historyEpoch;
  const controller = new AbortController();
  historyRequest = controller;
  try {
    const query = buildHistoryQuery();
    if (!query) return;
    historyLoading.value = true;
    const result = await current.query('history', query, controller.signal);
    if (controller.signal.aborted || version !== historyEpoch) return;
    if (
      result.source !== 'REALDB' ||
      result.series.some((item) =>
        item.points.some((point) => point.quality?.toUpperCase() === 'DEMO'),
      )
    )
      throw new Error('生产页面拒绝非 REALDB 历史数据源或模拟质量标记。');
    lastHistoryQuery.value = query;
    history.value = result.series.filter(
      (item) =>
        query.deviceIds.includes(item.deviceId) &&
        query.metricKeys.includes(item.metricKey),
    );
  } catch (error) {
    if (!controller.signal.aborted && version === historyEpoch)
      historyError.value = message(error);
  } finally {
    if (!controller.signal.aborted && version === historyEpoch)
      historyLoading.value = false;
  }
}
async function exportHistory() {
  if (busy.value || editing.value || sourceBlocked.value || !api.value) return;
  const signal = lifetime.signal;
  busy.value = true;
  exportNotice.value = '';
  historyError.value = '';
  try {
    // Reuse the exact successful UTC query window, not a moving 'now'.
    const query = lastHistoryQuery.value;
    if (!query) return;
    const job = await api.value.createExport(query, signal);
    if (!signal.aborted)
      exportNotice.value = `已创建下载任务 ${job.id}（${job.status}），请在下载任务中查看实际结果。`;
  } catch (error) {
    if (!signal.aborted) historyError.value = message(error);
  } finally {
    if (!signal.aborted) busy.value = false;
  }
}
const trend = computed(() =>
  history.value.map((item) => ({
    deviceId: item.deviceId,
    deviceName: selectedDevice.value?.name ?? String(item.deviceId),
    metricKey: item.metricKey,
    unit: item.unit,
    values: item.points.map((point) => ({
      timestamp: point.time,
      value: point.timeSource === 'DEVICE' ? point.value : null,
      quality: point.quality,
    })),
  })),
);
const samples = computed(
  () =>
    history.value[0]?.points
      .toSorted((a, b) => Date.parse(b.time) - Date.parse(a.time))
      .slice(0, 8) ?? [],
);
function time(value: string) {
  return Number.isFinite(Date.parse(value))
    ? new Date(value).toLocaleString('zh-CN', { hour12: false })
    : '时间无效';
}
function canLeave() {
  if (busy.value) return false;
  if (!editing.value) return true;
  // eslint-disable-next-line no-alert -- Synchronous router/host leave guard must confirm before losing an unsaved draft.
  return window.confirm('页面配置尚未保存，离开将放弃修改，继续吗？');
}
function hostCanLeave() {
  if (!canLeave()) return false;
  // Host guards run before committing its URL. A confirmed discard is applied
  // now so the child route guard will not ask the same question a second time.
  if (editing.value) cancelEdit();
  return true;
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (!editing.value && !busy.value) return;
  event.preventDefault();
  event.returnValue = '';
}
window.addEventListener('beforeunload', beforeUnload);
watch(
  () => state?.props.registerLeaveGuard,
  (register, previous) => {
    previous?.(undefined);
    register?.(hostCanLeave);
  },
  { immediate: true },
);
onBeforeRouteLeave(canLeave);
onBeforeRouteUpdate(canLeave);
watch(
  [
    () => state?.props.bridge,
    () => state?.props.context?.sessionKey,
    () => state?.props.context?.tenantId,
    enabled,
    () => template.value.id,
  ],
  () => void initialize(),
  { immediate: true, flush: 'sync' },
);
watch([scene, editing], () => {
  void refreshLive();
  void refreshHistory();
});
watch([binding, selectedMappings], () => {
  if (
    !selectedMappings.value.some((value) => value.metricKey === metricKey.value)
  )
    metricKey.value =
      selectedMappings.value.find(
        (value) => value.metricKey === binding.value?.primaryMetricKey,
      )?.metricKey ??
      selectedMappings.value[0]?.metricKey ??
      '';
});
watch(
  [selectedSlot, metricKey, historyWindow, historyInterval],
  () => void refreshHistory(),
);
watch(selectedSlot, async () => {
  await nextTick();
  const panel = diagramScroll.value;
  const element = panel?.querySelector(
    `[data-testid="equipment-${selectedIndex.value}"]`,
  );
  if (!panel || !element || panel.scrollWidth <= panel.clientWidth) return;
  const bounds = element.getBoundingClientRect();
  const viewport = panel.getBoundingClientRect();
  panel.scrollLeft +=
    bounds.left + bounds.width / 2 - viewport.left - viewport.width / 2;
});
onBeforeUnmount(() => {
  stopAll();
  clearInterval(clockTimer);
  window.removeEventListener('beforeunload', beforeUnload);
  state?.props.registerLeaveGuard?.(undefined);
});
</script>
<template>
  <section
    class="og-workspace pr-workspace"
    data-testid="production-scenes"
    :data-template="template.id"
    :aria-busy="loading || busy"
  >
    <div class="og-toolbar">
      <nav aria-label="生产工艺页面类型">
        <RouterLink
          v-for="item in processTemplates"
          :key="item.id"
          :to="`/process-${item.id}`"
          :data-testid="`production-${item.id}`"
          >{{ item.name }}</RouterLink
        >
      </nav>
      <span class="pr-readonly-badge">REALDB · 只读观测</span>
    </div>
    <div v-if="!enabled" class="dc-notice" data-testid="production-unavailable">
      生产工艺页面需要正式管理平台会话及生产页面
      API。独立预览和演示宿主不读取或伪造现场数据。
    </div>
    <template v-else>
      <SourceStatus
        :sources="sources"
        :error="sourceError"
        :loading="loading"
      />
      <div class="pr-actions">
        <label
          >已保存页面
          <select
            :value="scene?.id ?? ''"
            :disabled="busy || loading || editing || !matchingScenes.length"
            data-testid="production-scene-select"
            @change="
              selectScene(Number(($event.target as HTMLSelectElement).value))
            "
          >
            <option v-if="!matchingScenes.length" value="">
              此类型尚无页面
            </option>
            <option
              v-for="item in matchingScenes"
              :key="item.id"
              :value="item.id"
            >
              {{ item.name }}
            </option>
          </select></label
        >
        <button
          data-testid="scene-new"
          :disabled="busy || loading || editing"
          @click="beginEdit(true)"
        >
          新建页面
        </button>
        <button
          data-testid="scene-edit"
          :disabled="busy || loading || editing || !scene"
          @click="beginEdit()"
        >
          编辑绑定
        </button>
        <button
          data-testid="scene-reload"
          :disabled="busy || loading || editing"
          @click="initialize(scene?.id)"
        >
          重新加载
        </button>
        <button
          :disabled="busy || loading || editing || !scene"
          data-testid="scene-delete"
          class="pr-danger"
          @click="deleteConfirm = !deleteConfirm"
        >
          删除页面
        </button>
        <RouterLink to="/devices">设备与测点</RouterLink
        ><RouterLink to="/sources">接入检查</RouterLink>
      </div>
      <div
        v-if="pageError"
        class="dc-error"
        role="alert"
        data-testid="scene-error"
      >
        {{ pageError }}
      </div>
      <div v-if="notice" class="dc-notice" role="status">{{ notice }}</div>
      <div v-if="deleteConfirm" class="pr-confirm" role="alert">
        确定删除「{{
          scene?.name
        }}」的页面配置？不会删除设备或修改现场测点。<button
          data-testid="scene-delete-confirm"
          :disabled="busy"
          @click="remove"
        >
          确认删除</button
        ><button :disabled="busy" @click="deleteConfirm = false">
          保留页面
        </button>
      </div>
      <form
        v-if="draft"
        class="pr-editor"
        data-testid="scene-editor"
        @submit.prevent="save"
      >
        <fieldset :disabled="busy">
          <div class="pr-editor-heading">
            <strong>页面配置 · {{ template.name }}</strong
            ><span>只修改平台布局与绑定，不写入现场设备</span>
          </div>
          <div class="pr-fields">
            <label
              >页面名称<input
                v-model.trim="draft.name"
                data-testid="scene-name"
                maxlength="100"
                required
            /></label>
            <label class="pr-wide"
              >说明<input
                v-model.trim="draft.description"
                data-testid="scene-description"
                maxlength="500"
                placeholder="例如站点/装置范围；不要填写连接密码"
            /></label>
            <label
              >实时刷新（秒）<input
                v-model.number="draft.refreshSeconds"
                data-testid="scene-refresh-seconds"
                type="number"
                min="5"
                max="300"
                step="1"
                required
            /></label>
            <label
              >陈旧判定（秒）<input
                v-model.number="draft.staleAfterSeconds"
                data-testid="scene-stale-seconds"
                type="number"
                :min="draft.refreshSeconds"
                max="3600"
                step="1"
                required
            /></label>
          </div>
          <p class="pr-help">
            以下四个位置只是功能布局模板，不是现场工程设计。可全部保持未绑定后保存；绑定时只能选择设备实际存在的测点映射，单位由读取结果提供。
          </p>
          <div class="pr-bindings">
            <article
              v-for="(slot, index) in draft.bindings"
              :key="slot.slotId"
              class="pr-binding"
            >
              <h3>
                {{ index + 1 }} ·
                {{
                  template.slots.find((item) => item.id === slot.slotId)?.name
                }}
              </h3>
              <label
                >真实设备<select
                  :value="slot.deviceId ?? ''"
                  :data-testid="`${slot.slotId}-device`"
                  @change="
                    changeDevice(
                      slot.slotId,
                      ($event.target as HTMLSelectElement).value,
                    )
                  "
                >
                  <option value="">未绑定</option>
                  <option
                    v-if="
                      slot.deviceId &&
                      !devices.some((item) => item.id === slot.deviceId)
                    "
                    :value="slot.deviceId"
                  >
                    设备已不可用 #{{ slot.deviceId }}
                  </option>
                  <option
                    v-for="item in devices"
                    :key="item.id"
                    :value="item.id"
                  >
                    {{ item.name }} · {{ item.code }}
                  </option>
                </select></label
              >
              <p
                v-if="slot.deviceId && mappingLoading[slot.deviceId]"
                role="status"
              >
                读取该设备测点映射…
              </p>
              <div
                v-else-if="slot.deviceId && mappingErrors[slot.deviceId]"
                class="dc-error"
                role="alert"
              >
                {{ mappingErrors[slot.deviceId] }}
                <button
                  type="button"
                  @click="ensureMappings(slot.deviceId!, true)"
                >
                  重试映射
                </button>
              </div>
              <label
                >显示测点（可多选）<select
                  v-model="slot.metricKeys"
                  multiple
                  size="5"
                  :data-testid="`${slot.slotId}-metrics`"
                  :disabled="
                    slot.deviceId === null || mappingLoading[slot.deviceId]
                  "
                  @change="changeMetrics(slot.slotId)"
                >
                  <option
                    v-for="item in mappings[slot.deviceId ?? 0] ?? []"
                    :key="item.metricKey"
                    :value="item.metricKey"
                  >
                    {{ item.name }} · {{ item.metricKey }} →
                    {{ item.pointName }} [{{ item.unit || '无单位' }}]
                  </option>
                  <option
                    v-for="key in slot.metricKeys.filter(
                      (key) =>
                        !mappings[slot.deviceId ?? 0]?.some(
                          (item) => item.metricKey === key,
                        ),
                    )"
                    :key="key"
                    :value="key"
                  >
                    映射已不可用：{{ key }}
                  </option>
                </select></label
              >
              <label
                >图上主测点<select
                  v-model="slot.primaryMetricKey"
                  :data-testid="`${slot.slotId}-primary`"
                  :disabled="!slot.metricKeys.length"
                >
                  <option v-if="!slot.metricKeys.length" value="">
                    尚未选择测点
                  </option>
                  <option
                    v-for="key in slot.metricKeys"
                    :key="key"
                    :value="key"
                  >
                    {{
                      mappings[slot.deviceId ?? 0]?.find(
                        (item) => item.metricKey === key,
                      )?.name ?? key
                    }}
                  </option>
                </select></label
              >
              <p
                v-if="
                  slot.deviceId !== null &&
                  !mappingLoading[slot.deviceId] &&
                  !mappingErrors[slot.deviceId] &&
                  !mappings[slot.deviceId]?.length
                "
                class="pr-help"
              >
                此设备尚无测点映射，请先在「设备与测点」中配置；这里不会猜测点名。
              </p>
            </article>
          </div>
          <ul v-if="draftIssues.length" class="pr-validation">
            <li v-for="issue in draftIssues" :key="issue">{{ issue }}</li>
          </ul>
          <div v-if="conflict" class="pr-confirm" data-testid="scene-conflict">
            服务端版本已变化，本地草稿已保留。请先加载最新版本再合并修改。<button
              type="button"
              data-testid="scene-conflict-reload"
              @click="reloadConflict"
            >
              放弃草稿并加载最新版本
            </button>
          </div>
          <div class="pr-actions">
            <button
              data-testid="scene-save"
              type="submit"
              :disabled="
                !!draftIssues.length ||
                Object.values(mappingLoading).some(Boolean)
              "
            >
              {{ busy ? '保存中…' : '保存到服务端' }}</button
            ><button
              data-testid="scene-cancel"
              type="button"
              @click="cancelEdit"
            >
              取消编辑</button
            ><span>未点击保存不会更改已保存页面。</span>
          </div>
        </fieldset>
      </form>
      <div
        v-else-if="!scene && !loading"
        class="pr-empty"
        data-testid="scene-empty"
      >
        <strong>{{ template.name }}尚未配置</strong>
        <p>
          创建页面并绑定真实设备与测点后开始读取。这里没有预置设备编号或模拟工艺数值。
        </p>
        <button :disabled="busy" @click="beginEdit(true)">
          从空白绑定创建
        </button>
      </div>
      <template v-if="visibleScene && diagram">
        <div class="og-context">
          <span
            ><strong>{{ visibleScene.name }}</strong> ·
            {{ visibleScene.description || template.subtitle }}</span
          ><span>{{
            editing
              ? '配置预览 · 未保存、未读取'
              : `版本 ${scene?.version} · 每 ${visibleScene.refreshSeconds} 秒读取`
          }}</span>
        </div>
        <div class="og-main-grid">
          <article class="og-flow-panel">
            <div class="og-panel-heading">
              <h2>工艺测点总览</h2>
              <span>选择图中位置查看绑定设备</span>
            </div>
            <p class="og-pan-hint">左右滑动查看完整工艺图 · 选择位置自动定位</p>
            <div
              ref="diagramScroll"
              class="og-diagram-scroll"
              data-testid="production-diagram-scroll"
              tabindex="0"
              aria-label="工艺图，可左右滚动"
            >
              <ProcessDiagram
                :scene="diagram"
                :selected="selectedIndex"
                :readings="readings"
                @select="selectedSlot = template.slots[$event - 1]!.id"
              />
            </div>
            <div class="og-flow-footer">
              <span><i></i>功能布局示意 <i class="og-gas"></i>支路示意</span
              ><span>非现场工程图 · 非 P&amp;ID · 无控制功能</span>
            </div>
          </article>
          <aside
            class="og-device-panel"
            data-testid="production-live"
            :data-device-id="binding?.deviceId ?? ''"
          >
            <div class="og-panel-heading">
              <h2>绑定设备测点</h2>
              <span>{{ editing ? '未读取' : 'REALDB' }}</span>
            </div>
            <label class="og-device-select"
              >当前图中位置<select
                v-model="selectedSlot"
                data-testid="production-node"
              >
                <option
                  v-for="(slot, index) in visibleScene.bindings"
                  :key="slot.slotId"
                  :value="slot.slotId"
                >
                  {{ index + 1 }} ·
                  {{
                    devices.find((item) => item.id === slot.deviceId)?.name ??
                    template.slots.find((item) => item.id === slot.slotId)
                      ?.name
                  }}{{ slot.deviceId === null ? ' · 未绑定' : '' }}
                </option>
              </select></label
            >
            <div class="og-device-identity">
              <span>{{ selectedDevice?.code ?? '未绑定可用设备' }}</span
              ><strong>{{ selectedDevice?.name ?? '暂无真实设备' }}</strong
              ><small>{{
                binding?.deviceId === null
                  ? '请编辑绑定后读取'
                  : `设备 ID ${binding?.deviceId} · 原始质量独立显示`
              }}</small>
            </div>
            <p
              v-if="binding?.deviceId && liveErrors[binding.deviceId]"
              class="pr-inline-error"
              role="alert"
            >
              {{ liveErrors[binding.deviceId] }}
            </p>
            <p
              v-if="binding?.deviceId && mappingErrors[binding.deviceId]"
              class="pr-inline-error"
              role="alert"
            >
              映射读取失败：{{ mappingErrors[binding.deviceId] }}
            </p>
            <div
              v-for="key in binding?.metricKeys ?? []"
              :key="key"
              class="og-reading pr-reading"
              :data-metric="key"
              :data-status="reading(binding!, key).status"
            >
              <span
                >{{
                  mappings[binding!.deviceId ?? 0]?.find(
                    (item) => item.metricKey === key,
                  )?.name ?? key
                }}<small>{{ key }}</small></span
              >
              <strong
                >{{ editing ? '—' : reading(binding!, key).value
                }}<small>{{
                  editing ? '' : reading(binding!, key).unit
                }}</small></strong
              >
              <small class="pr-reading-meta"
                >{{
                  editing
                    ? '配置预览，未读取'
                    : `${reading(binding!, key).label} · 质量 ${reading(binding!, key).quality || '未取得'}`
                }}<br />{{
                  !editing && reading(binding!, key).time
                    ? `${reading(binding!, key).timeLabel} ${time(reading(binding!, key).time)}`
                    : '无有效时间'
                }}</small
              >
            </div>
            <div v-if="!binding?.metricKeys.length" class="og-timestamp">
              未绑定测点，不补零、不生成数值。
            </div>
            <div class="og-timestamp">
              {{
                lastRead && !editing
                  ? `最近成功请求 ${time(lastRead)}；不代表所有点质量正常。`
                  : '尚无成功读取'
              }}<br />超过
              {{ visibleScene.staleAfterSeconds }}
              秒标为陈旧，陈旧值不冒充当前值。
            </div>
            <div class="pr-card-actions">
              <button
                :disabled="editing || liveLoading || busy"
                data-testid="production-live-refresh"
                @click="refreshLive"
              >
                立即读取
              </button>
            </div>
          </aside>
        </div>
        <div class="og-bottom-grid">
          <article
            class="og-trend-panel"
            data-testid="production-trend"
            :data-device-id="binding?.deviceId ?? ''"
            :data-metric="metricKey"
          >
            <div class="og-panel-heading">
              <h2>真实历史采样 <small>RAW · 缺测断线 · 本地时间</small></h2>
              <span>不将缺测视为 0</span>
            </div>
            <div class="pr-history-controls">
              <label
                >测点<select
                  v-model="metricKey"
                  data-testid="production-metric"
                  :disabled="editing || !selectedMappings.length"
                >
                  <option v-if="!selectedMappings.length" value="">
                    未绑定可查询测点
                  </option>
                  <option
                    v-for="item in selectedMappings"
                    :key="item.metricKey"
                    :value="item.metricKey"
                  >
                    {{ item.name }}
                  </option>
                </select></label
              ><label
                >窗口<select
                  v-model.number="historyWindow"
                  :disabled="editing"
                  data-testid="production-history-window"
                >
                  <option :value="1800">最近 30 分钟</option>
                  <option :value="3600">最近 1 小时</option>
                  <option :value="21600">最近 6 小时</option>
                  <option :value="86400">最近 24 小时</option>
                </select></label
              ><label
                >间隔（秒）<input
                  v-model.number="historyInterval"
                  :disabled="editing"
                  type="number"
                  min="1"
                  max="86400"
                  step="1"
                  data-testid="production-history-interval" /></label
              ><label
                >展示<select v-model="historyDisplay">
                  <option value="line">数值趋势</option>
                  <option value="timeline">状态时间轴</option>
                </select></label
              ><button
                :disabled="editing || historyLoading || !metricKey"
                data-testid="production-history-refresh"
                @click="refreshHistory"
              >
                查询</button
              ><button
                v-if="historyLoading"
                @click="
                  stopHistory();
                  history = [];
                  historyError = '历史查询已取消';
                "
              >
                取消查询
              </button>
            </div>
            <div v-if="historyError" class="pr-inline-error" role="alert">
              {{ historyError }}
            </div>
            <WidgetView
              :widget="{
                type: historyDisplay,
                title: `${selectedDevice?.name ?? '未绑定'} · ${selectedMappings.find((item) => item.metricKey === metricKey)?.name ?? '未选择测点'}`,
              }"
              :series="trend"
              :loading="historyLoading"
            />
            <p class="pr-help">
              历史曲线只在查询/选择条件变化时读取；缺少采集时间来源的记录不绘制时间曲线。非数值运行状态请选「状态时间轴」。
            </p>
            <div class="pr-card-actions">
              <button
                data-testid="production-export"
                :disabled="
                  editing || busy || !lastHistoryQuery || sourceBlocked
                "
                @click="exportHistory"
              >
                创建历史下载任务</button
              ><RouterLink to="/history">完整历史查询</RouterLink
              ><RouterLink to="/downloads">下载任务</RouterLink>
            </div>
            <p v-if="exportNotice" class="pr-help" role="status">
              {{ exportNotice }}
            </p>
          </article>
          <article class="og-samples-panel" data-testid="production-samples">
            <div class="og-panel-heading">
              <h2>最近采样明细</h2>
              <span>{{ history[0]?.unit ?? '单位以返回值为准' }}</span>
            </div>
            <table>
              <thead>
                <tr>
                  <th>时间（本地）</th>
                  <th>值</th>
                  <th>原始质量</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(point, index) in samples"
                  :key="`${point.time}-${index}`"
                >
                  <td>
                    {{ time(point.time)
                    }}<small class="pr-time-origin">{{
                      point.timeSource === 'DEVICE'
                        ? '采样时间'
                        : point.timeSource === 'RECEIVED'
                          ? '接收时间 · 采集时间未知'
                          : '时间来源未知'
                    }}</small>
                  </td>
                  <td>
                    {{
                      formatValue({
                        timestamp: point.time,
                        value: point.value,
                        quality: point.quality,
                      })
                    }}
                  </td>
                  <td>{{ point.quality || 'UNKNOWN' }}</td>
                </tr>
                <tr v-if="!samples.length">
                  <td colspan="3">
                    {{ historyLoading ? '查询中…' : '暂无有效历史采样' }}
                  </td>
                </tr>
              </tbody>
            </table>
            <p>展示返回样本；UNKNOWN 不等同于 GOOD，不推断安全状态。</p>
          </article>
        </div>
        <footer class="og-disclaimer">
          <strong>生产使用边界</strong
          >页面显示已绑定设备的真实查询结果，数据源必须返回
          REALDB。图形是可复用功能布局，需由现场核对，不能作为工程设计、安全联锁或运行指令；不提供设备启停、阀门操作或写值。未配置与读取失败均无模拟回退。
        </footer>
      </template>
    </template>
  </section>
</template>
