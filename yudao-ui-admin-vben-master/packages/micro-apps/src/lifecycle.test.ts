import { describe, expect, it, vi } from 'vitest';

import { createMicroAppController } from './lifecycle';
import { normalizeDataCenterRoute } from './types';

describe('micro app lifecycle', () => {
  it('waits for an in-flight mount before unmounting and ignores stale updates', async () => {
    let finishMount!: () => void;
    const handle = {
      mountPromise: new Promise<void>((resolve) => {
        finishMount = resolve;
      }),
      unmount: vi.fn().mockResolvedValue(undefined),
      update: vi.fn().mockResolvedValue(undefined),
    };
    const controller = createMicroAppController(() => handle);
    const mounting = controller.mount();
    await Promise.resolve();
    const updating = controller.update({ tenantId: 2 });
    const unmounting = controller.unmount();
    expect(handle.unmount).not.toHaveBeenCalled();
    finishMount();
    await Promise.all([mounting, updating, unmounting]);
    expect(handle.update).not.toHaveBeenCalled();
    expect(handle.unmount).toHaveBeenCalledOnce();
  });

  it('does not mount a view disposed before the loading task starts', async () => {
    const load = vi.fn();
    const controller = createMicroAppController(load);
    await Promise.all([controller.mount(), controller.unmount()]);
    expect(load).not.toHaveBeenCalled();
  });

  it('handles loading and bootstrap rejections and still cleans up after failure', async () => {
    const unmount = vi.fn().mockResolvedValue(undefined);
    const controller = createMicroAppController(() => ({
      loadPromise: Promise.reject(new Error('entry unavailable')),
      bootstrapPromise: Promise.reject(new Error('entry unavailable')),
      mountPromise: Promise.reject(new Error('entry unavailable')),
      unmount,
    }));
    await expect(controller.mount()).rejects.toThrow('entry unavailable');
    await controller.unmount();
    expect(unmount).toHaveBeenCalledOnce();
  });

  it('limits child navigation to its own routes', () => {
    expect(normalizeDataCenterRoute('/departments')).toBe('/departments');
    expect(normalizeDataCenterRoute('/system/user')).toBe('/overview');
    expect(normalizeDataCenterRoute('//example.com')).toBe('/overview');
  });
});
