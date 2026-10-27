<script setup lang="ts">
import { computed } from 'vue';
import type { ProcessDiagramScene } from './process-model';
const props = defineProps<{
  scene: ProcessDiagramScene;
  selected: number;
  readings: Record<number, string>;
}>();
const emit = defineEmits<{ select: [id: number] }>();
const layouts: Record<
  string,
  {
    positions: number[][];
    main: string;
    branch: string;
    labels: { x: number; y: number; text: string }[];
  }
> = {
  separation: {
    positions: [
      [150, 300],
      [390, 300],
      [700, 160],
      [700, 430],
    ],
    main: 'M25 300H465 M465 300H540V160H660 M740 160H960 M430 330V370H550V430H648 M752 430H960',
    branch: 'M390 265V75H960',
    labels: [
      { x: 880, y: 65, text: '气相 →' },
      { x: 880, y: 145, text: '原油 →' },
      { x: 880, y: 415, text: '采出水 →' },
    ],
  },
  gas: {
    positions: [
      [145, 270],
      [380, 270],
      [615, 270],
      [850, 270],
    ],
    main: 'M25 270H975',
    branch: 'M145 305V470H400',
    labels: [
      { x: 850, y: 460, text: '处理气外输 →' },
      { x: 300, y: 455, text: '分离液 →' },
    ],
  },
  storage: {
    positions: [
      [200, 170],
      [200, 430],
      [650, 170],
      [650, 430],
    ],
    main: 'M252 170H400V430H252 M400 300H500 M500 170V430 M500 170H610 M500 430H610 M690 170H820V430H690 M820 300H960',
    branch: '',
    labels: [{ x: 865, y: 285, text: '计量外输 →' }],
  },
  utilities: {
    positions: [
      [150, 300],
      [480, 170],
      [480, 430],
      [800, 300],
    ],
    main: 'M202 300H290 M290 170V430 M290 170H440 M290 430H440 M520 170H650V430H520 M650 300H748 M852 300H975',
    branch: 'M800 255V100H150V255',
    labels: [
      { x: 400, y: 92, text: '回水支路示意' },
      { x: 870, y: 285, text: '供水 →' },
    ],
  },
  offshore: {
    positions: [
      [150, 300],
      [470, 430],
      [740, 430],
      [600, 160],
    ],
    main: 'M25 300H225 M225 300H300V430H418 M522 430H700 M780 430H960',
    branch: 'M150 265V160H560 M640 160H960 M150 335V510H300',
    labels: [
      { x: 855, y: 145, text: '气体外输 →' },
      { x: 855, y: 415, text: '原油外输 →' },
      { x: 230, y: 500, text: '采出水 →' },
    ],
  },
};
const valves = computed(
  () =>
    ({
      separation: [
        [560, 160],
        [560, 430],
      ],
      gas: [
        [270, 270],
        [720, 270],
      ],
      storage: [
        [540, 170],
        [540, 430],
      ],
      utilities: [
        [350, 170],
        [350, 430],
      ],
      offshore: [
        [350, 430],
        [800, 160],
      ],
    })[props.scene.id] ?? [],
);
const layout = computed(() => layouts[props.scene.id]!);
</script>
<template>
  <svg
    class="og-process"
    data-testid="process-diagram"
    viewBox="0 0 1000 600"
    role="img"
    :aria-label="`${scene.name}只读工艺示意，非工程设计`"
  >
    <defs>
      <linearGradient id="og-metal" x1="0" x2="1">
        <stop stop-color="#183f56" />
        <stop offset=".5" stop-color="#32617a" />
        <stop offset="1" stop-color="#193b52" />
      </linearGradient>
      <pattern
        id="og-grid"
        width="24"
        height="24"
        patternUnits="userSpaceOnUse"
      >
        <path d="M24 0H0V24" fill="none" stroke="#234053" stroke-width=".5" />
      </pattern>
    </defs>
    <rect width="1000" height="600" fill="url(#og-grid)" />
    <g v-if="scene.id === 'offshore'" stroke="#486679" fill="none">
      <path d="M45 570H955M100 570L70 598M900 570L930 598" stroke-width="9" />
      <path
        d="M0 590Q50 575 100 590T200 590T300 590T400 590T500 590T600 590T700 590T800 590T900 590T1000 590"
        stroke="#247986"
        stroke-width="2"
      />
    </g>
    <g fill="none" stroke-linejoin="round">
      <path :d="layout.main" stroke="#284c60" stroke-width="13" />
      <path :d="layout.main" stroke="#41c4ba" stroke-width="4" />
      <path
        :d="layout.branch"
        stroke="#5f91af"
        stroke-width="3"
        stroke-dasharray="8 5"
      />
    </g>
    <g
      v-for="(v, i) in valves"
      :key="i"
      :transform="`translate(${v[0]},${v[1]})`"
      fill="#102b3d"
      stroke="#6ed6ce"
      stroke-width="2"
    >
      <path d="M-10-8L10 8V-8L-10 8Z M0-10V-20M-7-20H7" />
    </g>
    <g v-for="label in layout.labels" :key="label.text">
      <text :x="label.x" :y="label.y" fill="#9ab6c7" font-size="12">
        {{ label.text }}
      </text>
    </g>
    <g
      v-for="(device, index) in scene.devices"
      :key="device.id"
      :transform="`translate(${layout.positions[index]![0]},${layout.positions[index]![1]})`"
      class="og-equipment"
      role="button"
      tabindex="0"
      :aria-label="`查看${device.name}只读测点`"
      :aria-pressed="selected === device.id"
      :data-testid="`equipment-${device.id}`"
      @click="emit('select', device.id)"
      @keydown.enter="emit('select', device.id)"
      @keydown.space.prevent="emit('select', device.id)"
    >
      <title>{{ device.name }} · {{ device.code }}</title>
      <rect
        x="-93"
        y="-83"
        width="186"
        height="170"
        rx="12"
        :fill="selected === device.id ? '#24505d55' : 'transparent'"
        :stroke="selected === device.id ? '#6ae5d1' : 'transparent'"
        stroke-dasharray="5 5"
      />
      <g fill="url(#og-metal)" stroke="#8bb9c9" stroke-width="2">
        <template v-if="device.kind === 'separator'">
          <rect x="-75" y="-35" width="150" height="70" rx="35" />
          <path d="M-25-33V33M25-33V33M-48 35V53M48 35V53" />
          <path d="M-57 13H57" stroke="#4dd7bb" stroke-width="5" />
        </template>
        <template v-else-if="device.kind === 'tank'">
          <path d="M-52-45Q0-70 52-45V45Q0 65-52 45Z" />
          <ellipse cy="-45" rx="52" ry="12" />
          <path
            d="M-50 5Q0 23 50 5V43Q0 60-50 43Z"
            fill="#2a8e90"
            stroke="none"
          />
          <path d="M-52 45Q0 65 52 45" />
        </template>
        <template v-else-if="device.kind === 'tower'">
          <rect x="-30" y="-65" width="60" height="120" rx="24" />
          <path d="M-28-30H28M-28-5H28M-28 20H28M-20 55V65M20 55V65" />
          <path d="M40-50V60M40-40H50M40-20H50M40 0H50M40 20H50M40 40H50" />
        </template>
        <template v-else>
          <circle r="40" />
          <path
            v-if="device.kind === 'pump'"
            d="M-17-24L27 0L-17 24Z"
            fill="#36a4a1"
          />
          <path v-else d="M-23-22L25-12V12L-23 22Z" fill="#5c91b4" />
          <path d="M-29 30L-45 52H45L29 30M40-8H70V17H40" />
          <rect x="-60" y="52" width="120" height="7" rx="2" />
        </template>
      </g>
      <text
        y="-96"
        text-anchor="middle"
        fill="#a0bfce"
        font-size="12"
        :textLength="device.code.length > 15 ? 180 : undefined"
        lengthAdjust="spacingAndGlyphs"
      >
        {{
          device.code.length > 24 ? `${device.code.slice(0, 23)}…` : device.code
        }}
      </text>
      <text
        y="107"
        text-anchor="middle"
        fill="#effaff"
        font-size="15"
        font-weight="600"
        :textLength="device.name.length > 12 ? 180 : undefined"
        lengthAdjust="spacingAndGlyphs"
      >
        {{
          device.name.length > 18 ? `${device.name.slice(0, 17)}…` : device.name
        }}
      </text>
      <text
        y="132"
        text-anchor="middle"
        fill="#69dfca"
        font-size="16"
        :textLength="(readings[device.id]?.length ?? 0) > 12 ? 195 : undefined"
        lengthAdjust="spacingAndGlyphs"
      >
        <title>{{ readings[device.id] || '—' }}</title>
        {{ readings[device.id] || '—' }}
      </text>
    </g>
    <text
      x="500"
      y="35"
      text-anchor="middle"
      fill="#7899ae"
      font-size="12"
      letter-spacing="3"
    >
      {{
        scene.id === 'offshore'
          ? 'OFFSHORE · PROCESS MODULES'
          : 'ONSHORE · PROCESS MODULES'
      }}
    </text>
  </svg>
</template>
