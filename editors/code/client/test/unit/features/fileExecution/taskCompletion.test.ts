// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import { awaitTask } from "../../../../src/features/fileExecution/taskCompletion";

describe("owned task completion", () => {
  it("correlates the actual execution and accepts process exit after task end", async () => {
    let processEnd: (event: {
      execution: object;
      exitCode?: number;
    }) => void = () => {};
    let taskEnd: (event: { execution: object }) => void = () => {};
    const execution = {};
    const api = {
      onDidEndTaskProcess: (fn: typeof processEnd) => {
        processEnd = fn;
        return { dispose() {} };
      },
      onDidEndTask: (fn: typeof taskEnd) => {
        taskEnd = fn;
        return { dispose() {} };
      },
      executeTask: async () => {
        queueMicrotask(() => {
          processEnd({ execution: {}, exitCode: 1 });
          taskEnd({ execution });
          processEnd({ execution, exitCode: 0 });
        });
        return execution;
      },
    };
    assert.strictEqual(
      await awaitTask(
        api,
        {},
        () => {},
        () => false,
      ),
      true,
    );
  });
  it("fails a task with no process exit event", async () => {
    let taskEnd: (event: { execution: object }) => void = () => {};
    const execution = {};
    const api = {
      onDidEndTaskProcess: () => ({ dispose() {} }),
      onDidEndTask: (fn: typeof taskEnd) => {
        taskEnd = fn;
        return { dispose() {} };
      },
      executeTask: async () => {
        queueMicrotask(() => taskEnd({ execution }));
        return execution;
      },
    };
    assert.strictEqual(
      await awaitTask(
        api,
        {},
        () => {},
        () => false,
      ),
      false,
    );
  });
});
