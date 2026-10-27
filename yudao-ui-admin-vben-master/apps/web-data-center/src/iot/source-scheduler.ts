import type { IotDevice, IotQuery } from '@vben/micro-apps';
import { deviceSourceId } from './source-model';

/** One lane per independent source, shared across polls for a component lifetime. */
export function createSourceScheduler(concurrency = 2) {
  if (!Number.isInteger(concurrency) || concurrency < 1)
    throw new Error('Source concurrency must be a positive integer');
  type Job = { start: () => void };
  type Lane = { active: number; queue: Job[] };
  const lanes = new Map<string, Lane>();
  const aborted = () =>
    new DOMException('The request was aborted', 'AbortError');
  function drain(sourceId: string, lane: Lane) {
    while (lane.active < concurrency && lane.queue.length)
      lane.queue.shift()!.start();
    if (!lane.active && !lane.queue.length && lanes.get(sourceId) === lane)
      lanes.delete(sourceId);
  }
  return {
    run<T>(
      sourceId: string,
      signal: AbortSignal,
      work: () => Promise<T>,
    ): Promise<T> {
      if (signal.aborted) return Promise.reject(aborted());
      let lane = lanes.get(sourceId);
      if (!lane) {
        lane = { active: 0, queue: [] };
        lanes.set(sourceId, lane);
      }
      const current = lane;
      return new Promise<T>((resolve, reject) => {
        let started = false;
        const cancel = () => {
          // Queued work never reaches the transport after cancellation. Running
          // work retains its slot until the transport actually settles, even if
          // it ignores AbortSignal; a replacement poll cannot exceed the limit.
          if (!started) {
            const index = current.queue.indexOf(job);
            if (index >= 0) current.queue.splice(index, 1);
          }
          signal.removeEventListener('abort', cancel);
          reject(aborted());
          drain(sourceId, current);
        };
        const job: Job = {
          start() {
            if (signal.aborted) {
              cancel();
              return;
            }
            started = true;
            current.active++;
            void Promise.resolve()
              .then(() => {
                if (signal.aborted) throw aborted();
                return work();
              })
              .then(
                (value) =>
                  signal.aborted ? reject(aborted()) : resolve(value),
                reject,
              )
              .finally(() => {
                signal.removeEventListener('abort', cancel);
                current.active--;
                drain(sourceId, current);
              });
          },
        };
        signal.addEventListener('abort', cancel, { once: true });
        current.queue.push(job);
        drain(sourceId, current);
      });
    },
  };
}

/** Preserve the exact window/aggregation while avoiding cross-source queue coupling. */
export function splitQueryBySource(query: IotQuery, devices: IotDevice[]) {
  const groups = new Map<string, IotQuery>();
  for (const id of query.deviceIds) {
    const device = devices.find((item) => item.id === id);
    if (!device) throw new Error(`设备 ${id} 已不可用，无法确定绑定数据源`);
    const sourceId = deviceSourceId(device);
    let group = groups.get(sourceId);
    if (!group) {
      group = { ...query, deviceIds: [], metricKeys: [...query.metricKeys] };
      groups.set(sourceId, group);
    }
    group.deviceIds.push(id);
  }
  return groups;
}
