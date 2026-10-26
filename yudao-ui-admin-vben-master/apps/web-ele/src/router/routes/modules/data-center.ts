import type { RouteRecordRaw } from 'vue-router';

/** 登录后可访问；实际数据仍由既有后端接口进行权限和租户校验。 */
const routes: RouteRecordRaw[] = [
  {
    name: 'DataCenter',
    path: '/data-center',
    component: () => import('#/views/data-center/index.vue'),
    meta: {
      icon: 'lucide:database',
      keepAlive: false,
      order: 1,
      title: '数据中心',
    },
  },
];

export default routes;
