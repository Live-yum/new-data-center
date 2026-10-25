import { mount } from './lifecycle';
import './style.css';

export { bootstrap, mount, unmount, update } from './lifecycle';

declare global {
  interface Window {
    __POWERED_BY_QIANKUN__?: boolean;
  }
}

if (!window.__POWERED_BY_QIANKUN__) {
  mount().catch((error: unknown) => console.error('数据中心启动失败', error));
}
