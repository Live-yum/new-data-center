<script setup lang="ts">
import { inject } from 'vue';
import { dataCenterKey } from './context';
const state = inject(dataCenterKey);
</script>
<template>
  <div class="dc-shell">
    <header class="dc-topbar">
      <strong>紫金桥 <small>IoT 数据空间</small></strong>
      <span class="dc-session"
        ><span class="dc-status">{{
          state?.props.context?.dataSource === 'demo'
            ? '演示数据'
            : state?.props.bridge
              ? '已连接管理平台'
              : '独立预览'
        }}</span>
        · {{ state?.props.context?.userName }} · 租户
        {{ state?.props.context?.tenantId ?? '未启用'
        }}<small v-if="state?.props.context?.dataSource === 'demo'">
          · 未连接实时库</small
        ></span
      >
    </header>
    <nav class="dc-global-nav dc-nav" aria-label="工作台导航">
      <RouterLink to="/dashboards">设备看板</RouterLink
      ><RouterLink to="/devices">设备与测点</RouterLink
      ><RouterLink to="/widgets">组件中心</RouterLink
      ><RouterLink to="/history">历史查询</RouterLink
      ><RouterLink to="/downloads">下载任务</RouterLink
      ><RouterLink to="/overview">总览</RouterLink
      ><RouterLink to="/departments">部门目录</RouterLink
      ><RouterLink to="/dictionaries">数据字典</RouterLink>
    </nav>
    <RouterView />
  </div>
</template>
