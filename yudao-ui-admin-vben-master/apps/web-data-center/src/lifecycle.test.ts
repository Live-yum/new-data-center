import type {
  DataCenterBridge,
  DataCenterDept,
  DataCenterProps,
} from '@vben/micro-apps';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { mount, unmount, update } from './lifecycle';

function context(sessionKey: string): DataCenterProps['context'] {
  return { locale: 'zh-CN', sessionKey, tenantId: 1, userName: '管理员' };
}

describe('data center micro app', () => {
  let container: HTMLElement;
  beforeEach(() => {
    container = document.createElement('div');
    container.innerHTML = '<div id="data-center-root"></div>';
    document.body.append(container);
  });
  afterEach(async () => {
    await unmount();
    container.remove();
  });

  it('uses container-scoped mounting and memory routes without changing the host URL', async () => {
    const url = window.location.href;
    const onNavigate = vi.fn();
    await mount({
      container,
      context: context('one'),
      route: '/departments',
      onNavigate,
    });
    expect(container.textContent).toContain('搜索部门');
    await update({ route: '/dictionaries' });
    expect(container.textContent).toContain('搜索类型、名称或值');
    expect(window.location.href).toBe(url);
    expect(onNavigate).not.toHaveBeenCalled();
    await unmount();
    expect(
      container.querySelector('#data-center-root')?.childElementCount,
    ).toBe(0);
    await mount({ container, route: '/overview' });
    expect(container.querySelectorAll('.data-center-app')).toHaveLength(1);
  });

  it('aborts old tenant requests and prevents old results from replacing new tenant data', async () => {
    let oldSignal!: AbortSignal;
    let resolveOld!: (departments: DataCenterDept[]) => void;
    const oldDepartments = new Promise<DataCenterDept[]>((resolve) => {
      resolveOld = resolve;
    });
    const oldBridge: DataCenterBridge = {
      getDepartments: (signal) => {
        oldSignal = signal;
        return oldDepartments;
      },
      getDictionaries: async () => [],
    };
    await mount({
      container,
      context: context('old'),
      bridge: oldBridge,
      route: '/departments',
    });
    const newBridge: DataCenterBridge = {
      getDepartments: async () => [{ id: 2, name: '新租户部门' }],
      getDictionaries: async () => [],
    };
    await update({ context: context('new'), bridge: newBridge });
    await vi.waitFor(() =>
      expect(container.textContent).toContain('新租户部门'),
    );
    expect(oldSignal.aborted).toBe(true);
    resolveOld([{ id: 1, name: '旧租户部门' }]);
    await Promise.resolve();
    await Promise.resolve();
    expect(container.textContent).not.toContain('旧租户部门');
  });

  it('aborts requests when the micro app is unmounted', async () => {
    let signal!: AbortSignal;
    await mount({
      container,
      bridge: {
        getDepartments: (current) => {
          signal = current;
          return new Promise(() => {});
        },
        getDictionaries: async () => [],
      },
    });
    await unmount();
    expect(signal.aborted).toBe(true);
  });
});
