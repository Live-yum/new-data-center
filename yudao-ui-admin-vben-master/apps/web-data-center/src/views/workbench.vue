<script lang="ts" setup>
import type { DataCenterDept, DataCenterDictData } from '@vben/micro-apps';

import { computed, inject, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';

import { dataCenterKey } from '../context';

const injectedState = inject(dataCenterKey);
if (!injectedState) throw new Error('数据中心上下文未初始化');
const state = injectedState;
const route = useRoute();
const departments = ref<DataCenterDept[]>([]);
const dictionaries = ref<DataCenterDictData[]>([]);
const search = ref('');
const loading = ref(false);
const error = ref('');
const refreshedAt = ref('');
let request: AbortController | undefined;

const context = computed(() => state.props.context);
const connected = computed(() => !!state.props.bridge);
const dictionaryTypes = computed(
  () => new Set(dictionaries.value.map((item) => item.dictType)).size,
);
const visibleDepartments = computed(() =>
  departments.value.filter((item) =>
    item.name.toLowerCase().includes(search.value.toLowerCase()),
  ),
);
const visibleDictionaries = computed(() =>
  dictionaries.value.filter((item) =>
    `${item.dictType} ${item.label} ${item.value}`
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
);

async function refresh() {
  request?.abort();
  const current = new AbortController();
  request = current;
  departments.value = [];
  dictionaries.value = [];
  refreshedAt.value = '';
  error.value = '';
  const bridge = state.props.bridge;
  if (!bridge) {
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const [deptList, dictList] = await Promise.all([
      bridge.getDepartments(current.signal),
      bridge.getDictionaries(current.signal),
    ]);
    // 切换租户/用户或卸载后，旧响应不得回填到新会话。
    if (current.signal.aborted) return;
    departments.value = deptList;
    dictionaries.value = dictList;
    refreshedAt.value = new Date().toLocaleTimeString();
  } catch (cause) {
    if (!current.signal.aborted) {
      error.value =
        cause instanceof Error ? cause.message : '数据加载失败，请稍后重试';
    }
  } finally {
    if (!current.signal.aborted) loading.value = false;
  }
}

watch(() => [state.props.context?.sessionKey, state.props.bridge], refresh, {
  immediate: true,
});
watch(
  () => route.path,
  () => {
    search.value = '';
  },
);
onBeforeUnmount(() => request?.abort());
</script>

<template>
  <section class="data-center-app" aria-label="数据中心工作台">
    <header class="dc-header">
      <div>
        <p class="dc-eyebrow">DATA CENTER</p>
        <h1>数据中心工作台</h1>
        <p class="dc-description">在统一工作台查看部门和数据字典。</p>
      </div>
      <div class="dc-session">
        <span class="dc-status" :class="{ 'is-connected': connected }">
          {{
            context?.dataSource === 'demo'
              ? '演示数据'
              : connected
                ? '已连接管理平台'
                : '独立预览'
          }}
        </span>
        <span v-if="context"
          >{{ context.userName }} · 租户
          {{ context.tenantId ?? '未启用' }}</span
        >
      </div>
    </header>

    <nav class="dc-nav" aria-label="工作台导航">
      <RouterLink to="/overview">总览</RouterLink>
      <RouterLink to="/departments">部门目录</RouterLink>
      <RouterLink to="/dictionaries">数据字典</RouterLink>
    </nav>

    <div v-if="!connected" class="dc-notice">
      当前为子应用独立预览。请从管理平台的“数据中心”菜单进入，以当前登录身份查询真实数据。
    </div>
    <div v-else-if="context?.dataSource === 'demo'" class="dc-notice">
      当前数据由独立示例宿主提供，仅用于验证微前端集成；未连接业务数据库或实时数据库。
    </div>
    <div v-if="error" class="dc-error" role="alert">{{ error }}</div>

    <div class="dc-toolbar">
      <p aria-live="polite">
        {{
          loading
            ? '正在加载…'
            : refreshedAt
              ? `更新于 ${refreshedAt}`
              : '尚未加载数据'
        }}
      </p>
      <button type="button" :disabled="loading || !connected" @click="refresh">
        刷新数据
      </button>
    </div>

    <div v-if="route.path === '/overview'" class="dc-metrics">
      <article>
        <p>部门</p>
        <strong>{{ refreshedAt ? departments.length : '—' }}</strong
        ><span>当前租户可用部门</span>
      </article>
      <article>
        <p>字典类型</p>
        <strong>{{ refreshedAt ? dictionaryTypes : '—' }}</strong
        ><span>已加载的类型数量</span>
      </article>
      <article>
        <p>字典数据</p>
        <strong>{{ refreshedAt ? dictionaries.length : '—' }}</strong
        ><span>统一业务数据定义</span>
      </article>
    </div>

    <div v-else class="dc-panel">
      <label class="dc-search">
        <span>{{
          route.path === '/departments' ? '搜索部门' : '搜索类型、名称或值'
        }}</span>
        <input v-model="search" type="search" placeholder="输入关键词" />
      </label>
      <div class="dc-table-wrap">
        <table v-if="route.path === '/departments'">
          <thead>
            <tr>
              <th>部门编号</th>
              <th>部门名称</th>
              <th>上级部门</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in visibleDepartments" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.name }}</td>
              <td>{{ item.parentId ?? '—' }}</td>
            </tr>
            <tr v-if="!visibleDepartments.length">
              <td colspan="3" class="dc-empty">
                {{ loading ? '正在加载…' : '暂无匹配部门' }}
              </td>
            </tr>
          </tbody>
        </table>
        <table v-else>
          <thead>
            <tr>
              <th>字典类型</th>
              <th>名称</th>
              <th>数据值</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in visibleDictionaries"
              :key="`${item.dictType}:${item.value}:${item.id}`"
            >
              <td>{{ item.dictType }}</td>
              <td>{{ item.label }}</td>
              <td>{{ item.value }}</td>
            </tr>
            <tr v-if="!visibleDictionaries.length">
              <td colspan="3" class="dc-empty">
                {{ loading ? '正在加载…' : '暂无匹配字典数据' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>
