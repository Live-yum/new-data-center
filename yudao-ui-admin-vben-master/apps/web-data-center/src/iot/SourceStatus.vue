<script setup lang="ts">
import type { IotSource } from '@vben/micro-apps';
defineProps<{ sources: IotSource[]; error?: string; loading?: boolean }>();
import { sourceStatusLabels } from './source-model';
import './production-scenes.css';
</script>
<template>
  <div
    class="pr-source-status"
    data-testid="source-status"
    :aria-busy="loading"
  >
    <span v-if="loading">读取数据源配置状态…</span>
    <span v-else-if="error" role="alert">数据源状态读取失败：{{ error }}</span>
    <span v-else-if="!sources.length"
      >当前租户没有可见数据源；未配置或未获授权，不使用模拟数据。</span
    >
    <span
      v-for="source in sources"
      :key="source.id"
      :data-source-status="source.status"
      :data-source-id="source.id"
    >
      <strong>{{ source.name }} [{{ source.id }}]</strong> ·
      {{ sourceStatusLabels[source.status] }}
      <small>采样时区 {{ source.timeZone }} · {{ source.type }}</small>
      <small v-if="source.description">{{ source.description }}</small>
    </span>
  </div>
</template>
