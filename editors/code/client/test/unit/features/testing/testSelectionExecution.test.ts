// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as sinon from "sinon";
import { EventEmitter } from "events";
import { PassThrough } from "stream";
import proxyquire from "proxyquire";
import type { TestSelectionDescriptor } from "../../../../src/features/testing/testTarget";

interface Item {
  id: string;
  parent?: Item;
  uri?: { toString(): string };
  children: { forEach(callback: (item: Item) => void): void };
}

describe("test selection execution boundary", () => {
  const sandbox = sinon.createSandbox();
  let descriptors: Map<Item, TestSelectionDescriptor<Item>>;
  let roots: Item[];
  let getTestCommand: sinon.SinonStub;
  let spawn: sinon.SinonStub;
  let createdConsumers: sinon.SinonSpy;
  let requestToken: { isCancellationRequested: boolean };
  let runToken: { isCancellationRequested: boolean };
  let run: Record<string, sinon.SinonStub> & { token: typeof runToken };
  let controller: unknown;
  let runner: {
    runTests(
      request: unknown,
      token: unknown,
      controller: unknown,
    ): Promise<void>;
    runTestsWithCoverage(
      request: unknown,
      token: unknown,
      controller: unknown,
      coverage: unknown,
    ): Promise<void>;
  };

  function suite(name: string, tests = ["first", "second"]) {
    const uri = `file:///suite-${roots.length}.groovy`;
    const children: Item[] = [];
    const item: Item = {
      id: `opaque suite ${roots.length}`,
      uri: { toString: () => uri },
      children: { forEach: (callback) => children.forEach(callback) },
    };
    for (const test of tests) {
      const child: Item = {
        id: `opaque child ${children.length}`,
        parent: item,
        uri: item.uri,
        children: { forEach: () => {} },
      };
      descriptors.set(child, { kind: "feature", test });
      children.push(child);
    }
    descriptors.set(item, {
      kind: "suite",
      suite: name,
      completeChildren: children,
    });
    roots.push(item);
    return { item, children };
  }

  beforeEach(() => {
    descriptors = new Map();
    roots = [];
    requestToken = {
      isCancellationRequested: false,
      ...{ onCancellationRequested: () => ({ dispose() {} }) },
    };
    runToken = { isCancellationRequested: false };
    const token = {
      ...runToken,
      onCancellationRequested: () => ({ dispose() {} }),
    };
    runToken = token;
    run = Object.assign(
      Object.fromEntries(
        ["enqueued", "errored", "appendOutput", "end"].map((name) => [
          name,
          sandbox.stub(),
        ]),
      ),
      { token },
    );
    controller = {
      items: {
        forEach: (callback: (item: Item) => void) => roots.forEach(callback),
      },
      createTestRun: () => run,
    };
    getTestCommand = sandbox.stub().resolves({
      executable: "/w/gradlew",
      args: ["test"],
      cwd: "/w",
      env: {},
    });
    spawn = sandbox.stub().callsFake(() => {
      const process = Object.assign(new EventEmitter(), {
        pid: 123,
        stdout: new PassThrough(),
        stderr: new PassThrough(),
      });
      setImmediate(() => {
        process.stdout.end();
        process.stderr.end();
        process.emit("close", 0);
      });
      return process;
    });
    createdConsumers = sandbox.spy();
    const vscode = {
      workspace: {
        workspaceFolders: [],
        getConfiguration: () => ({ get: () => undefined }),
      },
      window: { showWarningMessage: sandbox.stub().resolves() },
      TestMessage: class {
        constructor(public message: string) {}
      },
    };
    const { LSPTestExecutionService } = proxyquire.noCallThru()(
      "../../../../src/features/testing/LSPTestExecutionService",
      {
        vscode,
        child_process: { spawn },
        "./TestEventConsumer": {
          TestEventConsumer: class {
            constructor() {
              createdConsumers();
            }
            registerTestItem() {}
            clear() {}
          },
        },
      },
    );
    runner = new LSPTestExecutionService(
      { getTestCommand },
      { appendLine: sandbox.stub() },
      "/extension",
      (item: Item) => descriptors.get(item) ?? { kind: "unrepresentable" },
    );
  });

  afterEach(() => sandbox.restore());

  it("executes only the remaining discovered feature, using raw server names", async () => {
    const { item, children } = suite("example.Dotted.Spec", [
      "excluded.name",
      "literal feature.name",
    ]);
    await runner.runTests(
      { include: [item], exclude: [children[0]] },
      requestToken,
      controller,
    );
    assert.deepStrictEqual(
      getTestCommand.args.map((args) => args.slice(1)),
      [["example.Dotted.Spec", "literal feature.name"]],
    );
    assert.ok(spawn.calledOnce);
    assert.ok(run.end.calledOnce);
  });

  it("coalesces overlapping suites, features and duplicate includes", async () => {
    const { item, children } = suite("example.Spec");
    await runner.runTests(
      { include: [children[0], item, item] },
      requestToken,
      controller,
    );
    assert.ok(getTestCommand.calledOnce);
    assert.deepStrictEqual(getTestCommand.firstCall.args.slice(1), [
      "example.Spec",
      undefined,
    ]);
    assert.ok(spawn.calledOnce);
    assert.ok(run.end.calledOnce);
  });

  it("makes no command request for a feature excluded by its suite", async () => {
    const { item, children } = suite("example.Spec");
    await runner.runTests(
      { include: [children[0]], exclude: [item] },
      requestToken,
      controller,
    );
    assert.ok(getTestCommand.notCalled);
    assert.ok(spawn.notCalled);
    assert.ok(run.end.calledOnce);
  });

  it("refuses the entire mixed request when a later runtime row is unrepresentable", async () => {
    const first = suite("example.First");
    const second = suite("example.Second");
    const row: Item = {
      ...second.children[0],
      id: "iteration",
      parent: second.children[0],
    };
    await runner.runTests(
      { include: [first.item, row] },
      requestToken,
      controller,
    );
    assert.ok(getTestCommand.notCalled);
    assert.ok(spawn.notCalled);
    assert.ok(run.appendOutput.called);
    assert.ok(run.end.calledOnce);
  });

  for (const failure of ["missing", "error"] as const) {
    it(`does not spawn earlier selections when a later command is ${failure}`, async () => {
      const first = suite("example.First");
      const second = suite("example.Second");
      if (failure === "missing") getTestCommand.onSecondCall().resolves(null);
      else
        getTestCommand
          .onSecondCall()
          .rejects(new Error("Unsupported selector"));
      await runner.runTests(
        { include: [first.item, second.item] },
        requestToken,
        controller,
      );
      assert.ok(spawn.notCalled);
      assert.ok(run.appendOutput.called);
      assert.ok(run.end.calledOnce);
    });
  }

  for (const which of ["request", "run"] as const) {
    it(`does not spawn when the ${which} token cancels during command lookup`, async () => {
      const { item } = suite("example.Spec");
      getTestCommand.callsFake(async () => {
        (which === "request"
          ? requestToken
          : runToken
        ).isCancellationRequested = true;
        return { executable: "/w/gradlew", args: ["test"], cwd: "/w", env: {} };
      });
      await runner.runTests({ include: [item] }, requestToken, controller);
      assert.ok(spawn.notCalled);
      assert.ok(run.end.calledOnce);
    });
  }

  it("does not fetch coverage for explicit empty selection", async () => {
    suite("example.Spec");
    const addCoverageToRun = sandbox.stub().resolves();
    await runner.runTestsWithCoverage(
      { include: [] },
      requestToken,
      controller,
      { addCoverageToRun },
    );
    assert.ok(getTestCommand.notCalled);
    assert.ok(spawn.notCalled);
    assert.ok(addCoverageToRun.notCalled);
    assert.ok(run.end.calledOnce);
  });

  it("runs every current root when include is undefined", async () => {
    suite("example.First");
    suite("example.Second");
    await runner.runTests({}, requestToken, controller);
    assert.deepStrictEqual(
      getTestCommand.args.map((args) => args.slice(1)),
      [
        ["example.First", undefined],
        ["example.Second", undefined],
      ],
    );
    assert.equal(spawn.callCount, 2);
    assert.ok(run.end.calledOnce);
  });

  it("refuses exclusion expansion from an incomplete suite without changing excluded status", async () => {
    const { item, children } = suite("example.Spec");
    descriptors.set(item, {
      kind: "suite",
      suite: "example.Spec",
      completeChildren: undefined,
    });
    await runner.runTests(
      { include: [item], exclude: [children[0]] },
      requestToken,
      controller,
    );
    assert.ok(getTestCommand.notCalled);
    assert.ok(spawn.notCalled);
    assert.ok(run.errored.notCalled);
    assert.ok(run.appendOutput.called);
    assert.ok(run.end.calledOnce);
  });

  it("validates every source URI before requesting the first command", async () => {
    const first = suite("example.First");
    const second = suite("example.Second");
    delete second.item.uri;
    await runner.runTests(
      { include: [first.item, second.item] },
      requestToken,
      controller,
    );
    assert.ok(getTestCommand.notCalled);
    assert.ok(spawn.notCalled);
    assert.ok(run.end.calledOnce);
  });

  it("validates every Windows invocation before spawning the first command", async () => {
    sandbox.stub(process, "platform").value("win32");
    const first = suite("example.First");
    const second = suite("example.Second");
    getTestCommand.onFirstCall().resolves({
      executable: "C:\\w\\gradlew.bat",
      args: ["test"],
      cwd: "C:\\w",
      env: {},
    });
    getTestCommand.onSecondCall().resolves({
      executable: "C:\\w\\gradlew.bat",
      args: ["test", "--tests", "bad%name"],
      cwd: "C:\\w",
      env: {},
    });
    await runner.runTests(
      { include: [first.item, second.item] },
      requestToken,
      controller,
    );
    assert.ok(spawn.notCalled);
    assert.ok(run.errored.calledOnceWith(second.item));
    assert.ok(run.end.calledOnce);
  });

  it("ends once without command requests when planning throws", async () => {
    const { item } = suite("example.Spec");
    sandbox.stub(descriptors, "get").throws(new Error("metadata failure"));
    await runner.runTests({ include: [item] }, requestToken, controller);
    assert.ok(getTestCommand.notCalled);
    assert.ok(spawn.notCalled);
    assert.ok(run.appendOutput.called);
    assert.ok(run.end.calledOnce);
  });

  it("ends an already cancelled run without requesting commands", async () => {
    const { item } = suite("example.Spec");
    requestToken.isCancellationRequested = true;
    await runner.runTests({ include: [item] }, requestToken, controller);
    assert.ok(getTestCommand.notCalled);
    assert.ok(spawn.notCalled);
    assert.ok(run.end.calledOnce);
  });

  for (const which of ["request", "run"] as const) {
    for (const lateOutcome of ["resolve", "reject"] as const) {
      it(`ends promptly on ${which} cancellation before a lookup later ${lateOutcome}s`, async () => {
        const { item } = suite("example.Spec");
        const token = which === "request" ? requestToken : runToken;
        const listeners = new Set<() => void>();
        Object.assign(token, {
          onCancellationRequested: (listener: () => void) => {
            listeners.add(listener);
            return { dispose: () => listeners.delete(listener) };
          },
        });
        let releaseLookup = () => {};
        getTestCommand.returns(
          new Promise((resolve, reject) => {
            releaseLookup = () =>
              lateOutcome === "resolve"
                ? resolve({
                    executable: "/w/gradlew",
                    args: ["test"],
                    cwd: "/w",
                    env: {},
                  })
                : reject(new Error("Late command failure after cancellation"));
          }),
        );
        const pending = runner.runTests(
          { include: [item] },
          requestToken,
          controller,
        );
        let endedBeforeLookup: boolean;
        try {
          await new Promise<void>((resolve) => setImmediate(resolve));
          assert.ok(getTestCommand.calledOnce);
          token.isCancellationRequested = true;
          listeners.forEach((listener) => listener());
          await new Promise<void>((resolve) => setImmediate(resolve));
          endedBeforeLookup = run.end.calledOnce;
        } finally {
          releaseLookup();
          await pending;
        }
        assert.ok(
          endedBeforeLookup,
          "Stop must end the run without waiting for an unresponsive lookup",
        );
        assert.ok(spawn.notCalled);
        assert.ok(run.enqueued.notCalled);
        assert.ok(createdConsumers.notCalled);
        assert.equal(
          listeners.size,
          0,
          "cancellation subscriptions must be released",
        );
        assert.ok(run.end.calledOnce);
      });
    }
  }
});
