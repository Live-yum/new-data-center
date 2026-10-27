import type { DataCenterProps, IotBridge, IotSource } from '@vben/micro-apps';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { mount, unmount, update } from '../lifecycle';
const source: IotSource = {
  id: 'site-a',
  name: '现场甲',
  type: 'REALDB',
  enabled: true,
  configured: true,
  status: 'CONFIGURED',
  timeZone: 'Asia/Shanghai',
  description: '独立只读源',
};
let container: HTMLElement;
function props(
  sources: IotBridge['sources'],
  sessionKey = 'one',
): DataCenterProps {
  return {
    container,
    route: '/sources',
    context: {
      dataSource: 'platform',
      tenantId: sessionKey === 'one' ? 1 : 2,
      sessionKey,
      locale: 'zh-CN',
      userName: '测试',
    },
    bridge: {
      getDepartments: async () => [],
      getDictionaries: async () => [],
      iot: { sources } as IotBridge,
    },
  };
}
async function settle() {
  for (let i = 0; i < 40; i++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  container = document.createElement('div');
  container.innerHTML = '<div id="data-center-root"></div>';
  document.body.append(container);
});
afterEach(async () => {
  await unmount();
  container.remove();
  vi.restoreAllMocks();
});
describe('tenant-scoped read-only source catalog', () => {
  it('shows independent source IDs, disabled configuration and no editable connection or credentials', async () => {
    await mount(
      props(
        vi.fn(async () => [
          source,
          {
            ...source,
            id: 'site-b',
            name: '现场乙',
            enabled: false,
            status: 'DISABLED' as const,
          },
        ]),
      ),
    );
    await settle();
    expect(
      container.querySelector('[data-source-id="site-a"]')!.textContent,
    ).toContain('连接待实际读取验证');
    expect(
      container.querySelector('[data-source-id="site-b"]')!.textContent,
    ).toContain('未启用');
    expect(container.querySelectorAll('input')).toHaveLength(0);
    expect(container.textContent).toContain('设备的数据源创建后不可修改');
  });
  it('aborts old tenant catalog and ignores a late response that does not honor abort', async () => {
    let done!: (value: IotSource[]) => void;
    let oldSignal!: AbortSignal;
    await mount(
      props(
        vi.fn((signal) => {
          oldSignal = signal;
          return new Promise<IotSource[]>((resolve) => {
            done = resolve;
          });
        }),
      ),
    );
    await settle();
    await update(
      props(
        vi.fn(async () => [{ ...source, id: 'only-b', name: '新租户可见源' }]),
        'two',
      ),
    );
    await settle();
    done([source]);
    await settle();
    expect(oldSignal.aborted).toBe(true);
    expect(container.textContent).toContain('新租户可见源');
    expect(container.textContent).not.toContain('现场甲');
  });
  it('clears stale catalog after a denied refresh and presents the error without a fake fallback', async () => {
    let denied = false;
    const list = vi.fn(async () => {
      if (denied) throw new Error('当前租户无权读取数据源目录');
      return [source];
    });
    await mount(props(list));
    await settle();
    denied = true;
    container.querySelector<HTMLButtonElement>('.pr-actions button')!.click();
    await settle();
    expect(container.querySelector('[data-source-id="site-a"]')).toBeNull();
    expect(container.querySelector('[role="alert"]')!.textContent).toContain(
      '无权读取',
    );
  });
});
