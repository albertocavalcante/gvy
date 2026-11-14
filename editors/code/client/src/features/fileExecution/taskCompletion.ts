// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

interface Disposable {
  dispose(): void;
}
interface TaskEvents<T, E> {
  onDidEndTaskProcess(
    listener: (event: { execution: E; exitCode?: number }) => void,
  ): Disposable;
  onDidEndTask(listener: (event: { execution: E }) => void): Disposable;
  executeTask(task: T): PromiseLike<E>;
}

/** Track the execution returned by VS Code, not another invocation of the same task. */
export function awaitTask<T, E>(
  api: TaskEvents<T, E>,
  task: T,
  started: (execution: E) => void,
  cancelled: () => boolean,
): Promise<boolean> {
  return new Promise((resolve, reject) => {
    let owned: E | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let settled = false;
    const pending: Array<{
      execution: E;
      exitCode?: number;
      process: boolean;
    }> = [];
    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      processListener.dispose();
      taskListener.dispose();
      resolve(ok && !cancelled());
    };
    const observe = (event: {
      execution: E;
      exitCode?: number;
      process: boolean;
    }) => {
      if (!owned) {
        pending.push(event);
        return;
      }
      if (event.execution !== owned) return;
      if (event.process) finish(event.exitCode === 0);
      else timer = setTimeout(() => finish(false), 250);
    };
    const processListener = api.onDidEndTaskProcess((event) =>
      observe({ ...event, process: true }),
    );
    const taskListener = api.onDidEndTask((event) =>
      observe({ ...event, process: false }),
    );
    api.executeTask(task).then(
      (execution) => {
        owned = execution;
        started(execution);
        for (const event of pending) observe(event);
      },
      (error: unknown) => {
        processListener.dispose();
        taskListener.dispose();
        reject(error);
      },
    );
  });
}
