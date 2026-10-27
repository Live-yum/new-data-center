<script setup lang="ts">
import type { IotSource } from '@vben/micro-apps';
defineProps<{ sources: IotSource[]; error?: string; loading?: boolean }>();
const labels = {
  CONFIGURED: '已配置 · 连接待实际读取验证',
  DISABLED: '未启用',
  NOT_CONFIGURED: '配置不完整',
};
</script>
<template>
  <div
    class="pr-source-status"
    data-testid="source-status"
    :aria-busy="loading"
  >
    <span v-if="loading">读取数据源配置状态…</span>
    <span v-else-if="error" role="alert">数据源状态读取失败：{{ error }}</span>
    <span v-else-if="!sources.length">未取得数据源配置；不使用模拟数据。</span>
    <span
      v-for="source in sources"
      :key="source.id"
      :data-source-status="source.status"
    >
      <strong>{{ source.name }}</strong> · {{ labels[source.status] }}
      <small>采样时区 {{ source.timeZone }} · {{ source.type }}</small>
    </span>
  </div>
</template>
