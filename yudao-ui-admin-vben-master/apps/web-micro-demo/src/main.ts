import { createApp } from 'vue';
import { createRouter, createWebHashHistory } from 'vue-router';

import App from './app.vue';
import './style.css';

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
});

const app = createApp(App);
app.use(router);
void router.isReady().then(() => app.mount('#app'));
