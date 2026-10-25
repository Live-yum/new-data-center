interface MicroAppHandle<Props> {
  mountPromise: Promise<unknown>;
  unmount: () => Promise<unknown>;
  update?: (props: Props) => Promise<unknown>;
}

/** 串行化挂载、更新和卸载，避免快速切页时未完成挂载的应用遗留在页面上。 */
export function createMicroAppController<Props>(
  load: () => MicroAppHandle<Props>,
) {
  let app: MicroAppHandle<Props> | undefined;
  let disposed = false;
  let queue = Promise.resolve();

  function enqueue(action: () => Promise<void>) {
    const result = queue.then(action);
    // 一次加载失败不应阻塞后续卸载。
    queue = result.catch(() => {});
    return result;
  }

  return {
    mount: () =>
      enqueue(async () => {
        if (disposed || app) return;
        app = load();
        await app.mountPromise;
      }),
    update: (props: Props) =>
      enqueue(async () => {
        if (!disposed) await app?.update?.(props);
      }),
    unmount: () => {
      disposed = true;
      return enqueue(async () => {
        const current = app;
        app = undefined;
        if (current) await current.unmount();
      });
    },
  };
}
