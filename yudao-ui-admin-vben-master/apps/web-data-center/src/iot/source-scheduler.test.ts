import { describe, expect, it, vi } from 'vitest';
import { createSourceScheduler, splitQueryBySource } from './source-scheduler';
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { resolve, promise };
}
async function settle() {
  for (let i = 0; i < 15; i++) await Promise.resolve();
}
describe('source-keyed bounded read dispatch', () => {
  it('dispatches all 50 jobs FIFO with at most two active requests for a source', async () => {
    const scheduler = createSourceScheduler();
    const signal = new AbortController().signal;
    let active = 0;
    let maximum = 0;
    const starts: number[] = [];
    const gates = Array.from({ length: 50 }, () => deferred<number>());
    const jobs = gates.map((gate, index) =>
      scheduler.run('a', signal, async () => {
        starts.push(index);
        maximum = Math.max(maximum, ++active);
        const result = await gate.promise;
        active--;
        return result;
      }),
    );
    await settle();
    expect(starts).toEqual([0, 1]);
    for (let index = 0; index < gates.length; index++) {
      gates[index]!.resolve(index);
      await settle();
    }
    expect(await Promise.all(jobs)).toEqual(
      Array.from({ length: 50 }, (_, index) => index),
    );
    expect(starts).toEqual(Array.from({ length: 50 }, (_, index) => index));
    expect(maximum).toBe(2);
  });
  it('lets healthy source A finish while B is blocked, without sharing its two slots', async () => {
    const scheduler = createSourceScheduler();
    const controller = new AbortController();
    const blocked = [deferred<number>(), deferred<number>()];
    let startsB = 0;
    const pendingB = Array.from({ length: 5 }, () =>
      scheduler.run('b', controller.signal, () => blocked[startsB++]!.promise),
    );
    const observedB = Promise.allSettled(pendingB);
    const healthy = Array.from({ length: 8 }, (_, index) =>
      scheduler.run('a', controller.signal, async () => index),
    );
    expect(await Promise.all(healthy)).toHaveLength(8);
    expect(startsB).toBe(2);
    controller.abort();
    expect((await observedB).every((item) => item.status === 'rejected')).toBe(
      true,
    );
    blocked.forEach((gate) => gate.resolve(1));
    await settle();
    expect(startsB).toBe(2);
  });
  it('rejects queued work on abort, retains running slots until settlement, and never starts old session jobs', async () => {
    const scheduler = createSourceScheduler();
    const old = new AbortController();
    const fresh = new AbortController();
    const gates = [deferred<number>(), deferred<number>()];
    let starts = 0;
    const jobs = Array.from({ length: 6 }, () =>
      scheduler.run('a', old.signal, () => gates[starts++]!.promise),
    );
    const cancelled = Promise.allSettled(jobs);
    await settle();
    old.abort();
    expect((await cancelled).every((item) => item.status === 'rejected')).toBe(
      true,
    );
    const replacement = vi.fn(async () => 87);
    const next = scheduler.run('a', fresh.signal, replacement);
    await settle();
    expect(replacement).not.toHaveBeenCalled();
    gates[0]!.resolve(1);
    await settle();
    expect(await next).toBe(87);
    gates[1]!.resolve(1);
    await settle();
    expect(starts).toBe(2);
    expect(replacement).toHaveBeenCalledTimes(1);
  });
  it('never invokes transport for an already aborted job or one cancelled before dispatch microtask', async () => {
    const scheduler = createSourceScheduler();
    const controller = new AbortController();
    const work = vi.fn(async () => 1);
    const pending = scheduler.run('a', controller.signal, work);
    const observed = pending.catch((cause: Error) => cause.name);
    controller.abort();
    expect(await observed).toBe('AbortError');
    await expect(
      scheduler.run('a', controller.signal, work),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(work).not.toHaveBeenCalled();
  });
  it('releases a failed worker for later jobs instead of poisoning a source lane', async () => {
    const scheduler = createSourceScheduler(1);
    const signal = new AbortController().signal;
    const failed = scheduler.run('a', signal, async () => {
      throw new Error('upstream');
    });
    const next = scheduler.run('a', signal, async () => 42);
    await expect(failed).rejects.toThrow('upstream');
    expect(await next).toBe(42);
  });
  it('splits independent source device sets while preserving exact window, interval and aggregation', () => {
    const query = {
      deviceIds: [1, 2, 3],
      metricKeys: ['pressure'],
      aggregation: 'AVG' as const,
      startTime: '2026-10-27T01:00:00Z',
      endTime: '2026-10-27T02:00:00Z',
      intervalMs: 60000,
    };
    const groups = splitQueryBySource(query, [
      { id: 1, name: 'same', code: '1', sourceId: 'a' },
      { id: 2, name: 'same', code: '2', sourceId: 'b' },
      { id: 3, name: 'legacy', code: '3' },
    ]);
    expect([...groups.keys()]).toEqual(['a', 'b', 'default']);
    expect(groups.get('a')).toEqual({ ...query, deviceIds: [1] });
    expect(groups.get('b')).toEqual({ ...query, deviceIds: [2] });
    expect(query.deviceIds).toEqual([1, 2, 3]);
    expect(() => splitQueryBySource(query, [])).toThrow('设备 1 已不可用');
  });
});
