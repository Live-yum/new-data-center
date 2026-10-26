<script setup lang="ts">
import type { WidgetConfig, WidgetSeries } from './widget-model';
import { computed, ref, watch } from 'vue';
import {
  chartGeometry,
  coordinate,
  formatValue,
  gaugePercent,
  latestSample,
  orderedSamples,
  safeImageUrl,
  timelineGeometry,
} from './widget-model';
const props = defineProps<{
  widget: WidgetConfig;
  series: WidgetSeries[];
  loading?: boolean;
}>();
const emit = defineEmits<{ selectMetric: [key: string] }>();
const imageFailed = ref(false);
const imageUrl = computed(() => safeImageUrl(props.widget.imageUrl));
watch(imageUrl, () => {
  imageFailed.value = false;
});
const charts = computed(() =>
  props.series.map((series) => ({
    series,
    geometry: chartGeometry(series.values),
    latest: latestSample(series),
  })),
);
const mixedUnits = computed(
  () => new Set(props.series.map((series) => series.unit)).size > 1,
);
function time(timestamp: string) {
  return new Date(timestamp).toLocaleString('zh-CN', { hour12: false });
}
</script>

<template>
  <div class="iot-widget-view" :aria-busy="loading || undefined">
    <h3 class="iot-widget-title">{{ widget.title }}</h3>
    <span v-if="loading" class="iot-widget-hint" role="status">更新中…</span>
    <span
      v-if="mixedUnits && ['line', 'bar'].includes(widget.type)"
      class="iot-widget-hint"
      >不同单位独立坐标显示</span
    >
    <div v-if="widget.type === 'image'" class="iot-image-panel">
      <div v-if="!imageUrl || imageFailed" class="iot-widget-empty">
        {{
          imageFailed
            ? '图片加载失败，请检查图片地址'
            : '配置站内图片地址后显示测点热点'
        }}
      </div>
      <div v-else class="iot-image-stage">
        <img
          :src="imageUrl"
          :alt="widget.title"
          referrerpolicy="no-referrer"
          @error="imageFailed = true"
        />
        <button
          type="button"
          v-for="(hotspot, index) in widget.hotspots ?? []"
          :key="index"
          class="iot-hotspot"
          :disabled="!hotspot.metricKey"
          @click="hotspot.metricKey && emit('selectMetric', hotspot.metricKey)"
          :style="{
            left: `${coordinate(hotspot.x)}%`,
            top: `${coordinate(hotspot.y)}%`,
          }"
        >
          <b>{{ hotspot.label }}</b>
          <span
            >{{
              formatValue(
                hotspot.metricKey
                  ? charts.find(
                      (chart) => chart.series.metricKey === hotspot.metricKey,
                    )?.latest
                  : charts[index]?.latest,
              )
            }}
            {{
              hotspot.metricKey
                ? charts.find(
                    (chart) => chart.series.metricKey === hotspot.metricKey,
                  )?.series.unit
                : charts[index]?.series.unit
            }}</span
          >
        </button>
      </div>
      <small v-if="!widget.hotspots?.length" class="iot-widget-hint"
        >尚未配置热点位置</small
      >
    </div>
    <div v-else-if="!series.length" class="iot-widget-empty">
      暂无数据 · 请绑定设备和测点
    </div>
    <template v-else-if="widget.type === 'line' || widget.type === 'bar'">
      <div
        v-for="chart in charts"
        :key="`${chart.series.deviceId}:${chart.series.metricKey}`"
        class="iot-chart-series"
      >
        <div class="iot-series-label">
          <span>{{ chart.series.deviceName }}</span
          ><span
            >{{ chart.series.unit || '无单位' }} ·
            {{ formatValue(chart.latest) }}</span
          >
        </div>
        <div v-if="!chart.geometry.bars.length" class="iot-widget-empty">
          暂无有效数值数据
        </div>
        <svg
          v-else
          viewBox="0 0 300 124"
          role="img"
          :aria-label="`${chart.series.deviceName} ${widget.type === 'line' ? '趋势' : '柱状'}图，单位 ${chart.series.unit || '无'}`"
        >
          <path
            d="M12,14 H288 M12,60 H288 M12,106 H288"
            class="iot-grid-line"
          />
          <g v-if="widget.type === 'line'">
            <path
              v-for="(path, index) in chart.geometry.paths"
              :key="index"
              :d="path"
              class="iot-line"
            />
            <circle
              v-for="(bar, index) in chart.geometry.bars"
              :key="index"
              :cx="bar.x"
              :cy="
                106 -
                ((bar.value - chart.geometry.min) /
                  (chart.geometry.max - chart.geometry.min)) *
                  92
              "
              r="2"
              class="iot-dot"
            >
              <title>
                {{ time(bar.timestamp) }} · {{ bar.value }}
                {{ chart.series.unit }}
              </title>
            </circle>
          </g>
          <rect
            v-for="(bar, index) in widget.type === 'bar'
              ? chart.geometry.bars
              : []"
            :key="index"
            :x="bar.x - Math.min(8, 110 / chart.geometry.bars.length)"
            :y="bar.y"
            :width="Math.min(16, 220 / chart.geometry.bars.length)"
            :height="bar.height || 1"
            rx="2"
            class="iot-bar"
          >
            <title>
              {{ time(bar.timestamp) }} · {{ bar.value }}
              {{ chart.series.unit }}
            </title>
          </rect>
          <text x="12" y="12">{{ chart.geometry.max }}</text>
          <text x="12" y="118">{{ chart.geometry.min }}</text>
        </svg>
        <div
          v-if="orderedSamples(chart.series.values).length"
          class="iot-chart-time"
        >
          <span>{{
            time(orderedSamples(chart.series.values)[0]!.timestamp)
          }}</span
          ><span>{{
            time(orderedSamples(chart.series.values).at(-1)!.timestamp)
          }}</span>
        </div>
      </div>
    </template>
    <div
      v-else-if="
        widget.type === 'value' ||
        widget.type === 'stat' ||
        widget.type === 'gauge'
      "
      class="iot-value-grid"
    >
      <section
        v-for="chart in charts"
        :key="`${chart.series.deviceId}:${chart.series.metricKey}`"
        class="iot-reading"
      >
        <span class="iot-series-label">{{ chart.series.deviceName }}</span>
        <div
          v-if="widget.type === 'gauge'"
          class="iot-gauge"
          role="img"
          :aria-label="`${chart.series.deviceName} ${formatValue(chart.latest)} ${chart.series.unit}`"
        >
          <svg viewBox="0 0 200 116">
            <path d="M20 96 A80 80 0 0 1 180 96" class="iot-gauge-track" />
            <path
              v-if="gaugePercent(chart.latest, widget.min, widget.max) !== null"
              d="M20 96 A80 80 0 0 1 180 96"
              class="iot-gauge-fill"
              pathLength="100"
              :stroke-dasharray="`${gaugePercent(chart.latest, widget.min, widget.max)} 100`"
            />
          </svg>
          <strong
            >{{ formatValue(chart.latest) }}
            <small>{{ chart.series.unit }}</small></strong
          >
          <div class="iot-series-label">
            <span>{{ widget.min ?? 0 }}</span
            ><span>{{ widget.max ?? 100 }}</span>
          </div>
          <small v-if="(widget.max ?? 100) <= (widget.min ?? 0)"
            >量程无效，请设置最大值大于最小值</small
          >
        </div>
        <strong v-else class="iot-value"
          >{{ formatValue(chart.latest) }}
          <small>{{ chart.series.unit }}</small></strong
        >
        <small class="iot-widget-hint"
          >{{ chart.latest ? time(chart.latest.timestamp) : '暂无采样'
          }}<template v-if="chart.latest?.quality">
            · {{ chart.latest.quality }}</template
          ></small
        >
      </section>
    </div>
    <div v-else-if="widget.type === 'timeline'" class="iot-timelines">
      <section
        v-for="chart in charts"
        :key="`${chart.series.deviceId}:${chart.series.metricKey}`"
      >
        <div class="iot-series-label">{{ chart.series.deviceName }}</div>
        <div
          class="iot-timeline"
          role="img"
          :aria-label="`${chart.series.deviceName} 状态时间轴`"
        >
          <span
            v-for="(segment, index) in timelineGeometry(chart.series.values)"
            :key="index"
            class="iot-state-segment"
            :title="`${time(segment.sample.timestamp)} · ${formatValue(segment.sample)}`"
            :style="{
              left: `${segment.left}%`,
              width: `${segment.width}%`,
              background: segment.color,
            }"
            ><span class="sr-only"
              >{{ time(segment.sample.timestamp) }}
              {{ formatValue(segment.sample) }}</span
            ><title>
              {{ time(segment.sample.timestamp) }} ·
              {{ formatValue(segment.sample) }}
            </title></span
          >
        </div>
        <div class="iot-state-legend">
          <span
            v-for="(segment, index) in timelineGeometry(chart.series.values)"
            :key="index"
            ><i :style="{ background: segment.color }" />{{
              formatValue(segment.sample)
            }}
            · {{ time(segment.sample.timestamp) }}</span
          >
        </div>
        <span v-if="!chart.series.values.length" class="iot-widget-empty"
          >暂无状态记录</span
        >
      </section>
    </div>
    <div v-else-if="widget.type === 'table'" class="iot-table-wrap">
      <table>
        <thead>
          <tr>
            <th>设备</th>
            <th>时间</th>
            <th>值</th>
            <th>单位</th>
            <th>质量</th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="chart in charts"
            :key="`${chart.series.deviceId}:${chart.series.metricKey}`"
            ><tr
              v-for="(sample, index) in orderedSamples(chart.series.values)"
              :key="index"
            >
              <td>{{ chart.series.deviceName }}</td>
              <td>{{ time(sample.timestamp) }}</td>
              <td>{{ formatValue(sample) }}</td>
              <td>{{ chart.series.unit || '—' }}</td>
              <td>{{ sample.quality || '未提供' }}</td>
            </tr></template
          >
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.iot-widget-title {
  margin: 0 0 10px;
  font-size: 16px;
  color: #17364d;
}
.iot-widget-view {
  min-height: 100px;
  overflow: auto;
  color: #23324c;
  font-size: 12px;
}
.iot-widget-hint {
  display: block;
  color: #76859b;
  font-size: 11px;
  margin: 4px 0;
}
.iot-widget-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100px;
  color: #8a98ad;
  text-align: center;
}
.iot-chart-series {
  padding: 8px 0;
}
.iot-series-label {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  color: #68788f;
  font-size: 11px;
}
.iot-chart-series svg {
  display: block;
  width: 100%;
  max-height: 180px;
}
.iot-grid-line {
  stroke: #edf1f6;
  stroke-width: 1;
  fill: none;
}
.iot-line {
  stroke: #00868b;
  stroke-width: 2;
  fill: none;
  stroke-linejoin: round;
}
.iot-dot,
.iot-bar {
  fill: #0a9297;
}
.iot-chart-series text {
  font-size: 8px;
  fill: #8a98ad;
}
.iot-chart-time {
  display: flex;
  justify-content: space-between;
  font-size: 9px;
  color: #8a98ad;
}
.iot-value-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 16px;
  height: 100%;
}
.iot-reading {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 12px;
}
.iot-value {
  font-size: 34px;
  letter-spacing: -1px;
  padding: 16px 0;
  color: #253759;
}
.iot-value small,
.iot-gauge strong small {
  font-size: 12px;
  font-weight: 400;
  letter-spacing: 0;
  color: #7b8ba2;
}
.iot-gauge {
  position: relative;
  max-width: 250px;
  width: 100%;
  margin: auto;
}
.iot-gauge svg {
  width: 100%;
}
.iot-gauge-track,
.iot-gauge-fill {
  fill: none;
  stroke-width: 12;
  stroke-linecap: round;
}
.iot-gauge-track {
  stroke: #edf1fa;
}
.iot-gauge-fill {
  stroke: #00868b;
}
.iot-gauge strong {
  position: absolute;
  left: 0;
  right: 0;
  top: 52%;
  text-align: center;
  font-size: 25px;
}
.iot-table-wrap {
  max-height: 100%;
  overflow: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}
th {
  color: #7a879c;
  font-weight: 500;
  background: #f8faff;
  position: sticky;
  top: 0;
}
th,
td {
  padding: 9px 10px;
  border-bottom: 1px solid #eef1f6;
  white-space: nowrap;
}
.iot-timeline {
  position: relative;
  height: 26px;
  margin: 16px 2px 10px;
  background: #f1f4f8;
  border-radius: 3px;
}
.iot-state-segment {
  position: absolute;
  top: 0;
  bottom: 0;
  min-width: 2px;
  border-right: 1px solid white;
}
.iot-state-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  font-size: 10px;
  color: #77859a;
}
.iot-state-legend i {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 4px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
}
.iot-timeline li {
  min-width: 85px;
  flex: 1;
  padding: 8px;
  background: #e8f6f0;
  border-top: 4px solid #40b68d;
  border-radius: 3px;
}
.iot-timeline li.missing {
  background: #f0f2f5;
  border-color: #b9c1ce;
}
.iot-timeline small {
  display: block;
  color: #7a879c;
  font-size: 9px;
  margin-top: 6px;
}
.iot-image-panel {
  height: 100%;
}
.iot-image-stage {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 160px;
  overflow: hidden;
}
.iot-image-stage img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.iot-hotspot {
  position: absolute;
  transform: translate(-50%, -50%);
  background: #fffef5e8;
  box-shadow: 0 2px 10px #23324c26;
  border: 1px solid #b6dadd;
  border-radius: 5px;
  padding: 5px 8px;
  max-width: 150px;
}
.iot-hotspot b,
.iot-hotspot span {
  display: block;
  font-size: 10px;
}
.iot-hotspot span {
  color: #00868b;
  margin-top: 3px;
}
</style>
