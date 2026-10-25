import { afterEach, describe, expect, it, vi } from 'vitest';

import { createDemoBridge } from './demo-bridge';

afterEach(() => vi.useRealTimers());

describe('演示数据桥接', () => {
  it('退出或切换会话时取消全部请求并释放计时器', async () => {
    vi.useFakeTimers();
    const lifetime = new AbortController();
    let pending = 0;
    const bridge = createDemoBridge({
      tenantId: 1,
      signal: lifetime.signal,
      onPending: (change) => {
        pending += change;
      },
    });
    const request = new AbortController();
    const results = Promise.allSettled([
      bridge.getDepartments(request.signal),
      bridge.getDictionaries(request.signal),
    ]);
    expect(pending).toBe(2);
    lifetime.abort();
    expect(pending).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    for (const result of await results) {
      expect(result.status).toBe('rejected');
      if (result.status === 'rejected')
        expect(result.reason.name).toBe('AbortError');
    }
    await expect(bridge.getDepartments(request.signal)).rejects.toMatchObject({
      name: 'AbortError',
    });
  });

  it('单个请求取消不会取消同会话的另一个请求，返回数据按租户隔离', async () => {
    vi.useFakeTimers();
    const pending = vi.fn();
    const bridge = createDemoBridge({
      tenantId: 2,
      signal: new AbortController().signal,
      onPending: pending,
    });
    const request = new AbortController();
    const departments = bridge
      .getDepartments(request.signal)
      .catch((error: Error) => error);
    const dictionaries = bridge.getDictionaries(new AbortController().signal);
    request.abort();
    await vi.runAllTimersAsync();
    expect(await departments).toMatchObject({ name: 'AbortError' });
    expect(await dictionaries).toContainEqual({
      dictType: 'demo_status',
      label: '演示乙 · 启用',
      value: '1',
    });
    expect(
      pending.mock.calls
        .flat()
        .reduce((sum: number, value: number) => sum + value, 0),
    ).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('故障模拟返回可见错误，且仍释放请求资源', async () => {
    vi.useFakeTimers();
    let pending = 0;
    const bridge = createDemoBridge({
      tenantId: 1,
      fail: true,
      signal: new AbortController().signal,
      onPending: (change) => {
        pending += change;
      },
    });
    const result = bridge
      .getDepartments(new AbortController().signal)
      .catch((error: Error) => error);
    await vi.runAllTimersAsync();
    expect(await result).toMatchObject({
      message: expect.stringContaining('演示请求失败'),
    });
    expect(pending).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });
});
