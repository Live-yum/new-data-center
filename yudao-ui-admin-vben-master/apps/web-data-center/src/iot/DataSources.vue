<script setup lang="ts">
import type { IotSource } from '@vben/micro-apps';
import { normalizeIotError } from '@vben/micro-apps';
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue';
import { dataCenterKey } from '../context';
import SourceStatus from './SourceStatus.vue';
import './production-scenes.css';
const state = inject(dataCenterKey);
const sources = ref<IotSource[]>([]);
const sourceError = ref('');
const loading = ref(false);
const enabled = computed(
  () =>
    state?.props.context?.dataSource === 'platform' &&
    !!state?.props.bridge?.iot?.sources,
);
let request: AbortController | undefined;
async function refresh() {
  request?.abort();
  const controller = new AbortController();
  request = controller;
  sources.value = [];
  sourceError.value = '';
  loading.value = false;
  if (!enabled.value) return;
  loading.value = true;
  try {
    const result = await state!.props.bridge!.iot!.sources!(controller.signal);
    if (!controller.signal.aborted) sources.value = result;
  } catch (error) {
    if (!controller.signal.aborted)
      sourceError.value = normalizeIotError(error).message;
  } finally {
    if (!controller.signal.aborted) loading.value = false;
  }
}
watch(
  [
    () => state?.props.bridge,
    () => state?.props.context?.sessionKey,
    () => state?.props.context?.tenantId,
    enabled,
  ],
  () => void refresh(),
  { immediate: true },
);
onBeforeUnmount(() => request?.abort());
</script>
<template>
  <section class="pr-workspace" data-testid="production-sources">
    <div class="pr-actions">
      <strong>紫金桥接入与运行检查</strong
      ><button :disabled="loading || !enabled" @click="refresh">
        刷新状态
      </button>
    </div>
    <p v-if="!enabled" class="dc-notice">
      需要正式管理平台会话和生产数据源能力；独立预览及演示环境不连接真实数据源。
    </p>
    <SourceStatus
      v-else
      :sources="sources"
      :error="sourceError"
      :loading="loading"
    />
    <div class="pr-source-grid">
      <article>
        <h2>1 · 部署端连接配置</h2>
        <p>
          当前使用后端统一管理的紫金桥 RealDB
          数据源，不在浏览器保存地址、账号、密码或令牌。
        </p>
        <p>
          管理员在部署配置设置
          <code>yudao.realdb.enabled</code
          >、<code>base-url</code>、认证参数及现场采样时区，再按部署流程生效。配置完整不等于网络连通、认证成功或现场验收通过。
        </p>
        <p>本页只读取配置就绪状态，不发起自动连接探测，也不提供凭据编辑。</p>
      </article>
      <article>
        <h2>2 · 设备与真实测点映射</h2>
        <p>
          在设备目录建立实际设备，将统一指标键绑定到该设备真实紫金桥点名。逐项核对点名、单位和只读账号权限，不要求不同设备使用同一原始点名。
        </p>
        <RouterLink to="/devices">维护设备与测点 →</RouterLink>
      </article>
      <article>
        <h2>3 · 工艺页面绑定</h2>
        <p>
          从五种布局创建命名页面，把图中的位置绑定到真实设备与测点。未绑定保持空白；页面配置持久化到业务数据库，与实时数据库连接凭据分离。
        </p>
        <RouterLink to="/process-separation">配置生产工艺页面 →</RouterLink>
      </article>
      <article>
        <h2>4 · 只读核验与留档</h2>
        <p>
          实际读取后核对数据源、采样时间、原始质量和历史窗口；缺测、陈旧与失败不会补零或回退模拟值。历史下载沿用权限与租户校验。
        </p>
        <RouterLink to="/history">历史查询 →</RouterLink> ·
        <RouterLink to="/downloads">下载任务 →</RouterLink>
        <p>
          只有指定现场版本、网络与授权账号的实机测试，才能证明紫金桥接入验收；页面截图和契约测试不能替代。
        </p>
      </article>
    </div>
  </section>
</template>
